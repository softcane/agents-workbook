package io.github.softcane.workbook.proxy;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded, process-local workbook history for one active provider turn. Dashboard traces deliberately do
 * not participate: this store contains model-context data and has a shorter, independently bounded life.
 */
@Component
public final class ConversationStateStore {
    public record Settings(boolean codexEnabled, boolean claudeEnabled, int maximumSessions,
            int maximumBoundariesPerSession, long maximumBytes, Duration idleTimeout) {
        public static final Settings DEFAULTS = new Settings(false, false, 64, 32, 33_554_432,
                Duration.ofMinutes(30));

        public Settings {
            if (maximumSessions < 1) throw new IllegalArgumentException("maximumSessions must be positive");
            if (maximumBoundariesPerSession < 1) {
                throw new IllegalArgumentException("maximumBoundariesPerSession must be positive");
            }
            if (maximumBytes < 1) throw new IllegalArgumentException("maximumBytes must be positive");
            if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
                throw new IllegalArgumentException("idleTimeout must be positive");
            }
        }
    }

    public record Key(ProxyProvider provider, String identityHash) {
        public Key {
            if (provider == null) throw new IllegalArgumentException("provider must not be null");
            if (identityHash == null || identityHash.isBlank()) {
                throw new IllegalArgumentException("identityHash must not be blank");
            }
        }
    }

    public enum RequestKind { OPENING, TOOL_RESULTS, UNSUPPORTED }

    public record RequestContext(RequestKind kind, Set<String> trailingResultIds, Set<String> historyResultIds) {
        public RequestContext {
            if (kind == null) throw new IllegalArgumentException("kind must not be null");
            trailingResultIds = immutableSet(trailingResultIds);
            historyResultIds = immutableSet(historyResultIds);
        }
    }

    public record ToolCall(String id, String name) { }

    public record Exchange(ArrayNode providerOutput, String workbookCallId, String workbookResult) {
        public Exchange {
            if (providerOutput == null) throw new IllegalArgumentException("providerOutput must not be null");
            providerOutput = providerOutput.deepCopy();
            if (workbookCallId == null || workbookCallId.isBlank()) {
                throw new IllegalArgumentException("workbookCallId must not be blank");
            }
            if (workbookResult == null) throw new IllegalArgumentException("workbookResult must not be null");
        }

        @Override public ArrayNode providerOutput() { return providerOutput.deepCopy(); }
    }

    public record Boundary(long sequence, String requestFingerprint, String sourceFingerprint,
            List<Exchange> exchanges, String insertionAnchorId, Set<String> realToolCallIds,
            long serializedBytes) {
        public Boundary {
            exchanges = List.copyOf(exchanges);
            realToolCallIds = immutableSet(realToolCallIds);
        }
    }

    public enum Decision {
        READY, DISABLED, DUPLICATE_REQUEST, CONCURRENT_REQUEST, MISSING_STATE, EXPIRED,
        INCOMPLETE_RESULTS, SESSION_LIMIT, BOUNDARY_LIMIT, BYTE_LIMIT
    }

    /** Opaque capability for committing exactly the request generation accepted by {@link #begin}. */
    public static final class Lease {
        private final Key key;
        private final long generation;
        private final String token;
        private final String requestFingerprint;

        private Lease(Key key, long generation, String requestFingerprint) {
            this.key = key;
            this.generation = generation;
            this.requestFingerprint = requestFingerprint;
            this.token = UUID.randomUUID().toString();
        }
    }

    public record BeginResult(Decision decision, Lease lease, List<Boundary> replay) {
        public BeginResult {
            replay = replay == null ? List.of() : List.copyOf(replay);
        }
    }

    public record RestoreResult(ObjectNode request, int insertedBoundaryCount, String skipReason) { }

    private static final class Session {
        private final long generation;
        private final List<Boundary> boundaries = new ArrayList<>();
        private Instant touched;
        private Lease inFlight;
        private long bytes;
        private long nextSequence = 1;

        private Session(long generation, Instant touched) {
            this.generation = generation;
            this.touched = touched;
        }
    }

    private final ObjectMapper json;
    private final Settings settings;
    private final Clock clock;
    private final Map<Key, Session> sessions = new LinkedHashMap<>();
    private long nextGeneration = 1;
    private long totalBytes;

    public ConversationStateStore(ObjectMapper json) {
        this(json, Settings.DEFAULTS, Clock.systemUTC());
    }

    @Autowired
    public ConversationStateStore(ObjectMapper json,
            @Value("${workbook-proxy.continuity-codex-enabled:false}") boolean codexEnabled,
            @Value("${workbook-proxy.continuity-claude-enabled:false}") boolean claudeEnabled,
            @Value("${workbook-proxy.continuity-maximum-sessions:64}") int maximumSessions,
            @Value("${workbook-proxy.continuity-maximum-boundaries-per-session:32}")
                    int maximumBoundariesPerSession,
            @Value("${workbook-proxy.continuity-maximum-bytes:33554432}") long maximumBytes,
            @Value("${workbook-proxy.continuity-idle-timeout:PT30M}") Duration idleTimeout) {
        this(json, new Settings(codexEnabled, claudeEnabled, maximumSessions,
                maximumBoundariesPerSession, maximumBytes, idleTimeout), Clock.systemUTC());
    }

    public ConversationStateStore(ObjectMapper json, Settings settings, Clock clock) {
        this.json = json;
        this.settings = settings;
        this.clock = clock;
    }

    public synchronized boolean enabled(ProxyProvider provider) {
        pruneExpired();
        return provider == ProxyProvider.CODEX ? settings.codexEnabled() : settings.claudeEnabled();
    }

    public synchronized BeginResult begin(Key key, String requestFingerprint, RequestKind kind,
            Set<String> trailingResultIds, Set<String> historyResultIds) {
        boolean expired = pruneExpired().contains(key);
        if (!enabledWithoutPrune(key.provider())) return result(Decision.DISABLED);
        if (kind == RequestKind.UNSUPPORTED) return result(Decision.MISSING_STATE);

        Session session = sessions.get(key);
        if (session != null && session.inFlight != null) {
            Decision decision = session.inFlight.requestFingerprint.equals(requestFingerprint)
                    ? Decision.DUPLICATE_REQUEST : Decision.CONCURRENT_REQUEST;
            remove(key);
            return result(decision);
        }
        if (session != null && session.boundaries.stream()
                .anyMatch(boundary -> boundary.requestFingerprint().equals(requestFingerprint))) {
            remove(key);
            return result(Decision.DUPLICATE_REQUEST);
        }

        if (kind == RequestKind.OPENING) {
            if (session != null) remove(key);
            if (sessions.size() >= settings.maximumSessions()) return result(Decision.SESSION_LIMIT);
            session = new Session(nextGeneration++, clock.instant());
            sessions.put(key, session);
        } else {
            if (session == null) return result(expired ? Decision.EXPIRED : Decision.MISSING_STATE);
            if (session.boundaries.isEmpty()) {
                remove(key);
                return result(Decision.MISSING_STATE);
            }
            Boundary newest = session.boundaries.getLast();
            if (!trailingResultIds.containsAll(newest.realToolCallIds())) {
                remove(key);
                return result(Decision.INCOMPLETE_RESULTS);
            }
            for (int index = 0; index < session.boundaries.size() - 1; index++) {
                if (!historyResultIds.containsAll(session.boundaries.get(index).realToolCallIds())) {
                    remove(key);
                    return result(Decision.INCOMPLETE_RESULTS);
                }
            }
        }

        var lease = new Lease(key, session.generation, requestFingerprint);
        session.inFlight = lease;
        session.touched = clock.instant();
        return new BeginResult(Decision.READY, lease, List.copyOf(session.boundaries));
    }

    public synchronized Decision commitTool(Lease lease, String sourceFingerprint, List<Exchange> exchanges,
            String insertionAnchorId, Set<String> emittedToolIds) {
        pruneExpired();
        Session session = validSession(lease);
        if (session == null) return Decision.CONCURRENT_REQUEST;
        if (session.boundaries.size() >= settings.maximumBoundariesPerSession()) {
            remove(lease.key);
            return Decision.BOUNDARY_LIMIT;
        }
        var copiedExchanges = List.copyOf(exchanges);
        var copiedToolIds = immutableSet(emittedToolIds);
        long bytes = serializedBytes(lease.requestFingerprint, sourceFingerprint, copiedExchanges,
                insertionAnchorId, copiedToolIds);
        if (bytes > settings.maximumBytes() || totalBytes + bytes > settings.maximumBytes()) {
            remove(lease.key);
            return Decision.BYTE_LIMIT;
        }
        var boundary = new Boundary(session.nextSequence++, lease.requestFingerprint, sourceFingerprint,
                copiedExchanges, insertionAnchorId, copiedToolIds, bytes);
        session.boundaries.add(boundary);
        session.bytes += bytes;
        totalBytes += bytes;
        session.inFlight = null;
        session.touched = clock.instant();
        return Decision.READY;
    }

    public synchronized void commitFinal(Lease lease) {
        pruneExpired();
        if (validSession(lease) != null) remove(lease.key);
    }

    public synchronized void abort(Lease lease, boolean discardHistory) {
        pruneExpired();
        Session session = validSession(lease);
        if (session == null) return;
        if (discardHistory) remove(lease.key);
        else {
            session.inFlight = null;
            session.touched = clock.instant();
        }
    }

    public synchronized void discard(Key key) {
        pruneExpired();
        remove(key);
    }

    private Session validSession(Lease lease) {
        if (lease == null) return null;
        Session session = sessions.get(lease.key);
        if (session == null || session.generation != lease.generation || session.inFlight == null
                || !session.inFlight.token.equals(lease.token)) return null;
        return session;
    }

    private long serializedBytes(String requestFingerprint, String sourceFingerprint, List<Exchange> exchanges,
            String insertionAnchorId, Set<String> emittedToolIds) {
        long bytes = utf8(requestFingerprint) + utf8(sourceFingerprint) + utf8(insertionAnchorId);
        for (String id : emittedToolIds) bytes += utf8(id);
        for (Exchange exchange : exchanges) {
            bytes += json.writeValueAsBytes(exchange.providerOutput).length;
            bytes += utf8(exchange.workbookCallId) + utf8(exchange.workbookResult);
        }
        return bytes;
    }

    private Set<Key> pruneExpired() {
        Instant cutoff = clock.instant().minus(settings.idleTimeout());
        var expired = new LinkedHashSet<Key>();
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().touched.isBefore(cutoff)) {
                expired.add(entry.getKey());
                totalBytes -= entry.getValue().bytes;
                iterator.remove();
            }
        }
        return expired;
    }

    private void remove(Key key) {
        Session removed = sessions.remove(key);
        if (removed != null) totalBytes -= removed.bytes;
    }

    private boolean enabledWithoutPrune(ProxyProvider provider) {
        return provider == ProxyProvider.CODEX ? settings.codexEnabled() : settings.claudeEnabled();
    }

    private static BeginResult result(Decision decision) {
        return new BeginResult(decision, null, List.of());
    }

    private static Set<String> immutableSet(Set<String> values) {
        return values == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static int utf8(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
