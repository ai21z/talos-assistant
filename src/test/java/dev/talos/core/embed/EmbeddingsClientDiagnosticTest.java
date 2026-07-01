package dev.talos.core.embed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingsClientDiagnosticTest {

    @Test
    void embeddingFailureMessageIncludesEndpointAttemptsWithoutEchoingInputText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/embed", exchange -> {
                String body = readBody(exchange);
                if (body.contains("\"input\"")) {
                    respond(exchange, 500, "{\"error\":\"embedding failed for Patient Name: Plain Sensitive Person\"}");
                } else {
                    respond(exchange, 200, "{\"model\":\"bge-m3\",\"embeddings\":[]}");
                }
            });
            server.createContext("/api/embeddings", exchange -> {
                String body = readBody(exchange);
                if (body.contains("\"input\"")) {
                    respond(exchange, 200, "{\"model\":\"bge-m3\",\"embeddings\":[]}");
                } else {
                    respond(exchange, 500, "{\"error\":\"failed to encode response: json: unsupported value: NaN\"}");
                }
            });
            server.start();

            Config cfg = new Config();
            Map<String, Object> ollama = new LinkedHashMap<>();
            ollama.put("host", "http://127.0.0.1:" + server.getAddress().getPort());
            ollama.put("embed", "bge-m3");
            cfg.data.put("ollama", ollama);

            EmbeddingsClient client = new EmbeddingsClient(cfg);
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> client.embed("Patient Name: Plain Sensitive Person\nWorkspace note: ordinary private fact"));

            String message = ex.getMessage();
            assertTrue(message.contains("model 'bge-m3'"), message);
            assertTrue(message.contains("/api/embed input -> HTTP 500"), message);
            assertTrue(message.contains("/api/embed prompt -> empty embedding"), message);
            assertTrue(message.contains("/api/embeddings input -> empty embedding"), message);
            assertTrue(message.contains("bodyHash=sha256:"), message);
            assertTrue(message.contains("bodyChars="), message);
            assertFalse(message.contains("Plain Sensitive Person"), message);
            assertFalse(message.contains("ordinary private fact"), message);
            assertFalse(message.contains("inputPreview"), message);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embeddingDebugLogsDoNotEchoProviderBodyOrInputText() throws Exception {
        String logs = runProbe(EmbeddingDebugLogProbe.class);

        assertTrue(logs.contains("embed non-2xx"), logs);
        assertTrue(logs.contains("bodyHash=sha256:"), logs);
        assertTrue(logs.contains("bodyChars="), logs);
        assertFalse(logs.contains("Plain Sensitive Person"), logs);
        assertFalse(logs.contains("ordinary private fact"), logs);
    }

    public static final class EmbeddingDebugLogProbe {
        public static void main(String[] args) throws Exception {
            List<String> messages = captureEmbeddingDebugLogs();
            for (String message : messages) {
                System.out.println(message);
            }
        }
    }

    private static List<String> captureEmbeddingDebugLogs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/api/embed", exchange -> {
                readBody(exchange);
                respond(exchange, 500, "{\"error\":\"embedding failed for Plain Sensitive Person\"}");
            });
            server.createContext("/api/embeddings", exchange -> {
                readBody(exchange);
                respond(exchange, 500, "{\"error\":\"ordinary private fact echoed by provider\"}");
            });
            server.start();

            Config cfg = new Config();
            Map<String, Object> ollama = new LinkedHashMap<>();
            ollama.put("host", "http://127.0.0.1:" + server.getAddress().getPort());
            ollama.put("embed", "bge-m3");
            cfg.data.put("ollama", ollama);

            EmbeddingsClient client = new EmbeddingsClient(cfg);
            return captureFormattedLogMessages(EmbeddingsClient.class,
                    () -> assertThrows(IllegalStateException.class,
                            () -> client.embed("Patient Name: Plain Sensitive Person\nordinary private fact")));
        } finally {
            server.stop(0);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String runProbe(Class<?> probe) throws Exception {
        Process process = new ProcessBuilder(
                javaExecutable(),
                "-Dslf4j.provider=ch.qos.logback.classic.spi.LogbackServiceProvider",
                "-cp",
                probeClasspath(),
                probe.getName())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, output);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe"
                : "java";
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    private static String probeClasspath() {
        String separator = System.getProperty("path.separator");
        String[] entries = System.getProperty("java.class.path", "").split(java.util.regex.Pattern.quote(separator));
        StringBuilder out = new StringBuilder();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) continue;
            java.nio.file.Path path = java.nio.file.Path.of(entry);
            java.nio.file.Path fileName = path.getFileName();
            if (fileName != null && fileName.toString().startsWith("gradle-")) {
                continue;
            }
            if (!out.isEmpty()) out.append(separator);
            out.append(entry);
        }
        return out.toString();
    }

    private static List<String> captureFormattedLogMessages(
            Class<?> loggerOwner,
            ThrowingRunnable action
    ) throws Exception {
        Object logger = LoggerFactory.getLogger(loggerOwner);
        Class<?> classicLoggerClass = Class.forName("ch.qos.logback.classic.Logger");
        Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");
        Class<?> appenderClass = Class.forName("ch.qos.logback.core.Appender");
        Class<?> listAppenderClass = Class.forName("ch.qos.logback.core.read.ListAppender");
        if (!classicLoggerClass.isInstance(logger)) {
            throw new AssertionError("Expected Logback logger but got " + logger.getClass().getName());
        }

        Object appender = listAppenderClass.getConstructor().newInstance();
        listAppenderClass.getMethod("start").invoke(appender);

        Object previousLevel = classicLoggerClass.getMethod("getLevel").invoke(logger);
        Object debugLevel = levelClass.getField("DEBUG").get(null);
        classicLoggerClass.getMethod("setLevel", levelClass).invoke(logger, debugLevel);
        classicLoggerClass.getMethod("addAppender", appenderClass).invoke(logger, appender);
        try {
            action.run();
        } finally {
            classicLoggerClass.getMethod("detachAppender", appenderClass).invoke(logger, appender);
            classicLoggerClass.getMethod("setLevel", levelClass).invoke(logger, previousLevel);
        }

        Field listField = listAppenderClass.getField("list");
        List<?> events = (List<?>) listField.get(appender);
        return events.stream()
                .map(event -> {
                    try {
                        return String.valueOf(event.getClass().getMethod("getFormattedMessage").invoke(event));
                    } catch (ReflectiveOperationException ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .toList();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
