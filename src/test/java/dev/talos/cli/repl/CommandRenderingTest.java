package dev.talos.cli.repl;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.slash.*;
import dev.talos.cli.ui.*;
import dev.talos.core.Config;
import dev.talos.core.security.Redactor;
import dev.talos.runtime.*;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CommandRenderingTest {
    @TempDir Path workspace;

    private RenderEngine renderer(ByteArrayOutputStream sink, int width, boolean color) {
        return new RenderEngine(new Config(null), new Redactor(),
                new PrintStream(sink, true, StandardCharsets.UTF_8), color,
                CliTheme.forCapabilities(new TerminalCapabilities(
                        color ? ColorPolicy.ALWAYS : ColorPolicy.NEVER, color, color, true, false)), () -> width);
    }

    @ParameterizedTest
    @ValueSource(ints = {40, 60, 80, 120})
    void commandOutputIsBoundedWithoutAnswerTransformations(int width) {
        var sink = new ByteArrayOutputStream();
        renderer(sink, width, false).renderCommand("help", new Result.Ok(
                "Instructions\n    " + "long-name-".repeat(20) + "\n```java\nint x = 1;\n```\n[Sources]\nunchanged"));
        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("/help"));
        assertTrue(output.contains("```java\nint x = 1;\n```\n[Sources]\nunchanged"), output);
        assertFalse(output.contains("answer"));
        assertFalse(output.contains("\u001b"));
        output.lines().forEach(line -> assertTrue(new AttributedString(line).columnLength() <= width, line));
    }

    @Test void errorsKeepCodesAndRedactionBeforeWrapping() {
        var sink = new ByteArrayOutputStream();
        renderer(sink, 40, false).renderCommand("models", new Result.Error(
                "Failed \u001b[31mname\u001b[0m\u0007\n/path/private/file\n<think>hidden</think>", 400));
        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("/models [error 400]"), output);
        assertTrue(output.contains("[path]"));
        assertFalse(output.contains("hidden"));
        assertFalse(output.contains("private"));
        assertFalse(output.contains("\u001b"));
        assertFalse(output.contains("\u0007"));
    }

    @Test void displayedSourcePreservesSafeCodeLiteralsAndStillRejectsHiddenMarkup() {
        var sink = new ByteArrayOutputStream();
        renderer(sink, 80, false).renderCommand("show", new Result.Ok(
                "```html\n<button>Go</button>\n```\nInline `<script src=\"app.js\"></script>`."));
        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("<button>Go</button>"), output);
        assertTrue(output.contains("`<script src=\"app.js\"></script>`"), output);
        sink.reset();
        renderer(sink, 80, false).renderCommand("show", new Result.Ok("<think>hidden</think><script>unsafe</script>"));
        output = sink.toString(StandardCharsets.UTF_8);
        assertFalse(output.contains("hidden"));
        assertFalse(output.contains("unsafe"));
    }

    @Test void colorIsTrustedDecorationAndNotRequiredForStatus() {
        var sink = new ByteArrayOutputStream();
        renderer(sink, 80, true).renderCommand("models", new Result.Info("item [SELECTED]\u001b[31m"));
        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\u001b["));
        assertFalse(output.contains("\u001b[31m"));
        assertTrue(output.contains("[SELECTED]"));
    }

    @Test void tablesAreSanitizedBeforeLayout() {
        var sink = new ByteArrayOutputStream();
        renderer(sink, 40, false).renderCommand("files", new Result.Table("Files", List.of("Name", "State"),
                List.of(List.of("/path/private/file", "candidate\u0007"))));
        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[path]"));
        assertTrue(output.contains("candidate"));
        assertFalse(output.contains("private"));
        assertFalse(output.contains("\u0007"));
    }

    @Test void trustedInfoRetainsItsExistingPathDisclosureContract() {
        var sink = new ByteArrayOutputStream();
        renderer(sink, 80, false).renderCommand("status", new Result.TrustedInfo("Workspace /path/private/file"));
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("/path/private/file"));
    }

    @Test void builtinsUseCommandPresentationButWorkspaceTemplatesStillUseAnswers() throws Exception {
        var sink = new ByteArrayOutputStream();
        var registry = new CommandRegistry();
        registry.register(new HelpCommand(registry, null, () -> 40));
        Files.createDirectories(workspace.resolve(".talos/commands"));
        Files.writeString(workspace.resolve(".talos/commands/review.md"), "Explain this workspace.");
        var templates = WorkspaceCommandTemplates.load(workspace, registry.names());
        Config cfg = new Config(null);
        var router = new ReplRouter(ModeController.defaultController(), new TurnProcessor(
                (line, ws, ctx) -> Optional.of(new Result.Ok("Model response"))),
                new Session(workspace, cfg), Context.builder(cfg).build(), renderer(sink, 40, false),
                registry, workspace, new AtomicBoolean(false), "", templates);
        assertTrue(router.tryHandle("/help"));
        assertFalse(sink.toString(StandardCharsets.UTF_8).contains("answer"));
        sink.reset();
        assertTrue(router.tryHandle("/review"));
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("answer"));
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("Model response"));
        assertTrue(router.modelCompletionAliases().isEmpty());
    }
}
