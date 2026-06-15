package com.mall.ac.controller;

import com.mall.ac.algorithm.LevelSlopeCalculator;
import com.mall.ac.algorithm.PumpSpeedController;
import com.mall.ac.config.DrainageProperties;
import com.mall.ac.dto.ApiResponse;
import com.mall.ac.dto.DeviceStatusDTO;
import com.mall.ac.dto.ManualControlRequest;
import com.mall.ac.entity.AcDevice;
import com.mall.ac.entity.LevelHistory;
import com.mall.ac.entity.PumpControlLog;
import com.mall.ac.service.AsyncBatchProcessor;
import com.mall.ac.service.DrainageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/drainage")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DrainageController {

    private final DrainageService drainageService;
    private final AsyncBatchProcessor asyncProcessor;
    private final LevelSlopeCalculator slopeCalculator;
    private final PumpSpeedController speedController;
    private final DrainageProperties props;

    @GetMapping("/devices")
    public ApiResponse<List<DeviceStatusDTO>> getAllDevices() {
        List<DeviceStatusDTO> list = asyncProcessor.findAllDevices().stream()
                .map(this::toStatusDTO)
                .toList();
        return ApiResponse.success(list);
    }

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<DeviceStatusDTO> getDeviceStatus(@PathVariable String deviceId) {
        return asyncProcessor.findDevice(deviceId)
                .map(d -> ApiResponse.success(toStatusDTO(d)))
                .orElse(ApiResponse.error(404, "Device not found"));
    }

    @GetMapping("/devices/{deviceId}/history")
    public ApiResponse<List<LevelHistory>> getDeviceHistory(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(asyncProcessor.findHistory(deviceId, limit));
    }

    @GetMapping("/devices/{deviceId}/logs")
    public ApiResponse<List<PumpControlLog>> getControlLogs(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(asyncProcessor.findLogs(deviceId, limit));
    }

    @PostMapping("/devices/{deviceId}/control")
    public ApiResponse<PumpSpeedController.ControlDecision> manualControl(
            @PathVariable String deviceId,
            @RequestBody ManualControlRequest request) {
        if (request.getSpeedPercent() == null) {
            return ApiResponse.error(400, "speedPercent is required");
        }
        if (request.getSpeedPercent() < 0 || request.getSpeedPercent() > 100) {
            return ApiResponse.error(400, "speedPercent must be between 0 and 100");
        }
        try {
            PumpSpeedController.ControlDecision decision =
                    drainageService.manualControl(deviceId,
                            request.getSpeedPercent(),
                            request.getReason());

            asyncProcessor.findDevice(deviceId).ifPresent(d -> {
                d.setCurrentPumpSpeed(request.getSpeedPercent());
                d.setStatus("MANUAL");
                asyncProcessor.saveDevice(d);
            });

            return ApiResponse.success(decision);
        } catch (RuntimeException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("OK");
    }

    @GetMapping("/system/stats")
    public ApiResponse<Map<String, Object>> getSystemStats() {
        Map<String, Object> stats = new LinkedHashMap<>(asyncProcessor.getStats());

        List<AcDevice> all = asyncProcessor.findAllDevices();
        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        double totalLevel = 0;
        int activePumps = 0;
        int warningDevices = 0;

        for (AcDevice d : all) {
            statusCounts.merge(d.getStatus(), 1, Integer::sum);
            totalLevel += d.getCurrentLevelMm();
            if (d.getCurrentPumpSpeed() > 0) activePumps++;
            if ("WARNING".equals(d.getStatus()) || "DANGER".equals(d.getStatus())
                    || "OVERFLOW".equals(d.getStatus())) {
                warningDevices++;
            }
        }
        stats.put("totalDevices", all.size());
        stats.put("activePumps", activePumps);
        stats.put("warningDevices", warningDevices);
        stats.put("avgLevelMm", all.isEmpty() ? 0 : String.format("%.2f", totalLevel / all.size()));
        stats.put("statusBreakdown", statusCounts);

        PumpSpeedController.BusinessPhase nowPhase =
                speedController.resolveBusinessPhase(0.0);
        stats.put("businessPhase", nowPhase.name());
        stats.put("businessHours",
                String.format("%02d:00 - %02d:00",
                        props.getBusinessStartHour(), props.getBusinessEndHour()));
        stats.put("noiseLimited", nowPhase == PumpSpeedController.BusinessPhase.BUSINESS_HOURS);
        stats.put("effectiveMaxSpeedPercent",
                nowPhase == PumpSpeedController.BusinessPhase.BUSINESS_HOURS
                        ? props.getBusinessMaxSpeedPercent()
                        : props.getMaxSpeedPercent());
        stats.put("emergencyLineMm", String.format("%.1f",
                props.getOverflowLevelMm() * props.getEmergencyLevelRatio()));

        return ApiResponse.success(stats);
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

        PumpSpeedController.BusinessPhase phase =
                speedController.resolveBusinessPhase(d.getCurrentLevelMm() != null ? d.getCurrentLevelMm() : 0.0);
        dto.setBusinessPhase(phase.name());
        dto.setNoiseLimited(phase == PumpSpeedController.BusinessPhase.BUSINESS_HOURS);
        dto.setEffectiveMaxSpeed(
                phase == PumpSpeedController.BusinessPhase.BUSINESS_HOURS
                        ? props.getBusinessMaxSpeedPercent()
                        : props.getMaxSpeedPercent());
        return dto;
    }
}
