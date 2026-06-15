package com.mall.ac.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class DeviceStatusDTO {
    private String deviceId;
    private String location;
    private String gatewayId;
    private Double currentLevelMm;
    private Integer currentPumpSpeed;
    private String status;
    private Double levelSlope;
    private LocalDateTime lastUpdateTime;
    private String businessPhase;
    private Integer effectiveMaxSpeed;
    private Boolean noiseLimited;
}
