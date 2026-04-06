package dev.talos.cli.commands;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.modes.Mode;
import dev.talos.cli.modes.WorkspaceSymbolChecker;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RouteCommand}: verifies the {:code :route} diagnostic
 * command produces correct, human-readable route explanations.
 */
class RouteCommandTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    // ── Stub checker: recognizes workspace symbols ────────────────────────

    private static final WorkspaceSymbolChecker CHECKER = symbol -> {
        String lower = symbol.toLowerCase(Locale.ROOT);
        return "ragservice".equals(lower) || "modecontroller".equals(lower);
    };

    // ── Helpers ───────────────────────────────────────────────────────────

    private static ModeController controllerWithChecker() {
        var mc = stubController();
        mc.setSymbolChecker(CHECKER);
        return mc;
    }

    private static ModeController stubController() {
        var mc = new ModeController();
        mc.add(new StubMode("dev"));
        mc.add(new StubMode("rag"));
        var ask = new StubMode("ask");
        mc.add(ask);
        mc.alias("chat", ask);
        return mc;
    }

    private static Context ctx() {
        return Context.builder(new Config()).build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Spec
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void spec_name_is_route() {
        var cmd = new RouteCommand(stubController());
        assertEquals("route", cmd.spec().name());
    }

    @Test
    void spec_has_explain_route_alias() {
        var cmd = new RouteCommand(stubController());
        assertTrue(cmd.spec().aliases().contains("explain-route"));
    }

    @Test
    void spec_group_is_debug() {
        var cmd = new RouteCommand(stubController());
        assertEquals(CommandGroup.DEBUG, cmd.spec().group());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Empty / blank args → usage
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void empty_args_shows_usage() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("", ctx());
        assertInstanceOf(Result.Info.class, result);
        assertTrue(((Result.Info) result).text.contains("Usage:"));
    }

    @Test
    void null_args_shows_usage() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute(null, ctx());
        assertInstanceOf(Result.Info.class, result);
    }

    @Test
    void blank_args_shows_usage() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("   ", ctx());
        assertInstanceOf(Result.Info.class, result);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Route output structure
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void output_contains_route_line() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("hey", ctx());
        assertInstanceOf(Result.Ok.class, result);
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("Route:"), "Output should contain 'Route:' label");
        assertTrue(text.contains("ASSIST"), "Greeting should route to ASSIST");
    }

    @Test
    void output_contains_trigger_line() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("hey", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("Trigger:"), "Output should contain 'Trigger:' label");
    }

    @Test
    void output_contains_checker_status() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("hey", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("Checker:"), "Output should contain 'Checker:' label");
        assertTrue(text.contains("not available"), "Should report checker as not available");
    }

    @Test
    void output_contains_steps() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("hey", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("Steps:"), "Output should contain 'Steps:' section");
        assertTrue(text.contains("•"), "Steps should use bullet points");
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Specific routing scenarios
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void route_greeting_shows_assist() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("hey", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("ASSIST"));
        assertTrue(text.contains("default"));
    }

    @Test
    void route_file_ref_shows_retrieve() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("explain RagService.java", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("RETRIEVE"));
        assertTrue(text.contains("file reference"));
    }

    @Test
    void route_dev_command_shows_command() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("ls src/", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("COMMAND"));
        assertTrue(text.contains("dev command"));
    }

    @Test
    void route_show_me_file_shows_command() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("show me build.gradle.kts", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("COMMAND"));
        assertTrue(text.contains("show-me-file"));
    }

    @Test
    void route_workspace_frame_shows_retrieve() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("how does this project work", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("RETRIEVE"));
        assertTrue(text.contains("workspace framing"));
    }

    @Test
    void route_pascal_in_question_shows_retrieve() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("what does RagService do", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("RETRIEVE"));
        assertTrue(text.contains("PascalCase"));
    }

    @Test
    void route_anchored_noun_in_question_shows_retrieve() {
        var cmd = new RouteCommand(stubController());
        var result = cmd.execute("what does the pipeline do", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("RETRIEVE"));
        assertTrue(text.contains("anchored tech noun"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Checker integration
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void checker_active_reported_when_set() {
        var cmd = new RouteCommand(controllerWithChecker());
        var result = cmd.execute("RagService", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("Checker:") && text.contains("active"),
                "Should report checker as active");
    }

    @Test
    void workspace_symbol_routes_to_retrieve_with_checker() {
        var cmd = new RouteCommand(controllerWithChecker());
        var result = cmd.execute("RagService", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("RETRIEVE"));
        assertTrue(text.contains("workspace symbol match"));
    }

    @Test
    void brand_name_routes_to_assist_with_checker() {
        var cmd = new RouteCommand(controllerWithChecker());
        var result = cmd.execute("PowerPoint", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("ASSIST"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Conversation context (lastRoute)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void first_turn_reports_no_prior_route() {
        var mc = stubController();
        var cmd = new RouteCommand(mc);
        var result = cmd.execute("hey", ctx());
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("first turn") || text.contains("no prior route"));
    }

    @Test
    void after_retrieve_reports_last_route() throws Exception {
        var mc = stubController();
        var cmdCtx = ctx();
        // Force a RETRIEVE turn to set lastRoute
        mc.route("explain RagService.java", WS, cmdCtx);

        var cmd = new RouteCommand(mc);
        var result = cmd.execute("what about it?", cmdCtx);
        String text = ((Result.Ok) result).text;
        assertTrue(text.contains("RETRIEVE"),
                "Follow-up after RETRIEVE should show RETRIEVE");
        assertTrue(text.contains("last route was RETRIEVE") || text.contains("Context:"),
                "Should report the prior route context");
    }

    // ── Stub mode for controller testing ──────────────────────────────────

    private static class StubMode implements Mode {
        final String modeName;
        StubMode(String name) { this.modeName = name; }
        @Override public String name() { return modeName; }
        @Override public boolean canHandle(String raw) { return raw != null && !raw.isBlank(); }
        @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
            return Optional.of(new Result.Ok("stub:" + modeName));
        }
    }
}

