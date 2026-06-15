package com.mall.ac.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "pump_control_log", indexes = {
    @Index(name = "idx_pump_device_time", columnList = "deviceId, controlTime"),
    @Index(name = "idx_pump_success", columnList = "success")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PumpControlLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String deviceId;

    @Column(nullable = false)
    private Integer targetSpeedPercent;

    @Column(nullable = false)
    private Integer actualSpeedPercent;

    @Column(length = 256)
    private String reason;

    @Column(nullable = false)
    private Boolean success;

    @Column(length = 512)
    private String message;

    @Column(nullable = false)
    private LocalDateTime controlTime;
}
