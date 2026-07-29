package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.outcome.MutationFailureAnswerRenderer;
import dev.talos.runtime.trace.ProviderAuthorityTestTrace;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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
    @BeforeEach
    void beginProviderAuthorityTrace(TestInfo testInfo) {
        ProviderAuthorityTestTrace.begin("edit-recovery", testInfo);
    }

    @AfterEach
    void clearProviderAuthorityTrace() {
        ProviderAuthorityTestTrace.clear();
    }

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
        assertTrue(
                out.text().contains(
                        MutationFailureAnswerRenderer.PARTIAL_MUTATION_ANNOTATION),
                "the bounded rewrite must not erase its earlier unverified failures: "
                        + out.text());
    }

    @Test
    void recoverySurvivesToolResultCompactionByInjectingTheReadback(@TempDir Path workspace)
            throws Exception {
        // Read in iteration 1, one empty edit each in iterations 2 and 3:
        // by the reprompt that plans the recovery, three tool results exist
        // and compaction (keep 2) summarizes the read out of provider
        // context. The temporary recovery overlay must therefore carry the
        // complete safe readback itself, and must not persist afterwards.
        Files.writeString(workspace.resolve("README.md"), README_BEFORE);
        var registry = registry();
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_read",
                                "talos.read_file",
                                Map.of("path", "README.md")))),
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_edit_1",
                                "talos.edit_file",
                                Map.of("path", "README.md",
                                        "old_string", "",
                                        "new_string", "## Contributing\n")))),
                        new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                                "call_edit_2",
                                "talos.edit_file",
                                Map.of("path", "README.md",
                                        "old_string", "",
                                        "new_string", "## Contributing (second attempt)\n")))),
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
                "recovery must still succeed after the read was compacted; README:\n" + readme
                        + "\nanswer:\n" + out.text()
                        + "\nrequests=" + recorded.requests().size());

        List<ChatRequest> requests = recorded.requests();
        assertEquals(4, requests.size(),
                "read, edit, edit, recovery write (the final answer is synthesized "
                        + "after the successful mutation without another generation)");
        ChatRequest recoveryRequest = requests.get(3);
        String systemText = roleText(recoveryRequest, "system");
        String toolText = roleText(recoveryRequest, "tool");
        assertTrue(systemText.contains("[Edit recovery]"),
                "the recovery instruction must be present:\n" + systemText);
        assertFalse(systemText.contains("Open site/index.html in a browser."),
                "workspace bytes must never occupy a system-role message:\n" + systemText);
        assertTrue(toolText.contains("Open site/index.html in a browser."),
                "the complete content must be carried by the read tool result, restored "
                        + "from compaction for this request (three tool results existed and "
                        + "only the two most recent survive compaction):\n" + toolText);
    }

    private static String roleText(ChatRequest request, String role) {
        if (request == null || request.messages == null) return "";
        StringBuilder out = new StringBuilder();
        for (ChatMessage message : request.messages) {
            if (message != null && role.equals(message.role()) && message.content() != null) {
                out.append(message.content()).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void aFailureAfterASuccessfulRecoveryDoesNotClaimNoFileWasChanged(@TempDir Path workspace)
            throws Exception {
        // After the approved recovery write, the empty-edit strike ledger
        // for that path must reset: a later failing call in the same turn
        // must not stop with the hardcoded (now false) claim that no
        // approval was requested and no file was changed.
        Files.writeString(workspace.resolve("README.md"), README_BEFORE);
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
                                Map.of("path", "README.md", "content", README_CORRECTED)))),
                        // The model wastes one more call with an empty edit
                        // after the successful recovery.
                        new LlmClient.StreamResult("", List.of(emptyEdit("call_edit_3"))),
                        new LlmClient.StreamResult(
                                "Added the Contributing section to README.md.", List.of())),
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
                "the approved recovery write must persist; README:\n" + readme);
        assertFalse(out.text().contains("no file was changed"),
                "a post-recovery failure must not deny the approved change: " + out.text());
        assertFalse(out.text().contains("No approval was requested"),
                "approval was requested and granted: " + out.text());
        assertTrue(
                out.text().contains(
                        MutationFailureAnswerRenderer.PARTIAL_MUTATION_ANNOTATION),
                "the successful write and later failed attempt must remain partial: "
                        + out.text());
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
