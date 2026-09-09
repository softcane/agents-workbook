package io.github.softcane.workbook.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConversationStateStoreTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void isolatesProvidersAndSessionsWhileReplayingBoundariesInSequence() {
        var store = store(true, true, 8, 8, 1_000_000, Duration.ofMinutes(30), Clock.systemUTC());
        var key = key(ProxyProvider.CODEX, "session-a");
        var opening = store.begin(key, "request-0", ConversationStateStore.RequestKind.OPENING,
                Set.of(), Set.of());
        assertThat(opening.decision()).isEqualTo(ConversationStateStore.Decision.READY);
        assertThat(store.commitTool(opening.lease(), "source-0", List.of(exchange("wb-0")),
                "item-0", Set.of("real-0"))).isEqualTo(ConversationStateStore.Decision.READY);

        var second = store.begin(key, "request-1", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("real-0"), Set.of("real-0"));
        assertThat(second.replay()).extracting(ConversationStateStore.Boundary::sequence).containsExactly(1L);
        assertThat(store.commitTool(second.lease(), "source-1", List.of(exchange("wb-1")),
                "item-1", Set.of("real-1"))).isEqualTo(ConversationStateStore.Decision.READY);

        var third = store.begin(key, "request-2", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("real-1"), Set.of("real-0", "real-1"));
        assertThat(third.replay()).extracting(ConversationStateStore.Boundary::sequence).containsExactly(1L, 2L);
        assertThat(store.begin(key(ProxyProvider.CODEX, "session-b"), "other",
                ConversationStateStore.RequestKind.TOOL_RESULTS, Set.of("real-1"), Set.of("real-1")).decision())
                .isEqualTo(ConversationStateStore.Decision.MISSING_STATE);
        assertThat(store.begin(key(ProxyProvider.CLAUDE, "session-a"), "other-provider",
                ConversationStateStore.RequestKind.TOOL_RESULTS, Set.of("real-1"), Set.of("real-1")).decision())
                .isEqualTo(ConversationStateStore.Decision.MISSING_STATE);
    }

    @Test
    void invalidatesExactRetriesConcurrentRequestsAndTheirStaleLeases() {
        var store = store(true, false, 8, 8, 1_000_000, Duration.ofMinutes(30), Clock.systemUTC());
        var key = key(ProxyProvider.CODEX, "session");
        var first = store.begin(key, "same", ConversationStateStore.RequestKind.OPENING, Set.of(), Set.of());
        assertThat(store.begin(key, "same", ConversationStateStore.RequestKind.OPENING,
                Set.of(), Set.of()).decision()).isEqualTo(ConversationStateStore.Decision.DUPLICATE_REQUEST);
        assertThat(store.commitTool(first.lease(), "source", List.of(exchange("wb")), "item", Set.of("real")))
                .isEqualTo(ConversationStateStore.Decision.CONCURRENT_REQUEST);

        var next = store.begin(key, "one", ConversationStateStore.RequestKind.OPENING, Set.of(), Set.of());
        assertThat(store.begin(key, "two", ConversationStateStore.RequestKind.OPENING,
                Set.of(), Set.of()).decision()).isEqualTo(ConversationStateStore.Decision.CONCURRENT_REQUEST);
        assertThat(store.commitTool(next.lease(), "source", List.of(exchange("wb")), "item", Set.of("real")))
                .isEqualTo(ConversationStateStore.Decision.CONCURRENT_REQUEST);
    }

    @Test
    void requiresTheCompleteNewestParallelResultBatchAndAllOlderResults() {
        var store = store(true, false, 8, 8, 1_000_000, Duration.ofMinutes(30), Clock.systemUTC());
        var key = key(ProxyProvider.CODEX, "session");
        var opening = store.begin(key, "r0", ConversationStateStore.RequestKind.OPENING, Set.of(), Set.of());
        store.commitTool(opening.lease(), "s0", List.of(exchange("wb0")), "item0", Set.of("a", "b"));
        assertThat(store.begin(key, "r1", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("a"), Set.of("a")).decision())
                .isEqualTo(ConversationStateStore.Decision.INCOMPLETE_RESULTS);
        assertThat(store.begin(key, "r2", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("a", "b"), Set.of("a", "b")).decision())
                .isEqualTo(ConversationStateStore.Decision.MISSING_STATE);

        var restarted = store.begin(key, "r3", ConversationStateStore.RequestKind.OPENING, Set.of(), Set.of());
        store.commitTool(restarted.lease(), "s3", List.of(exchange("wb3")), "item3", Set.of("a"));
        var continuation = store.begin(key, "r4", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("a"), Set.of("a"));
        store.commitTool(continuation.lease(), "s4", List.of(exchange("wb4")), "item4", Set.of("b"));
        assertThat(store.begin(key, "r5", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("b"), Set.of("b")).decision())
                .isEqualTo(ConversationStateStore.Decision.INCOMPLETE_RESULTS);
    }

    @Test
    void expiresIdleTurnsAndRejectsTheirOutstandingLeases() {
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var store = store(true, false, 8, 8, 1_000_000, Duration.ofMinutes(5), clock);
        var key = key(ProxyProvider.CODEX, "session");
        var opening = store.begin(key, "r0", ConversationStateStore.RequestKind.OPENING, Set.of(), Set.of());
        clock.advance(Duration.ofMinutes(6));
        assertThat(store.begin(key, "r1", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("real"), Set.of("real")).decision()).isEqualTo(ConversationStateStore.Decision.EXPIRED);
        assertThat(store.commitTool(opening.lease(), "source", List.of(exchange("wb")), "item", Set.of("real")))
                .isEqualTo(ConversationStateStore.Decision.CONCURRENT_REQUEST);
    }

    @Test
    void enforcesSessionBoundaryAndGlobalByteLimitsWithoutEvictingAnotherSession() {
        var sessions = store(true, false, 1, 8, 1_000_000, Duration.ofMinutes(30), Clock.systemUTC());
        assertThat(sessions.begin(key(ProxyProvider.CODEX, "a"), "a", ConversationStateStore.RequestKind.OPENING,
                Set.of(), Set.of()).decision()).isEqualTo(ConversationStateStore.Decision.READY);
        assertThat(sessions.begin(key(ProxyProvider.CODEX, "b"), "b", ConversationStateStore.RequestKind.OPENING,
                Set.of(), Set.of()).decision()).isEqualTo(ConversationStateStore.Decision.SESSION_LIMIT);

        var boundaries = store(true, false, 8, 1, 1_000_000, Duration.ofMinutes(30), Clock.systemUTC());
        var boundaryKey = key(ProxyProvider.CODEX, "boundary");
        var first = boundaries.begin(boundaryKey, "r0", ConversationStateStore.RequestKind.OPENING,
                Set.of(), Set.of());
        boundaries.commitTool(first.lease(), "s0", List.of(exchange("wb0")), "item0", Set.of("real0"));
        var second = boundaries.begin(boundaryKey, "r1", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("real0"), Set.of("real0"));
        assertThat(boundaries.commitTool(second.lease(), "s1", List.of(exchange("wb1")),
                "item1", Set.of("real1"))).isEqualTo(ConversationStateStore.Decision.BOUNDARY_LIMIT);
        assertThat(boundaries.begin(boundaryKey, "r2", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("real1"), Set.of("real0", "real1")).decision())
                .isEqualTo(ConversationStateStore.Decision.MISSING_STATE);

        var bytes = store(true, false, 8, 8, 180, Duration.ofMinutes(30), Clock.systemUTC());
        var byteA = bytes.begin(key(ProxyProvider.CODEX, "byte-a"), "a", ConversationStateStore.RequestKind.OPENING,
                Set.of(), Set.of());
        assertThat(bytes.commitTool(byteA.lease(), "s", List.of(exchangeWithResult("wa", "x".repeat(40))),
                "ia", Set.of("ra"))).isEqualTo(ConversationStateStore.Decision.READY);
        var byteB = bytes.begin(key(ProxyProvider.CODEX, "byte-b"), "b", ConversationStateStore.RequestKind.OPENING,
                Set.of(), Set.of());
        assertThat(bytes.commitTool(byteB.lease(), "s", List.of(exchangeWithResult("wb", "x".repeat(100))),
                "ib", Set.of("rb"))).isEqualTo(ConversationStateStore.Decision.BYTE_LIMIT);
        assertThat(bytes.begin(key(ProxyProvider.CODEX, "byte-a"), "a1",
                ConversationStateStore.RequestKind.TOOL_RESULTS, Set.of("ra"), Set.of("ra")).decision())
                .isEqualTo(ConversationStateStore.Decision.READY);
    }

    @Test
    void finalAnswerCleanupAndDisabledProvidersLeaveNoReplayableState() {
        var store = store(true, false, 8, 8, 1_000_000, Duration.ofMinutes(30), Clock.systemUTC());
        var key = key(ProxyProvider.CODEX, "session");
        var opening = store.begin(key, "r0", ConversationStateStore.RequestKind.OPENING, Set.of(), Set.of());
        store.commitFinal(opening.lease());
        assertThat(store.begin(key, "r1", ConversationStateStore.RequestKind.TOOL_RESULTS,
                Set.of("real"), Set.of("real")).decision()).isEqualTo(ConversationStateStore.Decision.MISSING_STATE);
        assertThat(store.begin(key(ProxyProvider.CLAUDE, "disabled"), "r0",
                ConversationStateStore.RequestKind.OPENING, Set.of(), Set.of()).decision())
                .isEqualTo(ConversationStateStore.Decision.DISABLED);
    }

    private ConversationStateStore store(boolean codex, boolean claude, int sessions, int boundaries,
            long bytes, Duration timeout, Clock clock) {
        return new ConversationStateStore(json,
                new ConversationStateStore.Settings(codex, claude, sessions, boundaries, bytes, timeout), clock);
    }

    private ConversationStateStore.Exchange exchange(String callId) {
        return exchangeWithResult(callId, "continue");
    }

    private ConversationStateStore.Exchange exchangeWithResult(String callId, String result) {
        return new ConversationStateStore.Exchange(json.createArrayNode().add(json.createObjectNode()
                .put("type", "function_call").put("call_id", callId)), callId, result);
    }

    private static ConversationStateStore.Key key(ProxyProvider provider, String identity) {
        return new ConversationStateStore.Key(provider, identity);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }

        private void advance(Duration duration) { instant = instant.plus(duration); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
