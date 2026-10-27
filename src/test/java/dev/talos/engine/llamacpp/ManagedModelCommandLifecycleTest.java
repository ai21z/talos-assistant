package dev.talos.engine.llamacpp;

import com.sun.net.httpserver.HttpServer;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.slash.CommandRegistry;
import dev.talos.cli.repl.slash.ModelsCommand;
import dev.talos.cli.repl.slash.SetModelCommand;
import dev.talos.core.Config;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.Result;
import dev.talos.spi.EngineConfig;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.ModelEngineProvider;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real command, client, registry, manager, process and HTTP boundaries with synthetic server responses. */
@Timeout(120)
class ManagedModelCommandLifecycleTest {
    @TempDir Path temp;

    @Test
    void bothCommandsReplaceOwnedChildrenAndBindTheNextRequest() throws Exception {
        Fixture fixture = new Fixture(freePort());
        try (LlmClient client = fixture.client) {
            assertLoaded(fixture.commands.execute("models", "use first", fixture.context), "first");
            assertAnswer(client, "first");
            LlamaCppProcess first = fixture.children.getFirst();
            Result.Error invalid = assertInstanceOf(Result.Error.class,
                    fixture.commands.execute("models", "use missing", fixture.context));
            assertTrue(invalid.message.contains("previous selection was preserved"), invalid.message);
            assertTrue(first.isAlive());
            assertEquals(1, fixture.children.size());

            assertLoaded(fixture.commands.execute("set", "model second", fixture.context), "second");
            assertFalse(first.isAlive());
            assertAnswer(client, "second");
            LlamaCppProcess second = fixture.children.getLast();
            assertLoaded(fixture.commands.execute("model", "use first", fixture.context), "first");
            assertFalse(second.isAlive());
            assertAnswer(client, "first");
            assertEquals(3, fixture.children.size());
            assertFalse(client.nativeToolsCurrentlyEnabled());
            assertEquals("first", ((Map<?, ?>) fixture.cfg.data.get("llm")).get("model"));
        }
        assertTrue(fixture.children.stream().noneMatch(LlamaCppProcess::isAlive));
    }

    @Test
    void occupiedEndpointIsNotUsedAndExplicitSelectionCanRecover() throws Exception {
        HttpServer unrelated = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        unrelated.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        unrelated.start();
        Fixture fixture = new Fixture(unrelated.getAddress().getPort());
        try (LlmClient client = fixture.client) {
            Result.Error result = assertInstanceOf(Result.Error.class,
                    fixture.commands.execute("set", "model second", fixture.context));
            assertTrue(result.message.contains("pre-existing listener"), result.message);
            assertTrue(result.message.contains("/models use <alias>"), result.message);
            assertEquals(0, fixture.children.size());
            assertEquals(0, requests.get());
            assertTrue(client.modelSelectionUnavailable());
            unrelated.stop(0);
            assertLoaded(fixture.commands.execute("models", "use second", fixture.context), "second");
            assertAnswer(client, "second");
            assertFalse(client.modelSelectionUnavailable());
        } finally {
            unrelated.stop(0);
        }
        assertTrue(fixture.children.stream().noneMatch(LlamaCppProcess::isAlive));
    }

    private static void assertLoaded(Result result, String alias) {
        Result.Info info = assertInstanceOf(Result.Info.class, result, result.toString());
        assertTrue(info.text.contains("Model loaded: llama_cpp/" + alias), info.text);
    }

    private static void assertAnswer(LlmClient client, String alias) {
        LlmClient.StreamResult response = client.chatFull(List.of(ChatMessage.user("fixture request")), 30000L);
        assertFalse(response.aborted(), response.text());
        assertEquals("fixture answer from " + alias, response.text());
        assertEquals("MATCHED", client.modelIdentity().servedIdentityState());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private final class Fixture implements ModelEngineProvider {
        final List<LlamaCppProcess> children = new ArrayList<>();
        final Config cfg = new Config(null);
        final LlmClient client;
        final Context context;
        final CommandRegistry commands = new CommandRegistry();
        final Path java = Path.of(ProcessHandle.current().info().command().orElseThrow());
        final String classpath = Path.of(ManagedModelServerFixture.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toString();

        Fixture(int port) throws Exception {
            Path first = Files.write(temp.resolve("first.gguf"), new byte[]{'G', 'G', 'U', 'F'});
            Path second = Files.write(temp.resolve("second.gguf"), new byte[]{'G', 'G', 'U', 'F'});
            cfg.data.put("llm", Map.of("transport", "engine", "default_backend", "llama_cpp", "model", "first"));
            cfg.data.put("tools", Map.of("native_calling", false));
            cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "managed", "model", "first",
                    "server_path", java.toString(), "model_path", first.toString(), "host", "http://localhost", "port", port)));
            client = new LlmClient(cfg);
            var resolverField = LlmClient.class.getDeclaredField("engineResolver");
            resolverField.setAccessible(true);
            Object resolver = resolverField.get(client);
            var registryField = resolver.getClass().getDeclaredField("registry");
            registryField.setAccessible(true);
            var registry = (EngineRegistry) registryField.get(resolver);
            var providersField = EngineRegistry.class.getDeclaredField("providers");
            providersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var providers = (Map<String, ModelEngineProvider>) providersField.get(registry);
            providers.put("llama_cpp", this);
            client.managedModelLibrary().register("second", second);
            context = Context.builder(cfg).llm(client).build();
            commands.register(new ModelsCommand());
            commands.register(new SetModelCommand());
        }

        @Override public String id() { return "llama_cpp"; }

        @Override public ModelEngine create(EngineConfig target) {
            LlamaCppConfig selected = LlamaCppConfig.from(target);
            LlamaCppProcessLauncher launcher = (command, log) -> {
                LlamaCppProcess child = new ProcessBuilderLlamaCppProcessLauncher().start(List.of(java.toString(),
                        "-cp", classpath, ManagedModelServerFixture.class.getName(), Integer.toString(selected.port()),
                        selected.modelPath(), selected.model()), log);
                children.add(child);
                return child;
            };
            var manager = new LlamaCppServerManager(selected, launcher, HttpClient.newHttpClient(),
                    Duration.ofSeconds(30), Duration.ofMillis(50), temp.resolve("logs"));
            return new LlamaCppEngine(selected, manager, HttpClient.newHttpClient(),
                    new LlamaCppResolvedProfileService(selected, temp.resolve("profile-home")));
        }
    }
}
