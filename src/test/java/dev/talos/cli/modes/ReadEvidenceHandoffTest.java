package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReadEvidenceHandoffTest {

    @Test
    void handoffReadsNonProtectedEvidenceTargetThroughToolLoop(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "README evidence from disk.\n");
        Context ctx = context(workspace, "README summary after deterministic handoff.");
        List<ChatMessage> messages = messages("Read README.md and summarize it.");
        CurrentTurnPlan plan = plan(
                new TaskContract(
                        TaskType.READ_ONLY_QA,
                        false,
                        false,
                        false,
                        Set.of("README.md"),
                        Set.of(),
                        "Read README.md and summarize it."),
                EvidenceObligation.READ_TARGET_REQUIRED);

        ReadEvidenceHandoff.Result result = ReadEvidenceHandoff.readEvidenceHandoffIfNeeded(
                "unverified answer",
                messages,
                plan,
                workspace,
                ctx);

        assertNotNull(result.loopResult(), "handoff should run the read_file tool loop");
        assertEquals("README summary after deterministic handoff.", result.answer());
        assertEquals(List.of("README.md"), result.loopResult().readPaths());
        assertTrue(result.extraSummary().contains("talos.read_file"), result.extraSummary());
    }

    @Test
    void protectedMentionWithoutExplicitReadIntentDoesNotRunHandoff(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve(".env"), "SECRET=do-not-read\n");
        Context ctx = context(workspace, "should not be used");
        List<ChatMessage> messages = messages("Is .env considered a protected path?");
        CurrentTurnPlan plan = plan(
                new TaskContract(
                        TaskType.READ_ONLY_QA,
                        false,
                        false,
                        false,
                        Set.of(".env"),
                        Set.of(),
                        "Is .env considered a protected path?"),
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED);

        ReadEvidenceHandoff.Result result = ReadEvidenceHandoff.readEvidenceHandoffIfNeeded(
                "protected path explanation",
                messages,
                plan,
                workspace,
                ctx);

        assertNull(result.loopResult(), "mention-only protected targets must not trigger a read handoff");
        assertEquals("protected path explanation", result.answer());
        assertNull(result.extraSummary());
    }

    @Test
    void unsupportedCapabilityPreflightUsesSameDeterministicHandoff(@TempDir Path workspace) throws Exception {
        Files.write(workspace.resolve("slides.pptx"), new byte[] { 0x50, 0x4b, 0x03, 0x04 });
        Context ctx = context(workspace, "should not be used");
        List<ChatMessage> messages = messages("Summarize slides.pptx.");
        CurrentTurnPlan plan = plan(
                new TaskContract(
                        TaskType.READ_ONLY_QA,
                        false,
                        false,
                        false,
                        Set.of("slides.pptx"),
                        Set.of(),
                        "Summarize slides.pptx."),
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED);

        ReadEvidenceHandoff.Result result = ReadEvidenceHandoff.unsupportedCapabilityPreflightIfNeeded(
                messages,
                plan,
                workspace,
                ctx);

        assertNotNull(result.loopResult(), "unsupported-only targets should still execute read_file evidence");
        assertTrue(result.answer().contains("Document capability note"), result.answer());
        assertTrue(result.extraSummary().contains("talos.read_file"), result.extraSummary());
    }

    @Test
    void partialTargetRecoveryDoesNotRetryAfterDeniedEvidenceTarget(@TempDir Path workspace) {
        Context ctx = context(workspace, "should not be used");
        List<ChatMessage> messages = messages("Read README.md and summarize it.");
        CurrentTurnPlan plan = plan(
                new TaskContract(
                        TaskType.READ_ONLY_QA,
                        false,
                        false,
                        false,
                        Set.of("README.md"),
                        Set.of(),
                        "Read README.md and summarize it."),
                EvidenceObligation.READ_TARGET_REQUIRED);
        ToolCallLoop.LoopResult deniedTarget = new ToolCallLoop.LoopResult(
                "Read was denied.",
                1,
                1,
                List.of("talos.read_file"),
                messages,
                1,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file",
                        "README.md",
                        false,
                        false,
                        true,
                        "",
                        "denied")));

        ReadEvidenceHandoff.Result result = ReadEvidenceHandoff.readEvidenceRecoveryForPartialTargetsIfNeeded(
                "Read was denied.",
                messages,
                plan,
                deniedTarget,
                workspace,
                ctx);

        assertNull(result.loopResult(), "denied evidence target should block recovery handoff");
        assertEquals("Read was denied.", result.answer());
        assertNull(result.extraSummary());
    }

    @Test
    void pathExistenceRecoveryRunsAfterIrrelevantReadEvidence(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("scripts.js"), "console.log('present');\n");
        Files.writeString(workspace.resolve("styles.css"), "body { color: red; }\n");
        Context ctx = context(workspace, "Path existence answer after deterministic handoff.");
        List<ChatMessage> messages = messages(
                "Check whether scripts.js exists and whether script.js exists. Do not change anything.");
        CurrentTurnPlan plan = plan(
                new TaskContract(
                        TaskType.DIAGNOSE_ONLY,
                        false,
                        false,
                        false,
                        Set.of("scripts.js", "script.js"),
                        Set.of(),
                        "Check whether scripts.js exists and whether script.js exists. Do not change anything."),
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED);
        ToolCallLoop.LoopResult irrelevantRead = new ToolCallLoop.LoopResult(
                "scripts.js does not exist.",
                1,
                1,
                List.of("talos.read_file"),
                messages,
                1,
                0,
                false,
                0,
                List.of("styles.css"),
                0,
                0,
                0,
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file",
                        "styles.css",
                        true,
                        false,
                        false,
                        "body { color: red; }",
                        "")));

        ReadEvidenceHandoff.Result result = ReadEvidenceHandoff.readEvidenceRecoveryForPartialTargetsIfNeeded(
                "scripts.js does not exist.",
                messages,
                plan,
                irrelevantRead,
                workspace,
                ctx);

        assertNotNull(result.loopResult(), "path existence should recover from irrelevant read evidence");
        assertEquals("Path existence answer after deterministic handoff.", result.answer());
        assertTrue(result.extraSummary().contains("talos.read_file"), result.extraSummary());
    }

    private static CurrentTurnPlan plan(TaskContract contract, EvidenceObligation obligation) {
        return new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.INSPECT,
                ExecutionPhase.INSPECT,
                null,
                List.of(),
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of(),
                obligation.name(),
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);
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
