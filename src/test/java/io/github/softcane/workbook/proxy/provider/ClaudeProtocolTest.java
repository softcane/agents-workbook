package io.github.softcane.workbook.proxy.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import io.github.softcane.workbook.proxy.ConversationStateStore;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaudeProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void forcesWorkbookAndBuildsNativeToolResultContinuation() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                 "tools":[{"name":"real_tool","input_schema":{"type":"object"}}],
                 "tool_choice":{"type":"auto"},"unknown":"preserve-me"}
                """);
        var visible = new ArrayList<String>();
        var protocol = new ClaudeProtocol(json);
        var forced = protocol.forceWorkbook(original);

        assertThat(forced.path("tool_choice").path("name").asText()).isEqualTo(ClaudeProtocol.TOOL_NAME);
        assertThat(forced.path("tool_choice").path("disable_parallel_tool_use").asBoolean()).isTrue();
        assertThat(forced.path("tools").get(0).path("input_schema").path("additionalProperties").asBoolean()).isFalse();

        var stream = protocol.newHiddenStream(visible::add);
        stream.accept("content_block_start", json.readTree("""
                {"index":0,"content_block":{"type":"thinking","thinking":"","signature":"","unknown":"keep"}}
                """));
        stream.accept("content_block_delta", json.readTree("""
                {"index":0,"delta":{"type":"thinking_delta","thinking":"provider-only"}}
                """));
        stream.accept("content_block_delta", json.readTree("""
                {"index":0,"delta":{"type":"signature_delta","signature":"signed"}}
                """));
        stream.accept("content_block_stop", json.readTree("{" + "\"index\":0}"));
        stream.accept("content_block_start", json.readTree("""
                {"index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"%s","input":{}}}
                """.formatted(ClaudeProtocol.TOOL_NAME)));
        stream.accept("content_block_delta", json.readTree("""
                {"index":1,"delta":{"type":"input_json_delta","partial_json":"{\\\"work\\\":\\\"first "}}
                """));
        stream.accept("content_block_delta", json.readTree("""
                {"index":1,"delta":{"type":"input_json_delta","partial_json":"second\\\"}"}}
                """));
        stream.accept("content_block_stop", json.readTree("{" + "\"index\":1}"));
        stream.accept("message_stop", json.createObjectNode());

        var continuation = protocol.continueAfterWorkbook(original, stream.finish());
        assertThat(String.join("", visible)).isEqualTo("first second");
        assertThat(continuation.path("unknown").asText()).isEqualTo("preserve-me");
        assertThat(continuation.path("messages").get(1).path("content").get(0).path("thinking").asText())
                .isEqualTo("provider-only");
        assertThat(continuation.path("messages").get(1).path("content").get(0).path("signature").asText())
                .isEqualTo("signed");
        assertThat(continuation.path("messages").get(1).path("content").get(0).path("unknown").asText())
                .isEqualTo("keep");
        assertThat(continuation.path("messages").get(1).path("content").get(1).path("input").path("work").asText())
                .isEqualTo("first second");
        assertThat(continuation.path("messages").get(2).path("content").get(0).path("tool_use_id").asText())
                .isEqualTo("toolu_1");
    }

    @Test
    void declaresTheSameToolListOnBothHiddenAndContinuationRequests() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                 "tools":[{"name":"Bash","input_schema":{"type":"object"}},
                          {"name":"Read","input_schema":{"type":"object"}}],
                 "tool_choice":{"type":"auto"}}
                """);
        var protocol = new ClaudeProtocol(json);

        var forced = protocol.forceWorkbook(original);
        var continuation = protocol.continueAfterWorkbook(original, hiddenResult(protocol));

        assertThat(toolNames(forced)).containsExactly(ClaudeProtocol.TOOL_NAME, "Bash", "Read");
        assertThat(toolNames(continuation)).isEqualTo(toolNames(forced));
        assertThat(continuation.path("tools")).isEqualTo(forced.path("tools"));
        // The client's own choice comes back, so the visible turn is free to answer or call a real tool.
        assertThat(continuation.path("tool_choice")).isEqualTo(original.path("tool_choice"));
    }

    @Test
    void leavesTheClientsMessageHistoryExactlyAsItArrived() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","system":[{"type":"text","text":"attribution"},{"type":"text","text":"rules"}],
                 "messages":[{"role":"user","content":"first"},
                             {"role":"assistant","content":[{"type":"tool_use","id":"toolu_real","name":"Bash",
                                                             "input":{}}]},
                             {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_real",
                                                        "content":"ok"}]}],
                 "tools":[{"name":"Bash","input_schema":{"type":"object"}}]}
                """);
        var protocol = new ClaudeProtocol(json);

        var forced = protocol.forceWorkbook(original);
        var continuation = protocol.continueAfterWorkbook(original, hiddenResult(protocol));

        // Forwarded byte-identical: Claude Code's attribution block is stripped upstream by position.
        assertThat(forced.path("system")).isEqualTo(original.path("system"));
        assertThat(continuation.path("system")).isEqualTo(original.path("system"));

        // Nothing is appended: the hidden request is the client's own history, and the continuation adds
        // only the workbook exchange itself. The trailing turn stays the client's tool result, which is
        // where injected instructions would otherwise land.
        assertThat(forced.path("messages")).isEqualTo(original.path("messages"));
        assertThat(roles(forced)).containsExactly("user", "assistant", "user");
        assertThat(roles(continuation)).containsExactly("user", "assistant", "user", "assistant", "user");
        var toolResult = continuation.path("messages").get(4).path("content").get(0);
        assertThat(toolResult.path("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.path("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(toolResult.path("content").asText()).isEqualTo(ClaudeProtocol.WORKBOOK_RESULT);
    }

    @Test
    void classifiesOnlyTheActiveClaudeBoundaryAndKeepsHistoricalResultsSeparate() throws Exception {
        var protocol = new ClaudeProtocol(json);
        assertThat(protocol.requestContext((ObjectNode) json.readTree("""
                {"messages":[{"role":"user","content":"hi"}],"thinking":{"type":"adaptive"}}
                """)).kind()).isEqualTo(ConversationStateStore.RequestKind.OPENING);
        assertThat(protocol.requestContext((ObjectNode) json.readTree("""
                {"messages":[{"role":"user","content":"hi"}],
                 "thinking":{"type":"enabled","budget_tokens":2048}}
                """)).kind()).isEqualTo(ConversationStateStore.RequestKind.UNSUPPORTED);
        assertThat(protocol.requestContext((ObjectNode) json.readTree("""
                {"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":"partial"}]}
                """)).kind()).isEqualTo(ConversationStateStore.RequestKind.UNSUPPORTED);
        assertThat(protocol.requestContext((ObjectNode) json.readTree("""
                {"messages":[]}
                """)).kind()).isEqualTo(ConversationStateStore.RequestKind.UNSUPPORTED);

        var results = protocol.requestContext((ObjectNode) json.readTree("""
                {"messages":[
                 {"role":"user","content":[{"type":"tool_result","tool_use_id":"old","content":"old"}]},
                 {"role":"assistant","content":[{"type":"tool_use","id":"new-a"},{"type":"tool_use","id":"new-b"}]},
                 {"role":"user","content":[
                   {"type":"tool_result","tool_use_id":"new-a","content":"a"},
                   {"type":"tool_result","tool_use_id":"new-b","content":"b"},
                   {"type":"text","text":"continue"}]},
                 {"role":"system","content":"client trailer"}]}
                """));
        assertThat(results.kind()).isEqualTo(ConversationStateStore.RequestKind.TOOL_RESULTS);
        assertThat(results.trailingResultIds()).containsExactlyInAnyOrder("new-a", "new-b");
        assertThat(results.historyResultIds()).containsExactlyInAnyOrder("old", "new-a", "new-b");

        var newText = protocol.requestContext((ObjectNode) json.readTree("""
                {"messages":[
                 {"role":"user","content":[{"type":"tool_result","tool_use_id":"old","content":"old"}]},
                 {"role":"user","content":"a new request"}]}
                """));
        assertThat(newText.kind()).isEqualTo(ConversationStateStore.RequestKind.OPENING);
        assertThat(newText.trailingResultIds()).isEmpty();
        assertThat(newText.historyResultIds()).containsExactly("old");
    }

    @Test
    void fingerprintsOnlyCanonicalClaudeHistory() throws Exception {
        var protocol = new ClaudeProtocol(json);
        var first = (ObjectNode) json.readTree("""
                {"model":"one","tools":[{"name":"secret"}],
                 "messages":[{"role":"user","content":[{"text":"hi","type":"text"}]}]}
                """);
        var sameHistory = (ObjectNode) json.readTree("""
                {"model":"two","messages":[{"content":[{"type":"text","text":"hi"}],"role":"user"}],
                 "tool_choice":{"type":"none"}}
                """);
        var changed = (ObjectNode) json.readTree("""
                {"messages":[{"role":"user","content":[{"type":"text","text":"bye"}]}]}
                """);
        assertThat(protocol.historyFingerprint(first)).isEqualTo(protocol.historyFingerprint(sameHistory));
        assertThat(protocol.historyFingerprint(first)).isNotEqualTo(protocol.historyFingerprint(changed));
    }


    @Test
    void restoresCompleteClaudeContentBeforeItsRealBatchWithoutChangingOpaqueBlocks() throws Exception {
        var protocol = new ClaudeProtocol(json);
        var source = (ObjectNode) json.readTree("""
                {"messages":[{"role":"user","content":"start"}]}
                """);
        var providerOutput = (tools.jackson.databind.node.ArrayNode) json.readTree("""
                [{"type":"thinking","thinking":"opaque","signature":"signed","cache_control":{"type":"ephemeral"}},
                 {"type":"redacted_thinking","data":"cipher","unknown":"keep"},
                 {"type":"tool_use","id":"toolu_wb","name":"workbook","input":{"work":"note"}}]
                """);
        var boundary = new ConversationStateStore.Boundary(1, "raw", protocol.historyFingerprint(source),
                List.of(new ConversationStateStore.Exchange(
                        providerOutput, "toolu_wb", ClaudeProtocol.WORKBOOK_RESULT)),
                "toolu_real_a", Set.of("toolu_real_a", "toolu_real_b"), 0);
        var next = (ObjectNode) json.readTree("""
                {"messages":[
                 {"role":"user","content":"start"},
                 {"role":"assistant","content":[
                   {"type":"thinking","thinking":"visible","signature":"visible-signature"},
                   {"type":"tool_use","id":"toolu_real_a","name":"Bash","input":{"command":"a"}},
                   {"type":"tool_use","id":"toolu_real_b","name":"Bash","input":{"command":"b"}}]},
                 {"role":"user","content":[
                   {"type":"tool_result","tool_use_id":"toolu_real_a","content":"a"},
                   {"type":"tool_result","tool_use_id":"toolu_real_b","content":"b"}]}]}
                """);
        JsonNode realAssistant = next.path("messages").get(1).deepCopy();
        JsonNode realResults = next.path("messages").get(2).deepCopy();

        var restored = protocol.restoreWorkbookHistory(next, List.of(boundary));

        assertThat(restored.skipReason()).isNull();
        assertThat(restored.insertedBoundaryCount()).isEqualTo(1);
        assertThat(restored.request().path("messages").get(1).path("content")).isEqualTo(providerOutput);
        assertThat(restored.request().path("messages").get(2).path("content").get(0)
                .path("tool_use_id").asText()).isEqualTo("toolu_wb");
        assertThat(restored.request().path("messages").get(3)).isEqualTo(realAssistant);
        assertThat(restored.request().path("messages").get(4)).isEqualTo(realResults);

        var alreadyPresent = protocol.restoreWorkbookHistory(restored.request(), List.of(boundary));
        assertThat(alreadyPresent.skipReason()).isNull();
        assertThat(alreadyPresent.insertedBoundaryCount()).isZero();
        assertThat(alreadyPresent.request()).isEqualTo(restored.request());
    }

    @Test
    void rejectsPartialChangedAndAmbiguousClaudeReplayWithoutMutatingTheRequest() throws Exception {
        var protocol = new ClaudeProtocol(json);
        var source = (ObjectNode) json.readTree("""
                {"messages":[{"role":"user","content":"start"}]}
                """);
        var output = (tools.jackson.databind.node.ArrayNode) json.readTree("""
                [{"type":"tool_use","id":"toolu_wb","name":"workbook","input":{"work":"note"}}]
                """);
        var boundary = new ConversationStateStore.Boundary(1, "raw", protocol.historyFingerprint(source),
                List.of(new ConversationStateStore.Exchange(output, "toolu_wb", ClaudeProtocol.WORKBOOK_RESULT)),
                "toolu_real", Set.of("toolu_real"), 0);
        var request = (ObjectNode) json.readTree("""
                {"messages":[
                 {"role":"user","content":"start"},
                 {"role":"assistant","content":[
                   {"type":"tool_use","id":"toolu_real","name":"Bash","input":{}}]},
                 {"role":"user","content":[
                   {"type":"tool_result","tool_use_id":"toolu_real","content":"ok"}]}]}
                """);

        var partial = request.deepCopy();
        var resultOnly = json.createObjectNode().put("role", "user");
        resultOnly.set("content", json.createArrayNode().add(json.createObjectNode()
                .put("type", "tool_result").put("tool_use_id", "toolu_wb").put("content", "changed")));
        ((tools.jackson.databind.node.ArrayNode) partial.path("messages")).insert(1, resultOnly);
        JsonNode partialBefore = partial.deepCopy();
        assertThat(protocol.restoreWorkbookHistory(partial, List.of(boundary)).skipReason())
                .isEqualTo("partial_existing_replay");
        assertThat(partial).isEqualTo(partialBefore);

        var missing = request.deepCopy();
        ((ObjectNode) missing.path("messages").get(1).path("content").get(0)).put("id", "other");
        JsonNode missingBefore = missing.deepCopy();
        assertThat(protocol.restoreWorkbookHistory(missing, List.of(boundary)).skipReason())
                .isEqualTo("ambiguous_anchor");
        assertThat(missing).isEqualTo(missingBefore);

        var duplicate = request.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) duplicate.path("messages"))
                .insert(2, duplicate.path("messages").get(1).deepCopy());
        assertThat(protocol.restoreWorkbookHistory(duplicate, List.of(boundary)).skipReason())
                .isEqualTo("ambiguous_anchor");

        var changed = request.deepCopy();
        ((ObjectNode) changed.path("messages").get(0)).put("content", "changed");
        JsonNode changedBefore = changed.deepCopy();
        assertThat(protocol.restoreWorkbookHistory(changed, List.of(boundary)).skipReason())
                .isEqualTo("changed_branch");
        assertThat(changed).isEqualTo(changedBefore);
    }

    @Test
    void observesOnlyRealClaudeToolIdsAndRefusesLossyUnknownDeltas() throws Exception {
        var protocol = new ClaudeProtocol(json);
        var workbook = json.readTree("""
                {"content_block":{"type":"tool_use","id":"toolu_wb","name":"workbook",
                 "input":{"work":"secret"}}}
                """);
        assertThat(protocol.realToolCall("content_block_start", workbook)).isEmpty();
        var real = json.readTree("""
                {"content_block":{"type":"tool_use","id":"toolu_real","name":"Bash",
                 "input":{"command":"secret"}}}
                """);
        assertThat(protocol.realToolCall("content_block_start", real))
                .contains(new ConversationStateStore.ToolCall("toolu_real", "Bash"));

        var stream = protocol.newHiddenStream(delta -> { });
        stream.accept("content_block_start", json.readTree("""
                {"index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}
                """));
        assertThatThrownBy(() -> stream.accept("content_block_delta", json.readTree("""
                {"index":0,"delta":{"type":"future_opaque_delta","payload":"cannot reconstruct"}}
                """))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void keepsEveryRepeatedWorkbookExchangeLegallyPlaced() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                 "tools":[{"name":"Bash","input_schema":{"type":"object"}}]}
                """);
        var protocol = new ClaudeProtocol(json);

        var continuation = protocol.continueAfterWorkbook(original,
                List.of(hiddenResult(protocol), hiddenResult(protocol)));

        // Each exchange is one assistant turn and one tool_result turn, with nothing wedged between them.
        assertThat(roles(continuation))
                .containsExactly("user", "assistant", "user", "assistant", "user");
        assertThat(toolNames(continuation)).containsExactly(ClaudeProtocol.TOOL_NAME, "Bash");
        assertThat(continuation.path("messages").get(2).path("content").get(0).path("tool_use_id").asText())
                .isEqualTo("toolu_1");
        assertThat(continuation.path("messages").get(4).path("content").get(0).path("tool_use_id").asText())
                .isEqualTo("toolu_1");
    }

    /**
     * Live runs refused the exchange and named it prompt injection, citing three things in turn: a tool
     * called scratchpad, which collides with the "Scratchpad Directory" in Claude Code's own system prompt;
     * a notice advertising a tool "the user's client does not know about"; and finally, once the wording
     * was clean, the notice's position — a trailing block after the client's tool results, which the same
     * run distinguished from the declared tool it called legitimate. All three are wire text the model
     * reads, so all three are asserted here rather than left to a live run to catch.
     */
    @Test
    void putsTheWholeAskInTheToolDeclarationAndNothingInTheHistory() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"first"},
                                                   {"role":"assistant","content":[{"type":"tool_use",
                                                     "id":"toolu_real","name":"Bash","input":{}}]},
                                                   {"role":"user","content":[{"type":"tool_result",
                                                     "tool_use_id":"toolu_real","content":"ok"}]}],
                 "tools":[{"name":"Bash","input_schema":{"type":"object"}}]}
                """);

        assertThat(ClaudeProtocol.TOOL_NAME).isNotEqualTo("scratchpad");
        // Every style ships, so every style has to clear the bar, not just whichever one is deployed.
        for (var style : NoteStyle.values()) {
            var forced = new ClaudeProtocol(json, style).forceWorkbook(original);
            var description = forced.path("tools").get(0).path("description").asText();
            var workDescription = forced.path("tools").get(0)
                    .path("input_schema").path("properties").path("work").path("description").asText();

            // Nothing follows the client's tool results, so no free-floating text can read as injected.
            assertThat(forced.path("messages")).isEqualTo(original.path("messages"));
            assertThat(description).isNotBlank();
            assertThat(workDescription).isNotBlank();
            assertThat(description + workDescription + ClaudeProtocol.WORKBOOK_RESULT)
                    .doesNotContain("does not know")
                    .doesNotContain("private")
                    .doesNotContain("hidden")
                    .doesNotContain("secret trace");
        }
    }

    @Test
    void inheritLeavesTheClientsDepthSettingUntouched() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                 "thinking":{"type":"adaptive"},"output_config":{"effort":"low"}}
                """);

        var forced = new ClaudeProtocol(json, NoteStyle.REASONING, HiddenEffort.INHERIT).forceWorkbook(original);

        // The historical behaviour, and the default: this proxy adds no depth of its own.
        assertThat(forced.path("output_config")).isEqualTo(original.path("output_config"));
        assertThat(forced.path("thinking")).isEqualTo(original.path("thinking"));
    }

    @Test
    void hiddenEffortRaisesTheHiddenCallAndLeavesTheVisibleTurnAlone() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                 "thinking":{"type":"adaptive"},"output_config":{"effort":"low"}}
                """);
        var protocol = new ClaudeProtocol(json, NoteStyle.REASONING, HiddenEffort.MAX);

        var forced = protocol.forceWorkbook(original);
        var continuation = protocol.continueAfterWorkbook(original, hiddenResult(protocol));

        assertThat(forced.path("output_config").path("effort").asText()).isEqualTo("max");
        // Turning the user's real answer down is the half of the upstream effort split this project
        // deliberately does not have, so the continuation keeps whatever the client asked for.
        assertThat(continuation.path("output_config").path("effort").asText()).isEqualTo("low");
        // A forced tool_choice sits beside adaptive thinking here; only manual thinking rejects that.
        assertThat(forced.path("thinking").path("type").asText()).isEqualTo("adaptive");
        assertThat(forced.path("tool_choice").path("name").asText()).isEqualTo(ClaudeProtocol.TOOL_NAME);
    }

    @Test
    void hiddenEffortMergesIntoOutputConfigRatherThanReplacingIt() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                 "output_config":{"effort":"low","unknown":"preserve-me"}}
                """);

        var forced = new ClaudeProtocol(json, NoteStyle.REASONING, HiddenEffort.XHIGH).forceWorkbook(original);

        assertThat(forced.path("output_config").path("effort").asText()).isEqualTo("xhigh");
        assertThat(forced.path("output_config").path("unknown").asText()).isEqualTo("preserve-me");
    }

    @Test
    void hiddenEffortCreatesOutputConfigWhenTheClientSentNone() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}]}
                """);

        var forced = new ClaudeProtocol(json, NoteStyle.REASONING, HiddenEffort.HIGH).forceWorkbook(original);

        assertThat(forced.path("output_config").path("effort").asText()).isEqualTo("high");
    }

    /**
     * Claude Code's background title call disables thinking, and Opus 5 rejects that beside xhigh or max.
     * Proven live: the call returned 400 and the note was lost until the clamp existed.
     */
    @Test
    void clampsToHighWhenTheClientDisabledThinking() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                 "thinking":{"type":"disabled"}}
                """);

        for (var level : new HiddenEffort[] {HiddenEffort.XHIGH, HiddenEffort.MAX}) {
            var forced = new ClaudeProtocol(json, NoteStyle.REASONING, level).forceWorkbook(original);
            assertThat(forced.path("output_config").path("effort").asText()).isEqualTo("high");
        }

        // Only xhigh and max are rejected beside disabled thinking, so nothing below them is clamped.
        var medium = new ClaudeProtocol(json, NoteStyle.REASONING, HiddenEffort.MEDIUM).forceWorkbook(original);
        assertThat(medium.path("output_config").path("effort").asText()).isEqualTo("medium");
    }

    @Test
    void doesNotClampWhenThinkingIsAdaptive() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}],
                 "thinking":{"type":"adaptive"}}
                """);

        var forced = new ClaudeProtocol(json, NoteStyle.REASONING, HiddenEffort.MAX).forceWorkbook(original);

        assertThat(forced.path("output_config").path("effort").asText()).isEqualTo("max");
    }

    /**
     * The note is replayed to the model that writes the visible answer, so a workbook call is not only a
     * window for the user -- it is context the model itself reads back. Anything that strips the note
     * here would quietly turn the exchange into an empty round trip.
     */
    @Test
    void theNoteIsReplayedToTheModelInTheContinuation() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}]}
                """);
        var protocol = new ClaudeProtocol(json);

        var continuation = protocol.continueAfterWorkbook(original, hiddenResult(protocol));

        var replayed = continuation.path("messages").get(1);
        assertThat(replayed.path("role").asText()).isEqualTo("assistant");
        var toolUse = replayed.path("content").get(0);
        assertThat(toolUse.path("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.path("name").asText()).isEqualTo(ClaudeProtocol.TOOL_NAME);
        assertThat(toolUse.path("input").path("work").asText()).isEqualTo("note");
    }

    @Test
    void gatePassesMalformedBytesThroughAfterClaudeResponseIsCommitted() {
        var protocol = new ClaudeProtocol(json);
        var released = new java.io.ByteArrayOutputStream();
        var gate = protocol.newResponseGate(released::writeBytes, delta -> { }, 1 << 20);
        byte[] committing = """
                event: content_block_start
                data: {"content_block":{"type":"text","text":""}}

                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] malformed = {(byte) 0xc3, 0x28, '\n'};

        gate.accept(committing);
        gate.accept(malformed);

        byte[] expected = java.nio.ByteBuffer.allocate(committing.length + malformed.length)
                .put(committing).put(malformed).array();
        assertThat(released.toByteArray()).containsExactly(expected);
    }

    private ClaudeProtocol.HiddenResult hiddenResult(ClaudeProtocol protocol) throws Exception {
        var stream = protocol.newHiddenStream(delta -> { });
        stream.accept("content_block_start", json.readTree("""
                {"index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"%s","input":{}}}
                """.formatted(ClaudeProtocol.TOOL_NAME)));
        stream.accept("content_block_delta", json.readTree("""
                {"index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"work\\":\\"note\\"}"}}
                """));
        stream.accept("content_block_stop", json.readTree("{" + "\"index\":0}"));
        stream.accept("message_stop", json.createObjectNode());
        return stream.finish();
    }

    private List<String> toolNames(ObjectNode request) {
        var names = new ArrayList<String>();
        request.path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private List<String> roles(ObjectNode request) {
        var roles = new ArrayList<String>();
        request.path("messages").forEach(message -> roles.add(message.path("role").asText()));
        return roles;
    }
}
