package com.mall.ac.algorithm;

import com.mall.ac.config.DrainageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class PumpSpeedController {

    private final DrainageProperties props;

    public enum BusinessPhase {
        BUSINESS_HOURS,
        AFTER_HOURS,
        EMERGENCY_OVERRIDE
    }

    public static class ControlDecision {
        public final int speedPercent;
        public final String reason;
        public final String statusLevel;
        public final double slope;
        public final double level;
        public final BusinessPhase businessPhase;
        public final int effectiveMaxSpeed;
        public final boolean noiseLimited;

        public ControlDecision(int speedPercent, String reason, String statusLevel,
                               double slope, double level,
                               BusinessPhase businessPhase,
                               int effectiveMaxSpeed,
                               boolean noiseLimited) {
            this.speedPercent = speedPercent;
            this.reason = reason;
            this.statusLevel = statusLevel;
            this.slope = slope;
            this.level = level;
            this.businessPhase = businessPhase;
            this.effectiveMaxSpeed = effectiveMaxSpeed;
            this.noiseLimited = noiseLimited;
        }
    }

    public BusinessPhase resolveBusinessPhase(double currentLevel) {
        double overflow = props.getOverflowLevelMm();
        double emergencyLine = overflow * props.getEmergencyLevelRatio();

        if (currentLevel >= emergencyLine) {
            return BusinessPhase.EMERGENCY_OVERRIDE;
        }

        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int start = props.getBusinessStartHour();
        int end = props.getBusinessEndHour();

        boolean isBusinessHours;
        if (start < end) {
            isBusinessHours = hour >= start && hour < end;
        } else {
            isBusinessHours = hour >= start || hour < end;
        }

        return isBusinessHours ? BusinessPhase.BUSINESS_HOURS : BusinessPhase.AFTER_HOURS;
    }

    public ControlDecision calculateSpeed(String deviceId, double currentLevel,
                                          double slopeMmPerSec, int currentSpeed) {
        double warning = props.getWarningLevelMm();
        double danger = props.getDangerLevelMm();
        double overflow = props.getOverflowLevelMm();
        int minSpeed = props.getMinSpeedPercent();
        int maxSpeed = props.getMaxSpeedPercent();

        BusinessPhase phase = resolveBusinessPhase(currentLevel);
        int effectiveMaxSpeed;
        boolean noiseLimited;

        switch (phase) {
            case EMERGENCY_OVERRIDE -> {
                effectiveMaxSpeed = maxSpeed;
                noiseLimited = false;
            }
            case BUSINESS_HOURS -> {
                effectiveMaxSpeed = Math.min(maxSpeed, props.getBusinessMaxSpeedPercent());
                noiseLimited = true;
            }
            default -> {
                effectiveMaxSpeed = maxSpeed;
                noiseLimited = false;
            }
        }

        String statusLevel = "NORMAL";
        int baseSpeed = 0;

        if (currentLevel >= overflow) {
            statusLevel = "OVERFLOW";
            baseSpeed = effectiveMaxSpeed;
        } else if (currentLevel >= danger) {
            statusLevel = "DANGER";
            baseSpeed = (int) (minSpeed + (effectiveMaxSpeed - minSpeed) * 0.8);
        } else if (currentLevel >= warning) {
            statusLevel = "WARNING";
            baseSpeed = (int) (minSpeed + (effectiveMaxSpeed - minSpeed) * 0.5);
        } else if (currentLevel >= warning * 0.6) {
            statusLevel = "ELEVATED";
            baseSpeed = noiseLimited ? minSpeed : minSpeed;
        }

        int slopeBoost = calculateSlopeBoost(slopeMmPerSec, effectiveMaxSpeed);
        int targetSpeed = Math.min(effectiveMaxSpeed, baseSpeed + slopeBoost);

        if (currentLevel < warning * 0.3 && slopeMmPerSec < 0.2) {
            targetSpeed = 0;
        }

        targetSpeed = applyHysteresis(targetSpeed, currentSpeed, currentLevel, warning);

        if (targetSpeed > 0 && targetSpeed < minSpeed) {
            targetSpeed = noiseLimited ? minSpeed : minSpeed;
        }
        if (targetSpeed > effectiveMaxSpeed) {
            targetSpeed = effectiveMaxSpeed;
        }
        if (targetSpeed < 0) {
            targetSpeed = 0;
        }

        String phaseLabel = switch (phase) {
            case BUSINESS_HOURS -> "BUSINESS(noise-limit@" + effectiveMaxSpeed + "%)";
            case AFTER_HOURS -> "AFTER-HOURS(full-power@" + effectiveMaxSpeed + "%)";
            case EMERGENCY_OVERRIDE -> "EMERGENCY(override@" + effectiveMaxSpeed + "%)";
        };

        String reason = String.format(
                "level=%.1fmm slope=%.3fmm/s status=%s phase=%s base=%d slopeBoost=%d target=%d",
                currentLevel, slopeMmPerSec, statusLevel, phaseLabel, baseSpeed, slopeBoost, targetSpeed);

        log.debug("[{}] Control decision: {}", deviceId, reason);

        return new ControlDecision(targetSpeed, reason, statusLevel, slopeMmPerSec, currentLevel,
                phase, effectiveMaxSpeed, noiseLimited);
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
