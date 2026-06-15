package com.mall.ac.controller;

import com.mall.ac.algorithm.PumpSpeedController;
import com.mall.ac.dto.ApiResponse;
import com.mall.ac.dto.DeviceStatusDTO;
import com.mall.ac.dto.ManualControlRequest;
import com.mall.ac.entity.LevelHistory;
import com.mall.ac.entity.PumpControlLog;
import com.mall.ac.service.DrainageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/drainage")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DrainageController {

    private final DrainageService drainageService;

    @GetMapping("/devices")
    public ApiResponse<List<DeviceStatusDTO>> getAllDevices() {
        return ApiResponse.success(drainageService.getAllDeviceStatus());
    }

    @GetMapping("/devices/{deviceId}")
    public ApiResponse<DeviceStatusDTO> getDeviceStatus(@PathVariable String deviceId) {
        DeviceStatusDTO dto = drainageService.getDeviceStatus(deviceId);
        if (dto == null) {
            return ApiResponse.error(404, "Device not found");
        }
        return ApiResponse.success(dto);
    }

    @GetMapping("/devices/{deviceId}/history")
    public ApiResponse<List<LevelHistory>> getDeviceHistory(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(drainageService.getDeviceHistory(deviceId, limit));
    }

    @GetMapping("/devices/{deviceId}/logs")
    public ApiResponse<List<PumpControlLog>> getControlLogs(
            @PathVariable String deviceId,
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(drainageService.getControlLogs(deviceId, limit));
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
            return ApiResponse.success(decision);
        } catch (RuntimeException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("OK");
    }
}
