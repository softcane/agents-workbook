package io.github.softcane.workbook.proxy.trace;

import io.github.softcane.workbook.proxy.Sha256;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public final class TraceStore {
    private static final int MAXIMUM_CORRELATION_ENTRIES = 500;

    /**
     * A content-hash correlation key (unlike a provider-issued {@code previous_response_id}) can collide
     * across two unrelated conversations that happen to open with the same text (e.g. both "hi"). Bounding
     * a match to this freshness window — refreshed on every hit — keeps that collision window small instead
     * of merging unrelated sessions for the life of the process; it does not affect an actively continuing
     * conversation, since each of its turns re-touches the entry.
     */
    private static final Duration FRESH_KEY_CORRELATION_WINDOW = Duration.ofHours(24);

    private final Object lock = new Object();
    private final TraceArchive archive;
    private final int maximumTraces;
    private final ArrayDeque<MutableTrace> traces = new ArrayDeque<>();
    private final Map<String, CorrelatedSession> sessionByCorrelationKey = boundedMap();
    private final Map<String, String> sessionByProviderResponseId = boundedMap();

    public TraceStore(int maximumTraces) {
        this(maximumTraces, TraceArchive.disabled());
    }

    @Autowired
    public TraceStore(@Value("${workbook-proxy.maximum-traces:50}") int maximumTraces, TraceArchive archive) {
        if (maximumTraces < 1) throw new IllegalArgumentException("maximumTraces must be positive");
        this.maximumTraces = maximumTraces;
        this.archive = archive;
        // Seeded oldest first, so the newest archived turn ends up at the head where a live one would.
        archive.tail(maximumTraces).forEach(snapshot -> traces.addFirst(MutableTrace.restored(snapshot)));
    }

    public TraceHandle start(String provider, String sessionId, String requestId) {
        var trace = new MutableTrace(provider, sessionId, requestId);
        synchronized (lock) {
            traces.addFirst(trace);
            while (traces.size() > maximumTraces) traces.removeLast();
        }
        return new TraceHandle(trace);
    }

    /**
     * Resolves the conversation this request belongs to. {@code previousResponseId} (Codex's stateful
     * continuation chain) takes priority; otherwise {@code freshCorrelationKey} (a hash of the first
     * resent user message, never the raw text) groups a fresh conversation's first turn with its later
     * ones. Falls back to {@code fallbackSessionId} — a new, ungrouped session — when neither resolves.
     */
    public String correlateSession(String freshCorrelationKey, String previousResponseId, String fallbackSessionId) {
        synchronized (lock) {
            if (previousResponseId != null) {
                String linked = sessionByProviderResponseId.get(previousResponseId);
                if (linked != null) return linked;
            }
            if (freshCorrelationKey != null) {
                var existing = sessionByCorrelationKey.get(freshCorrelationKey);
                boolean fresh = existing != null
                        && Duration.between(existing.seenAt(), Instant.now()).compareTo(FRESH_KEY_CORRELATION_WINDOW) <= 0;
                String sessionId = fresh ? existing.sessionId() : fallbackSessionId;
                sessionByCorrelationKey.put(freshCorrelationKey, new CorrelatedSession(sessionId, Instant.now()));
                return sessionId;
            }
            return fallbackSessionId;
        }
    }

    private record CorrelatedSession(String sessionId, Instant seenAt) { }

    private static <V> Map<String, V> boundedMap() {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAXIMUM_CORRELATION_ENTRIES;
            }
        };
    }

    public List<TraceSnapshot> snapshot() {
        synchronized (lock) {
            return traces.stream().map(MutableTrace::snapshot).toList();
        }
    }

    public Flux<TraceEvent> liveEvents() {
        return Flux.defer(() -> {
            var cursors = currentCursors();
            return Flux.interval(Duration.ZERO, Duration.ofMillis(25))
                    .onBackpressureLatest()
                    .concatMap(ignored -> Flux.fromIterable(eventsAfter(cursors)));
        });
    }

    public final class TraceHandle {
        private final MutableTrace trace;

        private TraceHandle(MutableTrace trace) {
            this.trace = trace;
        }

        /**
         * Every mutation runs under the store's single lock, which the live-event cursors read under too.
         * Passing the change in keeps that invariant in one place rather than restating it per method.
         */
        private void mutate(Consumer<MutableTrace> change) {
            synchronized (lock) {
                change.accept(trace);
            }
        }

        public void toolStarted(String toolCallId) {
            mutate(current -> {
                current.toolCallId = required(toolCallId, "toolCallId");
                current.add("workbook_started", "", "");
            });
        }

        /** Reassigns this trace to the resolved conversation; a no-op once already on that session. */
        public void assignSession(String sessionId) {
            if (sessionId == null || sessionId.isBlank()) return;
            mutate(current -> current.sessionId = sessionId);
        }

        public String sessionId() {
            synchronized (lock) {
                return trace.sessionId;
            }
        }

        public void setModel(String model) {
            mutate(current -> current.model = model == null ? "" : model);
        }

        /**
         * Adds tokens from one provider call to this turn's running total. Continuation usage arrives
         * after the workbook has completed, so publish a fresh event then instead of leaving live
         * dashboards with the workbook-only total.
         */
        public void addUsage(long inputTokens, long outputTokens) {
            long addedInput = Math.max(0, inputTokens);
            long addedOutput = Math.max(0, outputTokens);
            if (addedInput == 0 && addedOutput == 0) return;
            mutate(current -> {
                current.inputTokens += addedInput;
                current.outputTokens += addedOutput;
                if (!"running".equals(current.status)) current.add("usage_updated", "", "");
            });
        }

        /** Records a real tool name observed in the provider's continuation response (name only, no args/results). */
        public void toolCallObserved(String toolName) {
            if (toolName == null || toolName.isBlank()) return;
            mutate(current -> current.add("tool_call", toolName, ""));
        }

        /** Remembers this session under a provider-native response id so a later continuation can chain to it. */
        public void linkResponseId(String providerResponseId) {
            if (providerResponseId == null || providerResponseId.isBlank()) return;
            mutate(current -> sessionByProviderResponseId.put(providerResponseId, current.sessionId));
        }

        public void delta(String visibleDelta) {
            if (visibleDelta == null || visibleDelta.isEmpty()) return;
            mutate(current -> {
                current.visibleWork.append(visibleDelta);
                current.add("workbook_delta", visibleDelta, "");
            });
        }

        public void complete() {
            finish("complete", "workbook_complete");
        }

        public void cancel() {
            finish("cancelled", "cancelled");
        }

        public void fail(String code) {
            finish(code == null || code.isBlank() ? "failed" : code, "failed");
        }

        /** The first terminal outcome wins: a cancel after a complete is bookkeeping, not a new ending. */
        private void finish(String status, String eventType) {
            mutate(current -> {
                if (!"running".equals(current.status)) return;
                current.status = status;
                current.add(eventType, "", Sha256.of(current.visibleWork.toString()));
            });
        }

        /**
         * Records a turn the proxy deliberately forwarded unmodified. Deliberately not {@link #fail}:
         * the client's real task ran to completion, only the capture did not happen.
         */
        public void captureSkipped(String reason) {
            String checked = required(reason, "reason");
            mutate(current -> {
                // Notes already captured keep their completed status -- the fallback happened after them,
                // to the client's visible turn -- and the reason is recorded as an event either way.
                if ("running".equals(current.status)) current.status = checked;
                current.add("capture_skipped", checked, "");
            });
        }

        /**
         * Ends this turn's bookkeeping and hands the finished row to the archive, at most once. Called
         * when the exchange is over rather than when the notes are, so the row carries the real tool
         * names observed in the client-visible response that followed them.
         */
        public void finished() {
            TraceSnapshot finished;
            synchronized (lock) {
                if (trace.archived) return;
                trace.archived = true;
                if ("running".equals(trace.status)) return;
                finished = trace.snapshot();
            }
            archive.append(finished);
        }
    }

    private Map<String, Long> currentCursors() {
        synchronized (lock) {
            var cursors = new HashMap<String, Long>();
            traces.forEach(trace -> cursors.put(trace.requestId, trace.sequence));
            return cursors;
        }
    }

    private List<TraceEvent> eventsAfter(Map<String, Long> cursors) {
        synchronized (lock) {
            Set<String> retained = traces.stream()
                    .map(trace -> trace.requestId)
                    .collect(Collectors.toSet());
            cursors.keySet().retainAll(retained);
            var pending = traces.stream()
                    .flatMap(trace -> trace.events.stream())
                    .filter(event -> event.sequence() > cursors.getOrDefault(event.requestId(), 0L))
                    .sorted(Comparator.comparing(TraceEvent::timestamp)
                            .thenComparing(TraceEvent::requestId)
                            .thenComparingLong(TraceEvent::sequence))
                    .toList();
            pending.forEach(event -> cursors.merge(event.requestId(), event.sequence(), Math::max));
            return pending;
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static final class MutableTrace {
        private final String provider;
        private String sessionId;
        private final String requestId;
        private final Instant startedAt;
        private final StringBuilder visibleWork = new StringBuilder();
        private final List<TraceEvent> events = new ArrayList<>();
        private String toolCallId = "pending";
        private String status = "running";
        private String model = "";
        private long inputTokens;
        private long outputTokens;
        private long sequence;
        private boolean archived;

        private MutableTrace(String provider, String sessionId, String requestId) {
            this(provider, sessionId, requestId, Instant.now());
        }

        private MutableTrace(String provider, String sessionId, String requestId, Instant startedAt) {
            this.provider = required(provider, "provider");
            this.sessionId = required(sessionId, "sessionId");
            this.requestId = required(requestId, "requestId");
            this.startedAt = startedAt;
        }

        /** Rebuilds a finished turn read back from the archive; never re-archived, never mutated again. */
        private static MutableTrace restored(TraceSnapshot snapshot) {
            var trace = new MutableTrace(snapshot.provider(), snapshot.sessionId(), snapshot.requestId(),
                    snapshot.startedAt());
            trace.toolCallId = snapshot.toolCallId();
            trace.status = snapshot.status();
            trace.model = snapshot.model();
            trace.inputTokens = snapshot.inputTokens();
            trace.outputTokens = snapshot.outputTokens();
            trace.visibleWork.append(snapshot.visibleWork());
            trace.events.addAll(snapshot.events());
            trace.sequence = snapshot.events().size();
            trace.archived = true;
            return trace;
        }

        private TraceEvent add(String type, String delta, String hash) {
            var event = new TraceEvent(provider, sessionId, requestId, toolCallId, ++sequence,
                    type, Instant.now(), delta, hash, model, inputTokens, outputTokens);
            events.add(event);
            return event;
        }

        private TraceSnapshot snapshot() {
            return new TraceSnapshot(provider, sessionId, requestId, toolCallId, status, startedAt, model,
                    inputTokens, outputTokens, visibleWork.toString(), List.copyOf(events));
        }
    }
}
