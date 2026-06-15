package com.mall.ac.grpc;

import com.mall.ac.grpc.api.*;
import com.mall.ac.service.DrainageService;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class DrainageGrpcService extends DrainPumpServiceGrpc.DrainPumpServiceImplBase {

    private final DrainageService drainageService;

    @Override
    public StreamObserver<LevelData> streamControl(StreamObserver<PumpControl> responseObserver) {
        String gatewayId = "unknown";
        try {
            gatewayId = io.grpc.Context.current()
                    .withValue(io.grpc.Metadata.Key.of("gateway-id",
                            io.grpc.Metadata.ASCII_STRING_MARSHALLER),
                    "unknown");
        } catch (Exception ignored) {}

        final String finalGatewayId = gatewayId;
        log.info("New bidirectional stream from gateway: {}", finalGatewayId);
        drainageService.registerStreamObserver(finalGatewayId, responseObserver);

        return new StreamObserver<LevelData>() {
            @Override
            public void onNext(LevelData levelData) {
                log.debug("Received level data: device={} level={}mm",
                        levelData.getDeviceId(), levelData.getLiquidLevelMm());
                try {
                    drainageService.processLevelData(levelData);
                } catch (Exception e) {
                    log.error("Error processing level data: {}", e.getMessage(), e);
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Stream error from gateway {}: {}", finalGatewayId, t.getMessage());
                drainageService.removeStreamObserver(finalGatewayId);
            }

            @Override
            public void onCompleted() {
                log.info("Stream completed from gateway: {}", finalGatewayId);
                drainageService.removeStreamObserver(finalGatewayId);
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void sendAck(ControlAck request, StreamObserver<ControlAck> responseObserver) {
        log.debug("Received ACK: device={} success={} actualSpeed={}%",
                request.getDeviceId(), request.getSuccess(), request.getActualSpeedPercent());
        drainageService.recordControlAck(
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
                .collect(Collectors.toList());
        drainageService.registerDevices(gatewayId, deviceInfoList);

        responseObserver.onNext(request);
        responseObserver.onCompleted();
    }
}
