package main

import (
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"go-gateway/internal/config"
	"go-gateway/internal/grpcclient"
	"go-gateway/internal/logger"
	"go-gateway/internal/pump"
	"go-gateway/internal/udpserver"
)

func main() {
	cfg := config.Load()
	logger.Init(cfg.LogLevel)

	logger.L.Info("=== Mall AC Drainage Gateway Starting ===")
	logger.L.Info("Gateway ID: %s", cfg.GatewayID)
	logger.L.Info("Devices managed: %d", len(cfg.Devices))

	pumpCtrl := pump.NewController(cfg)
	logger.L.Info("Pump controller initialized")

	sensorServer := udpserver.New(cfg)
	sensorServer.SetCallback(func(r udpserver.SensorReading) {
		logger.L.Debug("Sensor: %s = %.1fmm", r.DeviceID, r.LiquidLevel)
	})

	if err := sensorServer.Start(); err != nil {
		logger.L.Error("Failed to start UDP server: %v", err)
		os.Exit(1)
	}
	defer sensorServer.Stop()

	grpcClient := grpcclient.New(cfg, pumpCtrl, sensorServer)
	if err := grpcClient.Connect(); err != nil {
		logger.L.Error("Failed to connect gRPC: %v, will retry in background: %v", err, err)
		go retryConnect(grpcClient)
	} else {
		if err := grpcClient.StartBidirectionalStream(); err != nil {
			logger.L.Error("Failed to start stream: %v", err)
		}
	}
	defer grpcClient.Close()

	go printStatusLoop(pumpCtrl, sensorServer)

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	sig := <-sigChan
	logger.L.Info("Received signal %v, shutting down...", sig)
}

func retryConnect(gc *grpcclient.Client) {
	for i := 0; i < 10; i++ {
		time.Sleep(3 * time.Second)
		logger.L.Info("Retrying gRPC connection (attempt %d)...", i+1)
		if err := gc.Connect(); err == nil {
			if err := gc.StartBidirectionalStream(); err != nil {
				logger.L.Error("Stream start failed after retry: %v", err)
			} else {
				logger.L.Info("Successfully reconnected")
				return
			}
		}
	}
	logger.L.Error("Max retries exceeded, giving up gRPC connection")
}

func printStatusLoop(pc *pump.Controller, ss *udpserver.UDPServer) {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		levels := ss.GetAllLatest()
		pumps := pc.GetAllStates()
		logger.L.Info("--- Status --- Sensors: %d, Pumps: %d", len(levels), len(pumps))
		for _, p := range pumps {
			lvl := "-"
			for _, lv := range levels {
				if lv.DeviceID == p.DeviceID {
					lvl = fmt.Sprintf("%.1fmm", lv.LiquidLevel)
				}
			}
			logger.L.Info("  %s: level=%s pump=%d%%", p.DeviceID, lvl, p.ActualSpeed)
		}
	}
}
