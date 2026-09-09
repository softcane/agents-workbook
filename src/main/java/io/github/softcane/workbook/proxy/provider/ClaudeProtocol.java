package io.github.softcane.workbook.proxy.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.softcane.workbook.provider.IncrementalWorkDecoder;
import io.github.softcane.workbook.proxy.Diagnostics;
import io.github.softcane.workbook.proxy.ConversationStateStore;
import io.github.softcane.workbook.proxy.Sha256;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class ClaudeProtocol {
    /**
     * The name the tool carries on the wire. Deliberately not "scratchpad": Claude Code's own system
     * prompt documents a "Scratchpad Directory", so a tool by that name reads as an impostor of
     * something the model already trusts, and live Sonnet 5 runs named it as an injection attempt on
     * that basis.
     */
    public static final String TOOL_NAME = "workbook";
    /**
     * The result the proxy returns for a call it executed itself. It states what actually happened —
     * the user's own dashboard holds the note — because a result the model can tell is fabricated is a
     * reason to distrust the whole exchange and refuse the task.
     */
    public static final String WORKBOOK_RESULT =
            "Recorded on the user's dashboard. Continue thinking, call a tool, or respond to the user.";
    private final ObjectMapper json;
    private final NoteStyle.Wording wording;
    private final HiddenEffort hiddenEffort;

    public ClaudeProtocol(ObjectMapper json) {
        this(json, NoteStyle.REASONING);
    }

    public ClaudeProtocol(ObjectMapper json, NoteStyle noteStyle) {
        this(json, noteStyle, HiddenEffort.INHERIT);
    }

    public ClaudeProtocol(ObjectMapper json, NoteStyle noteStyle, HiddenEffort hiddenEffort) {
        this.json = json;
        this.wording = noteStyle.claude();
        this.hiddenEffort = hiddenEffort;
    }


    public ConversationStateStore.RequestContext requestContext(ObjectNode original) {
        if ("enabled".equals(original.path("thinking").path("type").asText())) return unsupportedContext();
        if (!(original.path("messages") instanceof ArrayNode messages) || messages.isEmpty()) {
            return unsupportedContext();
        }
        var historyResultIds = new LinkedHashSet<String>();
        messages.forEach(message -> message.path("content").forEach(block -> {
            if ("tool_result".equals(block.path("type").asText())
                    && !block.path("tool_use_id").asText().isBlank()) {
                historyResultIds.add(block.path("tool_use_id").asText());
            }
        }));
        JsonNode active = null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if ("system".equals(messages.get(index).path("role").asText())) continue;
            active = messages.get(index);
            break;
        }
        if (active == null || !"user".equals(active.path("role").asText())) return unsupportedContext();
        var trailingResultIds = new LinkedHashSet<String>();
        if (active.path("content").isArray()) {
            for (JsonNode block : active.path("content")) {
                if (!"tool_result".equals(block.path("type").asText())) break;
                String id = block.path("tool_use_id").asText();
                if (!id.isBlank()) trailingResultIds.add(id);
            }
        }
        return new ConversationStateStore.RequestContext(
                trailingResultIds.isEmpty()
                        ? ConversationStateStore.RequestKind.OPENING
                        : ConversationStateStore.RequestKind.TOOL_RESULTS,
                trailingResultIds, historyResultIds);
    }

    public String historyFingerprint(ObjectNode request) {
        var history = json.createObjectNode();
        if (request.has("messages")) history.set("messages", canonical(request.get("messages")));
        return Sha256.of(json.writeValueAsString(history));
    }

    public ConversationStateStore.RestoreResult restoreWorkbookHistory(ObjectNode original,
            List<ConversationStateStore.Boundary> boundaries) {
        if (!(original.path("messages") instanceof ArrayNode)) {
            return new ConversationStateStore.RestoreResult(original, 0, "ambiguous_anchor");
        }
        ObjectNode restored = original.deepCopy();
        var messages = (ArrayNode) restored.path("messages");
        int inserted = 0;
        for (ConversationStateStore.Boundary boundary : boundaries) {
            int anchor = uniqueRealBatchMessage(
                    messages, boundary.insertionAnchorId(), boundary.realToolCallIds());
            if (anchor < 0 || !hasFollowingResults(messages, anchor, boundary.realToolCallIds())) {
                return new ConversationStateStore.RestoreResult(original, 0, "ambiguous_anchor");
            }
            var expected = exchangeMessages(boundary.exchanges());
            var existingWorkbookMessages = workbookMessageIndices(messages, boundary.exchanges());
            if (existingWorkbookMessages.isEmpty()) {
                if (!boundary.sourceFingerprint().equals(prefixFingerprint(restored, messages, anchor))) {
                    return new ConversationStateStore.RestoreResult(original, 0, "changed_branch");
                }
                messages = insertBefore(restored, messages, anchor, expected);
                inserted++;
                continue;
            }
            int start = anchor - expected.size();
            if (start < 0 || !matches(messages, start, expected)
                    || existingWorkbookMessages.size() != boundary.exchanges().size() * 2) {
                return new ConversationStateStore.RestoreResult(original, 0, "partial_existing_replay");
            }
            if (!boundary.sourceFingerprint().equals(prefixFingerprint(restored, messages, start))) {
                return new ConversationStateStore.RestoreResult(original, 0, "changed_branch");
            }
        }
        return new ConversationStateStore.RestoreResult(restored, inserted, null);
    }

    public Optional<ConversationStateStore.ToolCall> realToolCall(String event, JsonNode data) {
        if (!"content_block_start".equals(event)) return Optional.empty();
        JsonNode block = data.path("content_block");
        if (!"tool_use".equals(block.path("type").asText())
                || TOOL_NAME.equals(block.path("name").asText())
                || block.path("id").asText().isBlank()) return Optional.empty();
        return Optional.of(new ConversationStateStore.ToolCall(
                block.path("id").asText(), block.path("name").asText()));
    }

    private ConversationStateStore.RequestContext unsupportedContext() {
        return new ConversationStateStore.RequestContext(
                ConversationStateStore.RequestKind.UNSUPPORTED, Set.of(), Set.of());
    }

    private String prefixFingerprint(ObjectNode request, ArrayNode messages, int endExclusive) {
        var prefix = request.deepCopy();
        var prefixMessages = json.createArrayNode();
        for (int index = 0; index < endExclusive; index++) {
            prefixMessages.add(messages.get(index).deepCopy());
        }
        prefix.set("messages", prefixMessages);
        return historyFingerprint(prefix);
    }

    private ArrayNode insertBefore(ObjectNode request, ArrayNode messages, int anchor, ArrayNode additions) {
        var rebuilt = json.createArrayNode();
        for (int index = 0; index < anchor; index++) rebuilt.add(messages.get(index).deepCopy());
        additions.forEach(message -> rebuilt.add(message.deepCopy()));
        for (int index = anchor; index < messages.size(); index++) rebuilt.add(messages.get(index).deepCopy());
        request.set("messages", rebuilt);
        return rebuilt;
    }

    private ArrayNode exchangeMessages(List<ConversationStateStore.Exchange> exchanges) {
        var messages = json.createArrayNode();
        for (ConversationStateStore.Exchange exchange : exchanges) {
            appendExchange(messages, exchange.providerOutput(),
                    exchange.workbookCallId(), exchange.workbookResult());
        }
        return messages;
    }

    private void appendExchange(ArrayNode messages, ArrayNode content, String callId, String resultText) {
        var assistant = json.createObjectNode().put("role", "assistant");
        assistant.set("content", content.deepCopy());
        messages.add(assistant);
        var result = json.createObjectNode()
                .put("type", "tool_result")
                .put("tool_use_id", callId)
                .put("content", resultText);
        var user = json.createObjectNode().put("role", "user");
        user.set("content", json.createArrayNode().add(result));
        messages.add(user);
    }

    private static int uniqueRealBatchMessage(
            ArrayNode messages, String insertionAnchorId, Set<String> realToolCallIds) {
        if (realToolCallIds.isEmpty() || !realToolCallIds.contains(insertionAnchorId)) return -1;
        var counts = new java.util.HashMap<String, Integer>();
        int found = -1;
        for (int index = 0; index < messages.size(); index++) {
            var ids = new LinkedHashSet<String>();
            String firstBatchId = null;
            for (JsonNode block : messages.get(index).path("content")) {
                if (!"tool_use".equals(block.path("type").asText())) continue;
                String id = block.path("id").asText();
                if (realToolCallIds.contains(id)) {
                    counts.merge(id, 1, Integer::sum);
                    ids.add(id);
                    if (firstBatchId == null) firstBatchId = id;
                }
            }
            if (!ids.containsAll(realToolCallIds)) continue;
            if (!insertionAnchorId.equals(firstBatchId) || found >= 0) return -1;
            found = index;
        }
        if (found < 0 || realToolCallIds.stream().anyMatch(id -> counts.getOrDefault(id, 0) != 1)) return -1;
        return found;
    }

    private static boolean hasFollowingResults(ArrayNode messages, int anchor, Set<String> realToolCallIds) {
        if (anchor + 1 >= messages.size()) return false;
        JsonNode resultMessage = messages.get(anchor + 1);
        if (!"user".equals(resultMessage.path("role").asText())
                || !resultMessage.path("content").isArray()) return false;
        var results = new LinkedHashSet<String>();
        for (JsonNode block : resultMessage.path("content")) {
            if (!"tool_result".equals(block.path("type").asText())) break;
            results.add(block.path("tool_use_id").asText());
        }
        if (!results.equals(realToolCallIds)) return false;
        for (String id : realToolCallIds) {
            int count = 0;
            for (JsonNode message : messages) {
                for (JsonNode block : message.path("content")) {
                    if ("tool_result".equals(block.path("type").asText())
                            && id.equals(block.path("tool_use_id").asText())) count++;
                }
            }
            if (count != 1) return false;
        }
        return true;
    }

    private static Set<Integer> workbookMessageIndices(ArrayNode messages,
            List<ConversationStateStore.Exchange> exchanges) {
        var ids = new LinkedHashSet<String>();
        exchanges.forEach(exchange -> ids.add(exchange.workbookCallId()));
        var indices = new LinkedHashSet<Integer>();
        for (int index = 0; index < messages.size(); index++) {
            for (JsonNode block : messages.get(index).path("content")) {
                if (("tool_use".equals(block.path("type").asText())
                        && ids.contains(block.path("id").asText()))
                        || ("tool_result".equals(block.path("type").asText())
                        && ids.contains(block.path("tool_use_id").asText()))) indices.add(index);
            }
        }
        return indices;
    }

    private static boolean matches(ArrayNode messages, int start, ArrayNode expected) {
        if (start + expected.size() > messages.size()) return false;
        for (int offset = 0; offset < expected.size(); offset++) {
            if (!messages.get(start + offset).equals(expected.get(offset))) return false;
        }
        return true;
    }

    private JsonNode canonical(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node == null ? json.nullNode() : node.deepCopy();
        }
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

    public ObjectNode forceWorkbook(ObjectNode original) {
        var forced = original.deepCopy();
        forced.set("tools", toolsWithWorkbook(original));
        forced.set("messages", copyOfMessages(original));
        forced.set("tool_choice", json.createObjectNode()
                .put("type", "tool")
                .put("name", TOOL_NAME)
                .put("disable_parallel_tool_use", true));
        forced.put("stream", true);
        applyHiddenEffort(forced);
        return forced;
    }

    /**
     * Raises depth on the hidden call only, when the deployment asks for it. {@code output_config} is
     * merged rather than replaced because the client owns every other key in it; only {@code effort} is
     * this proxy's to set, and only here. {@link #continueAfterWorkbook} copies the client's request, so
     * the visible turn keeps whatever depth the client chose.
     *
     * <p>Clamped rather than refused when the client disabled thinking. Opus 5 rejects
     * {@code thinking: {"type": "disabled"}} at {@code xhigh} and {@code max} with a 400, and Claude Code
     * really does send that combination -- its background title call disables thinking on every session.
     * Raising effort there would 400 the hidden call and lose the note, so the request is sent at
     * {@code high} instead: a shallower note beats no note, and capture failing open is the rule this
     * whole path is built on. The clamp is logged, because a note is only interpretable next to the depth
     * that actually produced it.
     */
    private void applyHiddenEffort(ObjectNode forced) {
        if (!hiddenEffort.overrides()) return;
        var effort = hiddenEffort;
        if (hiddenEffort.rejectedWithoutThinking()
                && "disabled".equals(forced.path("thinking").path("type").asText())) {
            effort = HiddenEffort.HIGH;
            Diagnostics.event("claude.effort.clamped",
                    "from", hiddenEffort.label(), "to", effort.label(), "reason", "thinking_disabled");
        }
        var outputConfig = forced.path("output_config") instanceof ObjectNode existing
                ? existing
                : forced.putObject("output_config");
        outputConfig.put("effort", effort.wireValue());
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

    /**
     * The hidden and continuation requests share this prefix byte for byte, so the continuation reads the
     * whole conversation back from the cache the hidden call just populated.
     */
    private ArrayNode copyOfMessages(ObjectNode original) {
        var messages = json.createArrayNode();
        if (original.path("messages").isArray()) {
            original.path("messages").forEach(message -> messages.add(message.deepCopy()));
        }
        return messages;
    }


    public HiddenStream newHiddenStream(Consumer<String> visibleDelta) {
        return new HiddenStream(json, visibleDelta);
    }

    /**
     * Anthropic's prompt-caching usage splits real input cost across three fields; {@code input_tokens}
     * alone excludes cache reads/writes, which is most of the prompt on a cache hit. Sum all three so the
     * total reflects what the call actually cost, not just its uncached portion.
     */
    public static long totalInputTokens(JsonNode usage) {
        return usage.path("input_tokens").asLong(0)
                + usage.path("cache_creation_input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0);
    }

    /**
     * Keeps workbook declared and leaves the client's own {@code tool_choice} in place. Withdrawing the
     * tool here would replay a {@code tool_use} for a tool the request no longer declares, which is the
     * shape that made the model read the exchange as an injected fake and refuse the task; Sonnet 5 cannot
     * withdraw it through the tool-change beta either.
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
        var messages = copyOfMessages(original);
        exchanges.forEach(hidden ->
                appendExchange(messages, hidden.content(), hidden.toolUseId(), WORKBOOK_RESULT));
        continuation.set("messages", messages);
        continuation.put("stream", true);
        return continuation;
    }

    /**
     * Holds the client-visible stream back until the model's first real content block proves the response
     * belongs to the client. Sonnet 5 cannot withdraw workbook through the tool-change beta, so the
     * continuation still declares it and the model is still free to call it again; without this gate that
     * repeat call would stream to a client that has no such tool.
     *
     * <p>The gate decides on the first block that is neither thinking nor redacted thinking. A model that
     * emits text first and only then calls workbook has already committed its response, which the plan
     * accepts: a tool call ends the turn, so workbook-after-text is not a shape the API produces.
     */
    public ResponseGate newResponseGate(Consumer<byte[]> release, Consumer<String> visibleDelta,
            int maximumBufferedBytes) {
        return new ResponseGate(json, release, visibleDelta, maximumBufferedBytes);
    }

    /**
     * The description carries the whole ask, and nothing is appended to the client's message history.
     * Earlier versions introduced the tool with a trailing system message; a live run flagged that block
     * as prompt injection while calling the declared tool itself legitimate. The wording was not the
     * problem — the position was. In an agentic client the last message is a user turn full of tool
     * results, so anything appended lands exactly where injected instructions arrive. A declared tool
     * cannot land there, which is why the ask lives here instead.
     */
    private ObjectNode workbookTool() {
        var properties = json.createObjectNode();
        properties.set("work", json.createObjectNode()
                .put("type", "string")
                .put("description", wording.workDescription()));
        var inputSchema = json.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        inputSchema.set("properties", properties);
        inputSchema.set("required", json.createArrayNode().add("work"));
        var tool = json.createObjectNode()
                .put("name", TOOL_NAME)
                .put("description", wording.description());
        tool.set("input_schema", inputSchema);
        return tool;
    }

    public record HiddenResult(ArrayNode content, String toolUseId, String work, long inputTokens, long outputTokens) { }

    public static final class ResponseGate {
        private final ObjectMapper json;
        private final Consumer<byte[]> release;
        private final HiddenStream hidden;
        private final SseParser parser;
        private final ByteArrayOutputStream buffered = new ByteArrayOutputStream();
        private final int maximumBufferedBytes;
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
            // Do not flush in the middle of parsing a chunk. message_stop can arrive in the same network
            // chunk as the content block that commits the gate, and continuity must be durable first.
            buffered.writeBytes(chunk);
            if (buffered.size() > maximumBufferedBytes) {
                throw new IllegalArgumentException("Claude response gate exceeded its local buffer limit");
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
            if ("message_stop".equals(event)) terminal = true;
            if (committed) return;
            JsonNode parsed;
            try {
                parsed = json.readTree(data);
            } catch (tools.jackson.core.JacksonException error) {
                throw new IllegalArgumentException("Claude returned invalid SSE JSON", error);
            }
            if (!privateWorkbook && "content_block_start".equals(event)) {
                JsonNode block = parsed.path("content_block");
                String type = block.path("type").asText();
                if ("tool_use".equals(type) && TOOL_NAME.equals(block.path("name").asText())) {
                    privateWorkbook = true;
                } else if (!"thinking".equals(type) && !"redacted_thinking".equals(type)) {
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
        private final Map<Integer, ObjectNode> blocks = new TreeMap<>();
        private final Map<String, Integer> deltaTypes = new TreeMap<>();
        private Integer workbookIndex;
        private String stopReason = "";
        private boolean completed;
        private long inputTokens;
        private long outputTokens;

        private HiddenStream(ObjectMapper json, Consumer<String> visibleDelta) {
            this.json = json;
            this.visibleDelta = visibleDelta;
        }

        public void accept(String event, JsonNode data) {
            int index = data.path("index").asInt(-1);
            switch (event) {
                case "content_block_start" -> startBlock(index, data.path("content_block"));
                case "content_block_delta" -> applyDelta(index, data.path("delta"));
                case "content_block_stop" -> closeWorkbookArguments(index);
                case "message_start" -> {
                    if (data.path("message").isObject()) {
                        inputTokens = ClaudeProtocol.totalInputTokens(data.path("message").path("usage"));
                    }
                }
                case "message_delta" -> {
                    if (data.path("usage").isObject()) {
                        outputTokens = data.path("usage").path("output_tokens").asLong(0);
                    }
                    stopReason = data.path("delta").path("stop_reason").asText(stopReason);
                }
                case "message_stop" -> completed = true;
                default -> { }
            }
        }

        private void startBlock(int index, JsonNode contentBlock) {
            if (!contentBlock.isObject()) return;
            var block = (ObjectNode) contentBlock.deepCopy();
            blocks.put(index, block);
            if ("tool_use".equals(block.path("type").asText())
                    && TOOL_NAME.equals(block.path("name").asText())) {
                workbookIndex = index;
            }
        }

        /**
         * Workbook arguments are decoded incrementally so the dashboard streams the note as it is written;
         * every other block's delta is folded back into the block instead, because the continuation replays
         * the assistant turn verbatim and a dropped thinking signature invalidates it.
         */
        private void applyDelta(int index, JsonNode delta) {
            if (Diagnostics.enabled()) {
                deltaTypes.merge((isWorkbook(index) ? "workbook." : "other.")
                        + delta.path("type").asText("absent"), 1, Integer::sum);
            }
            if (isWorkbook(index) && "input_json_delta".equals(delta.path("type").asText())) {
                String partial = delta.path("partial_json").asText();
                rawArguments.append(partial);
                String visible = decoder.feed(partial.getBytes(StandardCharsets.UTF_8));
                if (!visible.isEmpty()) visibleDelta.accept(visible);
            } else if (blocks.containsKey(index)) {
                applyPreservedDelta(blocks.get(index), delta);
            }
        }

        private void closeWorkbookArguments(int index) {
            if (!isWorkbook(index)) return;
            try {
                blocks.get(index).set("input", json.readTree(rawArguments.toString()));
            } catch (tools.jackson.core.JacksonException error) {
                throw new IllegalArgumentException("Claude returned invalid workbook arguments", error);
            }
        }

        private boolean isWorkbook(int index) {
            return workbookIndex != null && index == workbookIndex.intValue();
        }

        private List<String> blockTypes() {
            return blocks.values().stream().map(block -> block.path("type").asText("absent")).toList();
        }

        private static void applyPreservedDelta(ObjectNode block, JsonNode delta) {
            switch (delta.path("type").asText()) {
                case "thinking_delta" -> append(block, "thinking", delta.path("thinking").asText());
                case "signature_delta" -> append(block, "signature", delta.path("signature").asText());
                case "text_delta" -> append(block, "text", delta.path("text").asText());
                default -> throw new IllegalArgumentException(
                        "Claude returned an unsupported content-block delta");
            }
        }

        private static void append(ObjectNode block, String field, String suffix) {
            block.put(field, block.path(field).asText() + suffix);
        }

        public HiddenResult finish() {
            // Every field here is a shape, never content: the note itself is what this whole proxy
            // exists to keep out of logs.
            Diagnostics.event("claude.hidden.finish",
                    "completed", completed,
                    "stopReason", stopReason,
                    "blockTypes", blockTypes(),
                    "workbookIndex", workbookIndex == null ? -1 : workbookIndex,
                    "rawArgumentChars", rawArguments.length(),
                    "deltaTypes", Map.copyOf(deltaTypes),
                    "outputTokens", outputTokens);
            if ("refusal".equals(stopReason)) {
                throw new CaptureRefusedException("Claude refused the workbook call");
            }
            if (!completed || workbookIndex == null || !blocks.containsKey(workbookIndex)) {
                throw new IllegalArgumentException("Claude hidden response did not complete a workbook call");
            }
            var workbook = blocks.get(workbookIndex);
            String visibleWork = decoder.finish();
            String finalWork = workbook.path("input").path("work").asText();
            if (!visibleWork.equals(finalWork)) {
                throw new IllegalArgumentException("Claude visible workbook did not match final work");
            }
            if (workbook.path("id").asText().isBlank()) {
                throw new IllegalArgumentException("Claude workbook call had no tool-use ID");
            }
            var content = json.createArrayNode();
            blocks.values().forEach(content::add);
            Diagnostics.event("claude.hidden.captured", "workChars", visibleWork.length(),
                    "blocks", blocks.size(), "outputTokens", outputTokens);
            return new HiddenResult(content, workbook.path("id").asText(), visibleWork, inputTokens, outputTokens);
        }
    }
}
