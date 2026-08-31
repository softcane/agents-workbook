package io.github.softcane.workbook.proxy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.github.softcane.workbook.proxy.provider.CaptureRefusedException;
import io.github.softcane.workbook.proxy.provider.ClaudeProtocol;
import io.github.softcane.workbook.proxy.provider.CodexProtocol;
import io.github.softcane.workbook.proxy.provider.HiddenEffort;
import io.github.softcane.workbook.proxy.provider.NoteStyle;
import io.github.softcane.workbook.proxy.provider.SseParser;
import io.github.softcane.workbook.proxy.trace.TraceStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public final class ProxyExchangeService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProxyExchangeService.class);
    private static final Set<String> REQUEST_HEADERS_DENIED = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade", "accept-encoding",
            "content-encoding", "x-workbook-provider", "x-workbook-test-upstream");
    private static final Set<String> RESPONSE_HEADERS_DENIED = Set.of(
            "content-length", "connection", "transfer-encoding", "upgrade", "content-encoding");

    /**
     * The client-visible call may legitimately stream for a long time, so it keeps a deadline well past
     * anything a model response takes. Only the hidden call is held to the short deadline that has to fit
     * inside Claude Code's 300-second silent-stream watchdog.
     */
    private static final Duration VISIBLE_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Deadline NO_DEADLINE = () -> false;
    /**
     * Extra workbook calls the model may make before the proxy stops folding them in and sends the
     * client's own request instead. Each one costs a full generation the client is waiting through.
     */
    private static final int MAXIMUM_PRIVATE_LOOPS = 2;
    /** Ceiling on the ungated part of a response held in memory. Nothing here is ever written to disk. */
    private static final int MAXIMUM_GATE_BYTES = 1024 * 1024;
    /** Enough of a refused hidden call to read its error category from, and no more. */
    private static final int MAXIMUM_REJECTION_BYTES = 4096;
    private static final Duration KEEPALIVE_IDLE = Duration.ofSeconds(15);
    private static final Duration KEEPALIVE_POLL = Duration.ofSeconds(1);
    private static final byte[] SSE_KEEPALIVE = ":\n\n".getBytes(StandardCharsets.UTF_8);
    private static final String CLAUDE_SESSION_HEADER = "x-claude-code-session-id";
    private static final String CLAUDE_AGENT_HEADER = "x-claude-code-agent-id";
    private static final String CODEX_SESSION_HEADER = "session-id";
    private static final String CODEX_THREAD_HEADER = "thread-id";
    private static final String CODEX_CLIENT_METADATA = "client_metadata";

    private final ObjectMapper json;
    private final TraceStore traces;
    /**
     * Per-process, so a dashboard session id cannot be walked back to the Claude Code session id that
     * produced it, and so two runs of the proxy never share a grouping key.
     */
    private final String correlationSalt = UUID.randomUUID().toString();
    private final URI internalBaseUri;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrency;
    private final Settings settings;

    /**
     * Every tunable in one carrier, so a caller names the value it cares about instead of counting past
     * the ones it does not. Each field is validated once here rather than at the point it is used.
     */
    public record Settings(
            Duration upstreamTimeout,
            Duration hiddenTimeout,
            int maximumConcurrentRequests,
            int maximumWorkbookBytes,
            int maximumRequestBytes,
            boolean testUpstreamEnabled,
            NoteStyle noteStyle,
            HiddenEffort hiddenEffort) {

        /** Matches the shipped {@code application.yml}, so a test runs the deployed configuration. */
        public static final Settings DEFAULTS = new Settings(Duration.ofMinutes(5), Duration.ofSeconds(60),
                8, 256 * 1024, 64 * 1024 * 1024, false, NoteStyle.REASONING, HiddenEffort.INHERIT);

        public Settings {
            if (upstreamTimeout == null || upstreamTimeout.isNegative() || upstreamTimeout.isZero()) {
                throw new IllegalArgumentException("upstreamTimeout must be positive");
            }
            if (hiddenTimeout == null || hiddenTimeout.isNegative() || hiddenTimeout.isZero()) {
                throw new IllegalArgumentException("hiddenTimeout must be positive");
            }
            if (maximumConcurrentRequests < 1) {
                throw new IllegalArgumentException("maximumConcurrentRequests must be positive");
            }
            if (maximumWorkbookBytes < 1) {
                throw new IllegalArgumentException("maximumWorkbookBytes must be positive");
            }
            if (maximumRequestBytes < 1) {
                throw new IllegalArgumentException("maximumRequestBytes must be positive");
            }
            if (noteStyle == null) throw new IllegalArgumentException("noteStyle must not be null");
            if (hiddenEffort == null) throw new IllegalArgumentException("hiddenEffort must not be null");
        }

        public Settings withUpstreamTimeout(Duration replacement) {
            return new Settings(replacement, hiddenTimeout, maximumConcurrentRequests, maximumWorkbookBytes,
                    maximumRequestBytes, testUpstreamEnabled, noteStyle, hiddenEffort);
        }

        public Settings withHiddenTimeout(Duration replacement) {
            return new Settings(upstreamTimeout, replacement, maximumConcurrentRequests, maximumWorkbookBytes,
                    maximumRequestBytes, testUpstreamEnabled, noteStyle, hiddenEffort);
        }

        public Settings withHiddenEffort(HiddenEffort replacement) {
            return new Settings(upstreamTimeout, hiddenTimeout, maximumConcurrentRequests, maximumWorkbookBytes,
                    maximumRequestBytes, testUpstreamEnabled, noteStyle, replacement);
        }
    }

    @Autowired
    public ProxyExchangeService(
            ObjectMapper json,
            TraceStore traces,
            @Value("${workbook-proxy.internal-base-uri:http://127.0.0.1:10001}") String internalBaseUri,
            @Value("${workbook-proxy.upstream-timeout:PT5M}") Duration timeout,
            @Value("${workbook-proxy.hidden-timeout:PT60S}") Duration hiddenTimeout,
            @Value("${workbook-proxy.maximum-concurrent-requests:8}") int maximumConcurrent,
            @Value("${workbook-proxy.maximum-workbook-bytes:262144}") int maximumWorkbookBytes,
            @Value("${workbook-proxy.maximum-request-bytes:67108864}") int maximumRequestBytes,
            @Value("${workbook-proxy.test-upstream-enabled:false}") boolean testUpstreamEnabled,
            @Value("${workbook-proxy.note-style:REASONING}") NoteStyle noteStyle,
            @Value("${workbook-proxy.hidden-effort:INHERIT}") HiddenEffort hiddenEffort) {
        this(json, traces, URI.create(internalBaseUri), new Settings(timeout, hiddenTimeout, maximumConcurrent,
                maximumWorkbookBytes, maximumRequestBytes, testUpstreamEnabled, noteStyle, hiddenEffort));
    }

    public ProxyExchangeService(ObjectMapper json, TraceStore traces, URI internalBaseUri, Settings settings) {
        this.json = json;
        this.traces = traces;
        this.internalBaseUri = internalBaseUri;
        this.settings = settings;
        this.concurrency = new Semaphore(settings.maximumConcurrentRequests());
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.upstreamTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        // Configuration only: no request ever reaches this log line.
        log.info("workbook proxy ready: upstream={} hiddenTimeout={} maximumConcurrent={} noteStyle={} "
                        + "hiddenEffort={} testUpstream={}", internalBaseUri, settings.hiddenTimeout(),
                settings.maximumConcurrentRequests(), settings.noteStyle().label(),
                settings.hiddenEffort().label(), settings.testUpstreamEnabled());
    }

    public ExchangeHandle start(ProxyProvider provider, String path, Map<String, List<String>> headers,
            byte[] body, DownstreamSink downstream) {
        if (!concurrency.tryAcquire()) {
            // Silent until now: a client seeing a 502 had nothing on this side to correlate it with.
            log.warn("intercepted request rejected, the local proxy is at its concurrency limit: provider={} limit={}",
                    provider.wireName(), settings.maximumConcurrentRequests());
            downstream.failure("proxy_busy", "The local proxy already has "
                    + settings.maximumConcurrentRequests() + " active intercepted requests.");
            return new ExchangeHandle(CompletableFuture.failedFuture(
                    new IllegalStateException("proxy concurrency limit reached")), () -> { });
        }
        var state = new ExchangeState();
        var trace = traces.start(provider.wireName(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
        CompletableFuture<Void> completion = CompletableFuture.runAsync(() -> {
            state.thread = Thread.currentThread();
            try {
                process(provider, path, headers, body, downstream, trace, state);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                if (state.cancelled.compareAndSet(false, true)) trace.cancel();
            } catch (Exception error) {
                trace.fail(error.getClass().getSimpleName());
                // Only this exception's own hand-written top-level message is logged, never its cause chain
                // or a stack trace -- a wrapped JSON-parse failure can carry a fragment of the provider body
                // in its cause, which SECURITY.md forbids logging.
                log.warn("proxy exchange failed: provider={} error={} message={}",
                        provider.wireName(), error.getClass().getSimpleName(), error.getMessage());
                downstream.failure("proxy_upstream_error", safeMessage(error));
                throw new CompletionException(error);
            } finally {
                trace.finished();
                concurrency.release();
            }
        }, executor);
        return new ExchangeHandle(completion, () -> {
            if (state.cancelled.compareAndSet(false, true)) trace.cancel();
            Thread thread = state.thread;
            if (thread != null) thread.interrupt();
        });
    }

    /**
     * Capture is best effort; the client's own task is not. Every path that fails to record a workbook
     * ends by sending the client's unmodified request and forwarding whatever the provider answers, so a
     * capture defect costs a trace row and never a real turn.
     */
    private void process(ProxyProvider provider, String path, Map<String, List<String>> headers,
            byte[] body, DownstreamSink downstream, TraceStore.TraceHandle trace, ExchangeState state)
            throws Exception {
        byte[] decoded = decodeRequestBody(headers, body);
        JsonNode parsed = json.readTree(decoded);
        if (!(parsed instanceof ObjectNode original)) {
            throw new IllegalArgumentException("Provider request must be a JSON object");
        }
        trace.setModel(original.path("model").asText(""));
        Diagnostics.event("request.received",
                "provider", provider.wireName(),
                "noteStyle", settings.noteStyle().label(),
                "model", original.path("model").asText(""),
                "maxTokens", original.path("max_tokens").asInt(0),
                "thinkingType", original.path("thinking").path("type").asText("absent"),
                // Both efforts, because a note is only interpretable next to the depth that produced it:
                // what the client asked for, and what this proxy overrode it with on the hidden call.
                "clientEffort", original.path("output_config").path("effort").asText("absent"),
                "hiddenEffort", settings.hiddenEffort().label(),
                "toolCount", original.path("tools").isArray() ? original.path("tools").size() : 0,
                "messageCount", original.path("messages").isArray() ? original.path("messages").size() : 0,
                "stream", original.path("stream").asBoolean(false));
        trace.assignSession(traces.correlateSession(correlationKey(provider, original, headers),
                previousResponseId(provider, original), trace.sessionId()));

        String skipped = attemptCapture(provider, path, headers, original, downstream, trace, state);
        if (skipped == null) return;
        trace.captureSkipped(skipped);
        forwardUpstream(provider, trace, path, headers, decoded, downstream, state, false);
    }

    /**
     * Runs the hidden exchange and, on success, the client-visible continuation. Returns a reason when
     * capture did not happen and nothing has reached the client yet, which is the caller's cue to send the
     * client's original request instead; returns {@code null} once the client's response is committed.
     */
    private String attemptCapture(ProxyProvider provider, String path, Map<String, List<String>> headers,
            ObjectNode original, DownstreamSink downstream, TraceStore.TraceHandle trace, ExchangeState state)
            throws Exception {
        // Both protocols stream the client-visible continuation and the response gate reads it as SSE, so
        // a client that asked for a single JSON body could never be answered through this path. Claude
        // Code opens an interactive session with exactly that shape -- a one-token unstreamed probe --
        // and capturing it spent a hidden call the provider only ever refused.
        if (!original.path("stream").asBoolean(false)) {
            Diagnostics.event("capture.unsupported_stream", "provider", provider.wireName());
            return "unsupported_capture";
        }
        var workbookBytes = new AtomicInteger();
        try {
            return provider == ProxyProvider.CLAUDE
                    ? captureClaude(path, headers, original, downstream, trace, state, workbookBytes)
                    : captureCodex(path, headers, original, downstream, trace, state, workbookBytes);
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            // Past the client's status line there is no second chance: re-sending would splice a fresh
            // response onto one the client has already begun reading.
            if (state.committed.get()) throw failure;
            logCaptureFallback(provider, failure);
            return switch (failure) {
                case HttpTimeoutException ignored -> "capture_timeout";
                case CaptureRefusedException ignored -> "capture_refused";
                default -> "capture_skipped";
            };
        }
    }

    /**
     * Returns {@code null} once the client's response is committed, or a reason to send the client's own
     * request instead. Like Claude, the continuation goes out behind a response gate: it re-declares
     * workbook, so the model may answer the tool result with another workbook call that must never reach a
     * client with no such tool.
     */
    private String captureCodex(String path, Map<String, List<String>> headers, ObjectNode original,
            DownstreamSink downstream, TraceStore.TraceHandle trace, ExchangeState state,
            AtomicInteger workbookBytes) throws Exception {
        var toolStarted = new AtomicBoolean();
        var protocol = new CodexProtocol(json, settings.noteStyle());
        if (!protocol.supportsCapture(original)) {
            Diagnostics.event("codex.capture.unsupported",
                    "inputCount", original.path("input").isArray() ? original.path("input").size() : 0);
            return "unsupported_capture";
        }
        var stream = protocol.newHiddenStream(delta -> acceptDelta(trace, workbookBytes, delta));
        var parser = new SseParser((event, data) -> {
            JsonNode eventData = parseSseJson(data);
            maybeStartCodexTrace(trace, toolStarted, event, eventData);
            stream.accept(event, eventData);
        });
        int hiddenStatus = runHidden(path, headers, protocol.forceWorkbook(original), parser, state);
        if (!successful(hiddenStatus)) {
            logProviderRejection(ProxyProvider.CODEX, "hidden", hiddenStatus);
            return "provider_error";
        }
        var hidden = stream.finish();
        if (toolStarted.compareAndSet(false, true)) trace.toolStarted(hidden.callId());
        trace.addUsage(hidden.inputTokens(), hidden.outputTokens());
        trace.complete();
        return gatedContinuation(ProxyProvider.CODEX, new Continuation<CodexProtocol.HiddenResult>() {
            @Override public ObjectNode body(List<CodexProtocol.HiddenResult> exchanges) {
                return protocol.continueAfterWorkbook(original, exchanges);
            }
            @Override public Gate<CodexProtocol.HiddenResult> newGate(Consumer<byte[]> release,
                    Consumer<String> visibleDelta, int maximum) {
                var gate = protocol.newResponseGate(release, visibleDelta, maximum);
                return new Gate<>() {
                    @Override public void accept(byte[] chunk) { gate.accept(chunk); }
                    @Override public boolean committed() { return gate.committed(); }
                    @Override public Optional<CodexProtocol.HiddenResult> finish() { return gate.finish(); }
                };
            }
            @Override public long inputTokens(CodexProtocol.HiddenResult result) { return result.inputTokens(); }
            @Override public long outputTokens(CodexProtocol.HiddenResult result) { return result.outputTokens(); }
        }, hidden, trace, path, headers, downstream, state, workbookBytes);
    }

    /**
     * Claude commits its own client-visible continuation behind the response gate, so unlike Codex it
     * returns the caller's verdict directly: {@code null} once the client's response is committed, or a
     * reason to send the client's original request instead.
     */
    private String captureClaude(String path, Map<String, List<String>> headers, ObjectNode original,
            DownstreamSink downstream, TraceStore.TraceHandle trace, ExchangeState state,
            AtomicInteger workbookBytes) throws Exception {
        var toolStarted = new AtomicBoolean();
        var protocol = new ClaudeProtocol(json, settings.noteStyle(), settings.hiddenEffort());
        if (!protocol.supportsCapture(original)) {
            Diagnostics.event("claude.capture.unsupported",
                    "thinkingType", original.path("thinking").path("type").asText("absent"));
            return "unsupported_capture";
        }
        var stream = protocol.newHiddenStream(delta -> acceptDelta(trace, workbookBytes, delta));
        var parser = new SseParser((event, data) -> {
            JsonNode eventData = parseSseJson(data);
            maybeStartClaudeTrace(trace, toolStarted, event, eventData);
            stream.accept(event, eventData);
        });
        int hiddenStatus = runHidden(path, headers, protocol.forceWorkbook(original), parser, state);
        if (!successful(hiddenStatus)) {
            logProviderRejection(ProxyProvider.CLAUDE, "hidden", hiddenStatus);
            return "provider_error";
        }
        var hidden = stream.finish();
        if (toolStarted.compareAndSet(false, true)) trace.toolStarted(hidden.toolUseId());
        trace.addUsage(hidden.inputTokens(), hidden.outputTokens());
        trace.complete();
        return gatedContinuation(ProxyProvider.CLAUDE, new Continuation<ClaudeProtocol.HiddenResult>() {
            @Override public ObjectNode body(List<ClaudeProtocol.HiddenResult> exchanges) {
                return protocol.continueAfterWorkbook(original, exchanges);
            }
            @Override public Gate<ClaudeProtocol.HiddenResult> newGate(Consumer<byte[]> release,
                    Consumer<String> visibleDelta, int maximum) {
                var gate = protocol.newResponseGate(release, visibleDelta, maximum);
                return new Gate<>() {
                    @Override public void accept(byte[] chunk) { gate.accept(chunk); }
                    @Override public boolean committed() { return gate.committed(); }
                    @Override public Optional<ClaudeProtocol.HiddenResult> finish() { return gate.finish(); }
                };
            }
            @Override public long inputTokens(ClaudeProtocol.HiddenResult result) { return result.inputTokens(); }
            @Override public long outputTokens(ClaudeProtocol.HiddenResult result) { return result.outputTokens(); }
        }, hidden, trace, path, headers, downstream, state, workbookBytes);
    }

    /**
     * Sends the continuation behind the provider's response gate. While the model keeps answering
     * the tool result with another workbook call the response is recorded rather than forwarded and the
     * exchange is folded into the next request; the client hears nothing until the model picks text or one
     * of its own tools. That silence is the reason each attempt keeps the hidden deadline until it
     * commits — the budget has to stay inside Claude Code's 300-second watchdog.
     *
     * <p>Returns {@code null} once the client's response is committed, or a reason for the caller to send
     * the client's original request instead.
     */
    private <R> String gatedContinuation(ProxyProvider provider, Continuation<R> protocol, R hidden,
            TraceStore.TraceHandle trace, String path,
            Map<String, List<String>> headers, DownstreamSink downstream, ExchangeState state,
            AtomicInteger workbookBytes) throws Exception {
        var exchanges = new ArrayList<R>();
        exchanges.add(hidden);
        for (int attempt = 0; attempt <= MAXIMUM_PRIVATE_LOOPS; attempt++) {
            byte[] body = json.writeValueAsBytes(protocol.body(exchanges));
            var response = new AtomicReference<HttpResult>();
            var sentHeaders = new AtomicBoolean();
            var toolNames = new SseParser((event, data) ->
                    observeContinuation(provider, trace, event, parseSseJson(data)));
            try (var keepalive = new Keepalive(downstream)) {
                var gate = protocol.newGate(bytes -> {
                    if (sentHeaders.compareAndSet(false, true)) {
                        HttpResult committed = response.get();
                        downstream.headers(committed.status(), filterResponseHeaders(committed.headers()));
                        state.committed.set(true);
                    }
                    downstream.body(bytes, false);
                    keepalive.sent(bytes);
                    observeQuietly(() -> toolNames.feed(bytes));
                }, delta -> acceptDelta(trace, workbookBytes, delta), MAXIMUM_GATE_BYTES);
                long expiresAt = System.nanoTime() + settings.hiddenTimeout().toNanos();
                HttpResult result = exchange(path, headers, body,
                        bytes -> { if (successful(response.get().status())) gate.accept(bytes); },
                        state, (status, responseHeaders) -> response.set(new HttpResult(status, responseHeaders)),
                        settings.hiddenTimeout(), () -> !gate.committed() && System.nanoTime() - expiresAt > 0);
                if (!successful(result.status())) {
                    logProviderRejection(provider, "continuation", result.status());
                    return "provider_error";
                }
                var repeated = gate.finish();
                if (repeated.isEmpty()) {
                    observeQuietly(toolNames::finish);
                    if (!sentHeaders.get()) {
                        downstream.headers(result.status(), filterResponseHeaders(result.headers()));
                    }
                    state.committed.set(true);
                    downstream.body(new byte[0], true);
                    log.debug("workbook captured: provider={} exchanges={}", provider.wireName(), exchanges.size());
                    return null;
                }
                exchanges.add(repeated.get());
                trace.addUsage(protocol.inputTokens(repeated.get()), protocol.outputTokens(repeated.get()));
            }
        }
        log.info("workbook capture stopped after {} private loops, forwarding the client's own request",
                MAXIMUM_PRIVATE_LOOPS);
        return "capture_loop_exhausted";
    }

    /** The provider-specific half of {@link #gatedContinuation}: build the body, gate the response. */
    private interface Continuation<R> {
        ObjectNode body(List<R> exchanges);

        Gate<R> newGate(Consumer<byte[]> release, Consumer<String> visibleDelta, int maximumBufferedBytes);

        long inputTokens(R result);

        long outputTokens(R result);
    }

    /** One provider's response gate, narrowed to what the shared loop needs of it. */
    private interface Gate<R> {
        void accept(byte[] chunk);

        boolean committed();

        Optional<R> finish();
    }

    /**
     * Real-tool-name observation for the dashboard, run after the client's bytes are already out. A
     * malformed frame costs a dashboard row and must never disturb what the client is reading.
     */
    private static void observeQuietly(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException malformed) {
            // Deliberately swallowed; the byte-exact response is already forwarded.
        }
    }

    /**
     * The hidden call is the only one held to {@link Settings#hiddenTimeout()}. Its body is dropped rather than
     * parsed when the provider refuses, so the caller can re-send the client's own request cleanly.
     */
    private int runHidden(String path, Map<String, List<String>> headers, ObjectNode forced, SseParser parser,
            ExchangeState state) throws Exception {
        var status = new AtomicInteger();
        var rejection = new java.io.ByteArrayOutputStream();
        long expiresAt = System.nanoTime() + settings.hiddenTimeout().toNanos();
        HttpResult result = exchange(path, headers, json.writeValueAsBytes(forced),
                bytes -> {
                    if (successful(status.get())) parser.feed(bytes);
                    else if (rejection.size() < MAXIMUM_REJECTION_BYTES) rejection.writeBytes(bytes);
                },
                state, (code, ignored) -> status.set(code), settings.hiddenTimeout(),
                () -> System.nanoTime() - expiresAt > 0);
        if (successful(result.status())) parser.finish();
        else Diagnostics.event("hidden.rejected", "status", result.status(),
                "errorType", errorCategory(rejection.toByteArray()));
        return result.status();
    }

    /**
     * The provider's own error category and nothing else. The message beside it routinely quotes the
     * offending field, and a field's value is the client's content, so only this fixed token -- one of
     * invalid_request_error, rate_limit_error, overloaded_error and their siblings -- may be logged.
     * The body itself still reaches the client untouched; it just never reaches a log.
     */
    private String errorCategory(byte[] body) {
        if (body.length == 0) return "empty";
        try {
            return json.readTree(body).path("error").path("type").asText("absent");
        } catch (RuntimeException unparsed) {
            return "unparsed";
        }
    }

    private static boolean successful(int status) {
        return status >= 200 && status < 300;
    }

    /**
     * Logs only the exception type and its own top-level message, never the cause chain or a stack trace:
     * a wrapped JSON-parse failure can carry a fragment of the provider body in its cause, which
     * SECURITY.md forbids logging.
     */
    /**
     * The status alone, never the provider's error body: the body is forwarded to the client verbatim
     * and may quote the request. Without this line a refused hidden call left no trace of why.
     */
    private void logProviderRejection(ProxyProvider provider, String call, int status) {
        log.info("workbook capture skipped, the provider rejected the {} call: provider={} noteStyle={} "
                        + "status={}",
                call, provider.wireName(), settings.noteStyle().label(), status);
        Diagnostics.event("capture.provider_error", "provider", provider.wireName(), "call", call,
                "noteStyle", settings.noteStyle().label(), "status", status);
    }

    private void logCaptureFallback(ProxyProvider provider, Exception failure) {
        // The wording version rides along because a refusal is a verdict on the text that was deployed,
        // not on the proxy: without it, a log line from two wordings ago reads as a live one.
        log.info("workbook capture skipped, forwarding the original request: provider={} noteStyle={} "
                        + "error={} message={}",
                provider.wireName(), settings.noteStyle().label(),
                failure.getClass().getSimpleName(), failure.getMessage());
    }

    /**
     * A stable, non-reversible key for grouping one conversation's turns in the dashboard. Claude Code
     * hands a gateway the session it belongs to, which survives compaction and a changed opening message;
     * the first-user-message hash stays as the fallback for clients that send no such header.
     *
     * <p>Codex sends its session/thread identity in request headers (and mirrors it in
     * {@code client_metadata}) when available. Those provider-issued values survive changed prompts and
     * distinguish identical prompts from independent CLI processes. Older or stripped-down clients have
     * neither identity, so the first non-contextual user message is hashed as a fallback. Codex prepends
     * a shared {@code <environment_context>} user message to many requests; hashing that item would merge
     * independent processes. If a request has no non-contextual user message, it is left ungrouped rather
     * than hashing bootstrap text. Without provider identity, identical opening prompts remain inherently
     * indistinguishable; {@link TraceStore} bounds that fallback collision to its freshness window.
     *
     * <p>The {@link #previousResponseId} branch remains for Responses-API clients that do chain by id.
     */
    private String correlationKey(ProxyProvider provider, ObjectNode original,
            Map<String, List<String>> headers) {
        if (provider == ProxyProvider.CODEX) {
            if (previousResponseId(provider, original) != null) return null;
            String identity = firstHeader(headers, CODEX_SESSION_HEADER);
            if (identity != null) identity = "session:" + identity;
            if (identity == null) {
                identity = firstHeader(headers, CODEX_THREAD_HEADER);
                if (identity != null) identity = "thread:" + identity;
            }
            if (identity == null) identity = codexClientMetadataIdentity(original);
            if (identity != null) {
                return "codex:" + Sha256.of(correlationSalt + '\u0000' + identity);
            }
            var text = new StringBuilder();
            collectFirstCodexUserText(original.path("input"), text);
            return text.isEmpty() ? null : "codex:" + Sha256.of(correlationSalt + '\u0000' + text);
        }
        String session = firstHeader(headers, CLAUDE_SESSION_HEADER);
        if (session != null) {
            // An agent id names an agent, never a person. It is used only to keep a subagent's turns off
            // the root session's timeline.
            String branch = firstHeader(headers, CLAUDE_AGENT_HEADER);
            return "claude:" + Sha256.of(correlationSalt + '\u0000' + session + '\u0000'
                    + (branch == null ? "root" : branch));
        }
        for (JsonNode message : original.path("messages")) {
            if (!"user".equals(message.path("role").asText())) continue;
            var text = new StringBuilder();
            collectText(message.path("content"), text);
            return text.isEmpty() ? null : "claude:" + Sha256.of(text.toString());
        }
        return null;
    }

    private String codexClientMetadataIdentity(ObjectNode original) {
        JsonNode metadata = original.path(CODEX_CLIENT_METADATA);
        if (!metadata.isObject()) return null;
        String identity = nonBlankText(metadata.path("session_id"));
        if (identity != null) return "session:" + identity;
        identity = nonBlankText(metadata.path("thread_id"));
        if (identity != null) return "thread:" + identity;
        JsonNode turnMetadata = metadata.path("x-codex-turn-metadata");
        if (!turnMetadata.isTextual()) return null;
        try {
            JsonNode parsed = json.readTree(turnMetadata.asText());
            identity = nonBlankText(parsed.path("session_id"));
            if (identity != null) return "session:" + identity;
            identity = nonBlankText(parsed.path("thread_id"));
            return identity == null ? null : "thread:" + identity;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nonBlankText(JsonNode value) {
        if (!value.isTextual() || value.asText().isBlank()) return null;
        return value.asText();
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        for (var entry : headers.entrySet()) {
            if (!name.equalsIgnoreCase(entry.getKey()) || entry.getValue().isEmpty()) continue;
            String value = entry.getValue().getFirst();
            return value == null || value.isBlank() ? null : value;
        }
        return null;
    }

    private String previousResponseId(ProxyProvider provider, ObjectNode original) {
        if (provider != ProxyProvider.CODEX) return null;
        String value = original.path("previous_response_id").asText("");
        return value.isEmpty() ? null : value;
    }

    /**
     * Only the opening non-contextual user message is hashed. Codex resends its whole conversation every
     * turn, so hashing the entire input would produce a different key on every turn and scatter one
     * conversation across a dashboard row each.
     */
    private static void collectFirstCodexUserText(JsonNode input, StringBuilder out) {
        if (!input.isArray()) {
            collectText(input, out);
            return;
        }
        for (JsonNode item : input) {
            if (!"user".equals(item.path("role").asText())) continue;
            var text = new StringBuilder();
            collectText(item.path("content"), text);
            if (!text.isEmpty() && !isCodexBootstrap(text.toString())) {
                out.append(text);
                return;
            }
        }
    }
    private static boolean isCodexBootstrap(String text) {
        String trimmed = text.strip();
        return trimmed.startsWith("<environment_context>")
                && trimmed.endsWith("</environment_context>");
    }

    private static void collectText(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) return;
        if (node.isTextual()) {
            out.append(node.asText());
        } else if (node.isArray()) {
            node.forEach(child -> collectText(child, out));
        } else if (node.isObject()) {
            collectText(node.get("text"), out);
            collectText(node.get("content"), out);
        }
    }

    /**
     * Streams one upstream response to the client byte for byte, status and all. Claude Code matches on
     * upstream error wording to decide whether to retry with a capability disabled, so no proxy envelope
     * ever replaces it. With {@code onlyOnSuccess} the refusal is swallowed instead and its status
     * returned, leaving the caller free to fall back to the client's own request.
     */
    private int forwardUpstream(ProxyProvider provider, TraceStore.TraceHandle trace, String path,
            Map<String, List<String>> headers, byte[] body, DownstreamSink downstream, ExchangeState state,
            boolean onlyOnSuccess) throws Exception {
        var sentHeaders = new AtomicBoolean();
        var status = new AtomicInteger();
        var toolNames = new SseParser((event, data) -> observeContinuation(provider, trace, event, parseSseJson(data)));
        HttpResult result = exchange(path, headers, body, bytes -> {
            if (onlyOnSuccess && !successful(status.get())) return;
            if (!sentHeaders.get()) throw new IllegalStateException("Continuation body preceded its headers");
            downstream.body(bytes, false);
            observeQuietly(() -> toolNames.feed(bytes));
        }, state, (code, responseHeaders) -> {
            status.set(code);
            if (onlyOnSuccess && !successful(code)) return;
            downstream.headers(code, filterResponseHeaders(responseHeaders));
            sentHeaders.set(true);
            state.committed.set(true);
        }, VISIBLE_REQUEST_TIMEOUT, NO_DEADLINE);
        if (onlyOnSuccess && !successful(result.status())) return result.status();
        observeQuietly(toolNames::finish);
        if (!sentHeaders.get()) downstream.headers(result.status(), filterResponseHeaders(result.headers()));
        state.committed.set(true);
        downstream.body(new byte[0], true);
        return result.status();
    }

    /**
     * Observes the client-visible continuation stream for the real tool NAME only (never arguments or
     * results), token usage for this call, and, for Codex, the response id so the client's next request
     * can chain back to this session via {@code previous_response_id}.
     */
    private void observeContinuation(ProxyProvider provider, TraceStore.TraceHandle trace, String event, JsonNode data) {
        switch (provider) {
            case CODEX -> observeCodexContinuation(trace, event, data);
            case CLAUDE -> observeClaudeContinuation(trace, event, data);
        }
    }

    private void observeCodexContinuation(TraceStore.TraceHandle trace, String event, JsonNode data) {
        switch (event) {
            case "response.output_item.added" -> {
                if ("function_call".equals(data.path("item").path("type").asText())) {
                    trace.toolCallObserved(data.path("item").path("name").asText());
                }
            }
            case "response.completed" -> {
                trace.linkResponseId(data.path("response").path("id").asText());
                JsonNode usage = data.path("response").path("usage");
                trace.addUsage(usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0));
            }
            default -> { }
        }
    }

    private void observeClaudeContinuation(TraceStore.TraceHandle trace, String event, JsonNode data) {
        switch (event) {
            case "content_block_start" -> {
                if ("tool_use".equals(data.path("content_block").path("type").asText())) {
                    trace.toolCallObserved(data.path("content_block").path("name").asText());
                }
            }
            case "message_start" -> {
                if (data.path("message").isObject()) {
                    trace.addUsage(ClaudeProtocol.totalInputTokens(data.path("message").path("usage")), 0);
                }
            }
            case "message_delta" -> {
                if (data.path("usage").isObject()) {
                    trace.addUsage(0, data.path("usage").path("output_tokens").asLong(0));
                }
            }
            default -> { }
        }
    }

    private HttpResult exchange(String path, Map<String, List<String>> headers, byte[] body,
            Consumer<byte[]> chunks, ExchangeState state, HeaderConsumer onHeaders,
            Duration timeout, Deadline deadline) throws Exception {
        if (state.cancelled.get()) throw new InterruptedException("downstream cancelled");
        var builder = HttpRequest.newBuilder(internalBaseUri.resolve(path))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(":") && !REQUEST_HEADERS_DENIED.contains(lower)) {
                values.forEach(value -> builder.header(lower, value));
            }
        });
        builder.header("x-workbook-provider", ProxyProvider.forPath(path.split("\\?", 2)[0]).wireName());
        if (settings.testUpstreamEnabled()) builder.header("x-workbook-test-upstream", "true");
        HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        var responseHeaders = response.headers().map();
        onHeaders.accept(response.statusCode(), responseHeaders);
        try (InputStream input = response.body()) {
            byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); ; read = input.read(buffer)) {
                if (state.cancelled.get()) throw new InterruptedException("downstream cancelled");
                // HttpRequest.timeout stops applying once the response headers arrive, so a stream that
                // opens promptly and then dribbles is bounded here instead. Checked even on the final
                // read: a response that only lands after the budget is spent is one the client can no
                // longer afford to wait for, however complete it turned out to be.
                if (deadline.expired()) throw new HttpTimeoutException("hidden request deadline");
                if (read < 0) break;
                if (read > 0) chunks.accept(Arrays.copyOf(buffer, read));
            }
        }
        return new HttpResult(response.statusCode(), responseHeaders);
    }

    private void acceptDelta(TraceStore.TraceHandle trace, AtomicInteger total, String delta) {
        int count = total.addAndGet(delta.getBytes(StandardCharsets.UTF_8).length);
        if (count > settings.maximumWorkbookBytes()) {
            throw new IllegalArgumentException("Workbook exceeded the 256 KiB local limit");
        }
        trace.delta(delta);
    }

    private byte[] decodeRequestBody(Map<String, List<String>> headers, byte[] body) throws IOException {
        String encoding = headers.entrySet().stream()
                .filter(entry -> "content-encoding".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse("identity")
                .trim();
        if (encoding.isEmpty() || "identity".equalsIgnoreCase(encoding)) return body;
        if (!"gzip".equalsIgnoreCase(encoding)) {
            throw new IllegalArgumentException("Unsupported request content encoding: " + encoding);
        }
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
            byte[] decoded = gzip.readNBytes(settings.maximumRequestBytes() + 1);
            if (decoded.length > settings.maximumRequestBytes()) {
                throw new IllegalArgumentException("Decompressed request exceeded the 64 MiB local limit");
            }
            return decoded;
        }
    }

    private JsonNode parseSseJson(String data) {
        if ("[DONE]".equals(data)) return json.createObjectNode();
        try {
            return json.readTree(data);
        } catch (tools.jackson.core.JacksonException error) {
            throw new IllegalArgumentException("Provider returned invalid SSE JSON", error);
        }
    }

    private void maybeStartCodexTrace(TraceStore.TraceHandle trace, AtomicBoolean started,
            String event, JsonNode data) {
        JsonNode item = data.path("item");
        if ("response.output_item.added".equals(event)
                && "function_call".equals(item.path("type").asText())
                && "workbook".equals(item.path("name").asText())
                && started.compareAndSet(false, true)) {
            trace.toolStarted(item.path("call_id").asText());
        }
    }

    private void maybeStartClaudeTrace(TraceStore.TraceHandle trace, AtomicBoolean started,
            String event, JsonNode data) {
        JsonNode block = data.path("content_block");
        if ("content_block_start".equals(event)
                && "tool_use".equals(block.path("type").asText())
                && ClaudeProtocol.TOOL_NAME.equals(block.path("name").asText())
                && started.compareAndSet(false, true)) {
            trace.toolStarted(block.path("id").asText());
        }
    }

    private Map<String, List<String>> filterResponseHeaders(Map<String, List<String>> headers) {
        var filtered = new LinkedHashMap<String, List<String>>();
        headers.forEach((name, values) -> {
            if (!RESPONSE_HEADERS_DENIED.contains(name.toLowerCase(Locale.ROOT))) {
                filtered.put(name.toLowerCase(Locale.ROOT), List.copyOf(values));
            }
        });
        return Map.copyOf(filtered);
    }

    private String safeMessage(Exception error) {
        if (error instanceof IllegalArgumentException && error.getMessage() != null) return error.getMessage();
        return "The provider exchange failed; inspect redacted local logs for the exception type.";
    }

    @PreDestroy
    @Override
    public void close() {
        executor.close();
    }

    public record ExchangeHandle(CompletableFuture<Void> completion, Runnable cancelAction) {
        public void cancel() { cancelAction.run(); }
    }

    private record HttpResult(int status, Map<String, List<String>> headers) { }

    @FunctionalInterface
    private interface HeaderConsumer {
        void accept(int status, Map<String, List<String>> headers);
    }

    @FunctionalInterface
    private interface Deadline {
        boolean expired();
    }

    private static final class ExchangeState {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        /** Set the moment a status line reaches the client: past this point no path may re-send. */
        private final AtomicBoolean committed = new AtomicBoolean();
        private volatile Thread thread;
    }

    /**
     * SSE comment keepalives, so a provider that stalls part-way through a response does not push the
     * client past its 300-second silent-stream watchdog. It starts only once real bytes have gone out,
     * because a 429 or 500 still has to reach the client with its own status line, and a keepalive sent
     * ahead of that would have committed a 200 instead.
     *
     * <p>Comments are only emitted on an event boundary. Splicing one into a half-written event would
     * corrupt the very stream this is meant to protect. Writes run on a second thread, which
     * {@link DownstreamSink} implementations serialise.
     */
    private static final class Keepalive implements AutoCloseable {
        private final DownstreamSink downstream;
        private final AtomicLong lastSentAt = new AtomicLong(System.nanoTime());
        private final byte[] tail = new byte[4];
        private Thread thread;

        private Keepalive(DownstreamSink downstream) {
            this.downstream = downstream;
        }

        private void sent(byte[] bytes) {
            lastSentAt.set(System.nanoTime());
            remember(bytes);
            if (thread == null) {
                thread = Thread.ofVirtual().name("workbook-keepalive").start(this::poll);
            }
        }

        private synchronized void remember(byte[] bytes) {
            for (byte value : bytes) {
                System.arraycopy(tail, 1, tail, 0, tail.length - 1);
                tail[tail.length - 1] = value;
            }
        }

        private synchronized boolean atEventBoundary() {
            return tail[3] == '\n'
                    && (tail[2] == '\n' || (tail[2] == '\r' && tail[1] == '\n' && tail[0] == '\r'));
        }

        private void poll() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(KEEPALIVE_POLL);
                    if (System.nanoTime() - lastSentAt.get() >= KEEPALIVE_IDLE.toNanos() && atEventBoundary()) {
                        downstream.body(SSE_KEEPALIVE, false);
                        lastSentAt.set(System.nanoTime());
                    }
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            if (thread != null) thread.interrupt();
        }
    }
}
