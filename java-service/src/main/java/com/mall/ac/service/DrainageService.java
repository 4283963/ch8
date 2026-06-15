package com.mall.ac.service;

import com.mall.ac.algorithm.LevelSlopeCalculator;
import com.mall.ac.algorithm.PumpSpeedController;
import com.mall.ac.config.DrainageProperties;
import com.mall.ac.dto.DeviceStatusDTO;
import com.mall.ac.entity.AcDevice;
import com.mall.ac.entity.LevelHistory;
import com.mall.ac.entity.PumpControlLog;
import com.mall.ac.grpc.api.LevelData;
import com.mall.ac.grpc.api.PumpControl;
import com.mall.ac.repository.AcDeviceRepository;
import com.mall.ac.repository.LevelHistoryRepository;
import com.mall.ac.repository.PumpControlLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DrainageService {

    private final AcDeviceRepository deviceRepo;
    private final LevelHistoryRepository historyRepo;
    private final PumpControlLogRepository controlLogRepo;
    private final LevelSlopeCalculator slopeCalculator;
    private final PumpSpeedController speedController;
    private final DrainageProperties props;

    private final Map<String, io.grpc.stub.StreamObserver<PumpControl>> streamObservers =
            new ConcurrentHashMap<>();

    public void registerStreamObserver(String gatewayId,
                                       io.grpc.stub.StreamObserver<PumpControl> observer) {
        streamObservers.put(gatewayId, observer);
        log.info("Registered stream observer for gateway: {}, total: {}",
                gatewayId, streamObservers.size());
    }

    public void removeStreamObserver(String gatewayId) {
        streamObservers.remove(gatewayId);
        log.info("Removed stream observer for gateway: {}, remaining: {}",
                gatewayId, streamObservers.size());
    }

    @Transactional
    public PumpSpeedController.ControlDecision processLevelData(LevelData levelData) {
        String deviceId = levelData.getDeviceId();
        double level = levelData.getLiquidLevelMm();
        long ts = levelData.getTimestampMs();

        AcDevice device = deviceRepo.findByDeviceId(deviceId).orElse(null);
        if (device == null) {
            device = AcDevice.builder()
                    .deviceId(deviceId)
                    .location("未知位置")
                    .currentLevelMm(level)
                    .currentPumpSpeed(0)
                    .status("NORMAL")
                    .build();
            deviceRepo.save(device);
            log.warn("Auto-registered unknown device: {}", deviceId);
        }

        LevelSlopeCalculator.SlopeResult slopeResult =
                slopeCalculator.calculateSlope(deviceId, level, ts);

        PumpSpeedController.ControlDecision decision =
                speedController.calculateSpeed(deviceId, level,
                        slopeResult.slopeMmPerSec, device.getCurrentPumpSpeed());

        device.setCurrentLevelMm(level);
        device.setCurrentPumpSpeed(decision.speedPercent);
        device.setStatus(decision.statusLevel);
        device.setLastUpdateTime(LocalDateTime.now());
        deviceRepo.save(device);

        LevelHistory history = LevelHistory.builder()
                .deviceId(deviceId)
                .liquidLevelMm(level)
                .levelSlope(slopeResult.slopeMmPerSec)
                .pumpSpeedPercent(decision.speedPercent)
                .controlReason(decision.reason)
                .sensorStatus(levelData.getSensorStatus())
                .recordTime(LocalDateTime.ofInstant(
                        new Date(ts).toInstant(), ZoneId.systemDefault()))
                .build();
        historyRepo.save(history);

        if (decision.speedPercent != device.getCurrentPumpSpeed()
                || !"NORMAL".equals(decision.statusLevel)) {
            sendPumpControl(deviceId, decision);
        }

        return decision;
    }

    public void sendPumpControl(String deviceId,
                                PumpSpeedController.ControlDecision decision) {
        AcDevice device = deviceRepo.findByDeviceId(deviceId).orElse(null);
        if (device == null || device.getGatewayId() == null) {
            for (Map.Entry<String, io.grpc.stub.StreamObserver<PumpControl>> entry
                    : streamObservers.entrySet()) {
                sendToObserver(entry.getValue(), deviceId, decision);
            }
            return;
        }

        io.grpc.stub.StreamObserver<PumpControl> observer =
                streamObservers.get(device.getGatewayId());
        if (observer != null) {
            sendToObserver(observer, deviceId, decision);
        } else {
            log.warn("No stream observer for gateway: {}, device: {}",
                    device.getGatewayId(), deviceId);
        }
    }

    private void sendToObserver(io.grpc.stub.StreamObserver<PumpControl> observer,
                                String deviceId,
                                PumpSpeedController.ControlDecision decision) {
        try {
            PumpControl control = PumpControl.newBuilder()
                    .setDeviceId(deviceId)
                    .setSpeedPercent(decision.speedPercent)
                    .setTimestampMs(System.currentTimeMillis())
                    .setReason(decision.reason)
                    .build();
            observer.onNext(control);
            log.info("Sent pump control: device={} speed={}% reason={}",
                    deviceId, decision.speedPercent, decision.reason);
        } catch (Exception e) {
            log.error("Failed to send pump control for device {}: {}", deviceId, e.getMessage());
        }
    }

    @Transactional
    public void recordControlAck(String deviceId, boolean success,
                                 int actualSpeed, String message) {
        PumpControlLog logEntry = PumpControlLog.builder()
                .deviceId(deviceId)
                .actualSpeedPercent(actualSpeed)
                .targetSpeedPercent(actualSpeed)
                .success(success)
                .message(message)
                .controlTime(LocalDateTime.now())
                .build();
        controlLogRepo.save(logEntry);

        if (success) {
            deviceRepo.findByDeviceId(deviceId).ifPresent(d -> {
                d.setCurrentPumpSpeed(actualSpeed);
                deviceRepo.save(d);
            });
        }
    }

    @Transactional
    public PumpSpeedController.ControlDecision manualControl(String deviceId,
                                                              int speedPercent,
                                                              String reason) {
        AcDevice device = deviceRepo.findByDeviceId(deviceId)
                .orElseThrow(() -> new RuntimeException("Device not found: " + deviceId));

        int clamped = Math.max(0, Math.min(props.getMaxSpeedPercent(), speedPercent));

        PumpSpeedController.ControlDecision decision =
                new PumpSpeedController.ControlDecision(
                        clamped,
                        "MANUAL: " + (reason != null ? reason : "手动控制"),
                        "MANUAL",
                        0,
                        device.getCurrentLevelMm());

        device.setCurrentPumpSpeed(clamped);
        device.setStatus("MANUAL");
        deviceRepo.save(device);

        sendPumpControl(deviceId, decision);
        return decision;
    }

    public List<DeviceStatusDTO> getAllDeviceStatus() {
        return deviceRepo.findAll().stream()
                .map(this::toStatusDTO)
                .collect(Collectors.toList());
    }

    public DeviceStatusDTO getDeviceStatus(String deviceId) {
        return deviceRepo.findByDeviceId(deviceId)
                .map(this::toStatusDTO)
                .orElse(null);
    }

    private DeviceStatusDTO toStatusDTO(AcDevice d) {
        DeviceStatusDTO dto = new DeviceStatusDTO();
        dto.setDeviceId(d.getDeviceId());
        dto.setLocation(d.getLocation());
        dto.setGatewayId(d.getGatewayId());
        dto.setCurrentLevelMm(d.getCurrentLevelMm());
        dto.setCurrentPumpSpeed(d.getCurrentPumpSpeed());
        dto.setStatus(d.getStatus());
        dto.setLastUpdateTime(d.getLastUpdateTime());

        LevelSlopeCalculator.SlopeResult slope = slopeCalculator.calculateSlopeFromDb(d.getDeviceId());
        dto.setLevelSlope(slope.slopeMmPerSec);
        return dto;
    }

    public List<LevelHistory> getDeviceHistory(String deviceId, int limit) {
        return historyRepo.findTop100ByDeviceIdOrderByRecordTimeDesc(deviceId)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<PumpControlLog> getControlLogs(String deviceId, int limit) {
        return controlLogRepo.findTop100ByDeviceIdOrderByControlTimeDesc(deviceId)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
    }

    @Transactional
    public void registerDevices(String gatewayId, List<String[]> deviceInfoList) {
        for (String[] info : deviceInfoList) {
            String deviceId = info[0];
            String location = info.length > 1 ? info[1] : "";

            AcDevice device = deviceRepo.findByDeviceId(deviceId).orElse(null);
            if (device == null) {
                device = AcDevice.builder()
                        .deviceId(deviceId)
                        .location(location)
                        .gatewayId(gatewayId)
                        .currentLevelMm(0.0)
                        .currentPumpSpeed(0)
                        .status("OFFLINE")
                        .build();
            } else {
                device.setGatewayId(gatewayId);
                if (location != null && !location.isEmpty()) {
                    device.setLocation(location);
                }
            }
            deviceRepo.save(device);
        }
        log.info("Registered {} devices for gateway {}", deviceInfoList.size(), gatewayId);
    }
}
