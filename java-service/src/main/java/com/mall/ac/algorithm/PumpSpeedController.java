package com.mall.ac.algorithm;

import com.mall.ac.config.DrainageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PumpSpeedController {

    private final DrainageProperties props;

    public static class ControlDecision {
        public final int speedPercent;
        public final String reason;
        public final String statusLevel;
        public final double slope;
        public final double level;

        public ControlDecision(int speedPercent, String reason, String statusLevel,
                               double slope, double level) {
            this.speedPercent = speedPercent;
            this.reason = reason;
            this.statusLevel = statusLevel;
            this.slope = slope;
            this.level = level;
        }
    }

    public ControlDecision calculateSpeed(String deviceId, double currentLevel,
                                          double slopeMmPerSec, int currentSpeed) {
        double warning = props.getWarningLevelMm();
        double danger = props.getDangerLevelMm();
        double overflow = props.getOverflowLevelMm();
        int minSpeed = props.getMinSpeedPercent();
        int maxSpeed = props.getMaxSpeedPercent();

        String statusLevel = "NORMAL";
        int baseSpeed = 0;

        if (currentLevel >= overflow) {
            statusLevel = "OVERFLOW";
            baseSpeed = maxSpeed;
        } else if (currentLevel >= danger) {
            statusLevel = "DANGER";
            baseSpeed = (int) (minSpeed + (maxSpeed - minSpeed) * 0.8);
        } else if (currentLevel >= warning) {
            statusLevel = "WARNING";
            baseSpeed = (int) (minSpeed + (maxSpeed - minSpeed) * 0.5);
        } else if (currentLevel >= warning * 0.6) {
            statusLevel = "ELEVATED";
            baseSpeed = minSpeed;
        }

        int slopeBoost = calculateSlopeBoost(slopeMmPerSec, maxSpeed);
        int targetSpeed = Math.min(maxSpeed, baseSpeed + slopeBoost);

        if (currentLevel < warning * 0.3 && slopeMmPerSec < 0.2) {
            targetSpeed = 0;
        }

        targetSpeed = applyHysteresis(targetSpeed, currentSpeed, currentLevel, warning);

        if (targetSpeed > 0 && targetSpeed < minSpeed) {
            targetSpeed = minSpeed;
        }
        if (targetSpeed > maxSpeed) {
            targetSpeed = maxSpeed;
        }
        if (targetSpeed < 0) {
            targetSpeed = 0;
        }

        String reason = String.format(
                "level=%.1fmm slope=%.3fmm/s status=%s base=%d slopeBoost=%d target=%d",
                currentLevel, slopeMmPerSec, statusLevel, baseSpeed, slopeBoost, targetSpeed);

        log.debug("[{}] Control decision: {}", deviceId, reason);

        return new ControlDecision(targetSpeed, reason, statusLevel, slopeMmPerSec, currentLevel);
    }

    private int calculateSlopeBoost(double slopeMmPerSec, int maxSpeed) {
        if (slopeMmPerSec <= 0) {
            return 0;
        }
        if (slopeMmPerSec >= 2.0) {
            return maxSpeed;
        }
        double ratio = slopeMmPerSec / 2.0;
        return (int) (maxSpeed * ratio * 0.7);
    }

    private int applyHysteresis(int targetSpeed, int currentSpeed,
                                double currentLevel, double warningLevel) {
        if (currentSpeed > 0 && targetSpeed == 0) {
            if (currentLevel > warningLevel * 0.2) {
                return Math.min(currentSpeed / 2, 20);
            }
        }
        if (Math.abs(targetSpeed - currentSpeed) < 8) {
            return currentSpeed;
        }
        return targetSpeed;
    }
}
