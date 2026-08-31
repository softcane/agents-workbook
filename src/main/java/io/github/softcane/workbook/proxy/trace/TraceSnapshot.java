package io.github.softcane.workbook.proxy.trace;

import java.time.Instant;
import java.util.List;

public record TraceSnapshot(
        String provider,
        String sessionId,
        String requestId,
        String toolCallId,
        String status,
        Instant startedAt,
        String model,
        long inputTokens,
        long outputTokens,
        String visibleWork,
        List<TraceEvent> events) { }
