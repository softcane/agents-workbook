package io.github.softcane.workbook.proxy.provider;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Codex's half of the semantic parity contract. The Claude half lives in {@link ClaudeProtocolTest},
 * which asserts the same properties on the request shapes that actually ship.
 */
class WorkbookSemanticParityTest {
    private static final String GENERIC_DESCRIPTION =
            "Write concise working notes for the local trace before acting or answering. Do not include secrets.";
    private static final String CONTINUATION_CUE = "Continue thinking, call a tool, or respond to the user.";

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void codexGuidanceAndPreservedRequestFields() throws Exception {
        var original = (ObjectNode) json.readTree("""
                {"model":"gpt-test","input":[{"role":"user","content":"hello"}],
                 "reasoning":{"effort":"medium"},"max_output_tokens":512,"metadata":{"trace":"abc"},
                 "store":true,"tools":[{"type":"function","name":"real_tool","parameters":{"type":"object"}}],
                 "tool_choice":"auto","parallel_tool_calls":true,"unknown":"preserve-me"}
                """);
        var protocol = new CodexProtocol(json);

        var forced = protocol.forceWorkbook(original);
        var workbookTool = forced.path("tools").get(0);
        assertThat(workbookTool.path("description").asText())
                .isNotBlank().isNotEqualTo(GENERIC_DESCRIPTION);
        assertThat(workbookTool.path("parameters").path("properties").path("work").path("description").asText())
                .isNotBlank();
        assertThat(forced.path("reasoning").path("effort").asText()).isEqualTo("medium");
        assertThat(forced.path("max_output_tokens").asInt()).isEqualTo(512);
        assertThat(forced.path("metadata").path("trace").asText()).isEqualTo("abc");
        assertThat(forced.path("store").asBoolean()).isTrue();
        assertThat(forced.path("unknown").asText()).isEqualTo("preserve-me");

        var stream = protocol.newHiddenStream(delta -> { });
        stream.accept("response.function_call_arguments.delta", json.readTree("""
                {"delta":"{\\"work\\":\\"note\\"}"}
                """));
        stream.accept("response.output_item.done", json.readTree("""
                {"output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"workbook",
                 "arguments":"{\\"work\\":\\"note\\"}"}}
                """));
        stream.accept("response.completed", json.readTree("""
                {"response":{"id":"resp_1","output":[]}}
                """));
        var hidden = stream.finish();

        var continuation = protocol.continueAfterWorkbook(original, hidden);
        assertThat(continuation.path("reasoning").path("effort").asText()).isEqualTo("medium");
        assertThat(continuation.path("max_output_tokens").asInt()).isEqualTo(512);
        assertThat(continuation.path("metadata").path("trace").asText()).isEqualTo("abc");
        assertThat(continuation.path("unknown").asText()).isEqualTo("preserve-me");
        var workbookResult = continuation.path("input").get(continuation.path("input").size() - 1);
        assertThat(workbookResult.path("output").asText()).contains(CONTINUATION_CUE);
    }

    /**
     * One setting switches both providers, but they must never converge on one wording: Claude Code has
     * refused text Codex accepts, and a shared sentence would make a style comparison move two variables.
     * The envelopes differ for a harder reason — Claude speaks the native Anthropic schema, Codex the
     * OpenAI function schema — so neither may drift into the other's shape.
     */
    @Test
    void eachStyleGivesTheTwoProvidersDifferentSpecs() throws Exception {
        var claudeRequest = (ObjectNode) json.readTree("""
                {"model":"claude-test","messages":[{"role":"user","content":"hello"}]}
                """);
        var codexRequest = (ObjectNode) json.readTree("""
                {"model":"gpt-test","input":[{"role":"user","content":"hello"}]}
                """);

        for (var style : NoteStyle.values()) {
            var claudeTool = new ClaudeProtocol(json, style).forceWorkbook(claudeRequest).path("tools").get(0);
            var codexTool = new CodexProtocol(json, style).forceWorkbook(codexRequest).path("tools").get(0);

            assertThat(claudeTool.path("name").asText()).isEqualTo(ClaudeProtocol.TOOL_NAME);
            assertThat(codexTool.path("name").asText()).isEqualTo("workbook");
            assertThat(codexTool.path("description").asText())
                    .isNotBlank()
                    .isNotEqualTo(GENERIC_DESCRIPTION)
                    .isNotEqualTo(claudeTool.path("description").asText());

            var claudeWork = claudeTool.path("input_schema").path("properties").path("work").path("description");
            var codexWork = codexTool.path("parameters").path("properties").path("work").path("description");
            assertThat(codexWork.asText()).isNotBlank().isNotEqualTo(claudeWork.asText());

            assertThat(codexTool.path("type").asText()).isEqualTo("function");
            assertThat(codexTool.path("strict").asBoolean()).isFalse();
            assertThat(claudeTool.has("type")).isFalse();
            assertThat(claudeTool.has("strict")).isFalse();
            assertThat(claudeTool.has("parameters")).isFalse();
            assertThat(codexTool.has("input_schema")).isFalse();
        }
    }
}
