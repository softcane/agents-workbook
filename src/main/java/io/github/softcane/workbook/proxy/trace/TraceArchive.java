package io.github.softcane.workbook.proxy.trace;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Optional JSONL record of finished turns, so the dashboard survives a restart instead of opening on an
 * empty sidebar. Disabled unless an operator names a path: {@code SECURITY.md} promises that nothing
 * reaches disk by default, and turning that off silently would break a stated property.
 *
 * <p>The archive holds exactly the rows the dashboard already shows and nothing wider — no prompts, no
 * visible assistant text, no headers, no real-tool arguments or results. Writes happen off the request
 * path and fail open: a full or unwritable disk costs an archived row, never a client's task.
 */
@Component
public final class TraceArchive {
    private static final Logger log = LoggerFactory.getLogger(TraceArchive.class);
    private static final String DIRECTORY_PERMISSIONS = "rwx------";
    private static final String FILE_PERMISSIONS = "rw-------";

    private final ObjectMapper json;
    private final Path path;
    private final Path rolled;
    private final long maximumBytes;
    private final ExecutorService writer;
    private final Object lock = new Object();

    public TraceArchive(
            ObjectMapper json,
            @Value("${workbook-proxy.archive-path:}") String archivePath,
            @Value("${workbook-proxy.archive-maximum-bytes:67108864}") long maximumBytes) {
        this.json = json;
        this.path = archivePath == null || archivePath.isBlank() ? null : Path.of(archivePath);
        this.rolled = path == null ? null : path.resolveSibling(path.getFileName() + ".1");
        this.maximumBytes = maximumBytes;
        this.writer = path == null
                ? null
                : Executors.newSingleThreadExecutor(Thread.ofVirtual().name("workbook-archive").factory());
        // The path itself stays out of the log: SECURITY.md's promise is about what reaches disk, and an
        // operator-named path is the one piece of configuration a reader can already see in their own env.
        log.info("trace archive {}: maximumBytes={}", path == null ? "disabled" : "enabled", maximumBytes);
    }

    /** An archive that never touches disk: the default configuration, and what tests construct. */
    public static TraceArchive disabled() {
        return new TraceArchive(new ObjectMapper(), "", 0);
    }

    public boolean enabled() {
        return path != null;
    }

    /** Queues one finished turn. Returns immediately; the caller is on a client's request thread. */
    public void append(TraceSnapshot snapshot) {
        if (path == null) return;
        String line;
        try {
            line = json.writeValueAsString(toRecord(snapshot)) + "\n";
        } catch (RuntimeException malformed) {
            log.warn("trace archive skipped a row it could not encode: error={}",
                    malformed.getClass().getSimpleName());
            return;
        }
        writer.execute(() -> write(line));
    }

    /**
     * The most recent finished turns, oldest first, for seeding the in-memory deque at boot. A torn final
     * line — a crash part-way through an append — is skipped rather than allowed to brick the dashboard.
     */
    public List<TraceSnapshot> tail(int maximum) {
        if (path == null || maximum < 1) return List.of();
        var recent = new ArrayDeque<TraceSnapshot>();
        synchronized (lock) {
            for (Path file : List.of(rolled, path)) {
                readInto(file, recent, maximum);
            }
        }
        return List.copyOf(recent);
    }

    private void readInto(Path file, ArrayDeque<TraceSnapshot> recent, int maximum) {
        if (!Files.isReadable(file)) return;
        var skipped = new AtomicInteger();
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                TraceSnapshot snapshot = decode(line);
                if (snapshot == null) {
                    if (!line.isBlank()) skipped.incrementAndGet();
                    return;
                }
                recent.addLast(snapshot);
                while (recent.size() > maximum) recent.removeFirst();
            });
        } catch (IOException | RuntimeException unreadable) {
            log.warn("trace archive tail stopped early: file={} error={}",
                    file.getFileName(), unreadable.getClass().getSimpleName());
        }
        // A count, never the line: an unparseable row is still a row of captured notes.
        if (skipped.get() > 0) {
            log.warn("trace archive skipped unreadable rows while restoring: file={} rows={}",
                    file.getFileName(), skipped.get());
        }
    }

    private TraceSnapshot decode(String line) {
        if (line.isBlank()) return null;
        try {
            JsonNode record = json.readTree(line);
            var toolNames = new ArrayList<String>();
            record.path("toolNames").forEach(name -> toolNames.add(name.asText()));
            return new TraceSnapshot(
                    record.path("provider").asText(),
                    record.path("sessionId").asText(),
                    record.path("requestId").asText(),
                    record.path("toolCallId").asText("pending"),
                    record.path("status").asText(),
                    Instant.parse(record.path("startedAt").asText()),
                    record.path("model").asText(""),
                    record.path("inputTokens").asLong(0),
                    record.path("outputTokens").asLong(0),
                    record.path("work").asText(""),
                    restoredEvents(record, toolNames));
        } catch (RuntimeException torn) {
            // A crash part-way through an append leaves one unparseable line; the rest of the file is fine.
            return null;
        }
    }

    /**
     * Enough of an event list for the dashboard to render a restored row the way it renders a live one:
     * the real tool names it observed, then one terminal event carrying the finish time and note hash.
     */
    private List<TraceEvent> restoredEvents(JsonNode record, List<String> toolNames) {
        String provider = record.path("provider").asText();
        String sessionId = record.path("sessionId").asText();
        String requestId = record.path("requestId").asText();
        String toolCallId = record.path("toolCallId").asText("pending");
        String model = record.path("model").asText("");
        long inputTokens = record.path("inputTokens").asLong(0);
        long outputTokens = record.path("outputTokens").asLong(0);
        Instant completedAt = Instant.parse(record.path("completedAt").asText());
        var events = new ArrayList<TraceEvent>();
        toolNames.forEach(name -> events.add(new TraceEvent(provider, sessionId, requestId, toolCallId,
                events.size() + 1L, "tool_call", completedAt, name, "", model, inputTokens, outputTokens)));
        String status = record.path("status").asText();
        String terminal = switch (status) {
            case "complete" -> "workbook_complete";
            case "cancelled" -> "cancelled";
            default -> "capture_skipped";
        };
        events.add(new TraceEvent(provider, sessionId, requestId, toolCallId, events.size() + 1L,
                terminal, completedAt, "", record.path("workSha256").asText(""),
                model, inputTokens, outputTokens));
        return List.copyOf(events);
    }

    private ObjectNode toRecord(TraceSnapshot snapshot) {
        var record = json.createObjectNode();
        record.put("provider", snapshot.provider());
        record.put("sessionId", snapshot.sessionId());
        record.put("requestId", snapshot.requestId());
        record.put("toolCallId", snapshot.toolCallId());
        record.put("status", snapshot.status());
        record.put("startedAt", snapshot.startedAt().toString());
        record.put("completedAt", completedAt(snapshot).toString());
        record.put("model", snapshot.model());
        record.put("inputTokens", snapshot.inputTokens());
        record.put("outputTokens", snapshot.outputTokens());
        var toolNames = json.createArrayNode();
        snapshot.events().stream()
                .filter(event -> "tool_call".equals(event.eventType()))
                .forEach(event -> toolNames.add(event.visibleDelta()));
        record.set("toolNames", toolNames);
        record.put("workSha256", snapshot.events().stream()
                .map(TraceEvent::finalSha256)
                .filter(hash -> !hash.isBlank())
                .reduce((first, last) -> last)
                .orElse(""));
        record.put("work", snapshot.visibleWork());
        return record;
    }

    private static Instant completedAt(TraceSnapshot snapshot) {
        return snapshot.events().stream()
                .map(TraceEvent::timestamp)
                .max(Comparator.naturalOrder())
                .orElse(snapshot.startedAt());
    }

    private void write(String line) {
        try {
            synchronized (lock) {
                prepare();
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                if (Files.exists(path) && Files.size(path) + bytes.length > maximumBytes) {
                    // Size, not age: no clock dependency and a hard two-file ceiling.
                    Files.move(path, rolled, StandardCopyOption.REPLACE_EXISTING);
                    createFile();
                }
                Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException unwritable) {
            // Fails open, and the exception's own type is all that is logged: a path or a wrapped parse
            // failure can carry content, which SECURITY.md forbids logging.
            log.warn("trace archive append failed, the turn stays unarchived: error={}",
                    unwritable.getClass().getSimpleName());
        }
    }

    private void prepare() throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent);
            trySetPermissions(parent, DIRECTORY_PERMISSIONS);
        }
        if (!Files.exists(path)) createFile();
    }

    private void createFile() throws IOException {
        Files.createFile(path);
        trySetPermissions(path, FILE_PERMISSIONS);
    }

    /**
     * The tool asks the model to keep secrets out of its notes, but that is a request, not a guarantee,
     * and on disk a leaked secret persists. Owner-only is the least this file can be.
     */
    private static void trySetPermissions(Path target, String permissions) {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(permissions));
        } catch (IOException | UnsupportedOperationException unsupported) {
            log.warn("trace archive could not restrict permissions on {}: error={}",
                    target.getFileName(), unsupported.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void close() {
        if (writer == null) return;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
