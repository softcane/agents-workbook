package io.github.softcane.workbook.proxy.provider;

/**
 * How much effort the hidden call asks for, set independently of the client's own request.
 *
 * <p>A closed set rather than a free-text setting, for the same reason {@link NoteStyle} is one: the
 * value reaches a provider verbatim, and a typo would 400 the hidden call. Capture fails open, so a
 * typo would cost notes silently rather than raising anything.
 *
 * <p>{@link #INHERIT} is the default and the historical behaviour -- the hidden request carries
 * whatever depth the client asked for, and this proxy adds nothing. Every other value overrides the
 * hidden call only. The client-visible continuation is never touched: turning the user's real answer
 * down is the half of the upstream effort split this project deliberately does not have.
 *
 * <p>Anthropic renders the resolved effort into the prompt, so raising it here and leaving the
 * continuation alone means the continuation starts a new cache prefix and re-reads the conversation.
 * That is a latency cost on the client's real turn, paid once per turn, and it is the price of the
 * experiment rather than a bug to fix.
 */
public enum HiddenEffort {
    /** Forward the client's own depth setting untouched. */
    INHERIT(null),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    private final String wireValue;

    HiddenEffort(String wireValue) {
        this.wireValue = wireValue;
    }

    /** The string a provider expects, or null when this proxy sets nothing. */
    public String wireValue() {
        return wireValue;
    }

    public boolean overrides() {
        return wireValue != null;
    }

    /**
     * Whether this level refuses to sit beside {@code thinking: {"type": "disabled"}}. Opus 5 returns a
     * 400 for that combination at {@code xhigh} and {@code max} only, and Claude Code sends it on every
     * session -- its background title call disables thinking. Proven live on 2026-08-19: the title call
     * returned {@code 400 invalid_request_error} at {@code xhigh} and captured once clamped.
     */
    public boolean rejectedWithoutThinking() {
        return this == XHIGH || this == MAX;
    }

    /** How the setting names itself in a log line. */
    public String label() {
        return overrides() ? wireValue : "inherit";
    }
}
