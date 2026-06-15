package com.mall.ac.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "level_history", indexes = {
    @Index(name = "idx_device_time", columnList = "deviceId, recordTime"),
    @Index(name = "idx_record_time", columnList = "recordTime")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LevelHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String deviceId;

    @Column(nullable = false)
    private Double liquidLevelMm;

    @Column(nullable = false)
    private Double levelSlope;

    @Column(nullable = false)
    private Integer pumpSpeedPercent;

    @Column(length = 256)
    private String controlReason;

    @Column(nullable = false)
    private Integer sensorStatus;

    @Column(nullable = false)
    private LocalDateTime recordTime;
}
