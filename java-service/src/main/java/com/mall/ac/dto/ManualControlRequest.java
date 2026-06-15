package com.mall.ac.dto;

import lombok.Data;

@Data
public class ManualControlRequest {
    private String deviceId;
    private Integer speedPercent;
    private String reason;
}
