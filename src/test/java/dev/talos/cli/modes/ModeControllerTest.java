package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.core.index.WorkspaceSymbolChecker;
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
    void defaultController_legacy_chat_alias_resolves_to_canonical_agent_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("chat"), "Should accept 'chat' as a valid mode");
        assertEquals("agent", mc.getActiveName());
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
    void defaultController_legacy_dev_alias_resolves_to_canonical_agent_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("dev"));
        assertEquals("agent", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_agent_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("agent"));
        assertEquals("agent", mc.getActiveName());
    }

    @Test
    void defaultController_can_set_plan_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("plan"));
        assertEquals("plan", mc.getActiveName());
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
    void chat_resolves_to_agent_and_ask_resolves_to_askMode() {
        ModeController mc = ModeController.defaultController();

        mc.setActive("ask");
        var askMode = mc.getActive().orElse(null);

        mc.setActive("chat");
        var agentMode = mc.getActive().orElse(null);

        assertNotNull(askMode);
        assertNotNull(agentMode);
        assertNotSame(askMode, agentMode, "agent and ask should be different instances");
        assertTrue(agentMode instanceof UnifiedAssistantMode, "chat should resolve to the Agent engine");
        assertTrue(askMode instanceof AskMode, "ask should resolve to AskMode");
    }

    @Test
    void defaultController_legacy_unified_alias_resolves_to_canonical_agent_mode() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.setActive("unified"), "Should accept 'unified' as a valid mode");
        assertEquals("agent", mc.getActiveName());
    }

    // ── Reserved-mode and advertised-set discipline (T874) ───────────────

    @Test
    void defaultController_rejects_reserved_web_mode() {
        ModeController mc = ModeController.defaultController();
        assertFalse(mc.setActive("web"),
                "web is a reserved stub and must not be settable (no trap into a dead mode)");
        assertEquals("auto", mc.getActiveName(), "Should remain auto after rejecting reserved web");
    }

    @Test
    void availableModeNames_lists_public_modes_and_excludes_reserved_hidden_and_aliases() {
        ModeController mc = ModeController.defaultController();
        var names = mc.availableModeNames();
        assertTrue(names.contains("auto"), names.toString());
        assertTrue(names.contains("ask"), names.toString());
        assertTrue(names.contains("plan"), names.toString());
        assertTrue(names.contains("agent"), names.toString());
        assertFalse(names.contains("dev"), "dev is a hidden alias for agent");
        assertFalse(names.contains("rag"), "rag remains hidden legacy mode");
        assertFalse(names.contains("unified"), "unified is a hidden alias for agent");
        assertFalse(names.contains("web"), "reserved web must not be advertised as available");
        assertFalse(names.contains("chat"), "chat is an alias, not a canonical mode name");
    }

    @Test
    void explicit_mode_does_not_fall_through_to_other_modes() throws Exception {
        var declining = new DecliningStub("decline");
        var fallback = new RecordingStub("fallback");
        var mc = new ModeController()
                .add(declining)
                .add(fallback);
        var ctx = Context.builder(new Config()).build();

        assertTrue(mc.setActive("decline"));
        Optional<Result> result = mc.route("hello", WS, ctx);

        assertTrue(result.isEmpty(), "Explicit selected mode should be a hard boundary");
        assertFalse(fallback.invoked, "Explicit mode must not sweep into another mode");
    }

    @Test
    void reserved_web_mode_cannot_handle_even_when_routed_by_hint() throws Exception {
        ModeController mc = ModeController.defaultController();
        var ctx = Context.builder(new Config()).build();

        Optional<Result> result = mc.route("search the web", WS, ctx, "web");

        assertTrue(result.isEmpty(), "web is reserved and must be unsweepable");
    }

    @Test
    void reservedModeNames_contains_web() {
        ModeController mc = ModeController.defaultController();
        assertTrue(mc.reservedModeNames().contains("web"), mc.reservedModeNames().toString());
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
        assertEquals("agent", mc.getActiveName());

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
        mc.structuralMode(devStub).addHidden(ragStub).add(askStub).alias("chat", askStub);
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
    void auto_mode_routes_file_ref_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx);

        // In unified architecture: all non-COMMAND → unified (chat alias → ask stub)
        assertTrue(ask.invoked, "File ref should route to unified (chat/ask) in auto-mode");
        assertFalse(rag.invoked, "File ref should NOT route to rag in auto-mode");
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

    @Test
    void auto_mode_routes_natural_list_names_evidence_prompt_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("List names only at workspace root. Does ideas exist here? Answer from evidence only.", WS, ctx);

        assertTrue(ask.invoked, "Natural evidence prompt should route to unified assistant");
        assertFalse(dev.invoked, "Natural evidence prompt must not route to DevMode path extraction");
        assertFalse(rag.invoked, "Natural evidence prompt should not route to legacy rag mode");
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
        assertEquals(PromptClassifier.Route.RETRIEVE, mc.lastRoute());
    }

    @Test
    void lastRoute_tracks_assist() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("hey", WS, ctx);
        assertEquals(PromptClassifier.Route.ASSIST, mc.lastRoute());
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
        assertEquals(PromptClassifier.Route.RETRIEVE, mc.lastRoute(),
                "COMMAND should not reset the retrieval context");
    }

    @Test
    void follow_up_after_retrieve_routes_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx); // → classified RETRIEVE, dispatched to unified
        ask.reset();

        mc.route("what about the parse method?", WS, ctx); // → follow-up, dispatched to unified
        assertTrue(ask.invoked, "Follow-up should route to unified (chat/ask) in auto-mode");
        assertFalse(rag.invoked, "Follow-up should NOT route to rag in auto-mode");
    }

    @Test
    void social_follow_up_after_retrieve_routes_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx); // → classified RETRIEVE
        ask.reset();
        rag.reset();

        mc.route("thanks", WS, ctx); // → social → classified ASSIST → unified
        assertTrue(ask.invoked, "Social follow-up should route to unified");
        assertFalse(rag.invoked, "Social follow-up must NOT route to rag");
    }

    @Test
    void prefixed_follow_up_after_retrieve_routes_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("explain RagService.java", WS, ctx); // → classified RETRIEVE
        ask.reset();

        mc.route("cool, and the parser?", WS, ctx); // → prefixed follow-up → unified
        assertTrue(ask.invoked, "Prefixed follow-up should route to unified in auto-mode");
        assertFalse(rag.invoked, "Prefixed follow-up should NOT route to rag in auto-mode");
    }

    @Test
    void new_tech_noun_question_routes_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("what does the constructor do", WS, ctx);
        assertTrue(ask.invoked, "Tech noun + question should route to unified in auto-mode");
        assertFalse(rag.invoked, "Tech noun + question should NOT route to rag in auto-mode");
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
    void bare_workspace_symbol_routes_to_unified_with_checker() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        mc.setSymbolChecker(TEST_CHECKER);
        var ctx = Context.builder(new Config()).build();

        mc.route("RagService", WS, ctx);
        assertTrue(ask.invoked, "Workspace symbol should route to unified in auto-mode");
        assertFalse(rag.invoked, "Workspace symbol should NOT route to rag in auto-mode");
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
        // No checker set - original behavior
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
        assertEquals(PromptClassifier.Route.RETRIEVE, mc.lastRoute(),
                "Workspace symbol should update lastRoute to RETRIEVE");
    }

    @Test
    void workspace_symbol_then_follow_up_stays_in_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        mc.setSymbolChecker(TEST_CHECKER);
        var ctx = Context.builder(new Config()).build();

        // Turn 1: bare workspace symbol → unified
        mc.route("RagService", WS, ctx);
        ask.reset();

        // Turn 2: follow-up → stays in unified
        mc.route("what about the parse method?", WS, ctx);
        assertTrue(ask.invoked, "Follow-up after workspace symbol should stay in unified");
        assertFalse(rag.invoked, "Follow-up should NOT route to rag in auto-mode");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cache invalidation delegation
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void invalidateSymbolCache_delegates_to_checker() {
        var mc = new ModeController();
        int[] invalidated = {0};
        WorkspaceSymbolChecker checker = new WorkspaceSymbolChecker() {
            @Override public boolean existsInWorkspace(String symbol) { return false; }
            @Override public void invalidateCache() { invalidated[0]++; }
        };
        mc.setSymbolChecker(checker);

        mc.invalidateSymbolCache();
        assertEquals(1, invalidated[0], "Should delegate to checker's invalidateCache()");
    }

    @Test
    void invalidateSymbolCache_is_safe_without_checker() {
        var mc = new ModeController();
        // No checker set - should be a safe no-op
        assertDoesNotThrow(mc::invalidateSymbolCache);
    }

    @Test
    void invalidateSymbolCache_can_be_called_multiple_times() {
        var mc = new ModeController();
        int[] count = {0};
        mc.setSymbolChecker(new WorkspaceSymbolChecker() {
            @Override public boolean existsInWorkspace(String symbol) { return false; }
            @Override public void invalidateCache() { count[0]++; }
        });

        mc.invalidateSymbolCache();
        mc.invalidateSymbolCache();
        assertEquals(2, count[0], "Multiple invalidations should all delegate");
    }

    @Test
    void getSymbolChecker_returns_set_checker() {
        var mc = new ModeController();
        assertNull(mc.getSymbolChecker(), "Should be null by default");

        mc.setSymbolChecker(TEST_CHECKER);
        assertSame(TEST_CHECKER, mc.getSymbolChecker());

        mc.setSymbolChecker(null);
        assertNull(mc.getSymbolChecker(), "Should be null after clearing");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Action-intent routing through auto-mode (unified architecture)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void action_with_pascal_case_routes_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("write a test for RagService", WS, ctx);

        assertTrue(ask.invoked, "Action+PascalCase should route to unified in auto-mode");
        assertFalse(rag.invoked, "Action+PascalCase should NOT route to rag in auto-mode");
    }

    @Test
    void action_with_anchored_noun_routes_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("refactor the parser", WS, ctx);

        assertTrue(ask.invoked, "Action+tech noun should route to unified in auto-mode");
        assertFalse(rag.invoked, "Action+tech noun should NOT route to rag in auto-mode");
    }

    @Test
    void action_without_workspace_signal_routes_to_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("write a poem", WS, ctx);

        assertTrue(ask.invoked, "Action without workspace signal should route to unified");
        assertFalse(rag.invoked, "Action without workspace signal should NOT route to rag");
    }

    @Test
    void action_updates_lastRoute_to_retrieve() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("refactor ModeController", WS, ctx);
        // lastRoute still tracks PromptClassifier classification for diagnostics
        assertEquals(PromptClassifier.Route.RETRIEVE, mc.lastRoute(),
                "Action+PascalCase should update lastRoute to RETRIEVE");
    }

    @Test
    void follow_up_after_action_stays_in_unified() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.route("refactor the parser", WS, ctx); // → classified RETRIEVE, dispatched to unified
        ask.reset();

        mc.route("what about edge cases?", WS, ctx); // → follow-up → unified
        assertTrue(ask.invoked, "Follow-up after action should stay in unified");
        assertFalse(rag.invoked, "Follow-up should NOT route to rag in auto-mode");
    }

    // ── Explicit mode: /mode rag still works ─────────────────────────────

    @Test
    void explicit_rag_mode_still_routes_to_rag() throws Exception {
        var dev = new RecordingStub("dev");
        var rag = new RecordingStub("rag");
        var ask = new RecordingStub("ask");
        var mc = stubController(dev, rag, ask);
        var ctx = Context.builder(new Config()).build();

        mc.setActive("rag");
        mc.route("explain RagService.java", WS, ctx);

        assertTrue(rag.invoked, "Explicit rag mode should still route to rag");
        assertFalse(ask.invoked, "Explicit rag mode should NOT route to ask/unified");
    }

    @Test
    void active_agent_mode_routes_structural_commands_to_structural_handler() throws Exception {
        var structural = new RecordingStub("structural");
        var agent = new RecordingStub("agent");
        var mc = new ModeController()
                .structuralMode(structural)
                .add(agent)
                .alias("dev", agent);
        var ctx = Context.builder(new Config()).build();

        assertTrue(mc.setActive("dev"));
        assertEquals("agent", mc.getActiveName());

        Optional<Result> result = mc.route("ls", WS, ctx);

        assertTrue(result.isPresent());
        assertTrue(structural.invoked, "agent/dev compatibility should keep deterministic structural commands");
        assertFalse(agent.invoked, "structural command should not go through the agent model path");
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

    private static final class DecliningStub extends RecordingStub {
        DecliningStub(String name) {
            super(name);
        }

        @Override public boolean canHandle(String raw) { return false; }
    }
}
