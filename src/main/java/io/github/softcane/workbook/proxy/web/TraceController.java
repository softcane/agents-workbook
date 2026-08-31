package io.github.softcane.workbook.proxy.web;

import io.github.softcane.workbook.proxy.trace.TraceEvent;
import io.github.softcane.workbook.proxy.trace.TraceSnapshot;
import io.github.softcane.workbook.proxy.trace.TraceStore;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import reactor.core.publisher.Flux;

@Controller
public final class TraceController {
    private final TraceStore traces;

    public TraceController(TraceStore traces) {
        this.traces = traces;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Resource dashboard() {
        return new ClassPathResource("static/proxy-dashboard.html");
    }

    @GetMapping("/api/v1/traces")
    @ResponseBody
    public List<TraceSnapshot> traces() {
        return traces.snapshot();
    }

    @GetMapping(value = "/api/v1/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<ServerSentEvent<TraceEvent>> events() {
        var live = traces.liveEvents().map(event -> ServerSentEvent.builder(event)
                .event(event.eventType())
                .id(event.requestId() + ":" + event.sequence())
                .build());
        var heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> ServerSentEvent.<TraceEvent>builder().comment("keepalive").build());
        return Flux.merge(live, heartbeat);
    }
}
