package pump

import (
	"fmt"
	"sync"
	"time"

	"go-gateway/internal/config"
	"go-gateway/internal/logger"
)

type PumpState struct {
	DeviceID      string
	TargetSpeed   int
	ActualSpeed   int
	LastUpdatedMs int64
	LastReason    string
	CommandCount  int64
}

type Controller struct {
	cfg    *config.Config
	states map[string]*PumpState
	mu     sync.RWMutex
}

func NewController(cfg *config.Config) *Controller {
	c := &Controller{
		cfg:    cfg,
		states: make(map[string]*PumpState),
	}
	for _, d := range cfg.Devices {
		c.states[d.DeviceID] = &PumpState{
			DeviceID:      d.DeviceID,
			TargetSpeed:   0,
			ActualSpeed:   0,
			LastUpdatedMs: time.Now().UnixMilli(),
		}
	}
	return c
}

func (c *Controller) SetSpeed(deviceID string, speedPercent int, reason string) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	state, ok := c.states[deviceID]
	if !ok {
		return 0, fmt.Errorf("unknown device: %s", deviceID)
	}

	clamped := c.clampSpeed(speedPercent)
	state.TargetSpeed = clamped
	state.ActualSpeed = clamped
	state.LastUpdatedMs = time.Now().UnixMilli()
	state.LastReason = reason
	state.CommandCount++

	logger.L.Info("[Pump] device=%s set_speed=%d%% reason=%s (cmd#%d)",
		deviceID, clamped, reason, state.CommandCount)

	return clamped, nil
}

func (c *Controller) clampSpeed(speed int) int {
	if speed < 0 {
		return 0
	}
	if speed > c.cfg.PumpMaxSpeed {
		return c.cfg.PumpMaxSpeed
	}
	if speed > 0 && speed < c.cfg.PumpMinSpeed {
		return c.cfg.PumpMinSpeed
	}
	return speed
}

func (c *Controller) GetState(deviceID string) (*PumpState, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	s, ok := c.states[deviceID]
	return s, ok
}

func (c *Controller) GetAllStates() []PumpState {
	c.mu.RLock()
	defer c.mu.RUnlock()
	result := make([]PumpState, 0, len(c.states))
	for _, s := range c.states {
		result = append(result, *s)
	}
	return result
}
