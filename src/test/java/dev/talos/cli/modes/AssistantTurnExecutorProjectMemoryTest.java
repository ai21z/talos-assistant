package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.context.ProjectMemoryContext;
import dev.talos.runtime.context.ProjectMemoryDecision;
import dev.talos.runtime.context.ProjectMemorySource;
import dev.talos.runtime.context.ProjectMemoryStatus;
import dev.talos.runtime.context.ProjectMemoryTier;
import dev.talos.runtime.context.ProjectMemoryTrust;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AssistantTurnExecutorProjectMemoryTest {
    @TempDir Path workspace;

    @AfterEach
    void clearPromptDebug() {
        PromptDebugCapture.clear();
    }

    @Test
    void projectMemoryInstructionIsInsertedAfterBaseSystemBeforeHistoryAndCurrentTurnFrame() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("base system"),
                ChatMessage.user("earlier request"),
                ChatMessage.assistant("earlier answer"),
                ChatMessage.user("Explain this project.")));
        ProjectMemoryContext memory = memoryContext("Repo memory: Project Helios.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                new TaskContract(
                        TaskType.WORKSPACE_EXPLAIN,
                        false,
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        "Explain this project."),
                ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("talos.list_dir", "talos.read_file"),
                List.of());

        AssistantTurnExecutor.injectProjectMemoryInstruction(messages, memory);
        AssistantTurnExecutor.injectTaskContractInstruction(messages, plan);

        assertEquals("base system", messages.get(0).content());
        assertTrue(messages.get(1).content().contains("[ProjectMemory]"), messages.toString());
        assertTrue(messages.get(1).content().contains("untrusted local context"));
        assertTrue(messages.get(1).content().contains("Project Helios"));
        assertEquals("earlier request", messages.get(2).content());
        assertTrue(messages.get(messages.size() - 2).content().contains("[CurrentTurnCapability]"),
                messages.toString());
        assertEquals("Explain this project.", messages.get(messages.size() - 1).content());
    }

    @Test
    void executorLoadsWorkspaceProjectMemoryIntoProviderPromptForEligibleWorkspaceTurn() throws Exception {
        Files.writeString(workspace.resolve("TALOS.md"),
                "Repo memory: Project Helios uses Java 21.", StandardCharsets.UTF_8);
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("base system"),
                ChatMessage.user("Create README.md for this project.")));
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("I need to inspect the workspace."))
                .build();

        AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());

        String prompt = messages.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[ProjectMemory]"), prompt);
        assertTrue(prompt.contains("Project Helios uses Java 21"), prompt);
        assertTrue(prompt.contains("not proof that files were inspected"), prompt);
    }

    @Test
    void executorDoesNotLoadProjectMemoryForSmallTalk() throws Exception {
        Files.writeString(workspace.resolve("TALOS.md"),
                "Repo memory that small talk must not receive.", StandardCharsets.UTF_8);
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("base system"),
                ChatMessage.user("hello")));
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("Hi."))
                .build();

        AssistantTurnExecutor.execute(messages, workspace, ctx, new AssistantTurnExecutor.Options());

        assertTrue(PromptDebugCapture.latest().isEmpty(), "small talk direct answers should not call provider");
    }

    private static ProjectMemoryContext memoryContext(String content) {
        ProjectMemorySource source = new ProjectMemorySource(
                ProjectMemoryTier.REPO_ROOT,
                ProjectMemoryTrust.WORKSPACE_PROVIDED,
                "TALOS.md",
                content,
                "sha256:test",
                content.length(),
                content.getBytes(StandardCharsets.UTF_8).length,
                1,
                16,
                false);
        return new ProjectMemoryContext(
                ProjectMemoryStatus.LOADED,
                "WORKSPACE_EXPLAIN",
                List.of(source),
                List.of(new ProjectMemoryDecision(
                        source.tier(),
                        source.trust(),
                        source.pathHint(),
                        "INCLUDED_IN_MODEL_PROMPT",
                        "LOADED",
                        source.contentHash(),
                        source.chars(),
                        source.bytes(),
                        source.lines(),
                        source.estimatedTokens(),
                        source.truncated())));
    }
}
