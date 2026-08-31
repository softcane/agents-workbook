package io.github.softcane.workbook.proxy;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public final class GrpcServerLifecycle implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private final ExternalProcessorService service;
    private final String address;
    private final int port;
    private final int maximumMessageBytes;
    private volatile Server server;

    public GrpcServerLifecycle(ExternalProcessorService service,
            @Value("${workbook-proxy.grpc-address:127.0.0.1}") String address,
            @Value("${workbook-proxy.grpc-port:50051}") int port,
            @Value("${workbook-proxy.maximum-request-bytes:67108864}") int maximumMessageBytes) {
        this.service = service;
        this.address = address;
        this.port = port;
        this.maximumMessageBytes = maximumMessageBytes;
    }

    @Override
    public synchronized void start() {
        if (server != null) return;
        try {
            server = NettyServerBuilder.forAddress(new InetSocketAddress(address, port))
                    .maxInboundMessageSize(maximumMessageBytes)
                    .addService(service)
                    .build()
                    .start();
            log.info("ext_proc gRPC server listening on {}:{}", address, port);
        } catch (IOException error) {
            // Binding is the one failure that must stop startup: an unbound filter silently drops capture.
            throw new IllegalStateException("Could not bind ext_proc gRPC server to " + address + ":" + port, error);
        }
    }

    @Override
    public synchronized void stop() {
        if (server == null) return;
        server.shutdown();
        try {
            if (!server.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                log.warn("ext_proc gRPC server did not drain within {}, forcing shutdown", SHUTDOWN_GRACE);
                server.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        } finally {
            server = null;
            log.info("ext_proc gRPC server stopped");
        }
    }

    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
}
