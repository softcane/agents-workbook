package io.github.softcane.workbook.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import io.envoyproxy.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse;
import io.envoyproxy.envoy.service.ext_proc.v3.StreamedImmediateResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnvoyApiContractTest {
    @Test
    void onlyPostRequestsEnterProviderInterceptionRoutes() throws Exception {
        String config = Files.readString(Path.of("envoy/envoy.yaml"));

        assertPostRoute(config, "/backend-api/codex/responses");
        assertPostRoute(config, "/v1/messages");
    }

    @Test
    void generatedEnvoy139ApiExposesStreamingLocalResponses() {
        var streamed = StreamedImmediateResponse.newBuilder().build();
        var response = ProcessingResponse.newBuilder()
                .setStreamedImmediateResponse(streamed)
                .build();

        assertThat(ProcessingMode.BodySendMode.FULL_DUPLEX_STREAMED.getNumber()).isEqualTo(4);
        assertThat(response.getResponseCase().getNumber()).isEqualTo(11);
    }

    private void assertPostRoute(String config, String path) {
        int pathIndex = config.indexOf("path: \"" + path + "\"");
        assertThat(pathIndex).as("route for %s", path).isNotNegative();
        int start = config.lastIndexOf("- match:", pathIndex);
        int end = config.indexOf("- match:", pathIndex + 1);
        String route = config.substring(start, end);
        assertThat(route).contains("name: \":method\"", "exact: \"POST\"");
    }
}
