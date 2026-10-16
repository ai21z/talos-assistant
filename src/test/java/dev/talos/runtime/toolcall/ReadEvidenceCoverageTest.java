package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ToolCallLoop.ToolOutcome;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.policy.EvidenceObligationVerifier.Status;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolContentMetadata.ReadCoverage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic execution metadata isolates scope and correlation, no provider or tool is executed. */
class ReadEvidenceCoverageTest {
    @TempDir Path workspace;
    private final TaskContract contract = new TaskContract(TaskType.READ_ONLY_QA, false, false, false,
            Set.of("a.txt", "b.txt"), Set.of(), "Read a.txt and b.txt. Do not edit.");

    @BeforeEach void begin() {
        TurnTaskContractCapture.set(contract);
        LocalTurnTraceCapture.begin("coverage", "tests", 1, "2026-09-28T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", contract.originalUserRequest());
    }

    @AfterEach void clear() {
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    private LoopState state() {
        return new LoopState("", List.of(), new ArrayList<>(), workspace,
                Context.builder(new Config()).build(), null, 10, 0);
    }

    private ToolOutcome read(LoopState state, String path, boolean success, boolean withheld,
                             ReadCoverage coverage, boolean nativeCall) {
        return read(state, path, success, withheld, ToolContentMetadata.normal().withReadCoverage(coverage),
                nativeCall, success ? "1 | observed\n" : "read failed", "");
    }

    private ToolOutcome read(LoopState state, String path, boolean success, boolean withheld,
                             ToolContentMetadata metadata, boolean nativeCall, String content, String notice) {
        String id = "read-" + state.messages.size();
        var call = new ToolCall("talos.read_file", Map.of("path", path));
        var outcome = new ToolOutcome("talos.read_file", path, success, false, false, "", success ? "" : "failed");
        if (nativeCall) state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall(id, call.toolName(), Map.of("path", path)))));
        state.messages.add(nativeCall ? ChatMessage.toolResult(id, content) : ChatMessage.user(content));
        state.toolOutcomes.add(outcome);
        state.toolOutcomeResults.bind(outcome, state.messages.size() - 1, state.messages.getLast());
        if (success) {
            var evidence = state.readEvidence.record(call, path, content);
            state.toolOutcomeResults.bindRead(outcome, evidence, withheld, metadata, notice);
        }
        return outcome;
    }

    private ToolOutcome full(LoopState state, String path) {
        return read(state, path, true, false, ReadCoverage.COMPLETE, true);
    }

    private Status status(ReadEvidenceCoverage coverage, List<ToolOutcome> outcomes, String... targets) {
        Set<String> required = Set.of(targets);
        return coverage.assess(required, required, outcomes, workspace, contract).status();
    }

    @Test void laterFailureCannotBorrowEarlierSuccess() {
        var state = state();
        full(state, "a.txt");
        read(state, "a.txt", false, false, ReadCoverage.UNSPECIFIED, true);
        assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
    }

    @Test void fullRefreshResolvesButDoesNotEraseHistoricalFailure() {
        var state = state();
        read(state, "a.txt", false, false, ReadCoverage.UNSPECIFIED, true);
        var accepted = full(state, "a.txt");
        var coverage = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.SATISFIED, status(coverage, state.toolOutcomes, "a.txt"));
        assertEquals(List.of(accepted), coverage.effectiveOutcomes(state.toolOutcomes));
        assertEquals(2, state.toolOutcomes.size());
        assertFalse(state.toolOutcomes.getFirst().success());
    }

    @Test void withheldRequiredReadIsNotSourceEvidence() {
        var state = state();
        read(state, "a.txt", true, true, ReadCoverage.COMPLETE, true);
        assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
    }

    @Test void optionalWithholdingDoesNotPoisonRequiredSource() {
        var state = state();
        full(state, "a.txt");
        read(state, "b.txt", true, true, ReadCoverage.COMPLETE, true);
        state.contentWithheldFromModelContext = true;
        assertEquals(Status.SATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
    }

    @Test void textCompatibilityCanSatisfyAnOrdinaryReadButNotResolveFailedFreshness() {
        var state = state();
        read(state, "a.txt", true, false, ReadCoverage.COMPLETE, false);
        assertEquals(Status.SATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
        read(state, "a.txt", false, false, ReadCoverage.UNSPECIFIED, false);
        read(state, "a.txt", true, false, ReadCoverage.COMPLETE, false);
        assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
    }

    @Test void malformedNativeBindingCannotBecomeTextCompatibility() {
        var state = state();
        full(state, "a.txt");
        state.messages.set(0, ChatMessage.assistant("proposal removed"));
        assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
    }

    @Test void changedCarrierCannotBeResolvedByALaterFullRead() {
        var state = state();
        full(state, "a.txt");
        state.messages.set(1, ChatMessage.toolResult("read-0", "changed"));
        full(state, "a.txt");
        assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
    }

    @Test void duplicateCarrierIdRemainsAmbiguous() {
        var state = state();
        full(state, "a.txt");
        state.messages.add(state.messages.getLast());
        assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
    }

    @Test void sameIdWithDifferentExecutedCallCannotAuthenticateARefresh() {
        for (boolean differentName : List.of(true, false)) {
            var state = state();
            read(state, "a.txt", false, false, ReadCoverage.UNSPECIFIED, true);
            full(state, "a.txt");
            state.messages.set(2, ChatMessage.assistantWithToolCalls("", List.of(
                    new ChatMessage.NativeToolCall("read-2", differentName ? "talos.grep" : "talos.read_file",
                            Map.of("path", differentName ? "a.txt" : "b.txt")))));
            assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
        }
    }

    @Test void duplicateOrEqualReplacementOutcomesCannotBorrowCoverage() {
        var state = state();
        var original = full(state, "a.txt");
        var coverage = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, status(coverage, List.of(original, original), "a.txt"));
        var equal = new ToolOutcome(original.toolName(), original.pathHint(), true, false, false, "", "");
        assertEquals(original, equal);
        assertEquals(Status.UNSATISFIED, status(coverage, List.of(equal), "a.txt"));
    }

    @Test void scopeAndContractChangesCannotBorrowCoverage() {
        var state = state();
        full(state, "a.txt");
        var coverage = ReadEvidenceCoverage.capture(state);
        assertEquals(Status.UNSATISFIED, coverage.assess(Set.of("a.txt"), Set.of("a.txt"), state.toolOutcomes,
                workspace.resolve("other"), contract).status());
        assertEquals(Status.UNSATISFIED, coverage.assess(Set.of("a.txt"), Set.of("a.txt"), state.toolOutcomes,
                workspace, TaskContract.unknown("different request")).status());
        LocalTurnTraceCapture.clear();
        assertEquals(Status.UNSATISFIED, status(coverage, state.toolOutcomes, "a.txt"));
    }

    @Test void missingInstrumentationDoesNotDowngradeCapturedGapsToLegacy() {
        var state = state();
        read(state, "a.txt", false, false, ReadCoverage.UNSPECIFIED, true);
        LocalTurnTraceCapture.clear();
        var coverage = ReadEvidenceCoverage.capture(state);
        assertTrue(coverage.captured());
        assertEquals(Status.UNSATISFIED, status(coverage, state.toolOutcomes, "a.txt"));
        assertFalse(ReadEvidenceCoverage.empty().captured());
    }

    @Test void capturedEmptyStateIsMissingRequiredEvidence() {
        var state = state();
        var coverage = ReadEvidenceCoverage.capture(state);
        assertTrue(coverage.captured());
        assertEquals(Status.UNSATISFIED, status(coverage, state.toolOutcomes, "a.txt"));
    }

    @Test void deniedRequiredSourceDominatesMissingTargetsRegardlessOfOrder() {
        var state = state();
        var denied = new ToolOutcome("talos.read_file", "b.txt", false, false, true, "", "denied");
        state.messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall("denied", "talos.read_file", Map.of("path", "b.txt")))));
        state.messages.add(ChatMessage.toolResult("denied", "denied"));
        state.toolOutcomes.add(denied);
        state.toolOutcomeResults.bind(denied, 1, state.messages.getLast());
        var coverage = ReadEvidenceCoverage.capture(state);
        for (var order : List.of(List.of("a.txt", "b.txt"), List.of("b.txt", "a.txt"))) {
            var targets = new java.util.LinkedHashSet<>(order);
            assertEquals(Status.BLOCKED, coverage.assess(targets, targets, state.toolOutcomes, workspace, contract).status());
        }
        read(state, "b.txt", false, false, ReadCoverage.UNSPECIFIED, true);
        assertEquals(Status.BLOCKED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "b.txt"),
                "Another failed read cannot discharge an unresolved denial");
        full(state, "b.txt");
        assertEquals(Status.SATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "b.txt"));
    }

    @Test void mergeRetainsUntouchedSourcesAndUnresolvedGaps() {
        for (boolean firstSuccess : List.of(true, false)) {
            var first = state();
            read(first, "a.txt", firstSuccess, false, ReadCoverage.COMPLETE, true);
            var second = state();
            full(second, "b.txt");
            var merged = ReadEvidenceCoverage.capture(first).followedBy(ReadEvidenceCoverage.capture(second));
            var outcomes = new ArrayList<>(first.toolOutcomes);
            outcomes.addAll(second.toolOutcomes);
            assertEquals(firstSuccess ? Status.SATISFIED : Status.UNSATISFIED,
                    status(merged, outcomes, "a.txt", "b.txt"));
        }
    }

    @Test void laterScopedFullReadCanResolveEarlierGapButAbsentProjectionCannot() {
        var first = state();
        read(first, "a.txt", false, false, ReadCoverage.UNSPECIFIED, true);
        var second = state();
        full(second, "a.txt");
        var original = ReadEvidenceCoverage.capture(first);
        var outcomes = new ArrayList<>(first.toolOutcomes);
        outcomes.addAll(second.toolOutcomes);
        assertEquals(Status.SATISFIED, status(original.followedBy(ReadEvidenceCoverage.capture(second)), outcomes, "a.txt"));
        assertEquals(Status.UNSATISFIED, status(original.followedBy(ReadEvidenceCoverage.empty()), outcomes, "a.txt"));
    }

    @Test void readShapeSurvivesPrivacyMetadataCopy() {
        var metadata = dev.talos.tools.ToolContentMetadata.normal().withReadCoverage(ReadCoverage.LIMITED);
        var denied = metadata.withModelHandoffAllowed(false, "withheld");
        assertFalse(denied.modelHandoffAllowed());
        assertEquals(ReadCoverage.LIMITED, denied.readCoverage());
    }

    private ToolContentMetadata admittedDocument() {
        return ToolContentMetadata.extractedDocument("a.txt", true, true, false, false, "approved")
                .withReadCoverage(ReadCoverage.COMPLETE);
    }

    @Test void admittedSafeExtractionDoesNotBecomeFullOriginalSourceEvidence() {
        String safeText = "Patient: [redacted-private-document-canary]. Other admitted text.";
        var raw = state();
        read(raw, "a.txt", true, false, ToolContentMetadata.normal().withReadCoverage(ReadCoverage.COMPLETE),
                true, safeText, "");
        assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(raw), raw.toolOutcomes, "a.txt"));
        var extracted = state();
        var outcome = read(extracted, "a.txt", true, false, admittedDocument(), true, safeText, "");
        assertEquals(Status.SATISFIED, status(ReadEvidenceCoverage.capture(extracted), extracted.toolOutcomes, "a.txt"));
        assertFalse(extracted.toolOutcomeResults.readShape(outcome).full());
    }

    @Test void redactedExtractionCannotDischargeEarlierFailedOrWithheldEvidence() {
        for (boolean withheld : List.of(false, true)) {
            var state = state();
            read(state, "a.txt", withheld, withheld, ReadCoverage.COMPLETE, true);
            read(state, "a.txt", true, false, admittedDocument(), true,
                    "Patient: [redacted-private-document-canary]", "");
            assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
        }
    }

    @Test void extractionMetadataCannotBypassWithholdingPermissionOrClipping() {
        for (var metadata : List.of(admittedDocument(),
                admittedDocument().withModelHandoffAllowed(false, "denied"),
                admittedDocument().withReadCoverage(ReadCoverage.LIMITED))) {
            var state = state();
            boolean withheld = metadata.modelHandoffAllowed() && metadata.readCoverage() == ReadCoverage.COMPLETE;
            read(state, "a.txt", true, withheld, metadata, true,
                    "Patient: [redacted-private-document-canary]", "");
            assertEquals(Status.UNSATISFIED, status(ReadEvidenceCoverage.capture(state), state.toolOutcomes, "a.txt"));
        }
    }

    @Test void privacyNoticesRequireScopedBoundUnresolvedOccurrences() {
        var state = state();
        String notice = "Private document content was withheld by runtime policy.";
        read(state, "a.txt", true, true, admittedDocument(), true, "withheld", notice);
        var coverage = ReadEvidenceCoverage.capture(state);
        assertEquals(List.of(notice), coverage.missingPrivacyNotices(state.toolOutcomes, workspace, contract));
        assertTrue(coverage.missingPrivacyNotices(state.toolOutcomes, workspace.resolve("other"), contract).isEmpty());
        state.messages.set(1, ChatMessage.toolResult("read-0", "changed"));
        assertTrue(ReadEvidenceCoverage.capture(state)
                .missingPrivacyNotices(state.toolOutcomes, workspace, contract).isEmpty());
        var recovered = state();
        read(recovered, "a.txt", true, true, admittedDocument(), true, "withheld", notice);
        full(recovered, "a.txt");
        assertTrue(ReadEvidenceCoverage.capture(recovered)
                .missingPrivacyNotices(recovered.toolOutcomes, workspace, contract).isEmpty());
    }

    @Test void optionalNoticeAndProviderTextCannotSupplyRequiredPrivacyNotice() {
        var required = new TaskContract(TaskType.READ_ONLY_QA, false, false, false,
                Set.of("a.txt"), Set.of(), "Read a.txt. Do not edit.");
        TurnTaskContractCapture.set(required);
        var state = state();
        read(state, "a.txt", false, false, ReadCoverage.UNSPECIFIED, true);
        read(state, "b.txt", true, true, admittedDocument(), true, "withheld", "Optional privacy notice.");
        state.messages.add(ChatMessage.assistant("Private document content was withheld by runtime policy."));
        assertTrue(ReadEvidenceCoverage.capture(state)
                .missingPrivacyNotices(state.toolOutcomes, workspace, required).isEmpty());
    }
}
