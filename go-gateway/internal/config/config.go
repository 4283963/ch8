package config

import (
	"os"
	"strconv"
)

type Config struct {
	GatewayID       string
	UDPListenAddr   string
	UDPSensorPort   int
	GrpcServerAddr  string
	LogLevel        string
	ReadIntervalMs  int
	PumpMinSpeed    int
	PumpMaxSpeed    int
	Devices         []DeviceConfig
}

type DeviceConfig struct {
	DeviceID string
	Location string
}

func Load() *Config {
	return &Config{
		GatewayID:      getEnv("GATEWAY_ID", "GATEWAY-001"),
		UDPListenAddr:  getEnv("UDP_LISTEN_ADDR", "0.0.0.0"),
		UDPSensorPort:  getEnvInt("UDP_SENSOR_PORT", 8888),
		GrpcServerAddr: getEnv("GRPC_SERVER_ADDR", "localhost:9090"),
		LogLevel:       getEnv("LOG_LEVEL", "info"),
		ReadIntervalMs: getEnvInt("READ_INTERVAL_MS", 1000),
		PumpMinSpeed:   getEnvInt("PUMP_MIN_SPEED", 10),
		PumpMaxSpeed:   getEnvInt("PUMP_MAX_SPEED", 100),
		Devices: []DeviceConfig{
			{DeviceID: "AC-001-F1", Location: "1F-北区"},
			{DeviceID: "AC-002-F1", Location: "1F-南区"},
			{DeviceID: "AC-003-F2", Location: "2F-东区"},
			{DeviceID: "AC-004-F2", Location: "2F-西区"},
			{DeviceID: "AC-005-F3", Location: "3F-餐饮区"},
		},
	}
}

func getEnv(key, fallback string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return fallback
}

func getEnvInt(key string, fallback int) int {
	if v, ok := os.LookupEnv(key); ok {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return fallback
}
