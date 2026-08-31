package io.github.softcane.workbook.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

class BrowserE2EIT {
    @Test
    void replacesStaleTerminalUsageWithFinalizedSnapshot() throws Exception {
        var tracesRequests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> staticFile(exchange, "proxy-dashboard.html", "text/html"));
        server.createContext("/proxy-dashboard.js", exchange -> staticFile(exchange, "proxy-dashboard.js", "text/javascript"));
        server.createContext("/proxy-dashboard.css", exchange -> staticFile(exchange, "proxy-dashboard.css", "text/css"));
        server.createContext("/api/v1/traces", exchange -> {
            String body;
            if (tracesRequests.incrementAndGet() == 1) {
                body = "[]";
            } else {
                try {
                    // Keep the stale terminal SSE totals visible long enough to prove the browser
                    // rendered them before this canonical response supplies the continuation usage.
                    Thread.sleep(1500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while finalizing test usage", interrupted);
                }
                body = finalizedSnapshot();
            }
            respond(exchange, 200, "application/json", body);
        });
        server.createContext("/api/v1/events", this::events);
        server.start();
        WebDriver driver = null;
        try {
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new", "--no-sandbox", "--disable-dev-shm-usage");
            driver = new ChromeDriver(options);
            driver.get("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            var wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            String staleLabel = wait.until(current -> {
                var labels = current.findElements(By.cssSelector(".turn-label"));
                if (labels.isEmpty()) return null;
                String text = labels.getFirst().getText();
                return text.startsWith("Thought") ? text : null;
            });
            assertThat(staleLabel).contains("Thought for 1 tokens");
            String label = wait.until(current -> {
                var labels = current.findElements(By.cssSelector(".turn-label"));
                if (labels.isEmpty()) return null;
                String text = labels.getFirst().getText();
                return text.contains("7 tokens") ? text : null;
            });
            assertThat(label).contains("Thought for 7 tokens");
            driver.findElement(By.cssSelector(".turn-summary")).click();
            assertThat(driver.findElement(By.cssSelector(".turn-work")).getText()).isEqualTo("private notes");
            assertThat(tracesRequests.get()).isGreaterThanOrEqualTo(2);
        } finally {
            if (driver != null) driver.quit();
            server.stop(0);
        }
    }

    private void events(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        String common = "\"provider\":\"codex\",\"sessionId\":\"session-1\",\"requestId\":\"request-1\","
                + "\"model\":\"gpt-test\",\"inputTokens\":1,\"outputTokens\":1,";
        String events = "event: workbook_started\ndata: {" + common
                + "\"sequence\":1,\"eventType\":\"workbook_started\",\"timestamp\":\"2026-01-01T00:00:00Z\"}\n\n"
                + "event: workbook_delta\ndata: {" + common
                + "\"sequence\":2,\"eventType\":\"workbook_delta\",\"visibleDelta\":\"private notes\"}\n\n"
                + "event: workbook_complete\ndata: {" + common
                + "\"sequence\":3,\"eventType\":\"workbook_complete\",\"timestamp\":\"2026-01-01T00:00:01Z\"}\n\n";
        exchange.getResponseBody().write(events.getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        exchange.close();
    }

    private String finalizedSnapshot() {
        return "[{\"provider\":\"codex\",\"sessionId\":\"session-1\",\"requestId\":\"request-1\","
                + "\"status\":\"complete\",\"startedAt\":\"2026-01-01T00:00:00Z\",\"model\":\"gpt-test\","
                + "\"inputTokens\":4,\"outputTokens\":7,\"visibleWork\":\"private notes\",\"events\":["
                + "{\"sequence\":1,\"eventType\":\"workbook_started\",\"timestamp\":\"2026-01-01T00:00:00Z\"},"
                + "{\"sequence\":2,\"eventType\":\"workbook_delta\",\"timestamp\":\"2026-01-01T00:00:00Z\"},"
                + "{\"sequence\":3,\"eventType\":\"workbook_complete\",\"timestamp\":\"2026-01-01T00:00:01Z\"}]}]";
    }

    private void staticFile(HttpExchange exchange, String name, String contentType) throws IOException {
        Path path = Path.of("src/main/resources/static").resolve(name);
        respond(exchange, 200, contentType, Files.readString(path));
    }

    private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
