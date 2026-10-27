package dev.talos.qualification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.outcome.MutationRecoveryAssessment;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.PromptAuditSnapshot;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationMutationRecoveryRuntimeTraceIntegrationTest {
    private static final String TARGET = "notes.md";
    private static final String FAILURE_CALL_ID = "call-1-1";
    private static final String REPAIR_CALL_ID = "call-1-2";
    private static final String LATER_CALL_ID = "call-1-3";

    @AfterEach
    void clearTraceCapture() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void productionFinalizationEmitsOneExactlyBoundMatchedRecovery(
            @TempDir Path workspace
    ) throws Exception {
        String before = "Status: draft\nKeep this final line unchanged.\n";
        String after = "Status: final\nKeep this final line unchanged.\n";
        Files.writeString(workspace.resolve(TARGET), after);

        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.user(
                        "In notes.md, change draft to final. If an exact edit fails, "
                                + "recover without deleting any other line."));
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        List<String> selectedTools =
                List.of("talos.edit_file", "talos.write_file");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                selectedTools,
                List.of(),
                List.of());

        LocalTurnTraceCapture.begin(
                "trace-t1154-runtime-integration",
                "session-t1154-runtime-integration",
                1,
                "2026-07-28T00:00:00Z",
                "workspace-t1154",
                "auto",
                "test",
                "test-model",
                messages.getLast().content());
        LocalTurnTraceCapture.recordPolicyTrace(
                TurnPolicyTrace.from(
                        contract,
                        ExecutionPhase.APPLY.name(),
                        selectedTools,
                        List.of()));
        LocalTurnTraceCapture.recordPromptAudit(
                PromptAuditSnapshot.fromPlan(plan, messages));
        LocalTurnTraceCapture.recordRequestObligation(
                plan.requestObligation());
        LocalTurnTraceCapture.enterExecutionStage(
                TurnExecutionStage.TOOL_EXECUTION);

        ToolCall failedCall = new ToolCall(
                "talos.edit_file",
                Map.of(
                        "path", TARGET,
                        "old_string", "Status: pending",
                        "new_string", "Status: final"));
        String failureCallId = recordSettledCall(
                failedCall,
                false,
                "old_string not found");
        assertEquals(FAILURE_CALL_ID, failureCallId);
        ToolCallLoop.ToolOutcome failedOutcome =
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file",
                        TARGET,
                        false,
                        true,
                        false,
                        "",
                        "old_string not found",
                        null,
                        ToolError.INVALID_PARAMS,
                        null,
                        ToolMutationEvidence.exactEdit(
                                "Status: pending",
                                "Status: final"),
                        ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                        List.of(),
                        failureCallId);
        LocalTurnTraceCapture.recordToolOutcomeClassified(
                failedOutcome,
                1);

        ToolCall repairCall = new ToolCall(
                "talos.write_file",
                Map.of(
                        "path", TARGET,
                        "content", after));
        String repairCallId = recordSettledCall(
                repairCall,
                true,
                "");
        assertEquals(REPAIR_CALL_ID, repairCallId);
        ToolCallLoop.ToolOutcome repairOutcome =
                new ToolCallLoop.ToolOutcome(
                        "talos.write_file",
                        TARGET,
                        true,
                        true,
                        false,
                        "wrote notes.md",
                        "",
                        dev.talos.tools.VerificationStatus.PASS,
                        "",
                        null,
                        ToolMutationEvidence.fullWriteReplacement(
                                before,
                                after),
                        ToolFailureReason.NONE,
                        List.of(),
                        repairCallId);
        LocalTurnTraceCapture.recordToolOutcomeClassified(
                repairOutcome,
                2);

        ToolCallLoop.LoopResult loopResult =
                new ToolCallLoop.LoopResult(
                        "Updated notes.md.",
                        2,
                        2,
                        selectedTools,
                        List.of(),
                        1,
                        0,
                        false,
                        1,
                        List.of(),
                        0,
                        0,
                        0,
                        0,
                        List.of(failedOutcome, repairOutcome));

        Object executionOutcome = invokeExecutionOutcome(
                plan,
                messages,
                loopResult,
                workspace);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(
                outcomeFinalAnswer(executionOutcome));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertNotNull(trace);

        assertEquals("PASSED", trace.verification().status());
        assertEquals("COMPLETE", trace.outcome().status());
        assertEquals("PASSED", trace.outcome().verificationStatus());
        assertEquals("SUCCEEDED", trace.outcome().mutationStatus());
        assertEquals(
                "COMPLETED_VERIFIED",
                trace.outcome().classification());

        int failedParsed =
                eventIndex(trace, "TOOL_CALL_PARSED", FAILURE_CALL_ID);
        int failedTerminal =
                eventIndex(trace, "TOOL_EXECUTED", FAILURE_CALL_ID);
        int failedClassified =
                eventIndex(trace, "TOOL_OUTCOME_CLASSIFIED", FAILURE_CALL_ID);
        int repairParsed =
                eventIndex(trace, "TOOL_CALL_PARSED", REPAIR_CALL_ID);
        int repairTerminal =
                eventIndex(trace, "TOOL_EXECUTED", REPAIR_CALL_ID);
        int repairClassified =
                eventIndex(trace, "TOOL_OUTCOME_CLASSIFIED", REPAIR_CALL_ID);
        int verification =
                eventIndex(trace, "VERIFICATION_COMPLETED", "");
        int recovery =
                eventIndex(
                        trace,
                        "MUTATION_FAILURE_RECOVERY_EVALUATED",
                        "");
        int outcome =
                eventIndex(trace, "OUTCOME_EVALUATED", "");
        int rendered =
                eventIndex(trace, "OUTCOME_RENDERED", "");
        int completed =
                eventIndex(trace, "TRACE_COMPLETED", "");

        assertTrue(
                failedParsed < failedTerminal
                        && failedTerminal < failedClassified
                        && failedClassified < repairParsed
                        && repairParsed < repairTerminal
                        && repairTerminal < repairClassified,
                "each call must settle before its typed classification");
        assertTrue(
                repairClassified < verification
                        && verification < recovery
                        && recovery < outcome
                        && outcome < rendered
                        && rendered < completed,
                "verification and recovery must precede the terminal outcome");

        assertEquals(
                List.of(FAILURE_CALL_ID, REPAIR_CALL_ID),
                trace.events().stream()
                        .filter(event ->
                                "TOOL_OUTCOME_CLASSIFIED".equals(
                                        event.type()))
                        .map(event ->
                                String.valueOf(
                                        event.data().get("toolCallId")))
                        .toList());
        assertEquals(
                List.of(1, 2),
                trace.events().stream()
                        .filter(event ->
                                "TOOL_OUTCOME_CLASSIFIED".equals(
                                        event.type()))
                        .map(event ->
                                (Integer) event.data().get(
                                        "outcomeOrdinal"))
                        .toList());
        TurnTraceEvent recoveryEvent = trace.events().get(recovery);
        assertEquals(
                FAILURE_CALL_ID,
                recoveryEvent.data().get("failureToolCallId"));
        assertEquals(
                REPAIR_CALL_ID,
                recoveryEvent.data().get("repairToolCallId"));
        assertEquals(
                List.of(REPAIR_CALL_ID),
                recoveryEvent.data().get(
                        "coveredSuccessfulMutationToolCallIds"));

        QualificationTraceEvidence.Facts facts =
                QualificationTraceEvidence.read(
                        QualificationJson.bytes(trace),
                        scenario());
        assertEquals(
                List.of(FAILURE_CALL_ID, REPAIR_CALL_ID),
                facts.calls().stream()
                        .map(QualificationTraceEvidence.Call::toolCallId)
                        .toList());
        assertEquals("PASSED", facts.verificationStatus());
        assertEquals("COMPLETE", facts.outcomeStatus());
        assertEquals("COMPLETED_VERIFIED", facts.terminalState());
        assertEquals(1, facts.mutationRecoveries().size());
        QualificationTraceEvidence.MutationRecoveryFact recoveryFact =
                facts.mutationRecoveries().getFirst();
        assertTrue(recoveryFact.proven());
        assertEquals(
                FAILURE_CALL_ID,
                recoveryFact.failureToolCallId());
        assertEquals(
                REPAIR_CALL_ID,
                recoveryFact.repairToolCallId());
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RECOVERED_MATCHED_OBLIGATION.name(),
                recoveryFact.decision());
    }

    @Test
    void productionFinalizationKeepsTypedRepairSettlementComparativelyUnproven(
            @TempDir Path workspace
    ) throws Exception {
        String before = "Status: draft\nKeep this final line unchanged.\n";
        String after = "Status: final\nKeep this final line unchanged.\n";
        Files.writeString(workspace.resolve(TARGET), after);

        String userRequest =
                "Apply the requested verified repair to notes.md.";
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.user(userRequest));
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of(TARGET),
                Set.of(),
                userRequest);
        List<String> selectedTools =
                List.of("talos.edit_file", "talos.read_file");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                selectedTools,
                List.of(),
                List.of());
        assertEquals(
                "NOT_MIGRATED",
                plan.requestObligation().derivationStatus().name());

        LocalTurnTraceCapture.begin(
                "trace-t1154-runtime-unproven-integration",
                "session-t1154-runtime-unproven-integration",
                1,
                "2026-07-28T00:00:00Z",
                "workspace-t1154",
                "auto",
                "test",
                "test-model",
                messages.getLast().content());
        LocalTurnTraceCapture.recordPolicyTrace(
                TurnPolicyTrace.from(
                        contract,
                        ExecutionPhase.APPLY.name(),
                        selectedTools,
                        List.of()));
        LocalTurnTraceCapture.recordPromptAudit(
                PromptAuditSnapshot.fromPlan(plan, messages));
        LocalTurnTraceCapture.recordRequestObligation(
                plan.requestObligation());
        LocalTurnTraceCapture.enterExecutionStage(
                TurnExecutionStage.TOOL_EXECUTION);

        ToolCall failedCall = new ToolCall(
                "talos.edit_file",
                Map.of(
                        "path", TARGET,
                        "old_string", "",
                        "new_string", ""));
        String failureCallId = recordSettledCall(
                failedCall,
                false,
                "old_string and new_string must be non-empty");
        assertEquals(FAILURE_CALL_ID, failureCallId);
        ToolCallLoop.ToolOutcome failedOutcome =
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file",
                        TARGET,
                        false,
                        true,
                        false,
                        "",
                        "old_string and new_string must be non-empty",
                        null,
                        ToolError.INVALID_PARAMS,
                        null,
                        ToolMutationEvidence.none(),
                        ToolFailureReason.EDIT_EMPTY_ARGUMENTS,
                        List.of(),
                        failureCallId);
        LocalTurnTraceCapture.recordToolOutcomeClassified(
                failedOutcome,
                1);

        ToolCall readCall = new ToolCall(
                "talos.read_file",
                Map.of("path", TARGET));
        String readCallId = recordSettledCall(
                readCall,
                true,
                "");
        assertEquals(REPAIR_CALL_ID, readCallId);
        ToolCallLoop.ToolOutcome readOutcome =
                new ToolCallLoop.ToolOutcome(
                        "talos.read_file",
                        TARGET,
                        true,
                        false,
                        false,
                        "read notes.md",
                        "",
                        dev.talos.tools.VerificationStatus.PASS,
                        "",
                        null,
                        ToolMutationEvidence.none(),
                        ToolFailureReason.NONE,
                        List.of(TARGET),
                        readCallId);
        LocalTurnTraceCapture.recordToolOutcomeClassified(
                readOutcome,
                2);

        ToolCall repairCall = new ToolCall(
                "talos.edit_file",
                Map.of(
                        "path", TARGET,
                        "old_string", "Status: draft",
                        "new_string", "Status: final"));
        String repairCallId = recordSettledCall(
                repairCall,
                true,
                "");
        assertEquals(LATER_CALL_ID, repairCallId);
        ToolCallLoop.ToolOutcome repairOutcome =
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file",
                        TARGET,
                        true,
                        true,
                        false,
                        "edited notes.md",
                        "",
                        dev.talos.tools.VerificationStatus.PASS,
                        "",
                        null,
                        ToolMutationEvidence.exactEdit(
                                "Status: draft",
                                "Status: final"),
                        ToolFailureReason.NONE,
                        List.of(),
                        repairCallId);
        LocalTurnTraceCapture.recordToolOutcomeClassified(
                repairOutcome,
                3);

        ToolCallLoop.LoopResult loopResult =
                new ToolCallLoop.LoopResult(
                        "Updated notes.md.",
                        3,
                        3,
                        selectedTools,
                        List.of(),
                        1,
                        1,
                        false,
                        1,
                        List.of(TARGET),
                        0,
                        0,
                        0,
                        0,
                        List.of(
                                failedOutcome,
                                readOutcome,
                                repairOutcome));

        Object executionOutcome = invokeExecutionOutcome(
                plan,
                messages,
                loopResult,
                workspace);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(
                outcomeFinalAnswer(executionOutcome));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertNotNull(trace);

        assertEquals("PASSED", trace.verification().status());
        assertEquals("COMPLETE", trace.outcome().status());
        assertEquals("PASSED", trace.outcome().verificationStatus());
        assertEquals("SUCCEEDED", trace.outcome().mutationStatus());
        assertEquals(
                "COMPLETED_VERIFIED",
                trace.outcome().classification());

        List<TurnTraceEvent> recoveries = trace.events().stream()
                .filter(event ->
                        "MUTATION_FAILURE_RECOVERY_EVALUATED".equals(
                                event.type()))
                .toList();
        assertEquals(1, recoveries.size());
        Map<String, Object> recovery = recoveries.getFirst().data();
        assertEquals(2, recovery.get("schemaVersion"));
        assertEquals(1, recovery.get("failureOrdinal"));
        assertEquals(3, recovery.get("repairOrdinal"));
        assertEquals(FAILURE_CALL_ID, recovery.get("failureToolCallId"));
        assertEquals(LATER_CALL_ID, recovery.get("repairToolCallId"));
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN.name(),
                recovery.get("decision"));
        assertEquals(true, recovery.get("resolved"));
        assertEquals(false, recovery.get("recovered"));
        assertEquals(
                "",
                recovery.get("verifiedRequestObligationSha256"));
        assertEquals(
                List.of(),
                recovery.get("coveredSuccessfulMutationToolCallIds"));

        QualificationTraceEvidence.Facts facts =
                QualificationTraceEvidence.read(
                        QualificationJson.bytes(trace),
                        scenario());
        assertEquals(1, facts.mutationRecoveries().size());
        QualificationTraceEvidence.MutationRecoveryFact recoveryFact =
                facts.mutationRecoveries().getFirst();
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN.name(),
                recoveryFact.decision());
        assertFalse(recoveryFact.proven());

        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        facts,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);
        assertTrue(decision.ambiguousToolOutcome());
        assertTrue(decision.provenMutationRecoveries().isEmpty());

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive(
                        "T1154-runtime-unproven",
                        "r1",
                        new QualificationCausalFinalizer.MechanicalFacts(
                                QualificationRecords.ResponseOwnerContract
                                        .DETERMINISTIC_RUNTIME_ALLOWED,
                                QualificationRecords.TrustGates.allPassed(),
                                true,
                                true,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                false,
                                decision));
        assertEquals(
                QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(
                QualificationRecords.CausalOwner.UNKNOWN,
                document.causalOwner());
        assertEquals(
                QualificationRecords.CausalReason
                        .TOOL_OUTCOME_CAUSE_NOT_MECHANICALLY_PROVEN,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
    }

    private static String recordSettledCall(
            ToolCall call,
            boolean success,
            String reason
    ) {
        LocalTurnTraceCapture.ToolCallAuthorityDecision authority =
                LocalTurnTraceCapture.beginRuntimeToolCallForExecution(
                        "tool_execution",
                        call,
                        LocalTurnTraceCapture.RuntimeToolAuthorityReason
                                .DIRECT_RUNTIME_API);
        assertTrue(authority.authorized());
        LocalTurnTraceCapture.ParsedToolCallPermit permit =
                authority.permit();
        assertTrue(
                LocalTurnTraceCapture.consumeToolCallParsedPermit(
                        permit,
                        call));
        LocalTurnTraceCapture.recordToolExecuted(
                "tool_execution",
                call,
                success,
                reason);
        return permit.toolCallId();
    }

    private static Object invokeExecutionOutcome(
            CurrentTurnPlan plan,
            List<ChatMessage> messages,
            ToolCallLoop.LoopResult loopResult,
            Path workspace
    ) throws Exception {
        Class<?> type =
                Class.forName("dev.talos.cli.modes.ExecutionOutcome");
        Method method = type.getDeclaredMethod(
                "fromToolLoop",
                String.class,
                CurrentTurnPlan.class,
                List.class,
                ToolCallLoop.LoopResult.class,
                Path.class,
                int.class);
        assertTrue(method.trySetAccessible());
        try {
            return method.invoke(
                    null,
                    loopResult.finalAnswer(),
                    plan,
                    messages,
                    loopResult,
                    workspace,
                    0);
        } catch (InvocationTargetException failed) {
            rethrow(failed.getCause());
            throw new AssertionError("unreachable");
        }
    }

    private static String outcomeFinalAnswer(Object outcome)
            throws Exception {
        Method accessor =
                outcome.getClass().getDeclaredMethod("finalAnswer");
        assertTrue(accessor.trySetAccessible());
        try {
            return (String) accessor.invoke(outcome);
        } catch (InvocationTargetException failed) {
            rethrow(failed.getCause());
            throw new AssertionError("unreachable");
        }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new RuntimeException(failure);
    }

    private static int eventIndex(
            LocalTurnTrace trace,
            String eventType,
            String toolCallId
    ) {
        for (int index = 0; index < trace.events().size(); index++) {
            TurnTraceEvent event = trace.events().get(index);
            if (eventType.equals(event.type())
                    && (toolCallId.isBlank()
                        || toolCallId.equals(
                                event.data().get("toolCallId")))) {
                return index;
            }
        }
        throw new AssertionError(
                "Missing trace event " + eventType
                        + (toolCallId.isBlank()
                            ? ""
                            : " for " + toolCallId));
    }

    private static QualificationProtocol.Scenario scenario() {
        return new QualificationProtocol.Scenario(
                "T1154-runtime-recovery",
                QualificationProtocol.BankKind.FULL,
                QualificationProtocol.Archetype.RECOVERY,
                QualificationProtocol.ScenarioCategory.REPAIR,
                "fixture.json",
                "b".repeat(64),
                "Change draft to final while preserving every other line.",
                QualificationProtocol.ApprovalBehavior.APPROVE_ONCE,
                QualificationRecords.ResponseOwnerContract
                        .DETERMINISTIC_RUNTIME_ALLOWED,
                List.of("COMPLETED_VERIFIED"),
                List.of(TARGET),
                List.of(),
                List.of(new QualificationProtocol.ExpectedDecision(
                        "mutation",
                        QualificationProtocol.DecisionKind.REQUIRED_TOOL,
                        List.of(
                                "talos.edit_file",
                                "talos.write_file"),
                        false)),
                List.of(new QualificationProtocol.AtomicRequirement(
                        "final-text",
                        "FILE_CONTAINS",
                        TARGET,
                        "final")));
    }
}
