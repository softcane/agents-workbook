package io.github.softcane.workbook.provider;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Incrementally decodes the string value of the JSON field named {@code work}. */
public final class IncrementalWorkDecoder {
    private enum State { SEEK_KEY, IN_KEY, AFTER_KEY, BEFORE_VALUE, IN_VALUE, DONE }

    private final ByteArrayOutputStream pendingUtf8 = new ByteArrayOutputStream(4);
    private final StringBuilder key = new StringBuilder();
    private final StringBuilder visible = new StringBuilder();
    private final StringBuilder unicodeEscape = new StringBuilder(4);
    private State state = State.SEEK_KEY;
    private boolean escaped;
    private boolean inUnicodeEscape;
    private Character highSurrogate;

    public String feed(byte[] bytes) {
        if (state == State.DONE && bytes.length > 0) {
            return "";
        }
        pendingUtf8.writeBytes(bytes);
        ByteBuffer input = ByteBuffer.wrap(pendingUtf8.toByteArray());
        CharBuffer output = CharBuffer.allocate(Math.max(8, input.remaining() * 2));
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        var result = decoder.decode(input, output, false);
        if (result.isError()) {
            try {
                result.throwException();
            } catch (CharacterCodingException error) {
                throw new IllegalArgumentException("Malformed UTF-8 in workbook arguments", error);
            }
        }
        byte[] remainder = new byte[input.remaining()];
        input.get(remainder);
        pendingUtf8.reset();
        pendingUtf8.writeBytes(remainder);
        output.flip();

        int start = visible.length();
        while (output.hasRemaining()) {
            accept(output.get());
        }
        return visible.substring(start);
    }

    public String finish() {
        if (pendingUtf8.size() != 0) {
            throw new IllegalArgumentException("Truncated UTF-8 in workbook arguments");
        }
        if (state != State.DONE || escaped || inUnicodeEscape || highSurrogate != null) {
            throw new IllegalArgumentException("Truncated JSON work string");
        }
        return visible.toString();
    }

    private void accept(char value) {
        switch (state) {
            case SEEK_KEY -> {
                if (value == '"') {
                    key.setLength(0);
                    state = State.IN_KEY;
                }
            }
            case IN_KEY -> {
                if (value == '"') {
                    state = "work".contentEquals(key) ? State.AFTER_KEY : State.SEEK_KEY;
                } else {
                    key.append(value);
                }
            }
            case AFTER_KEY -> {
                if (value == ':') state = State.BEFORE_VALUE;
                else if (!Character.isWhitespace(value)) throw malformed("Expected ':' after work");
            }
            case BEFORE_VALUE -> {
                if (value == '"') state = State.IN_VALUE;
                else if (!Character.isWhitespace(value)) throw malformed("work must be a string");
            }
            case IN_VALUE -> acceptValue(value);
            case DONE -> { }
        }
    }

    private void acceptValue(char value) {
        if (inUnicodeEscape) {
            if (Character.digit(value, 16) < 0) throw malformed("Invalid Unicode escape");
            unicodeEscape.append(value);
            if (unicodeEscape.length() == 4) {
                char decoded = (char) Integer.parseInt(unicodeEscape.toString(), 16);
                unicodeEscape.setLength(0);
                inUnicodeEscape = false;
                appendDecoded(decoded);
                escaped = false;
            }
            return;
        }
        if (escaped) {
            switch (value) {
                case '"', '\\', '/' -> appendDecoded(value);
                case 'b' -> appendDecoded('\b');
                case 'f' -> appendDecoded('\f');
                case 'n' -> appendDecoded('\n');
                case 'r' -> appendDecoded('\r');
                case 't' -> appendDecoded('\t');
                case 'u' -> {
                    unicodeEscape.setLength(0);
                    inUnicodeEscape = true;
                    return;
                }
                default -> throw malformed("Invalid JSON escape");
            }
            escaped = false;
            return;
        }
        if (value == '\\') {
            escaped = true;
        } else if (value == '"') {
            if (highSurrogate != null) throw malformed("Unpaired high surrogate");
            state = State.DONE;
        } else if (value < 0x20) {
            throw malformed("Control character in JSON string");
        } else {
            appendDecoded(value);
        }
    }

    private void appendDecoded(char value) {
        if (highSurrogate != null) {
            if (!Character.isLowSurrogate(value)) throw malformed("Unpaired high surrogate");
            visible.append(highSurrogate).append(value);
            highSurrogate = null;
        } else if (Character.isHighSurrogate(value)) {
            highSurrogate = value;
        } else if (Character.isLowSurrogate(value)) {
            throw malformed("Unpaired low surrogate");
        } else {
            visible.append(value);
        }
    }

    private IllegalArgumentException malformed(String message) {
        return new IllegalArgumentException(message + " in workbook arguments");
    }
}
