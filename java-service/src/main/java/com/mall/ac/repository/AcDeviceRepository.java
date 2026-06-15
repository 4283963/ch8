package com.mall.ac.repository;

import com.mall.ac.entity.AcDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface AcDeviceRepository extends JpaRepository<AcDevice, Long> {
    Optional<AcDevice> findByDeviceId(String deviceId);
    List<AcDevice> findByGatewayId(String gatewayId);
    List<AcDevice> findByStatus(String status);
}
