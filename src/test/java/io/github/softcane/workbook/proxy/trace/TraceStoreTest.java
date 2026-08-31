package io.github.softcane.workbook.proxy.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.reactivestreams.Subscription;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.BaseSubscriber;

class TraceStoreTest {
    @Test
    void retainsACompleteBurstForASlowLiveSubscriber() throws Exception {
        var store = new TraceStore(2);
        var received = new CopyOnWriteArrayList<TraceEvent>();
        var complete = new CountDownLatch(302);
        var subscriber = new BaseSubscriber<TraceEvent>() {
            @Override protected void hookOnSubscribe(Subscription subscription) { }

            @Override protected void hookOnNext(TraceEvent event) {
                received.add(event);
                complete.countDown();
            }

            void requestEvents(long count) {
                request(count);
            }
        };
        store.liveEvents().subscribe(subscriber);

        var trace = store.start("codex", "session-slow", "request-slow");
        trace.toolStarted("call-slow");
        for (int index = 0; index < 300; index++) trace.delta("x");
        trace.complete();
        subscriber.requestEvents(302);

        assertThat(complete.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received).extracting(TraceEvent::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 302).boxed().toList());
        assertThat(received).extracting(TraceEvent::eventType)
                .startsWith("workbook_started").endsWith("workbook_complete");
        subscriber.cancel();
    }

    @Test
    void publishesFinalContinuationUsageAfterWorkbookCompletion() {
        var store = new TraceStore(2);
        var trace = store.start("codex", "session-usage", "request-usage");
        trace.toolStarted("call-usage");
        trace.addUsage(100, 10);
        trace.complete();
        trace.addUsage(50, 5);

        var snapshot = store.snapshot().getFirst();
        assertThat(snapshot.status()).isEqualTo("complete");
        assertThat(snapshot.inputTokens()).isEqualTo(150);
        assertThat(snapshot.outputTokens()).isEqualTo(15);
        assertThat(snapshot.events()).extracting(TraceEvent::eventType)
                .containsExactly("workbook_started", "workbook_complete", "usage_updated");
        assertThat(snapshot.events().getLast().inputTokens()).isEqualTo(150);
        assertThat(snapshot.events().getLast().outputTokens()).isEqualTo(15);
    }

    @Test
    void publishesSafeDeltasAndRetainsPartialTraceOnCancellation() throws Exception {
        var store = new TraceStore(2);
        var trace = store.start("codex", "session-1", "request-1");
        trace.toolStarted("call-1");
        trace.delta("hello ");
        trace.delta("world");
        trace.cancel();

        var snapshot = store.snapshot().getFirst();
        assertThat(snapshot.visibleWork()).isEqualTo("hello world");
        assertThat(snapshot.status()).isEqualTo("cancelled");
        assertThat(snapshot.events()).allSatisfy(event -> {
            assertThat(event.provider()).isEqualTo("codex");
            assertThat(event.toolCallId()).isEqualTo("call-1");
            assertThat(event.visibleDelta()).doesNotContain("authorization");
        });

        var complete = store.start("claude", "session-2", "request-2");
        complete.toolStarted("call-2");
        complete.delta("done");
        complete.complete();
        complete.cancel();
        complete.fail("failed");
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("done".getBytes(StandardCharsets.UTF_8)));
        assertThat(store.snapshot().getFirst().status()).isEqualTo("complete");
        assertThat(store.snapshot().getFirst().events()).hasSize(3);
        assertThat(store.snapshot().getFirst().events().getLast().finalSha256()).isEqualTo(expected);
    }
}
