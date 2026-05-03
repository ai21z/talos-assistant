package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.PromptDebugSnapshot;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.types.ToolChoiceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDebugCommandTest {

    private final Context ctx = Context.builder(new Config()).build();

    @AfterEach
    void clearCapture() {
        PromptDebugCapture.clear();
    }

    @Test
    void commandIsHiddenAndHasInternalHelp() throws Exception {
        PromptDebugCommand command = new PromptDebugCommand();

        assertTrue(command.spec().hidden());

        Result result = command.execute("help", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("/prompt-debug last"), info.text);
        assertTrue(info.text.contains("internal"), info.text.toLowerCase());
    }

    @Test
    void lastReportsMissingCapture() throws Exception {
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("last", ctx);

        Result.Info info = assertInstanceOf(Result.Info.class, result);
        assertTrue(info.text.contains("No prompt debug capture"), info.text);
    }

    @Test
    void lastRendersPromptDiagnosticsAndExpectedTargetCoverage() throws Exception {
        PromptDebugCapture.record(PromptDebugSnapshot.fromProviderBody(
                new ChatRequest(
                        "ollama",
                        "qwen2.5-coder:14b",
                        "",
                        "",
                        List.of(),
                        Duration.ofSeconds(5),
                        List.of(
                                ChatMessage.system("main system"),
                                ChatMessage.system("[CurrentTurnCapability]\n[TaskContract]\ntype: FILE_CREATE"),
                                ChatMessage.user("Create index.html, styles.css, and scripts.js")),
                        List.of(new ToolSpec("talos.write_file", "Write", "{}")),
                        new ChatRequestControls(
                                ToolChoiceMode.REQUIRED,
                                "",
                                ResponseFormatMode.JSON_OBJECT,
                                "",
                                List.of("expected-target-repair"))),
                false,
                "{\"model\":\"qwen2.5-coder:14b\",\"system\":\"main system\\n\\n[CurrentTurnCapability]\",\"messages\":[{\"role\":\"user\",\"content\":\"Create index.html, styles.css, and scripts.js\"}]}"));
        PromptDebugCommand command = new PromptDebugCommand();

        Result result = command.execute("last", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("# Talos Prompt Debug"), info.text);
        assertTrue(info.text.contains("Stage: OLLAMA_HTTP_BODY"), info.text);
        assertTrue(info.text.contains("Ollama merges system messages"), info.text);
        assertTrue(info.text.contains("Tool choice: REQUIRED"), info.text);
        assertTrue(info.text.contains("Response format: JSON_OBJECT"), info.text);
        assertTrue(info.text.contains("Debug tags: expected-target-repair"), info.text);
        assertTrue(info.text.contains("Expected-target coverage: MISSING"), info.text);
        assertTrue(info.text.contains("Expected targets:"), info.text);
        assertTrue(info.text.contains("index.html"), info.text);
        assertTrue(info.text.contains("styles.css"), info.text);
        assertTrue(info.text.contains("scripts.js"), info.text);
        assertFalse(info.text.contains("SECRET_VALUE"), info.text);
    }
}
