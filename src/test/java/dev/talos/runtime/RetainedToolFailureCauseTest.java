package dev.talos.runtime;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Constructed runtime audit calls, without provider or tool execution. */
class RetainedToolFailureCauseTest {
    @AfterEach void clear() {
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        dev.talos.runtime.trace.LocalTurnTraceCapture.clear();
    }

    @Test void capturePreservesTypedCausesWithoutParsingDiagnosticProse() {
        TurnAuditCapture.begin();
        var call = new ToolCall("talos.edit_file", Map.of("path", "note.txt"));
        TurnAuditCapture.recordBlockedToolCall(call, "note.txt", "APPLY", "policy text", "policy text",
                ToolFailureReason.USER_APPROVAL_DENIED);
        TurnAuditCapture.recordBlockedToolCall(call, "note.txt", "APPLY", "user text", "user text",
                ToolFailureReason.PERMISSION_POLICY_DENIED);
        TurnAuditCapture.recordAuthorityRejectedToolCall(call, "approval denied by user");
        TurnAuditCapture.recordBlockedToolCall(call, "note.txt", "APPLY", "approval denied by user");
        var audit = TurnAuditCapture.end();
        assertEquals(List.of(ToolFailureReason.USER_APPROVAL_DENIED, ToolFailureReason.PERMISSION_POLICY_DENIED,
                        ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID, ToolFailureReason.NONE),
                audit.toolCalls().stream().map(TurnRecord.ToolCallSummary::failureReason).toList());
        assertTrue(audit.toolCalls().stream().allMatch(item -> item.executionDisposition() == TurnRecord.ToolCallDisposition.BLOCKED));
        assertTrue(audit.toolCalls().stream().noneMatch(item -> item.mutationEffects().hasEffects()));
    }

    @Test void legacyAndNullCausesDoNotInventApprovalEvidence() {
        var legacy = new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"),
                false, "approval denied by user", ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.BLOCKED);
        var absent = new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"),
                false, "permission denied", ToolMutationEffects.none(), TurnRecord.ToolCallDisposition.BLOCKED, null);
        assertEquals(ToolFailureReason.NONE, legacy.failureReason());
        assertEquals(ToolFailureReason.NONE, absent.failureReason());
    }

    @Test void preExecutionCausesRequireBlockedUnsuccessfulCallsWithoutEffects() {
        for (var cause : List.of(ToolFailureReason.USER_APPROVAL_DENIED,
                ToolFailureReason.PERMISSION_POLICY_DENIED, ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID)) {
            for (var disposition : TurnRecord.ToolCallDisposition.values()) {
                for (boolean success : List.of(false, true)) {
                    for (var effects : List.of(ToolMutationEffects.none(),
                            new ToolMutationEffects(List.of("note.txt"), List.of()),
                            new ToolMutationEffects(List.of(), List.of("note.txt")))) {
                        String context = cause + "/" + disposition + "/" + success + "/" + effects;
                        if (disposition == TurnRecord.ToolCallDisposition.BLOCKED && !success && !effects.hasEffects()) {
                            assertDoesNotThrow(() -> new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt",
                                    List.of("note.txt"), success, "", effects, disposition, cause), context);
                        } else {
                            assertThrows(IllegalArgumentException.class,
                                    () -> new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt",
                                            List.of("note.txt"), success, "", effects, disposition, cause), context);
                        }
                    }
                }
            }
            assertThrows(IllegalArgumentException.class,
                    () -> new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"),
                            false, "", ToolMutationEffects.none(), null, cause));
        }
    }

    @Test void absentAndOtherCausesKeepExistingUnknownDispositionCompatibility() {
        var effects = new ToolMutationEffects(List.of("note.txt"), List.of());
        var legacy = new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"),
                true, "", effects);
        assertEquals(TurnRecord.ToolCallDisposition.UNKNOWN, legacy.executionDisposition());
        assertEquals(ToolFailureReason.NONE, legacy.failureReason());
        assertEquals(effects, legacy.mutationEffects());
        var absent = new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt", List.of("note.txt"),
                true, "", effects, null, null);
        assertEquals(legacy, absent);
        for (var cause : ToolFailureReason.values()) {
            if (cause == ToolFailureReason.USER_APPROVAL_DENIED || cause == ToolFailureReason.PERMISSION_POLICY_DENIED
                    || cause == ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID) continue;
            assertDoesNotThrow(() -> new TurnRecord.ToolCallSummary("talos.edit_file", "note.txt",
                    List.of("note.txt"), false, "", ToolMutationEffects.none(),
                    TurnRecord.ToolCallDisposition.UNKNOWN, cause), cause.name());
        }
    }
}
