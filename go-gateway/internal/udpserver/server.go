package udpserver

import (
	"encoding/binary"
	"fmt"
	"net"
	"sync"
	"time"

	"go-gateway/internal/config"
	"go-gateway/internal/logger"
)

type SensorReading struct {
	DeviceID     string
	LiquidLevel  float64
	TimestampMs  int64
	SensorStatus int32
}

type UDPServer struct {
	cfg      *config.Config
	conn     *net.UDPConn
	readings map[string]*SensorReading
	mu       sync.RWMutex
	callback func(SensorReading)
	running  bool
}

func New(cfg *config.Config) *UDPServer {
	return &UDPServer{
		cfg:      cfg,
		readings: make(map[string]*SensorReading),
	}
}

func (s *UDPServer) SetCallback(cb func(SensorReading)) {
	s.callback = cb
}

func (s *UDPServer) Start() error {
	addr := &net.UDPAddr{
		IP:   net.ParseIP(s.cfg.UDPListenAddr),
		Port: s.cfg.UDPSensorPort,
	}

	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen UDP: %w", err)
	}
	s.conn = conn
	s.running = true

	logger.L.Info("UDP Server listening on %s:%d", s.cfg.UDPListenAddr, s.cfg.UDPSensorPort)

	go s.readLoop()
	go s.simulateSensors()

	return nil
}

func (s *UDPServer) readLoop() {
	buf := make([]byte, 1024)
	for s.running {
		n, remoteAddr, err := s.conn.ReadFromUDP(buf)
		if err != nil {
			if s.running {
				logger.L.Error("UDP read error: %v", err)
			}
			continue
		}
		if n >= 16 {
			s.parseAndStore(buf[:n], remoteAddr.String())
		}
	}
}

func (s *UDPServer) parseAndStore(data []byte, remote string) {
	if len(data) < 16 {
		return
	}
	deviceID := string(data[:8])
	level := float64(binary.LittleEndian.Uint32(data[8:12])) / 10.0
	status := int32(binary.LittleEndian.Uint16(data[12:14]))

	reading := SensorReading{
		DeviceID:     deviceID,
		LiquidLevel:  level,
		TimestampMs:  time.Now().UnixMilli(),
		SensorStatus: status,
	}

	s.mu.Lock()
	s.readings[deviceID] = &reading
	s.mu.Unlock()

	if s.callback != nil {
		s.callback(reading)
	}

	logger.L.Debug("Received sensor data: device=%s level=%.1fmm status=%d from=%s",
		deviceID, level, status, remote)
}

func (s *UDPServer) simulateSensors() {
	logger.L.Info("Starting sensor simulation (no real hardware connected)")
	ticker := time.NewTicker(time.Duration(s.cfg.ReadIntervalMs) * time.Millisecond)
	defer ticker.Stop()

	levels := make(map[string]float64)
	for _, d := range s.cfg.Devices {
		levels[d.DeviceID] = 20.0 + float64(len(d.DeviceID))*2
	}

	for range ticker.C {
		for _, dev := range s.cfg.Devices {
			cur := levels[dev.DeviceID]
			change := (randFloat() - 0.35) * 3.0
			if cur > 80 {
				change = -randFloat() * 4.0
			} else if cur < 10 {
				change = randFloat() * 2.0
			}
			newLevel := cur + change
			if newLevel < 0 {
				newLevel = 0
			}
			if newLevel > 150 {
				newLevel = 150
			}
			levels[dev.DeviceID] = newLevel

			reading := SensorReading{
				DeviceID:     dev.DeviceID,
				LiquidLevel:  newLevel,
				TimestampMs:  time.Now().UnixMilli(),
				SensorStatus: 0,
			}

			s.mu.Lock()
			s.readings[dev.DeviceID] = &reading
			s.mu.Unlock()

			if s.callback != nil {
				s.callback(reading)
			}
		}
	}
}

func randFloat() float64 {
	return float64(time.Now().UnixNano()%1000) / 1000.0
}

func (s *UDPServer) GetLatest(deviceID string) (*SensorReading, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	r, ok := s.readings[deviceID]
	return r, ok
}

func (s *UDPServer) GetAllLatest() []SensorReading {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]SensorReading, 0, len(s.readings))
	for _, r := range s.readings {
		result = append(result, *r)
	}
	return result
}

func (s *UDPServer) Stop() {
	s.running = false
	if s.conn != nil {
		s.conn.Close()
	}
}
