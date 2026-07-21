package dev.talos.spi.types;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderResponseCaptureOwnershipTest {
    @AfterEach
    void clearCapture() {
        ProviderResponseCapture.clear();
    }

    @Test
    void fabricatedReceiptWithCopiedVisibleFieldsCannotCompleteGeneration() {
        Capture listener = new Capture();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(listener, "receipt-owner");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt issued = attempt.respond(response("exact answer"));
        ProviderResponseCapture.Receipt forged = new ProviderResponseCapture.Receipt(
                issued.generationId(),
                issued.attemptId(),
                issued.attemptOrdinal(),
                issued.source(),
                issued.purpose(),
                issued.completion(),
                true,
                false,
                issued.assistantHash());

        assertThrows(IllegalArgumentException.class, () -> generation.complete(forged));
        assertFalse(forged.selected());
        assertFalse(forged.providerTerminalEligible());
        assertFalse(issued.selected());
        assertEquals(0, listener.completions.size());

        generation.complete(issued);
        assertTrue(issued.selected());
        assertTrue(issued.providerTerminalEligible());
        assertEquals(1, listener.completions.size());
    }

    @Test
    void earlierAttemptReceiptBecomesNonselectableWhenRetryStarts() {
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                new Capture(), "receipt-stale");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt first = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        first.requestDispatched();
        ProviderResponseCapture.Receipt stale = first.respond(response("first"));
        ProviderResponseCapture.Attempt retry = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.TRANSIENT_RETRY,
                ProviderResponseCapture.RetryReason.TRANSIENT_ENGINE_FAILURE);
        retry.requestDispatched();
        retry.fail(ProviderResponseCapture.Failure.PROVIDER_EXCEPTION);

        assertThrows(IllegalArgumentException.class, () -> generation.complete(stale));
        assertFalse(stale.selected());
        assertFalse(stale.providerTerminalEligible());
    }

    @Test
    void abandonedResponseReceiptNeverBecomesEligible() {
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                new Capture(), "receipt-abandoned");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt receipt = attempt.respond(response("late"));

        generation.abandon(ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT);
        generation.complete(receipt);

        assertFalse(receipt.selected());
        assertFalse(receipt.providerTerminalEligible());
    }

    @Test
    void terminalProviderFactCannotBeReplacedByALaterAbandonmentClaim() {
        Capture listener = new Capture();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                listener, "receipt-terminal-winner");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        ProviderResponseCapture.Receipt receipt = attempt.respond(response("winner"));
        generation.complete(receipt);

        assertFalse(generation.tryAbandon(
                ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT));
        assertEquals(1, listener.completions.size());
        assertTrue(receipt.providerTerminalEligible());
    }

    @Test
    void responseWithWrongSourceIsRejectedBeforeAReceiptExists() {
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                new Capture(), "receipt-wrong-source");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();

        assertThrows(IllegalArgumentException.class, () -> attempt.respond(
                new ProviderResponseCapture.Response(
                        "background bytes",
                        0,
                        ProviderResponseCapture.Source.BACKGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false)));
        assertFalse(generation.terminal());
    }

    @Test
    void selectedReceiptCarriesBoundedStableNativeProposalHashes() {
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                new Capture(), "receipt-proposal");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file"),
                        ToolChoiceMode.AUTO,
                        ""));
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched(new ProviderTransportHandoff.DispatchEvidence(
                "sha256:" + "a".repeat(64), List.of("talos.read_file")));
        Map<String, Object> firstOrder = new LinkedHashMap<>();
        firstOrder.put("z", List.of(Map.of("b", 2, "a", 1)));
        firstOrder.put("a", true);
        ProviderResponseCapture.Receipt receipt = attempt.respond(new ProviderResponseCapture.Response(
                "",
                List.of(new ChatMessage.NativeToolCall(
                        "call-1", "talos.read_file", firstOrder)),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                false));
        generation.complete(receipt);

        Map<String, Object> secondOrder = new LinkedHashMap<>();
        secondOrder.put("a", true);
        secondOrder.put("z", List.of(Map.of("a", 1, "b", 2)));
        ProviderResponseCapture.ToolProposalReceipt proposal =
                receipt.selectedToolProposal(1).orElseThrow();

        assertEquals(
                ProviderResponseCapture.canonicalArgumentsHash(secondOrder),
                proposal.argumentsHash());
        assertEquals(
                ProviderResponseCapture.toolNameHash("talos.read_file"),
                proposal.toolNameHash());
    }

    @Test
    void toolAuthorityContractRejectsAmbiguousOrBroadenedSelections() {
        assertThrows(IllegalArgumentException.class, () ->
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of(), ToolChoiceMode.AUTO, ""));
        assertThrows(IllegalArgumentException.class, () ->
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of(" "), ToolChoiceMode.AUTO, ""));
        assertThrows(IllegalArgumentException.class, () ->
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file "), ToolChoiceMode.AUTO, ""));
        assertThrows(IllegalArgumentException.class, () ->
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file", "talos.read_file"),
                        ToolChoiceMode.AUTO, ""));
        assertThrows(IllegalArgumentException.class, () ->
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file"), ToolChoiceMode.NONE, ""));
        assertThrows(IllegalArgumentException.class, () ->
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file"), ToolChoiceMode.NAMED,
                        "talos.write_file"));
        assertThrows(IllegalArgumentException.class, () ->
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file"), ToolChoiceMode.REQUIRED,
                        "talos.read_file"));
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderResponseCapture.ToolAuthorityContract(
                        ProviderResponseCapture.ToolAuthorityMode.NONE,
                        List.of("talos.read_file"), ToolChoiceMode.AUTO, "", true));
    }

    @Test
    void dispatchBindingRequiresTheExactOrderedSelectedSurface() {
        ProviderResponseCapture.ToolAuthorityContract nativeContract =
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE,
                        List.of("talos.read_file", "talos.write_file"),
                        ToolChoiceMode.AUTO, "");
        String bodyHash = "sha256:" + "b".repeat(64);

        assertEquals(ProviderResponseCapture.ToolAuthorityBinding.DISPATCH_UNBOUND,
                nativeContract.bindDispatch(
                        ProviderTransportHandoff.DispatchEvidence.unbound()));
        assertEquals(ProviderResponseCapture.ToolAuthorityBinding.DISPATCH_SURFACE_MISMATCH,
                nativeContract.bindDispatch(new ProviderTransportHandoff.DispatchEvidence(
                        bodyHash, List.of("talos.write_file", "talos.read_file"))));
        assertEquals(ProviderResponseCapture.ToolAuthorityBinding.DISPATCH_SURFACE_MISMATCH,
                nativeContract.bindDispatch(new ProviderTransportHandoff.DispatchEvidence(
                        bodyHash, List.of("talos.read_file"))));
        assertEquals(ProviderResponseCapture.ToolAuthorityBinding.BOUND,
                nativeContract.bindDispatch(new ProviderTransportHandoff.DispatchEvidence(
                        bodyHash, List.of("talos.read_file", "talos.write_file"))));

        ProviderResponseCapture.ToolAuthorityContract textContract =
                ProviderResponseCapture.ToolAuthorityContract.bound(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON,
                        List.of("talos.read_file"), ToolChoiceMode.AUTO, "");
        assertEquals(ProviderResponseCapture.ToolAuthorityBinding.BOUND,
                textContract.bindDispatch(new ProviderTransportHandoff.DispatchEvidence(
                        bodyHash, List.of())));
        assertEquals(ProviderResponseCapture.ToolAuthorityBinding.DISPATCH_SURFACE_MISMATCH,
                textContract.bindDispatch(new ProviderTransportHandoff.DispatchEvidence(
                        bodyHash, List.of("talos.read_file"))));
    }

    @Test
    void legacyModeOnlyGenerationRemainsUnboundAndCannotAuthorizeAProposal() {
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                new Capture(), "legacy-mode-only-authority");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched(new ProviderTransportHandoff.DispatchEvidence(
                "sha256:" + "c".repeat(64), List.of("talos.read_file")));
        ProviderResponseCapture.Receipt receipt = attempt.respond(
                new ProviderResponseCapture.Response(
                        "",
                        List.of(new ChatMessage.NativeToolCall(
                                "call-1", "talos.read_file", Map.of("path", "README.md"))),
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false,
                        false,
                        false));
        generation.complete(receipt);

        assertEquals(ProviderResponseCapture.ToolAuthorityBinding.SURFACE_UNBOUND,
                receipt.toolAuthorityBinding());
        assertFalse(receipt.providerToolCallEligible());
        assertFalse(receipt.authorizesExactToolName("talos.read_file"));
        assertTrue(receipt.selectedToolProposal(1).isEmpty());
    }

    @Test
    void terminalAttemptRejectsTransportHandoffBeforeDispatchEvent() {
        Capture listener = new Capture();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                listener, "terminal-handoff");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);

        assertTrue(generation.tryAbandon(
                ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT));

        assertFalse(attempt.tryRequestDispatched());
        assertThrows(ProviderTransportHandoff.DispatchRejected.class,
                attempt::requestDispatched);

        ProviderResponseCapture.Attempt lateRetry = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.TRANSIENT_RETRY,
                ProviderResponseCapture.RetryReason.TRANSIENT_ENGINE_FAILURE);
        assertFalse(lateRetry.tryRequestDispatched());
        assertThrows(ProviderTransportHandoff.DispatchRejected.class,
                lateRetry::requestDispatched);
        assertThrows(IllegalStateException.class, lateRetry::transportUnobserved);
        assertEquals(0, listener.dispatches.size());
    }

    @Test
    void typedDispatchEvidenceIsBoundToTheExactAttemptWhileLegacyDispatchIsUnbound() {
        Capture listener = new Capture();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                listener, "typed-dispatch-evidence");
        ProviderResponseCapture.Generation firstGeneration = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt firstAttempt = firstGeneration.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportHandoff.DispatchEvidence exact =
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                        "{\"messages\":[]}",
                        List.of(new ToolSpec("talos.read_file", "Read", "{}")));
        firstAttempt.requestDispatched(exact);

        ProviderResponseCapture.Generation secondGeneration = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt secondAttempt = secondGeneration.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        secondAttempt.requestDispatched();

        assertEquals(2, listener.dispatches.size());
        ProviderResponseCapture.RequestDispatch first = listener.dispatches.get(0);
        ProviderResponseCapture.RequestDispatch second = listener.dispatches.get(1);
        assertEquals(firstGeneration.generationId() + "-attempt-1",
                first.attempt().attemptId());
        assertEquals(firstGeneration.generationId(), first.attempt().generationId());
        assertEquals(exact, first.evidence());
        assertEquals(secondGeneration.generationId() + "-attempt-1",
                second.attempt().attemptId());
        assertEquals(secondGeneration.generationId(), second.attempt().generationId());
        assertEquals(ProviderTransportHandoff.DispatchEvidence.unbound(), second.evidence());
    }

    @Test
    void detachedAttemptKeepsNoCaptureTransportPathAdmitted() {
        ProviderResponseCapture.clear();
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);

        assertTrue(attempt.tryRequestDispatched());
        attempt.requestDispatched();
    }

    private static ProviderResponseCapture.Response response(String text) {
        return new ProviderResponseCapture.Response(
                text,
                0,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false);
    }

    private static final class Capture implements ProviderResponseCapture.Listener {
        private final List<ProviderResponseCapture.GenerationCompletion> completions =
                new ArrayList<>();
        private final List<ProviderResponseCapture.RequestDispatch> dispatches =
                new ArrayList<>();

        @Override
        public void requestDispatched(ProviderResponseCapture.RequestDispatch event) {
            dispatches.add(event);
        }

        @Override
        public void generationCompleted(ProviderResponseCapture.GenerationCompletion completion) {
            completions.add(completion);
        }
    }
}
