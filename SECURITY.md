# Security

## Local boundary

Docker Compose publishes the dashboard, provider listener, internal listener, and Envoy admin endpoint on `127.0.0.1` only. The Java dashboard also defaults to loopback outside Compose. Do not expose these ports to another host: the dashboard contains model-written workbook text and has no authentication layer.

The production route is fail-closed with Envoy `failure_mode_allow: false`: if the Java service is unreachable, the provider request does not silently bypass the proxy. That is a transport guarantee, not a capture guarantee — inside Java, capture itself fails open (see **Failure policy**).

## Authentication and network traffic

Authentication belongs to the normal clients:

- Codex forwards its existing ChatGPT subscription headers.
- Claude Code forwards its existing Claude.ai OAuth headers.
- The proxy does not read an OpenAI or Anthropic API key.

Headers and bodies are forwarded in memory. Application code does not log request headers, prompts, provider bodies, real-tool inputs, cookies, or authorization values. Envoy validates upstream TLS with the system CA store and an exact DNS SAN for `chatgpt.com` or `api.anthropic.com`.

The internal Envoy listener is published only for local diagnostics. Provider selection is set by Java after exact public-route validation; callers cannot choose an arbitrary upstream host.

## Trace data

A trace row holds twelve fields and nothing else: provider, a generated session ID, a generated request ID, the tool-call ID, a sequence number, an event type, a timestamp, the decoded `work` delta, a SHA-256 of the finished note, the client-selected model name, and the provider's own input and output token counts. Prompts, credentials, real-tool arguments, real-tool results, and any provider reasoning outside the workbook call never enter it.

A row's `status` comes from a fixed set (`complete`, `cancelled`, `capture_timeout`, `capture_skipped`, `provider_error`, `unsupported_capture`, `capture_loop_exhausted`) or, when an exception ends the turn, that exception's class name. The class name goes in. Its message does not.

Both IDs the dashboard shows you are random UUIDs minted when the turn starts, so neither derives from anything your client sent. Grouping turns into one conversation happens through a separate key that stays in memory and never appears on the dashboard or in the archive:

- Codex sends `previous_response_id`, and the proxy stores that provider-issued value raw as the index key.
- Claude Code sends a session ID to its gateway. The proxy hashes it (SHA-256) with a per-process random salt and the agent ID. The salt makes the key meaningless in another process; the agent ID keeps a subagent's turns off the root timeline. An agent ID names an agent, never a person.
- A client that sends neither falls back to a SHA-256 of the first resent user message, unsalted. The proxy compares hashes and keeps no raw text.

Two unrelated conversations that both open with "hi" hash alike, so a content-hash match only counts inside a rolling 24-hour window that refreshes on every turn of a live conversation. Past that window the turn starts a fresh, ungrouped session instead of merging into an old one. Each index caps at 500 entries. Session titles come from the model's own `work` text, never from your prompt.

The proxy also reads the client-visible continuation for two things: the name of the next real tool the model calls (`Bash`, `run_js`), which the dashboard shows in place of the workbook step, and the provider's token counts, which it adds to the turn's running total. It never reads that call's arguments or results, and it never delays, buffers, or alters a byte you receive. A malformed frame, or a provider that reports no usage counts, costs a dashboard row and nothing else.

Memory holds the newest 50 turns by default. The dashboard renders model text through `textContent`, so a note cannot inject markup.

The workbook prompt asks the model to leave secrets out. Treat that as a request, keep credentials out of your prompts, and treat the dashboard as sensitive.

## Persistence

Leave `WORKBOOK_ARCHIVE_PATH` unset and nothing reaches disk.

Set it and the proxy appends one JSONL row per finished turn, carrying the columns the dashboard already shows: provider, session and request IDs, tool-call ID, status, start and finish times, model name, token counts, the observed tool names, the note text, and the note's SHA-256. Prompts, visible assistant content, headers, and real-tool arguments or results stay out.

Check the file mode yourself rather than trusting the default. The proxy creates the file and then narrows it to `0600`; if the filesystem rejects POSIX permissions it logs a warning and keeps writing. It applies `0700` to the parent directory only when it creates that directory. Point the archive at a directory that already exists and the proxy leaves that directory's mode alone. The shipped image avoids the gap by building `/var/lib/workbook` as `0700` owned by UID 65532 (see `Dockerfile`). Run the jar outside Docker and the decision is yours.

Retention on disk is not retention in memory. The dashboard keeps 50 turns; the file keeps every turn until it reaches 64 MiB, rolls once to `.1`, and starts fresh, which puts the ceiling at two files and about 128 MiB. A restart reloads the newest 50 rows into the sidebar and leaves the older ones sitting in the file.

A crash part-way through an append leaves one torn line. The reader skips it, logs how many rows it skipped, and never logs the row. Writes run off the request path and fail open, so a full or unwritable disk costs an archived row and never your task.

A leaked secret persists on disk, which makes the caveat above matter more here than it does in memory.

## Failure policy

Capture is best effort and fails open. When the hidden workbook exchange cannot finish for any reason (an unsupported request shape, a deadline, a missing or malformed call, a provider refusal), the proxy sends your client's own unmodified request and forwards whatever the provider answers. The dashboard marks the turn untraced. Your real task always completes.

Falling open costs a second call. The hidden call's tokens are already spent by the time the proxy gives up, and your original request then goes upstream carrying the full history again. A failed capture roughly doubles the turn rather than saving it.

Errors on the hidden call never reach you. A 429 or a 500 there ends capture, the proxy drops that response body, and your own request goes out instead. You see the result of that retry, not the refusal behind it.

Your first byte can be four minutes away on the Claude path. The initial hidden call gets 60 seconds. Each of the three private attempts that can follow gets its own fresh 60 seconds, because the model may answer the tool result with another note and the proxy folds that in rather than forwarding it. That budget covers capture alone: after 240 seconds the proxy gives up and only then sends your original request, so silence can run past 240 seconds against Claude Code's 300-second stream watchdog. SSE keepalives do not cover this window. They start once real bytes go out, and no byte goes out until the proxy commits your response.

Once your response has started, no path re-sends or switches it.

Provider status codes and error bodies on the client-visible call go through unmodified. Claude Code reads upstream error wording to decide whether to retry with a capability disabled, and a proxy envelope would break that recovery. Local failures are the exception and name themselves: `proxy_busy` when eight intercepted requests are already running, `proxy_upstream_error` when the exchange throws.

Routing, request validation, size limits, and authentication fail closed.

Logs carry an exception's type and its own top-level message, never a cause chain and never a stack trace. One dependency is worth knowing: when Jackson raises that top-level exception, the message stays clean because Jackson 3 disables `StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` by default and prints `[Source: REDACTED]` in place of the body. Nothing in this repository pins or tests that default.

## Resource and cancellation controls

- Intercepted requests are bounded at 64 MiB after gzip decoding.
- At most eight intercepted requests run concurrently.
- Visible workbook output is bounded at 256 KiB.
- A downstream disconnect interrupts the active provider exchange and retains an already-emitted partial trace.
- The hidden workbook call has its own 60-second deadline, enforced both before response headers arrive and between chunks after them. Each private attempt that follows gets a fresh 60 seconds, so capture can hold a Claude turn silent for 240 seconds before the fallback request goes out (see **Failure policy**).
- At most two extra private workbook calls are folded into a turn before the client's own request is sent instead.
- The ungated part of a client-visible response is held in memory up to 1 MiB and never spilled to disk.
- Unsupported encodings, malformed JSON, and reserved tool collisions return structured local errors. Non-success provider responses do not: they are forwarded verbatim (see **Failure policy**).

The Java container runs as UID/GID 65532. Envoy and the Java runtime images are digest-pinned. Review and update both digests deliberately when upgrading.
