package io.github.softcane.workbook.proxy.provider;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class SseParser {
    @FunctionalInterface
    public interface Listener {
        void onEvent(String event, String data);
    }

    private final Listener listener;
    private final ByteArrayOutputStream lineBytes = new ByteArrayOutputStream();
    private final StringBuilder data = new StringBuilder();
    private String event = "message";

    public SseParser(Listener listener) {
        this.listener = listener;
    }

    public void feed(byte[] bytes) {
        for (byte value : bytes) {
            if (value == '\n') {
                acceptLine(decodeLine());
                lineBytes.reset();
            } else {
                lineBytes.write(value);
            }
        }
    }

    public void finish() {
        if (lineBytes.size() > 0) {
            acceptLine(decodeLine());
            lineBytes.reset();
        }
        dispatch();
    }

    private String decodeLine() {
        byte[] bytes = lineBytes.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') length--;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IllegalArgumentException("Malformed UTF-8 in provider SSE", error);
        }
    }

    private void acceptLine(String line) {
        if (line.isEmpty()) {
            dispatch();
        } else if (line.startsWith("event:")) {
            event = value(line.substring(6));
        } else if (line.startsWith("data:")) {
            if (!data.isEmpty()) data.append('\n');
            data.append(value(line.substring(5)));
        }
    }

    private String value(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private void dispatch() {
        if (data.isEmpty()) return;
        String payload = data.toString();
        String dispatchedEvent = event;
        // Reset buffered state before invoking the listener so a listener exception can never leave a
        // stale event/data pair to corrupt the next dispatch.
        event = "message";
        data.setLength(0);
        listener.onEvent(dispatchedEvent, payload);
    }
}
