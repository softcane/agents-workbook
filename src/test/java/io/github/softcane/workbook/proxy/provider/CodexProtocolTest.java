package io.github.softcane.workbook.proxy.provider;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
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
    void refusesCaptureOnlyForAnAssistantPrefill() throws Exception {
        var protocol = new CodexProtocol(json);
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"input":[{"role":"user","content":"hi"}]}"""))).isTrue();
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"input":"hi"}"""))).isTrue();
        // Codex never sends this over HTTP, but chaining by id must not disable capture regardless.
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"previous_response_id":"resp_1","input":[{"type":"function_call_output","call_id":"c"}]}""")))
                .isTrue();
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"input":[{"role":"user","content":"hi"},{"role":"developer","content":"note"}]}"""))).isTrue();
        assertThat(protocol.supportsCapture((ObjectNode) json.readTree("""
                {"input":[{"role":"assistant","content":"half-written"}]}"""))).isFalse();
        // No tool_choice refuses capture: only the hidden call overrides it, and the continuation restores
        // the client's own value, so the client-visible turn stays bound by whatever it asked for.
        for (String choice : new String[] {"\"auto\"", "\"required\"", "\"none\"",
                "{\"type\":\"function\",\"name\":\"real_tool\"}"}) {
            assertThat(protocol.supportsCapture((ObjectNode) json.readTree(
                    "{\"input\":[{\"role\":\"user\",\"content\":\"hi\"}],\"tool_choice\":" + choice + "}")))
                    .as("tool_choice=%s", choice).isTrue();
        }
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

    private static byte[] sse(String event, String data) {
        return ("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
