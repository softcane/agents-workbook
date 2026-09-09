package io.github.softcane.workbook.proxy.provider;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import io.github.softcane.workbook.proxy.ConversationStateStore;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CodexProtocolTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void forcesWorkbookAndBuildsContinuationFromExactStreamedArguments() throws Exception {
        var original = (tools.jackson.databind.node.ObjectNode) json.readTree("""
                {"model":"gpt-test","input":[{"role":"user","content":"hello"}],
                 "tools":[{"type":"function","name":"real_tool","parameters":{"type":"object"}}],
                 "tool_choice":"auto","parallel_tool_calls":true,"unknown":"preserve-me"}
                """);
        var visible = new ArrayList<String>();
        var protocol = new CodexProtocol(json);
        var forced = protocol.forceWorkbook(original);

        assertThat(forced.path("tool_choice").path("name").asText()).isEqualTo("workbook");
        assertThat(forced.path("parallel_tool_calls").asBoolean()).isFalse();
        assertThat(forced.path("tools").get(0).path("name").asText()).isEqualTo("workbook");
        assertThat(forced.path("tools").get(0).has("strict")).isTrue();
        assertThat(forced.path("tools").get(0).path("strict").asBoolean()).isFalse();

        var stream = protocol.newHiddenStream(visible::add);
        stream.accept("response.function_call_arguments.delta", json.readTree("""
                {"delta":"{\\\"work\\\":\\\"line\\\\n"}
                """));
        stream.accept("response.function_call_arguments.delta", json.readTree("""
                {"delta":"two 🚀\\\"}"}
                """));
        stream.accept("response.output_item.done", json.readTree("""
                {"output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[]}}
                """));
        stream.accept("response.output_item.done", json.readTree("""
                {"output_index":1,"item":
                  {"type":"function_call","id":"fc_1","call_id":"call_1","name":"workbook",
                   "arguments":"{\\\"work\\\":\\\"line\\\\ntwo 🚀\\\"}","unknown_item":"keep"}}
                """));
        stream.accept("response.completed", json.readTree("""
                {"response":{"id":"resp_1","output":[]}}
                """));

        var continuation = protocol.continueAfterWorkbook(original, stream.finish());
        assertThat(String.join("", visible)).isEqualTo("line\ntwo 🚀");
        assertThat(continuation.path("unknown").asText()).isEqualTo("preserve-me");
        // Inverted deliberately: workbook must stay declared, or the continuation replays a function_call
        // for a function the request no longer offers -- the shape that reads as an injected fake.
        assertThat(continuation.path("tools").get(0).path("name").asText()).isEqualTo("workbook");
        assertThat(continuation.path("tools").get(1).path("name").asText()).isEqualTo("real_tool");
        assertThat(continuation.path("tool_choice").asText()).isEqualTo("auto");
        assertThat(continuation.path("parallel_tool_calls").asBoolean()).isTrue();
        assertThat(continuation.path("input").get(1).path("type").asText()).isEqualTo("reasoning");
        assertThat(continuation.path("input").get(2).path("unknown_item").asText()).isEqualTo("keep");
        assertThat(continuation.path("input").get(3).path("call_id").asText()).isEqualTo("call_1");
    }

    @Test
    void classifiesOnlyTheActiveCodexBoundaryAndRejectsProviderManagedChains() throws Exception {
        var protocol = new CodexProtocol(json);
        assertThat(protocol.requestContext((ObjectNode) json.readTree("""
                {"input":[{"role":"user","content":"hi"}]}""")).kind())
                .isEqualTo(ConversationStateStore.RequestKind.OPENING);
        assertThat(protocol.requestContext((ObjectNode) json.readTree("""
                {"input":"hi"}""")).kind()).isEqualTo(ConversationStateStore.RequestKind.OPENING);
        assertThat(protocol.requestContext((ObjectNode) json.readTree("""
                {"previous_response_id":"resp_1",
                 "input":[{"type":"function_call_output","call_id":"c"}]}""")).kind())
                .isEqualTo(ConversationStateStore.RequestKind.UNSUPPORTED);

        var results = protocol.requestContext((ObjectNode) json.readTree("""
                {"input":[
                 {"type":"function_call_output","call_id":"old"},
                 {"type":"function_call","call_id":"new-a"},
                 {"type":"function_call_output","call_id":"new-a"},
                 {"type":"function_call_output","call_id":"new-b"},
                 {"role":"developer","content":"client trailer"}]}
                """));
        assertThat(results.kind()).isEqualTo(ConversationStateStore.RequestKind.TOOL_RESULTS);
        assertThat(results.trailingResultIds()).containsExactlyInAnyOrder("new-a", "new-b");
        assertThat(results.historyResultIds()).containsExactlyInAnyOrder("old", "new-a", "new-b");

        var customResults = protocol.requestContext((ObjectNode) json.readTree("""
                {"input":[
                 {"type":"custom_tool_call","call_id":"custom-a","name":"shell","input":"printf x"},
                 {"type":"custom_tool_call_output","call_id":"custom-a","output":"x"}]}
                """));
        assertThat(customResults.kind()).isEqualTo(ConversationStateStore.RequestKind.TOOL_RESULTS);
        assertThat(customResults.trailingResultIds()).containsExactly("custom-a");
        assertThat(customResults.historyResultIds()).containsExactly("custom-a");

        var newText = protocol.requestContext((ObjectNode) json.readTree("""
                {"input":[{"type":"function_call_output","call_id":"old"},
                          {"role":"user","content":"a new request"}]}
                """));
        assertThat(newText.kind()).isEqualTo(ConversationStateStore.RequestKind.OPENING);
        assertThat(newText.trailingResultIds()).isEmpty();
        assertThat(newText.historyResultIds()).containsExactly("old");
        assertThat(protocol.requestContext((ObjectNode) json.readTree("""
                {"input":[{"role":"assistant","content":"half-written"}]}""")).kind())
                .isEqualTo(ConversationStateStore.RequestKind.UNSUPPORTED);

        for (String choice : new String[] {"\"auto\"", "\"required\"", "\"none\"",
                "{\"type\":\"function\",\"name\":\"real_tool\"}"}) {
            assertThat(protocol.requestContext((ObjectNode) json.readTree(
                    "{\"input\":[{\"role\":\"user\",\"content\":\"hi\"}],\"tool_choice\":" + choice + "}")).kind())
                    .as("tool_choice=%s", choice).isEqualTo(ConversationStateStore.RequestKind.OPENING);
        }
    }

    @Test
    void fingerprintsOnlyCanonicalCodexHistory() throws Exception {
        var protocol = new CodexProtocol(json);
        var first = (ObjectNode) json.readTree("""
                {"model":"one","tools":[{"name":"secret"}],
                 "input":[{"type":"message","role":"user","content":[{"text":"hi","type":"input_text"}]}]}
                """);
        var sameHistory = (ObjectNode) json.readTree("""
                {"model":"two","input":[{"content":[{"type":"input_text","text":"hi"}],
                 "role":"user","type":"message"}],"parallel_tool_calls":false}
                """);
        var changed = (ObjectNode) json.readTree("""
                {"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"bye"}]}]}
                """);
        assertThat(protocol.historyFingerprint(first)).isEqualTo(protocol.historyFingerprint(sameHistory));
        assertThat(protocol.historyFingerprint(first)).isNotEqualTo(protocol.historyFingerprint(changed));
    }

    @Test
    void restoresCompleteCodexOutputBeforeItsOriginalAnchorWithoutChangingOpaqueItems() throws Exception {
        var protocol = new CodexProtocol(json);
        var source = (ObjectNode) json.readTree("""
                {"input":[{"type":"message","role":"user","content":[{"type":"input_text","text":"start"}]}]}
                """);
        var providerOutput = (tools.jackson.databind.node.ArrayNode) json.readTree("""
                [{"type":"reasoning","id":"rs_private","status":"completed",
                  "encrypted_content":"cipher-private","summary":[{"type":"summary_text","text":"opaque"}],
                  "unknown":"keep"},
                 {"type":"function_call","id":"fc_private","call_id":"wb_private","name":"workbook",
                  "arguments":"{\\"work\\":\\"note\\"}","status":"completed"}]
                """);
        var boundary = new ConversationStateStore.Boundary(1, "raw", protocol.historyFingerprint(source),
                List.of(new ConversationStateStore.Exchange(
                        providerOutput, "wb_private", CodexProtocol.WORKBOOK_RESULT)),
                "rs_visible", Set.of("real-a", "real-b"), 0);
        var next = (ObjectNode) json.readTree("""
                {"input":[
                 {"type":"message","role":"user","content":[{"type":"input_text","text":"start"}]},
                 {"type":"reasoning","id":"rs_visible","status":"completed",
                  "encrypted_content":"cipher-visible","summary":[],"phase":"commentary"},
                 {"type":"function_call","id":"fc_a","call_id":"real-a","name":"shell","arguments":"{}"},
                 {"type":"function_call","id":"fc_b","call_id":"real-b","name":"shell","arguments":"{}"},
                 {"type":"function_call_output","call_id":"real-a","output":"a"},
                 {"type":"function_call_output","call_id":"real-b","output":"b"}]}
                """);
        JsonNode visibleReasoning = next.path("input").get(1).deepCopy();
        JsonNode realCall = next.path("input").get(2).deepCopy();

        var restored = protocol.restoreWorkbookHistory(next, List.of(boundary));

        assertThat(restored.skipReason()).isNull();
        assertThat(restored.insertedBoundaryCount()).isEqualTo(1);
        assertThat(restored.request().path("input").get(1)).isEqualTo(providerOutput.get(0));
        assertThat(restored.request().path("input").get(2)).isEqualTo(providerOutput.get(1));
        assertThat(restored.request().path("input").get(3).path("call_id").asText()).isEqualTo("wb_private");
        assertThat(restored.request().path("input").get(4)).isEqualTo(visibleReasoning);
        assertThat(restored.request().path("input").get(5)).isEqualTo(realCall);

        var alreadyPresent = protocol.restoreWorkbookHistory(restored.request(), List.of(boundary));
        assertThat(alreadyPresent.skipReason()).isNull();
        assertThat(alreadyPresent.insertedBoundaryCount()).isZero();
        assertThat(alreadyPresent.request()).isEqualTo(restored.request());
    }


    @Test
    void rejectsPartialChangedAndAmbiguousCodexReplayWithoutMutatingTheRequest() throws Exception {
        var protocol = new CodexProtocol(json);
        var source = (ObjectNode) json.readTree("""
                {"input":[{"type":"message","role":"user","content":"start"}]}
                """);
        var output = (tools.jackson.databind.node.ArrayNode) json.readTree("""
                [{"type":"function_call","id":"fc_wb","call_id":"wb","name":"workbook",
                  "arguments":"{\\"work\\":\\"note\\"}"}]
                """);
        var boundary = new ConversationStateStore.Boundary(1, "raw", protocol.historyFingerprint(source),
                List.of(new ConversationStateStore.Exchange(output, "wb", CodexProtocol.WORKBOOK_RESULT)),
                "anchor", Set.of("real"), 0);
        var request = (ObjectNode) json.readTree("""
                {"input":[{"type":"message","role":"user","content":"start"},
                          {"type":"reasoning","id":"anchor","encrypted_content":"cipher"},
                          {"type":"function_call","id":"fc_real","call_id":"real","name":"shell"},
                          {"type":"function_call_output","call_id":"real","output":"ok"}]}
                """);

        var partial = request.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) partial.path("input")).insert(1, json.createObjectNode()
                .put("type", "function_call_output").put("call_id", "wb").put("output", "changed"));
        JsonNode partialBefore = partial.deepCopy();
        assertThat(protocol.restoreWorkbookHistory(partial, List.of(boundary)).skipReason())
                .isEqualTo("partial_existing_replay");
        assertThat(partial).isEqualTo(partialBefore);

        var missing = request.deepCopy();
        ((ObjectNode) missing.path("input").get(1)).put("id", "other");
        JsonNode missingBefore = missing.deepCopy();
        assertThat(protocol.restoreWorkbookHistory(missing, List.of(boundary)).skipReason())
                .isEqualTo("ambiguous_anchor");
        assertThat(missing).isEqualTo(missingBefore);

        var duplicate = request.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) duplicate.path("input"))
                .insert(2, duplicate.path("input").get(1).deepCopy());
        assertThat(protocol.restoreWorkbookHistory(duplicate, List.of(boundary)).skipReason())
                .isEqualTo("ambiguous_anchor");

        var changed = request.deepCopy();
        ((ObjectNode) changed.path("input").get(0)).put("content", "changed");
        JsonNode changedBefore = changed.deepCopy();
        assertThat(protocol.restoreWorkbookHistory(changed, List.of(boundary)).skipReason())
                .isEqualTo("changed_branch");
        assertThat(changed).isEqualTo(changedBefore);
    }

    @Test
    void observesOnlyRealCodexToolIdsAndEveryOutputItemAnchor() throws Exception {
        var protocol = new CodexProtocol(json);
        var reasoning = json.readTree("""
                {"item":{"type":"reasoning","id":"reasoning-anchor","encrypted_content":"cipher"}}
                """);
        assertThat(protocol.outputItemId("response.output_item.added", reasoning))
                .contains("reasoning-anchor");
        assertThat(protocol.realToolCall("response.output_item.added", reasoning)).isEmpty();

        var workbook = json.readTree("""
                {"item":{"type":"function_call","id":"fc_wb","call_id":"wb","name":"workbook",
                 "arguments":"secret"}}
                """);
        assertThat(protocol.realToolCall("response.output_item.added", workbook)).isEmpty();

        var real = json.readTree("""
                {"item":{"type":"function_call","id":"fc_real","call_id":"real","name":"shell",
                 "arguments":"secret"}}
                """);
        assertThat(protocol.realToolCall("response.output_item.added", real))
                .contains(new ConversationStateStore.ToolCall("real", "shell"));

        var custom = json.readTree("""
                {"item":{"type":"custom_tool_call","id":"ctc_real","call_id":"custom-real",
                 "name":"shell","input":"secret"}}
                """);
        assertThat(protocol.realToolCall("response.output_item.added", custom))
                .contains(new ConversationStateStore.ToolCall("custom-real", "shell"));
    }
    @Test
    void gateHoldsARepeatedWorkbookCallBackAndReleasesARealOutputItem() {
        var protocol = new CodexProtocol(json);
        var released = new ArrayList<String>();
        var visible = new ArrayList<String>();
        var gate = protocol.newResponseGate(bytes -> released.add(new String(bytes, StandardCharsets.UTF_8)),
                visible::add, 1 << 20);

        gate.accept(sse("response.output_item.added",
                "{\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}"));
        assertThat(released).isEmpty();
        gate.accept(sse("response.output_item.added",
                "{\"item\":{\"type\":\"function_call\",\"name\":\"workbook\",\"call_id\":\"call_2\"}}"));
        gate.accept(sse("response.function_call_arguments.delta",
                "{\"delta\":\"{\\\"work\\\":\\\"second\\\"}\"}"));
        gate.accept(sse("response.output_item.done",
                "{\"item\":{\"type\":\"function_call\",\"call_id\":\"call_2\",\"name\":\"workbook\","
                        + "\"arguments\":\"{\\\"work\\\":\\\"second\\\"}\"}}"));
        gate.accept(sse("response.completed", "{\"response\":{\"id\":\"resp_2\",\"output\":[]}}"));

        var repeated = gate.finish();
        assertThat(gate.committed()).isFalse();
        assertThat(released).isEmpty();
        assertThat(visible).containsExactly("second");
        assertThat(repeated).isPresent();
        assertThat(repeated.get().callId()).isEqualTo("call_2");
    }

    @Test
    void gateReleasesEveryBufferedByteOnceTheModelPicksARealOutputItem() {
        var protocol = new CodexProtocol(json);
        var released = new ArrayList<String>();
        var gate = protocol.newResponseGate(bytes -> released.add(new String(bytes, StandardCharsets.UTF_8)),
                delta -> { }, 1 << 20);

        gate.accept(sse("response.output_item.added",
                "{\"item\":{\"type\":\"reasoning\",\"id\":\"rs_1\"}}"));
        gate.accept(sse("response.output_item.added",
                "{\"item\":{\"type\":\"message\",\"role\":\"assistant\"}}"));

        assertThat(gate.committed()).isTrue();
        assertThat(gate.finish()).isEmpty();
        // The reasoning frame the gate buffered before deciding must still reach the client.
        assertThat(String.join("", released)).contains("rs_1").contains("\"type\":\"message\"");
    }

    @Test
    void gatePassesMalformedBytesThroughAfterCodexResponseIsCommitted() {
        var protocol = new CodexProtocol(json);
        var released = new java.io.ByteArrayOutputStream();
        var gate = protocol.newResponseGate(released::writeBytes, delta -> { }, 1 << 20);
        byte[] committing = sse("response.output_item.added",
                "{\"item\":{\"type\":\"message\",\"role\":\"assistant\"}}");
        byte[] malformed = {(byte) 0xc3, 0x28, '\n'};

        gate.accept(committing);
        gate.accept(malformed);

        byte[] expected = java.nio.ByteBuffer.allocate(committing.length + malformed.length)
                .put(committing).put(malformed).array();
        assertThat(released.toByteArray()).containsExactly(expected);
    }

    private static byte[] sse(String event, String data) {
        return ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
