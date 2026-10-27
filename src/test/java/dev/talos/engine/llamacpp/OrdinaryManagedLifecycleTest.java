package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OrdinaryManagedLifecycleTest {
    @TempDir Path temp;

    @Test
    void realChildOwnsListenerAndCachedProfileRefusesDeadOrReplacedChild() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        Path java = Path.of(ProcessHandle.current().info().command().orElseThrow());
        String childClasspath = Path.of(HttpChild.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path model = Files.write(temp.resolve("ordinary.gguf"), new byte[]{'G', 'G', 'U', 'F'});
        LlamaCppConfig cfg = config("http://localhost", port, java, model);
        AtomicReference<LlamaCppProcess> launched = new AtomicReference<>();
        LlamaCppProcessLauncher launcher = (command, log) -> {
            LlamaCppProcess child = new ProcessBuilderLlamaCppProcessLauncher().start(List.of(
                    java.toString(), "-cp", childClasspath,
                    HttpChild.class.getName(), Integer.toString(port), model.toString()), log);
            launched.set(child);
            return child;
        };
        try (LlamaCppServerManager manager = new LlamaCppServerManager(cfg, launcher, HttpClient.newHttpClient(),
                Duration.ofSeconds(30), Duration.ofMillis(50), temp.resolve("real-child-logs"))) {
            LlamaCppEngine engine = new LlamaCppEngine(cfg, manager, HttpClient.newHttpClient(),
                    new LlamaCppResolvedProfileService(cfg, temp.resolve("isolated-home")));
            assertTrue(engine.prepareCapabilities().chat());
            assertTrue(launched.get().isAlive());
            assertEquals("MATCHED", engine.modelIdentity().servedIdentityState());
            launched.get().destroy();
            assertTrue(launched.get().waitFor(Duration.ofSeconds(10)));
            assertFalse(launched.get().isAlive());
            assertFalse(engine.caps().chat());
            assertFalse(engine.modelIdentity().chatAvailable());
            assertEquals("UNAVAILABLE", engine.modelIdentity().propsState());
            assertThrows(EngineException.ConnectionFailed.class, engine::prepareCapabilities);
            AtomicInteger unrelatedCalls = new AtomicInteger();
            HttpServer replacement = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            replacement.createContext("/", exchange -> {
                unrelatedCalls.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            replacement.start();
            try {
                assertThrows(EngineException.ConnectionFailed.class, engine::prepareCapabilities);
                assertFalse(engine.modelIdentity().chatAvailable());
                assertEquals(0, unrelatedCalls.get());
            } finally {
                replacement.stop(0);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "http://localhost", "127.0.0.1", "http://127.0.0.1", "http://127.0.0.1:18115"})
    void managedEndpointUsesSameNumericHostAndPort(String host) {
        LlamaCppConfig cfg = config(host, 18115, Path.of("unused"), Path.of("unused.gguf"));
        assertEquals("127.0.0.1", cfg.listenHost());
        assertEquals("http://127.0.0.1:18115", cfg.baseUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "[::1]", "http://[::1]:18115"})
    void ipv6IsParsedWithoutTruncatingTheHost(String host) {
        LlamaCppConfig cfg = config(host, 18115, Path.of("unused"), Path.of("unused.gguf"));
        assertEquals("::1", cfg.listenHost());
        assertEquals("http://[::1]:18115", cfg.baseUrl());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1:18116", "http://localhost/path", "https://localhost",
            "http://localhost?query=true", "http://user@localhost", "http://[::1]:18115", "http://192.0.2.1", "http://[broken"})
    void ambiguousOrUnsupportedManagedEndpointFailsBeforeLaunch(String host) throws Exception {
        Path binary = Files.writeString(temp.resolve("server"), "fixture");
        Path model = Files.write(temp.resolve("model.gguf"), new byte[]{'G', 'G', 'U', 'F'});
        LlamaCppConfig cfg = config(host, 18115, binary, model);
        AtomicInteger starts = new AtomicInteger();
        try (LlamaCppServerManager manager = new LlamaCppServerManager(cfg, (command, log) -> {
            starts.incrementAndGet();
            throw new AssertionError("invalid endpoint must not launch");
        }, HttpClient.newHttpClient(), Duration.ofSeconds(1), Duration.ofMillis(10), temp.resolve("logs"))) {
            assertThrows(EngineException.ConnectionFailed.class, manager::ensureStarted);
            assertEquals(0, starts.get());
            assertFalse(Files.exists(temp.resolve("logs")));
        }
    }

    @Test
    void connectOnlyLocalhostEndpointIsNotNormalized() {
        Config cfg = new Config();
        cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "connect_only", "host", "http://localhost", "port", 18115)));
        assertEquals("http://localhost:18115", LlamaCppConfig.from(cfg).baseUrl());
    }

    @Test
    void interruptedOwnershipInspectionPreservesControlFlowWithoutLaunch() throws Exception {
        LlamaCppConfig cfg = config("http://127.0.0.1", 18115,
                Files.writeString(temp.resolve("server"), "fixture"),
                Files.write(temp.resolve("model.gguf"), new byte[]{'G', 'G', 'U', 'F'}));
        try (LlamaCppServerManager manager = new LlamaCppServerManager(cfg, (command, log) -> {
            throw new AssertionError("interrupted inspection must not launch");
        }, HttpClient.newHttpClient(), Duration.ofSeconds(1), Duration.ofMillis(10), temp.resolve("logs"),
                (host, port, pid, started, timeout) -> {
                    Thread.currentThread().interrupt();
                    return LlamaCppEndpointOwnerProbe.Observation.indeterminate(SyntheticManagedServer.OBSERVER);
                })) {
            try {
                RuntimeException failure = assertThrows(RuntimeException.class, manager::ensureStarted);
                assertEquals(dev.talos.spi.types.ControlFlowFailure.Kind.INTERRUPTION,
                        dev.talos.spi.types.ControlFlowFailure.classify(failure));
                assertTrue(Thread.currentThread().isInterrupted());
                assertFalse(Files.exists(temp.resolve("logs")));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void realUnrelatedListenerAppearingAfterPreflightIsRejectedBeforeHealth() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        Path java = Path.of(ProcessHandle.current().info().command().orElseThrow());
        String childClasspath = Path.of(SleepingChild.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path model = Files.write(temp.resolve("race.gguf"), new byte[]{'G', 'G', 'U', 'F'});
        AtomicReference<HttpServer> unrelated = new AtomicReference<>();
        AtomicReference<LlamaCppProcess> launched = new AtomicReference<>();
        AtomicInteger healthCalls = new AtomicInteger();
        LlamaCppProcessLauncher launcher = (command, log) -> {
            HttpServer listener = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            listener.createContext("/health", exchange -> {
                healthCalls.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            listener.start();
            unrelated.set(listener);
            LlamaCppProcess child = new ProcessBuilderLlamaCppProcessLauncher().start(List.of(java.toString(),
                    "-cp", childClasspath, SleepingChild.class.getName()), log);
            launched.set(child);
            return child;
        };
        try (LlamaCppServerManager manager = new LlamaCppServerManager(config("127.0.0.1", port, java, model),
                launcher, HttpClient.newHttpClient(), Duration.ofSeconds(10), Duration.ofMillis(20), temp.resolve("race-logs"))) {
            EngineException.ConnectionFailed failure = assertThrows(EngineException.ConnectionFailed.class, manager::ensureStarted);
            assertTrue(failure.getMessage().contains("not owned by the launched process"), failure.getMessage());
            assertEquals(0, healthCalls.get());
            assertNotNull(launched.get());
            assertFalse(launched.get().isAlive());
        } finally {
            if (unrelated.get() != null) unrelated.get().stop(0);
        }
    }

    private static LlamaCppConfig config(String host, int port, Path binary, Path model) {
        Config cfg = new Config();
        cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "managed", "host", host, "port", port,
                "server_path", binary.toString(), "model_path", model.toString(), "model", "ordinary-test")));
        return LlamaCppConfig.from(cfg);
    }

    /** A real launched child and listener with synthetic metadata. No model inference is performed. */
    public static final class HttpChild {
        public static void main(String[] args) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), 0);
            server.createContext("/", exchange -> {
                byte[] body = (exchange.getRequestURI().getPath().equals("/props")
                        ? "{\"model_path\":\"" + args[1].replace("\\", "\\\\").replace("\"", "\\\"")
                            + "\",\"default_generation_settings\":{\"n_ctx\":8192},\"chat_template\":\"fixture\","
                            + "\"chat_template_caps\":{\"supports_tools\":false,\"supports_tool_calls\":false},"
                            + "\"modalities\":{\"vision\":false},\"build_info\":\"fixture\"}"
                        : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }
    }

    public static final class SleepingChild {
        public static void main(String[] args) throws Exception {
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }
}
