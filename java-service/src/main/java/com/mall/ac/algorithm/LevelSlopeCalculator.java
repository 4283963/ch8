package com.mall.ac.algorithm;

import com.mall.ac.config.DrainageProperties;
import com.mall.ac.entity.LevelHistory;
import com.mall.ac.repository.LevelHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class LevelSlopeCalculator {

    private final DrainageProperties props;
    private final LevelHistoryRepository historyRepo;

    private final Map<String, Deque<LevelPoint>> recentPoints = new ConcurrentHashMap<>();

    private static class LevelPoint {
        final double level;
        final long timestampMs;
        LevelPoint(double level, long timestampMs) {
            this.level = level;
            this.timestampMs = timestampMs;
        }
    }

    public static class SlopeResult {
        public final double slopeMmPerSec;
        public final int sampleCount;
        public SlopeResult(double slopeMmPerSec, int sampleCount) {
            this.slopeMmPerSec = slopeMmPerSec;
            this.sampleCount = sampleCount;
        }
    }

    public SlopeResult calculateSlope(String deviceId, double currentLevel, long timestampMs) {
        Deque<LevelPoint> deque = recentPoints.computeIfAbsent(
                deviceId, k -> new ArrayDeque<>());

        long windowMs = props.getSlopeWindowSeconds() * 1000L;
        while (!deque.isEmpty() && (timestampMs - deque.peekFirst().timestampMs) > windowMs) {
            deque.pollFirst();
        }

        deque.addLast(new LevelPoint(currentLevel, timestampMs));

        while (deque.size() > props.getSlopeSampleCount() * 3) {
            deque.pollFirst();
        }

        if (deque.size() < 3) {
            return new SlopeResult(0.0, deque.size());
        }

        List<LevelPoint> points = new ArrayList<>(deque);
        double slope = linearRegressionSlope(points);
        return new SlopeResult(slope, points.size());
    }

    private double linearRegressionSlope(List<LevelPoint> points) {
        int n = points.size();
        if (n < 2) return 0.0;

        long firstTs = points.get(0).timestampMs;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (LevelPoint p : points) {
            double x = (p.timestampMs - firstTs) / 1000.0;
            double y = p.level;
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = (n * sumX2) - (sumX * sumX);
        if (Math.abs(denominator) < 1e-9) {
            return 0.0;
        }
        return (n * sumXY - sumX * sumY) / denominator;
    }

    public SlopeResult calculateSlopeFromDb(String deviceId) {
        LocalDateTime startTime = LocalDateTime.now()
                .minusSeconds(props.getSlopeWindowSeconds());
        List<LevelHistory> history = historyRepo
                .findByDeviceIdAndRecordTimeAfterOrderByRecordTimeAsc(deviceId, startTime);

        if (history.size() < 3) {
            return new SlopeResult(0.0, history.size());
        }

        long firstTs = java.sql.Timestamp.valueOf(history.get(0).getRecordTime()).getTime();
        int n = history.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (LevelHistory h : history) {
            long ts = java.sql.Timestamp.valueOf(h.getRecordTime()).getTime();
            double x = (ts - firstTs) / 1000.0;
            double y = h.getLiquidLevelMm();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }

        double denominator = (n * sumX2) - (sumX * sumX);
        if (Math.abs(denominator) < 1e-9) {
            return new SlopeResult(0.0, n);
        }
        return new SlopeResult((n * sumXY - sumX * sumY) / denominator, n);
    }
}
