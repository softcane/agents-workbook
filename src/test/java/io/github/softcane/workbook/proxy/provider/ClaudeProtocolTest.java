package io.github.softcane.workbook.proxy.provider;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
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
    void reportsCaptureUnsupportedForManualThinkingAndForHistoriesThatDoNotEndOnAUserTurn() throws Exception {
        var protocol = new ClaudeProtocol(json);

        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hi"}],
                 "thinking":{"type":"adaptive"}}
                """))).isTrue();
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hi"}]}
                """))).isTrue();
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hi"}],
                 "thinking":{"type":"enabled","budget_tokens":2048}}
                """))).isFalse();
        // An assistant prefill must not be split from its continuation by a forced tool call.
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hi"},
                                                   {"role":"assistant","content":"partial"}]}
                """))).isFalse();
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[]}
                """))).isFalse();
        // A client's own trailing system message does not change whose turn it is.
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hi"},
                                                   {"role":"system","content":"client note"}]}
                """))).isTrue();
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
