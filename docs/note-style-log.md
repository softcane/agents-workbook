# Note style log

`NoteStyle` carries the only text the model ever sees about the workbook tool, and that text decides
whether a capture happens at all. A wording that works today can start refusing after a provider
updates a model, so every wording gets a version and every version gets an entry here.

**When you change any string in a style:** bump its `wordingVersion`, never reuse a number, and append
an entry below with what a live run did. A wording with no live entry is unverified, whatever the unit
tests say.

**Where the version shows up at runtime:** the startup line (`noteStyle=REASONING/v2`), both capture
fallback log lines, and the `request.received` and `capture.provider_error` JSON diagnostics.

**The failure to watch for:** a model can answer a forced workbook call by opening it, writing nothing,
and ending the turn with `stop_reason: "refusal"`. That surfaces as the `capture_refused` reason and
the dashboard label "the model declined to write a note". It is a verdict on the wording, not a bug in
the proxy — no retry and no decoder change will move it.

---

## REASONING v2 — current, 2026-08-19

> Records the decision you are making and what it rules out, on the dashboard the user runs alongside
> this session, which is how they follow along with your work. Call it once before you answer, and
> again when another tool's result changes what you will do, then carry on with the task exactly as you
> otherwise would. The note is not added to the transcript, so keep each one self-contained and free of
> credentials or other secrets.
>
> *work:* The decision you are making, the alternatives you rejected, and what each one would have
> cost. Go long where the problem earns it. Plain text. Do not include secrets.

| Model | Effort | Result |
|---|---|---|
| claude-opus-5 | low | captured, 603 and 618 chars |
| claude-opus-5 | default | captured, 621, 1086 and 1117 chars |
| claude-sonnet-5 | default | captured, 216 and 399 chars |

Asks for the same substance as v1 — the options weighed, why the winner won — named as the decision
rather than as the model's thinking. Produces the longest notes any wording has produced here.

## REASONING v1 — retired, refused by Opus 5

> Records how you are working the problem through, on the dashboard the user runs alongside this
> session… Write the thinking itself rather than a description of it: what you are weighing, the
> options you ruled out, and why the approach you picked beat them…
>
> *work:* Your working-through of the problem in full rather than a summary of it. Go long where the
> problem earns it, around 12000 tokens. Do not include secrets.

| Model | Effort | Result |
|---|---|---|
| claude-opus-5 | low | **refused**, `stop_reason: refusal`, 0 chars, 9+ calls, no exceptions |
| claude-opus-5 | default | **refused**, 0 chars |
| claude-sonnet-5 | low and default | captured, 42-342 chars |

## INTENT v1

| Model | Effort | Result |
|---|---|---|
| claude-opus-5 | low | captured, 80 and 88 chars |

## DELTA v1

| Model | Effort | Result |
|---|---|---|
| claude-opus-5 | low | captured, 9 chars — its wording says to write "no change" when nothing changed, and on a first turn nothing has |

---

## PREDICTION v1 — added 2026-08-19, unverified

> Records what you expect to happen next, on the dashboard the user runs alongside this session, which
> is how they follow along with your work. Call it once before you act, then carry on with the task
> exactly as you otherwise would…
>
> *work:* What you expect the next tool call or command to return, stated concretely enough that it can
> turn out wrong, and the result that would make you change approach. If you are about to answer rather
> than act, say instead what you expect the user to push back on. Plain text. Do not include secrets.

Live through `127.0.0.1:10000`, one headless session per cell, same trivial task each time ("use ls,
then say how many files"). Output tokens are per captured note, from the dashboard.

| Model | `WORKBOOK_HIDDEN_EFFORT` | Result |
|---|---|---|
| claude-opus-5 | max | captured, 2 notes, 98 and 226 output tokens |
| claude-opus-5 | inherit | captured, 3 notes, 82 / 190 / 91 |
| claude-sonnet-5 | max | captured, 3 notes, 95 / 131 / 144 |
| claude-sonnet-5 | inherit | captured, 3 notes, 61 / 125 / 94 |
| claude-opus-4-8 | max | captured, 3 notes, 53 / 152 / 129 |
| claude-opus-4-8 | inherit | captured, 3 notes, 71 / 126 / 78 |
| claude-opus-4-7 | inherit | captured, 45 output tokens — the boundary model, first release to reject `thinking.type: enabled` |
| claude-fable-5 | max | untested — the account has no Fable credits; the provider returned 429 and capture failed open |
| claude-mythos-5 | inherit | untested — not available on this account; rows landed as `unsupported_capture` and `provider_error` |

**No refusals on any model.** `stop_reason` was `tool_use` on every captured call. This is the first
wording since `REASONING` v2 to clear Opus 5, and it cleared 4.8 and Sonnet 5 on the first attempt.

Asks for a claim the proxy can check itself rather than prose only a reader can judge: the next request
the client sends carries the result the claim was about. Like `REASONING` v2 the ask points at the
world, not at the model's own thinking, which is the distinction that decided whether Opus 5 answered a
forced call at all. The models used both branches of the ask unprompted -- an expectation before acting
("Running ls in the working directory. Expect a small number of files"), and the pushback branch before
answering ("Reporting 2 files. User may push back if they wanted hidden files counted").

## Hidden effort, first live run — 2026-08-19

`WORKBOOK_HIDDEN_EFFORT=MAX` sets `output_config.effort` on the hidden call only.

**What is proven.** No `400` on any model at `max`, so a forced `tool_choice` beside adaptive thinking
beside `effort: max` is a legal combination. Every `request.received` in this run logged
`thinkingType: adaptive`, which is the live disproof of the retired claim that a forced tool choice and
thinking cannot coexist.

**What is not proven.** Whether raising effort makes the note *better*. Per-capture output tokens were
higher at `max` on all three models -- roughly 162 vs 121 on Opus 5, 123 vs 93 on Sonnet 5, 111 vs 92 on
Opus 4.8 -- but that is one session per arm on a task with no depth to it. Direction only, not a result.
A real comparison needs a task where the plan can be wrong, and more than one session per arm.

**Closed the same day.** `Diagnostics.request.received` now carries `clientEffort` (what the client
asked for) and `hiddenEffort` (what this deployment overrides it with), so a note can be attributed to a
depth from the logs alone.

## Hidden effort, controlled run — 2026-08-20

The 2026-08-19 numbers below were measured on a task with no decision in it ("list two files"), one
session per arm. This is the replacement: a real debugging task, four models, both arms, clean trace
store per arm.

**Task.** A failing test whose obvious cause is wrong. `clean()` filters with `if r`, which silently
drops legitimate `0` readings; a second test passes coincidentally before the fix and still passes
after, for a different reason. The agent had to run the suite, find the cause, fix it, and re-run.

**Method.** `claude -p --permission-mode bypassPermissions`, identical prompt and pristine fixture per
session, container recreated between arms so each arm starts at zero traces.

| Model | arm | notes | avg chars | change |
|---|---|---|---|---|
| claude-opus-5 | inherit | 5 | 91 | |
| claude-opus-5 | xhigh | 5 | 227 | **+149%** |
| claude-sonnet-5 | inherit | 6 | 114 | |
| claude-sonnet-5 | xhigh | 6 | 185 | **+62%** |
| claude-opus-4-8 | inherit | 6 | 149 | |
| claude-opus-4-8 | xhigh | 6 | 142 | -5% |
| claude-opus-4-7 | inherit | 6 | 39 | |
| claude-opus-4-7 | xhigh | 6 | 64 | **+64%** |

Matched note counts per model per arm, zero `provider_error` rows in all eight cells, and all four
models solved the task in both arms.

**What is established.** Raising hidden effort lengthens notes on three of four models, reproducibly,
without costing a capture. Opus 4.8 did not move.

**What is not established: that the longer notes are better.** All eight cells named the real
mechanism, so the task did not discriminate on correctness. The one qualitative difference worth
recording is a single example, not a finding: at `xhigh` the Opus 5 note anticipated the fixture's red
herring --

> User might push back asking whether test_all_zero still passes for the right reason
> (average([0,0]) with non-empty list = 0.0 — confirmed by the run)

-- where its `inherit` counterpart wrote only "expect no pushback since both tests pass". Counting that
behaviour across all cells gives 2 of 4 at `xhigh` against 1 of 4 at `inherit`, which is one session per
cell and proves nothing. A real answer needs repeated sessions per cell and a task where models
actually fail.

**Also observed.** Headless `claude -p` sends `clientEffort: low` on its main calls and `high` on the
background title call. So under any override the note is written at greater depth than the answer it
describes -- worth remembering when reading these numbers.

## The `disabled` + `xhigh` trap — found live, 2026-08-19

Adding that field immediately exposed a bug in the effort override itself.

Claude Code's background title call sends `thinking: {"type": "disabled"}` on every session. Opus 5
rejects that beside `xhigh` or `max` effort. So `WORKBOOK_HIDDEN_EFFORT=XHIGH` made that turn's hidden
call return `400 invalid_request_error` every time, and its note was silently lost -- capture failed
open, exactly as designed, so nothing looked broken.

Same config, same model, same task, before and after the fix:

| | rejections | captures |
|---|---|---|
| before | 1 x `400 invalid_request_error` | 2 |
| after | none | 3 |

**The fix is a clamp, not a refusal.** A request that disables thinking is sent at `high` instead of
`xhigh`/`max`. A shallower note beats no note, and it is logged as
`{"event":"claude.effort.clamped","from":"xhigh","to":"high","reason":"thinking_disabled"}` so the arm
stays attributable. See `HiddenEffort.rejectedWithoutThinking`.

**Read this before trusting the effort table above.** The `max` arm in that table ran before the clamp
existed, so its Opus 5 row is two captures rather than three -- the missing one is this 400, not a
refusal.

## Wordings tried and not shipped

Kept because a negative result is what stops the next person re-running the same experiment. All on
claude-opus-5, 2026-08-19.

| Wording | Result |
|---|---|
| v1 minus "write the thinking itself", minus the 12000-token target | **refused** |
| "Write the working out rather than a verdict… several paragraphs where the problem earns it" | **refused** |
| DELTA's ask plus "Go long where the problem earns it" | captured, 215 and 251 chars |
| INTENT's short ask, reframed around "your reasoning" | main call captured (229 chars); Claude Code's background title call wrote 420 chars then refused mid-note |

What the four rule out: effort level is not the variable (v1 refused at both), length is not the
variable (DELTA + "go long" captured), and the word "reasoning" is not the variable (the short
reasoning ask captured on the main call). Asking the model to externalise its own thinking process is.

## Dead ends outside the wording

Setting `thinking` on the hidden call returns `400 invalid_request_error` in both directions —
`{"type":"enabled"}` and `{"type":"disabled"}` alike. Do not try to fix a refusal there.

**Corrected 2026-08-19: the cause is not the forced `tool_choice`.** That was written down here and in
`ClaudeProtocol.supportsCapture` and it was wrong. Checked against the Anthropic docs:

- Forced tool use is incompatible with *manual* extended thinking only. "Adaptive thinking, including
  on models where thinking is on by default, supports forced tool use." This proxy proves it every
  turn: Claude Code sends `{"type":"adaptive"}`, the proxy forwards it, and the forced `tool_choice`
  goes out beside it while capture works.
- `{"type":"enabled"}` is rejected by Claude 4.7 and later outright, tool choice or not.
- `{"type":"disabled"}` returns 400 on Opus 5 only at `xhigh` or `max` effort.

**What this opens.** There is no forced-tool-call barrier to controlling depth on the hidden call. The
lever is `output_config.effort`, not `thinking` — see `WORKBOOK_HIDDEN_EFFORT` and
`provider/HiddenEffort.java`.
