package dev.talos.cli.repl;

import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.cli.ui.CliTheme;
import dev.talos.cli.ui.ColorPolicy;
import dev.talos.cli.ui.TerminalCapabilities;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RenderEngineSanitizeTest {

    private static RenderEngine newRenderer(ByteArrayOutputStream sink) {
        return new RenderEngine(new Config(), new Redactor(), new PrintStream(sink));
    }

    private static RenderEngine plainAsciiRenderer(ByteArrayOutputStream sink, boolean interactive) {
        var caps = new TerminalCapabilities(ColorPolicy.NEVER, interactive, false, false, true);
        return new RenderEngine(
                new Config(),
                new Redactor(),
                new PrintStream(sink),
                interactive,
                CliTheme.forCapabilities(caps));
    }

    private static String out(ByteArrayOutputStream sink) {
        return sink.toString();
    }

    private static void assertNoAnsiOrThink(String s) {
        // ANSI ESC sequence and generic control chars
        assertFalse(s.contains("\u001B"), "ANSI escape codes should be stripped");
        assertFalse(s.matches(".*[\\x00-\\x08\\x0E-\\x1F\\x7F].*"), "Control characters should be stripped");
        // Think blocks
        assertFalse(s.contains("<think>"), "Think blocks should be removed");
        assertFalse(s.contains("</think>"), "Think blocks should be removed");
    }

    private static void assertAsciiOnly(String s) {
        assertTrue(s.codePoints().allMatch(cp -> cp == '\n' || cp == '\r' || cp == '\t'
                        || (cp >= 0x20 && cp <= 0x7E)),
                "Expected ASCII-only terminal output, got: " + s);
    }

    @Test
    void ok_isSanitizedAndPrinted() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RenderEngine re = newRenderer(sink);

        String payload = "Hello \u001B[31mWorld\u001B[0m <think>secret</think>";
        re.render(new Result.Ok(payload));

        String out = out(sink);
        assertTrue(out.contains("Hello"), "Expected text should remain");
        assertNoAnsiOrThink(out);
    }

    @Test
    void info_isSanitizedAndPrinted() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RenderEngine re = newRenderer(sink);

        re.render(new Result.Info("Notice \u0007<think>debug</think>"));
        String out = out(sink);

        assertTrue(out.toLowerCase().contains("notice"), "Expected text should remain");
        assertNoAnsiOrThink(out);
    }

    @Test
    void error_showsCodeAndSanitizedMessage() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RenderEngine re = newRenderer(sink);

        re.render(new Result.Error("Boom \u001B[33m<think>x</think>", 500));
        String out = out(sink);

        assertTrue(out.contains("[error]") || out.contains("[500]"), "Error code should be rendered");
        assertNoAnsiOrThink(out);
    }

    @Test
    void table_titleColumnsRows_areSanitized() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RenderEngine re = newRenderer(sink);

        Result.Table tbl = new Result.Table(
                "Title \u001B[0m<think>x</think>",
                List.of("Col<think>1</think>", "Col\u0007 2"),
                List.of(
                        List.of("A \u001B[31m", "B<think>b</think>"),
                        List.of("C\u0007", "D")
                )
        );
        re.render(tbl);

        String out = out(sink);
        assertTrue(out.contains("Title"), "Title should be printed");
        assertTrue(out.contains("Col"), "Columns should be printed");
        assertTrue(out.contains("A"), "Rows should be printed");
        assertTrue(out.contains("D"), "Rows should be printed");
        assertNoAnsiOrThink(out);
    }

    @Test
    void streaming_lifecycle_isSanitized() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RenderEngine re = newRenderer(sink);

        re.render(new Result.StreamStart("Preface \u001B[35m<think>tmp</think>"));
        re.render(new Result.StreamChunk("chunk-1 <think>xx</think>"));
        re.render(new Result.StreamChunk(" + chunk-2 \u0007"));
        re.render(new Result.StreamEnd());

        String out = out(sink);
        assertTrue(out.contains("Preface"), "Stream preface should be printed");
        assertTrue(out.contains("chunk-1"), "Stream chunks should be printed");
        assertTrue(out.contains("chunk-2"), "Stream chunks should be printed");
        assertNoAnsiOrThink(out);
        // By contract, a final newline is printed at StreamEnd
        assertTrue(out.endsWith(System.lineSeparator()), "StreamEnd should end with a newline");
    }

    @Test
    void trustedRendererStyleIsAppliedAfterModelTextSanitization() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        var caps = new TerminalCapabilities(ColorPolicy.ALWAYS, true, true, true, false);
        RenderEngine re = new RenderEngine(
                new Config(),
                new Redactor(),
                new PrintStream(sink),
                true,
                CliTheme.forCapabilities(caps));

        re.render(new Result.Error("Boom \u001B[31m<think>x</think>", 500));
        String out = out(sink);

        assertTrue(out.contains("\u001B["), "Trusted renderer may apply ANSI styling");
        assertFalse(out.contains("\u001B[31m"), "Model-controlled ANSI must be stripped first");
        assertFalse(out.contains("<think>"), "Think blocks must be removed before display");
        assertTrue(out.contains("Boom"), "Expected sanitized text should remain");
    }

    @Test
    void noColorThemeKeepsRendererOutputPlain() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        var caps = new TerminalCapabilities(ColorPolicy.NEVER, true, false, false, false);
        RenderEngine re = new RenderEngine(
                new Config(),
                new Redactor(),
                new PrintStream(sink),
                true,
                CliTheme.forCapabilities(caps));

        re.render(new Result.Error("Boom", 500));

        assertFalse(out(sink).contains("\u001B"), "No-color renderer path must not emit ANSI");
    }

    @Test
    void unsafeUnicodeTerminalDowngradesTrustedPromptOutput() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RenderEngine re = plainAsciiRenderer(sink, false);

        re.render(new Result.TrustedInfo("You CAN create files — use tools → verify… ✓ ❌ ⚠"));

        String output = out(sink);
        assertAsciiOnly(output);
        assertTrue(output.contains("You CAN create files - use tools -> verify..."));
        assertTrue(output.contains("[ok]"));
        assertTrue(output.contains("[error]"));
        assertTrue(output.contains("[warning]"));
    }

    @Test
    void unsafeUnicodeTerminalDowngradesNormalAndToolProgressOutput() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        RenderEngine re = plainAsciiRenderer(sink, true);

        re.render(new Result.Ok("Changed — verified…"));
        re.printToolProgress("talos.write_file", "warning", "HTML issues — unclosed tag…");

        String output = out(sink);
        assertAsciiOnly(output);
        assertTrue(output.contains("Changed - verified..."));
        assertTrue(output.contains("HTML issues - unclosed tag..."));
    }
}
