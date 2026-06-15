package com.mall.ac.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class LevelHistoryDTO {
    private Long id;
    private String deviceId;
    private Double liquidLevelMm;
    private Double levelSlope;
    private Integer pumpSpeedPercent;
    private String controlReason;
    private LocalDateTime recordTime;
}
