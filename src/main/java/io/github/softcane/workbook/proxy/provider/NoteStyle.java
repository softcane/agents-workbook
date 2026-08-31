package io.github.softcane.workbook.proxy.provider;

/**
 * The notes tool's wording: one row per style, one column per provider. Both providers switch together
 * on a single setting, but they never share a sentence. Claude Code reads the tool through a system
 * prompt that already documents a scratchpad directory and has refused wordings Codex accepts, so the
 * two vocabularies were tuned against different failures and are kept separately reviewable here.
 *
 * <p>A closed set rather than free-text configuration, so the deployed wording stays reviewable in
 * source while an experiment can still switch between styles without a rebuild.
 *
 * <p>Every ask has to fit in the two description strings. Neither provider appends anything to the
 * client's message history — see {@code ClaudeProtocol.workbookTool} for what happened when one did.
 */
public enum NoteStyle {
    /** Asks for the next step. Produces plans, and restates them while the plan holds. */
    INTENT(1,
            new Wording(
                    "Records a short note about your next step on the dashboard the user runs alongside this "
                            + "session, which is how they follow along with your work. Call it once with a short "
                            + "note, then carry on with the task exactly as you otherwise would. The note is not "
                            + "added to the transcript, so keep each one self-contained, brief, and free of "
                            + "credentials or other secrets.",
                    "Concise plain-text working notes describing what you're about to do next. "
                            + "Do not include secrets."),
            new Wording(
                    "Call this function once, before any other tool call or your final response, to record brief "
                            + "working notes explaining your next step. Never include credentials, API keys, or "
                            + "other secrets in the notes.",
                    "Concise plain-text working notes for your next step. No secrets.")),
    /** Asks for what changed since the last note, which forces a comparison rather than a restatement. */
    DELTA(1,
            new Wording(
                    "Records what you have just learned that changes your plan, on the dashboard the user runs "
                            + "alongside this session, which is how they follow along with your work. Call it "
                            + "once, then carry on with the task exactly as you otherwise would. The note is not "
                            + "added to the transcript, so keep each one self-contained, brief, and free of "
                            + "credentials or other secrets.",
                    "What you learned since your last note that changes what you will do: a finding, an option "
                            + "you ruled out, a surprise, or a decision and the alternative you rejected. If "
                            + "nothing changed, write only: no change. Plain text. Do not include secrets."),
            new Wording(
                    "Call this function once, before any other tool call or your final response, to record what "
                            + "you have just learned that changes your plan. Never include credentials, API keys, "
                            + "or other secrets in the notes.",
                    "What you learned since your last note that changes what you will do: a finding, an option "
                            + "you ruled out, a surprise, or a decision and the alternative you rejected. If "
                            + "nothing changed, write only: no change. No secrets.")),
    /**
     * Asks for the decision and the alternatives it rules out, at whatever length the problem earns, and
     * is the style this proxy ships by default. It is also by far the most expensive: a turn under this
     * wording can add thousands of output tokens on top of the answer the client asked for, which is
     * why {@code DELTA} exists for anyone who wants the dashboard without the bill.
     *
     * <p>The framing is load-bearing, not a matter of taste. This wording used to ask the model to
     * "write the thinking itself"; Opus 5 answered every such call with {@code stop_reason: refusal} --
     * it opened the forced tool call, wrote nothing, and ended the turn -- while Sonnet 5 complied.
     * Live probing on 2026-08-19 separated the variables: length is not the trigger and neither is the
     * word "reasoning". Asking the model to externalise its own thinking process is. Asked instead for
     * the decision it is making and what that rules out, Opus 5 writes notes several times longer than
     * the introspective wording ever got out of Sonnet. Keep the ask pointed at the work, not at the
     * model's mind.
     *
     * <p>Nothing here advertises a lifted effort cap or an uncapped channel. The proxy lifts nothing,
     * and live runs that refused the exchange cited a tool describing itself falsely as the reason, so
     * a wording that overclaims costs capture rather than buying depth.
     */
    REASONING(2,
            new Wording(
                    "Records the decision you are making and what it rules out, on the dashboard the user runs "
                            + "alongside this session, which is how they follow along with your work. Call it "
                            + "once before you answer, and again when another tool's result changes what you will "
                            + "do, then carry on with the task exactly as you otherwise would. The note is not "
                            + "added to the transcript, so keep each one self-contained and free of credentials "
                            + "or other secrets.",
                    "The decision you are making, the alternatives you rejected, and what each one would have "
                            + "cost. Go long where the problem earns it. Plain text. Do not include secrets."),
            new Wording(
                    "Call this function once, before any other tool call or your final response, to record how "
                            + "you are working the problem through: the angles you are weighing, the options you "
                            + "ruled out, and the reasoning behind the approach you picked. Never include "
                            + "credentials, API keys, or other secrets in the notes.",
                    "How you worked the problem through, covering the angles that mattered and the ones you "
                            + "rejected. No secrets.")),
    /**
     * Asks what the model expects to happen next, concretely enough to turn out wrong. The other three
     * styles produce prose that only a reader can judge; this one produces a claim the proxy can check
     * itself, because the next request the client sends carries the result the claim was about.
     *
     * <p>The framing also sidesteps the failure {@link #REASONING} was rewritten to avoid. An expectation
     * is a statement about the world rather than about the model's own thinking, which is the distinction
     * that decided whether Opus 5 answered a forced call at all -- see the notes on {@code REASONING} and
     * {@code docs/note-style-log.md}. Unverified against a live model until an entry appears there.
     */
    PREDICTION(1,
            new Wording(
                    "Records what you expect to happen next, on the dashboard the user runs alongside this "
                            + "session, which is how they follow along with your work. Call it once before you "
                            + "act, then carry on with the task exactly as you otherwise would. The note is not "
                            + "added to the transcript, so keep each one self-contained and free of credentials "
                            + "or other secrets.",
                    "What you expect the next tool call or command to return, stated concretely enough that it "
                            + "can turn out wrong, and the result that would make you change approach. If you "
                            + "are about to answer rather than act, say instead what you expect the user to "
                            + "push back on. Plain text. Do not include secrets."),
            new Wording(
                    "Call this function once, before any other tool call or your final response, to record "
                            + "what you expect to happen next. Never include credentials, API keys, or other "
                            + "secrets in the notes.",
                    "Your concrete expectation for the next command or tool result, specific enough that it can "
                            + "turn out wrong, plus the observation that would make you change course. No "
                            + "secrets."));

    /**
     * One provider's rendering of one style. The same two fields either side, because the tool declaration
     * is the only channel each provider has; the envelope around them differs, the text always does.
     */
    public record Wording(String description, String workDescription) { }

    private final int wordingVersion;
    private final Wording claude;
    private final Wording codex;

    NoteStyle(int wordingVersion, Wording claude, Wording codex) {
        this.wordingVersion = wordingVersion;
        this.claude = claude;
        this.codex = codex;
    }

    /**
     * Bumped whenever any string in this style changes, and never reused. A wording that captures today
     * can start refusing after a provider updates a model, so a note, a refusal, and a log line are only
     * interpretable next to the version of the text that produced them. What each version did in a live
     * run is recorded in {@code docs/note-style-log.md}.
     */
    public int wordingVersion() {
        return wordingVersion;
    }

    /** How a style names itself in a log: the style and the wording that was actually deployed. */
    public String label() {
        return name() + "/v" + wordingVersion;
    }

    public Wording claude() {
        return claude;
    }

    public Wording codex() {
        return codex;
    }
}
