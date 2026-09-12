package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.context.ChangeSummaryContext;
import dev.talos.runtime.outcome.MutationOutcomeStatus;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.*;
import dev.talos.tools.impl.MutationEffectTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FailedMutationEffectsIntegrationTest {
    @TempDir Path root;

    @AfterEach void clearCaptures() {
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void realPostPromotionFailureSurvivesRegistryLoopSettlementPersistenceAndFollowUp() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        Files.writeString(workspace.resolve("note.txt"), "before");
        String request = "Overwrite note.txt with exactly AFTER. Use talos.write_file.";
        String response = "{\"name\":\"talos.write_file\",\"parameters\":{\"path\":\"note.txt\",\"content\":\"AFTER\"}}";
        ToolRegistry registry = new ToolRegistry();
        registry.register(MutationEffectTestFixtures.postPromotionWriteFailure());
        TurnProcessor processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 2);
        Config config = new Config();
        Context context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .llm(ScriptedNativeLlmClient.textToolFallbackStrings(List.of("No files were changed."))).build();
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("test"), ChatMessage.user(request)));
        LocalTurnTraceCapture.begin("trc-mutation-effects", "effects", 1,
                "2026-09-05T00:00:00Z", "workspace-hash", "test", "llama_cpp", "fixture", request);
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(messages), messages);
        TurnAuditCapture.begin();
        ToolCallLoop.LoopResult result = loop.run(response, messages, workspace, context);
        assertEquals("AFTER", Files.readString(workspace.resolve("note.txt")), result.toString());
        assertEquals(0, result.mutatingToolSuccesses());
        assertEquals(1, result.toolOutcomes().size(), result.toString());
        assertEquals(1, result.toolsInvoked());
        assertEquals(0, result.retriedCalls());
        assertTrue(result.failureDecision().shouldStop());
        assertEquals(List.of("note.txt"), result.toolOutcomes().getFirst().mutationEffects().appliedPaths());
        assertFalse(result.toolOutcomes().getFirst().success());
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("No files were changed.", messages, result, workspace, 0);
        assertEquals(MutationOutcomeStatus.PARTIAL, outcome.taskOutcome().mutationOutcome().status());
        assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().contains("APPLIED_UNVERIFIED"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("No files were changed"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("No file on disk was actually modified"), outcome.finalAnswer());
        var trace = LocalTurnTraceCapture.complete();
        TurnAudit audit = TurnAuditCapture.end().withLocalTrace(trace);
        assertEquals(List.of("note.txt"), audit.toolCalls().getFirst().mutationEffects().appliedPaths());
        assertTrue(trace.events().stream().anyMatch(event -> "TOOL_EXECUTED".equals(event.type())
                && event.data().containsKey("mutationEffects")), trace.toString());
        TurnRecord record = new TurnRecord(1, Instant.parse("2026-09-05T00:00:00Z"), 1,
                request, outcome.finalAnswer(), audit.toolCalls(), audit.approvalsRequired(),
                audit.approvalsGranted(), audit.approvalsDenied(), "", "error");
        assertDurableChangedHistory(record, List.of("note.txt"));
    }

    @Test void realPartialBatchRecordsOnlyAttemptedEffectsAndKeepsThemAfterRestore() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("batch-workspace"));
        ToolResult produced = new BatchWorkspaceApplyTool().execute(new ToolCall("talos.apply_workspace_batch",
                Map.of("operations_json", """
                        [{"op":"mkdir","path":"docs"},
                         {"op":"move_path","from":"missing.txt","to":"docs/missing.txt"},
                         {"op":"mkdir","path":"never-attempted"}]
                        """)), new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config()));
        assertFalse(produced.success());
        assertTrue(Files.isDirectory(workspace.resolve("docs")));
        assertFalse(Files.exists(workspace.resolve("never-attempted")));
        ToolResult safe = ProtectedContentPolicy.sanitizeToolResult(produced);
        assertEquals(List.of("docs"), safe.mutationEffects().appliedPaths());
        ToolCallLoop.ToolOutcome toolOutcome = new ToolCallLoop.ToolOutcome("talos.apply_workspace_batch", "docs",
                false, true, false, "", safe.errorMessage(), safe.verification(), safe.error().code())
                .withMutationEffects(safe.mutationEffects());
        ToolCallLoop.LoopResult loopResult = loopResult(toolOutcome);
        List<ChatMessage> messages = List.of(ChatMessage.user("Create docs and move missing.txt into it."));
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("Nothing changed.", messages, loopResult, workspace, 0);
        assertEquals(MutationOutcomeStatus.PARTIAL, outcome.taskOutcome().mutationOutcome().status());
        assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
        assertTrue(outcome.finalAnswer().contains("docs: APPLIED_UNVERIFIED"), outcome.finalAnswer());
        TurnRecord.ToolCallSummary summary = new TurnRecord.ToolCallSummary(toolOutcome.toolName(), "docs",
                List.of("docs"), false, safe.errorMessage(), safe.mutationEffects());
        TurnRecord record = new TurnRecord(1, Instant.parse("2026-09-05T00:00:00Z"), 1, "batch",
                outcome.finalAnswer(), List.of(summary), 1, 1, 0, "", "error");
        assertDurableChangedHistory(record, List.of("docs"));
    }

    @Test void unknownEffectsStayUnknownAndLaterSuccessIsASeparateRecord() throws Exception {
        ToolMutationEffects effects = ToolMutationEffects.uncertain("note.txt");
        ToolCallLoop.ToolOutcome failure = new ToolCallLoop.ToolOutcome("talos.write_file", "note.txt",
                false, true, false, "", "commit unknown").withMutationEffects(effects);
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("I changed note.txt.",
                List.of(ChatMessage.user("Overwrite note.txt with exactly AFTER.")), loopResult(failure), root, 0);
        assertTrue(outcome.finalAnswer().contains("UNKNOWN"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("No file on disk was actually modified"));
        TurnRecord first = new TurnRecord(1, Instant.now(), 1, "write", "unknown",
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "note.txt", List.of("note.txt"),
                        false, "commit unknown", effects)), 1, 1, 0, "", "error");
        TurnRecord second = new TurnRecord(2, Instant.now(), 1, "repair", "written",
                List.of(new TurnRecord.ToolCallSummary("talos.write_file", "note.txt", true)), 1, 1, 0, "", "ok");
        ChangeSummaryContext earlier = ChangeSummaryContext.fromTurnRecords(List.of(first));
        assertEquals("UNKNOWN", earlier.changedFiles().getFirst().toolOutcome());
        assertTrue(earlier.renderForChangeSummaryQuestion().contains("possible effects"));
        ChangeSummaryContext later = ChangeSummaryContext.fromTurnRecords(List.of(first, second));
        assertEquals(2, later.changedFiles().getFirst().turnNumber());
        assertEquals("SUCCEEDED", later.changedFiles().getFirst().toolOutcome());
        assertEquals("UNKNOWN", earlier.changedFiles().getFirst().toolOutcome());
        assertDurableChangedHistory(first, List.of("note.txt"));
    }

    @Test void secondLoadOnlyBrowserBehaviorCannotBecomeVerifiedCompletion() throws Exception {
        Files.writeString(root.resolve("index.html"), """
                <!doctype html><html><head><title>Test</title><link rel="stylesheet" href="styles.css"></head>
                <body><button id="teaser-button">Go</button><p id="teaser-status">Waiting</p>
                <script src="scripts.js"></script></body></html>
                """);
        Files.writeString(root.resolve("styles.css"), "body { color: black; }");
        Files.writeString(root.resolve("scripts.js"), """
                window.loads = (window.loads || 0) + 1;
                if (window.loads > 1) {
                  document.getElementById('teaser-button').addEventListener('click', function() {
                    document.getElementById('teaser-status').textContent = 'Ready';
                  });
                }
                """);
        ToolCallLoop.ToolOutcome write = new ToolCallLoop.ToolOutcome("talos.write_file", "scripts.js",
                true, true, false, "Updated scripts.js", "", VerificationStatus.PASS);
        ToolCallLoop.LoopResult result = new ToolCallLoop.LoopResult("Done", 1, 1,
                List.of("talos.write_file"), List.of(), 0, 0, false, 1, List.of(),
                0, 0, 0, 0, List.of(write));
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("The button works.", List.of(ChatMessage.user(
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.")), result, root, 0);
        assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus(), outcome.toString());
        assertNotEquals(dev.talos.runtime.outcome.TaskCompletionStatus.COMPLETED_VERIFIED,
                outcome.taskOutcome().completionStatus());
        assertFalse(outcome.taskOutcome().verificationReport().requiredClaimsSatisfied());
        assertTrue(outcome.finalAnswer().contains("verification failed"), outcome.finalAnswer());
    }

    @Test void originalPageWithUnsupportedLexicalBindingCannotBecomeVerifiedCompletion() throws Exception {
        Files.writeString(root.resolve("index.html"), """
                <!doctype html><html><body>
                <button id="teaser-button">Go</button><p id="teaser-status">Waiting</p>
                <script src="scripts.js"></script></body></html>
                """);
        Files.writeString(root.resolve("scripts.js"), """
                const trigger = document.getElementById('teaser-button');
                const status = document.getElementById('teaser-status');
                trigger.addEventListener('click', function() { status.textContent = 'Ready'; });
                """);
        ToolCallLoop.ToolOutcome write = new ToolCallLoop.ToolOutcome("talos.write_file", "scripts.js",
                true, true, false, "Updated scripts.js", "", VerificationStatus.PASS);
        ToolCallLoop.LoopResult result = new ToolCallLoop.LoopResult("Done", 1, 1,
                List.of("talos.write_file"), List.of(), 0, 0, false, 1, List.of(),
                0, 0, 0, 0, List.of(write));
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("The button works.", List.of(ChatMessage.user(
                "Update scripts.js so #teaser-button updates #teaser-status when clicked.")), result, root, 0);
        assertEquals(ExecutionOutcome.VerificationStatus.UNAVAILABLE, outcome.verificationStatus(), outcome.toString());
        assertNotEquals(dev.talos.runtime.outcome.TaskCompletionStatus.COMPLETED_VERIFIED,
                outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().verificationReport().hasRequiredUnavailable());
        assertFalse(outcome.taskOutcome().verificationReport().hasRequiredFailure());
        assertTrue(outcome.finalAnswer().contains("unavailable"), outcome.finalAnswer());
    }

    private void assertDurableChangedHistory(TurnRecord record, List<String> expectedPaths) {
        JsonSessionStore store = new JsonSessionStore(root.resolve("sessions"));
        try (SessionWriterLease writer = store.acquireSessionWriter("effects")) {
            writer.appendTurn("effects", record);
        }
        List<TurnRecord> restored = store.loadTurns("effects");
        assertEquals(1, restored.size());
        assertEquals(record.toolCalls(), restored.getFirst().toolCalls());
        ChangeSummaryContext history = ChangeSummaryContext.fromTurnRecords(restored);
        assertEquals(expectedPaths, history.changedFiles().stream().map(ChangeSummaryContext.FileChange::path).toList());
        assertFalse(history.renderForChangeSummaryQuestion().startsWith("No files were changed"));
    }

    private static ToolCallLoop.LoopResult loopResult(ToolCallLoop.ToolOutcome outcome) {
        return new ToolCallLoop.LoopResult("", 1, 1, List.of(outcome.toolName()), List.of(),
                1, 0, false, 0, List.of(), 0, 0, 0, 0, List.of(outcome));
    }
}
