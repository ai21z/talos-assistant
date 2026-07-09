package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1003: after repeated empty-old_string edit_file failures on a small text
 * file that was completely read in the same turn, Talos must enter a
 * deterministic full-rewrite recovery (one bounded talos.write_file with the
 * complete corrected content, normal approval and readback) instead of
 * stopping with only "copy the exact old_string". This reproduces the
 * installed-product QA failure shape from trace trc-1b879fd4: the model
 * emitted two empty edit calls and the read in a single response, so both
 * failure-policy strikes were spent before the read could help.
 */
@DisplayName("Invalid-edit full-rewrite recovery")
class EditRecoveryFullRewriteTest {

    private static final String README_BEFORE = """
            # Acme Widget Tracker

            A tiny internal tool.

            ## Setup
            Open site/index.html in a browser.
            """;

    private static final String README_CORRECTED = README_BEFORE
            + "\n## Contributing\nContributions welcome via pull request.\n";

    @Test
    void repeatedEmptyEditsOnAReadSmallFileRecoverViaBoundedFullRewrite(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"), README_BEFORE);
        var registry = registry();
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        // The QA failure shape: two empty edits plus the read in ONE response.
                        new LlmClient.StreamResult("", List.of(
                                emptyEdit("call_edit_1"),
                                emptyEdit("call_edit_2"),
                                new ChatMessage.NativeToolCall(
                                        "call_read",
                                        "talos.read_file",
                                        Map.of("path", "README.md")))),
                        // The recovery generation: one bounded full-content write.
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_write",
                                "talos.write_file",
                                Map.of("path", "README.md", "content", README_CORRECTED)))),
                        new LlmClient.StreamResult("Added the Contributing section to README.md.", List.of())),
                16_384);
        recorded.client().setToolSpecs(specs(registry));
        Context ctx = context(workspace, registry, recorded.client());

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Append a short Contributing section to README.md that says: "
                        + "Contributions welcome via pull request."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        String readme = Files.readString(workspace.resolve("README.md"));
        assertTrue(readme.contains("Contributions welcome via pull request"),
                "recovery must produce the approved full-rewrite; README:\n" + readme
                        + "\nanswer:\n" + out.text());
        assertFalse(out.text().contains("failure policy stopped"),
                "the safe stop must be replaced by the bounded recovery: " + out.text());
        assertFalse(out.text().contains("no file was changed"),
                "the turn must not end with the invalid-edit truth stop: " + out.text());
    }

    @Test
    void recoveryDoesNotFireForALargeTarget(@TempDir Path workspace) throws Exception {
        // Over the conservative recovery size cap: fail closed with the
        // existing truthful stop, and never rewrite the file.
        String big = "x".repeat(200) + "\n";
        Files.writeString(workspace.resolve("README.md"), big.repeat(400)); // ~80 KB
        String before = Files.readString(workspace.resolve("README.md"));
        var registry = registry();
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(
                                emptyEdit("call_edit_1"),
                                emptyEdit("call_edit_2"),
                                new ChatMessage.NativeToolCall(
                                        "call_read",
                                        "talos.read_file",
                                        Map.of("path", "README.md")))),
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_write",
                                "talos.write_file",
                                Map.of("path", "README.md", "content", "REWRITTEN")))),
                        new LlmClient.StreamResult("Done.", List.of())),
                16_384);
        recorded.client().setToolSpecs(specs(registry));
        Context ctx = context(workspace, registry, recorded.client());

        AssistantTurnExecutor.TurnOutput out = AssistantTurnExecutor.execute(
                messages("Append a short Contributing section to README.md that says: hi."),
                workspace,
                ctx,
                new AssistantTurnExecutor.Options());

        assertEquals(before, Files.readString(workspace.resolve("README.md")),
                "a target over the recovery size cap must not be rewritten");
        assertTrue(out.text().contains("failure policy stopped")
                        || out.text().contains("no file was changed"),
                "over-cap targets keep the existing truthful stop: " + out.text());
    }

    private static ChatMessage.NativeToolCall emptyEdit(String id) {
        return new ChatMessage.NativeToolCall(id, "talos.edit_file", Map.of(
                "path", "README.md",
                "old_string", "",
                "new_string", "\n## Contributing\nContributions welcome via pull request.\n"));
    }

    private static ToolRegistry registry() {
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileEditTool());
        registry.register(new FileWriteTool());
        return registry;
    }

    private static List<ToolSpec> specs(ToolRegistry registry) {
        return registry.descriptors().stream()
                .map(descriptor -> new ToolSpec(
                        descriptor.name(),
                        descriptor.description(),
                        descriptor.parametersSchema()))
                .toList();
    }

    private static Context context(Path workspace, ToolRegistry registry, LlmClient llm) {
        var processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        return Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 4))
                .nativeToolSpecs(specs(registry))
                .build();
    }

    private static List<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }
}
