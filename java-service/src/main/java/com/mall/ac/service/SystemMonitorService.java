package com.mall.ac.service;

import com.mall.ac.algorithm.PumpSpeedController;
import com.mall.ac.config.DrainageProperties;
import com.mall.ac.entity.AcDevice;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class SystemMonitorService {

    @Lazy private final AsyncBatchProcessor asyncProcessor;
    private final DrainageProperties props;
    private final PumpSpeedController speedController;

    private static final double RING_HIGH_WATERMARK = 0.85;
    private static final double RING_CRITICAL_WATERMARK = 0.97;
    private static final int HIGH_DROP_THRESHOLD_PER_MIN = 1000;
    private static final long CIRCUIT_BREAKER_MS = 60_000;

    private long lastHighUsageTs = 0;
    private long circuitOpenUntil = 0;
    private long prevDropped = 0;
    private long prevReceived = 0;

    @PostConstruct
    public void init() {
        log.info("SystemMonitorService initialized - scheduled checks every 5s");
    }

    @Scheduled(fixedDelay = 5000)
    public void monitorHealth() {
        try {
            Map<String, Object> stats = asyncProcessor.getStats();
            int ringSize = (Integer) stats.get("ringSize");
            int ringCap = (Integer) stats.get("ringCapacity");
            long received = (Long) stats.get("totalReceived");
            long dropped = (Long) stats.get("totalDropped");

            double usage = ringSize * 1.0 / ringCap;
            long deltaDropped = dropped - prevDropped;
            long deltaReceived = Math.max(1, received - prevReceived);
            double dropRate = deltaDropped * 100.0 / deltaReceived;

            checkRingUsage(usage, ringSize, ringCap);
            checkDropRate(deltaDropped, dropRate, deltaReceived);
            checkDeviceAlarms();

            prevDropped = dropped;
            prevReceived = received;

        } catch (Exception e) {
            log.error("Monitor health check exception: {}", e.getMessage());
        }
    }

    private void checkRingUsage(double usage, int size, int cap) {
        long now = System.currentTimeMillis();

        if (usage >= RING_CRITICAL_WATERMARK) {
            log.error("""
                **********************************************
                [CIRCUIT-BREAKER] Ring buffer CRITICAL!
                usage={}/{} ({:.1f}%)
                Stopping new DB writes to prevent OOM!
                **********************************************
                """.formatted(size, cap, usage * 100));
            circuitOpenUntil = now + CIRCUIT_BREAKER_MS;
        } else if (usage >= RING_HIGH_WATERMARK) {
            if (now - lastHighUsageTs > 15000) {
                log.warn("""
                [ALARM] Ring buffer HIGH for > 15s
                usage={}/{} ({:.1f}%)
                Java DB writer is lagging - consider scaling DB pool!
                """.formatted(size, cap, usage * 100));
                lastHighUsageTs = now;
            }
        }
    }

    private void checkDropRate(long deltaDropped, double dropRate, long deltaReceived) {
        if (deltaDropped > HIGH_DROP_THRESHOLD_PER_MIN) {
            log.error("""
                [DATA-LOSS-ALARM] {} items dropped in 5s!
                drop rate: {:.2f}% of {} received
                Go gateway is dropping data due to Java backpressure.
                PLEASE CHECK:
                  1. DB connection pool size
                  2. Slow query on level_history table
                  3. Slope calculation CPU load
                """.formatted(deltaDropped, dropRate, deltaReceived));
        } else if (dropRate > 5.0 && deltaReceived > 100) {
            log.warn("[DATA-LOSS] drop rate = {:.2f}% ({} dropped / {} received)",
                    dropRate, deltaDropped, deltaReceived);
        }
    }

    private void checkDeviceAlarms() {
        List<AcDevice> devices = asyncProcessor.findAllDevices();

        List<String> overflow = new ArrayList<>();
        List<String> danger = new ArrayList<>();
        List<String> offlineSuspect = new ArrayList<>();

        long fiveMinAgo = System.currentTimeMillis() - 5L * 60 * 1000;

        for (AcDevice d : devices) {
            switch (d.getStatus()) {
                case "OVERFLOW" -> overflow.add(d.getDeviceId() + "@" + d.getLocation()
                        + "(level=" + d.getCurrentLevelMm().intValue() + "mm)");
                case "DANGER" -> danger.add(d.getDeviceId() + "@" + d.getLocation()
                        + "(level=" + d.getCurrentLevelMm().intValue() + "mm)");
                default -> {}
            }
            if (d.getLastUpdateTime() != null) {
                long lastUpd = d.getLastUpdateTime().atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
                if (lastUpd < fiveMinAgo && !"OFFLINE".equals(d.getStatus())) {
                    offlineSuspect.add(d.getDeviceId());
                }
            }
        }

        if (!overflow.isEmpty()) {
            log.error("""
                ======================= [OVERFLOW! =======================
                !! CONDENSATE OVERFLOW - CEILING LEAK IMMINENT !!
                Devices at OVERFLOW level (> {:.0f}mm):
                {}
                Action required: Dispatch maintenance immediately!
                ==========================================================
                """.formatted(props.getOverflowLevelMm(), String.join("\n  ", overflow)));
        }

        if (!danger.isEmpty()) {
            log.warn("""
                ^^^^^^^^^^^^ [DANGER LEVEL] ^^^^^^^^^^^^
                Devices approaching overflow (> {:.0f}mm):
                {}
                """.formatted(props.getDangerLevelMm(), String.join("\n  ", danger)));
        }

        if (offlineSuspect.size() > devices.size() / 3 && !devices.isEmpty()) {
            log.warn("[GATEWAY-OFFLINE?] {}/{} devices silent > 5min: {}",
                    offlineSuspect.size(), devices.size(),
                    offlineSuspect.stream().limit(5).toList());
        }
    }

    @Scheduled(cron = "0 * * * * *")
    public void perMinuteSummary() {
        Map<String, Object> stats = asyncProcessor.getStats();
        List<AcDevice> devices = asyncProcessor.findAllDevices();

        int activePumps = 0;
        double maxLevel = 0;
        String maxDevice = "-";
        Map<String, Integer> statusCounts = new HashMap<>();

        for (AcDevice d : devices) {
            statusCounts.merge(d.getStatus(), 1, Integer::sum);
            if (d.getCurrentPumpSpeed() != null && d.getCurrentPumpSpeed() > 0) activePumps++;
            if (d.getCurrentLevelMm() != null && d.getCurrentLevelMm() > maxLevel) {
                maxLevel = d.getCurrentLevelMm();
                maxDevice = d.getDeviceId();
            }
        }

        PumpSpeedController.BusinessPhase phase =
                speedController.resolveBusinessPhase(0.0);
        String phaseLabel = switch (phase) {
            case BUSINESS_HOURS -> "🤫 BUSINESS (noise-limit @" + props.getBusinessMaxSpeedPercent() + "%)";
            case AFTER_HOURS -> "🌙 AFTER-HOURS (full-power @" + props.getMaxSpeedPercent() + "%)";
            case EMERGENCY_OVERRIDE -> "🚨 EMERGENCY OVERRIDE";
        };

        log.info("""
            ┌───────── 1min STATUS SUMMARY ─────────┐
            Phase: {}
            Ring: {}/{} ({}) | recv={} proc={} written={} dropped={}
            Devices: {} total | {} pumps active | max={:.1f}mm ({})
            Status: {}
            └───────────────────────────────────────┘
            """.formatted(
                phaseLabel,
                stats.get("ringSize"), stats.get("ringCapacity"), stats.get("ringUsagePct"),
                stats.get("totalReceived"), stats.get("totalProcessed"),
                stats.get("totalWrittenDb"), stats.get("totalDropped"),
                devices.size(), activePumps, maxLevel, maxDevice,
                statusCounts
        ));
    }

    public boolean isCircuitOpen() {
        return System.currentTimeMillis() < circuitOpenUntil;
    }

    @PreDestroy
    public void shutdown() {
        log.info("SystemMonitor shutdown - final stats: {}", asyncProcessor.getStats());
    }
}
