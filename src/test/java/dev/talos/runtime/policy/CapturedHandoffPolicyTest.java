package dev.talos.runtime.policy;

import dev.talos.runtime.*;
import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.intent.RequestedResultInventory.*;
import dev.talos.runtime.observation.TurnRequestFrame;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.WorkspaceObservationBoundary.Fields;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CapturedHandoffPolicyTest {
    @TempDir Path workspace;
    private static final String REQUEST = "Give me a short handoff. What actually changed? What remains unverified?";

    @Test void retainedEffectCountsRespectTheCapturedFieldPolicy() {
        var memory = memory(1, "READBACK_ONLY", List.of("one.txt", "two.txt"));
        try (var frame = adopted(List.of(Kind.RETAINED_SAVES), new Fields(false, true, true, true))) {
            String answer = WorkStatusAnswerPolicy.renderCaptured(frame.meaning().inventory(), memory, workspace);
            assertTrue(answer.contains("one.txt") && answer.contains("two.txt"), answer);
            assertFalse(answer.contains("2 applied"), answer);
        }
    }

    @Test void laterFailedVerificationSurvivesBoundedSelectionAndOmissionIsExplicit() {
        var memory = new SessionMemory();
        for (int turn = 1; turn <= 13; turn++) record(memory, turn, turn == 13 ? "FAILED" : "PASSED", List.of());
        try (var frame = adopted(List.of(Kind.RETAINED_VERIFICATION), Fields.all())) {
            String answer = WorkStatusAnswerPolicy.renderCaptured(frame.meaning().inventory(), memory, workspace);
            assertTrue(answer.contains("FAILED"), answer);
            assertTrue(answer.contains("incomplete") || answer.contains("omitted"), answer);
            assertFalse(answer.contains("full suite passed"), answer);
        }
    }

    @Test void repeatedResultSlotsCannotExceedTheExistingPresentationBound() {
        var memory = memory(1, "READBACK_ONLY", List.of("a".repeat(900) + ".txt"));
        try (var frame = adopted(Collections.nCopies(16, Kind.RETAINED_SAVES), Fields.all())) {
            String answer = WorkStatusAnswerPolicy.renderCaptured(frame.meaning().inventory(), memory, workspace);
            assertTrue(answer.length() <= WorkStatusAnswerPolicy.MAX_OPERATION_DETAIL_CHARS, "Length " + answer.length());
            assertTrue(answer.contains("omitted") || answer.contains("bound") || answer.contains("incomplete"), answer);
        }
    }

    @Test void unsupportedRetainedResultCannotBeRecordedAsCovered() {
        try (var frame = adopted(List.of(Kind.RETAINED_SAVES), Fields.all())) {
            var result = frame.meaning().inventory().results().getFirst();
            var unsupported = new RequestedResult(result.id(), result.region(), result.kind(), result.subject(),
                    result.scope(), result.evidenceBasis(), Disposition.UNSUPPORTED, "");
            var inventory = new RequestedResultInventory(REQUEST, List.of(unsupported), List.of());
            var receipt = WorkStatusReceipt.captured(inventory, "A requested result remains unresolved.");
            assertEquals(dev.talos.runtime.intent.WorkStatusRequest.Coverage.PARTIAL, receipt.coverage());
            assertFalse(receipt.presentationComplete());
            assertEquals("UNSUPPORTED", receipt.resultDecisions().getFirst().disposition());
        }
    }

    @Test void freshInspectionCannotBeSwallowedByCapturedRetainedWork() {
        String request = "Give me a short handoff, then inspect this workspace.";
        try (var frame = TurnRequestFrame.open(request, workspace, 20, (owned, task) -> {
            var result = new RequestedResult("saves", new SourceRegion(0, request.length()), Kind.RETAINED_SAVES,
                    "active workspace", "retained conversation", EvidenceBasis.RETAINED_RUNTIME_RECORDS);
            return new TurnRequestFrame.Meaning(new RequestedResultInventory(request, List.of(result), List.of()),
                    Set.of(), TurnRequestFrame.Publication.ALL_NAMES, false, false);
        })) {
            frame.adopt(TaskContractResolver.resolveFromUserRequest(request));
            assertEquals(TurnRequestFrame.State.UNRESOLVED, frame.state());
            assertTrue(frame.observations().isEmpty());
        }
    }

    private TurnRequestFrame adopted(List<Kind> kinds, Fields fields) {
        var frame = TurnRequestFrame.open(REQUEST, workspace, 20, (owned, task) -> {
            var results = new ArrayList<RequestedResult>();
            for (var kind : kinds) results.add(new RequestedResult("opaque-" + results.size(),
                    new SourceRegion(0, REQUEST.length()), kind, "active workspace", "retained conversation",
                    EvidenceBasis.RETAINED_RUNTIME_RECORDS));
            return new TurnRequestFrame.Meaning(new RequestedResultInventory(REQUEST, results, List.of()), Set.of(),
                    kinds.contains(Kind.RETAINED_SAVES) ? TurnRequestFrame.Publication.ALL_NAMES : TurnRequestFrame.Publication.NO_NAMES,
                    false, false, fields);
        });
        frame.adopt(TaskContractResolver.resolveFromUserRequest(REQUEST));
        assertEquals(TurnRequestFrame.State.ADOPTED, frame.state());
        return frame;
    }

    private SessionMemory memory(int turn, String verification, List<String> paths) {
        var memory = new SessionMemory();
        record(memory, turn, verification, paths);
        return memory;
    }

    private void record(SessionMemory memory, int turn, String verification, List<String> paths) {
        var trace = LocalTurnTrace.builder("synthetic-" + turn, "fixture", turn, "2026-10-16T00:00:00Z")
                .workspaceHash(JsonSessionStore.sessionIdFor(workspace)).verification(verification, "", List.of()).build();
        var calls = paths.isEmpty() ? List.<TurnRecord.ToolCallSummary>of()
                : List.of(new TurnRecord.ToolCallSummary("talos.write_file", paths.getFirst(), List.of(), true, "",
                    new ToolMutationEffects(paths, List.of()), TurnRecord.ToolCallDisposition.EXECUTED, ToolFailureReason.NONE));
        var audit = new TurnAudit(calls, 0, 0, 0, TurnPolicyTrace.empty(), trace, ContentRestrictions.UNRESTRICTED,
                List.of(), WorkStatusReceipt.none());
        new MemoryUpdateListener(new dev.talos.core.context.ConversationManager(memory,
                new dev.talos.core.context.TokenBudget()), null, memory).onTurnComplete(
                new TurnResult(new Result.Ok("Synthetic status metadata."), null, turn, Duration.ZERO, audit),
                "Constructed historical operation");
    }
}
