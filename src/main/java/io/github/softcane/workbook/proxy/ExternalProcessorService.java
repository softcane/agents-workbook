package io.github.softcane.workbook.proxy;

import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.config.core.v3.HeaderMap;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedBodyResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedImmediateResponse;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public final class ExternalProcessorService extends ExternalProcessorGrpc.ExternalProcessorImplBase {
    private static final Logger log = LoggerFactory.getLogger(ExternalProcessorService.class);

    private final ObjectMapper json;
    private final ProxyExchangeService exchanges;
    private final long maximumRequestBytes;

    public ExternalProcessorService(ObjectMapper json, ProxyExchangeService exchanges,
            @Value("${workbook-proxy.maximum-request-bytes:67108864}") long maximumRequestBytes) {
        this.json = json;
        this.exchanges = exchanges;
        this.maximumRequestBytes = maximumRequestBytes;
    }

    @Override
    public StreamObserver<ProcessingRequest> process(StreamObserver<ProcessingResponse> responses) {
        var state = new RequestState(responses);
        if (responses instanceof ServerCallStreamObserver<ProcessingResponse> server) {
            server.setOnCancelHandler(state::cancel);
        }
        return new StreamObserver<>() {
            @Override
            public void onNext(ProcessingRequest request) {
                try {
                    switch (request.getRequestCase()) {
                        case REQUEST_HEADERS -> state.headers(request.getRequestHeaders());
                        case REQUEST_BODY -> state.incomingBody(request.getRequestBody().getBody().toByteArray(),
                                request.getRequestBody().getEndOfStream());
                        case REQUEST_TRAILERS -> state.startExchange();
                        default -> {
                            log.warn("ext_proc message rejected: case={}", request.getRequestCase());
                            state.fail("unsupported_ext_proc_message",
                                    "The local proxy received an unexpected ext_proc message.");
                        }
                    }
                } catch (Exception error) {
                    // Type and top-level message only: a wrapped failure's cause can carry request bytes.
                    log.warn("intercepted request rejected: error={} message={}",
                            error.getClass().getSimpleName(), error.getMessage());
                    state.fail("invalid_proxy_request", safeMessage(error));
                }
            }

            @Override
            public void onError(Throwable error) {
                log.debug("ext_proc stream ended by the client: error={}", error.getClass().getSimpleName());
                state.cancel();
            }

            @Override public void onCompleted() { state.inputCompleted = true; }
        };
    }

    private static String safeMessage(Exception error) {
        if (error instanceof IllegalArgumentException && error.getMessage() != null) return error.getMessage();
        return "The local proxy rejected the intercepted request.";
    }

    private final class RequestState implements DownstreamSink {
        private final StreamObserver<ProcessingResponse> responses;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private Map<String, List<String>> headers;
        private String path;
        private ProxyProvider provider;
        private ProxyExchangeService.ExchangeHandle exchange;
        private boolean started;
        private volatile boolean inputCompleted;

        private RequestState(StreamObserver<ProcessingResponse> responses) {
            this.responses = responses;
        }

        private synchronized void headers(HttpHeaders requestHeaders) {
            if (headers != null || started) throw new IllegalArgumentException("Duplicate request headers");
            headers = fromHeaderMap(requestHeaders.getHeaders());
            path = first(headers, ":path");
            String cleanPath = path == null ? null : path.split("\\?", 2)[0];
            provider = ProxyProvider.forPath(cleanPath);
            if (requestHeaders.getEndOfStream()) startExchange();
        }

        private synchronized void incomingBody(byte[] bytes, boolean endOfStream) {
            if (headers == null) throw new IllegalArgumentException("Request body arrived before headers");
            if (started) throw new IllegalArgumentException("Request body arrived after exchange start");
            if ((long) body.size() + bytes.length > maximumRequestBytes) {
                throw new IllegalArgumentException("Request exceeded the 64 MiB local limit");
            }
            body.writeBytes(bytes);
            if (endOfStream) startExchange();
        }

        private synchronized void startExchange() {
            if (started || terminal.get()) return;
            if (headers == null || path == null) throw new IllegalArgumentException("Missing intercepted request headers");
            started = true;
            exchange = exchanges.start(provider, path, headers, body.toByteArray(), this);
        }

        @Override
        public synchronized void headers(int status, Map<String, List<String>> responseHeaders) {
            if (terminal.get()) return;
            var headerMap = HeaderMap.newBuilder().addHeaders(header(":status", Integer.toString(status)));
            responseHeaders.forEach((name, values) -> values.forEach(value -> headerMap.addHeaders(header(name, value))));
            var immediate = StreamedImmediateResponse.newBuilder().setHeadersResponse(HttpHeaders.newBuilder()
                    .setHeaders(headerMap)
                    .setEndOfStream(false));
            responses.onNext(ProcessingResponse.newBuilder().setStreamedImmediateResponse(immediate).build());
        }

        @Override
        public synchronized void body(byte[] bytes, boolean endOfStream) {
            if (terminal.get()) return;
            var immediate = StreamedImmediateResponse.newBuilder().setBodyResponse(StreamedBodyResponse.newBuilder()
                    .setBody(ByteString.copyFrom(bytes))
                    .setEndOfStream(endOfStream));
            responses.onNext(ProcessingResponse.newBuilder().setStreamedImmediateResponse(immediate).build());
            if (endOfStream && terminal.compareAndSet(false, true)) responses.onCompleted();
        }

        @Override
        public synchronized void failure(String code, String message) {
            fail(code, message);
        }

        private synchronized void fail(String code, String message) {
            if (terminal.get()) return;
            headers(502, Map.of("content-type", List.of("application/json"),
                    "cache-control", List.of("no-store")));
            body(errorPayload(code, message), true);
        }

        private synchronized void cancel() {
            if (terminal.compareAndSet(false, true) && exchange != null) exchange.cancel();
        }
    }

    private static Map<String, List<String>> fromHeaderMap(HeaderMap map) {
        var collected = new LinkedHashMap<String, List<String>>();
        for (HeaderValue header : map.getHeadersList()) {
            String value = header.getRawValue().isEmpty()
                    ? header.getValue()
                    : header.getRawValue().toStringUtf8();
            collected.computeIfAbsent(header.getKey().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(value);
        }
        return collected.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private static String first(Map<String, List<String>> headers, String name) {
        var values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static HeaderValue header(String name, String value) {
        return HeaderValue.newBuilder()
                .setKey(name)
                .setRawValue(ByteString.copyFromUtf8(value))
                .build();
    }

    /**
     * Serialised rather than hand-escaped: a rejection message can carry a tab or a control character
     * that string replacement leaves as an illegal raw byte inside a JSON string, and Claude Code parses
     * this body to decide how to retry.
     */
    private byte[] errorPayload(String code, String message) {
        var error = json.createObjectNode();
        error.putObject("error").put("code", code).put("message", message);
        return json.writeValueAsBytes(error);
    }
}
