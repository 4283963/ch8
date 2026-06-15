package grpcclient

import (
	"context"
	"fmt"
	"io"
	"sync"
	"sync/atomic"
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

const (
	throttleWindowMs = 100
	batchWindowMs    = 200
	ringBufferSize   = 32
	highWatermark    = 0.8
)

type deviceLatest struct {
	level        float64
	timestampMs  int64
	sensorStatus int32
	lastSentMs   int64
}

type ringBuffer struct {
	buf   []*pb.BatchLevelData
	mu    sync.Mutex
	head  int
	tail  int
	size  int
	count int
	drops uint64
}

func newRingBuffer(size int) *ringBuffer {
	return &ringBuffer{
		buf:  make([]*pb.BatchLevelData, size),
		size: size,
	}
}

func (r *ringBuffer) push(b *pb.BatchLevelData) (dropped bool) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.count == r.size {
		r.head = (r.head + 1) % r.size
		r.count--
		r.drops++
		dropped = true
	}
	r.buf[r.tail] = b
	r.tail = (r.tail + 1) % r.size
	r.count++
	return
}

func (r *ringBuffer) pop() (*pb.BatchLevelData, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.count == 0 {
		return nil, false
	}
	b := r.buf[r.head]
	r.buf[r.head] = nil
	r.head = (r.head + 1) % r.size
	r.count--
	return b, true
}

func (r *ringBuffer) usage() float64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return float64(r.count) / float64(r.size)
}

func (r *ringBuffer) getAndResetDrops() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	d := r.drops
	r.drops = 0
	return d
}

type Client struct {
	cfg          *config.Config
	conn         *grpc.ClientConn
	client       pb.DrainPumpServiceClient
	pumpCtrl     *pump.Controller
	sensorServer *udpserver.UDPServer

	stream  pb.DrainPumpService_StreamBatchControlClient
	mu      sync.Mutex
	running bool

	latestMu sync.RWMutex
	latest   map[string]*deviceLatest

	ring *ringBuffer

	totalSent     uint64
	totalReceived uint64
}

func New(cfg *config.Config, pumpCtrl *pump.Controller, sensorServer *udpserver.UDPServer) *Client {
	return &Client{
		cfg:          cfg,
		pumpCtrl:     pumpCtrl,
		sensorServer: sensorServer,
		latest:       make(map[string]*deviceLatest),
		ring:         newRingBuffer(ringBufferSize),
	}
}

func (c *Client) Connect() error {
	conn, err := grpc.Dial(
		c.cfg.GrpcServerAddr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithBlock(),
		grpc.WithTimeout(5*time.Second),
		grpc.WithDefaultCallOptions(
			grpc.MaxCallRecvMsgSize(16*1024*1024),
			grpc.MaxCallSendMsgSize(16*1024*1024),
		),
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

	c.sensorServer.SetCallback(c.onSensorReading)

	return nil
}

func (c *Client) onSensorReading(r udpserver.SensorReading) {
	now := time.Now().UnixMilli()

	c.latestMu.Lock()
	prev, ok := c.latest[r.DeviceID]
	if !ok {
		c.latest[r.DeviceID] = &deviceLatest{
			level:        r.LiquidLevel,
			timestampMs:  r.TimestampMs,
			sensorStatus: r.SensorStatus,
			lastSentMs:   0,
		}
		c.latestMu.Unlock()
		return
	}

	prev.level = r.LiquidLevel
	prev.timestampMs = r.TimestampMs
	prev.sensorStatus = r.SensorStatus

	if now-prev.lastSentMs < throttleWindowMs {
		c.latestMu.Unlock()
		return
	}
	c.latestMu.Unlock()
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

	stream, err := c.client.StreamBatchControl(ctx)
	if err != nil {
		return fmt.Errorf("failed to create bidirectional stream: %w", err)
	}

	c.mu.Lock()
	c.stream = stream
	c.running = true
	c.mu.Unlock()

	logger.L.Info("Bidirectional BATCH gRPC stream established (throttle=%dms batch=%dms ring=%d)",
		throttleWindowMs, batchWindowMs, ringBufferSize)

	go c.batchCollector()
	go c.batchSender()
	go c.recvLoop()
	go c.metricsReporter()

	return nil
}

func (c *Client) batchCollector() {
	ticker := time.NewTicker(time.Duration(batchWindowMs) * time.Millisecond)
	defer ticker.Stop()

	var localDropped uint64

	for range ticker.C {
		c.mu.Lock()
		running := c.running
		c.mu.Unlock()
		if !running {
			return
		}

		now := time.Now().UnixMilli()
		batch := &pb.BatchLevelData{
			GatewayId:        c.cfg.GatewayID,
			BatchTimestampMs: now,
		}

		c.latestMu.Lock()
		for devID, d := range c.latest {
			batch.Items = append(batch.Items, &pb.LevelData{
				DeviceId:      devID,
				LiquidLevelMm: d.level,
				TimestampMs:   d.timestampMs,
				SensorStatus:  d.sensorStatus,
			})
			d.lastSentMs = now
		}
		c.latestMu.Unlock()

		if len(batch.Items) == 0 {
			continue
		}

		dropped := c.ring.push(batch)
		if dropped {
			localDropped++
			if c.ring.usage() >= highWatermark {
				logger.L.Warn("[BACKPRESSURE] Ring buffer usage=%.0f%% - Java consumer slow, dropping old batches",
					c.ring.usage()*100)
			}
		}
		batch.DroppedCount = int32(localDropped)
		if !dropped {
			localDropped = 0
		}

	}
}

func (c *Client) batchSender() {
	for {
		c.mu.Lock()
		running := c.running
		stream := c.stream
		c.mu.Unlock()
		if !running {
			return
		}

		batch, ok := c.ring.pop()
		if !ok {
			time.Sleep(20 * time.Millisecond)
			continue
		}
		if stream == nil {
			time.Sleep(50 * time.Millisecond)
			continue
		}

		if err := stream.Send(batch); err != nil {
			logger.L.Error("Failed to send batch: %v", err)
			time.Sleep(100 * time.Millisecond)
			continue
		}
		atomic.AddUint64(&c.totalSent, uint64(len(batch.Items)))
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

		atomic.AddUint64(&c.totalReceived, 1)
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

func (c *Client) metricsReporter() {
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	var prevSent, prevRecv uint64
	for range ticker.C {
		sent := atomic.LoadUint64(&c.totalSent)
		recv := atomic.LoadUint64(&c.totalReceived)
		drops := c.ring.getAndResetDrops()
		usage := c.ring.usage()
		logger.L.Info("[METRICS] sent_items=%d recv_controls=%d ring_usage=%.0f%% drops=%d",
			sent-prevSent, recv-prevRecv, usage*100, drops)
		prevSent = sent
		prevRecv = recv
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
