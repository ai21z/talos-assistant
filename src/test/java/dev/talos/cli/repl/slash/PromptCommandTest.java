package dev.talos.cli.repl.slash;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.prompt.PromptInspector;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptCommandTest {

    @Test
    void promptCommandRendersNextPromptWithoutModelCall() throws Exception {
        PromptCommand command = new PromptCommand(ModeController.defaultController(), Path.of("."));

        Result result = command.execute("Check the workspace.", context());

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("# Talos Prompt Render"));
        assertTrue(info.text.contains("Check the workspace."));
        assertTrue(info.text.contains("talos.read_file"));
    }

    @Test
    void promptCommandAppliesTaskContractForInputPreview() throws Exception {
        PromptCommand command = new PromptCommand(ModeController.defaultController(), Path.of("."));

        Result result = command.execute("hello", context());

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("Task contract: SMALL_TALK"));
        assertTrue(info.text.contains("Tools exposed: (none)"));
        assertTrue(info.text.contains("Do not call tools"));
    }

    @Test
    void promptLastReportsMissingCapture() throws Exception {
        LastPromptCapture.clear();
        PromptCommand command = new PromptCommand(ModeController.defaultController(), Path.of("."));

        Result result = command.execute("last", context());

        Result.Info info = assertInstanceOf(Result.Info.class, result);
        assertTrue(info.text.contains("No prompt has been captured"));
    }

    @Test
    void promptLastReturnsCapturedPrompt() throws Exception {
        Context ctx = context();
        LastPromptCapture.record(PromptInspector.renderNext("auto", "hello", Path.of("."), ctx));
        PromptCommand command = new PromptCommand(ModeController.defaultController(), Path.of("."));

        Result result = command.execute("last", ctx);

        Result.TrustedInfo info = assertInstanceOf(Result.TrustedInfo.class, result);
        assertTrue(info.text.contains("hello"));
    }

    private static Context context() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        return Context.builder(new Config())
                .toolRegistry(registry)
                .build();
    }
}
