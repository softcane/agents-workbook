# Experiments worth running next

Written after reading the current Anthropic, OpenAI and harness docs rather than
working from this repo's own comments.

Three things this repo says are wrong. One of them closed a line of work that
should be open. The plan is in Part 3; the corrections come first because one of
them changes the plan.

**A note on cost.** An earlier draft of this file argued at length about prompt
caching. That has been cut. Cost is not a constraint on this project — it is an
experiment, not a product, and the README already warns anyone who runs it. Only
one cost-adjacent fact survives, and it is really a latency fact: see 1.3.

---

# Part 1 — What the docs say, and the repo doesn't

## 1.1 The `thinking` door is not closed. It was misdiagnosed.

**`docs/note-style-log.md` says:**

> Setting `thinking` on the hidden call returns `400 invalid_request_error` in
> both directions… **because that call forces `tool_choice`**. Do not try to fix
> a refusal there.

**The 400s are real. The stated cause is not.**

Forced tool use is incompatible with *manual* extended thinking only:

> Tool use with manual extended thinking (`thinking: {type: "enabled"}`) only
> supports `tool_choice: {"type": "auto"}` or `tool_choice: {"type": "none"}`…
> **Adaptive thinking, including on models where thinking is on by default,
> supports forced tool use.**

The two 400s have separate causes, neither involving `tool_choice`:

- `type: "enabled"` — *"Claude 4.7 and later models do not support it and reject
  requests that use it, returning a 400 error."* Opus 5 rejects the mode outright.
- `type: "disabled"` — *"On Claude Opus 5, thinking cannot be disabled at `xhigh`
  or `max` effort."* A 400 only at those effort levels.

**This repo already proves it without knowing it.** Claude Code sends
`thinking: {type: "adaptive"}`, the proxy forwards it untouched, the forced
`tool_choice` goes out beside it, and capture works. Adaptive thinking next to a
forced tool call is the production path today. The docs and the running system
agree; only the written explanation is wrong.

**What follows.** There is no forced-tool-call barrier to controlling depth on the
hidden call. `ClaudeProtocol.supportsCapture` refusing when
`thinking.type == "enabled"` is still correct behaviour — but for a different
reason, and on Opus 5 the client cannot send `enabled` anyway.

Source: [Thinking](https://platform.claude.com/docs/en/build-with-claude/thinking),
[Extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)

---

## 1.2 Anthropic has an effort parameter

**`NOTE_PLAN.md` says:**

> Anthropic has no effort setting. Depth is set with `thinking`.

**It has one.** `output_config.effort`, five levels, no beta header:

```json
{
  "model": "claude-opus-5",
  "output_config": { "effort": "max" }
}
```

`low`, `medium`, `high` (the default), `xhigh`, `max`. Opus 5 and Sonnet 5 support
all five. It works with or without thinking, and it governs all output tokens
including tool calls — which is exactly what a workbook note is.

**Why this matters.** `NOTE_PLAN.md` concludes *"The Claude half cannot be
built"* — meaning the upstream trick of raising depth on the hidden call. It can
be built. It is one line.

Source: [Effort](https://platform.claude.com/docs/en/build-with-claude/effort)

---

## 1.3 The objection that closed that experiment does not apply here

`NOTE_PLAN.md` rules the effort change out on cost:

> A cheap change to the small request would quietly make the big one cost more.
> So it needs a measurement before it needs an implementation.

The mechanism is real and the docs confirm it: *"The thinking configuration and
the resolved `effort` level are rendered into the prompt itself, so changing any
of them starts a new cache prefix."* Raise effort on one call and not the other,
and the other one stops hitting cache.

**But this project does not optimise for cost.** So there is nothing to measure
first. The experiment is unblocked.

**The one residue worth keeping is latency, not money.** A cache miss means the
whole conversation is re-read before the first token comes back. The README
already warns that your answer starts later; this is the reason, and it grows with
conversation length. If a session starts feeling slow, this is the first thing to
check — not a bug.

**What stays rejected, unchanged.** The upstream project also turns the *visible*
answer down to minimal effort. That damages the user's real task and remains
something this project should deliberately never have. Raising the hidden call is
the only half on the table.

Source: [Thinking](https://platform.claude.com/docs/en/build-with-claude/thinking),
[Effort](https://platform.claude.com/docs/en/build-with-claude/effort)

---

# Part 2 — Hooks: where they help, and where they can't

Both harnesses ship hook systems that inject text into the model's context for
free. Claude Code fires `additionalContext` from `UserPromptSubmit`,
`PostToolUse`, `PostToolUseFailure`, `PostToolBatch`, `Stop`, `SubagentStop` and
`SessionStart`, and can rewrite tool input via `updatedInput` on `PreToolUse`.
Codex has its own set covering roughly the same lifecycle.

**The line is simple: hooks inject *into* the model. They cannot extract *from*
it.** Everything a hook can put in `additionalContext` is text you already had.
The workbook's whole premise is getting the model to produce something it was not
otherwise going to produce. No hook event does that.

Two specifics that matter here:

- **A hook's text lands in the transcript.** The tool wording promises the
  opposite — *"The note is not added to the transcript."* Hook-based capture would
  change what the client sees.
- **A hook *can* call the model**, by shelling out to `claude -p` on the same
  subscription. So a second-opinion feature is hook-doable. But it starts **cold**,
  with no conversation history. The proxy's hidden call carries the entire session
  verbatim, mid-turn, at the exact moment. A subshell can only approximate that.

**What the proxy is uniquely for**, then — three things:

1. It sees the wire: usage, `stop_reason`, refusals, the real request shape.
2. It can ask the model a question **with the full live conversation in context**.
3. One implementation covers both harnesses.

Anything outside those three should be a hook.

Source: [Claude Code hooks](https://code.claude.com/docs/en/hooks),
[Codex hooks](https://learn.chatgpt.com/docs/hooks)

---

# Part 3 — The plan, in order

Ordered by what produces knowledge soonest. **Items 1, 2 and 3 are built as of
2026-08-19** and are marked below; they now need a live run, not more code.
Items 4–5 are unblocked and unstarted.

## 1. Raise `effort` on the hidden call — BUILT, needs a live run

**Done:** `WORKBOOK_HIDDEN_EFFORT` sets `output_config.effort` on the hidden call
only. Values `INHERIT` (default, old behaviour), `LOW`, `MEDIUM`, `HIGH`, `XHIGH`,
`MAX`. See `provider/HiddenEffort.java`. The continuation keeps whatever depth the
client asked for — asserted by a test.

**Why:** it tests whether a model told to think harder writes a better note. That
is the most basic question this project has never asked, and it was closed on a
cost argument that does not apply here. Nothing blocks it and nothing else on this
list is cheaper to try.

**Watch for:** notes getting longer without getting better. Length is not the
goal — `note-style-log.md` already shows length is not the variable that matters.

## 2. Fix the three wrong statements — DONE

**Done:** `docs/note-style-log.md` and `NOTE_PLAN.md` now name the real causes, and
the `supportsCapture` javadoc in `ClaudeProtocol` says why the guard survives.

**Why:** they are steering decisions right now. One of them closed the experiment
in item 1 for months. Ten minutes of editing stops the repo misleading the next
person to read it, including you in three weeks.

## 3. Ask for a prediction instead of a note — BUILT, unverified

**Done:** `NoteStyle.PREDICTION` v1 ships beside `INTENT`, `DELTA` and `REASONING`,
with separate Claude and Codex wordings and an entry in `docs/note-style-log.md`.
**No live run yet**, so nothing says it captures.

**Why:** right now, judging a note means reading it, so nobody does. A prediction
can be marked right or wrong by machine, because the next request contains the
answer. It also sidesteps the refusal problem — a prediction is a claim about the
world, not about the model's own mind, which is the framing Opus 5 accepts.

## 4. Score how much the note adds over the visible answer

**Do:** count word overlap between each note and the answer that followed it, over
the archive you already have. Plot it. Nothing touches the live path.

**Why:** `NOTE_PLAN.md` has a stop/go gate — *"if neither is useful, stop here"* —
that is waiting on somebody reading a pile of notes, which means it never happens.
A crude number unblocks it this week. Crude is fine: the failure it needs to catch
is "the note restates the answer", and word overlap catches exactly that.

## 5. Put the previous note in the tool result

**Do:** replace the fixed `WORKBOOK_RESULT` string with the model's own last note.

**Why:** that string is free space the model already reads. Filling it turns the
workbook from a diary into a small memory. First thing to watch: do the notes stop
repeating themselves?

## 6. Record and replay

**Do:** an env-gated recorder writing request/response pairs, then an offline
runner over them.

**Why:** it makes testing decoder, scoring and dashboard changes a test run instead
of a live session. Not about money — about not needing a real task and a real
model every time you change a parser.

**Limit:** replay can never test a wording or a refusal. A canned response never
refuses. Wording work stays live, permanently.

## 7. Second opinion on turns the model flags as stuck

**Do:** once the note carries `confidence` and `stuck` (`NOTE_PLAN.md` step 4),
make one extra hidden call on low-confidence turns asking the model to argue the
opposite. Show both. Inject nothing back.

**Why:** the turn worth catching is the one where the agent commits to a wrong
approach while sounding certain. This fires only when the model itself admits
doubt. It is also the clearest thing on this list that a hook genuinely cannot do
well — it needs the full live conversation, which a `claude -p` subshell lacks.

**Blocked on:** `NOTE_PLAN.md` step 4.

## 8. Turn on Codex reasoning summaries and compare them to the note

**Do:** set `reasoning: {summary: "auto"}` on the Codex request the proxy already
rewrites. The summary comes back in the stream already being parsed — **no extra
round trip.**

**Why:** it is the only place where the model's own account of its work sits next
to a note written to *your* question, on the same turn. If they say the same
thing, the forced call is buying nothing on Codex — the most important negative
result this project could produce, and cheap to find.

**Honest limit:** raw reasoning is never readable; in stateless mode it comes back
as `encrypted_content`. Only the summary is legible, and only if you ask for it.

**Blocked on:** item 9.

## 9. Get one live Codex session on the board

**Do:** fund the account, run one session, append a Codex section to
`note-style-log.md`.

**Why:** every entry in that log is Claude. The Codex wordings have never met a
live model and may refuse without anyone knowing. Your own rule: *a wording with
no live entry is unverified, whatever the unit tests say.* This is a billing
problem gating two items above.

---

## Not doing, and why

**A refusal-probe rig.** The Opus 5 refusal finding is the most original thing here
and deserves writing up once. But refusal behaviour is a policy artifact, not a
capability — it changes with every model release and trends nowhere. Building
infrastructure around it means re-running the suite forever for findings that
expire. Write the finding; don't build the factory.

**A self-contradiction detector.** Cheap and revealing, but better models
contradict themselves less, and a `Stop` hook can do it without the proxy. Read one
long session by hand first; if there are no contradictions, there is nothing to
build.

**Editing the client's own tool results.** Still the most powerful thing the
proxy's position allows, and still ruled out: the agent behaves differently and the
user cannot see why. Recorded so it stops being re-proposed as new.

---

# Part 4 — Moved out of the proxy

Not cancelled. Cheaper and safer as hooks.

| Was | Now | Why |
|---|---|---|
| Enrich tool results | `PostToolUse` / `PostToolUseFailure` hook | Free, and a hook cannot corrupt a tool result mid-stream |
| Cross-session repo memory | `SessionStart` + `UserPromptSubmit` hook | Free, and it stays visible to the user — the objection in `NOTE_PLAN.md` |
| End-of-turn analysis | `Stop` hook | Fires once per turn and already has the transcript |

Rule going forward: **if a hook can do it, a hook should do it.** Spend the proxy
on the wire, the live-context second call, and cross-harness parity.

---

# Part 5 — Corrections owed to other files

Item 2 of the plan. Three statements are wrong and currently steering decisions:

- **`docs/note-style-log.md`**, "Dead ends outside the wording" — attributes both
  400s to the forced `tool_choice`. Neither is caused by it. Replace with the two
  real causes from 1.1, and note that adaptive already runs beside a forced call
  in production.
- **`NOTE_PLAN.md`**, reasoning-effort section — "Anthropic has no effort setting"
  is false. `output_config.effort` exists on Opus 5 and Sonnet 5.
- **`NOTE_PLAN.md`**, same section — "The Claude half cannot be built" follows from
  the above. It can be built, it is one line, and it is item 1 of this plan.

Memory entry `forcing-thinking-on-the-hidden-call-returns-400` recorded the same
wrong cause and has been corrected.

---

*Gate for any code change, unchanged:*

```sh
./mvnw -q -o clean verify
./mvnw -q -o -Pbrowser-e2e verify
```

*`browser-e2e` currently runs zero tests — there is no `BrowserE2EIT.java`. It is
not evidence for a dashboard change.*

## Sources

- [Thinking](https://platform.claude.com/docs/en/build-with-claude/thinking)
- [Extended thinking](https://platform.claude.com/docs/en/build-with-claude/extended-thinking)
- [Effort](https://platform.claude.com/docs/en/build-with-claude/effort)
- [Claude Code hooks](https://code.claude.com/docs/en/hooks)
- [OpenAI reasoning](https://developers.openai.com/api/docs/guides/reasoning)
- [Codex hooks](https://learn.chatgpt.com/docs/hooks)
- [Codex config reference](https://learn.chatgpt.com/docs/config-file/config-reference)
