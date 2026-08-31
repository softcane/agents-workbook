package io.github.softcane.workbook.proxy.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class SseParserTest {
    @Test
    void preservesUtf8AndMultilineDataAcrossEveryByteBoundary() {
        var events = new ArrayList<String>();
        var parser = new SseParser((event, data) -> events.add(event + "=" + data));
        byte[] wire = "event: delta\r\ndata: {\"text\":\"🚀\"}\r\ndata: second\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8);

        for (byte value : wire) parser.feed(new byte[] {value});
        parser.finish();

        assertThat(events).containsExactly("delta={\"text\":\"🚀\"}\nsecond");
    }

    @Test
    void recoversCleanlyAfterAListenerExceptionInsteadOfCorruptingTheNextEvent() {
        var events = new ArrayList<String>();
        var parser = new SseParser((event, data) -> {
            if ("boom".equals(data)) throw new IllegalArgumentException("simulated malformed payload");
            events.add(event + "=" + data);
        });

        assertThatThrownBy(() -> parser.feed("event: bad\ndata: boom\n\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        parser.feed("event: good\ndata: fine\n\n".getBytes(StandardCharsets.UTF_8));
        parser.finish();

        assertThat(events).containsExactly("good=fine");
    }
}
