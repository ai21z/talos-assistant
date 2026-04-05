package dev.loqj.cli.modes;

import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ModeController}: alias registration,
 * mode switching, chat alias behavior, and auto-mode routing
 * with conversation context tracking.
 */
class ModeControllerTest {

    // ── defaultController setup ──────────────────────────────────────────

    @Test
    void defaultController_has_auto_as_default_mode() {
        ModeController mc = ModeController.defaultController();
        assertEquals("auto", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_chat_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("chat"), "Should accept 'chat' as a valid mode");
        assertEquals("chat", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_ask_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("ask"), "Should accept 'ask' as a valid mode");
        assertEquals("ask", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_rag_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("rag"));
        assertEquals("rag", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_dev_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("dev"));
        assertEquals("dev", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_auto_mode() {
        ModeController mc = ModeController.defaultController();
        mc.setActive("rag"); // change first
        assertTrue(mc.setActive("auto"));
        assertEquals("auto", mc.getActiveName());
    }

    @Test
    void defaultController_rejects_unknown_mode() {
        ModeController mc = ModeController.defaultController();
        assertFalse(mc.setActive("nonexistent"));
        assertEquals("auto", mc.getActiveName(), "Should remain auto after rejection");
    }

    // ── Alias behavior ──────────────────────────────────────────────────

    @Test
    void chat_and_ask_resolve_to_same_mode_instance() {
        ModeController mc = ModeController.defaultController();

        mc.setActive("ask");
        var askMode = mc.getActive().orElse(null);

        mc.setActive("chat");
        var chatMode = mc.getActive().orElse(null);

        assertNotNull(askMode);
        assertNotNull(chatMode);
        assertSame(askMode, chatMode, "chat and ask should resolve to the same Mode instance");
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    void setActive_rejects_null() {
        ModeController mc = ModeController.defaultController();
        assertFalse(mc.setActive(null));
    }

    @Test
    void setActive_rejects_blank() {
        ModeController mc = ModeController.defaultController();
        assertFalse(mc.setActive(""));
        assertFalse(mc.setActive("   "));
    }

    @Test
    void setActive_is_case_insensitive() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("CHAT"));
        assertEquals("chat", mc.getActiveName());

        assertTrue(mc.setActive("Rag"));
        assertEquals("rag", mc.getActiveName());

        assertTrue(mc.setActive("AUTO"));
        assertEquals("auto", mc.getActiveName());
    }

    // ── Prompt refresh callback ──────────────────────────────────────────

    @Test
    void promptRefreshCallback_fires_on_mode_change() {
        ModeController mc = ModeController.defaultController();
        int[] callCount = {0};
        mc.setPromptRefreshCallback(() -> callCount[0]++);

        mc.setActive("rag");
        assertEquals(1, callCount[0]);

        mc.setActive("chat");
        assertEquals(2, callCount[0]);
    }

    @Test
    void promptRefreshCallback_does_not_fire_on_rejection() {
        ModeController mc = ModeController.defaultController();
        int[] callCount = {0};
        mc.setPromptRefreshCallback(() -> callCount[0]++);

        mc.setActive("nonexistent");
        assertEquals(0, callCount[0]);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Auto-mode routing with stubs (end-to-end routing behavior)
    // ═══════════════════════════════════════════════════════════════════════

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    /**
     * Creates a ModeController with stub modes for isolated routing tests.
     * Each stub records whether it was dispatched.
     */
    private static ModeController stubController(
            RecordingStub devStub, RecordingStub ragStub, RecordingStub askStub) {
        var mc = new ModeController();
        mc.add(devStub).add(ragStub).add(askStub).alias("chat", askStub);
        return mc;
    }

    @Test
    void auto_mode_routes_greeting_to_ask() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("hey", WS, ctx);

        assertTrue(ask.invoked, "Greeting should route to ask/chat");
        assertFalse(rag.invoked, "Greeting must NOT reach rag");
        assertFalse(dev.invoked, "Greeting must NOT reach dev");
    }

    @Test
    void auto_mode_routes_file_ref_to_rag() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx);

        assertTrue(rag.invoked, "File ref should route to rag");
        assertFalse(ask.invoked, "File ref should NOT route to ask");
    }

    @Test
    void auto_mode_routes_show_command_to_dev() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("show build.gradle.kts", WS, ctx);

        assertTrue(dev.invoked, "show <file> should route to dev");
        assertFalse(rag.invoked, "show <file> should NOT route to rag");
    }

    @Test
    void auto_mode_routes_show_me_file_to_dev() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("show me build.gradle.kts", WS, ctx);

        assertTrue(dev.invoked, "show me <file> should route to dev");
        assertFalse(rag.invoked, "show me <file> should NOT route to rag");
    }

    // ── Conversation context tracking ────────────────────────────────────

    @Test
    void lastRoute_tracks_retrieve() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx);
        assertEquals(PromptRouter.Route.RETRIEVE, mc.lastRoute());
    }

    @Test
    void lastRoute_tracks_assist() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("hey", WS, ctx);
        assertEquals(PromptRouter.Route.ASSIST, mc.lastRoute());
    }

    @Test
    void lastRoute_not_reset_by_command() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx); // → RETRIEVE
        mc.route("ls src/", WS, ctx);                   // → COMMAND (neutral)

        // COMMAND should not reset retrieval context
        assertEquals(PromptRouter.Route.RETRIEVE, mc.lastRoute(),
                "COMMAND should not reset the retrieval context");
    }

    @Test
    void follow_up_after_retrieve_routes_to_rag() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx); // → RETRIEVE
        rag.reset();

        mc.route("what about the parse method?", WS, ctx); // → follow-up → RETRIEVE
        assertTrue(rag.invoked, "Follow-up after RETRIEVE should route to rag");
    }

    @Test
    void social_follow_up_after_retrieve_routes_to_ask() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx); // → RETRIEVE
        ask.reset();
        rag.reset();

        mc.route("thanks", WS, ctx); // → social → ASSIST
        assertTrue(ask.invoked, "Social follow-up should route to ask, not rag");
        assertFalse(rag.invoked, "Social follow-up must NOT route to rag");
    }

    @Test
    void prefixed_follow_up_after_retrieve_routes_to_rag() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx); // → RETRIEVE
        rag.reset();

        mc.route("cool, and the parser?", WS, ctx); // → prefixed follow-up → RETRIEVE
        assertTrue(rag.invoked, "Prefixed follow-up after RETRIEVE should route to rag");
    }

    @Test
    void new_tech_noun_question_routes_to_rag() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("what does the constructor do", WS, ctx);
        assertTrue(rag.invoked, "New tech noun + question should route to rag");
        assertFalse(ask.invoked, "New tech noun + question should NOT route to ask");
    }

    @Test
    void show_me_quoted_file_routes_to_dev() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("show me \"docs/My Guide.md\"", WS, ctx);
        assertTrue(dev.invoked, "show me quoted file should route to dev");
        assertFalse(rag.invoked, "show me quoted file should NOT route to rag");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Workspace-aware PascalCase routing (Layer 2c via ModeController)
    // ═══════════════════════════════════════════════════════════════════════

    /** Stub checker: recognizes "RagService" and "ModeController" as workspace symbols. */
    private static final WorkspaceSymbolChecker TEST_CHECKER = symbol -> {
        String lower = symbol.toLowerCase(java.util.Locale.ROOT);
        return "ragservice".equals(lower) || "modecontroller".equals(lower);
    };

    @Test
    void bare_workspace_symbol_routes_to_rag_with_checker() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        mc.setSymbolChecker(TEST_CHECKER);
        var ctx = Context.builder(new Config()).build();

        mc.route("RagService", WS, ctx);
        assertTrue(rag.invoked, "Bare workspace symbol should route to rag");
        assertFalse(ask.invoked, "Bare workspace symbol should NOT route to ask");
    }

    @Test
    void bare_brand_name_routes_to_ask_with_checker() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        mc.setSymbolChecker(TEST_CHECKER);
        var ctx = Context.builder(new Config()).build();

        mc.route("PowerPoint", WS, ctx);
        assertTrue(ask.invoked, "Brand name should route to ask even with checker");
        assertFalse(rag.invoked, "Brand name must NOT route to rag");
    }

    @Test
    void bare_workspace_symbol_without_checker_routes_to_ask() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        // No checker set — original behavior
        var ctx = Context.builder(new Config()).build();

        mc.route("RagService", WS, ctx);
        assertTrue(ask.invoked, "Without checker, bare PascalCase should route to ask");
        assertFalse(rag.invoked, "Without checker, bare PascalCase must NOT route to rag");
    }

    @Test
    void workspace_symbol_lastRoute_tracks_retrieve() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        mc.setSymbolChecker(TEST_CHECKER);
        var ctx = Context.builder(new Config()).build();

        mc.route("RagService", WS, ctx);
        assertEquals(PromptRouter.Route.RETRIEVE, mc.lastRoute(),
                "Workspace symbol should update lastRoute to RETRIEVE");
    }

    @Test
    void workspace_symbol_then_follow_up_stays_in_rag() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        mc.setSymbolChecker(TEST_CHECKER);
        var ctx = Context.builder(new Config()).build();

        // Turn 1: bare workspace symbol → RETRIEVE
        mc.route("RagService", WS, ctx);
        rag.reset();

        // Turn 2: follow-up → stays in RETRIEVE
        mc.route("what about the parse method?", WS, ctx);
        assertTrue(rag.invoked, "Follow-up after workspace symbol should stay in rag");
    }

    // ── Recording stub mode for isolated testing ─────────────────────────

    private static class RecordingStub implements Mode {
        final String modeName;
        boolean invoked;

        RecordingStub(String name) {
            this.modeName = name;
        }

        @Override public String name() { return modeName; }
        @Override public boolean canHandle(String raw) { return raw != null && !raw.isBlank(); }

        @Override
        public Optional<Result> handle(String raw, Path ws, Context ctx) {
            invoked = true;
            return Optional.of(new Result.Ok("stub:" + modeName));
        }

        void reset() { invoked = false; }
    }
}
