package com.mall.ac.repository;

import com.mall.ac.entity.PumpControlLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PumpControlLogRepository extends JpaRepository<PumpControlLog, Long> {
    List<PumpControlLog> findTop100ByDeviceIdOrderByControlTimeDesc(String deviceId);
}
