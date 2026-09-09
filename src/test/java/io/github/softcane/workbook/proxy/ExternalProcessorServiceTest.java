package io.github.softcane.workbook.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpServer;
import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpBody;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.grpc.stub.StreamObserver;
import io.github.softcane.workbook.proxy.trace.TraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExternalProcessorServiceTest {
    @Test
    void convertsBufferedRequestIntoStreamedImmediateContinuationResponse() throws Exception {
        var upstream = deterministicUpstream();
        upstream.start();
        var responses = new ArrayList<ProcessingResponse>();
        var done = new CountDownLatch(1);
        var observer = new StreamObserver<ProcessingResponse>() {
            @Override public void onNext(ProcessingResponse value) { synchronized (responses) { responses.add(value); } }
            @Override public void onError(Throwable error) { done.countDown(); }
            @Override public void onCompleted() { done.countDown(); }
        };

        try (var exchange = new ProxyExchangeService(new ObjectMapper(), new TraceStore(50), new ConversationStateStore(new ObjectMapper()), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), ProxyExchangeService.Settings.DEFAULTS.withUpstreamTimeout(Duration.ofSeconds(5)))) {
            var service = new ExternalProcessorService(new ObjectMapper(), exchange, 64L * 1024 * 1024);
            var requests = service.process(observer);
            requests.onNext(ProcessingRequest.newBuilder().setRequestHeaders(HttpHeaders.newBuilder()
                    .setHeaders(headers(
                            header(":path", "/backend-api/codex/responses"),
                            header("content-type", "application/json")))).build());
            requests.onNext(ProcessingRequest.newBuilder().setRequestBody(HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8("""
                            {"model":"gpt-test","input":[{"role":"user","content":"hello"}],"stream":true}
                            """))
                    .setEndOfStream(true)).build());
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            upstream.stop(0);
        }

        String downstream = responses.stream()
                .filter(response -> response.hasStreamedImmediateResponse()
                        && response.getStreamedImmediateResponse().hasBodyResponse())
                .map(response -> response.getStreamedImmediateResponse().getBodyResponse().getBody().toStringUtf8())
                .reduce("", String::concat);
        assertThat(responses.getFirst().getStreamedImmediateResponse().getHeadersResponse()
                .getHeaders().getHeadersList()).anySatisfy(value -> {
                    assertThat(value.getKey()).isEqualTo(":status");
                    assertThat(value.getRawValue().toStringUtf8()).isEqualTo("200");
                });
        assertThat(downstream).contains("final answer").doesNotContain("private notes");
        assertThat(responses.getLast().getStreamedImmediateResponse().getBodyResponse().getEndOfStream()).isTrue();
    }

    @Test
    void propagatesRealGrpcCancellationToTheActiveProxyExchange() throws Exception {
        var upstreamStarted = new CountDownLatch(1);
        var releaseUpstream = new CountDownLatch(1);
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", http -> {
            http.getRequestBody().readAllBytes();
            http.getResponseHeaders().set("content-type", "text/event-stream");
            http.sendResponseHeaders(200, 0);
            http.getResponseBody().flush();
            upstreamStarted.countDown();
            try {
                releaseUpstream.await();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } finally {
                http.close();
            }
        });
        upstream.start();

        var traces = new TraceStore(50);
        io.grpc.Server grpc = null;
        io.grpc.ManagedChannel channel = null;
        try (var exchange = new ProxyExchangeService(new ObjectMapper(), traces,
                new ConversationStateStore(new ObjectMapper()), new SimpleMeterRegistry(),
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                ProxyExchangeService.Settings.DEFAULTS.withUpstreamTimeout(Duration.ofSeconds(5)))) {
            var service = new ExternalProcessorService(new ObjectMapper(), exchange, 64L * 1024 * 1024);
            grpc = io.grpc.ServerBuilder.forPort(0).addService(service).build().start();
            channel = io.grpc.ManagedChannelBuilder.forAddress("127.0.0.1", grpc.getPort())
                    .usePlaintext().build();

            var clientCall = new java.util.concurrent.atomic.AtomicReference<
                    io.grpc.stub.ClientCallStreamObserver<ProcessingRequest>>();
            var clientDone = new CountDownLatch(1);
            var responseObserver =
                    new io.grpc.stub.ClientResponseObserver<ProcessingRequest, ProcessingResponse>() {
                        @Override
                        public void beforeStart(
                                io.grpc.stub.ClientCallStreamObserver<ProcessingRequest> requestStream) {
                            clientCall.set(requestStream);
                        }

                        @Override public void onNext(ProcessingResponse value) { }
                        @Override public void onError(Throwable error) { clientDone.countDown(); }
                        @Override public void onCompleted() { clientDone.countDown(); }
                    };
            var requests = ExternalProcessorGrpc.newStub(channel).process(responseObserver);
            requests.onNext(ProcessingRequest.newBuilder().setRequestHeaders(HttpHeaders.newBuilder()
                    .setHeaders(headers(
                            header(":path", "/backend-api/codex/responses"),
                            header("content-type", "application/json")))).build());
            requests.onNext(ProcessingRequest.newBuilder().setRequestBody(HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8("""
                            {"model":"gpt-test","input":[{"role":"user","content":"hello"}],"stream":true}
                            """))
                    .setEndOfStream(true)).build());
            assertThat(upstreamStarted.await(5, TimeUnit.SECONDS)).isTrue();

            clientCall.get().cancel("test cancellation", null);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (traces.snapshot().stream().noneMatch(trace -> "cancelled".equals(trace.status()))
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(traces.snapshot()).anySatisfy(trace -> assertThat(trace.status()).isEqualTo("cancelled"));
            assertThat(clientDone.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseUpstream.countDown();
            if (channel != null) channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            if (grpc != null) grpc.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            upstream.stop(0);
        }
    }

    private HttpServer deterministicUpstream() throws Exception {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/backend-api/codex/responses", exchange -> {
            boolean continuation = json.readTree(exchange.getRequestBody()).toString().contains("function_call_output");
            String wire = continuation
                    ? "event: response.output_text.delta\ndata: {\"delta\":\"final answer\"}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"response\":{\"id\":\"resp_final\",\"output\":[]}}\n\n"
                    : """
                      event: response.output_item.added
                      data: {"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"workbook","arguments":""}}

                      event: response.function_call_arguments.delta
                      data: {"delta":"{\\"work\\":\\"private notes\\"}"}

                      event: response.completed
                      data: {"response":{"output":[{"type":"function_call","call_id":"call_1","name":"workbook","arguments":"{\\"work\\":\\"private notes\\"}"}]}}

                      """;
            byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private HeaderMap headers(HeaderValue... values) {
        return HeaderMap.newBuilder().addAllHeaders(List.of(values)).build();
    }

    private HeaderValue header(String name, String value) {
        return HeaderValue.newBuilder().setKey(name).setRawValue(ByteString.copyFromUtf8(value)).build();
    }
}
