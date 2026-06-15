package com.mall.ac.grpc;

import com.mall.ac.grpc.api.*;
import com.mall.ac.service.AsyncBatchProcessor;
import com.mall.ac.service.DrainageService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class DrainageGrpcService extends DrainPumpServiceGrpc.DrainPumpServiceImplBase {

    private final DrainageService drainageService;
    private final AsyncBatchProcessor asyncProcessor;

    private final AtomicLong totalBatches = new AtomicLong(0);
    private final AtomicLong totalItems = new AtomicLong(0);

    @Override
    public StreamObserver<LevelData> streamControl(StreamObserver<PumpControl> responseObserver) {
        String gatewayId = extractGatewayId();
        log.info("Legacy stream opened from gateway: {}", gatewayId);
        drainageService.registerStreamObserver(gatewayId, responseObserver);

        return new StreamObserver<LevelData>() {
            @Override
            public void onNext(LevelData d) {
                asyncProcessor.submit(d.getDeviceId(), d.getLiquidLevelMm(),
                        d.getTimestampMs(), d.getSensorStatus());
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Legacy stream error from {}: {}", gatewayId, t.getMessage());
                drainageService.removeStreamObserver(gatewayId);
            }

            @Override
            public void onCompleted() {
                log.info("Legacy stream closed: {}", gatewayId);
                drainageService.removeStreamObserver(gatewayId);
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public StreamObserver<BatchLevelData> streamBatchControl(StreamObserver<PumpControl> responseObserver) {
        String gatewayId = extractGatewayId();
        log.info("BATCH stream opened from gateway: {}", gatewayId);
        drainageService.registerStreamObserver(gatewayId, responseObserver);

        return new StreamObserver<BatchLevelData>() {
            @Override
            public void onNext(BatchLevelData batch) {
                try {
                    long batches = totalBatches.incrementAndGet();
                    int count = batch.getItemsCount();
                    long items = totalItems.addAndGet(count);

                    if (batch.getDroppedCount() > 0) {
                        log.warn("[BACKPRESSURE] gateway {} reports {} batches dropped locally",
                                batch.getGatewayId(), batch.getDroppedCount());
                    }

                    for (int i = 0; i < count; i++) {
                        LevelData d = batch.getItems(i);
                        asyncProcessor.submit(d.getDeviceId(), d.getLiquidLevelMm(),
                                d.getTimestampMs(), d.getSensorStatus());
                    }

                    if (batches % 500 == 0) {
                        log.info("[STATS] processed {} batches, {} items total. queue stats: {}",
                                batches, items, asyncProcessor.getStats());
                    }
                } catch (Exception e) {
                    log.error("Batch processing error: {}", e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Batch stream error from {}: {}", gatewayId, t.getMessage());
                drainageService.removeStreamObserver(gatewayId);
            }

            @Override
            public void onCompleted() {
                log.info("Batch stream completed: {}", gatewayId);
                drainageService.removeStreamObserver(gatewayId);
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void sendAck(ControlAck request, StreamObserver<ControlAck> responseObserver) {
        log.debug("ACK: device={} success={} speed={}%",
                request.getDeviceId(), request.getSuccess(), request.getActualSpeedPercent());
        asyncProcessor.recordControlLog(
                request.getDeviceId(),
                request.getSuccess(),
                request.getActualSpeedPercent(),
                request.getMessage());
        responseObserver.onNext(request);
        responseObserver.onCompleted();
    }

    @Override
    public void registerGateway(GatewayHello request, StreamObserver<GatewayHello> responseObserver) {
        String gatewayId = request.getGatewayId();
        log.info("Gateway registration: {} with {} devices",
                gatewayId, request.getDevicesCount());

        List<String[]> deviceInfoList = request.getDevicesList().stream()
                .map(d -> new String[]{d.getDeviceId(), d.getLocation()})
                .toList();
        drainageService.registerDevices(gatewayId, deviceInfoList);

        responseObserver.onNext(request);
        responseObserver.onCompleted();
    }

    private String extractGatewayId() {
        try {
            var ctx = io.grpc.Context.current();
            var key = io.grpc.Context.keyWithDefault("gateway-id", "unknown");
            Object val = key.get(ctx);
            return val != null ? val.toString() : "unknown";
        } catch (Exception e) {
            return "unknown-" + Thread.currentThread().getId();
        }
    }
}
