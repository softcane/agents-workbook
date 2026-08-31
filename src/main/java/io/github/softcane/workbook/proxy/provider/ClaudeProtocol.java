package io.github.softcane.workbook.proxy.provider;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.softcane.workbook.provider.IncrementalWorkDecoder;
import io.github.softcane.workbook.proxy.Diagnostics;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /**
     * Whether this request can carry a hidden workbook exchange at all. Manual extended thinking
     * ({@code thinking.type = "enabled"}) is refused, and a history ending on an assistant prefill is
     * refused too: a forced tool call would abandon the half-written turn the client asked the model to
     * finish. Both are forwarded untouched instead.
     *
     * <p>The thinking guard is not about {@code tool_choice}, whatever this comment used to say. Forced
     * tool use is incompatible with manual extended thinking specifically; adaptive thinking supports it,
     * which is why capture works today against a client that sends {@code "adaptive"} on every request.
     * The guard survives because Claude 4.7 and later reject {@code "enabled"} outright -- a client
     * sending it has a request the provider would refuse before this proxy mattered.
     */
    public boolean supportsCapture(ObjectNode original) {
        if ("enabled".equals(original.path("thinking").path("type").asText())) return false;
        return original.path("messages") instanceof ArrayNode messages && endsOnUserTurn(messages);
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

    /**
     * A forced tool call has to answer a user turn rather than continue an assistant prefill. Trailing
     * system messages are the client's own and are skipped: they do not change whose turn it is.
     */
    private static boolean endsOnUserTurn(ArrayNode messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            String role = messages.get(index).path("role").asText();
            if ("system".equals(role)) continue;
            return "user".equals(role);
        }
        return false;
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
        exchanges.forEach(hidden -> {
            var assistant = json.createObjectNode().put("role", "assistant");
            assistant.set("content", hidden.content().deepCopy());
            messages.add(assistant);
            var resultBlock = json.createObjectNode()
                    .put("type", "tool_result")
                    .put("tool_use_id", hidden.toolUseId())
                    .put("content", WORKBOOK_RESULT);
            var user = json.createObjectNode().put("role", "user");
            user.set("content", json.createArrayNode().add(resultBlock));
            messages.add(user);
        });
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
                throw new IllegalArgumentException("Claude response gate exceeded its local buffer limit");
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
                default -> { }
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
