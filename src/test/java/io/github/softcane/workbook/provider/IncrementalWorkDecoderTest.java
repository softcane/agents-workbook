package io.github.softcane.workbook.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IncrementalWorkDecoderTest {
    @Test
    void decodesEveryUtf8BoundaryAndJsonEscape() {
        String raw = "{\"work\":\"line\\nquote: \\\"; slash: \\\\; emoji: \\uD83D\\uDE80; café\"}";
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        var decoder = new IncrementalWorkDecoder();
        var emitted = new StringBuilder();
        for (byte value : bytes) emitted.append(decoder.feed(new byte[] { value }));
        String expected = "line\nquote: \"; slash: \\; emoji: 🚀; café";
        assertThat(decoder.finish()).isEqualTo(expected);
        // Byte-at-a-time emission has to be lossless: the dashboard only ever sees these increments.
        assertThat(emitted.toString()).isEqualTo(expected);
    }

    @Test
    void rejectsMalformedAndTruncatedInput() {
        var malformed = new IncrementalWorkDecoder();
        assertThatThrownBy(() -> malformed.feed("{\"work\":\"\\q\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        var truncated = new IncrementalWorkDecoder();
        truncated.feed("{\"work\":\"abc\\uD83D".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(truncated::finish).isInstanceOf(IllegalArgumentException.class);
    }
}
