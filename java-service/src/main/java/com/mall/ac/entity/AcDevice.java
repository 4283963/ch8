package com.mall.ac.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "ac_device", indexes = {
    @Index(name = "idx_device_id", columnList = "deviceId", unique = true),
    @Index(name = "idx_location", columnList = "location")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AcDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String deviceId;

    @Column(length = 128)
    private String location;

    @Column(length = 64)
    private String gatewayId;

    @Column(nullable = false)
    private Double currentLevelMm = 0.0;

    @Column(nullable = false)
    private Integer currentPumpSpeed = 0;

    @Column(length = 32)
    private String status;

    @Column(nullable = false)
    private LocalDateTime lastUpdateTime;

    @Column(nullable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        createTime = LocalDateTime.now();
        lastUpdateTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdateTime = LocalDateTime.now();
    }
}
