package io.github.softcane.workbook.proxy.provider;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.softcane.workbook.provider.IncrementalWorkDecoder;
import io.github.softcane.workbook.proxy.ConversationStateStore;
import io.github.softcane.workbook.proxy.Sha256;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;
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


    public ConversationStateStore.RequestContext requestContext(ObjectNode original) {
        if (!original.path("previous_response_id").asText("").isBlank()) {
            return unsupportedContext();
        }
        JsonNode input = original.path("input");
        if (input.isTextual()) {
            return new ConversationStateStore.RequestContext(
                    ConversationStateStore.RequestKind.OPENING, Set.of(), Set.of());
        }
        if (!(input instanceof ArrayNode items) || items.isEmpty()) return unsupportedContext();

        var historyResultIds = new LinkedHashSet<String>();
        items.forEach(item -> {
            if (isToolResult(item.path("type").asText())
                    && !item.path("call_id").asText().isBlank()) {
                historyResultIds.add(item.path("call_id").asText());
            }
        });
        var trailingResultIds = new LinkedHashSet<String>();
        for (int index = items.size() - 1; index >= 0; index--) {
            JsonNode item = items.get(index);
            String role = item.path("role").asText();
            if ("system".equals(role) || "developer".equals(role)) continue;
            if (!isToolResult(item.path("type").asText())) break;
            String callId = item.path("call_id").asText();
            if (!callId.isBlank()) trailingResultIds.add(callId);
        }
        if (!trailingResultIds.isEmpty()) {
            return new ConversationStateStore.RequestContext(
                    ConversationStateStore.RequestKind.TOOL_RESULTS, trailingResultIds, historyResultIds);
        }
        return endsOnUserTurn(input)
                ? new ConversationStateStore.RequestContext(
                        ConversationStateStore.RequestKind.OPENING, Set.of(), historyResultIds)
                : unsupportedContext();
    }

    public String historyFingerprint(ObjectNode request) {
        var history = json.createObjectNode();
        if (request.has("previous_response_id")) {
            history.set("previous_response_id", request.get("previous_response_id").deepCopy());
        }
        if (request.has("input")) history.set("input", canonical(request.get("input")));
        return Sha256.of(json.writeValueAsString(history));
    }

    public ConversationStateStore.RestoreResult restoreWorkbookHistory(ObjectNode original,
            List<ConversationStateStore.Boundary> boundaries) {
        if (!(original.path("input") instanceof ArrayNode)) {
            return new ConversationStateStore.RestoreResult(original, 0, "ambiguous_anchor");
        }
        ObjectNode restored = original.deepCopy();
        var input = (ArrayNode) restored.path("input");
        int inserted = 0;
        for (ConversationStateStore.Boundary boundary : boundaries) {
            int anchor = uniqueItemId(input, boundary.insertionAnchorId());
            if (anchor < 0 || !hasCompleteRealBatch(input, anchor, boundary.realToolCallIds())) {
                return new ConversationStateStore.RestoreResult(original, 0, "ambiguous_anchor");
            }
            var expected = exchangeItems(boundary.exchanges());
            var existingWorkbookIndices = workbookIndices(input, boundary.exchanges());
            if (existingWorkbookIndices.isEmpty()) {
                if (!boundary.sourceFingerprint().equals(prefixFingerprint(restored, input, anchor))) {
                    return new ConversationStateStore.RestoreResult(original, 0, "changed_branch");
                }
                input = insertBefore(restored, input, anchor, expected);
                inserted++;
                continue;
            }
            int start = anchor - expected.size();
            if (start < 0 || !matches(input, start, expected)
                    || existingWorkbookIndices.size() != workbookCallCount(boundary.exchanges())) {
                return new ConversationStateStore.RestoreResult(original, 0, "partial_existing_replay");
            }
            if (!boundary.sourceFingerprint().equals(prefixFingerprint(restored, input, start))) {
                return new ConversationStateStore.RestoreResult(original, 0, "changed_branch");
            }
        }
        return new ConversationStateStore.RestoreResult(restored, inserted, null);
    }

    public Optional<ConversationStateStore.ToolCall> realToolCall(String event, JsonNode data) {
        if (!"response.output_item.added".equals(event)) return Optional.empty();
        JsonNode item = data.path("item");
        if (!isToolCall(item.path("type").asText())
                || TOOL_NAME.equals(item.path("name").asText())
                || item.path("call_id").asText().isBlank()) return Optional.empty();
        return Optional.of(new ConversationStateStore.ToolCall(
                item.path("call_id").asText(), item.path("name").asText()));
    }

    public Optional<String> outputItemId(String event, JsonNode data) {
        if (!"response.output_item.added".equals(event)) return Optional.empty();
        String id = data.path("item").path("id").asText();
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    private static boolean isToolCall(String type) {
        return "function_call".equals(type) || "custom_tool_call".equals(type);
    }

    private static boolean isToolResult(String type) {
        return "function_call_output".equals(type) || "custom_tool_call_output".equals(type);
    }

    private ConversationStateStore.RequestContext unsupportedContext() {
        return new ConversationStateStore.RequestContext(
                ConversationStateStore.RequestKind.UNSUPPORTED, Set.of(), Set.of());
    }

    private String prefixFingerprint(ObjectNode request, ArrayNode input, int endExclusive) {
        var prefix = request.deepCopy();
        var prefixInput = json.createArrayNode();
        for (int index = 0; index < endExclusive; index++) prefixInput.add(input.get(index).deepCopy());
        prefix.set("input", prefixInput);
        return historyFingerprint(prefix);
    }

    private ArrayNode insertBefore(ObjectNode request, ArrayNode input, int anchor, ArrayNode additions) {
        var rebuilt = json.createArrayNode();
        for (int index = 0; index < anchor; index++) rebuilt.add(input.get(index).deepCopy());
        additions.forEach(item -> rebuilt.add(item.deepCopy()));
        for (int index = anchor; index < input.size(); index++) rebuilt.add(input.get(index).deepCopy());
        request.set("input", rebuilt);
        return rebuilt;
    }

    private ArrayNode exchangeItems(List<ConversationStateStore.Exchange> exchanges) {
        var items = json.createArrayNode();
        for (ConversationStateStore.Exchange exchange : exchanges) {
            appendExchange(items, exchange.providerOutput(), exchange.workbookCallId(), exchange.workbookResult());
        }
        return items;
    }

    private void appendExchange(ArrayNode input, ArrayNode output, String callId, String result) {
        output.forEach(item -> input.add(item.deepCopy()));
        input.add(json.createObjectNode()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", result));
    }

    private static int uniqueItemId(ArrayNode input, String id) {
        int found = -1;
        for (int index = 0; index < input.size(); index++) {
            if (!id.equals(input.get(index).path("id").asText())) continue;
            if (found >= 0) return -1;
            found = index;
        }
        return found;
    }

    private static boolean hasCompleteRealBatch(ArrayNode input, int anchor, Set<String> realToolCallIds) {
        for (String callId : realToolCallIds) {
            int call = -1;
            int result = -1;
            String callType = "";
            String resultType = "";
            for (int index = 0; index < input.size(); index++) {
                JsonNode item = input.get(index);
                String type = item.path("type").asText();
                if (isToolCall(type) && callId.equals(item.path("call_id").asText())) {
                    if (call >= 0) return false;
                    call = index;
                    callType = type;
                }
                if (isToolResult(type) && callId.equals(item.path("call_id").asText())) {
                    if (result >= 0) return false;
                    result = index;
                    resultType = type;
                }
            }
            if (call < anchor || result <= call || !matchingToolPair(callType, resultType)) return false;
        }
        return !realToolCallIds.isEmpty();
    }

    private static boolean matchingToolPair(String callType, String resultType) {
        return ("function_call".equals(callType) && "function_call_output".equals(resultType))
                || ("custom_tool_call".equals(callType) && "custom_tool_call_output".equals(resultType));
    }

    private static Set<Integer> workbookIndices(ArrayNode input,
            List<ConversationStateStore.Exchange> exchanges) {
        var ids = new LinkedHashSet<String>();
        exchanges.forEach(exchange -> ids.add(exchange.workbookCallId()));
        var indices = new LinkedHashSet<Integer>();
        for (int index = 0; index < input.size(); index++) {
            JsonNode item = input.get(index);
            if (("function_call".equals(item.path("type").asText())
                    || "function_call_output".equals(item.path("type").asText()))
                    && ids.contains(item.path("call_id").asText())) indices.add(index);
        }
        return indices;
    }

    private static int workbookCallCount(List<ConversationStateStore.Exchange> exchanges) {
        int count = 0;
        for (ConversationStateStore.Exchange exchange : exchanges) {
            for (JsonNode item : exchange.providerOutput()) {
                if ("function_call".equals(item.path("type").asText())
                        && exchange.workbookCallId().equals(item.path("call_id").asText())) count++;
            }
            count++;
        }
        return count;
    }

    private static boolean matches(ArrayNode input, int start, ArrayNode expected) {
        if (start + expected.size() > input.size()) return false;
        for (int offset = 0; offset < expected.size(); offset++) {
            if (!input.get(start + offset).equals(expected.get(offset))) return false;
        }
        return true;
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) return node == null ? json.nullNode() : node.deepCopy();
        if (node.isArray()) {
            var array = json.createArrayNode();
            node.forEach(child -> array.add(canonical(child)));
            return array;
        }
        var sorted = new TreeMap<String, JsonNode>();
        node.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        var object = json.createObjectNode();
        sorted.forEach((name, value) -> object.set(name, canonical(value)));
        return object;
    }

    /**
     * Trailing system and developer items are the client's own and do not change whose turn it is.
     * Function and custom tool results carry no role and are the normal agentic shape, so only an explicit
     * assistant role refuses. A bare string input is a user turn.
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
        exchanges.forEach(hidden ->
                appendExchange(input, hidden.output(), hidden.callId(), WORKBOOK_RESULT));
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
        private boolean terminal;

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
            // Buffer the whole chunk before parsing it, and only flush after every event in that chunk
            // has been seen. A response.completed frame can share a network chunk with the output item
            // that commits the gate; delaying the flush lets the service persist terminal state first.
            buffered.writeBytes(chunk);
            if (buffered.size() > maximumBufferedBytes) {
                throw new IllegalArgumentException("Codex response gate exceeded its local buffer limit");
            }
            parser.feed(chunk);
            flushCommitted();
        }

        public boolean committed() {
            return committed;
        }
        public boolean visibleTerminal() {
            return terminal && !privateWorkbook;
        }


        /**
         * Returns a private workbook exchange when one completed. Empty has three possible meanings:
         * visible bytes were already committed; a visible terminal response is still buffered and requires
         * {@link #release()}; or EOF supplied no classifying event and requires the original-request fallback.
         * Callers must distinguish them with {@link #committed()} and {@link #visibleTerminal()}.
         */
        public Optional<HiddenResult> finish() {
            if (committed) {
                flushCommitted();
                return Optional.empty();
            }
            parser.finish();
            if (privateWorkbook) return Optional.of(hidden.finish());
            flushCommitted();
            return Optional.empty();
        }

        public void release() {
            commit();
            flushCommitted();
        }
        private void onEvent(String event, String data) {
            if ("response.completed".equals(event)) terminal = true;
            if (committed) return;
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
            committed = true;
        }

        private void flushCommitted() {
            if (!committed || buffered.size() == 0) return;
            release.accept(buffered.toByteArray());
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
