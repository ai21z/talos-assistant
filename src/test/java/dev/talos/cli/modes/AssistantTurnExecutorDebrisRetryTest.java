package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T743: malformed tool-protocol debris on a mutation-obligation turn gets one
 * bounded MissingMutationRetry pass; a valid corrected call on the retry
 * rescues the turn instead of returning the no-action notice.
 */
class AssistantTurnExecutorDebrisRetryTest {

    @Test
    void malformedDebrisOnMutationTurnIsRescuedByBoundedRetry(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("scripts.js"), """
                document.querySelector("#wrongButton").addEventListener("click", () => {
                  console.log("wrong");
                });
                """);

        var registry = new dev.talos.tools.ToolRegistry();
        var undoStack = new dev.talos.tools.FileUndoStack();
        registry.register(new dev.talos.tools.impl.FileEditTool(undoStack));
        var processor = new dev.talos.runtime.TurnProcessor(
                null, new dev.talos.runtime.NoOpApprovalGate(), registry);
        var loop = new dev.talos.runtime.ToolCallLoop(processor, 3);
        var ctx = Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(
                        // turn 1: malformed protocol debris (single-quoted JSON)
                        """
                        {
                          "name": "talos.edit_file",
                          "arguments": {
                            "path": "scripts.js",
                            "old_string": 'document.querySelector("#wrongButton").addEventListener("click", () => {',
                            "new_string": 'document.querySelector("button").addEventListener("click", () => {'
                          }
                        }
                        """,
                        // bounded retry: corrected, valid tool-call JSON
                        """
                        {
                          "name": "talos.edit_file",
                          "arguments": {
                            "path": "scripts.js",
                            "old_string": "document.querySelector(\\"#wrongButton\\").addEventListener(\\"click\\", () => {",
                            "new_string": "document.querySelector(\\"button\\").addEventListener(\\"click\\", () => {"
                          }
                        }
                        """)))
                .sandbox(new dev.talos.core.security.Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .build();
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "My BMI page is almost there, but when I press the button nothing happens. "
                        + "Please keep the look the same and just make the button work."));

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages, workspace, ctx, new AssistantTurnExecutor.Options());

        String scripts = Files.readString(workspace.resolve("scripts.js"));
        assertTrue(scripts.contains("document.querySelector(\"button\")"),
                "bounded debris retry should have applied the corrected edit: " + scripts);
        assertNotEquals(AssistantTurnExecutor.MALFORMED_TOOL_PROTOCOL_REPLACEMENT, out.text(),
                "rescued turn must not end with the no-action notice: " + out.text());
    }
}
