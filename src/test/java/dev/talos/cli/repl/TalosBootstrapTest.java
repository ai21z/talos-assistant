package dev.talos.cli.repl;

import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.NoOpSessionStore;
import dev.talos.runtime.SessionData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TalosBootstrap} — the composition root.
 *
 * <p>Verifies that the bootstrap wires everything correctly and
 * produces a functional ReplRouter without exceptions.
 */
class TalosBootstrapTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

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
        JsonSessionStore store = new JsonSessionStore(home.resolve(".talos").resolve("sessions"));
        String sessionId = JsonSessionStore.sessionIdFor(workspace);
        store.save(new SessionData(sessionId, workspace.toString(), "", 1, Instant.now(),
                List.of(
                        new SessionData.Turn("user", user, ""),
                        new SessionData.Turn("assistant", assistant, "ok")),
                "ollama/qwen2.5-coder:14b"));
    }

    @Test
    void createProducesWorkingRouter() {
        SessionState session = new SessionState() {
            private int k = 6; private boolean dbg;
            public int getK() { return k; } public void setK(int v) { k = v; }
            public boolean isDebug() { return dbg; } public void setDebug(boolean on) { dbg = on; }
        };

        ReplRouter router = TalosBootstrap.create(session, new Config(), System.out, WS);

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

        ReplRouter router = TalosBootstrap.create(session, null, null, null);
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

            ReplRouter router = TalosBootstrap.create(session,
                    configWithSessionPolicy(true, false),
                    new PrintStream(java.io.OutputStream.nullOutputStream()), workspace);

            assertTrue(router.getStartupNotice().contains("saved session found"));
            assertTrue(router.getStartupNotice().contains("Not loaded"));
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

            ReplRouter router = TalosBootstrap.create(session,
                    configWithSessionPolicy(true, true),
                    new PrintStream(java.io.OutputStream.nullOutputStream()), workspace);

            assertTrue(router.getStartupNotice().contains("restored 1 prior exchange"));
            assertTrue(router.context().conversationManager().hasHistory());
            assertTrue(router.context().memory().get().contains("old BMI answer"));
        });
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

            ReplRouter router = TalosBootstrap.create(session,
                    configWithSessionPolicy(false, true),
                    new PrintStream(java.io.OutputStream.nullOutputStream()), workspace);

            assertTrue(router.getStartupNotice().isBlank());
            assertFalse(router.context().conversationManager().hasHistory());
            assertInstanceOf(NoOpSessionStore.class, router.getRuntimeSession().store());
        });
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
    void syncActiveManagedModelUpdatesLlmWithoutMutatingLegacyOllamaBlock() throws Exception {
        Config cfg = new Config(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> ollama = (Map<String, Object>) cfg.data.get("ollama");
        String originalOllamaModel = String.valueOf(ollama.get("model"));

        invokeSyncActiveModelIntoConfig(cfg, "llama_cpp/qwen2.5-coder-14b");

        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) cfg.data.get("llm");
        assertEquals("llama_cpp", llm.get("default_backend"));
        assertEquals("qwen2.5-coder-14b", llm.get("model"));
        assertEquals(originalOllamaModel, ollama.get("model"),
                "selecting a managed model must not rewrite the legacy Ollama model block");
    }

    @Test
    void syncActiveOllamaModelUpdatesLlmAndLegacyOllamaCompatibilityBlock() throws Exception {
        Config cfg = new Config(null);

        invokeSyncActiveModelIntoConfig(cfg, "ollama/gpt-oss:20b");

        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) cfg.data.get("llm");
        @SuppressWarnings("unchecked")
        Map<String, Object> ollama = (Map<String, Object>) cfg.data.get("ollama");
        assertEquals("ollama", llm.get("default_backend"));
        assertEquals("gpt-oss:20b", llm.get("model"));
        assertEquals("gpt-oss:20b", ollama.get("model"));
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

        ReplRouter router = TalosBootstrap.create(session, new Config(), System.out, WS);
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

        ReplRouter router = TalosBootstrap.create(session, new Config(),
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

        ReplRouter router = TalosBootstrap.create(session, new Config(),
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
            ReplRouter router = TalosBootstrap.create(session,
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
        ReplRouter router = TalosBootstrap.create(session, new Config(),
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

