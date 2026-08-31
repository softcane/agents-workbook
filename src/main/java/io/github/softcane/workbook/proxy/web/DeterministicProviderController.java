package io.github.softcane.workbook.proxy.web;

import io.github.softcane.workbook.proxy.provider.ClaudeProtocol;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnProperty(name = "workbook-proxy.test-upstream-enabled", havingValue = "true")
public final class DeterministicProviderController {
    private final ObjectMapper json;

    public DeterministicProviderController(ObjectMapper json) {
        this.json = json;
    }

    @PostMapping(value = "/__test/provider", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> provider(
            @RequestHeader("x-workbook-provider") String provider,
            @RequestBody String requestBody) {
        // Parsed rather than substring-matched so a malformed body fails here instead of silently
        // answering as though it were an opening turn.
        final JsonNode request;
        try {
            request = json.readTree(requestBody);
        } catch (JacksonException error) {
            throw new IllegalArgumentException("test provider received invalid JSON", error);
        }
        boolean claude = "claude".equals(provider);
        if (repliesToAWorkbookResult(request)) {
            return delayed(event(claude ? "content_block_delta" : "response.output_text.delta",
                    claude
                            ? "{\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"deterministic final answer\"}}"
                            : "{\"delta\":\"deterministic final answer\"}"), 20);
        }
        return claude ? claudeHidden() : codexHidden();
    }

    /** A continuation carries the proxy's own tool result: Codex as an input item, Claude as a content block. */
    private static boolean repliesToAWorkbookResult(JsonNode request) {
        for (JsonNode item : request.path("input")) {
            if ("function_call_output".equals(item.path("type").asText())) return true;
        }
        for (JsonNode message : request.path("messages")) {
            for (JsonNode block : message.path("content")) {
                if ("tool_result".equals(block.path("type").asText())) return true;
            }
        }
        return false;
    }

    private Flux<ServerSentEvent<String>> codexHidden() {
        return Flux.concat(
                delayed(event("response.output_item.added",
                        "{\"item\":{\"type\":\"function_call\",\"id\":\"fc_e2e\",\"call_id\":\"call_e2e\",\"name\":\"workbook\",\"arguments\":\"\"}}"), 10),
                delayed(event("response.function_call_arguments.delta", "{\"delta\":\"{\\\"work\\\":\\\"streamed \"}"), 80),
                delayed(event("response.function_call_arguments.delta", "{\"delta\":\"workbook\\\"}\"}"), 80),
                delayed(event("response.output_item.done",
                        "{\"output_index\":0,\"item\":{\"type\":\"function_call\",\"id\":\"fc_e2e\",\"call_id\":\"call_e2e\",\"name\":\"workbook\",\"arguments\":\"{\\\"work\\\":\\\"streamed workbook\\\"}\"}}"), 80),
                delayed(event("response.completed",
                        "{\"response\":{\"id\":\"resp_e2e\",\"output\":[],"
                                + "\"usage\":{\"input_tokens\":812,\"output_tokens\":34}}}"), 80));
    }

    private Flux<ServerSentEvent<String>> claudeHidden() {
        return Flux.concat(
                delayed(event("message_start",
                        "{\"message\":{\"id\":\"msg_e2e\",\"usage\":{\"input_tokens\":740,\"output_tokens\":1}}}"), 5),
                // The wire name the proxy actually forces; a stale literal here streams a tool_use the
                // response gate does not recognise, so the e2e run captures nothing.
                delayed(event("content_block_start",
                        "{\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_e2e\",\"name\":\""
                                + ClaudeProtocol.TOOL_NAME + "\",\"input\":{}}}"), 10),
                delayed(event("content_block_delta",
                        "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"work\\\":\\\"streamed \"}}"), 80),
                delayed(event("content_block_delta",
                        "{\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"workbook\\\"}\"}}"), 80),
                delayed(event("content_block_stop", "{\"index\":0}"), 30),
                delayed(event("message_delta", "{\"usage\":{\"output_tokens\":29}}"), 10),
                delayed(event("message_stop", "{}"), 30));
    }

    private Flux<ServerSentEvent<String>> delayed(ServerSentEvent<String> event, long millis) {
        return Mono.delay(Duration.ofMillis(millis)).map(ignored -> event).flux();
    }

    private ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }
}
