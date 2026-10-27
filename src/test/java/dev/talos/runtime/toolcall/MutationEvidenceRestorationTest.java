package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Constructed executed reads exercise the real ledger, compactor and temporary request overlay. */
class MutationEvidenceRestorationTest {
    private static final String REQUEST = "Change README.md from old title to new title and NOTES.md "
            + "from old note to new note. Preserve everything else.";
    @TempDir Path workspace;

    @AfterEach void clear() { TurnTaskContractCapture.clear(); }

    private LoopState state() {
        var messages = new ArrayList<>(List.of(ChatMessage.system("fixture system"), ChatMessage.user(REQUEST)));
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        TurnTaskContractCapture.set(resolution, messages);
        assertEquals(ActionObligation.MUTATING_TOOL_REQUIRED,
                ActionObligationPolicy.derive(resolution, ExecutionPhase.APPLY));
        return new LoopState("", List.of(), messages, workspace,
                Context.builder(new Config()).build(), null, 10, 0);
    }

    private static void read(LoopState state, String path, String body) throws Exception {
        ReadOnlyEvidenceContextTest.read(state, path, body, Map.of("path", path));
    }

    private static void compact(LoopState state) {
        ToolCallSupport.compactOlderToolResultsInPlace(state.messages, state.compactedToolResultOriginals);
    }

    private static void trailingReads(LoopState state) throws Exception {
        read(state, "NOTES.md", "1 | old note\n");
        read(state, "guide.md", "1 | retained guide\n");
        compact(state);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactAndCompactedSourcesKeepOriginalRolesIdsArgumentsAndPositions(boolean compacted) throws Exception {
        var state = state();
        read(state, "README.md", "1 | old title\n");
        read(state, "NOTES.md", "1 | old note\n");
        read(state, "guide.md", "1 | retained guide\n");
        if (compacted) compact(state);
        var before = List.copyOf(state.messages);
        var resolution = TurnTaskContractCapture.getResolution();
        assertFalse(ReadOnlyEvidenceContext.delivery(state).available(), "Read-only eligibility stays unchanged");
        assertFalse(ReadOnlyEvidenceContext.select(state).available());
        var delivery = ReadOnlyEvidenceContext.mutationContinuationDelivery(state);
        assertTrue(delivery.available());
        assertEquals(3, delivery.carriers().size());
        if (compacted) assertTrue(before.get(3).content().startsWith("[compacted:"));
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertEquals(before.size(), state.messages.size());
            for (var carrier : delivery.carriers()) {
                assertEquals(carrier.message(), state.messages.get(carrier.index()));
                assertEquals(carrier.call(), state.messages.get(carrier.index() - 1).toolCalls().getFirst());
                assertEquals(carrier.call().id(), state.messages.get(carrier.index()).toolCallId());
                assertEquals("tool", state.messages.get(carrier.index()).role());
                assertFalse(state.messages.stream().filter(message -> !"tool".equals(message.role()))
                        .anyMatch(message -> message.content().contains(carrier.evidence().body())));
            }
            assertSame(resolution, TurnTaskContractCapture.getResolution());
            assertTrue(state.toolOutcomes.isEmpty());
            assertEquals(0, state.totalToolsInvoked);
        }
        assertEquals(before, state.messages, "Original compaction is restored on close");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-capture", "INSPECT", "VERIFY", "RESPOND", "not-requested", "not-allowed",
            "conditional", "unknown", "denied", "user-denied", "policy-denied", "authority-rejected",
            "terminal", "mutation-flag", "mutation-success", "mutation-outcome", "failed-effect", "withheld"})
    void currentActionAndPreMutationBoundaryFailClosed(String kind) throws Exception {
        var state = state();
        read(state, "README.md", "1 | preimage boundary\n");
        trailingReads(state);
        switch (kind) {
            case "no-capture" -> TurnTaskContractCapture.clear();
            case "INSPECT", "VERIFY", "RESPOND" -> state.ctx.executionPhaseState().moveTo(ExecutionPhase.valueOf(kind));
            case "not-requested", "not-allowed", "conditional", "unknown" -> TurnTaskContractCapture.set(
                    new TaskContract(kind.equals("unknown") ? TaskType.UNKNOWN : TaskType.FILE_EDIT,
                            !kind.equals("not-requested"), !kind.equals("not-allowed"), false,
                            Set.of("README.md"), Set.of(), REQUEST,
                            kind.equals("conditional") ? "explicit-review-and-fix-request" : ""));
            case "terminal" -> state.failureDecision = FailureDecision.stop(FailureAction.STOP_WITH_PARTIAL, "fixture");
            case "mutation-flag" -> state.mutationSinceStart = true;
            case "mutation-success" -> state.mutatingToolSuccesses = 1;
            case "withheld" -> state.contentWithheldFromModelContext = true;
            default -> {
                var outcome = new ToolCallLoop.ToolOutcome("talos.read_file", "README.md", false,
                        kind.equals("mutation-outcome"), kind.equals("denied"), "", "fixture");
                outcome = outcome.withFailureReason(switch (kind) {
                    case "user-denied" -> ToolFailureReason.USER_APPROVAL_DENIED;
                    case "policy-denied" -> ToolFailureReason.PERMISSION_POLICY_DENIED;
                    case "authority-rejected" -> ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID;
                    default -> ToolFailureReason.NONE;
                });
                if (kind.equals("failed-effect")) outcome = outcome.withMutationEffects(
                        new ToolMutationEffects(List.of(), List.of("README.md")));
                state.toolOutcomes.add(outcome);
            }
        }
        assertFalse(ReadOnlyEvidenceContext.mutationContinuationDelivery(state).available(), kind);
        var before = List.copyOf(state.messages);
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertEquals(before, state.messages, kind);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"altered-result", "altered-arguments", "altered-id", "duplicate-id", "unbound", "out-of-bounds",
            "textual", "original-restricted", "current-restricted", "protected", "redacted", "truncated",
            "ranged", "invalidated", "failed-refresh", "unbound-failed-refresh"})
    void unsafeAndUncorrelatedObservationsNeverRestoreOldPreimages(String kind) throws Exception {
        var state = state();
        String path = kind.equals("protected") ? ".env" : "README.md";
        String body = switch (kind) {
            case "redacted" -> "1 | " + ProtectedContentPolicy.REDACTED_VALUE + "\n";
            case "truncated" -> "1 | unavailable remainder\n... (output truncated)";
            default -> "1 | unsafe preimage fixture\n";
        };
        read(state, path, body);
        int index = state.messages.size() - 1;
        ChatMessage original = state.messages.get(index);
        var call = new ToolCall("talos.read_file", Map.of("path", path));
        switch (kind) {
            case "altered-result" -> state.messages.set(index, ChatMessage.toolResult(original.toolCallId(), "altered output"));
            case "altered-id" -> state.messages.set(index, ChatMessage.toolResult("other-id", original.content()));
            case "altered-arguments" -> state.messages.set(index - 1, ChatMessage.assistantWithToolCalls("", List.of(
                    new ChatMessage.NativeToolCall(original.toolCallId(), "talos.read_file", Map.of("path", "other.md")))));
            case "duplicate-id" -> state.messages.add(ChatMessage.assistantWithToolCalls("", state.messages.get(index - 1).toolCalls()));
            case "unbound" -> {
                ReadEvidenceStateAccounting.recordSuccessfulToolResult(state, call, path, ToolResult.ok(body));
            }
            case "out-of-bounds" -> state.readEvidence.bindToolResult(call, index + 100, original);
            case "textual" -> {
                state.messages.set(index - 1, ChatMessage.assistant("talos.read_file(path=" + path + ")"));
                state.messages.set(index, ChatMessage.user(original.content()));
            }
            case "original-restricted", "current-restricted" -> {
                var restricted = original.withContentRestrictions(new ContentRestrictions(false, true, true, true));
                if (kind.equals("original-restricted")) state.readEvidence.bindToolResult(call, index, restricted);
                else state.messages.set(index, restricted);
            }
            case "ranged" -> ReadOnlyEvidenceContextTest.read(state, path, "2 | newer partial preimage\n",
                    Map.of("path", path, "offset", "2"));
            case "failed-refresh", "unbound-failed-refresh" -> failedRead(state, path, !kind.startsWith("unbound"));
            default -> { }
        }
        trailingReads(state);
        if (kind.equals("invalidated")) ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
        var delivery = ReadOnlyEvidenceContext.mutationContinuationDelivery(state);
        assertTrue(delivery.available(), kind);
        assertTrue(delivery.excludedSourceCount() > 0, kind);
        assertTrue(delivery.carriers().stream().noneMatch(carrier -> carrier.target().equals(path)), kind);
        var before = List.copyOf(state.messages);
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertEquals(before.get(index), state.messages.get(index), kind);
            assertTrue(state.messages.stream().filter(message -> "system".equals(message.role()))
                    .noneMatch(message -> message.content().contains(body)), kind);
        }
        assertEquals(before, state.messages, kind);
    }

    @Test void latestSuccessfulRefreshSupersedesFailureButInvalidatedEvidenceStaysUnavailable() throws Exception {
        var state = state();
        read(state, "README.md", "1 | old source\n");
        failedRead(state, "README.md", true);
        read(state, "README.md", "1 | refreshed source\n");
        trailingReads(state);
        var delivery = ReadOnlyEvidenceContext.mutationContinuationDelivery(state);
        assertEquals(3, delivery.carriers().size());
        assertTrue(delivery.carriers().stream().anyMatch(carrier -> carrier.evidence().body().contains("refreshed source")));
        assertTrue(delivery.carriers().stream().noneMatch(carrier -> carrier.evidence().body().contains("old source")));
        ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
        assertTrue(ReadOnlyEvidenceContext.mutationContinuationDelivery(state).carriers().isEmpty());
    }

    @Test void stricterNonModelRestrictionsSurviveRestorationAndClose() throws Exception {
        var state = state();
        read(state, "README.md", "1 | private-destination fixture\n");
        trailingReads(state);
        int index = 3;
        var restrictions = new ContentRestrictions(true, false, false, false);
        state.messages.set(index, state.messages.get(index).withContentRestrictions(restrictions));
        var before = List.copyOf(state.messages);
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertTrue(state.messages.get(index).content().contains("private-destination fixture"));
            assertEquals(restrictions, state.messages.get(index).contentRestrictions());
        }
        assertEquals(before, state.messages);
    }

    @Test void targetAndCharacterBoundsRejectTheWholeDelivery() throws Exception {
        for (boolean characters : List.of(false, true)) {
            var state = state();
            if (characters) read(state, "README.md", "x".repeat(ReadOnlyEvidenceContext.MAX_EVIDENCE_CHARS));
            else for (int index = 0; index <= ReadOnlyEvidenceContext.MAX_TARGETS; index++)
                read(state, "source-" + index + ".md", "1 | source " + index + "\n");
            compact(state);
            var delivery = ReadOnlyEvidenceContext.mutationContinuationDelivery(state);
            assertTrue(delivery.retentionLimited());
            assertFalse(delivery.available());
            assertTrue(delivery.carriers().isEmpty());
            var before = List.copyOf(state.messages);
            try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
                assertEquals(before, state.messages);
            }
        }
    }

    private static void failedRead(LoopState state, String path, boolean bound) {
        String id = "failed-" + state.messages.size();
        state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall(id, "talos.read_file", Map.of("path", path)))));
        state.messages.add(ChatMessage.toolResult(id, "[tool_error: READ_FAILED] fixture failure"));
        var outcome = new ToolCallLoop.ToolOutcome("talos.read_file", path, false, false, false, "", "fixture failure");
        state.toolOutcomes.add(outcome);
        state.failedCalls++;
        if (bound) state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
    }
}
