package io.github.softcane.workbook.proxy.trace;

import java.time.Instant;

public record TraceEvent(
        String provider,
        String sessionId,
        String requestId,
        String toolCallId,
        long sequence,
        String eventType,
        Instant timestamp,
        String visibleDelta,
        String finalSha256,
        String model,
        long inputTokens,
        long outputTokens) { }
