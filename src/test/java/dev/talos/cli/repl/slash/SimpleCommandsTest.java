package dev.talos.cli.repl.slash;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.DebugLevel;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for simple stateless REPL commands: HelpCommand, QuitCommand,
 * DebugCommand, KCommand, AuditToggleCommand, PolicyCommand, ModeCommand.
 *
 * <p>Uses {@code Context.builder(new Config()).build()} for minimal wiring —
 * no external services required.
 */
@DisplayName("REPL commands — simple stateless")
class SimpleCommandsTest {

    private final Context ctx = Context.builder(new Config()).build();

    // ═══════════════════════════════════════════════════════════════════════
    //  QuitCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("QuitCommand")
    class Quit {

        @Test void sets_quit_flag() {
            var flag = new AtomicBoolean(false);
            var cmd = new QuitCommand(flag);
            cmd.execute("", ctx);
            assertTrue(flag.get(), "Flag should be set after execute");
        }

        @Test void returns_quit_token() {
            var cmd = new QuitCommand(new AtomicBoolean());
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains(QuitCommand.TOKEN));
        }

        @Test void spec_name_is_q() {
            var cmd = new QuitCommand(new AtomicBoolean());
            assertEquals("q", cmd.spec().name());
            assertTrue(cmd.spec().aliases().contains("quit"));
            assertTrue(cmd.spec().aliases().contains("exit"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  DebugCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DebugCommand")
    class Debug {

        private final StubRuntime rt = new StubRuntime();
        private final DebugCommand cmd = new DebugCommand(rt);

        @Test void on_without_explicit_level_is_invalid() {
            Result r = cmd.execute("on", ctx);
            assertInstanceOf(Result.Error.class, r);
            assertEquals(DebugLevel.OFF, rt.getDebugLevel());
        }

        @Test void off_disables_debug() {
            rt.setDebug(true);
            cmd.execute("off", ctx);
            assertFalse(rt.isDebug());
            assertEquals(DebugLevel.OFF, rt.getDebugLevel());
        }

        @Test void true_alias() {
            cmd.execute("true", ctx);
            assertTrue(rt.isDebug());
        }

        @Test void false_alias() {
            rt.setDebug(true);
            cmd.execute("false", ctx);
            assertFalse(rt.isDebug());
        }

        @Test void one_alias() {
            cmd.execute("1", ctx);
            assertTrue(rt.isDebug());
        }

        @Test void zero_alias() {
            rt.setDebug(true);
            cmd.execute("0", ctx);
            assertFalse(rt.isDebug());
        }

        @Test void rag_level_sets_retrieval_debug_intent() {
            Result r = cmd.execute("rag", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertEquals(DebugLevel.RAG, rt.getDebugLevel());
            assertTrue(r.toString().contains("rag"));
        }

        @Test void tools_level_sets_tool_debug_intent() {
            cmd.execute("tools", ctx);
            assertEquals(DebugLevel.TOOLS, rt.getDebugLevel());
        }

        @Test void trace_level_sets_trace_debug_intent() {
            cmd.execute("trace", ctx);
            assertEquals(DebugLevel.TRACE, rt.getDebugLevel());
        }

        @Test void on_suffix_sets_non_off_debug_level() {
            for (var entry : Map.of(
                    "brief on", DebugLevel.BRIEF,
                    "rag on", DebugLevel.RAG,
                    "tools on", DebugLevel.TOOLS,
                    "prompt on", DebugLevel.PROMPT,
                    "trace on", DebugLevel.TRACE
            ).entrySet()) {
                cmd.execute("off", ctx);
                Result r = cmd.execute(entry.getKey(), ctx);
                assertInstanceOf(Result.Info.class, r, entry.getKey());
                assertEquals(entry.getValue(), rt.getDebugLevel(), entry.getKey());
            }
        }

        @Test void off_suffix_after_level_disables_debug() {
            rt.setDebugLevel(DebugLevel.PROMPT);
            Result r = cmd.execute("prompt off", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertEquals(DebugLevel.OFF, rt.getDebugLevel());
        }

        @Test void no_args_shows_current() {
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("debug"));
        }

        @Test void invalid_arg_returns_error() {
            Result r = cmd.execute("maybe", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void null_args_shows_current() {
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Info.class, r);
        }

        @Test void spec_name() {
            assertEquals("debug", cmd.spec().name());
            assertTrue(cmd.spec().usage().contains("trace"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  KCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("KCommand")
    class K {

        private final StubRuntime rt = new StubRuntime();
        private final KCommand cmd = new KCommand(rt);

        @Test void set_k() {
            cmd.execute("10", ctx);
            assertEquals(10, rt.getK());
        }

        @Test void show_k_no_args() {
            rt.setK(5);
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("5"));
        }

        @Test void show_k_null_args() {
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Info.class, r);
        }

        @Test void k_must_be_positive() {
            Result r = cmd.execute("0", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void k_negative_rejected() {
            Result r = cmd.execute("-1", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void k_non_integer_rejected() {
            Result r = cmd.execute("abc", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void k_large_value_accepted() {
            cmd.execute("100", ctx);
            assertEquals(100, rt.getK());
        }

        @Test void spec_name() {
            assertEquals("k", cmd.spec().name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  AuditToggleCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AuditToggleCommand")
    class AuditToggle {

        private final AuditToggleCommand cmd = new AuditToggleCommand();

        @Test void on_enables_audit() {
            ctx.audit().setEnabled(false);
            Result r = cmd.execute("on", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(ctx.audit().isEnabled());
            assertTrue(r.toString().contains("ON"));
        }

        @Test void off_disables_audit() {
            ctx.audit().setEnabled(true);
            Result r = cmd.execute("off", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertFalse(ctx.audit().isEnabled());
            assertTrue(r.toString().contains("OFF"));
        }

        @Test void enable_alias() {
            cmd.execute("enable", ctx);
            assertTrue(ctx.audit().isEnabled());
        }

        @Test void disable_alias() {
            ctx.audit().setEnabled(true);
            cmd.execute("disable", ctx);
            assertFalse(ctx.audit().isEnabled());
        }

        @Test void invalid_arg_returns_error() {
            Result r = cmd.execute("toggle", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void empty_arg_returns_error() {
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void null_arg_returns_error() {
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void spec_name() {
            assertEquals("audit", cmd.spec().name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  PolicyCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PolicyCommand")
    class Policy {

        private final PolicyCommand cmd = new PolicyCommand();

        @Test void returns_table_result() {
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Table.class, r);
        }

        @Test void table_has_expected_columns() {
            var table = (Result.Table) cmd.execute("", ctx);
            assertEquals("Policy", table.title);
            assertEquals(2, table.columns.size());
            assertTrue(table.columns.contains("Key"));
            assertTrue(table.columns.contains("Value"));
        }

        @Test void table_has_net_enabled_row() {
            var table = (Result.Table) cmd.execute("", ctx);
            boolean found = table.rows.stream()
                    .anyMatch(row -> row.get(0).equals("net.enabled"));
            assertTrue(found, "Should contain net.enabled row");
        }

        @Test void table_has_max_bytes_row() {
            var table = (Result.Table) cmd.execute("", ctx);
            boolean found = table.rows.stream()
                    .anyMatch(row -> row.get(0).equals("max_bytes"));
            assertTrue(found, "Should contain max_bytes row");
        }

        @Test void spec_name() {
            assertEquals("policy", cmd.spec().name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ModeCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ModeCommand")
    class Mode {

        private final ModeController modes = ModeController.defaultController();
        private final ModeCommand cmd = new ModeCommand(modes);

        @Test void show_current_mode() {
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("auto"), "Default mode is auto");
        }

        @Test void switch_to_rag() {
            Result r = cmd.execute("rag", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertEquals("rag", modes.getActiveName());
        }

        @Test void switch_to_dev() {
            cmd.execute("dev", ctx);
            assertEquals("dev", modes.getActiveName());
        }

        @Test void switch_to_chat() {
            cmd.execute("chat", ctx);
            assertEquals("chat", modes.getActiveName());
        }

        @Test void switch_to_ask() {
            cmd.execute("ask", ctx);
            assertEquals("ask", modes.getActiveName());
        }

        @Test void switch_to_auto() {
            modes.setActive("rag");
            cmd.execute("auto", ctx);
            assertEquals("auto", modes.getActiveName());
        }

        @Test void unknown_mode_returns_error() {
            Result r = cmd.execute("imaginary", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void null_args_shows_mode() {
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Info.class, r);
        }

        @Test void case_insensitive() {
            cmd.execute("RAG", ctx);
            assertEquals("rag", modes.getActiveName());
        }

        @Test void spec_name() {
            assertEquals("mode", cmd.spec().name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  HelpCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HelpCommand")
    class Help {

        private CommandRegistry registry() {
            var reg = new CommandRegistry();
            reg.register(new QuitCommand(new AtomicBoolean()));
            reg.register(new DebugCommand(new StubRuntime()));
            reg.register(new KCommand(new StubRuntime()));
            reg.register(new AuditToggleCommand());
            reg.register(new PolicyCommand());
            return reg;
        }

        private CommandRegistry fullRegistry() {
            var reg = registry();
            reg.register(new ModeCommand(ModeController.defaultController()));
            reg.register(new ModelsCommand());
            reg.register(new SetModelCommand());
            reg.register(new ExplainLastTurnCommand(Path.of("."), new dev.talos.runtime.NoOpSessionStore()));
            return reg;
        }

        @Test void help_no_args_lists_commands() {
            var cmd = new HelpCommand(registry());
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("Talos Help"), "Default help should be the short help page");
            assertTrue(r.toString().contains("/q"), "Should list quit");
            assertTrue(r.toString().contains("/debug"), "Should list debug");
            assertTrue(r.toString().contains("/help all"), "Should point to full command inventory");
        }

        @Test void help_all_lists_full_inventory() {
            var cmd = new HelpCommand(registry());
            Result r = cmd.execute("all", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("Session"), "Full help should include grouped inventory");
            assertTrue(r.toString().contains("Security"), "Full help should include security commands");
        }

        @Test void help_all_keeps_mode_and_last_summaries_readable() {
            var cmd = new HelpCommand(fullRegistry());
            Result r = cmd.execute("all", ctx);

            assertInstanceOf(Result.Ok.class, r);
            String text = r.toString();
            assertTrue(text.contains("Available: auto, rag, chat, dev, ask, web (reserved)"), text);
            assertFalse(text.contains("Available: auto, rag, c..."), text);
            assertTrue(text.contains("Inspect the latest turn from structured audit data"), text);
            assertFalse(text.contains("structured aud..."), text);
        }

        @Test void help_debug_topic() {
            var cmd = new HelpCommand(registry());
            Result r = cmd.execute("debug", ctx);
            assertInstanceOf(Result.Ok.class, r);
            String text = r.toString();
            assertTrue(text.contains("Debug Help"));
            assertTrue(text.contains("/debug"));
            assertTrue(text.contains("/debug prompt on"), text);
            assertTrue(text.contains("/debug prompt off"), text);
            assertTrue(text.contains("/last trace"), text);
        }

        @Test void help_models_topic_explains_model_switch_flow() {
            var cmd = new HelpCommand(fullRegistry());
            Result r = cmd.execute("models", ctx);
            assertInstanceOf(Result.Ok.class, r);
            String text = r.toString();
            assertTrue(text.contains("Model Help"), text);
            assertTrue(text.contains("/models"), text);
            assertTrue(text.contains("/model"), text);
            assertTrue(text.contains("/set model <backend/model>"), text);
        }

        @Test void help_security_topic() {
            var cmd = new HelpCommand(registry());
            Result r = cmd.execute("security", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("Security Help"));
            assertTrue(r.toString().contains("/policy"));
        }

        @Test void help_rag_topic() {
            var cmd = new HelpCommand(registry());
            Result r = cmd.execute("rag", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("RAG Help"));
            assertTrue(r.toString().contains("/k"));
        }

        @Test void help_specific_command() {
            var cmd = new HelpCommand(registry());
            Result r = cmd.execute("policy", ctx);
            assertInstanceOf(Result.Ok.class, r);
            assertTrue(r.toString().contains("policy"));
        }

        @Test void help_unknown_command_returns_error() {
            var cmd = new HelpCommand(registry());
            Result r = cmd.execute("nonexistent", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void hidden_command_is_executable_but_not_listed_or_documented() throws Exception {
            var reg = registry();
            reg.register(hiddenCommand("prompt-debug"));
            var cmd = new HelpCommand(reg);

            assertTrue(reg.has("prompt-debug"));
            assertInstanceOf(Result.Ok.class, reg.execute("prompt-debug", "", ctx));

            String defaultHelp = cmd.execute("", ctx).toString();
            String fullHelp = cmd.execute("all", ctx).toString();
            Result topic = cmd.execute("prompt-debug", ctx);

            assertFalse(defaultHelp.contains("prompt-debug"), defaultHelp);
            assertFalse(fullHelp.contains("prompt-debug"), fullHelp);
            assertInstanceOf(Result.Error.class, topic);
        }

        @Test void help_null_args_shows_all() {
            var cmd = new HelpCommand(registry());
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Ok.class, r);
        }

        @Test void spec_name_and_aliases() {
            var cmd = new HelpCommand(registry());
            assertEquals("help", cmd.spec().name());
            assertTrue(cmd.spec().aliases().contains("h"));
            assertTrue(cmd.spec().aliases().contains("?"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SetCommand
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SetCommand")
    class Set {

        private final SetCommand cmd = new SetCommand();

        @Test void set_model_updates_llm() throws Exception {
            Result r = cmd.execute("model qwen3:8b", ctx);
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("qwen3:8b"));
        }

        @Test void set_no_model_name_returns_error() throws Exception {
            Result r = cmd.execute("model", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void set_without_model_returns_usage() throws Exception {
            Result r = cmd.execute("", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void set_null_returns_usage() throws Exception {
            Result r = cmd.execute(null, ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void set_model_sanitizes_name() throws Exception {
            Result r = cmd.execute("model <my-model>", ctx);
            assertInstanceOf(Result.Info.class, r);
        }

        @Test void set_model_invalid_chars_rejected() throws Exception {
            Result r = cmd.execute("model ../../../../etc/passwd", ctx);
            // Path traversal should be rejected (contains ..)
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void spec_name() {
            assertEquals("set", cmd.spec().name());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  CommandRegistry
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CommandRegistry")
    class Registry {

        @Test void register_and_lookup() throws Exception {
            var reg = new CommandRegistry();
            reg.register(new QuitCommand(new AtomicBoolean()));
            assertTrue(reg.has("q"));
            assertTrue(reg.has("quit"));
            assertTrue(reg.has("exit"));
        }

        @Test void execute_unknown_returns_error() throws Exception {
            var reg = new CommandRegistry();
            Result r = reg.execute("mystery", "", ctx);
            assertInstanceOf(Result.Error.class, r);
        }

        @Test void allSpecs_deduplicates() {
            var reg = new CommandRegistry();
            reg.register(new QuitCommand(new AtomicBoolean()));
            reg.register(new DebugCommand(new StubRuntime()));
            var specs = reg.allSpecs();
            assertEquals(2, specs.size(), "Should have exactly 2 unique commands");
        }

        @Test void has_null_returns_false() {
            var reg = new CommandRegistry();
            assertFalse(reg.has(null));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Helper: stub CliRuntime
    // ═══════════════════════════════════════════════════════════════════════

    private static class StubRuntime implements CliRuntime {
        private int k = 6;
        private DebugLevel debugLevel = DebugLevel.OFF;

        @Override public int getK() { return k; }
        @Override public void setK(int k) { this.k = k; }
        @Override public boolean isDebug() { return debugLevel.enabled(); }
        @Override public void setDebug(boolean on) { this.debugLevel = on ? DebugLevel.BRIEF : DebugLevel.OFF; }
        @Override public DebugLevel getDebugLevel() { return debugLevel; }
        @Override public void setDebugLevel(DebugLevel level) { this.debugLevel = level == null ? DebugLevel.OFF : level; }
    }

    private static Command hiddenCommand(String name) {
        return new Command() {
            @Override
            public CommandSpec spec() {
                return new CommandSpec(
                        name,
                        java.util.List.of(),
                        "/" + name,
                        "Internal command",
                        CommandGroup.DEBUG,
                        true);
            }

            @Override
            public Result execute(String args, Context ctx) {
                return new Result.Ok("hidden");
            }
        };
    }
}

