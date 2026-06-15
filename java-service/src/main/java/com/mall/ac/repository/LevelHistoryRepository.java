package com.mall.ac.repository;

import com.mall.ac.entity.LevelHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LevelHistoryRepository extends JpaRepository<LevelHistory, Long> {
    List<LevelHistory> findByDeviceIdOrderByRecordTimeDesc(String deviceId);

    @Query("SELECT h FROM LevelHistory h WHERE h.deviceId = :deviceId " +
           "AND h.recordTime >= :startTime ORDER BY h.recordTime ASC")
    List<LevelHistory> findByDeviceIdAndRecordTimeAfterOrderByRecordTimeAsc(
            @Param("deviceId") String deviceId,
            @Param("startTime") LocalDateTime startTime);

    List<LevelHistory> findTop100ByDeviceIdOrderByRecordTimeDesc(String deviceId);
}
