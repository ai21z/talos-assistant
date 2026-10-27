package dev.talos.runtime.trace;

import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionWriterLease;
import dev.talos.runtime.TurnEnvelopePersistenceReceipt;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationPlanEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public persistence-bound lifecycle identity checks for T1205. */
class T1205WorkspaceOperationLifecyclePersistenceIntegrityTest {

    private static final String REQUEST = "Copy a.md to b.md.";
    private static final String TIME = "2026-08-02T00:00:00Z";
    private static final String TOOL_CALL_ID = "call-workspace-copy-1";

    @Test
    void exactBoundConsumedLifecyclePersistsAndLoads(@TempDir Path directory) {
        Fixture fixture = fixture("valid-lifecycle");
        JsonSessionStore store = new JsonSessionStore(directory);

        TurnEnvelopePersistenceReceipt receipt;
        try (SessionWriterLease writer =
                store.acquireSessionWriter(fixture.sessionId())) {
            receipt = writer.persistTurnEnvelope(
                    fixture.sessionId(), fixture.record(), fixture.trace());
        }

        assertEquals(
                TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                receipt.state());
        TurnRecord loadedRecord = store.loadTurns(fixture.sessionId())
                .getFirst();
        LocalTurnTrace loadedTrace = store.loadTrace(
                        fixture.sessionId(), fixture.traceId())
                .orElseThrow();
        assertEquals(
                fixture.policy().workspaceOperationPlan(),
                loadedRecord.policyTrace().workspaceOperationPlan());
        assertEquals(
                expectedLifecycleOrder(),
                lifecycleTypes(loadedTrace));
        assertTrue(loadedTrace.events().stream().anyMatch(event ->
                "CHECKPOINT_CREATED".equals(event.type())
                        && fixture.planId().equals(
                                event.data().get("authorityPlanId"))
                        && fixture.operationId().equals(
                                event.data().get("operationId"))
                        && TOOL_CALL_ID.equals(
                                event.data().get("toolCallId"))));
    }

    @Test
    void nonInteractiveAllowLifecyclePersistsWithoutApprovalRequired(
            @TempDir Path directory
    ) {
        Fixture fixture = fixture("non-interactive-allow");
        LocalTurnTrace noPrompt = withEvents(
                fixture.trace(),
                fixture.trace().events().stream()
                        .filter(event -> !"WORKSPACE_OPERATION_APPROVAL_REQUIRED"
                                .equals(event.type()))
                        .toList());
        JsonSessionStore store = new JsonSessionStore(directory);

        TurnEnvelopePersistenceReceipt receipt;
        try (SessionWriterLease writer =
                store.acquireSessionWriter(fixture.sessionId())) {
            receipt = writer.persistTurnEnvelope(
                    fixture.sessionId(), fixture.record(), noPrompt);
        }

        assertEquals(
                TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED,
                receipt.state());
        assertFalse(lifecycleTypes(store.loadTrace(
                        fixture.sessionId(), fixture.traceId())
                .orElseThrow()).contains(
                        "WORKSPACE_OPERATION_APPROVAL_REQUIRED"));
    }

    @Test
    void missingCheckpointLifecycleIsRejectedBeforeEitherWrite(
            @TempDir Path directory
    ) {
        Fixture fixture = fixture("missing-checkpoint");
        assertRejected(
                directory,
                fixture,
                trace -> withEvents(
                        trace,
                        trace.events().stream()
                                .filter(event -> !"WORKSPACE_OPERATION_CHECKPOINT_CREATED"
                                        .equals(event.type()))
                                .toList()));
    }

    @Test
    void verificationBeforeConsumptionIsRejectedBeforeEitherWrite(
            @TempDir Path directory
    ) {
        Fixture fixture = fixture("reordered-verification");
        assertRejected(
                directory,
                fixture,
                trace -> withEvents(trace, swapLifecycleEvents(
                        trace.events(),
                        "WORKSPACE_OPERATION_OBLIGATION_CONSUMED",
                        "WORKSPACE_OPERATION_VERIFICATION_SUCCEEDED")));
    }

    @Test
    void duplicateApprovalRequiredAfterVerificationIsRejectedBeforeEitherWrite(
            @TempDir Path directory
    ) {
        Fixture fixture = fixture("late-duplicate-approval");
        assertRejected(
                directory,
                fixture,
                trace -> {
                    ArrayList<TurnTraceEvent> events =
                            new ArrayList<>(trace.events());
                    events.add(firstEvent(
                            trace,
                            "WORKSPACE_OPERATION_APPROVAL_REQUIRED"));
                    return withEvents(trace, events);
                });
    }

    @Test
    void mismatchedPlanIdentityIsRejectedBeforeEitherWrite(
            @TempDir Path directory
    ) {
        Fixture fixture = fixture("mismatched-plan");
        assertRejected(
                directory,
                fixture,
                trace -> replaceLifecycleEventData(
                        trace,
                        "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED",
                        "planId",
                        "sha256:" + "a".repeat(64)));
    }

    @Test
    void mismatchedOperationIdentityIsRejectedBeforeEitherWrite(
            @TempDir Path directory
    ) {
        Fixture fixture = fixture("mismatched-operation");
        assertRejected(
                directory,
                fixture,
                trace -> replaceLifecycleEventData(
                        trace,
                        "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED",
                        "operationId",
                        "sha256:" + "b".repeat(64)));
    }

    @Test
    void mismatchedParsedCallIdentityIsRejectedBeforeEitherWrite(
            @TempDir Path directory
    ) {
        Fixture fixture = fixture("mismatched-tool-call");
        assertRejected(
                directory,
                fixture,
                trace -> replaceLifecycleEventData(
                        trace,
                        "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED",
                        "toolCallId",
                        "call-workspace-copy-2"));
    }

    @Test
    void authorityDecisionForDifferentParsedCallIsRejectedBeforeEitherWrite(
            @TempDir Path directory
    ) {
        Fixture fixture = fixture("mismatched-authority-call");
        assertRejected(
                directory,
                fixture,
                trace -> replaceLifecycleEventData(
                        trace,
                        "WORKSPACE_OPERATION_AUTHORITY_DECIDED",
                        "toolCallId",
                        "call-workspace-copy-2"));
    }

    private static void assertRejected(
            Path directory,
            Fixture fixture,
            UnaryOperator<LocalTurnTrace> tamper
    ) {
        JsonSessionStore store = new JsonSessionStore(directory);
        LocalTurnTrace invalid = tamper.apply(fixture.trace());

        TurnEnvelopePersistenceReceipt receipt;
        try (SessionWriterLease writer =
                store.acquireSessionWriter(fixture.sessionId())) {
            receipt = writer.persistTurnEnvelope(
                    fixture.sessionId(), fixture.record(), invalid);
        }

        assertEquals(
                TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED,
                receipt.state());
        assertTrue(store.loadTurns(fixture.sessionId()).isEmpty());
        assertTrue(store.loadTrace(
                fixture.sessionId(), fixture.traceId()).isEmpty());
        assertFalse(Files.exists(directory.resolve(
                fixture.sessionId() + ".turns.jsonl")));
    }

    private static Fixture fixture(String identity) {
        var resolution = TaskContractResolver.resolveFromUserRequest(REQUEST);
        TurnPolicyTrace policy = TurnPolicyTrace.from(
                resolution,
                "APPLY",
                List.of("talos.copy_path"),
                List.of("tool_use:copy_path"));
        WorkspaceOperationPlanEvidence evidence =
                policy.workspaceOperationPlan();
        assertEquals("AUTHORIZED_SINGLE", evidence.state());
        assertEquals(1, evidence.operations().size());

        String sessionId = "t1205-" + identity;
        String traceId = "trace-" + identity;
        String operationId = evidence.operations().getFirst().operationId();
        LocalTurnTrace trace = validTrace(
                traceId,
                sessionId,
                policy,
                evidence.planId(),
                operationId);
        TurnRecord record = new TurnRecord(
                1,
                Instant.parse(TIME),
                1,
                REQUEST,
                "Copied a.md to b.md.",
                List.of(new TurnRecord.ToolCallSummary(
                        "talos.copy_path",
                        "b.md",
                        List.of("a.md", "b.md"),
                        true,
                        "")),
                1,
                1,
                0,
                "",
                "ok",
                policy,
                traceId);
        return new Fixture(
                sessionId,
                traceId,
                policy,
                evidence.planId(),
                operationId,
                record,
                trace);
    }

    private static LocalTurnTrace validTrace(
            String traceId,
            String sessionId,
            TurnPolicyTrace policy,
            String planId,
            String operationId
    ) {
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(
                traceId, sessionId, 1, TIME);
        PolicyTraceRecorder.record(builder, policy);

        builder.event(TurnTraceEvent.simple(
                "WORKSPACE_OPERATION_AUTHORITY_DECIDED",
                TIME,
                Map.of(
                        "planId", planId,
                        "planState", "AUTHORIZED_SINGLE",
                        "decision", "AUTHORIZED",
                        "operationId", operationId,
                        "toolCallId", TOOL_CALL_ID)));

        builder.event(lifecycle(
                "WORKSPACE_OPERATION_APPROVAL_REQUIRED",
                planId,
                operationId,
                TOOL_CALL_ID));
        builder.event(TurnTraceEvent.simple(
                "APPROVAL_GRANTED",
                TIME,
                Map.of("toolCallId", TOOL_CALL_ID)));
        builder.event(lifecycle(
                "WORKSPACE_OPERATION_APPROVAL_GRANTED",
                planId,
                operationId,
                TOOL_CALL_ID));
        builder.event(TurnTraceEvent.simple(
                "CHECKPOINT_CREATED",
                TIME,
                Map.of(
                        "authorityPlanId", planId,
                        "operationId", operationId,
                        "toolCallId", TOOL_CALL_ID)));
        builder.event(lifecycle(
                "WORKSPACE_OPERATION_CHECKPOINT_CREATED",
                planId,
                operationId,
                TOOL_CALL_ID));
        builder.event(TurnTraceEvent.simple(
                "TOOL_EXECUTED",
                TIME,
                Map.of(
                        "toolCallId", TOOL_CALL_ID,
                        "success", true)));
        builder.event(lifecycle(
                "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED",
                planId,
                operationId,
                TOOL_CALL_ID));
        builder.event(lifecycle(
                "WORKSPACE_OPERATION_OBLIGATION_CONSUMED",
                planId,
                operationId,
                TOOL_CALL_ID));
        builder.event(lifecycle(
                "WORKSPACE_OPERATION_VERIFICATION_SUCCEEDED",
                planId,
                operationId,
                TOOL_CALL_ID));
        return builder.build();
    }

    private static TurnTraceEvent lifecycle(
            String type,
            String planId,
            String operationId,
            String toolCallId
    ) {
        return TurnTraceEvent.simple(
                type,
                TIME,
                Map.of(
                        "planId", planId,
                        "operationId", operationId,
                        "toolCallId", toolCallId));
    }

    private static LocalTurnTrace replaceLifecycleEventData(
            LocalTurnTrace trace,
            String type,
            String key,
            String replacement
    ) {
        boolean[] replaced = {false};
        List<TurnTraceEvent> events = trace.events().stream()
                .map(event -> {
                    if (replaced[0] || !type.equals(event.type())) {
                        return event;
                    }
                    replaced[0] = true;
                    var data = new java.util.LinkedHashMap<>(event.data());
                    data.put(key, replacement);
                    return new TurnTraceEvent(
                            event.type(),
                            event.timestamp(),
                            event.phase(),
                            event.toolName(),
                            data);
                })
                .toList();
        assertTrue(replaced[0], "fixture lifecycle event must exist");
        return withEvents(trace, events);
    }

    private static List<TurnTraceEvent> swapLifecycleEvents(
            List<TurnTraceEvent> events,
            String firstType,
            String secondType
    ) {
        ArrayList<TurnTraceEvent> swapped = new ArrayList<>(events);
        int first = indexOf(swapped, firstType);
        int second = indexOf(swapped, secondType);
        assertTrue(first >= 0 && second >= 0, "fixture lifecycle events must exist");
        TurnTraceEvent value = swapped.get(first);
        swapped.set(first, swapped.get(second));
        swapped.set(second, value);
        return List.copyOf(swapped);
    }

    private static int indexOf(List<TurnTraceEvent> events, String type) {
        for (int index = 0; index < events.size(); index++) {
            if (type.equals(events.get(index).type())) return index;
        }
        return -1;
    }

    private static TurnTraceEvent firstEvent(
            LocalTurnTrace trace,
            String type
    ) {
        return trace.events().stream()
                .filter(event -> type.equals(event.type()))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> lifecycleTypes(LocalTurnTrace trace) {
        return trace.events().stream()
                .map(TurnTraceEvent::type)
                .filter(type -> type.startsWith("WORKSPACE_OPERATION_")
                        && !"WORKSPACE_OPERATION_PLAN_EVIDENCE_RECORDED"
                                .equals(type))
                .toList();
    }

    private static List<String> expectedLifecycleOrder() {
        return List.of(
                "WORKSPACE_OPERATION_AUTHORITY_DECIDED",
                "WORKSPACE_OPERATION_APPROVAL_REQUIRED",
                "WORKSPACE_OPERATION_APPROVAL_GRANTED",
                "WORKSPACE_OPERATION_CHECKPOINT_CREATED",
                "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED",
                "WORKSPACE_OPERATION_OBLIGATION_CONSUMED",
                "WORKSPACE_OPERATION_VERIFICATION_SUCCEEDED");
    }

    private static LocalTurnTrace withEvents(
            LocalTurnTrace source,
            List<TurnTraceEvent> events
    ) {
        return new LocalTurnTrace(
                source.schemaVersion(),
                source.traceId(),
                source.sessionId(),
                source.turnNumber(),
                source.timestamp(),
                source.workspaceHash(),
                source.mode(),
                source.model(),
                source.taskContract(),
                source.phaseTransitions(),
                source.toolSurface(),
                source.promptAudit(),
                events,
                source.verification(),
                source.repair(),
                source.checkpoint(),
                source.outcome(),
                source.warnings(),
                source.contextLedgerSummary(),
                source.redaction());
    }

    private record Fixture(
            String sessionId,
            String traceId,
            TurnPolicyTrace policy,
            String planId,
            String operationId,
            TurnRecord record,
            LocalTurnTrace trace
    ) {}
}
