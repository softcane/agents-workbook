package io.github.softcane.workbook.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * One-line JSON diagnostics for the capture path, silent unless this package logs at DEBUG.
 *
 * <p>Only shapes may pass through here: counts, byte lengths, status codes, and the provider's own
 * protocol type names. SECURITY.md forbids a note, a prompt, a tool argument, or a header reaching a
 * log, and a diagnostic is not an exception to that rule -- a field that could carry one belongs in a
 * debugger, not here.
 */
public final class Diagnostics {
    private static final Logger log = LoggerFactory.getLogger(Diagnostics.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private Diagnostics() {
    }

    /** Lets a caller skip building a field a diagnostic would only throw away. */
    public static boolean enabled() {
        return log.isDebugEnabled();
    }

    /** Name/value pairs: {@code event("claude.hidden.finish", "status", 200, "blocks", 3)}. */
    public static void event(String name, Object... fields) {
        if (!log.isDebugEnabled()) return;
        if (fields.length % 2 != 0) {
            throw new IllegalArgumentException("Diagnostic fields must be name/value pairs");
        }
        var node = JSON.createObjectNode();
        node.put("event", name);
        for (int index = 0; index < fields.length; index += 2) {
            node.putPOJO(String.valueOf(fields[index]), fields[index + 1]);
        }
        log.debug(JSON.writeValueAsString(node));
    }
}
