package io.github.softcane.workbook.proxy.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class TraceArchiveTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void writesNothingAtAllUntilAnOperatorNamesAPath(@TempDir Path directory) throws Exception {
        var archive = TraceArchive.disabled();
        var store = new TraceStore(50, archive);

        var trace = store.start("claude", "session-1", "request-1");
        trace.toolStarted("toolu_1");
        trace.delta("private notes");
        trace.complete();
        trace.finished();

        assertThat(archive.enabled()).isFalse();
        assertThat(archive.tail(50)).isEmpty();
        assertThat(Files.list(directory)).isEmpty();
    }

    @Test
    void restoresFinishedTurnsWithTheirNotesTokensAndToolNames(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("nested").resolve("traces.jsonl");
        var archive = new TraceArchive(json, file.toString(), 64 * 1024 * 1024);
        var store = new TraceStore(50, archive);

        var first = store.start("claude", "session-1", "request-1");
        first.setModel("claude-test");
        first.toolStarted("toolu_1");
        first.delta("first note");
        first.addUsage(100, 20);
        first.complete();
        first.toolCallObserved("Bash");
        first.finished();

        var second = store.start("claude", "session-1", "request-2");
        second.captureSkipped("unsupported_capture");
        second.finished();
        awaitLines(file, 2);
        archive.close();

        // Owner-only: the notes ask the model to keep secrets out, but that is a request, not a guarantee.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())))
                .isEqualTo("rwx------");

        var restored = new TraceStore(50, new TraceArchive(json, file.toString(), 64 * 1024 * 1024)).snapshot();
        assertThat(restored).hasSize(2);
        assertThat(restored.get(0).requestId()).isEqualTo("request-2");
        assertThat(restored.get(0).status()).isEqualTo("unsupported_capture");
        var reloaded = restored.get(1);
        assertThat(reloaded.requestId()).isEqualTo("request-1");
        assertThat(reloaded.status()).isEqualTo("complete");
        assertThat(reloaded.visibleWork()).isEqualTo("first note");
        assertThat(reloaded.model()).isEqualTo("claude-test");
        assertThat(reloaded.inputTokens()).isEqualTo(100);
        assertThat(reloaded.outputTokens()).isEqualTo(20);
        assertThat(reloaded.events()).extracting(TraceEvent::eventType)
                .containsExactly("tool_call", "workbook_complete");
        assertThat(reloaded.events().get(0).visibleDelta()).isEqualTo("Bash");
        assertThat(reloaded.events().get(1).finalSha256()).matches("^[0-9a-f]{64}$");
    }

    @Test
    void skipsATornFinalLineInsteadOfLosingTheWholeFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("traces.jsonl");
        var archive = new TraceArchive(json, file.toString(), 64 * 1024 * 1024);
        var store = new TraceStore(50, archive);
        var trace = store.start("claude", "session-1", "request-1");
        trace.delta("intact note");
        trace.complete();
        trace.finished();
        awaitLines(file, 1);
        archive.close();

        // A crash part-way through an append leaves a half-written last line.
        Files.writeString(file, "{\"provider\":\"claude\",\"sessionId\":\"sess",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        var restored = new TraceStore(50, new TraceArchive(json, file.toString(), 64 * 1024 * 1024)).snapshot();
        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).visibleWork()).isEqualTo("intact note");
    }

    @Test
    void rollsToASingleBackupOnceTheFileOutgrowsItsBudget(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("traces.jsonl");
        Path rolled = directory.resolve("traces.jsonl.1");
        // Small enough that the second row cannot share the file with the first.
        var archive = new TraceArchive(json, file.toString(), 400);
        var store = new TraceStore(50, archive);

        for (int index = 1; index <= 3; index++) {
            var trace = store.start("claude", "session-1", "request-" + index);
            trace.delta("note " + index + " " + "x".repeat(200));
            trace.complete();
            trace.finished();
            awaitContains(file, "note " + index);
        }
        archive.close();

        assertThat(Files.exists(rolled)).isTrue();
        assertThat(Files.size(file)).isLessThanOrEqualTo(600);
        // Worst case is two files, so the oldest row is gone and the newest two survive.
        var restored = new TraceStore(50, new TraceArchive(json, file.toString(), 400)).snapshot();
        assertThat(restored).extracting(TraceSnapshot::requestId).containsExactly("request-3", "request-2");
    }

    @Test
    void keepsOnlyTheRowsTheDashboardAlreadyShows(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("traces.jsonl");
        var archive = new TraceArchive(json, file.toString(), 64 * 1024 * 1024);
        var store = new TraceStore(50, archive);
        var trace = store.start("claude", "session-1", "request-1");
        trace.setModel("claude-test");
        trace.toolStarted("toolu_1");
        trace.delta("private notes");
        trace.complete();
        trace.toolCallObserved("Bash");
        trace.finished();
        awaitLines(file, 1);
        archive.close();

        var record = json.readTree(Files.readString(file, StandardCharsets.UTF_8).strip());
        assertThat(record.propertyNames()).containsExactlyInAnyOrder(
                "provider", "sessionId", "requestId", "toolCallId", "status", "startedAt", "completedAt",
                "model", "inputTokens", "outputTokens", "toolNames", "workSha256", "work");
    }

    private static void awaitLines(Path file, int expected) throws Exception {
        await(file, lines -> lines.stream().filter(line -> !line.isBlank()).count() >= expected,
                "reach " + expected + " lines");
    }

    private static void awaitContains(Path file, String marker) throws Exception {
        await(file, lines -> lines.stream().anyMatch(line -> line.contains(marker)), "contain " + marker);
    }

    /**
     * Writes land on the archive's own thread, and a rotation renames the file out from under a reader,
     * so a poll that catches that instant sees no file at all rather than a failure worth reporting.
     */
    private static void await(Path file, java.util.function.Predicate<List<String>> satisfied, String what)
            throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            try {
                if (Files.exists(file) && satisfied.test(Files.readAllLines(file, StandardCharsets.UTF_8))) return;
            } catch (java.io.IOException rotating) {
                // Retried below.
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("archive never managed to " + what);
    }

    @Test
    void toleratesAnArchivePathItCannotWrite(@TempDir Path directory) throws Exception {
        Path unwritable = directory.resolve("locked");
        Files.createDirectory(unwritable);
        Files.setPosixFilePermissions(unwritable, PosixFilePermissions.fromString("r-x------"));
        var archive = new TraceArchive(json, unwritable.resolve("traces.jsonl").toString(), 64 * 1024 * 1024);
        var store = new TraceStore(50, archive);

        var trace = store.start("claude", "session-1", "request-1");
        trace.delta("private notes");
        trace.complete();
        trace.finished();
        archive.close();

        // Fails open: the row is lost, the turn is not.
        assertThat(store.snapshot()).extracting(TraceSnapshot::status).containsExactly("complete");
        assertThat(archive.tail(50)).isEmpty();
        Files.setPosixFilePermissions(unwritable, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void archivesEveryFinishedTurnExactlyOnce(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("traces.jsonl");
        var archive = new TraceArchive(json, file.toString(), 64 * 1024 * 1024);
        var store = new TraceStore(50, archive);
        var trace = store.start("claude", "session-1", "request-1");
        trace.delta("private notes");
        trace.complete();
        trace.finished();
        trace.finished();
        awaitLines(file, 1);
        archive.close();

        assertThat(Files.readAllLines(file, StandardCharsets.UTF_8)).hasSize(1);
        assertThat(archive.tail(50)).extracting(TraceSnapshot::requestId).isEqualTo(List.of("request-1"));
    }
}
