package com.mall.ac.service;

import com.mall.ac.algorithm.LevelSlopeCalculator;
import com.mall.ac.algorithm.PumpSpeedController;
import com.mall.ac.config.DrainageProperties;
import com.mall.ac.entity.AcDevice;
import com.mall.ac.entity.LevelHistory;
import com.mall.ac.entity.PumpControlLog;
import com.mall.ac.repository.AcDeviceRepository;
import com.mall.ac.repository.LevelHistoryRepository;
import com.mall.ac.repository.PumpControlLogRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.context.annotation.Lazy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncBatchProcessor {

    private final AcDeviceRepository deviceRepo;
    private final LevelHistoryRepository historyRepo;
    private final PumpControlLogRepository controlLogRepo;
    private final LevelSlopeCalculator slopeCalculator;
    private final PumpSpeedController speedController;
    private final DrainageProperties props;
    private final DrainageService drainageService;
    @Lazy private final SystemMonitorService monitorService;

    private static final int RING_CAPACITY = 8192;
    private static final int BATCH_FLUSH_SIZE = 512;
    private static final int BATCH_FLUSH_MS = 100;
    private static final int DEVICE_DEDUP_WINDOW_MS = 100;

    private final BlockingQueue<LevelProcessEntry> ring =
            new ArrayBlockingQueue<>(RING_CAPACITY, true);

    private volatile boolean running = false;
    private Thread workerThread;
    private Thread deviceFlusherThread;

    private final AtomicLong totalReceived = new AtomicLong(0);
    private final AtomicLong totalDropped = new AtomicLong(0);
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalWrittenDb = new AtomicLong(0);

    private final ConcurrentHashMap<String, DeviceDedupSlot> dedupMap = new ConcurrentHashMap<>();

    private record LevelProcessEntry(
            String deviceId,
            double level,
            long timestampMs,
            int sensorStatus,
            long enqueueTimeNs
    ) {}

    private static class DeviceDedupSlot {
        volatile double level;
        volatile long timestampMs;
        volatile int sensorStatus;
        volatile long lastFlushMs;
        volatile boolean dirty;

        DeviceDedupSlot(double level, long timestampMs, int sensorStatus) {
            this.level = level;
            this.timestampMs = timestampMs;
            this.sensorStatus = sensorStatus;
            this.dirty = true;
            this.lastFlushMs = 0;
        }
    }

    @PostConstruct
    public void start() {
        running = true;
        workerThread = Thread.ofPlatform().name("async-batch-worker").daemon(true).start(this::workerLoop);
        deviceFlusherThread = Thread.ofPlatform().name("device-flusher").daemon(true).start(this::deviceFlushLoop);
        log.info("AsyncBatchProcessor started: ring_cap={} batch_size={} batch_ms={}",
                RING_CAPACITY, BATCH_FLUSH_SIZE, BATCH_FLUSH_MS);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (workerThread != null) workerThread.interrupt();
        if (deviceFlusherThread != null) deviceFlusherThread.interrupt();
        log.info("AsyncBatchProcessor stopped - stats: received={} processed={} written={} dropped={}",
                totalReceived.get(), totalProcessed.get(), totalWrittenDb.get(), totalDropped.get());
    }

    public boolean submit(String deviceId, double level, long timestampMs, int sensorStatus) {
        totalReceived.incrementAndGet();

        DeviceDedupSlot slot = dedupMap.compute(deviceId, (k, existing) -> {
            if (existing == null) {
                return new DeviceDedupSlot(level, timestampMs, sensorStatus);
            }
            existing.level = level;
            existing.timestampMs = timestampMs;
            existing.sensorStatus = sensorStatus;
            existing.dirty = true;
            return existing;
        });

        if (slot == dedupMap.get(deviceId) && slot.lastFlushMs > 0
                && System.currentTimeMillis() - slot.lastFlushMs < DEVICE_DEDUP_WINDOW_MS) {
            return true;
        }

        LevelProcessEntry entry = new LevelProcessEntry(
                deviceId, level, timestampMs, sensorStatus, System.nanoTime());

        if (!ring.offer(entry)) {
            totalDropped.incrementAndGet();
            long dropped = totalDropped.get();
            if (dropped % 500 == 1) {
                log.warn("[ASYNC-DROP] ring full (size={}), total dropped={}", ring.size(), dropped);
            }
            return false;
        }
        return true;
    }

    private void workerLoop() {
        ArrayList<LevelProcessEntry> batch = new ArrayList<>(BATCH_FLUSH_SIZE);
        while (running) {
            try {
                LevelProcessEntry first = ring.poll(BATCH_FLUSH_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                ring.drainTo(batch, BATCH_FLUSH_SIZE - 1);

                processBatch(batch);
                batch.clear();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Batch worker exception: {}", e.getMessage(), e);
            }
        }
    }

    private void processBatch(ArrayList<LevelProcessEntry> batch) {
        if (batch.isEmpty()) return;

        HashMap<String, LevelProcessEntry> latestPerDevice = new LinkedHashMap<>();
        for (LevelProcessEntry e : batch) {
            LevelProcessEntry prev = latestPerDevice.get(e.deviceId());
            if (prev == null || e.timestampMs() > prev.timestampMs()) {
                latestPerDevice.put(e.deviceId(), e);
            }
        }

        HashMap<String, AcDevice> deviceCache = new HashMap<>();
        List<AcDevice> devicesToUpdate = new ArrayList<>();
        List<LevelHistory> historyBatch = new ArrayList<>(latestPerDevice.size());
        List<PumpControlRecord> controls = new ArrayList<>();

        long now = System.currentTimeMillis();
        for (Map.Entry<String, LevelProcessEntry> entry : latestPerDevice.entrySet()) {
            String deviceId = entry.getKey();
            LevelProcessEntry e = entry.getValue();

            try {
                AcDevice device = deviceRepo.findByDeviceId(deviceId).orElse(null);
                if (device == null) {
                    device = AcDevice.builder()
                            .deviceId(deviceId)
                            .location("未知位置")
                            .currentLevelMm(e.level())
                            .currentPumpSpeed(0)
                            .status("NORMAL")
                            .build();
                    deviceRepo.save(device);
                }
                deviceCache.put(deviceId, device);

                LevelSlopeCalculator.SlopeResult slope =
                        slopeCalculator.calculateSlope(deviceId, e.level(), e.timestampMs());

                PumpSpeedController.ControlDecision decision =
                        speedController.calculateSpeed(deviceId, e.level(),
                                slope.slopeMmPerSec, device.getCurrentPumpSpeed());

                boolean speedChanged = decision.speedPercent() != device.getCurrentPumpSpeed();
                boolean statusChanged = !decision.statusLevel().equals(device.getStatus());

                device.setCurrentLevelMm(e.level());
                if (speedChanged) {
                    device.setCurrentPumpSpeed(decision.speedPercent());
                }
                if (statusChanged) {
                    device.setStatus(decision.statusLevel());
                }
                device.setLastUpdateTime(LocalDateTime.now());
                devicesToUpdate.add(device);

                LocalDateTime recordTime = LocalDateTime.ofInstant(
                        new Date(e.timestampMs()).toInstant(), ZoneId.systemDefault());

                historyBatch.add(LevelHistory.builder()
                        .deviceId(deviceId)
                        .liquidLevelMm(e.level())
                        .levelSlope(slope.slopeMmPerSec)
                        .pumpSpeedPercent(decision.speedPercent())
                        .controlReason(decision.reason())
                        .sensorStatus(e.sensorStatus())
                        .businessPhase(decision.businessPhase().name())
                        .noiseLimited(decision.noiseLimited())
                        .recordTime(recordTime)
                        .build());

                if (speedChanged || statusChanged
                        || (!"NORMAL".equals(decision.statusLevel()) && decision.speedPercent() > 0)) {
                    controls.add(new PumpControlRecord(deviceId, decision));
                }

                DeviceDedupSlot slot = dedupMap.get(deviceId);
                if (slot != null) {
                    slot.lastFlushMs = now;
                }

                totalProcessed.incrementAndGet();

            } catch (Exception ex) {
                log.error("Error processing device {}: {}", deviceId, ex.getMessage(), ex);
            }
        }

        flushDbBatch(devicesToUpdate, historyBatch);
        dispatchControls(controls);
    }

    private void flushDbBatch(List<AcDevice> devices, List<LevelHistory> histories) {
        try {
            if (!devices.isEmpty()) {
                deviceRepo.saveAll(devices);
            }

            boolean circuitOpen = monitorService.isCircuitOpen();
            if (!histories.isEmpty() && !circuitOpen) {
                historyRepo.saveAll(histories);
                totalWrittenDb.addAndGet(devices.size() + histories.size());
            } else if (circuitOpen && !histories.isEmpty()) {
                log.warn("[CIRCUIT-OPEN] Skipping {} history writes to reduce DB pressure", histories.size());
                totalWrittenDb.addAndGet(devices.size());
            } else {
                totalWrittenDb.addAndGet(devices.size());
            }
        } catch (Exception e) {
            log.error("DB batch write failed ({} devices, {} histories): {}",
                    devices.size(), histories.size(), e.getMessage());
        }
    }

    private void dispatchControls(List<PumpControlRecord> controls) {
        for (PumpControlRecord rec : controls) {
            try {
                drainageService.sendPumpControl(rec.deviceId(), rec.decision());
            } catch (Exception e) {
                log.error("Dispatch control failed for {}: {}", rec.deviceId(), e.getMessage());
            }
        }
    }

    private record PumpControlRecord(String deviceId, PumpSpeedController.ControlDecision decision) {}

    private void deviceFlushLoop() {
        while (running) {
            try {
                Thread.sleep(500);
                long now = System.currentTimeMillis();
                int flushCount = 0;
                for (Map.Entry<String, DeviceDedupSlot> e : dedupMap.entrySet()) {
                    DeviceDedupSlot slot = e.getValue();
                    if (slot.dirty && (now - slot.lastFlushMs > 400)) {
                        submit(e.getKey(), slot.level, slot.timestampMs, slot.sensorStatus);
                        slot.dirty = false;
                        flushCount++;
                    }
                }
                if (flushCount > 0 && log.isDebugEnabled()) {
                    log.debug("Device flusher pushed {} stale entries", flushCount);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.error("Device flusher error: {}", ex.getMessage());
            }
        }
    }

    public Map<String, Object> getStats() {
        HashMap<String, Object> stats = new LinkedHashMap<>();
        stats.put("ringSize", ring.size());
        stats.put("ringCapacity", RING_CAPACITY);
        stats.put("ringUsagePct", String.format("%.1f%%", ring.size() * 100.0 / RING_CAPACITY));
        stats.put("totalReceived", totalReceived.get());
        stats.put("totalProcessed", totalProcessed.get());
        stats.put("totalWrittenDb", totalWrittenDb.get());
        stats.put("totalDropped", totalDropped.get());
        stats.put("dedupSlots", dedupMap.size());
        return stats;
    }

    public void recordControlLog(String deviceId, boolean success, int actualSpeed, String message) {
        try {
            controlLogRepo.save(PumpControlLog.builder()
                    .deviceId(deviceId)
                    .actualSpeedPercent(actualSpeed)
                    .targetSpeedPercent(actualSpeed)
                    .success(success)
                    .message(message)
                    .controlTime(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.error("Save control log failed: {}", e.getMessage());
        }
    }

    public List<AcDevice> findAllDevices() {
        return deviceRepo.findAll();
    }

    public Optional<AcDevice> findDevice(String deviceId) {
        return deviceRepo.findByDeviceId(deviceId);
    }

    public List<LevelHistory> findHistory(String deviceId, int limit) {
        return historyRepo.findTop100ByDeviceIdOrderByRecordTimeDesc(deviceId)
                .stream().limit(limit).toList();
    }

    public List<PumpControlLog> findLogs(String deviceId, int limit) {
        return controlLogRepo.findTop100ByDeviceIdOrderByControlTimeDesc(deviceId)
                .stream().limit(limit).toList();
    }

    public void saveDevice(AcDevice device) {
        deviceRepo.save(device);
    }
}
