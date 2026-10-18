package dev.talos.cli.repl;

import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.NoOpSessionStore;
import dev.talos.runtime.SessionData;
import dev.talos.runtime.SessionWriterLease;
import dev.talos.runtime.SessionWriterUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TalosBootstrap} - the composition root.
 *
 * <p>Verifies that the bootstrap wires everything correctly and
 * produces a functional ReplRouter without exceptions.
 */
class TalosBootstrapTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @TempDir
    Path isolatedUserHome;

    private final List<ReplRouter> openedRouters = new ArrayList<>();
    private String previousUserHome;

    @BeforeEach
    void isolateUserHome() {
        previousUserHome = System.getProperty("user.home");
        System.setProperty("user.home", isolatedUserHome.toString());
    }

    @AfterEach
    void closeRoutersAndRestoreUserHome() {
        try {
            for (int index = openedRouters.size() - 1; index >= 0; index--) {
                openedRouters.get(index).getRuntimeSession().close();
            }
        } finally {
            if (previousUserHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousUserHome);
            }
        }
    }

    private ReplRouter createRouter(
            SessionState session,
            Config config,
            PrintStream out,
            Path workspace
    ) {
        ReplRouter router = TalosBootstrap.create(session, config, out, workspace);
        openedRouters.add(router);
        return router;
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void withUserHome(Path home, CheckedRunnable body) throws Exception {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previous);
            }
        }
    }

    private static Config configWithSessionPolicy(boolean persistence, boolean autoLoad) {
        Config cfg = new Config();
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("persistence", persistence);
        session.put("auto_load", autoLoad);
        cfg.data.put("session", session);
        return cfg;
    }

    private static void saveSession(Path home, Path workspace, String user, String assistant) {
        saveSession(home, workspace, user, assistant, "llama_cpp/qwen2.5-coder-14b");
    }

    private static void saveSession(Path home, Path workspace, String user, String assistant, String model) {
        JsonSessionStore store = new JsonSessionStore(home.resolve(".talos").resolve("sessions"));
        String sessionId = JsonSessionStore.sessionIdFor(workspace);
        try (var writer = store.acquireSessionWriter(sessionId)) {
            writer.save(new SessionData(sessionId, workspace.toString(), "", 1, Instant.now(),
                    List.of(
                            new SessionData.Turn("user", user, ""),
                            new SessionData.Turn("assistant", assistant, "ok")),
                    model));
        }
    }

    private static SessionState sessionState() {
        return new SessionState() {
            private int k = 6;
            private boolean debug;

            @Override public int getK() { return k; }
            @Override public void setK(int value) { k = value; }
            @Override public boolean isDebug() { return debug; }
            @Override public void setDebug(boolean enabled) { debug = enabled; }
        };
    }

    @Test
    void createProducesWorkingRouter() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = createRouter(session, new Config(), System.out, WS);

        assertNotNull(router);
        assertNotNull(router.getModes());
        assertNotNull(router.getRuntimeSession());
        assertFalse(router.shouldQuit());
        assertEquals("auto", router.getModes().getActiveName());
    }

    @Test
    void createHandlesNullConfig() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = createRouter(session, null, null, null);
        assertNotNull(router);
        assertFalse(router.shouldQuit());
    }

    @Test
    void savedSessionIsNotLoadedByDefaultButStartupNoticeIsShown(@TempDir Path home) throws Exception {
        Path workspace = home.resolve("workspace");
        java.nio.file.Files.createDirectories(workspace);
        saveSession(home, workspace, "old BMI request", "old BMI answer");

        withUserHome(home, () -> {
            SessionState session = new SessionState() {
                private int k = 6; private boolean dbg;
                public int getK() { return k; } public void setK(int v) { k = v; }
                public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
            };

            ReplRouter router = createRouter(session,
                    configWithSessionPolicy(true, false),
                    new PrintStream(java.io.OutputStream.nullOutputStream()), workspace);

            assertTrue(router.getStartupNotice().contains("saved session found"));
            assertTrue(router.getStartupNotice().contains("Not loaded"));
            assertTrue(router.getStartupNotice().contains(
                    "Use /session load to resume or /session list to manage saved sessions"));
            assertFalse(router.getStartupNotice().contains("/session clear to delete"));
            assertFalse(router.context().conversationManager().hasHistory(),
                    "saved session must not enter prompt context by default");
        });
    }

    @Test
    void autoLoadOptInRestoresSavedSession(@TempDir Path home) throws Exception {
        Path workspace = home.resolve("workspace");
        java.nio.file.Files.createDirectories(workspace);
        saveSession(home, workspace, "old BMI request", "old BMI answer");

        withUserHome(home, () -> {
            SessionState session = new SessionState() {
                private int k = 6; private boolean dbg;
                public int getK() { return k; } public void setK(int v) { k = v; }
                public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
            };

            ReplRouter router = createRouter(session,
                    configWithSessionPolicy(true, true),
                    new PrintStream(java.io.OutputStream.nullOutputStream()), workspace);

            assertTrue(router.getStartupNotice().contains("restored 1 prior exchange"));
            assertTrue(router.context().conversationManager().hasHistory());
            assertTrue(router.context().memory().get().contains("old BMI answer"));
        });
    }

    @Test
    void autoLoadKeepsHistoryAndConfiguredModelWhenSessionOnlyAliasIsMissing() throws Exception {
        Path workspace = isolatedUserHome.resolve("missing-model-workspace");
        java.nio.file.Files.createDirectories(workspace);
        saveSession(isolatedUserHome, workspace, "saved request", "saved answer", "llama_cpp/session-only-import");
        Config cfg = configWithSessionPolicy(true, true);
        cfg.data.put("llm", Map.of("transport", "engine", "default_backend", "llama_cpp", "model", "configured-model"));
        cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "managed", "model", "configured-model")));

        ReplRouter router = createRouter(sessionState(), cfg,
                new PrintStream(java.io.OutputStream.nullOutputStream()), workspace);

        assertTrue(router.getStartupNotice().contains("restored 1 prior exchange"));
        assertTrue(router.getStartupNotice().contains("Saved model was not restored"));
        assertTrue(router.getStartupNotice().contains("previous model selection was preserved"));
        assertFalse(router.getStartupNotice().contains("model llama_cpp/session-only-import"));
        assertEquals("llama_cpp/configured-model", router.context().llm().getModel());
        assertEquals("configured-model", dev.talos.core.CfgUtil.map(cfg.data.get("llm")).get("model"));
        assertFalse(router.context().llm().modelSelectionUnavailable());
        assertTrue(router.context().memory().get().contains("saved answer"));
        assertEquals(1, router.context().conversationManager().turnCount());
    }

    @Test
    void restoringAlreadySelectedModelPreservesLazyStartup() {
        Config cfg = configWithSessionPolicy(false, false);
        cfg.data.put("llm", Map.of("transport", "engine", "default_backend", "llama_cpp", "model", "configured-model"));
        cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "managed", "model", "configured-model")));
        // No artifact or server path exists. Calling setModel would fail local validation.
        try (var llm = new dev.talos.core.llm.LlmClient(cfg)) {
            assertEquals("", TalosBootstrap.restoreSavedModel(llm, "llama_cpp/configured-model"));
            assertEquals("llama_cpp/configured-model", llm.getModel());
            assertFalse(llm.modelSelectionUnavailable());
        }
    }

    @Test
    void failedSavedModelRestoreDoesNotClearExistingUnavailableState() throws Exception {
        Config cfg = configWithSessionPolicy(false, false);
        cfg.data.put("llm", Map.of("transport", "engine", "default_backend", "llama_cpp", "model", "configured-model"));
        cfg.data.put("engines", Map.of("llama_cpp", Map.of("mode", "managed", "model", "configured-model")));
        try (var llm = new dev.talos.core.llm.LlmClient(cfg)) {
            // Synthetic unsettled-switch state. No provider or model process is started.
            var resolverField = dev.talos.core.llm.LlmClient.class.getDeclaredField("engineResolver");
            resolverField.setAccessible(true);
            Object resolver = resolverField.get(llm);
            var registryField = resolver.getClass().getDeclaredField("registry");
            registryField.setAccessible(true);
            var registry = (dev.talos.core.engine.EngineRegistry) registryField.get(resolver);
            var unavailableField = dev.talos.core.engine.EngineRegistry.class.getDeclaredField("selectionUnavailable");
            unavailableField.setAccessible(true);
            unavailableField.setBoolean(registry, true);

            String notice = TalosBootstrap.restoreSavedModel(llm, "llama_cpp/configured-model");

            assertTrue(notice.contains("No model is available"));
            assertFalse(notice.contains("selection was preserved"));
            assertTrue(llm.modelSelectionUnavailable());
            assertEquals("llama_cpp/configured-model", llm.getModel());
            assertThrows(IllegalStateException.class, registry::engine);
        }
    }

    @Test
    void persistenceFalseSkipsSavedSessionAndUsesNoOpStore(@TempDir Path home) throws Exception {
        Path workspace = home.resolve("workspace");
        java.nio.file.Files.createDirectories(workspace);
        saveSession(home, workspace, "old BMI request", "old BMI answer");

        withUserHome(home, () -> {
            SessionState session = new SessionState() {
                private int k = 6; private boolean dbg;
                public int getK() { return k; } public void setK(int v) { k = v; }
                public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
            };

            ReplRouter router = createRouter(session,
                    configWithSessionPolicy(false, true),
                    new PrintStream(java.io.OutputStream.nullOutputStream()), workspace);

            assertTrue(router.getStartupNotice().isBlank());
            assertFalse(router.context().conversationManager().hasHistory());
            assertInstanceOf(NoOpSessionStore.class, router.getRuntimeSession().store());
        });
    }

    @Test
    void persistenceEnabledBindsOneLeaseAndCloseSnapshotsBeforeReleasingIt(
            @TempDir Path home
    ) throws Exception {
        Path workspace = home.resolve("leased-workspace");
        java.nio.file.Files.createDirectories(workspace);

        withUserHome(home, () -> {
            ReplRouter router = createRouter(
                    sessionState(),
                    configWithSessionPolicy(true, false),
                    new PrintStream(java.io.OutputStream.nullOutputStream()),
                    workspace);
            SessionWriterLease lease = assertInstanceOf(
                    SessionWriterLease.class, router.getRuntimeSession().store());
            String sessionId = lease.sessionId();
            router.context().conversationManager().addTurn("leased user", "leased answer");

            JsonSessionStore contender = new JsonSessionStore(
                    home.resolve(".talos").resolve("sessions"));
            SessionWriterUnavailableException busy = assertThrows(
                    SessionWriterUnavailableException.class,
                    () -> contender.acquireSessionWriter(sessionId));
            assertEquals(SessionWriterUnavailableException.Reason.WRITER_BUSY, busy.reason());

            router.getRuntimeSession().close();

            assertFalse(lease.isOpen());
            assertEquals(
                    "leased answer",
                    contender.load(sessionId).orElseThrow().turns().get(1).content());
            try (SessionWriterLease reopened = contender.acquireSessionWriter(sessionId)) {
                assertTrue(reopened.isOpen());
            }
        });
    }

    @Test
    void persistenceDisabledCreatesNoSessionOwnershipArtifacts(@TempDir Path home)
            throws Exception {
        Path workspace = home.resolve("ephemeral-workspace");
        java.nio.file.Files.createDirectories(workspace);

        withUserHome(home, () -> {
            ReplRouter router = createRouter(
                    sessionState(),
                    configWithSessionPolicy(false, true),
                    new PrintStream(java.io.OutputStream.nullOutputStream()),
                    workspace);
            assertInstanceOf(NoOpSessionStore.class, router.getRuntimeSession().store());
            router.getRuntimeSession().close();
        });

        assertFalse(java.nio.file.Files.exists(
                home.resolve(".talos").resolve("sessions").resolve(".ownership"),
                java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void configParseFailureProducesStartupNotice(@TempDir Path home) throws Exception {
        Path configFile = home.resolve(".talos").resolve("config.yaml");
        java.nio.file.Files.createDirectories(configFile.getParent());
        java.nio.file.Files.writeString(configFile, """
                llm:
                  transport: "engine"
                engines:
                  llama_cpp:
                    server_path: "C:\\Users\\bad\\llama-server.exe"
                """, StandardCharsets.UTF_8);

        Config cfg = new Config(configFile);

        String notice = TalosBootstrap.buildConfigNotice(cfg.getReport());

        assertTrue(notice.contains("config warning"));
        assertTrue(notice.contains("talos status --verbose"));
        assertTrue(notice.contains("talos setup models"));
    }

    @Test
    void syncActiveManagedModelUpdatesOnlyTheLlmBlock() throws Exception {
        Config cfg = new Config(null);
        java.util.Set<String> sectionsBefore = new java.util.LinkedHashSet<>(cfg.data.keySet());

        invokeSyncActiveModelIntoConfig(cfg, "llama_cpp/qwen2.5-coder-14b");

        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) cfg.data.get("llm");
        assertEquals("llama_cpp", llm.get("default_backend"));
        assertEquals("qwen2.5-coder-14b", llm.get("model"));
        assertEquals(sectionsBefore, cfg.data.keySet(), "selecting a model must not add config sections");
    }

    @Test
    void backwardCompatibleConstructorWorks() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        // This is how RunCmd currently creates the router
        ReplRouter router = new ReplRouter(session, new Config(), System.out, WS);
        assertNotNull(router);
        assertNotNull(router.getModes());
        assertEquals("auto", router.getModes().getActiveName());
    }

    @Test
    void modesHaveSymbolCheckerWired() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = createRouter(session, new Config(), System.out, WS);
        // SymbolChecker is set during bootstrap
        assertNotNull(router.getModes().getSymbolChecker());
    }

    @Test
    void explainLastTurnCommandIsRegistered() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = createRouter(session, new Config(),
                new PrintStream(java.io.OutputStream.nullOutputStream()), WS);

        assertTrue(router.getRegistry().has("explain-last-turn"));
        assertTrue(router.getRegistry().has("explain"));
    }

    @Test
    void unknownCommandIsNotHandled() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = createRouter(session, new Config(),
                new PrintStream(java.io.OutputStream.nullOutputStream()), WS);

        // Known command should be handled
        assertTrue(router.tryHandle("/help"));

        // Unknown command should not be handled
        assertFalse(router.tryHandle("/nonexistent"));

        // Non-command text should not be handled as command
        assertFalse(router.tryHandle("hello world"));
    }

    /**
     * T806: a registry miss now consults workspace templates before giving
     * up. The expansion runs the unmodified prompt pipeline (placeholder
     * transport under the temp home keeps this hermetic); unknown commands
     * with no matching template keep the pinned pre-T806 behavior.
     */
    @Test
    void workspaceTemplateDispatchesThroughThePromptPipeline(@TempDir Path home) throws Exception {
        Path workspace = home.resolve("workspace");
        Path commands = workspace.resolve(".talos").resolve("commands");
        java.nio.file.Files.createDirectories(commands);
        java.nio.file.Files.writeString(commands.resolve("hello.md"),
                "Say hello politely.\n\n$ARGS\n");

        withUserHome(home, () -> {
            SessionState session = new SessionState() {
                private int k = 6; private boolean dbg;
                public int getK() { return k; } public void setK(int v) { k = v; }
                public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
            };
            ReplRouter router = createRouter(session,
                    configWithSessionPolicy(false, false),
                    new PrintStream(java.io.OutputStream.nullOutputStream()), workspace);

            assertTrue(router.getTemplates().has("hello"));
            assertTrue(router.tryHandle("/hello to the new user"),
                    "template miss-path dispatch must handle the line");
            assertFalse(router.tryHandle("/nonexistent"),
                    "unknown commands without a template stay unhandled (pre-T806 pin)");
            assertFalse(router.getRegistry().has("hello"),
                    "templates are a separate catalog, never registry entries");
        });
    }

    @Test
    void quitCommandDoesNotRenderInternalToken() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };
        var sink = new ByteArrayOutputStream();
        ReplRouter router = createRouter(session, new Config(),
                new PrintStream(sink, true, StandardCharsets.UTF_8), WS);

        assertTrue(router.tryHandle("/q"));
        assertTrue(router.shouldQuit());
        assertFalse(sink.toString(StandardCharsets.UTF_8).contains("__QUIT__"));
    }

    private static void invokeSyncActiveModelIntoConfig(Config cfg, String activeModel) throws Exception {
        Method method = TalosBootstrap.class.getDeclaredMethod("syncActiveModelIntoConfig", Config.class, String.class);
        method.setAccessible(true);
        method.invoke(null, cfg, activeModel);
    }
}

