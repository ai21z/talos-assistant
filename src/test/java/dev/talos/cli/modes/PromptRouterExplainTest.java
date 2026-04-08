package dev.talos.cli.modes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static dev.talos.cli.modes.PromptRouter.Route.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PromptRouter#explainRoute} — verifies that routing
 * decisions produce correct trigger labels and evaluation step traces.
 *
 * <p>These tests complement {@link PromptRouterTest} (which only checks
 * the Route enum). Here we validate the full {@link PromptRouter.RouteResult}
 * including trigger strings and step ordering.
 */
class PromptRouterExplainTest {

    // ── Stub checkers ─────────────────────────────────────────────────────

    private static final WorkspaceSymbolChecker WORKSPACE_CHECKER = symbol -> {
        String lower = symbol.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "ragservice", "modecontroller", "devmode" -> true;
            default -> false;
        };
    };

    private static final WorkspaceSymbolChecker EMPTY_CHECKER = symbol -> false;

    // ═══════════════════════════════════════════════════════════════════════
    //  RouteResult invariants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void explainRoute_never_returns_null() {
        assertNotNull(PromptRouter.explainRoute(null, null, null));
        assertNotNull(PromptRouter.explainRoute("", null, null));
        assertNotNull(PromptRouter.explainRoute("hey", null, null));
    }

    @Test
    void explainRoute_steps_list_is_immutable() {
        var result = PromptRouter.explainRoute("hey", null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> result.steps().add("should fail"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Trigger labels per routing layer
    // ═══════════════════════════════════════════════════════════════════════

    // ── Empty input ───────────────────────────────────────────────────────

    @Test
    void empty_input_trigger() {
        var r = PromptRouter.explainRoute(null, null, null);
        assertEquals(ASSIST, r.route());
        assertEquals("empty input", r.trigger());
        assertTrue(r.steps().isEmpty(), "No steps for empty input");
    }

    @Test
    void blank_input_trigger() {
        var r = PromptRouter.explainRoute("   ", null, null);
        assertEquals(ASSIST, r.route());
        assertEquals("empty input", r.trigger());
    }

    // ── Layer 1: dev command ──────────────────────────────────────────────

    @Test
    void dev_command_trigger() {
        var r = PromptRouter.explainRoute("ls src/", null, null);
        assertEquals(COMMAND, r.route());
        assertEquals("dev command", r.trigger());
        assertTrue(r.steps().contains("matched dev command pattern"));
    }

    @Test
    void show_me_file_trigger() {
        var r = PromptRouter.explainRoute("show me build.gradle.kts", null, null);
        assertEquals(COMMAND, r.route());
        assertEquals("show-me-file compound command", r.trigger());
        // Should have passed through dev command check first
        assertTrue(r.steps().contains("no dev command match"));
        assertTrue(r.steps().contains("matched 'show me <file>' pattern"));
    }

    // ── Layer 2: workspace framing ────────────────────────────────────────

    @Test
    void workspace_framing_trigger() {
        var r = PromptRouter.explainRoute("how does this project handle auth", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("workspace framing", r.trigger());
        assertTrue(r.steps().contains("matched workspace framing phrase"));
    }

    // ── Layer 2: file reference ───────────────────────────────────────────

    @Test
    void file_reference_trigger() {
        var r = PromptRouter.explainRoute("explain RagService.java", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("file reference", r.trigger());
        assertTrue(r.steps().contains("matched file reference pattern"));
        // Should have checked workspace framing first
        assertTrue(r.steps().contains("no workspace framing"));
    }

    // ── Layer 2b: PascalCase + question ───────────────────────────────────

    @Test
    void pascal_case_in_question_trigger() {
        var r = PromptRouter.explainRoute("what does RagService do", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("PascalCase identifier in question", r.trigger());
        assertTrue(r.steps().contains("question context + PascalCase identifier"));
    }

    // ── Layer 2b: anchored tech noun + question ───────────────────────────

    @Test
    void anchored_tech_noun_trigger() {
        var r = PromptRouter.explainRoute("what does the pipeline do", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("anchored tech noun in question", r.trigger());
        assertTrue(r.steps().contains("question context + anchored tech noun"));
    }

    // ── Layer 2c: workspace symbol match ──────────────────────────────────

    @Test
    void workspace_symbol_trigger() {
        var r = PromptRouter.explainRoute("RagService", null, WORKSPACE_CHECKER);
        assertEquals(RETRIEVE, r.route());
        assertEquals("workspace symbol match", r.trigger());
        assertTrue(r.steps().contains("PascalCase confirmed in workspace index"));
    }

    @Test
    void workspace_symbol_not_found_step() {
        var r = PromptRouter.explainRoute("PowerPoint", null, WORKSPACE_CHECKER);
        assertEquals(ASSIST, r.route());
        assertTrue(r.steps().contains("no workspace symbol match"),
                "Should report that workspace symbol was not found");
    }

    @Test
    void no_checker_step() {
        var r = PromptRouter.explainRoute("RagService", null, null);
        assertEquals(ASSIST, r.route());
        assertTrue(r.steps().contains("workspace checker not available"));
    }

    // ── Layer 3: sticky follow-up ─────────────────────────────────────────

    @Test
    void sticky_follow_up_trigger() {
        var r = PromptRouter.explainRoute("what about it?", RETRIEVE, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("sticky retrieval follow-up", r.trigger());
        assertTrue(r.steps().contains("follow-up after RETRIEVE turn"));
    }

    @Test
    void after_retrieve_not_follow_up_step() {
        var r = PromptRouter.explainRoute("hey", RETRIEVE, null);
        assertEquals(ASSIST, r.route());
        assertTrue(r.steps().contains("after RETRIEVE but not a follow-up pattern"));
    }

    // ── Layer 4: default assist ───────────────────────────────────────────

    @Test
    void default_assist_trigger() {
        var r = PromptRouter.explainRoute("hey", null, null);
        assertEquals(ASSIST, r.route());
        assertEquals("default — no retrieval evidence", r.trigger());
    }

    @Test
    void default_assist_reports_no_context() {
        var r = PromptRouter.explainRoute("hey", null, null);
        assertTrue(r.steps().contains("no conversation context"));
    }

    @Test
    void default_assist_after_assist_reports_last_route() {
        var r = PromptRouter.explainRoute("hey", ASSIST, null);
        assertTrue(r.steps().contains("last route was ASSIST (not RETRIEVE)"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Step trace ordering and completeness
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void assist_default_traverses_all_layers() {
        var r = PromptRouter.explainRoute("hey", null, EMPTY_CHECKER);
        assertEquals(ASSIST, r.route());

        // Verify the trace shows all negative checks in order
        var steps = r.steps();
        assertTrue(steps.size() >= 6, "Should traverse all layers, got: " + steps);
        assertEquals("no dev command match", steps.get(0));
        assertEquals("no show-me-file match", steps.get(1));
        assertEquals("no workspace framing", steps.get(2));
        assertEquals("no file reference", steps.get(3));
        // isQ check
        assertTrue(steps.stream().anyMatch(s ->
                s.contains("not question-like") || s.contains("question-like but")));
        // Workspace checker step
        assertTrue(steps.contains("no workspace symbol match"));
        // No conversation context
        assertTrue(steps.contains("no conversation context"));
    }

    @Test
    void early_exit_on_dev_command_has_minimal_steps() {
        var r = PromptRouter.explainRoute("ls", null, WORKSPACE_CHECKER);
        assertEquals(COMMAND, r.route());
        assertEquals(1, r.steps().size(), "Early exit should only have one step");
    }

    @Test
    void question_with_pascal_case_shows_no_file_ref_check() {
        var r = PromptRouter.explainRoute("explain RagService", null, null);
        // "explain" + PascalCase → Layer 2b fires after Layer 2 checks
        assertEquals(RETRIEVE, r.route());
        var steps = r.steps();
        assertTrue(steps.contains("no workspace framing"));
        assertTrue(steps.contains("no file reference"));
        assertTrue(steps.contains("question context + PascalCase identifier"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Realistic user scenarios — end-to-end trace verification
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void scenario_hey() {
        var r = PromptRouter.explainRoute("hey", null, null);
        assertEquals(ASSIST, r.route());
        assertEquals("default — no retrieval evidence", r.trigger());
        assertFalse(r.steps().isEmpty());
    }

    @Test
    void scenario_explain_ragservice_java() {
        var r = PromptRouter.explainRoute("explain RagService.java", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("file reference", r.trigger());
    }

    @Test
    void scenario_bare_ragservice_with_checker() {
        var r = PromptRouter.explainRoute("RagService", null, WORKSPACE_CHECKER);
        assertEquals(RETRIEVE, r.route());
        assertEquals("workspace symbol match", r.trigger());
    }

    @Test
    void scenario_bare_powerpoint_with_checker() {
        var r = PromptRouter.explainRoute("PowerPoint", null, WORKSPACE_CHECKER);
        assertEquals(ASSIST, r.route());
        assertEquals("default — no retrieval evidence", r.trigger());
    }

    @Test
    void scenario_show_me_build_gradle() {
        var r = PromptRouter.explainRoute("show me build.gradle.kts", null, null);
        assertEquals(COMMAND, r.route());
        assertEquals("show-me-file compound command", r.trigger());
    }

    @Test
    void scenario_follow_up_after_retrieve() {
        var r = PromptRouter.explainRoute("what about it?", RETRIEVE, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("sticky retrieval follow-up", r.trigger());
    }

    @Test
    void scenario_thanks_after_retrieve_breaks_to_assist() {
        var r = PromptRouter.explainRoute("thanks", RETRIEVE, null);
        assertEquals(ASSIST, r.route());
        assertEquals("default — no retrieval evidence", r.trigger());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Action-intent trigger labels and traces
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void action_with_pascal_case_trigger() {
        var r = PromptRouter.explainRoute("write a test for RagService", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("PascalCase identifier in action", r.trigger());
        assertTrue(r.steps().contains("action context + PascalCase identifier"));
    }

    @Test
    void action_with_anchored_noun_trigger() {
        var r = PromptRouter.explainRoute("fix the parser", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("anchored tech noun in action", r.trigger());
        assertTrue(r.steps().contains("action context + anchored tech noun"));
    }

    @Test
    void action_without_workspace_signal_shows_action_like_step() {
        var r = PromptRouter.explainRoute("write a poem", null, null);
        assertEquals(ASSIST, r.route());
        assertTrue(r.steps().stream().anyMatch(s -> s.contains("action-like but")));
    }

    @Test
    void question_still_uses_question_label() {
        // Verify questions still get "question" labels, not "action"
        var r = PromptRouter.explainRoute("what does RagService do", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("PascalCase identifier in question", r.trigger());
        assertTrue(r.steps().contains("question context + PascalCase identifier"));
    }

    @Test
    void action_label_takes_priority_when_both_action_and_question() {
        // "fix the parser?" is both action-like and question-like (ends with ?)
        var r = PromptRouter.explainRoute("fix the parser?", null, null);
        assertEquals(RETRIEVE, r.route());
        // Action is checked first in the ternary
        assertEquals("anchored tech noun in action", r.trigger());
    }

    @Test
    void prefixed_action_trigger() {
        var r = PromptRouter.explainRoute("hey, refactor ModeController", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("PascalCase identifier in action", r.trigger());
    }

    @Test
    void scenario_refactor_ragservice() {
        var r = PromptRouter.explainRoute("refactor RagService", null, null);
        assertEquals(RETRIEVE, r.route());
        assertEquals("PascalCase identifier in action", r.trigger());
        var steps = r.steps();
        assertTrue(steps.contains("no workspace framing"));
        assertTrue(steps.contains("no file reference"));
        assertTrue(steps.contains("action context + PascalCase identifier"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Route result consistency: route(args) == explainRoute(args).route()
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {
        "hey",
        "ls",
        "show me build.gradle.kts",
        "explain RagService.java",
        "what does the pipeline do",
        "I use PowerPoint",
        "RagService",
    })
    void route_and_explainRoute_agree(String input) {
        var route = PromptRouter.route(input);
        var explain = PromptRouter.explainRoute(input, null, null);
        assertEquals(route, explain.route(),
                "route() and explainRoute() must agree for '" + input + "'");
    }

    @Test
    void route_and_explainRoute_agree_with_context() {
        assertEquals(
                PromptRouter.route("what about it?", RETRIEVE),
                PromptRouter.explainRoute("what about it?", RETRIEVE, null).route());
        assertEquals(
                PromptRouter.route("thanks", RETRIEVE),
                PromptRouter.explainRoute("thanks", RETRIEVE, null).route());
    }

    @Test
    void route_and_explainRoute_agree_with_checker() {
        assertEquals(
                PromptRouter.route("RagService", null, WORKSPACE_CHECKER),
                PromptRouter.explainRoute("RagService", null, WORKSPACE_CHECKER).route());
        assertEquals(
                PromptRouter.route("PowerPoint", null, WORKSPACE_CHECKER),
                PromptRouter.explainRoute("PowerPoint", null, WORKSPACE_CHECKER).route());
    }
}

