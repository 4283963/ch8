package com.mall.ac.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "drainage.control")
public class DrainageProperties {
    private Double warningLevelMm = 60.0;
    private Double dangerLevelMm = 100.0;
    private Double overflowLevelMm = 140.0;
    private Integer minSpeedPercent = 10;
    private Integer maxSpeedPercent = 100;
    private Integer slopeWindowSeconds = 60;
    private Integer slopeSampleCount = 10;

    private Integer businessStartHour = 10;
    private Integer businessEndHour = 22;
    private Integer businessMaxSpeedPercent = 50;
    private Double emergencyLevelRatio = 0.9;
}
