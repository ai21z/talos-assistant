package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyInspectionRetryTest {

    @Test
    void retriesReadOnlyEvidenceRequestAndRunsToolLoop(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Workspace facts from README.\n");
        Context ctx = context(workspace, "Answer from retry evidence.");
        List<ChatMessage> messages = messages("Explain this workspace.");
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();

        ReadOnlyInspectionRetry.Result result = ReadOnlyInspectionRetry.retryIfNeeded(
                "I cannot inspect from here.",
                messages,
                plan(TaskContractResolver.fromUserRequest("Explain this workspace."), ExecutionPhase.INSPECT),
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return new LlmClient.StreamResult(
                            "{\"name\":\"talos.read_file\",\"arguments\":{\"path\":\"README.md\"}}",
                            List.of());
                });

        assertNotNull(result.loopResult(), "retry tool calls should re-enter the tool loop");
        assertEquals("Answer from retry evidence.", result.answer());
        assertEquals(List.of("README.md"), result.loopResult().readPaths());
        assertTrue(result.extraSummary().contains("talos.read_file"), result.extraSummary());
        assertEquals(4, retryMessages.get().size(), "retry appends assistant answer and corrective user prompt");
        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("Use read-only tools now."), prompt);
        assertTrue(prompt.contains("any obvious primary text files"), prompt);
        assertTrue(prompt.contains("Do not call write_file or edit_file."), prompt);
    }

    @Test
    void directoryListingRetryKeepsListOnlyPrompt(@TempDir Path workspace) throws Exception {
        Context ctx = context(workspace, "Directory entries:\n- README.md");
        List<ChatMessage> messages = messages("List the top-level files only.");
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();
        TaskContract contract = new TaskContract(
                TaskType.DIRECTORY_LISTING,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "List the top-level files only.",
                "explicit-directory-listing-request");

        ReadOnlyInspectionRetry.retryIfNeeded(
                "I cannot inspect from here.",
                messages,
                plan(contract, ExecutionPhase.INSPECT),
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return new LlmClient.StreamResult("No listing.", List.of());
                });

        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("Task type: DIRECTORY_LISTING"), prompt);
        assertTrue(prompt.contains("Use talos.list_dir"), prompt);
        assertTrue(prompt.contains("Answer with file and directory names only."), prompt);
        assertFalse(prompt.contains("Use read-only tools now."), prompt);
    }

    @Test
    void verifyOnlyCommandRetryKeepsRunCommandPrompt(@TempDir Path workspace) throws Exception {
        Context ctx = context(workspace, "No command was run.")
                .withNativeToolSpecs(List.of(new ToolSpec("talos.run_command", "Run approved command", "{}")));
        List<ChatMessage> messages = messages("Run the approved Gradle check command profile.");
        AtomicReference<List<ChatMessage>> retryMessages = new AtomicReference<>();
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Run the approved Gradle check command profile.");

        ReadOnlyInspectionRetry.retryIfNeeded(
                "I cannot verify that from here.",
                messages,
                plan(contract, ExecutionPhase.VERIFY),
                workspace,
                ctx,
                sentMessages -> {
                    retryMessages.set(List.copyOf(sentMessages));
                    return new LlmClient.StreamResult("No command was run.", List.of());
                });

        String prompt = retryMessages.get().get(3).content();
        assertTrue(prompt.contains("Task type: VERIFY_ONLY"), prompt);
        assertTrue(prompt.contains("talos.run_command"), prompt);
        assertFalse(prompt.contains("talos.list_dir"), prompt);
        assertFalse(prompt.contains("Use read-only tools"), prompt);
    }

    @Test
    void nonWorkspaceEvidenceTaskDoesNotRetry(@TempDir Path workspace) throws Exception {
        Context ctx = context(workspace, "should not be used");
        List<ChatMessage> messages = messages("hello");

        ReadOnlyInspectionRetry.Result result = ReadOnlyInspectionRetry.retryIfNeeded(
                "Hi, I am Talos.",
                messages,
                plan(TaskContractResolver.fromUserRequest("hello"), ExecutionPhase.RESPOND),
                workspace,
                ctx,
                ignored -> {
                    throw new AssertionError("chat should not be called");
                });

        assertEquals("Hi, I am Talos.", result.answer());
        assertNull(result.loopResult());
        assertNull(result.extraSummary());
        assertEquals(2, messages.size(), "non-retry path must not append messages");
    }

    private static CurrentTurnPlan plan(TaskContract contract, ExecutionPhase phase) {
        return CurrentTurnPlan.compatibility(
                contract,
                phase,
                List.of("talos.list_dir", "talos.read_file", "talos.run_command"),
                List.of("talos.list_dir", "talos.read_file", "talos.run_command"),
                List.of());
    }

    private static Context context(Path workspace, String finalAnswer) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        return Context.builder(new Config())
                .llm(LlmClient.scripted(List.of(finalAnswer)))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, 5))
                .build();
    }

    private static List<ChatMessage> messages(String request) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }
}
