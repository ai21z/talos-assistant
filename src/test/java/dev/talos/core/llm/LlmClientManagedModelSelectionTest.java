package dev.talos.core.llm;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.slash.ModelsCommand;
import dev.talos.cli.repl.slash.SetModelCommand;
import dev.talos.core.Config;
import dev.talos.runtime.Result;
import dev.talos.spi.EngineConfig;
import dev.talos.spi.types.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real command and client boundaries, with synthetic GGUF headers and a non-process resolver. */
class LlmClientManagedModelSelectionTest {
    @TempDir Path temp;

    @Test
    void modelCommandAliasesAcceptEquivalentWhitespaceAndShareSelection() throws Exception {
        Config cfg = config(false);
        Resolver resolver = new Resolver();
        try (LlmClient client = new LlmClient(cfg, resolver)) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            Context ctx = Context.builder(cfg).llm(client).build();
            var commands = new dev.talos.cli.repl.slash.CommandRegistry();
            commands.register(new ModelsCommand());
            commands.register(new SetModelCommand());
            for (String command : List.of("models", "model")) {
                for (String arguments : List.of("use second", "use  second", "use\tsecond")) {
                    assertInstanceOf(Result.Info.class, commands.execute(command, arguments, ctx),
                            command + " " + arguments);
                }
            }
            assertInstanceOf(Result.Info.class, commands.execute("set", "model second", ctx));
            assertEquals(7, resolver.switches.get());
            assertFalse(client.nativeToolsCurrentlyEnabled());
        }
    }

    @Test
    void bothSelectionCommandsGiveTheSameRecoveryCommandWithoutAliasPingPong() throws Exception {
        Config cfg = config(false);
        Resolver resolver = new Resolver();
        resolver.failSwitch = true;
        try (LlmClient client = new LlmClient(cfg, resolver)) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            Context ctx = Context.builder(cfg).llm(client).build();
            Result.Error primary = assertInstanceOf(Result.Error.class,
                    new ModelsCommand().execute("use second", ctx));
            Result.Error compatibility = assertInstanceOf(Result.Error.class,
                    new SetModelCommand().execute("model second", ctx));
            assertEquals(primary.message, compatibility.message);
            assertTrue(primary.message.contains("After the cause is resolved, use /models use <alias>"), primary.message);
            assertFalse(primary.message.contains("/set model"), primary.message);
            assertFalse(primary.message.contains("Model loaded"));
            assertEquals(2, resolver.switches.get());
        }
    }

    @Test
    void invalidRegistrationIsAValidationErrorRatherThanAnUnavailableCatalog() throws Exception {
        Config cfg = config(false);
        Resolver resolver = new Resolver();
        try (LlmClient client = new LlmClient(cfg, resolver)) {
            Result.Error result = assertInstanceOf(Result.Error.class, new ModelsCommand().execute(
                    "add second relative.gguf", Context.builder(cfg).llm(client).build()));
            assertEquals(400, result.code);
            assertTrue(result.message.contains("absolute"), result.message);
            assertFalse(result.message.contains("catalog"), result.message);
            assertEquals(0, resolver.switches.get());
        }
    }

    @Test
    void failedManagedSelectionFromAnotherBackendRecommendsReachableRecovery() throws Exception {
        Config cfg = config(false);
        Resolver resolver = new Resolver();
        try (LlmClient client = new LlmClient(cfg, resolver)) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            client.setModel("unsupported/other");
            resolver.failSwitch = true;
            Context ctx = Context.builder(cfg).llm(client).build();
            Result.Error failure = assertInstanceOf(Result.Error.class,
                    new SetModelCommand().execute("model llama_cpp/second", ctx));
            assertTrue(failure.message.contains("use /set model llama_cpp/<alias>"), failure.message);
            assertFalse(failure.message.contains("use /models use"), failure.message);
            assertEquals("unsupported/other", client.getModel());
            assertTrue(client.modelSelectionUnavailable());
            resolver.failSwitch = false;
            assertInstanceOf(Result.Info.class,
                    new SetModelCommand().execute("model llama_cpp/second", ctx));
            assertEquals("llama_cpp/second", client.getModel());
            assertFalse(client.modelSelectionUnavailable());
            assertEquals(2, resolver.switches.get());
        }
    }

    @Test
    void registeredModelIsSelectedThroughCommandsAndUsedByNextRequest() throws Exception {
        Config cfg = config(true);
        Resolver resolver = new Resolver();
        try (LlmClient client = new LlmClient(cfg, resolver)) {
            Context ctx = Context.builder(cfg).llm(client).build();
            Path next = gguf("another model.gguf");
            Result added = new ModelsCommand().execute("add second \"" + next + "\"", ctx);
            assertInstanceOf(Result.Info.class, added);
            assertEquals(0, resolver.switches.get());
            Result selected = new ModelsCommand().execute("use second", ctx);
            assertInstanceOf(Result.Info.class, selected);
            assertTrue(((Result.Info) selected).text.contains("Model loaded: llama_cpp/second"));
            assertEquals(1, resolver.switches.get());
            assertEquals(next.toRealPath().toString(), block(resolver.target).get("model_path"));
            client.chatFull(List.of(ChatMessage.user("hello")), 5000L, List.of(new ToolSpec("read", "read", "{}")));
            assertEquals("second", resolver.lastRequest.model);
            assertEquals(1, resolver.lastRequest.tools.size());
            assertEquals("first", block(cfg).get("model"));
            assertEquals("first", ((Map<?, ?>) cfg.data.get("llm")).get("model"));
            assertEquals("UNVERIFIED", client.modelIdentity().resolution());
            String listing = ((Result.Ok) new ModelsCommand().execute("", ctx)).text;
            assertTrue(listing.contains("second  [ACTIVE]"), listing);
            String status = new dev.talos.cli.repl.slash.StatusCommand(
                    dev.talos.cli.modes.ModeController.defaultController(), temp).execute("--verbose", ctx).toString();
            assertTrue(status.contains("ModelFile another model.gguf"), status);
            assertTrue(status.contains("Request layout  preserve-as-written"), status);
            assertTrue(status.contains("Resolved ctx    8192"), status);
            assertFalse(status.contains("first.gguf"), status);
        }
    }

    @Test
    void globalNativeToolRestrictionIsNotEnabledBySelectingACapableModel() throws Exception {
        Resolver resolver = new Resolver();
        try (LlmClient client = new LlmClient(config(false), resolver)) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            client.setModel("second");
            assertFalse(client.prepareNativeTools());
            assertFalse(client.nativeToolsCurrentlyEnabled());
            client.chatFull(List.of(ChatMessage.user("hello")), 5000L, List.of(new ToolSpec("read", "read", "{}")));
            assertTrue(resolver.lastRequest.tools.isEmpty());
        }
    }

    @Test
    void prelaunchFailurePreservesSelectionAndNeverCallsReplacement() throws Exception {
        Resolver resolver = new Resolver();
        try (LlmClient client = new LlmClient(config(true), resolver)) {
            assertThrows(IllegalArgumentException.class, () -> client.setModel("missing"));
            assertEquals("llama_cpp/first", client.getModel());
            assertEquals(0, resolver.switches.get());
            assertFalse(client.modelSelectionUnavailable());
        }
    }

    @Test
    void failedReplacementNeverReportsNewModelAndDisablesDispatchPreparation() throws Exception {
        Config cfg = config(true);
        Resolver resolver = new Resolver();
        resolver.failSwitch = true;
        try (LlmClient client = new LlmClient(cfg, resolver)) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            Result result = new SetModelCommand().execute("model second", Context.builder(cfg).llm(client).build());
            assertInstanceOf(Result.Error.class, result);
            assertTrue(((Result.Error) result).message.contains("No model is available"));
            assertEquals("llama_cpp/first", client.getModel());
            assertThrows(IllegalStateException.class, client::prepareNativeTools);
            assertFalse(client.nativeToolsCurrentlyEnabled());
            String status = new dev.talos.cli.repl.slash.StatusCommand(
                    dev.talos.cli.modes.ModeController.defaultController(), temp).execute("--verbose",
                    Context.builder(cfg).llm(client).build()).toString();
            assertTrue(status.contains("(unavailable)"), status);
            assertFalse(status.contains("ModelFile"), status);
            assertFalse(status.contains("Resolution"), status);
        }
    }

    @Test
    void returnFromAnotherBackendStillUsesManagedReplacement() throws Exception {
        Resolver resolver = new Resolver();
        try (LlmClient client = new LlmClient(config(true), resolver)) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            client.setModel("unsupported/other");
            client.setModel("llama_cpp/second");
            assertEquals(1, resolver.switches.get());
            assertEquals("llama_cpp/second", client.getModel());
        }
    }

    @Test
    void selectionCommandsPreserveCancellation() throws Exception {
        Config cfg = config(true);
        Resolver resolver = new Resolver();
        resolver.cancelSwitch = true;
        try (LlmClient client = new LlmClient(cfg, resolver)) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            Context ctx = Context.builder(cfg).llm(client).build();
            assertThrows(CancellationException.class, () -> new ModelsCommand().execute("use second", ctx));
            assertThrows(CancellationException.class, () -> new SetModelCommand().execute("model second", ctx));
            assertEquals("llama_cpp/first", client.getModel());
        }
    }

    @Test
    void activeLegacyBackgroundAndStructuredCallsBlockSelection() throws Exception {
        for (int route = 0; route < 4; route++) {
            Resolver resolver = new Resolver();
            resolver.entered = new CountDownLatch(1);
            resolver.release = new CountDownLatch(1);
            try (LlmClient client = new LlmClient(config(true), resolver);
                 ExecutorService worker = Executors.newSingleThreadExecutor()) {
                client.managedModelLibrary().register("second", gguf("second-" + route + ".gguf"));
                int selectedRoute = route;
                Future<?> call = worker.submit(() -> {
                    switch (selectedRoute) {
                        case 0 -> client.chat("system", "question", List.of());
                        case 1 -> client.chatStream("system", "question", List.of(), ignored -> {});
                        case 2 -> client.chatPlainBackgroundMaintenance("system", "question");
                        default -> client.chatFull(List.of(ChatMessage.user("question")), 5000L);
                    }
                });
                try {
                    assertTrue(resolver.entered.await(5, TimeUnit.SECONDS));
                    assertThrows(IllegalStateException.class, () -> client.setModel("second"));
                    assertEquals(0, resolver.switches.get());
                } finally {
                    resolver.release.countDown();
                }
                call.get(5, TimeUnit.SECONDS);
                client.setModel("second");
                assertEquals(1, resolver.switches.get());
            }
        }
    }

    @Test
    void preDispatchWorkerBlocksSelectionAfterItsCallerTimesOut() throws Exception {
        Resolver resolver = new Resolver();
        resolver.entered = new CountDownLatch(1);
        resolver.release = new CountDownLatch(1);
        resolver.blockWorkerCapabilities = true;
        try (LlmClient client = new LlmClient(config(true), resolver);
             ExecutorService caller = Executors.newSingleThreadExecutor()) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            Future<LlmClient.StreamResult> call = caller.submit(
                    () -> client.chatFull(List.of(ChatMessage.user("question")), 1000L));
            try {
                assertTrue(resolver.entered.await(5, TimeUnit.SECONDS));
                resolver.capabilityWorker.interrupt();
                assertTrue(call.get(5, TimeUnit.SECONDS).aborted());
                assertNull(resolver.lastRequest, "the delayed worker has not opened a provider stream");
                assertThrows(IllegalStateException.class, () -> client.setModel("second"));
                assertEquals(0, resolver.switches.get());
                assertEquals("llama_cpp/first", client.getModel());
            } finally {
                resolver.release.countDown();
            }
        }
    }

    @Test
    void preparationCannotPublishStaleCapabilitiesAcrossSelection() throws Exception {
        Resolver resolver = new Resolver();
        resolver.entered = new CountDownLatch(1);
        resolver.release = new CountDownLatch(1);
        resolver.blockPreparation = true;
        try (LlmClient client = new LlmClient(config(true), resolver);
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            client.managedModelLibrary().register("second", gguf("second.gguf"));
            Future<Boolean> preparation = worker.submit(client::prepareNativeTools);
            try {
                assertTrue(resolver.entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalStateException.class, () -> client.setModel("second"));
            } finally {
                resolver.release.countDown();
            }
            assertTrue(preparation.get(5, TimeUnit.SECONDS));
            resolver.nativeCapable = false;
            client.setModel("second");
            assertFalse(client.nativeToolsCurrentlyEnabled());
        }
    }

    private Config config(boolean nativeTools) throws Exception {
        Config cfg = new Config();
        cfg.data.put("llm", Map.of("transport", "engine", "default_backend", "llama_cpp", "model", "first"));
        cfg.data.put("tools", Map.of("native_calling", nativeTools));
        cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "managed", "model", "first",
                "model_path", gguf("first.gguf").toString(), "server_path",
                Files.writeString(temp.resolve("fixture-server"), "never executed").toString())));
        return cfg;
    }

    private Path gguf(String file) throws Exception { return Files.write(temp.resolve(file), new byte[] {'G', 'G', 'U', 'F'}); }
    private static Map<?, ?> block(EngineConfig cfg) { return (Map<?, ?>) ((Map<?, ?>) cfg.data().get("engines")).get("llama_cpp"); }

    private static final class Resolver implements LlmEngineResolver {
        final AtomicInteger switches = new AtomicInteger();
        boolean unavailable, failSwitch, cancelSwitch, blockPreparation, blockWorkerCapabilities, nativeCapable = true;
        String model = "first";
        EngineConfig target;
        ChatRequest lastRequest;
        CountDownLatch entered, release;
        volatile Thread capabilityWorker;
        public boolean ownsManagedModelLifecycle() { return true; }
        public void select(String backend, String model) { this.model = model; }
        public boolean capabilitiesAuthoritative() { return true; }
        public Capabilities capabilities() {
            if (blockWorkerCapabilities && Thread.currentThread().getName().equals("talos-llm-call")) {
                awaitUninterruptiblePreprocessing();
            }
            return Capabilities.of(true, true, false, 8192, nativeCapable);
        }
        public Capabilities prepareCapabilities() throws Exception {
            if (blockPreparation) awaitRelease();
            return capabilities();
        }
        public Capabilities switchManagedModel(EngineConfig cfg, String alias) {
            switches.incrementAndGet();
            if (cancelSwitch) throw new CancellationException("synthetic cancellation");
            if (failSwitch) { unavailable = true; throw new IllegalStateException("synthetic startup failure"); }
            unavailable = false;
            target = cfg;
            model = alias;
            return capabilities();
        }
        public boolean selectionUnavailable() { return unavailable; }
        public ModelIdentitySnapshot modelIdentity() {
            return ModelIdentitySnapshot.builder().backend("llama_cpp").configuredModel(model)
                    .configuredModelFile(target == null ? model + ".gguf" : block(target).get("model_path").toString())
                    .propsState("AVAILABLE").servedIdentityState("MATCHED").resolution("UNVERIFIED")
                    .effectiveRequestLayout("PRESERVE_AS_WRITTEN").effectiveContextTokens(8192)
                    .chatAvailable(true).build();
        }
        public Stream<TokenChunk> chatStream(ChatRequest request) throws Exception {
            lastRequest = request;
            if (!blockPreparation) awaitRelease();
            return Stream.of(TokenChunk.of("fixture answer"), TokenChunk.eos("stop"));
        }
        private void awaitRelease() throws Exception {
            if (entered == null) return;
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture synchronization timeout");
        }
        private void awaitUninterruptiblePreprocessing() {
            // Synthetic uninterruptible preprocessing keeps the worker pending after caller timeout.
            capabilityWorker = Thread.currentThread();
            entered.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean interrupted = false;
            try {
                while (release.getCount() != 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new IllegalStateException("fixture preprocessing timeout");
                    try {
                        if (release.await(remaining, TimeUnit.NANOSECONDS)) return;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        public void close() {}
    }
}
