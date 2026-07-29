package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.core.tool.ToolProtocolText;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.CheckpointStore;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderToolExecutionAuthorityTest {
    private static final String TOOL = "talos.authority_probe";
    private static final String WRITE_TOOL = "talos.write_file";
    private static final String EDIT_TOOL = "talos.edit_file";

    @TempDir
    Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void selectedEligibleForegroundNativeProposalExecutesExactlyOnce() {
        beginTrace("selected-positive");
        NativeToolCall proposal = proposal("positive.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(List.of(proposal), receipt);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(1, harness.approvals().get()),
                () -> assertEquals(1, harness.checkpoints().get()),
                () -> assertEquals(1, harness.invocations().get()),
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.failuresThisIteration()),
                () -> assertTrue(Files.exists(workspace.resolve("positive.txt"))),
                () -> assertEquals(1, countEvents(trace, "TOOL_EXECUTED")));
    }

    @Test
    void providerNameTransformationOutsideSelectedSurfaceStopsBeforeEveryEffect() {
        beginTrace("transformation-outside-selected-surface");
        NativeToolCall proposal = new NativeToolCall(
                "call-write-only",
                WRITE_TOOL,
                Map.of("path", "outside-surface.txt", "content", "must not be written"));
        ProviderResponseCapture.Receipt receipt = captureNative(
                List.of(proposal), List.of(WRITE_TOOL));
        Harness harness = harness(
                "", List.of(proposal), receipt, List.of(WRITE_TOOL, EDIT_TOOL));
        ToolCallParseStage.ParsedCalls parsed =
                new ToolCallParseStage().parse(harness.state(), 1);
        ToolCall original = parsed.calls().getFirst();
        LocalTurnTraceCapture.ToolCallAuthorityDecision authority =
                LocalTurnTraceCapture.authorizeParsedToolCallForExecution(
                        "tool_loop", original, parsed.lineage(0));
        ToolCall transformed = new ToolCall(
                EDIT_TOOL,
                Map.of("path", "outside-surface.txt", "content", "must not be written"));

        assertTrue(authority.authorized());
        assertFalse(LocalTurnTraceCapture.recordToolCallTransformedForExecution(
                authority.permit(),
                "tool_loop",
                transformed,
                LocalTurnTraceCapture.ToolCallTransformationReason.APPEND_WRITE_TO_EDIT));
        ToolResult result = harness.processor().executeToolAfterParsed(
                harness.state().toolSession,
                transformed,
                harness.state().ctx,
                authority.permit());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertFalse(result.success()),
                () -> assertEquals(ToolError.INVALID_AUTHORITY, result.error().code()),
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertFalse(Files.exists(workspace.resolve("outside-surface.txt"))),
                () -> assertEquals(0, countEvents(trace, "APPROVAL_REQUIRED")),
                () -> assertEquals(0, countEvents(trace, "CHECKPOINT_CAPTURED")),
                () -> assertEquals(0, countEvents(trace, "TOOL_EXECUTED")),
                () -> assertTrue(trace.events().stream()
                        .filter(event -> "TOOL_CALL_AUTHORITY_REJECTED".equals(event.type()))
                        .map(event -> (Map<?, ?>) event.data().get("executionAuthority"))
                        .anyMatch(evidence -> "PROVIDER_TOOL_SURFACE_MISMATCH".equals(
                                evidence.get("reasonCode")))));
    }

    @Test
    void appendWriteSteeringOutsideSelectedSurfaceSettlesWithoutException()
            throws Exception {
        beginTrace("append-steering-outside-selected-surface");
        String target = "README.md";
        String request = "Read README.md, then append exactly this line to README.md: "
                + "Release gate note";
        Files.writeString(workspace.resolve(target), "# Demo\n");
        NativeToolCall proposal = new NativeToolCall(
                "call-append-write-only",
                WRITE_TOOL,
                Map.of(
                        "path", target,
                        "content", "# Demo\nRelease gate note\n"));
        ProviderResponseCapture.Receipt receipt = captureNative(
                List.of(proposal), List.of(WRITE_TOOL));
        Harness harness = harness(
                "", List.of(proposal), receipt, List.of(WRITE_TOOL, EDIT_TOOL));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                harness.state(),
                new ToolCall("talos.read_file", Map.of("path", target)),
                target,
                ToolResult.ok("1 | # Demo\n"));

        ToolCallExecutionStage.IterationOutcome outcome;
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
        try {
            outcome = execute(harness);
        } finally {
            TurnTaskContractCapture.clear();
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        var blockedEvent = trace.events().stream()
                .filter(event -> "TOOL_CALL_BLOCKED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        var classifiedEvent = trace.events().stream()
                .filter(event -> "TOOL_OUTCOME_CLASSIFIED".equals(event.type()))
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertEquals("# Demo\n",
                        Files.readString(workspace.resolve(target))),
                () -> assertEquals(ToolError.INVALID_AUTHORITY,
                        harness.state().toolOutcomes.getFirst().errorCode()),
                () -> assertEquals(
                        ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                        harness.state().toolOutcomes.getFirst().failureReason()),
                () -> assertEquals(1,
                        countEvents(trace, "TOOL_CALL_AUTHORITY_REJECTED")),
                () -> assertEquals(1, countEvents(trace, "TOOL_CALL_BLOCKED")),
                () -> assertEquals(1, countEvents(trace, "TOOL_OUTCOME_CLASSIFIED")),
                () -> assertEquals(
                        blockedEvent.data().get("toolCallId"),
                        classifiedEvent.data().get("toolCallId")),
                () -> assertEquals(
                        ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID.name(),
                        classifiedEvent.data().get("failureReason")),
                () -> assertTrue(
                        trace.events().indexOf(classifiedEvent)
                                > trace.events().indexOf(blockedEvent)),
                () -> assertEquals(0, countEvents(trace, "APPROVAL_REQUIRED")),
                () -> assertEquals(0, countEvents(trace, "CHECKPOINT_CAPTURED")),
                () -> assertEquals(0, countEvents(trace, "TOOL_EXECUTED")));
    }

    @Test
    void providerNameTransformationWithinSelectedSurfaceRetainsOneUseAuthority()
            throws Exception {
        beginTrace("transformation-within-selected-surface");
        Files.writeString(workspace.resolve("within-surface.txt"), "original");
        NativeToolCall proposal = new NativeToolCall(
                "call-write-edit-selected",
                WRITE_TOOL,
                Map.of("path", "within-surface.txt", "content", "selected surface"));
        ProviderResponseCapture.Receipt receipt = captureNative(
                List.of(proposal), List.of(WRITE_TOOL, EDIT_TOOL));
        Harness harness = harness(
                "", List.of(proposal), receipt, List.of(WRITE_TOOL, EDIT_TOOL));
        ToolCallParseStage.ParsedCalls parsed =
                new ToolCallParseStage().parse(harness.state(), 1);
        ToolCall original = parsed.calls().getFirst();
        LocalTurnTraceCapture.ToolCallAuthorityDecision authority =
                LocalTurnTraceCapture.authorizeParsedToolCallForExecution(
                        "tool_loop", original, parsed.lineage(0));
        ToolCall transformed = new ToolCall(
                EDIT_TOOL,
                Map.of(
                        "path", "within-surface.txt",
                        "old_string", "original",
                        "new_string", "selected surface"));

        assertTrue(authority.authorized());
        assertTrue(LocalTurnTraceCapture.recordToolCallTransformedForExecution(
                authority.permit(),
                "tool_loop",
                transformed,
                LocalTurnTraceCapture.ToolCallTransformationReason.APPEND_WRITE_TO_EDIT));
        ToolResult result = harness.processor().executeToolAfterParsed(
                harness.state().toolSession,
                transformed,
                harness.state().ctx,
                authority.permit());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertTrue(result.success(), result.toString()),
                () -> assertEquals(1, harness.approvals().get()),
                () -> assertEquals(1, harness.checkpoints().get()),
                () -> assertEquals(1, harness.invocations().get()),
                () -> assertEquals("selected surface",
                        Files.readString(workspace.resolve("within-surface.txt"))),
                () -> assertEquals(1, countEvents(trace, "TOOL_CALL_TRANSFORMED")),
                () -> assertEquals(1, countEvents(trace, "TOOL_EXECUTED")));
    }

    @Test
    void missingProviderReceiptStopsBeforeEveryEffect() {
        beginTrace("missing-receipt");
        Harness harness = harness(
                List.of(proposal("missing.txt")),
                ProviderResponseCapture.Receipt.none());

        assertAuthorityBlocked(harness, "missing.txt");
    }

    @Test
    void staleSelectedGenerationStopsBeforeEveryEffect() {
        beginTrace("stale-generation");
        NativeToolCall staleProposal = proposal("stale.txt");
        ProviderResponseCapture.Receipt stale = capture(
                List.of(staleProposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        capture(
                List.of(proposal("current.txt")),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(List.of(staleProposal), stale);

        assertAuthorityBlocked(harness, "stale.txt");
    }

    @Test
    void receiptSelectedInAnotherTraceCannotAuthorizeExecution() {
        beginTrace("cross-trace-source");
        NativeToolCall proposal = proposal("cross-trace.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        LocalTurnTraceCapture.complete();

        beginTrace("cross-trace-consumer");

        assertAuthorityBlocked(harness(List.of(proposal), receipt), "cross-trace.txt");
    }

    @Test
    void terminalIneligibleNormalizedEosStopsBeforeEveryEffect() {
        beginTrace("normalized-eos");
        NativeToolCall proposal = proposal("normalized.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                true,
                false);
        assertTrue(receipt.providerToolCallEligible(), "red fixture must retain tool eligibility");
        assertFalse(receipt.providerTerminalEligible(), "red fixture must be terminal-ineligible");
        Harness harness = harness(List.of(proposal), receipt);

        assertAuthorityBlocked(harness, "normalized.txt");
    }

    @Test
    void mismatchedNativeProposalStopsBeforeEveryEffect() {
        beginTrace("mismatched-proposal");
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal("provider.txt")),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(List.of(proposal("substituted.txt")), receipt);

        assertAuthorityBlocked(harness, "substituted.txt");
    }

    @Test
    void proposalBeyondBoundedEvidenceStopsBeforeItsEffects() {
        beginTrace("proposal-bound");
        List<NativeToolCall> proposals = new ArrayList<>();
        for (int ordinal = 1;
                ordinal <= ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS + 1;
                ordinal++) {
            proposals.add(proposal("proposal-" + ordinal + ".txt"));
        }
        ProviderResponseCapture.Receipt receipt = capture(
                proposals,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(proposals, receipt);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        Path unboundedTarget = workspace.resolve(
                "proposal-" + (ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS + 1) + ".txt");

        assertAll(
                () -> assertEquals(
                        ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS,
                        harness.approvals().get()),
                () -> assertEquals(
                        ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS,
                        harness.checkpoints().get()),
                () -> assertEquals(
                        ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS,
                        harness.invocations().get()),
                () -> assertEquals(
                        ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS,
                        outcome.successesThisIteration()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertFalse(Files.exists(unboundedTarget)),
                () -> assertEquals(
                        ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS,
                        countEvents(trace, "TOOL_EXECUTED")));
    }

    @Test
    void nonForegroundAndSyntheticSourcesCannotAuthorizeProviderExecution() {
        for (ProviderResponseCapture.Source source : List.of(
                ProviderResponseCapture.Source.BACKGROUND_PROVIDER,
                ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                ProviderResponseCapture.Source.SCRIPTED_HARNESS)) {
            LocalTurnTraceCapture.clear();
            beginTrace("source-" + source.name().toLowerCase());
            NativeToolCall proposal = proposal(source.name().toLowerCase() + ".txt");
            ProviderResponseCapture.Completion completion =
                    source == ProviderResponseCapture.Source.BACKGROUND_PROVIDER
                            ? ProviderResponseCapture.Completion.PROVIDER_EOS
                            : ProviderResponseCapture.Completion.SYNTHETIC_RETURN;
            ProviderResponseCapture.Receipt receipt = capture(
                    "",
                    List.of(proposal),
                    source,
                    completion,
                    false,
                    false,
                    false);

            assertAuthorityBlocked(
                    harness(List.of(proposal), receipt),
                    source.name().toLowerCase() + ".txt");
        }
    }

    @Test
    void incompleteProviderStopsCannotAuthorizeNativeExecution() {
        for (ProviderResponseCapture.Completion completion : List.of(
                ProviderResponseCapture.Completion.PROVIDER_LENGTH,
                ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP,
                ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS,
                ProviderResponseCapture.Completion.RUNTIME_ABORT,
                ProviderResponseCapture.Completion.RUNTIME_SAFE_CAP,
                ProviderResponseCapture.Completion.RUNTIME_LEGACY_TEXT_STOP,
                ProviderResponseCapture.Completion.SYNTHETIC_RETURN)) {
            LocalTurnTraceCapture.clear();
            beginTrace("completion-" + completion.name().toLowerCase());
            String target = completion.name().toLowerCase() + ".txt";
            NativeToolCall proposal = proposal(target);
            ProviderResponseCapture.Receipt receipt = capture(
                    List.of(proposal), completion, false, false);

            assertAuthorityBlocked(harness(List.of(proposal), receipt), target);
        }
    }

    @Test
    void forgedReceiptCannotAuthorizeExecution() {
        beginTrace("forged-receipt");
        NativeToolCall proposal = proposal("forged.txt");
        ProviderResponseCapture.Receipt receipt = new ProviderResponseCapture.Receipt(
                "forged-generation",
                "forged-attempt",
                1,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                true,
                true,
                "sha256:" + "0".repeat(64));

        assertAuthorityBlocked(harness(List.of(proposal), receipt), "forged.txt");
    }

    @Test
    void staleAttemptReceiptCannotAuthorizeSelectedRetry() {
        beginTrace("stale-attempt");
        NativeToolCall staleProposal = proposal("stale-attempt.txt");
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
        ProviderResponseCapture.Attempt first = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                first, ProviderTransportHandoff.DispatchEvidence.unbound());
        ProviderResponseCapture.Receipt stale = first.respond(
                providerResponse("", List.of(staleProposal),
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false, false, false));
        ProviderResponseCapture.Attempt retry = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.TRANSIENT_RETRY,
                ProviderResponseCapture.RetryReason.TRANSIENT_ENGINE_FAILURE);
        ObservedProviderTransportFixture.completeTerminal(
                retry, ProviderTransportHandoff.DispatchEvidence.unbound());
        ProviderResponseCapture.Receipt selected = retry.respond(
                providerResponse("", List.of(proposal("selected-attempt.txt")),
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false, false, false));
        generation.complete(selected);

        assertAuthorityBlocked(
                harness(List.of(staleProposal), stale), "stale-attempt.txt");
    }

    @Test
    void failedOrAbandonedGenerationCannotAuthorizeReturnedProposal() {
        for (boolean abandon : List.of(false, true)) {
            LocalTurnTraceCapture.clear();
            beginTrace(abandon ? "abandoned-generation" : "failed-generation");
            String target = abandon ? "abandoned.txt" : "failed.txt";
            NativeToolCall proposal = proposal(target);
            ProviderResponseCapture.Generation generation =
                    ProviderResponseCapture.openGeneration(
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Purpose.TURN_RESPONSE,
                            ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
            ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                    ProviderResponseCapture.AttemptKind.PRIMARY,
                    ProviderResponseCapture.RetryReason.NONE);
            ObservedProviderTransportFixture.completeTerminal(
                    attempt, ProviderTransportHandoff.DispatchEvidence.unbound());
            ProviderResponseCapture.Receipt receipt = attempt.respond(
                    providerResponse("", List.of(proposal),
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Completion.PROVIDER_EOS,
                            false, false, false));
            if (abandon) {
                generation.abandon(ProviderResponseCapture.Abandonment.SCOPE_CLOSED);
            } else {
                generation.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
            }

            assertAuthorityBlocked(harness(List.of(proposal), receipt), target);
        }
    }

    @Test
    void selectedProposalCannotBeAuthorizedTwice() {
        beginTrace("proposal-reuse");
        NativeToolCall proposal = proposal("reuse.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness first = harness(List.of(proposal), receipt);
        Harness second = harness(List.of(proposal), receipt);

        ToolCallExecutionStage.IterationOutcome firstOutcome = execute(first);
        ToolCallExecutionStage.IterationOutcome secondOutcome = execute(second);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(1, firstOutcome.successesThisIteration()),
                () -> assertEquals(0, firstOutcome.failuresThisIteration()),
                () -> assertEquals(0, secondOutcome.successesThisIteration()),
                () -> assertEquals(1, secondOutcome.failuresThisIteration()),
                () -> assertEquals(1, first.invocations().get()),
                () -> assertEquals(0, second.approvals().get()),
                () -> assertEquals(0, second.checkpoints().get()),
                () -> assertEquals(0, second.invocations().get()),
                () -> assertEquals(1, countEvents(trace, "TOOL_EXECUTED")),
                () -> assertEquals(1,
                        countEvents(trace, "TOOL_CALL_AUTHORITY_REJECTED")));
    }

    @Test
    void byteIdenticalProposalsRemainBoundToTheirRawCallIdsAndOrdinals() {
        beginTrace("identical-proposal-swap");
        Map<String, Object> arguments =
                Map.of("path", "identical.txt", "content", "authority probe");
        NativeToolCall first = new NativeToolCall("call-identical-a", TOOL, arguments);
        NativeToolCall second = new NativeToolCall("call-identical-b", TOOL, arguments);
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(first, second),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(List.of(second, first), receipt);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(2, outcome.failuresThisIteration()),
                () -> assertFalse(Files.exists(workspace.resolve("identical.txt"))),
                () -> assertEquals(2,
                        countEvents(trace, "TOOL_CALL_AUTHORITY_REJECTED")),
                () -> assertEquals(0, countEvents(trace, "TOOL_EXECUTED")));
    }

    @Test
    void byteIdenticalProposalsExecuteIndependentlyInTheirOriginalOrder() {
        beginTrace("identical-proposal-positive");
        Map<String, Object> arguments =
                Map.of("path", "identical-positive.txt", "content", "authority probe");
        NativeToolCall first = new NativeToolCall("call-identical-positive-a", TOOL, arguments);
        NativeToolCall second = new NativeToolCall("call-identical-positive-b", TOOL, arguments);
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(first, second),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(List.of(first, second), receipt);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(2, harness.approvals().get()),
                () -> assertEquals(2, harness.checkpoints().get()),
                () -> assertEquals(2, harness.invocations().get()),
                () -> assertEquals(2, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.failuresThisIteration()),
                () -> assertEquals(2, countEvents(trace, "TOOL_EXECUTED")),
                () -> assertEquals(2, trace.events().stream()
                        .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                        .map(event -> (Map<?, ?>) event.data().get("executionAuthority"))
                        .map(authority -> authority.get("providerCallIdHash"))
                        .distinct()
                        .count()));
    }

    @Test
    void nativeProposalWithWhitespaceCannotBorrowCanonicalSurfaceAuthority() {
        beginTrace("raw-tool-name-whitespace");
        String rawToolName = " " + TOOL + " ";
        NativeToolCall proposal = new NativeToolCall(
                "call-whitespace",
                rawToolName,
                Map.of("path", "whitespace.txt", "content", "authority probe"));
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(List.of(proposal), receipt);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        String rawHash = ProviderResponseCapture.toolNameHash(rawToolName);

        assertAll(
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertFalse(Files.exists(workspace.resolve("whitespace.txt"))),
                () -> assertTrue(trace.events().stream()
                        .filter(event -> "PROVIDER_TOOL_PROPOSAL_CAPTURED".equals(event.type()))
                        .anyMatch(event -> rawHash.equals(event.data().get("toolNameHash"))
                                && rawToolName.length()
                                == ((Number) event.data().get("toolNameChars")).intValue())),
                () -> assertEquals(0, countEvents(trace, "TOOL_CALL_PARSED")),
                () -> assertTrue(trace.events().stream()
                        .filter(event -> "TOOL_CALL_AUTHORITY_REJECTED".equals(event.type()))
                        .map(event -> (Map<?, ?>) event.data().get("executionAuthority"))
                        .anyMatch(authority -> "PROVIDER_TOOL_SURFACE_MISMATCH".equals(
                                authority.get("reasonCode")))));
    }

    @Test
    void selectedJsonTextCallRequiresItsExactParserLineage() {
        beginTrace("json-text-positive");
        String text = textProposal("json-text.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                true);
        Harness harness = harness(text, List.of(), receipt);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.failuresThisIteration()),
                () -> assertEquals(1, harness.approvals().get()),
                () -> assertEquals(1, harness.checkpoints().get()),
                () -> assertEquals(1, harness.invocations().get()),
                () -> assertTrue(Files.exists(workspace.resolve("json-text.txt"))),
                () -> assertTrue(trace.events().stream().anyMatch(event ->
                        "TOOL_CALL_PARSED".equals(event.type())
                                && "PROVIDER_TEXT_JSON".equals(
                                        ((Map<?, ?>) event.data().get("executionAuthority"))
                                                .get("kind")))));
    }

    @Test
    void adjacentJsonTextCallsWithBracesUseProviderOwnedParserLineage() {
        beginTrace("json-text-adjacent");
        String firstTarget = "json-adjacent-a.txt";
        String secondTarget = "json-adjacent-b.txt";
        String text = rawTextProposal(firstTarget, ".a { color: red; }")
                + rawTextProposal(secondTarget, ".b { margin: 0; }");
        boolean textualToolCallPresent = ToolProtocolText.containsToolCalls(text);
        assertTrue(textualToolCallPresent,
                "receipt detector must recognize every adjacent JSON shape the parser accepts");
        ProviderResponseCapture.Receipt receipt = capture(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                textualToolCallPresent);
        Harness harness = harness(text, List.of(), receipt);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(2, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.failuresThisIteration()),
                () -> assertEquals(2, harness.approvals().get()),
                () -> assertEquals(2, harness.checkpoints().get()),
                () -> assertEquals(2, harness.invocations().get()),
                () -> assertTrue(Files.exists(workspace.resolve(firstTarget))),
                () -> assertTrue(Files.exists(workspace.resolve(secondTarget))),
                () -> assertEquals(2, trace.events().stream()
                        .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                        .map(event -> (Map<?, ?>) event.data().get("executionAuthority"))
                        .filter(authority -> "PROVIDER_TEXT_JSON".equals(authority.get("kind")))
                        .count()));
    }

    @Test
    void truncatedProviderJsonTextStopsBeforeEveryEffect() {
        beginTrace("json-text-truncated");
        String text = textProposal("truncated-text.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                true,
                true);

        assertAuthorityBlocked(
                harness(text, List.of(), receipt),
                "truncated-text.txt");
    }

    @Test
    void parserByteEqualityWithoutBoundLineageCannotAuthorizeTextExecution() {
        beginTrace("json-text-unbound");
        String text = textProposal("unbound-text.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                true);
        Harness harness = harness(text, List.of(), receipt);
        ToolCallParseStage.ParsedCalls unbound =
                new ToolCallParseStage().parse(text, List.of(), 1);

        ToolCallExecutionStage.IterationOutcome outcome =
                harness.stage().execute(harness.state(), unbound);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertFalse(Files.exists(workspace.resolve("unbound-text.txt"))),
                () -> assertEquals(1,
                        countEvents(trace, "TOOL_CALL_AUTHORITY_REJECTED")));
    }

    @Test
    void xmlTextCallCannotExecuteWithoutExplicitCompatibilityAuthority() {
        beginTrace("xml-text-blocked");
        String text = "<tool_call>{\"name\":\"" + TOOL
                + "\",\"arguments\":{\"path\":\"xml.txt\","
                + "\"content\":\"authority probe\"}}</tool_call>";
        ProviderResponseCapture.Receipt receipt = capture(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                true);
        Harness harness = harness(text, List.of(), receipt);

        assertAuthorityBlocked(harness, "xml.txt");
    }

    @Test
    void deterministicRuntimeCallUsesSeparateExplicitAuthority() {
        beginTrace("runtime-authority");
        NativeToolCall proposal = proposal("runtime.txt");
        Harness harness = harness(
                List.of(proposal), ProviderResponseCapture.Receipt.none());
        harness.state().setRuntimeNativeCalls(
                "",
                List.of(proposal),
                LocalTurnTraceCapture.RuntimeToolAuthorityReason
                        .DIRECT_RUNTIME_API);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.failuresThisIteration()),
                () -> assertEquals(1, harness.invocations().get()),
                () -> assertTrue(Files.exists(workspace.resolve("runtime.txt"))),
                () -> assertTrue(trace.events().stream().anyMatch(event ->
                        "TOOL_CALL_PARSED".equals(event.type())
                                && "RUNTIME_DETERMINISTIC".equals(
                                        ((Map<?, ?>) event.data().get("executionAuthority"))
                                                .get("kind")))));
    }

    @Test
    void newerGenerationRevokesAnAuthorizedButUnconsumedProposal() {
        beginTrace("authority-revoked");
        NativeToolCall proposal = proposal("revoked.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(List.of(proposal), receipt);
        ToolCallParseStage.ParsedCalls parsed =
                new ToolCallParseStage().parse(harness.state(), 1);
        ToolCall call = parsed.calls().getFirst();
        LocalTurnTraceCapture.ToolCallAuthorityDecision authority =
                LocalTurnTraceCapture.authorizeParsedToolCallForExecution(
                        "tool_loop", call, parsed.lineage(0));
        assertTrue(authority.authorized());

        capture(
                List.of(proposal("replacement.txt")),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        ToolResult result = harness.processor().executeToolAfterParsed(
                harness.state().toolSession,
                call,
                harness.state().ctx,
                authority.permit());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertFalse(result.success()),
                () -> assertEquals(ToolError.INVALID_AUTHORITY, result.error().code()),
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertFalse(Files.exists(workspace.resolve("revoked.txt"))),
                () -> assertEquals(1, countEvents(trace, "TOOL_CALL_PARSED")),
                () -> assertEquals(1, countEvents(trace, "TOOL_CALL_BLOCKED")),
                () -> assertEquals(0, countEvents(trace, "TOOL_EXECUTED")));
    }

    @Test
    void providerPermitCannotBeConsumedOnAnotherThread() throws Exception {
        beginTrace("foreign-thread");
        NativeToolCall proposal = proposal("foreign-thread.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        Harness harness = harness(List.of(proposal), receipt);
        ToolCallParseStage.ParsedCalls parsed =
                new ToolCallParseStage().parse(harness.state(), 1);
        ToolCall call = parsed.calls().getFirst();
        LocalTurnTraceCapture.ToolCallAuthorityDecision authority =
                LocalTurnTraceCapture.authorizeParsedToolCallForExecution(
                        "tool_loop", call, parsed.lineage(0));
        assertTrue(authority.authorized());

        var worker = Executors.newSingleThreadExecutor();
        ToolResult result;
        try {
            result = worker.submit(() -> harness.processor().executeToolAfterParsed(
                            harness.state().toolSession,
                            call,
                            harness.state().ctx,
                            authority.permit()))
                    .get(10, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertFalse(result.success()),
                () -> assertEquals(ToolError.INVALID_AUTHORITY, result.error().code()),
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertFalse(Files.exists(workspace.resolve("foreign-thread.txt"))),
                () -> assertEquals(0, countEvents(trace, "TOOL_EXECUTED")));
    }

    @Test
    void providerExecutionFailsClosedWithoutAnActiveTrace() {
        beginTrace("trace-inactive-fixture");
        NativeToolCall proposal = proposal("trace-inactive.txt");
        ProviderResponseCapture.Receipt receipt = capture(
                List.of(proposal),
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
        LocalTurnTraceCapture.complete();
        Harness harness = harness(List.of(proposal), receipt);

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        assertAll(
                () -> assertFalse(LocalTurnTraceCapture.isActive()),
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertFalse(Files.exists(workspace.resolve("trace-inactive.txt"))));
    }

    @Test
    void missingOrWrongProposalOrdinalCannotMintAuthority() {
        for (int ordinal : List.of(0, 2)) {
            LocalTurnTraceCapture.clear();
            beginTrace("wrong-ordinal-" + ordinal);
            NativeToolCall proposal = proposal("wrong-ordinal-" + ordinal + ".txt");
            ProviderResponseCapture.Receipt receipt = capture(
                    List.of(proposal),
                    ProviderResponseCapture.Completion.PROVIDER_EOS,
                    false,
                    false);
            ToolCall call = ToolCallSupport.convertNativeToolCalls(
                    List.of(proposal)).getFirst();
            LocalTurnTraceCapture.ParsedToolCallLineage lineage =
                    LocalTurnTraceCapture.captureProviderNativeToolCallLineage(
                            receipt,
                            call,
                            proposal,
                            ordinal);

            LocalTurnTraceCapture.ToolCallAuthorityDecision decision =
                    LocalTurnTraceCapture.authorizeParsedToolCallForExecution(
                            "tool_loop", call, lineage);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertAll(
                    () -> assertFalse(decision.authorized()),
                    () -> assertEquals(
                            LocalTurnTraceCapture.ToolCallAuthorityRejectionReason
                                    .PROVIDER_PROPOSAL_ORDINAL_INVALID,
                            decision.rejectionReason()),
                    () -> assertEquals(1,
                            countEvents(trace, "TOOL_CALL_AUTHORITY_REJECTED")),
                    () -> assertEquals(0, countEvents(trace, "TOOL_EXECUTED")));
        }
    }

    private void assertAuthorityBlocked(Harness harness, String target) {
        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertAll(
                () -> assertEquals(0, harness.approvals().get()),
                () -> assertEquals(0, harness.checkpoints().get()),
                () -> assertEquals(0, harness.invocations().get()),
                () -> assertEquals(0, harness.state().totalToolsInvoked),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertEquals(ToolError.INVALID_AUTHORITY,
                        harness.state().toolOutcomes.getFirst().errorCode()),
                () -> assertEquals(
                        ToolFailureReason.TOOL_EXECUTION_AUTHORITY_INVALID,
                        harness.state().toolOutcomes.getFirst().failureReason()),
                () -> assertFalse(Files.exists(workspace.resolve(target))),
                () -> assertEquals(0, countEvents(trace, "APPROVAL_REQUIRED")),
                () -> assertEquals(0, countEvents(trace, "APPROVAL_GRANTED")),
                () -> assertEquals(0, countEvents(trace, "CHECKPOINT_CAPTURED")),
                () -> assertEquals(0, countEvents(trace, "TOOL_EXECUTED")),
                () -> assertEquals(1,
                        countEvents(trace, "TOOL_CALL_AUTHORITY_REJECTED")));
    }

    private ToolCallExecutionStage.IterationOutcome execute(Harness harness) {
        ToolCallParseStage.ParsedCalls parsed =
                new ToolCallParseStage().parse(harness.state(), 1);
        return harness.stage().execute(harness.state(), parsed);
    }

    private Harness harness(
            List<NativeToolCall> proposals,
            ProviderResponseCapture.Receipt receipt
    ) {
        return harness("", proposals, receipt);
    }

    private Harness harness(
            String text,
            List<NativeToolCall> proposals,
            ProviderResponseCapture.Receipt receipt
    ) {
        return harness(text, proposals, receipt, List.of(TOOL));
    }

    private Harness harness(
            String text,
            List<NativeToolCall> proposals,
            ProviderResponseCapture.Receipt receipt,
            List<String> registeredToolNames
    ) {
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger checkpoints = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        Config config = new Config(null);
        config.data.put("checkpoint", Map.of(
                "enabled", true,
                "fail_closed", true,
                "max_file_bytes", 1_000_000,
                "max_turn_bytes", 2_000_000));
        ToolRegistry registry = new ToolRegistry();
        for (String toolName : registeredToolNames) {
            registry.register(new ProbeTool(toolName, workspace, invocations));
        }
        ApprovalGate gate = new ApprovalGate() {
            @Override
            public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }

            @Override
            public ApprovalResponse approveFull(String description, String detail) {
                approvals.incrementAndGet();
                return ApprovalResponse.APPROVED;
            }
        };
        CheckpointService checkpointService =
                new CheckpointService(new CountingCheckpointStore(checkpoints));
        TurnProcessor processor = new TurnProcessor(
                null,
                gate,
                registry,
                ApprovalPolicy.ALWAYS_ASK,
                checkpointService);
        Context context = Context.builder(config)
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .build();
        LoopState state = new LoopState(
                text,
                proposals,
                receipt,
                new ArrayList<>(List.of(
                        ChatMessage.system("system"),
                        ChatMessage.user("Create the requested probe files."))),
                workspace,
                context,
                new Session(workspace, config),
                5,
                0);
        return new Harness(
                new ToolCallExecutionStage(processor, null, false),
                processor,
                state,
                approvals,
                checkpoints,
                invocations);
    }

    private static ProviderResponseCapture.Receipt capture(
            List<NativeToolCall> proposals,
            ProviderResponseCapture.Completion completion,
            boolean normalized,
            boolean truncated
    ) {
        return capture(
                "",
                proposals,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                completion,
                normalized,
                truncated,
                false);
    }

    private static ProviderResponseCapture.Receipt capture(
            String assistantText,
            List<NativeToolCall> proposals,
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Completion completion,
            boolean normalized,
            boolean truncated,
            boolean textualToolCallPresent
    ) {
        ProviderResponseCapture.Purpose purpose = switch (source) {
            case FOREGROUND_PROVIDER -> ProviderResponseCapture.Purpose.TURN_RESPONSE;
            case BACKGROUND_PROVIDER ->
                    ProviderResponseCapture.Purpose.BACKGROUND_MAINTENANCE;
            case PLACEHOLDER_RUNTIME ->
                    ProviderResponseCapture.Purpose.PLACEHOLDER_RESPONSE;
            case SCRIPTED_HARNESS ->
                    ProviderResponseCapture.Purpose.HARNESS_FIXTURE;
        };
        ProviderResponseCapture.ToolAuthorityMode mode =
                source == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                        ? !proposals.isEmpty()
                                ? ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                                : textualToolCallPresent
                                        ? ProviderResponseCapture.ToolAuthorityMode
                                                .PROVIDER_TEXT_JSON
                                        : ProviderResponseCapture.ToolAuthorityMode.NONE
                        : ProviderResponseCapture.ToolAuthorityMode.NONE;
        ProviderResponseCapture.ToolAuthorityContract contract =
                mode == ProviderResponseCapture.ToolAuthorityMode.NONE
                        ? ProviderResponseCapture.ToolAuthorityContract.none()
                        : ProviderResponseCapture.ToolAuthorityContract.bound(
                                mode, List.of(TOOL), ToolChoiceMode.AUTO, "");
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                source, purpose, contract);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        if (source == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                || source == ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
            ProviderTransportHandoff.DispatchEvidence evidence =
                    new ProviderTransportHandoff.DispatchEvidence(
                            "sha256:" + "a".repeat(64),
                            mode == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                                    ? List.of(TOOL)
                                    : List.of());
            if (completion == ProviderResponseCapture.Completion.PROVIDER_EOS
                    || completion == ProviderResponseCapture.Completion.PROVIDER_LENGTH
                    || completion
                            == ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP) {
                ObservedProviderTransportFixture.completeTerminal(attempt, evidence);
            } else {
                ObservedProviderTransportFixture.completeBodyEof(attempt, evidence);
            }
        }
        ProviderResponseCapture.Receipt receipt = attempt.respond(providerResponse(
                assistantText,
                proposals,
                source,
                completion,
                normalized,
                truncated,
                textualToolCallPresent));
        generation.complete(receipt);
        return receipt;
    }

    private static ProviderResponseCapture.Receipt captureNative(
            List<NativeToolCall> proposals,
            List<String> selectedToolNames
    ) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        selectedToolNames,
                        ToolChoiceMode.AUTO,
                        ""));
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                attempt,
                new ProviderTransportHandoff.DispatchEvidence(
                        "sha256:" + "b".repeat(64), selectedToolNames));
        ProviderResponseCapture.Receipt receipt = attempt.respond(providerResponse(
                "",
                proposals,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                false));
        generation.complete(receipt);
        return receipt;
    }

    private static NativeToolCall proposal(String path) {
        return new NativeToolCall(
                "call-" + path,
                TOOL,
                Map.of("path", path, "content", "authority probe"));
    }

    private static String textProposal(String path) {
        return "```json\n"
                + "{\"name\":\"" + TOOL + "\",\"arguments\":{"
                + "\"path\":\"" + path + "\","
                + "\"content\":\"authority probe\"}}\n"
                + "```";
    }

    private static String rawTextProposal(String path, String content) {
        return "{\"name\":\"" + TOOL + "\",\"arguments\":{"
                + "\"path\":\"" + path + "\","
                + "\"content\":\"" + content + "\"}}";
    }

    private static ProviderResponseCapture.Response providerResponse(
            String assistantText,
            List<NativeToolCall> proposals,
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Completion completion,
            boolean normalized,
            boolean truncated,
            boolean textualToolCallPresent
    ) {
        return new ProviderResponseCapture.Response(
                assistantText,
                proposals,
                source,
                completion,
                normalized,
                truncated,
                textualToolCallPresent);
    }

    private static long countEvents(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(event -> type.equals(event.type()))
                .count();
    }

    private static void beginTrace(String suffix) {
        LocalTurnTraceCapture.begin(
                "trace-t1193-" + suffix,
                "session-t1193",
                1,
                "2026-07-20T00:00:00Z",
                "workspace-t1193",
                "test",
                "scripted",
                "model",
                "T1193 provider authority red");
    }

    private record Harness(
            ToolCallExecutionStage stage,
            TurnProcessor processor,
            LoopState state,
            AtomicInteger approvals,
            AtomicInteger checkpoints,
            AtomicInteger invocations
    ) {}

    private record ProbeTool(
            String name,
            Path workspace,
            AtomicInteger invocations
    ) implements TalosTool {
        @Override
        public String description() {
            return "T1193 authority probe";
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(
                    name,
                    description(),
                    "{}",
                    ToolRiskLevel.WRITE,
                    ToolOperationMetadata.defaultFor(name, ToolRiskLevel.WRITE));
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext ctx) {
            invocations.incrementAndGet();
            try {
                String content = call.param("content");
                if (content == null) content = call.param("new_string");
                Files.writeString(workspace.resolve(call.param("path")), content);
                return ToolResult.ok("probe written");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private record CountingCheckpointStore(AtomicInteger captures) implements CheckpointStore {
        @Override
        public CheckpointCaptureResult captureBeforeMutation(
                Path workspace,
                Config config,
                ToolCall call,
                String traceId,
                int turnNumber
        ) {
            return CheckpointCaptureResult.captured(
                    "checkpoint-" + captures.incrementAndGet(),
                    0);
        }

        @Override
        public CheckpointRestoreResult restore(Path workspace, String checkpointId) {
            return CheckpointRestoreResult.failure(checkpointId, "not implemented");
        }
    }
}
