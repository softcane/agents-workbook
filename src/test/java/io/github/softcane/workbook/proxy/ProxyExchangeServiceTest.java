package io.github.softcane.workbook.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import com.sun.net.httpserver.HttpServer;
import io.github.softcane.workbook.proxy.provider.ClaudeProtocol;
import io.github.softcane.workbook.proxy.provider.CodexProtocol;
import io.github.softcane.workbook.proxy.trace.TraceStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class ProxyExchangeServiceTest {
    /** The deployed configuration, only with a connect timeout short enough to fail a test rather than hang it. */
    private static final ProxyExchangeService.Settings TEST_SETTINGS =
            ProxyExchangeService.Settings.DEFAULTS.withUpstreamTimeout(Duration.ofSeconds(5));

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ownsReservedInternalRoutingHeaders() throws Exception {
        var captured = new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.Headers>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            captured.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        upstream.start();

        try (var service = new ProxyExchangeService(json, new TraceStore(50), new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            var completion = service.start(ProxyProvider.CODEX, "/backend-api/codex/responses",
                    Map.of("content-type", List.of("application/json"),
                            "x-workbook-test-upstream", List.of("true"),
                            "x-workbook-provider", List.of("claude")),
                    "{\"model\":\"gpt-test\",\"input\":\"hello\"}".getBytes(StandardCharsets.UTF_8),
                    new IgnoringSink()).completion();
            completion.get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(captured.get().get("x-workbook-test-upstream")).isNull();
        assertThat(captured.get().get("x-workbook-provider")).containsExactly("codex");
    }

    @Test
    void replaysEveryEarlierCodexWorkbookBoundaryBeforeGeneratingTheNextNote() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var hiddenAOutput = codexWorkbookOutput(
                "fc_wb_a", "wb_a", "note A sentinel", "cipher-a", "opaque-a");
        var hiddenBOutput = codexWorkbookOutput(
                "fc_wb_b", "wb_b", "note B sentinel", "cipher-b", "opaque-b");
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            String response = switch (requestCount.incrementAndGet()) {
                case 1 -> codexWorkbookResponse(hiddenAOutput);
                case 2 -> codexCustomToolResponse("real_item_1", "call_real_1", "shell");
                case 3 -> codexWorkbookResponse(hiddenBOutput);
                case 4 -> codexCustomToolResponse("real_item_2", "call_real_2", "shell");
                case 5 -> codexWorkbookResponse("fc_wb_c", "wb_c", "note C sentinel");
                default -> codexTextResponse("final answer");
            };
            writeSse(exchange, response);
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(true, false, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var downstream = new ArrayList<RecordingSink>();
        var meterRegistry = new SimpleMeterRegistry();
        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(Diagnostics.class);
        var previousLevel = logger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                meterRegistry,
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            var headers = Map.of("content-type", List.of("application/json"));
            var first = new RecordingSink();
            downstream.add(first);
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"continuity-session"},
                     "input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"start"}]}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), first).completion().get(5, TimeUnit.SECONDS);

            var second = new RecordingSink();
            downstream.add(second);
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"continuity-session"},"input":[
                     {"type":"message","role":"user","content":[{"type":"input_text","text":"start"}]},
                     {"type":"custom_tool_call","id":"real_item_1","call_id":"call_real_1","name":"shell","input":"{}"},
                     {"type":"custom_tool_call_output","call_id":"call_real_1","output":"alpha"}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), second).completion().get(5, TimeUnit.SECONDS);

            var third = new RecordingSink();
            downstream.add(third);
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"continuity-session"},"input":[
                     {"type":"message","role":"user","content":[{"type":"input_text","text":"start"}]},
                     {"type":"custom_tool_call","id":"real_item_1","call_id":"call_real_1","name":"shell","input":"{}"},
                     {"type":"custom_tool_call_output","call_id":"call_real_1","output":"alpha"},
                     {"type":"custom_tool_call","id":"real_item_2","call_id":"call_real_2","name":"shell","input":"{}"},
                     {"type":"custom_tool_call_output","call_id":"call_real_2","output":"beta"}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), third).completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        assertThat(received).hasSize(6);
        var expectedSecondInput = json.createArrayNode();
        expectedSecondInput.add(received.get(0).path("input").get(0).deepCopy());
        appendCodexWorkbookExchange(expectedSecondInput, hiddenAOutput, "wb_a");
        expectedSecondInput.add(json.createObjectNode()
                .put("type", "custom_tool_call")
                .put("id", "real_item_1")
                .put("call_id", "call_real_1")
                .put("name", "shell")
                .put("input", "{}"));
        expectedSecondInput.add(json.createObjectNode()
                .put("type", "custom_tool_call_output")
                .put("call_id", "call_real_1")
                .put("output", "alpha"));
        assertThat(received.get(2).path("input")).isEqualTo(expectedSecondInput);

        var expectedThirdInput = expectedSecondInput.deepCopy();
        appendCodexWorkbookExchange(expectedThirdInput, hiddenBOutput, "wb_b");
        expectedThirdInput.add(json.createObjectNode()
                .put("type", "custom_tool_call")
                .put("id", "real_item_2")
                .put("call_id", "call_real_2")
                .put("name", "shell")
                .put("input", "{}"));
        expectedThirdInput.add(json.createObjectNode()
                .put("type", "custom_tool_call_output")
                .put("call_id", "call_real_2")
                .put("output", "beta"));
        assertThat(received.get(4).path("input")).isEqualTo(expectedThirdInput);
        assertThat(codexCallTimeline(received.get(2).path("input"))).containsExactly(
                "function_call:wb_a",
                "function_call_output:wb_a",
                "custom_tool_call:call_real_1",
                "custom_tool_call_output:call_real_1");
        assertThat(codexCallTimeline(received.get(4).path("input"))).containsExactly(
                "function_call:wb_a",
                "function_call_output:wb_a",
                "custom_tool_call:call_real_1",
                "custom_tool_call_output:call_real_1",
                "function_call:wb_b",
                "function_call_output:wb_b",
                "custom_tool_call:call_real_2",
                "custom_tool_call_output:call_real_2");
        assertThat(downstream.stream().map(sink -> sink.body.toString()).toList())
                .anySatisfy(body -> assertThat(body).contains("call_real_1"))
                .anySatisfy(body -> assertThat(body).contains("call_real_2"))
                .anySatisfy(body -> assertThat(body).contains("final answer"))
                .allSatisfy(body -> assertThat(body)
                        .doesNotContain("workbook", "note A sentinel", "note B sentinel", "note C sentinel"));
        assertThat(meterRegistry.find("workbook.continuity.replay")
                .tags("provider", "codex", "outcome", "replayed", "reason", "history_restored")
                .counter().count()).isEqualTo(2);
        var continuityEvents = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("\"event\":\"continuity.replay\""))
                .toList();
        assertThat(continuityEvents).anySatisfy(message -> {
            JsonNode event = json.readTree(message);
            assertThat(event.path("provider").asText()).isEqualTo("codex");
            assertThat(event.path("insertedBoundaryCount").asInt()).isEqualTo(2);
            assertThat(event.path("boundarySequences").toString()).isEqualTo("[1,2]");
            assertThat(event.path("returnedToolCount").asInt()).isEqualTo(1);
        });
        assertThat(String.join("\n", continuityEvents))
                .doesNotContain("continuity-session", "note A sentinel", "note B sentinel", "note C sentinel",
                        "alpha", "beta", "call_real_1", "call_real_2");
    }

    @Test
    void commitsCodexBoundaryWhenExchangeHandleCancelsAfterSemanticTerminalEvent() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var releaseUpstream = new java.util.concurrent.CountDownLatch(1);
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            int request = requestCount.incrementAndGet();
            if (request == 2) {
                writeOpenSse(exchange,
                        codexCustomToolResponse("real_item_1", "call_real_1", "shell"),
                        releaseUpstream);
                return;
            }
            writeSse(exchange, switch (request) {
                case 1 -> codexWorkbookResponse("fc_wb_a", "wb_a", "note A sentinel");
                case 3 -> codexWorkbookResponse("fc_wb_b", "wb_b", "note B sentinel");
                default -> codexTextResponse("final answer");
            });
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(true, false, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var cancelling = new BlockingSink("response.completed");
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            var headers = Map.of("content-type", List.of("application/json"));
            var handle = service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"terminal-cancel-session"},
                     "input":[{"type":"message","role":"user","content":"start"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8), cancelling);
            assertThat(cancelling.awaitBlocked()).isTrue();
            handle.cancel();
            cancelling.release();
            releaseUpstream.countDown();
            handle.completion().handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
            var second = new RecordingSink();
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"terminal-cancel-session"},"input":[
                     {"type":"message","role":"user","content":"start"},
                     {"type":"custom_tool_call","id":"real_item_1","call_id":"call_real_1","name":"shell","input":"{}"},
                     {"type":"custom_tool_call_output","call_id":"call_real_1","output":"alpha"}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), second).completion().get(5, TimeUnit.SECONDS);
        } finally {
            releaseUpstream.countDown();
            upstream.stop(0);
        }

        assertThat(received).hasSize(4);
        assertThat(codexCallTimeline(received.get(2).path("input"))).containsExactly(
                "function_call:wb_a",
                "function_call_output:wb_a",
                "custom_tool_call:call_real_1",
                "custom_tool_call_output:call_real_1");
    }

    @Test
    void discardsCodexBoundaryWhenExchangeHandleCancelsBeforeSemanticTerminalEvent() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var releaseUpstream = new java.util.concurrent.CountDownLatch(1);
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            int request = requestCount.incrementAndGet();
            if (request == 2) {
                writeOpenSse(exchange, """
                        event: response.output_item.added
                        data: {"item":{"type":"custom_tool_call","id":"real_item_1","call_id":"call_real_1","name":"shell","input":"{}"}}

                        """, releaseUpstream);
                return;
            }
            writeSse(exchange, switch (request) {
                case 1 -> codexWorkbookResponse("fc_wb_a", "wb_a", "note A sentinel");
                case 3 -> codexWorkbookResponse("fc_wb_b", "wb_b", "note B sentinel");
                default -> codexTextResponse("final answer");
            });
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(true, false, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var cancelling = new BlockingSink("custom_tool_call");
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            var headers = Map.of("content-type", List.of("application/json"));
            var handle = service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"early-cancel-session"},
                     "input":[{"type":"message","role":"user","content":"start"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8), cancelling);
            assertThat(cancelling.awaitBlocked()).isTrue();
            handle.cancel();
            cancelling.release();
            releaseUpstream.countDown();
            handle.completion().handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);

            var second = new RecordingSink();
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"early-cancel-session"},"input":[
                     {"type":"message","role":"user","content":"start"},
                     {"type":"custom_tool_call","id":"real_item_1","call_id":"call_real_1","name":"shell","input":"{}"},
                     {"type":"custom_tool_call_output","call_id":"call_real_1","output":"alpha"}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), second).completion().get(5, TimeUnit.SECONDS);
        } finally {
            releaseUpstream.countDown();
            upstream.stop(0);
        }

        assertThat(received).hasSize(3);
        assertThat(codexCallTimeline(received.get(2).path("input"))).containsExactly(
                "custom_tool_call:call_real_1",
                "custom_tool_call_output:call_real_1");
    }

    @Test
    void forwardsCommittedCodexStreamAndDiscardsContinuityWhenTerminalEventIsMissing() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            int request = requestCount.incrementAndGet();
            writeSse(exchange, switch (request) {
                case 1 -> codexWorkbookResponse("fc_wb_a", "wb_a", "note A sentinel");
                case 2 -> """
                        event: response.output_item.added
                        data: {"item":{"type":"custom_tool_call","id":"real_item_1","call_id":"call_real_1","name":"shell","input":"{}"}}

                        """;
                default -> codexTextResponse("provider pass-through");
            });
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(true, false, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var meterRegistry = new SimpleMeterRegistry();
        var first = new RecordingSink();
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                meterRegistry, URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            var headers = Map.of("content-type", List.of("application/json"));
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"missing-terminal-session"},
                     "input":[{"type":"message","role":"user","content":"start"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8), first).completion().get(5, TimeUnit.SECONDS);

            var second = new RecordingSink();
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"missing-terminal-session"},"input":[
                     {"type":"message","role":"user","content":"start"},
                     {"type":"custom_tool_call","id":"real_item_1","call_id":"call_real_1","name":"shell","input":"{}"},
                     {"type":"custom_tool_call_output","call_id":"call_real_1","output":"alpha"}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), second).completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(first.body.toString()).contains("call_real_1");
        assertThat(first.ended).isTrue();
        assertThat(first.failures).isEmpty();
        assertThat(received).hasSize(3);
        assertThat(codexCallTimeline(received.get(2).path("input"))).containsExactly(
                "custom_tool_call:call_real_1",
                "custom_tool_call_output:call_real_1");
        assertThat(meterRegistry.find("workbook.continuity.replay")
                .tags("provider", "codex", "outcome", "skipped", "reason", "missing_terminal")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void fallsBackToOriginalCodexRequestWhenContinuationHasNoVisibleItemOrTerminal() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            writeSse(exchange, switch (requestCount.incrementAndGet()) {
                case 1 -> codexWorkbookResponse("fc_wb_a", "wb_a", "note A sentinel");
                case 2 -> ": keepalive\n\n";
                default -> codexTextResponse("provider pass-through");
            });
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(true, false, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var meterRegistry = new SimpleMeterRegistry();
        var downstream = new RecordingSink();
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                meterRegistry, URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses",
                    Map.of("content-type", List.of("application/json")), """
                            {"model":"gpt-test","client_metadata":{"session_id":"empty-continuation-session"},
                             "input":[{"type":"message","role":"user","content":"start"}],"stream":true}
                            """.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(received).hasSize(3);
        assertThat(received.get(2).path("tools")).isEmpty();
        assertThat(downstream.body.toString())
                .contains("provider pass-through")
                .doesNotContain("keepalive", "workbook", "note A sentinel");
        assertThat(downstream.ended).isTrue();
        assertThat(downstream.failures).isEmpty();
        assertThat(meterRegistry.find("workbook.continuity.replay")
                .tags("provider", "codex", "outcome", "skipped", "reason", "missing_terminal")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void fallsBackToOriginalClaudeRequestWhenContinuationHasNoVisibleItemOrTerminal() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            writeSse(exchange, switch (requestCount.incrementAndGet()) {
                case 1 -> claudeWorkbookResponse("toolu_wb_a", "note A sentinel");
                case 2 -> ": keepalive\n\n";
                default -> claudeTextResponse("provider pass-through");
            });
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(false, true, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var meterRegistry = new SimpleMeterRegistry();
        var downstream = new RecordingSink();
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                meterRegistry, URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                    Map.of("content-type", List.of("application/json"),
                            "x-claude-code-session-id", List.of("empty-continuation-session")), """
                            {"model":"claude-test","messages":[{"role":"user","content":"start"}],"stream":true}
                            """.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(received).hasSize(3);
        assertThat(received.get(2).has("tools")).isFalse();
        assertThat(downstream.body.toString())
                .contains("provider pass-through")
                .doesNotContain("keepalive", "workbook", "note A sentinel");
        assertThat(downstream.ended).isTrue();
        assertThat(downstream.failures).isEmpty();
        assertThat(meterRegistry.find("workbook.continuity.replay")
                .tags("provider", "claude", "outcome", "skipped", "reason", "missing_terminal")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void discardsCodexBoundaryWhenTerminalChunkHasMalformedTrailingUtf8() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            int request = requestCount.incrementAndGet();
            if (request == 2) {
                byte[] valid = codexCustomToolResponse("real_item_1", "call_real_1", "shell")
                        .getBytes(StandardCharsets.UTF_8);
                byte[] malformed = {(byte) 0xc3, 0x28};
                byte[] response = java.nio.ByteBuffer.allocate(valid.length + malformed.length)
                        .put(valid).put(malformed).array();
                exchange.getResponseHeaders().set("content-type", "text/event-stream");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
                return;
            }
            writeSse(exchange, request == 1
                    ? codexWorkbookResponse("fc_wb_a", "wb_a", "note A sentinel")
                    : codexTextResponse("provider pass-through"));
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(true, false, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var meterRegistry = new SimpleMeterRegistry();
        var first = new RecordingSink();
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                meterRegistry, URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            var headers = Map.of("content-type", List.of("application/json"));
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"malformed-terminal-session"},
                     "input":[{"type":"message","role":"user","content":"start"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8), first).completion().get(5, TimeUnit.SECONDS);

            var second = new RecordingSink();
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"malformed-terminal-session"},"input":[
                     {"type":"message","role":"user","content":"start"},
                     {"type":"custom_tool_call","id":"real_item_1","call_id":"call_real_1","name":"shell","input":"{}"},
                     {"type":"custom_tool_call_output","call_id":"call_real_1","output":"alpha"}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), second).completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(first.body.toString()).contains("response.completed");
        assertThat(first.ended).isTrue();
        assertThat(first.failures).isEmpty();
        assertThat(received).hasSize(3);
        assertThat(codexCallTimeline(received.get(2).path("input"))).containsExactly(
                "custom_tool_call:call_real_1",
                "custom_tool_call_output:call_real_1");
        assertThat(meterRegistry.find("workbook.continuity.replay")
                .tags("provider", "codex", "outcome", "skipped", "reason", "malformed_observer")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void completesClaudeContinuationAtMessageStopBeforeHttpEof() throws Exception {
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var releaseUpstream = new java.util.concurrent.CountDownLatch(1);
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            json.readTree(exchange.getRequestBody());
            if (requestCount.incrementAndGet() == 1) {
                writeSse(exchange, claudeWorkbookResponse("toolu_wb", "note"));
            } else {
                writeOpenSse(exchange, claudeTextResponse("final answer"), releaseUpstream);
            }
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(false, true, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var cancelling = new BlockingSink("message_stop");
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            var handle = service.start(ProxyProvider.CLAUDE, "/v1/messages",
                    Map.of("content-type", List.of("application/json"),
                            "x-claude-code-session-id", List.of("terminal-session")), """
                            {"model":"claude-test","messages":[{"role":"user","content":"start"}],
                             "stream":true}
                            """.getBytes(StandardCharsets.UTF_8), cancelling);
            assertThat(cancelling.awaitBlocked()).isTrue();
            handle.cancel();
            cancelling.release();
            releaseUpstream.countDown();
            handle.completion().handle((ignored, failure) -> null).get(5, TimeUnit.SECONDS);
        } finally {
            releaseUpstream.countDown();
            upstream.stop(0);
        }
    }

    @Test
    void replaysEveryEarlierClaudeWorkbookBoundaryBeforeGeneratingTheNextNote() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            String response = switch (requestCount.incrementAndGet()) {
                case 1 -> claudeWorkbookResponse("toolu_wb_a", "note A sentinel");
                case 2 -> claudeRealToolResponse("toolu_real_1", "Bash");
                case 3 -> claudeWorkbookResponse("toolu_wb_b", "note B sentinel");
                case 4 -> claudeRealToolResponse("toolu_real_2", "Bash");
                case 5 -> claudeWorkbookResponse("toolu_wb_c", "note C sentinel");
                default -> claudeTextResponse("final answer");
            };
            writeSse(exchange, response);
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(false, true, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var downstream = new ArrayList<RecordingSink>();
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity,
                new SimpleMeterRegistry(),
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()),
                TEST_SETTINGS)) {
            var headers = Map.of(
                    "content-type", List.of("application/json"),
                    "x-claude-code-session-id", List.of("continuity-session"));
            var first = new RecordingSink();
            downstream.add(first);
            service.start(ProxyProvider.CLAUDE, "/v1/messages", headers, """
                    {"model":"claude-test","messages":[{"role":"user","content":"start"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8), first).completion().get(5, TimeUnit.SECONDS);

            var second = new RecordingSink();
            downstream.add(second);
            service.start(ProxyProvider.CLAUDE, "/v1/messages", headers, """
                    {"model":"claude-test","messages":[
                     {"role":"user","content":"start"},
                     {"role":"assistant","content":[{"type":"tool_use","id":"toolu_real_1","name":"Bash","input":{}}]},
                     {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_real_1","content":"alpha"}]}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), second).completion().get(5, TimeUnit.SECONDS);

            var third = new RecordingSink();
            downstream.add(third);
            service.start(ProxyProvider.CLAUDE, "/v1/messages", headers, """
                    {"model":"claude-test","messages":[
                     {"role":"user","content":"start"},
                     {"role":"assistant","content":[{"type":"tool_use","id":"toolu_real_1","name":"Bash","input":{}}]},
                     {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_real_1","content":"alpha"}]},
                     {"role":"assistant","content":[{"type":"tool_use","id":"toolu_real_2","name":"Bash","input":{}}]},
                     {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_real_2","content":"beta"}]}],
                     "stream":true}
                    """.getBytes(StandardCharsets.UTF_8), third).completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(received).hasSize(6);
        assertThat(claudeCallTimeline(received.get(2).path("messages"))).containsExactly(
                "tool_use:toolu_wb_a",
                "tool_result:toolu_wb_a",
                "tool_use:toolu_real_1",
                "tool_result:toolu_real_1");
        assertThat(claudeCallTimeline(received.get(4).path("messages"))).containsExactly(
                "tool_use:toolu_wb_a",
                "tool_result:toolu_wb_a",
                "tool_use:toolu_real_1",
                "tool_result:toolu_real_1",
                "tool_use:toolu_wb_b",
                "tool_result:toolu_wb_b",
                "tool_use:toolu_real_2",
                "tool_result:toolu_real_2");
        assertThat(downstream.stream().map(sink -> sink.body.toString()).toList())
                .anySatisfy(body -> assertThat(body).contains("toolu_real_1"))
                .anySatisfy(body -> assertThat(body).contains("toolu_real_2"))
                .anySatisfy(body -> assertThat(body).contains("final answer"))
                .allSatisfy(body -> assertThat(body)
                        .doesNotContain("workbook", "note A sentinel", "note B sentinel", "note C sentinel"));
    }

    @Test
    void passesAContinuationWithAMissingCodexAnchorThroughUntouched() throws Exception {
        var received = new ArrayList<JsonNode>();
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            synchronized (received) {
                received.add(json.readTree(exchange.getRequestBody()));
            }
            String response = switch (requestCount.incrementAndGet()) {
                case 1 -> codexWorkbookResponse("fc_wb", "wb", "private note");
                case 2 -> codexRealToolResponse("real_item", "real_call", "shell");
                default -> codexTextResponse("provider pass-through");
            };
            writeSse(exchange, response);
        });
        upstream.start();

        var continuity = new ConversationStateStore(json,
                new ConversationStateStore.Settings(true, false, 64, 32, 33_554_432, Duration.ofMinutes(30)),
                java.time.Clock.systemUTC());
        var meterRegistry = new SimpleMeterRegistry();
        var headers = Map.of("content-type", List.of("application/json"));
        var continuation = (tools.jackson.databind.node.ObjectNode) json.readTree("""
                {"model":"gpt-test","client_metadata":{"session_id":"missing-anchor"},"input":[
                 {"type":"message","role":"user","content":"start"},
                 {"type":"function_call_output","call_id":"real_call","output":"alpha"}],
                 "stream":true}
                """);
        var downstream = new RecordingSink();
        try (var service = new ProxyExchangeService(json, new TraceStore(50), continuity, meterRegistry,
                URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers, """
                    {"model":"gpt-test","client_metadata":{"session_id":"missing-anchor"},
                     "input":[{"type":"message","role":"user","content":"start"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8), new IgnoringSink()).completion().get(5, TimeUnit.SECONDS);
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers,
                    json.writeValueAsBytes(continuation), downstream).completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(received).hasSize(3);
        assertThat(received.get(2)).isEqualTo(continuation);
        assertThat(received.get(2).toString()).doesNotContain("workbook", "private note");
        assertThat(downstream.body.toString()).contains("provider pass-through");
        assertThat(meterRegistry.find("workbook.continuity.replay")
                .tags("provider", "codex", "outcome", "skipped", "reason", "ambiguous_anchor")
                .counter().count()).isEqualTo(1);
    }

    @Test
    void sendsHiddenForcedCallThenStreamsOnlyContinuationToClient() throws Exception {
        var received = new ArrayList<JsonNode>();
        var receivedContentEncodings = new ArrayList<String>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            synchronized (received) {
                received.add(request);
                receivedContentEncodings.add(exchange.getRequestHeaders().getFirst("content-encoding"));
            }
            String response = request.toString().contains("function_call_output")
                    ? codexTextResponse("final answer")
                    : hiddenCodexResponse();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var downstream = new RecordingSink();
        try (var service = new ProxyExchangeService(json, new TraceStore(50), new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            byte[] original = """
                    {"model":"gpt-test","input":[{"role":"user","content":"hello"}],
                     "tools":[{"type":"function","name":"real_tool","parameters":{"type":"object"}}],
                     "tool_choice":"auto","parallel_tool_calls":true,"stream":true}
                    """.getBytes(StandardCharsets.UTF_8);
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses?beta=true",
                    Map.of("content-type", List.of("application/json"),
                            "content-encoding", List.of("gzip")), gzip(original), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(received).hasSize(2);
        assertThat(receivedContentEncodings).containsOnlyNulls();
        assertThat(received.get(0).path("tool_choice").path("name").asText()).isEqualTo("workbook");
        // Inverted deliberately: the continuation must keep workbook declared alongside the client's tools.
        assertThat(received.get(1).path("tools").get(0).path("name").asText()).isEqualTo("workbook");
        assertThat(received.get(1).path("tools").get(1).path("name").asText()).isEqualTo("real_tool");
        assertThat(downstream.body.toString()).contains("final answer").doesNotContain("private notes");
        assertThat(downstream.status).isEqualTo(200);
    }

    @Test
    void groupsChainedCodexRequestsIntoOneSessionAndCapturesTheRealToolName() throws Exception {
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            String response = switch (requestCount.incrementAndGet()) {
                case 1 -> hiddenCodexResponse();
                // Deliberately ends without a trailing blank-line terminator after the last data line,
                // simulating a connection that closes right after its final event: only SseParser.finish()
                // (not feed() alone) can flush this into a dispatched "response.completed" event.
                case 2 -> "event: response.output_item.added\n"
                        + "data: {\"item\":{\"type\":\"function_call\",\"id\":\"fc_real\",\"call_id\":\"call_real\","
                        + "\"name\":\"run_js\",\"arguments\":\"\"}}\n"
                        + "\n"
                        + "event: response.completed\n"
                        + "data: {\"response\":{\"id\":\"resp_turn_1\",\"output\":[]}}";
                case 3 -> hiddenCodexResponse();
                default -> codexTextResponse("final answer");
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            byte[] firstTurn = """
                    {"model":"gpt-test","input":[{"role":"user","content":"hello"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8);
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses",
                            Map.of("content-type", List.of("application/json")), firstTurn, new IgnoringSink())
                    .completion().get(5, TimeUnit.SECONDS);

            byte[] secondTurn = """
                    {"model":"gpt-test","previous_response_id":"resp_turn_1",
                     "input":[{"type":"function_call_output","call_id":"call_real","output":"ok"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8);
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses",
                            Map.of("content-type", List.of("application/json")), secondTurn, new IgnoringSink())
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        var snapshots = traces.snapshot();
        assertThat(snapshots).hasSize(2);
        assertThat(requestCount.get()).isEqualTo(4);
        assertThat(snapshots.get(0).sessionId()).isEqualTo(snapshots.get(1).sessionId());
        assertThat(snapshots.get(0).model()).isEqualTo("gpt-test");
        assertThat(snapshots.stream().flatMap(s -> s.events().stream())
                        .filter(e -> "tool_call".equals(e.eventType()))
                        .map(io.github.softcane.workbook.proxy.trace.TraceEvent::visibleDelta))
                .containsExactly("run_js");
    }

    @Test
    void groupsCodexTurnsThatResendTheWholeGrowingInputIntoOneSession() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            String response = request.toString().contains("function_call_output")
                    ? "event: response.output_text.delta\ndata: {\"delta\":\"done\"}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"response\":{\"id\":\"resp_x\",\"output\":[]}}\n\n"
                    : hiddenCodexResponse();
            exchange.getResponseHeaders().add("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        upstream.start();
        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            // Real Codex sends no previous_response_id over HTTP; it resends the whole conversation, which
            // grows every turn. Both turns must still land in one session.
            send(service, traces, """
                    {"model":"gpt-test","input":[{"role":"user","content":"open the file"}],"stream":true}
                    """);
            send(service, traces, """
                    {"model":"gpt-test","input":[{"role":"user","content":"open the file"},
                     {"role":"assistant","content":"reading"},
                     {"role":"user","content":"now summarise it"}],"stream":true}
                    """);
        } finally {
            upstream.stop(0);
        }

        var snapshots = traces.snapshot();
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).sessionId()).isEqualTo(snapshots.get(1).sessionId());
    }


    @Test
    void separatesCodexProcessesWithSharedBootstrapAndGroupsResentTurns() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            writeSse(exchange, request.toString().contains("function_call_output")
                    ? codexTextResponse("done")
                    : hiddenCodexResponse());
        });
        upstream.start();

        var traces = new TraceStore(50);
        var headers = Map.of("content-type", List.of("application/json"));
        String bootstrap = "<environment_context><cwd>/workspace</cwd></environment_context>";
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            sendCodex(service, headers, """
                    {"model":"gpt-test","input":[
                      {"type":"message","role":"user","content":[{"type":"input_text","text":"%s"}]},
                      {"type":"message","role":"user","content":[{"type":"input_text","text":"open alpha"}]}],
                     "stream":true}
                    """.formatted(bootstrap));
            sendCodex(service, headers, """
                    {"model":"gpt-test","input":[
                      {"type":"message","role":"user","content":[{"type":"input_text","text":"%s"}]},
                      {"type":"message","role":"user","content":[{"type":"input_text","text":"open alpha"}]},
                      {"type":"message","role":"assistant","content":[{"type":"output_text","text":"done"}]},
                      {"type":"message","role":"user","content":[{"type":"input_text","text":"summarise alpha"}]}],
                     "stream":true}
                    """.formatted(bootstrap));
            sendCodex(service, headers, """
                    {"model":"gpt-test","input":[
                      {"type":"message","role":"user","content":[{"type":"input_text","text":"%s"}]},
                      {"type":"message","role":"user","content":[{"type":"input_text","text":"open beta"}]}],
                     "stream":true}
                    """.formatted(bootstrap));
        } finally {
            upstream.stop(0);
        }

        var snapshots = traces.snapshot();
        assertThat(snapshots).hasSize(3);
        // Newest first: the independent beta process, then both turns of alpha.
        assertThat(snapshots.get(1).sessionId()).isEqualTo(snapshots.get(2).sessionId());
        assertThat(snapshots.get(0).sessionId()).isNotEqualTo(snapshots.get(1).sessionId());
    }

    @Test
    void usesCodexSessionMetadataToSeparateIdenticalOpeningPrompts() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            writeSse(exchange, request.toString().contains("function_call_output")
                    ? codexTextResponse("done")
                    : hiddenCodexResponse());
        });
        upstream.start();

        var traces = new TraceStore(50);
        String request = """
                {"model":"gpt-test","client_metadata":{"session_id":"%s"},
                 "input":[{"type":"message","role":"user",
                 "content":[{"type":"input_text","text":"same opening prompt"}]}],"stream":true}
                """;
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            sendCodex(service, Map.of("content-type", List.of("application/json")),
                    request.formatted("codex-session-a"));
            sendCodex(service, Map.of("content-type", List.of("application/json")),
                    request.formatted("codex-session-b"));
        } finally {
            upstream.stop(0);
        }

        var snapshots = traces.snapshot();
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).sessionId()).isNotEqualTo(snapshots.get(1).sessionId());
        assertThat(snapshots).noneMatch(snapshot -> snapshot.sessionId().contains("codex-session-"));
    }

    private void sendCodex(ProxyExchangeService service, Map<String, List<String>> headers, String body)
            throws Exception {
        service.start(ProxyProvider.CODEX, "/backend-api/codex/responses", headers,
                        body.getBytes(StandardCharsets.UTF_8), new IgnoringSink())
                .completion().get(10, TimeUnit.SECONDS);
    }

    private void send(ProxyExchangeService service, TraceStore traces, String body) throws Exception {
        service.start(ProxyProvider.CODEX, "/backend-api/codex/responses",
                        Map.of("content-type", List.of("application/json")),
                        body.getBytes(StandardCharsets.UTF_8), new IgnoringSink())
                .completion().get(5, TimeUnit.SECONDS);
    }

    @Test
    void sumsTokenUsageFromTheHiddenWorkbookCallAndTheClientVisibleContinuation() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            String response = request.toString().contains("function_call_output")
                    ? "event: response.output_text.delta\ndata: {\"delta\":\"final answer\"}\n\n"
                            + "event: response.completed\n"
                            + "data: {\"response\":{\"id\":\"resp_usage\",\"output\":[],"
                            + "\"usage\":{\"input_tokens\":50,\"output_tokens\":5}}}\n\n"
                    : """
                            event: response.output_item.added
                            data: {"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"workbook","arguments":""}}

                            event: response.function_call_arguments.delta
                            data: {"delta":"{\\"work\\":\\"private notes\\"}"}

                            event: response.completed
                            data: {"response":{"id":"resp_1","output":[{"type":"function_call","id":"fc_1","call_id":"call_1","name":"workbook","arguments":"{\\"work\\":\\"private notes\\"}"}],"usage":{"input_tokens":100,"output_tokens":10}}}

                            """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            byte[] original = """
                    {"model":"gpt-test","input":[{"role":"user","content":"hello"}],"stream":true}
                    """.getBytes(StandardCharsets.UTF_8);
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses",
                            Map.of("content-type", List.of("application/json")), original, new IgnoringSink())
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        var snapshot = traces.snapshot().get(0);
        assertThat(snapshot.inputTokens()).isEqualTo(150);
        assertThat(snapshot.outputTokens()).isEqualTo(15);
    }

    @Test
    void failsOpenAndLogsSafelyWhenTheModelSkipsTheForcedWorkbookCall() throws Exception {
        var received = new ArrayList<String>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (received) {
                received.add(body);
            }
            // The hidden call comes back with a thinking block and a text reply and no private
            // tool_use at all -- capture is impossible, but the client's real turn must still run.
            String response = body.contains(ClaudeProtocol.TOOL_NAME)
                    ? """
                            event: message_start
                            data: {"message":{"usage":{"input_tokens":10}}}

                            event: content_block_start
                            data: {"index":0,"content_block":{"type":"thinking","thinking":""}}

                            event: content_block_delta
                            data: {"index":0,"delta":{"type":"thinking_delta","thinking":"private chain of thought"}}

                            event: content_block_stop
                            data: {"index":0}

                            event: content_block_start
                            data: {"index":1,"content_block":{"type":"text","text":""}}

                            event: content_block_delta
                            data: {"index":1,"delta":{"type":"text_delta","text":"answering without the tool"}}

                            event: content_block_stop
                            data: {"index":1}

                            event: message_delta
                            data: {"usage":{"output_tokens":20}}

                            event: message_stop
                            data: {}

                            """
                    : "event: content_block_delta\n"
                            + "data: {\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"real client answer\"}}\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ProxyExchangeService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        String original = """
                {"model":"claude-test","thinking":{"type":"adaptive"},
                 "messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")),
                            original.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
            logger.detachAppender(appender);
        }

        assertThat(received).hasSize(2);
        assertThat(received.get(1)).isEqualTo(original);
        assertThat(downstream.failures).isEmpty();
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString()).contains("real client answer");

        var snapshot = traces.snapshot().get(0);
        assertThat(snapshot.status()).isEqualTo("capture_skipped");
        assertThat(snapshot.events()).extracting(io.github.softcane.workbook.proxy.trace.TraceEvent::eventType)
                .containsExactly("capture_skipped");

        var logged = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("workbook capture skipped"))
                .toList();
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0))
                .contains("provider=claude")
                .contains("IllegalArgumentException")
                .contains("did not complete a workbook call")
                .doesNotContain("private chain of thought")
                .doesNotContain("answering without the tool");
    }

    @Test
    void reportsARefusalAsItsOwnReasonRatherThanADecodeFailure() throws Exception {
        var received = new ArrayList<String>();
        var upstream = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(body);
            // Live Opus 5 answers this wording by opening the workbook call, writing nothing into it,
            // and ending the turn with stop_reason refusal. The arguments are empty rather than damaged.
            String response = body.contains(ClaudeProtocol.TOOL_NAME)
                    ? """
                            event: message_start
                            data: {"message":{"usage":{"input_tokens":10}}}

                            event: content_block_start
                            data: {"index":0,"content_block":{"type":"tool_use","id":"toolu_refused","name":"workbook","input":{}}}

                            event: content_block_delta
                            data: {"index":0,"delta":{"type":"input_json_delta","partial_json":""}}

                            event: content_block_stop
                            data: {"index":0}

                            event: message_delta
                            data: {"delta":{"stop_reason":"refusal"},"usage":{"output_tokens":0}}

                            event: message_stop
                            data: {}

                            """
                    : "event: content_block_delta\n"
                            + "data: {\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"real client answer\"}}\n\n";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ProxyExchangeService.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        String original = """
                {"model":"claude-test","thinking":{"type":"adaptive"},
                 "messages":[{"role":"user","content":"hello"}],"stream":true}
                """;
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")),
                            original.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
            logger.detachAppender(appender);
        }

        assertThat(received).hasSize(2);
        assertThat(received.get(1)).isEqualTo(original);
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString()).contains("real client answer");

        var snapshot = traces.snapshot().get(0);
        assertThat(snapshot.status()).isEqualTo("capture_refused");

        var logged = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("workbook capture skipped"))
                .toList();
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0))
                .contains("CaptureRefusedException")
                .contains("refused the workbook call")
                .doesNotContain("Truncated")
                .doesNotContain("real client answer");
    }

    @Test
    void forwardsTheOriginalRequestUntouchedWhenManualThinkingRulesOutCapture() throws Exception {
        var received = new ArrayList<String>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            synchronized (received) {
                received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = ("event: content_block_delta\n"
                    + "data: {\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"real client answer\"}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        String original = """
                {"model":"claude-test","thinking":{"type":"enabled","budget_tokens":1024},
                 "messages":[{"role":"user","content":"hello"}],
                 "tools":[{"name":"real_tool","input_schema":{"type":"object"}}],"stream":true}
                """;
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")),
                            original.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        // Manual thinking rejects a forced tool_choice, so no hidden call is even attempted.
        assertThat(received).containsExactly(original);
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString()).contains("real client answer");
        assertThat(traces.snapshot().get(0).status()).isEqualTo("unsupported_capture");
    }

    @Test
    void forwardsANonStreamedClaudeRequestUntouchedBecauseTheContinuationCanOnlyStream() throws Exception {
        String providerAnswer = "{\"type\":\"message\",\"content\":[{\"type\":\"text\",\"text\":\"real client answer\"}]}";
        var received = new ArrayList<String>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            synchronized (received) {
                received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = providerAnswer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        // Claude Code opens an interactive session with exactly this probe: one message, one output
        // token, no stream.
        String original = """
                {"model":"claude-test","max_tokens":1,"messages":[{"role":"user","content":"test"}]}
                """;
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")),
                            original.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        // One call, and it is the client's own: no hidden call may be spent on a request the capture
        // path could never answer.
        assertThat(received).containsExactly(original);
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString()).isEqualTo(providerAnswer);
        assertThat(traces.snapshot().get(0).status()).isEqualTo("unsupported_capture");
    }

    @Test
    void forwardsANonStreamedCodexRequestUntouchedForTheSameReason() throws Exception {
        String providerAnswer = "{\"id\":\"resp_1\",\"output\":[{\"type\":\"message\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"real client answer\"}]}]}";
        var received = new ArrayList<String>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/backend-api/codex/responses", exchange -> {
            synchronized (received) {
                received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] bytes = providerAnswer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        String original = """
                {"model":"gpt-test","input":[{"role":"user","content":"hello"}]}
                """;
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CODEX, "/backend-api/codex/responses",
                            Map.of("content-type", List.of("application/json")),
                            original.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(received).containsExactly(original);
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString()).isEqualTo(providerAnswer);
        assertThat(traces.snapshot().get(0).status()).isEqualTo("unsupported_capture");
    }

    @Test
    void forwardsAProviderRefusalWithItsOwnStatusAndBodyInsteadOfAProxyEnvelope() throws Exception {
        String providerError = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\","
                + "\"message\":\"Number of request tokens has exceeded your per-minute rate limit\"}}";
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = providerError.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.getResponseHeaders().set("retry-after", "42");
            exchange.sendResponseHeaders(429, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")), """
                                    {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                                     "stream":true}
                                    """.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        // Claude Code matches on the provider's own error wording to decide how to retry.
        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(downstream.status).isEqualTo(429);
        assertThat(downstream.body.toString()).isEqualTo(providerError);
        assertThat(downstream.headers).containsEntry("retry-after", List.of("42"));
        assertThat(downstream.failures).isEmpty();
        assertThat(traces.snapshot().get(0).status()).isEqualTo("provider_error");
    }

    @Test
    void abandonsAHiddenCallThatOutlastsTheHiddenDeadlineAndStillCompletesTheClientTurn() throws Exception {
        var received = new ArrayList<String>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (received) {
                received.add(body);
            }
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            if (body.contains(ClaudeProtocol.TOOL_NAME)) {
                // Headers and a first chunk arrive promptly, then the stream stalls: HttpRequest.timeout
                // no longer applies once headers are in, so only the read-loop deadline can end this.
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(("event: content_block_start\n"
                        + "data: {\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                        + "\"name\":\"" + ClaudeProtocol.TOOL_NAME + "\",\"input\":{}}}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            byte[] bytes = ("event: content_block_delta\n"
                    + "data: {\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"real client answer\"}}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS.withHiddenTimeout(Duration.ofMillis(200)))) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")), """
                                    {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                                     "stream":true}
                                    """.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(10, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(received).hasSize(2);
        assertThat(received.get(1)).doesNotContain(ClaudeProtocol.TOOL_NAME);
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString()).contains("real client answer");
        assertThat(traces.snapshot().get(0).status()).isEqualTo("capture_timeout");
    }

    @Test
    void hidesARepeatWorkbookCallFromTheClientAndRecordsItsNotes() throws Exception {
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int index = requestCount.incrementAndGet();
            // Turn 1 is the forced hidden call; turn 2 is the continuation, and the model answers the
            // tool result with a second workbook call -- still legal, because the tool is still declared.
            String response = switch (index) {
                case 1 -> claudeWorkbookResponse("toolu_1", "first note");
                case 2 -> claudeWorkbookResponse("toolu_2", "second note");
                default -> claudeTextResponse("final answer");
            };
            writeSse(exchange, response);
        });
        upstream.start();

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")), """
                                    {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                                     "stream":true}
                                    """.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(10, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(requestCount.get()).isEqualTo(3);
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString())
                .contains("final answer")
                .doesNotContain(ClaudeProtocol.TOOL_NAME)
                .doesNotContain("first note")
                .doesNotContain("second note");
        var snapshot = traces.snapshot().get(0);
        assertThat(snapshot.status()).isEqualTo("complete");
        assertThat(snapshot.visibleWork()).isEqualTo("first notesecond note");
    }

    @Test
    void sendsTheClientsOwnRequestOnceTheModelExhaustsThePrivateLoopBudget() throws Exception {
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int index = requestCount.incrementAndGet();
            writeSse(exchange, body.contains(ClaudeProtocol.TOOL_NAME)
                    ? claudeWorkbookResponse("toolu_" + index, "note " + index)
                    : claudeTextResponse("final answer"));
        });
        upstream.start();

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")), """
                                    {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                                     "stream":true}
                                    """.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(10, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        // One hidden call, three gated continuations, then the client's own request.
        assertThat(requestCount.get()).isEqualTo(5);
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString()).contains("final answer")
                .doesNotContain(ClaudeProtocol.TOOL_NAME);
        // The notes themselves were captured, so the turn stays complete; the fallback is its own event.
        var exhausted = traces.snapshot().get(0);
        assertThat(exhausted.status()).isEqualTo("complete");
        assertThat(exhausted.events())
                .filteredOn(event -> "capture_skipped".equals(event.eventType()))
                .extracting(io.github.softcane.workbook.proxy.trace.TraceEvent::visibleDelta)
                .containsExactly("capture_loop_exhausted");
    }

    @Test
    void sendsTheClientsOwnRequestWhenTheGateWouldHaveToBufferTooMuch() throws Exception {
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var thinking = "x".repeat(4096);
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int index = requestCount.incrementAndGet();
            if (index == 1) {
                writeSse(exchange, claudeWorkbookResponse("toolu_1", "first note"));
                return;
            }
            if (index == 2) {
                // Thinking alone never commits the gate, so an unbounded run of it would be held in
                // memory until the response ended.
                var response = new StringBuilder("""
                        event: content_block_start
                        data: {"index":0,"content_block":{"type":"thinking","thinking":""}}

                        """);
                for (int block = 0; block < 300; block++) {
                    response.append("event: content_block_delta\ndata: {\"index\":0,\"delta\":")
                            .append("{\"type\":\"thinking_delta\",\"thinking\":\"").append(thinking)
                            .append("\"}}\n\n");
                }
                writeSse(exchange, response.toString());
                return;
            }
            writeSse(exchange, claudeTextResponse("final answer"));
        });
        upstream.start();

        var downstream = new RecordingSink();
        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")), """
                                    {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                                     "stream":true}
                                    """.getBytes(StandardCharsets.UTF_8), downstream)
                    .completion().get(20, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
        }

        assertThat(requestCount.get()).isEqualTo(3);
        assertThat(downstream.status).isEqualTo(200);
        assertThat(downstream.body.toString()).contains("final answer").doesNotContain(thinking);
        var overflowed = traces.snapshot().get(0);
        assertThat(overflowed.status()).isEqualTo("complete");
        assertThat(overflowed.events())
                .filteredOn(event -> "capture_skipped".equals(event.eventType()))
                .extracting(io.github.softcane.workbook.proxy.trace.TraceEvent::visibleDelta)
                .containsExactly("capture_skipped");
    }

    @Test
    void groupsClaudeTurnsByTheSessionHeaderAndKeepsSubagentsOnTheirOwnBranch() throws Exception {
        var requestCount = new java.util.concurrent.atomic.AtomicInteger();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            writeSse(exchange, body.contains(ClaudeProtocol.TOOL_NAME)
                    ? claudeWorkbookResponse("toolu_" + requestCount.incrementAndGet(), "note")
                    : claudeTextResponse("final answer"));
        });
        upstream.start();

        var traces = new TraceStore(50);
        try (var service = new ProxyExchangeService(json, traces, new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS)) {
            // Two turns of one session whose opening user message differs, plus a subagent turn that
            // repeats the first turn's message verbatim.
            send(service, Map.of("content-type", List.of("application/json"),
                    "x-claude-code-session-id", List.of("session-a")), "first message");
            send(service, Map.of("content-type", List.of("application/json"),
                    "x-claude-code-session-id", List.of("session-a")), "a later message");
            send(service, Map.of("content-type", List.of("application/json"),
                    "x-claude-code-session-id", List.of("session-a"),
                    "x-claude-code-agent-id", List.of("agent-1")), "first message");
            send(service, Map.of("content-type", List.of("application/json"),
                    "x-claude-code-session-id", List.of("session-b")), "first message");
        } finally {
            upstream.stop(0);
        }

        // Newest first: session-b, the subagent, then the two root turns of session-a.
        var sessionIds = traces.snapshot().stream().map(io.github.softcane.workbook.proxy.trace.TraceSnapshot::sessionId)
                .toList();
        assertThat(sessionIds.get(3)).isEqualTo(sessionIds.get(2));
        assertThat(sessionIds.get(1)).isNotEqualTo(sessionIds.get(2));
        assertThat(sessionIds.get(0)).isNotEqualTo(sessionIds.get(2));
        assertThat(sessionIds.get(0)).isNotEqualTo(sessionIds.get(1));
        // The raw session id never appears in a grouping key the dashboard shows.
        assertThat(sessionIds).noneMatch(id -> id.contains("session-a") || id.contains("session-b"));
    }

    private void send(ProxyExchangeService service, Map<String, List<String>> headers, String message)
            throws Exception {
        service.start(ProxyProvider.CLAUDE, "/v1/messages", headers,
                        ("{\"model\":\"claude-test\",\"messages\":[{\"role\":\"user\",\"content\":\""
                                + message + "\"}],\"stream\":true}").getBytes(StandardCharsets.UTF_8),
                        new IgnoringSink())
                .completion().get(10, TimeUnit.SECONDS);
    }

    private static void writeSse(com.sun.net.httpserver.HttpExchange exchange, String response)
            throws java.io.IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void writeOpenSse(com.sun.net.httpserver.HttpExchange exchange, String response,
            java.util.concurrent.CountDownLatch release) throws java.io.IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().flush();
        try {
            release.await();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Test upstream interrupted", cancelled);
        } finally {
            exchange.close();
        }
    }

    /**
     * A note is only interpretable next to the depth that produced it, so {@code request.received} has to
     * carry both efforts. Asserts the shapes reach the log and that nothing from the prompt or the note
     * travels with them.
     */
    @Test
    void requestReceivedRecordsBothTheClientsEffortAndTheOverride() throws Exception {
        var received = new ArrayList<String>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/messages", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = received.size() == 1
                    ? claudeWorkbookResponse("toolu_1", "expect two files")
                    : claudeTextResponse("real client answer");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        upstream.start();

        var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(Diagnostics.class);
        var previousLevel = logger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);

        String original = """
                {"model":"claude-test","thinking":{"type":"adaptive"},
                 "output_config":{"effort":"low"},
                 "messages":[{"role":"user","content":"secret-prompt-text"}],"stream":true}
                """;
        try (var service = new ProxyExchangeService(json, new TraceStore(50), new ConversationStateStore(json), new SimpleMeterRegistry(), URI.create("http://127.0.0.1:" + upstream.getAddress().getPort()), TEST_SETTINGS.withHiddenEffort(io.github.softcane.workbook.proxy.provider.HiddenEffort.MAX))) {
            service.start(ProxyProvider.CLAUDE, "/v1/messages",
                            Map.of("content-type", List.of("application/json")),
                            original.getBytes(StandardCharsets.UTF_8), new IgnoringSink())
                    .completion().get(5, TimeUnit.SECONDS);
        } finally {
            upstream.stop(0);
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        var line = appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("request.received"))
                .findFirst()
                .orElseThrow();
        assertThat(json.readTree(line).path("clientEffort").asText()).isEqualTo("low");
        assertThat(json.readTree(line).path("hiddenEffort").asText()).isEqualTo("max");

        // The hidden call really did carry the override, so the log describes what was sent.
        assertThat(json.readTree(received.get(0)).path("output_config").path("effort").asText()).isEqualTo("max");

        // SECURITY.md: a diagnostic carries shapes, never the prompt or the note.
        var everything = String.join("\n", appender.list.stream()
                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList());
        assertThat(everything)
                .doesNotContain("secret-prompt-text")
                .doesNotContain("expect two files")
                .doesNotContain("real client answer");
    }

    private String claudeWorkbookResponse(String toolUseId, String work) {
        return "event: message_start\n"
                + "data: {\"message\":{\"usage\":{\"input_tokens\":100}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + toolUseId
                + "\",\"name\":\"" + ClaudeProtocol.TOOL_NAME + "\",\"input\":{}}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":"
                + "\"{\\\"work\\\":\\\"" + work + "\\\"}\"}}\n\n"
                + "event: content_block_stop\ndata: {\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"usage\":{\"output_tokens\":10}}\n\n"
                + "event: message_stop\ndata: {}\n\n";
    }

    private String claudeTextResponse(String text) {
        return "event: message_start\n"
                + "data: {\"message\":{\"usage\":{\"input_tokens\":200}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}\n\n"
                + "event: content_block_stop\ndata: {\"index\":0}\n\n"
                + "event: message_stop\ndata: {}\n\n";
    }

    private String claudeRealToolResponse(String toolUseId, String name) {
        return "event: message_start\n"
                + "data: {\"message\":{\"usage\":{\"input_tokens\":200}}}\n\n"
                + "event: content_block_start\n"
                + "data: {\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + toolUseId
                + "\",\"name\":\"" + name + "\",\"input\":{}}}\n\n"
                + "event: content_block_stop\ndata: {\"index\":0}\n\n"
                + "event: message_stop\ndata: {}\n\n";
    }

    private List<String> claudeCallTimeline(JsonNode messages) {
        var timeline = new ArrayList<String>();
        messages.forEach(message -> message.path("content").forEach(block -> {
            String type = block.path("type").asText();
            if ("tool_use".equals(type)) timeline.add(type + ":" + block.path("id").asText());
            if ("tool_result".equals(type)) timeline.add(type + ":" + block.path("tool_use_id").asText());
        }));
        return timeline;
    }

    private byte[] gzip(byte[] input) throws java.io.IOException {
        var output = new java.io.ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) {
            gzip.write(input);
        }
        return output.toByteArray();
    }

    private List<String> codexCallTimeline(JsonNode input) {
        var timeline = new ArrayList<String>();
        input.forEach(item -> {
            String type = item.path("type").asText();
            if ("function_call".equals(type) || "function_call_output".equals(type)
                    || "custom_tool_call".equals(type) || "custom_tool_call_output".equals(type)) {
                timeline.add(type + ":" + item.path("call_id").asText());
            }
        });
        return timeline;
    }

    private ArrayNode codexWorkbookOutput(String itemId, String callId, String work,
            String encryptedContent, String opaqueValue) {
        var output = json.createArrayNode();
        var reasoning = json.createObjectNode()
                .put("type", "reasoning")
                .put("id", "rs_" + callId)
                .put("encrypted_content", encryptedContent);
        reasoning.set("summary", json.createArrayNode()
                .add(json.createObjectNode().put("type", "summary_text").put("text", "summary-" + callId)));
        output.add(reasoning);

        var call = json.createObjectNode()
                .put("type", "function_call")
                .put("id", itemId)
                .put("call_id", callId)
                .put("name", "workbook")
                .put("arguments", json.createObjectNode().put("work", work).toString())
                .put("status", "completed");
        call.set("provider_extension", json.createObjectNode().put("opaque", opaqueValue));
        output.add(call);
        return output;
    }

    private void appendCodexWorkbookExchange(ArrayNode input, ArrayNode providerOutput, String callId) {
        providerOutput.forEach(item -> input.add(item.deepCopy()));
        input.add(json.createObjectNode()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", CodexProtocol.WORKBOOK_RESULT));
    }

    private String codexWorkbookResponse(ArrayNode output) {
        JsonNode call = null;
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asText())
                    && "workbook".equals(item.path("name").asText())) {
                call = item;
                break;
            }
        }
        if (call == null) throw new IllegalArgumentException("Missing workbook call");

        var added = json.createObjectNode();
        added.set("item", call.deepCopy());
        var delta = json.createObjectNode().put("delta", call.path("arguments").asText());
        var response = json.createObjectNode().put("id", "resp_" + call.path("call_id").asText());
        response.set("output", output.deepCopy());
        var completed = json.createObjectNode();
        completed.set("response", response);
        return "event: response.output_item.added\n"
                + "data: " + added + "\n\n"
                + "event: response.function_call_arguments.delta\n"
                + "data: " + delta + "\n\n"
                + "event: response.completed\n"
                + "data: " + completed + "\n\n";
    }

    private String codexWorkbookResponse(String itemId, String callId, String work) {
        String arguments = "{\\\"work\\\":\\\"" + work + "\\\"}";
        return "event: response.output_item.added\n"
                + "data: {\"item\":{\"type\":\"function_call\",\"id\":\"" + itemId
                + "\",\"call_id\":\"" + callId + "\",\"name\":\"workbook\",\"arguments\":\"\"}}\n\n"
                + "event: response.function_call_arguments.delta\n"
                + "data: {\"delta\":\"" + arguments + "\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"response\":{\"id\":\"resp_" + callId
                + "\",\"output\":[{\"type\":\"function_call\",\"id\":\"" + itemId
                + "\",\"call_id\":\"" + callId + "\",\"name\":\"workbook\",\"arguments\":\""
                + arguments + "\"}]}}\n\n";
    }

    private String codexRealToolResponse(String itemId, String callId, String name) {
        return "event: response.output_item.added\n"
                + "data: {\"item\":{\"type\":\"function_call\",\"id\":\"" + itemId
                + "\",\"call_id\":\"" + callId + "\",\"name\":\"" + name
                + "\",\"arguments\":\"{}\"}}\n\n"
                + "event: response.completed\n"
                + "data: {\"response\":{\"id\":\"resp_" + callId
                + "\",\"output\":[{\"type\":\"function_call\",\"id\":\"" + itemId
                + "\",\"call_id\":\"" + callId + "\",\"name\":\"" + name
                + "\",\"arguments\":\"{}\"}]}}\n\n";
    }

    private String codexCustomToolResponse(String itemId, String callId, String name) {
        return "event: response.output_item.added\n"
                + "data: {\"item\":{\"type\":\"custom_tool_call\",\"id\":\"" + itemId
                + "\",\"call_id\":\"" + callId + "\",\"name\":\"" + name
                + "\",\"input\":\"{}\"}}\n\n"
                + "event: response.completed\n"
                + "data: {\"response\":{\"id\":\"resp_" + callId
                + "\",\"output\":[{\"type\":\"custom_tool_call\",\"id\":\"" + itemId
                + "\",\"call_id\":\"" + callId + "\",\"name\":\"" + name
                + "\",\"input\":\"{}\"}]}}\n\n";
    }

    private String codexTextResponse(String text) {
        return "event: response.output_text.delta\n"
                + "data: {\"delta\":\"" + text + "\"}\n\n"
                + "event: response.completed\n"
                + "data: {\"response\":{\"id\":\"resp_final\",\"output\":[]}}\n\n";
    }

    private String hiddenCodexResponse() {
        return """
                event: response.output_item.added
                data: {"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"workbook","arguments":""}}

                event: response.function_call_arguments.delta
                data: {"delta":"{\\"work\\":\\"private notes\\"}"}

                event: response.completed
                data: {"response":{"id":"resp_1","output":[{"type":"function_call","id":"fc_1","call_id":"call_1","name":"workbook","arguments":"{\\"work\\":\\"private notes\\"}"}]}}

                """;
    }

    private static final class BlockingSink implements DownstreamSink {
        private final String marker;
        private final java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.Semaphore release = new java.util.concurrent.Semaphore(0);
        private final java.util.concurrent.atomic.AtomicBoolean blockedOnce =
                new java.util.concurrent.atomic.AtomicBoolean();
        private volatile boolean ended;

        private BlockingSink(String marker) {
            this.marker = marker;
        }

        @Override public void headers(int status, Map<String, List<String>> headers) { }

        @Override public void body(byte[] bytes, boolean endOfStream) {
            if (endOfStream) {
                ended = true;
                return;
            }
            if (new String(bytes, StandardCharsets.UTF_8).contains(marker)
                    && blockedOnce.compareAndSet(false, true)) {
                blocked.countDown();
                release.acquireUninterruptibly();
            }
        }

        @Override public void failure(String code, String message) { }

        private boolean awaitBlocked() throws InterruptedException {
            return blocked.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            release.release();
        }

        private boolean ended() {
            return ended;
        }
    }

    private static final class RecordingSink implements DownstreamSink {
        private final StringBuilder body = new StringBuilder();
        private final List<String> failures = new ArrayList<>();
        private Map<String, List<String>> headers = Map.of();
        private int status;
        private boolean ended;

        @Override public void headers(int status, Map<String, List<String>> headers) {
            this.status = status;
            this.headers = headers;
        }
        @Override public void body(byte[] bytes, boolean endOfStream) {
            body.append(new String(bytes, StandardCharsets.UTF_8));
            if (endOfStream) ended = true;
        }
        @Override public void failure(String code, String message) { failures.add(code + ": " + message); }
    }

    private static final class IgnoringSink implements DownstreamSink {
        @Override public void headers(int status, Map<String, List<String>> headers) { }
        @Override public void body(byte[] bytes, boolean endOfStream) { }
        @Override public void failure(String code, String message) { }
    }
}
