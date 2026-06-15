package com.mall.ac.config;

import io.grpc.ServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Configuration
public class GrpcServerConfig {

    private static final int GRPC_CORE_THREADS = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);
    private static final int GRPC_MAX_THREADS  = Math.max(32, Runtime.getRuntime().availableProcessors() * 4);
    private static final int GRPC_QUEUE_CAP    = 1024;
    private static final long THREAD_KEEPALIVE = 60L;

    private static final int FLOW_CONTROL_WINDOW = 32 * 1024 * 1024;
    private static final int MAX_CONCURRENT_CALLS_PER_CONNECTION = 512;
    private static final int MAX_INBOUND_MSG_SIZE = 32 * 1024 * 1024;

    @Bean
    public GrpcServerConfigurer highThroughputServerConfigurer() {
        return serverBuilder -> {
            if (serverBuilder instanceof NettyServerBuilder nettyBuilder) {
                log.info("""
                    Applying gRPC HIGH-THROUGHPUT config:
                      - executor: core=%d max=%d queue=%d rejection=CallerRuns
                      - flowControlWindow=%dMB
                      - maxConcurrentCallsPerConn=%d
                      - maxInboundMessageSize=%dMB
                    """.formatted(
                    GRPC_CORE_THREADS, GRPC_MAX_THREADS, GRPC_QUEUE_CAP,
                    FLOW_CONTROL_WINDOW / 1024 / 1024,
                    MAX_CONCURRENT_CALLS_PER_CONNECTION,
                    MAX_INBOUND_MSG_SIZE / 1024 / 1024
                ));

                nettyBuilder.executor(createBoundedExecutor("grpc-handler"));

                nettyBuilder
                        .flowControlWindow(FLOW_CONTROL_WINDOW)
                        .maxInboundMessageSize(MAX_INBOUND_MSG_SIZE)
                        .maxConcurrentCallsPerConnection(MAX_CONCURRENT_CALLS_PER_CONNECTION);
            }
        };
    }

    public static ExecutorService createBoundedExecutor(String namePrefix) {
        AtomicInteger idx = new AtomicInteger(0);
        return new ThreadPoolExecutor(
                GRPC_CORE_THREADS,
                GRPC_MAX_THREADS,
                THREAD_KEEPALIVE,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(GRPC_QUEUE_CAP),
                r -> {
                    Thread t = new Thread(r, namePrefix + "-" + idx.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        if (!e.isShutdown()) {
                            log.warn("[GRPC-REJECTED] queue full ({}), caller runs. pool size={} active={}",
                                    e.getQueue().size(), e.getPoolSize(), e.getActiveCount());
                            super.rejectedExecution(r, e);
                        }
                    }
                }
        );
    }
}
