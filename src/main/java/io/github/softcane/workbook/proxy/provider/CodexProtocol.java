package io.github.softcane.workbook.proxy.provider;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.softcane.workbook.provider.IncrementalWorkDecoder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public final class CodexProtocol {
    /** The name the tool carries on the wire; see {@link ClaudeProtocol#TOOL_NAME} for why not "scratchpad". */
    public static final String TOOL_NAME = "workbook";
    /**
     * The result the proxy returns for a call it executed itself. Nothing but the cue back to the model:
     * a longer preamble here only competes with the client's own tool results for the model's attention.
     */
    public static final String WORKBOOK_RESULT = "Continue thinking, call a tool, or respond to the user.";

    private final ObjectMapper json;
    private final NoteStyle.Wording wording;

    public CodexProtocol(ObjectMapper json) {
        this(json, NoteStyle.REASONING);
    }

    /**
     * Codex takes the same style switch as Claude but never the same sentences: the two vocabularies were
     * tuned against different refusals. Like Claude's, this path appends nothing to the client's own
     * request — the tool declaration is the only place the ask lands.
     */
    public CodexProtocol(ObjectMapper json, NoteStyle noteStyle) {
        this.json = json;
        this.wording = noteStyle.codex();
    }

    /**
     * Whether this request can carry a hidden workbook exchange at all. Only an input ending on an
     * assistant turn refuses: that is a prefill, and a forced tool call would abandon the half-written turn
     * the client asked the model to finish. Such a request is forwarded untouched instead.
     *
     * <p>The client's {@code tool_choice} is deliberately not a refusal, whatever it says. Only the hidden
     * call overrides it; {@link #continueAfterWorkbook} copies the original request, so the client-visible
     * turn is still bound by the client's own value -- {@code "required"} still demands one of the client's
     * tools, {@code "none"} still yields no call, and a named function is still the one the model must
     * call. Refusing capture for those would cost a note and protect nothing.
     *
     * <p>{@code previous_response_id} is deliberately not a refusal, and Codex never sends it over HTTP --
     * it exists only on its WebSocket request, which this proxy does not intercept. The continuation
     * appends our items to whatever input the client sent, so chaining by id would stay correct anyway:
     * the hidden response is only ever replayed as items, never referenced by id.
     */
    public boolean supportsCapture(ObjectNode original) {
        return endsOnUserTurn(original.path("input"));
    }

    /**
     * Trailing system and developer items are the client's own and are skipped: they do not change whose
     * turn it is. A trailing {@code function_call_output} carries no role and is the normal agentic shape,
     * so only an explicit assistant role refuses. A bare string input is a user turn.
     */
    private static boolean endsOnUserTurn(JsonNode input) {
        if (input.isTextual()) return true;
        if (!input.isArray()) return false;
        for (int index = input.size() - 1; index >= 0; index--) {
            String role = input.get(index).path("role").asText();
            if ("system".equals(role) || "developer".equals(role)) continue;
            return !"assistant".equals(role);
        }
        return false;
    }

    public ObjectNode forceWorkbook(ObjectNode original) {
        var forced = original.deepCopy();
        forced.set("tools", toolsWithWorkbook(original));
        forced.set("tool_choice", json.createObjectNode()
                .put("type", "function")
                .put("name", TOOL_NAME));
        forced.put("parallel_tool_calls", false);
        forced.put("stream", true);
        return forced;
    }

    public HiddenStream newHiddenStream(Consumer<String> visibleDelta) {
        return new HiddenStream(json, visibleDelta);
    }

    /**
     * Keeps workbook declared and leaves the client's own {@code tool_choice} in place. Withdrawing the
     * tool would replay a {@code function_call} for a function the request no longer declares -- the same
     * shape that made Claude read the exchange as an injected fake. The API is not documented to reject
     * that shape, so this is a behavioral argument, not a schema one; it is also what lets the model call
     * workbook again, which {@link ResponseGate} is here to catch.
     *
     * <p>The provider's whole {@code output} array goes back verbatim rather than the call alone because
     * the Responses API requires reasoning items returned alongside a tool call to be replayed with its
     * output, {@code encrypted_content} included.
     */
    public ObjectNode continueAfterWorkbook(ObjectNode original, HiddenResult hidden) {
        return continueAfterWorkbook(original, List.of(hidden));
    }

    /**
     * Replays every workbook exchange this turn produced. More than one happens when the model answers
     * the first tool result with another workbook call, which stays legal precisely because the tool is
     * still declared.
     */
    public ObjectNode continueAfterWorkbook(ObjectNode original, List<HiddenResult> exchanges) {
        var continuation = original.deepCopy();
        continuation.set("tools", toolsWithWorkbook(original));
        var input = json.createArrayNode();
        var originalInput = original.get("input");
        if (originalInput != null && originalInput.isArray()) {
            originalInput.forEach(item -> input.add(item.deepCopy()));
        } else if (originalInput != null && !originalInput.isNull()) {
            input.add(json.createObjectNode()
                    .put("role", "user")
                    .set("content", originalInput.deepCopy()));
        }
        exchanges.forEach(hidden -> {
            hidden.output().forEach(item -> input.add(item.deepCopy()));
            input.add(json.createObjectNode()
                    .put("type", "function_call_output")
                    .put("call_id", hidden.callId())
                    .put("output", WORKBOOK_RESULT));
        });
        continuation.set("input", input);
        continuation.put("stream", true);
        return continuation;
    }

    /**
     * Holds the client-visible stream back until the model's first real output item proves the response
     * belongs to the client. The continuation still declares workbook, so the model is still free to call
     * it again; without this gate that repeat call would stream to a client that has no such tool.
     *
     * <p>The gate decides on the first output item that is not a reasoning item, mirroring the Claude
     * gate's treatment of thinking blocks.
     */
    public ResponseGate newResponseGate(Consumer<byte[]> release, Consumer<String> visibleDelta,
            int maximumBufferedBytes) {
        return new ResponseGate(json, release, visibleDelta, maximumBufferedBytes);
    }

    private ArrayNode toolsWithWorkbook(ObjectNode original) {
        var tools = json.createArrayNode();
        tools.add(workbookTool());
        if (original.path("tools").isArray()) {
            original.path("tools").forEach(tool -> {
                if (TOOL_NAME.equals(tool.path("name").asText())) {
                    throw new IllegalArgumentException("The tool name " + TOOL_NAME + " is reserved by the local proxy");
                }
                tools.add(tool.deepCopy());
            });
        }
        return tools;
    }

    private ObjectNode workbookTool() {
        var properties = json.createObjectNode();
        properties.set("work", json.createObjectNode()
                .put("type", "string")
                .put("description", wording.workDescription()));
        var parameters = json.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        parameters.set("properties", properties);
        parameters.set("required", json.createArrayNode().add("work"));
        var tool = json.createObjectNode()
                .put("type", "function")
                .put("name", TOOL_NAME)
                .put("description", wording.description())
                .put("strict", false);
        tool.set("parameters", parameters);
        return tool;
    }

    public record HiddenResult(ArrayNode output, String callId, String work, long inputTokens, long outputTokens) { }

    public static final class ResponseGate {
        private final Consumer<byte[]> release;
        private final HiddenStream hidden;
        private final SseParser parser;
        private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        private final int maximumBufferedBytes;
        private final ObjectMapper json;
        private boolean committed;
        private boolean privateWorkbook;

        private ResponseGate(ObjectMapper json, Consumer<byte[]> release, Consumer<String> visibleDelta,
                int maximumBufferedBytes) {
            this.json = json;
            this.release = release;
            this.maximumBufferedBytes = maximumBufferedBytes;
            this.hidden = new HiddenStream(json, visibleDelta);
            this.parser = new SseParser(this::onEvent);
        }

        public void accept(byte[] chunk) {
            if (committed) {
                release.accept(chunk);
                return;
            }
            // Buffered before parsing, so a commit decided part-way through this chunk still flushes the
            // whole chunk rather than dropping the bytes that decided it.
            buffered.writeBytes(chunk);
            if (buffered.size() > maximumBufferedBytes) {
                throw new IllegalArgumentException("Codex response gate exceeded its local buffer limit");
            }
            parser.feed(chunk);
        }

        public boolean committed() {
            return committed;
        }

        /** The workbook exchange to fold into the next request, or empty once the response was released. */
        public Optional<HiddenResult> finish() {
            if (!committed) parser.finish();
            if (privateWorkbook) return Optional.of(hidden.finish());
            commit();
            return Optional.empty();
        }

        private void onEvent(String event, String data) {
            JsonNode parsed;
            try {
                parsed = json.readTree(data);
            } catch (JacksonException error) {
                throw new IllegalArgumentException("Codex returned invalid SSE JSON", error);
            }
            if (!privateWorkbook && "response.output_item.added".equals(event)) {
                JsonNode item = parsed.path("item");
                String type = item.path("type").asText();
                if ("function_call".equals(type) && TOOL_NAME.equals(item.path("name").asText())) {
                    privateWorkbook = true;
                } else if (!"reasoning".equals(type)) {
                    commit();
                }
            }
            if (!committed) hidden.accept(event, parsed);
        }

        private void commit() {
            if (committed) return;
            committed = true;
            if (buffered.size() > 0) release.accept(buffered.toByteArray());
            buffered.reset();
        }
    }

    public static final class HiddenStream {
        private final ObjectMapper json;
        private final Consumer<String> visibleDelta;
        private final IncrementalWorkDecoder decoder = new IncrementalWorkDecoder();
        private final StringBuilder rawArguments = new StringBuilder();
        private final ArrayNode output;
        private ObjectNode completedResponse;

        private HiddenStream(ObjectMapper json, Consumer<String> visibleDelta) {
            this.json = json;
            this.visibleDelta = visibleDelta;
            this.output = json.createArrayNode();
        }

        public void accept(String event, JsonNode data) {
            switch (event) {
                case "response.function_call_arguments.delta" -> {
                    String delta = data.path("delta").asText();
                    rawArguments.append(delta);
                    String visible = decoder.feed(delta.getBytes(StandardCharsets.UTF_8));
                    if (!visible.isEmpty()) visibleDelta.accept(visible);
                }
                case "response.output_item.done" -> {
                    if (data.path("item").isObject()) output.add(data.path("item").deepCopy());
                }
                case "response.completed" -> completeFrom(data.path("response"));
                default -> { }
            }
        }

        /** Some deployments only ever name the output items in the terminal event, so it is the fallback. */
        private void completeFrom(JsonNode response) {
            if (!response.isObject()) return;
            completedResponse = (ObjectNode) response.deepCopy();
            if (output.isEmpty() && completedResponse.path("output").isArray()) {
                completedResponse.path("output").forEach(item -> output.add(item.deepCopy()));
            }
        }

        public HiddenResult finish() {
            if (completedResponse == null) {
                throw new IllegalArgumentException("Codex hidden response did not complete");
            }
            String visibleWork = decoder.finish();
            JsonNode call = null;
            for (JsonNode item : output) {
                if ("function_call".equals(item.path("type").asText())
                        && TOOL_NAME.equals(item.path("name").asText())) {
                    call = item;
                    break;
                }
            }
            if (call == null || call.path("call_id").asText().isBlank()) {
                throw new IllegalArgumentException(
                        "Codex did not return the forced workbook call; output=" + outputShape());
            }
            String completedArguments = call.path("arguments").asText();
            if (!completedArguments.equals(rawArguments.toString())) {
                throw new IllegalArgumentException("Codex workbook argument deltas did not reassemble exactly");
            }
            try {
                String completedWork = json.readTree(completedArguments).path("work").asText();
                if (!completedWork.equals(visibleWork)) {
                    throw new IllegalArgumentException("Codex visible workbook did not match final work");
                }
            } catch (JacksonException error) {
                throw new IllegalArgumentException("Codex returned invalid workbook arguments", error);
            }
            JsonNode usage = completedResponse.path("usage");
            return new HiddenResult(output.deepCopy(), call.path("call_id").asText(), visibleWork,
                    usage.path("input_tokens").asLong(0), usage.path("output_tokens").asLong(0));
        }

        private String outputShape() {
            var shape = new StringBuilder("[");
            for (JsonNode item : output) {
                if (shape.length() > 1) shape.append(',');
                shape.append(item.path("type").asText("unknown"));
                if (!item.path("name").asText().isBlank()) {
                    shape.append(':').append(item.path("name").asText());
                }
            }
            return shape.append(']').toString();
        }
    }
}
