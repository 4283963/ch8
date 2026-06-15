package grpcclient

import (
	"context"
	"fmt"
	"io"
	"sync"
	"time"

	"go-gateway/internal/config"
	"go-gateway/internal/logger"
	"go-gateway/internal/pump"
	"go-gateway/internal/udpserver"

	pb "go-gateway/proto/drainage"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
)

type Client struct {
	cfg          *config.Config
	conn         *grpc.ClientConn
	client       pb.DrainPumpServiceClient
	pumpCtrl     *pump.Controller
	sensorServer *udpserver.UDPServer
	stream       pb.DrainPumpService_StreamControlClient
	mu           sync.Mutex
	running      bool
}

func New(cfg *config.Config, pumpCtrl *pump.Controller, sensorServer *udpserver.UDPServer) *Client {
	return &Client{
		cfg:          cfg,
		pumpCtrl:     pumpCtrl,
		sensorServer: sensorServer,
	}
}

func (c *Client) Connect() error {
	conn, err := grpc.Dial(
		c.cfg.GrpcServerAddr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
		grpc.WithTimeout(5*time.Second),
	)
	if err != nil {
		return fmt.Errorf("failed to connect to gRPC server: %w", err)
	}
	c.conn = conn
	c.client = pb.NewDrainPumpServiceClient(conn)
	logger.L.Info("Connected to gRPC server at %s", c.cfg.GrpcServerAddr)

	if err := c.registerGateway(); err != nil {
		logger.L.Warn("Gateway registration failed: %v", err)
	}

	return nil
}

func (c *Client) registerGateway() error {
	devices := make([]*pb.DeviceInfo, 0, len(c.cfg.Devices))
	for _, d := range c.cfg.Devices {
		devices = append(devices, &pb.DeviceInfo{
			DeviceId: d.DeviceID,
			Location: d.Location,
		})
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	md := metadata.New(map[string]string{"gateway-id": c.cfg.GatewayID})
	ctx = metadata.NewOutgoingContext(ctx, md)

	resp, err := c.client.RegisterGateway(ctx, &pb.GatewayHello{
		GatewayId:   c.cfg.GatewayID,
		Devices:     devices,
		TimestampMs: time.Now().UnixMilli(),
	})
	if err != nil {
		return err
	}
	logger.L.Info("Gateway registered: %s, server echoed: %s", c.cfg.GatewayID, resp.GatewayId)
	return nil
}

func (c *Client) StartBidirectionalStream() error {
	ctx := context.Background()
	md := metadata.New(map[string]string{"gateway-id": c.cfg.GatewayID})
	ctx = metadata.NewOutgoingContext(ctx, md)

	stream, err := c.client.StreamControl(ctx)
	if err != nil {
		return fmt.Errorf("failed to create bidirectional stream: %w", err)
	}

	c.mu.Lock()
	c.stream = stream
	c.running = true
	c.mu.Unlock()

	logger.L.Info("Bidirectional gRPC stream established")

	go c.sendLoop()
	go c.recvLoop()

	return nil
}

func (c *Client) sendLoop() {
	ticker := time.NewTicker(time.Duration(c.cfg.ReadIntervalMs) * time.Millisecond)
	defer ticker.Stop()

	for range ticker.C {
		c.mu.Lock()
		stream := c.stream
		running := c.running
		c.mu.Unlock()

		if !running || stream == nil {
			return
		}

		readings := c.sensorServer.GetAllLatest()
		for _, r := range readings {
			if err := stream.Send(&pb.LevelData{
				DeviceId:      r.DeviceID,
				LiquidLevelMm: r.LiquidLevel,
				TimestampMs:   r.TimestampMs,
				SensorStatus:  r.SensorStatus,
			}); err != nil {
				logger.L.Error("Failed to send level data: %v", err)
				return
			}
		}
	}
}

func (c *Client) recvLoop() {
	for {
		c.mu.Lock()
		stream := c.stream
		running := c.running
		c.mu.Unlock()

		if !running || stream == nil {
			return
		}

		control, err := stream.Recv()
		if err == io.EOF {
			logger.L.Warn("gRPC stream closed by server")
			return
		}
		if err != nil {
			logger.L.Error("Failed to receive pump control: %v", err)
			return
		}

		logger.L.Info("Received control: device=%s speed=%d%% reason=%s",
			control.DeviceId, control.SpeedPercent, control.Reason)

		actualSpeed, setErr := c.pumpCtrl.SetSpeed(
			control.DeviceId,
			int(control.SpeedPercent),
			control.Reason,
		)

		go c.sendAck(control.DeviceId, actualSpeed, setErr)
	}
}

func (c *Client) sendAck(deviceID string, actualSpeed int, setErr error) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	ack := &pb.ControlAck{
		DeviceId:           deviceID,
		Success:            setErr == nil,
		ActualSpeedPercent: int32(actualSpeed),
		TimestampMs:        time.Now().UnixMilli(),
	}
	if setErr != nil {
		ack.Message = setErr.Error()
	} else {
		ack.Message = "OK"
	}

	if _, err := c.client.SendAck(ctx, ack); err != nil {
		logger.L.Error("Failed to send ACK for device %s: %v", deviceID, err)
	}
}

func (c *Client) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.running = false
	if c.stream != nil {
		c.stream.CloseSend()
	}
	if c.conn != nil {
		c.conn.Close()
	}
	logger.L.Info("gRPC client disconnected")
}
