package dev.talos.spi.types;

import dev.talos.spi.EngineException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderProcessTransportBindingTest {

    @AfterEach
    void clearCapture() {
        ProviderResponseCapture.clear();
    }

    @Test
    void exactReceiptBindsTheT1191OperationLifecycleResponseAndTerminal() throws Exception {
        Capture capture = new Capture();
        ProviderProcessLaunchReceipt processReceipt = receipt();
        ProviderTransportHandoff.DispatchEvidence evidence =
                ProviderTransportHandoff.DispatchEvidence
                        .fromProviderBody("{\"messages\":[]}", List.of())
                        .withProcessReceipt(processReceipt);
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(capture, "scope");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);

        ProviderTransportAttempt transport = attempt.admitTransport(evidence);
        transport.invokeSend(() -> null);
        transport.responseHeadersObserved();
        transport.responseBodyOpened(() -> { });
        transport.providerTerminalObserved();
        transport.responseBodyCompleted();
        ProviderResponseCapture.Receipt response = attempt.respond(
                new ProviderResponseCapture.Response(
                        "done", 0,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        false, false));
        generation.complete(response);

        String operation = capture.dispatch.attempt().attemptId() + "-transport-1";
        assertEquals(operation, capture.dispatch.transportOperationId());
        assertEquals(processReceipt.receiptId(), capture.dispatch.processReceiptId());
        assertEquals(processReceipt, capture.dispatch.evidence().processReceipt().orElseThrow());
        assertFalse(capture.lifecycle.isEmpty());
        assertTrue(capture.lifecycle.stream().allMatch(event ->
                operation.equals(event.transportOperationId())
                        && processReceipt.receiptId().equals(event.processReceiptId())
                        && event.processBinding()
                                == ProviderTransportAttempt.ProcessBinding.MANAGED_QUALIFICATION_EXACT));
        assertEquals(processReceipt.receiptId(), response.processReceiptId());
        assertEquals(processReceipt.receiptId(),
                capture.completion.selectedResponse().processReceiptId());
    }

    @Test
    void handoffWrapperAttachesARevalidatedReceiptWithoutChangingConnectOnly() {
        ProviderProcessLaunchReceipt receipt = receipt();
        RecordingHandoff target = new RecordingHandoff();
        ProviderTransportHandoff bound = ProviderTransportHandoff.bindProcessReceipt(
                target, () -> receipt);
        ProviderTransportHandoff.DispatchEvidence body =
                ProviderTransportHandoff.DispatchEvidence
                        .fromProviderBody("{}", List.of());

        bound.admitTransport(body);

        assertEquals(receipt, target.evidence.processReceipt().orElseThrow());
        assertTrue(ProviderTransportHandoff.DispatchEvidence.unbound()
                .processReceipt().isEmpty());
    }

    @Test
    void handoffWrapperObtainsTheReceiptAtAdmissionRatherThanAtWrapperCreation() {
        RecordingHandoff target = new RecordingHandoff();
        AtomicReference<ProviderProcessLaunchReceipt> active = new AtomicReference<>();
        ProviderTransportHandoff bound = ProviderTransportHandoff.bindProcessReceipt(
                target,
                () -> {
                    ProviderProcessLaunchReceipt receipt = active.get();
                    if (receipt == null) throw new IllegalStateException("stale process");
                    return receipt;
                });
        ProviderTransportHandoff.DispatchEvidence body =
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody("{}", List.of());

        assertThrows(IllegalStateException.class, () -> bound.admitTransport(body));
        active.set(receipt());
        bound.admitTransport(body);

        assertEquals(active.get(), target.evidence.processReceipt().orElseThrow());
    }

    @Test
    void failureAndAbandonmentEventsRetainTheExactOperationAndProcessReceipt() {
        assertTerminalPropagation(false);
        ProviderResponseCapture.clear();
        assertTerminalPropagation(true);
    }

    @ParameterizedTest
    @EnumSource(InvalidationBoundary.class)
    void changedProcessReceiptInvalidatesEveryNetworkAndTerminalBoundary(
            InvalidationBoundary boundary) throws Exception {
        Capture capture = new Capture();
        AtomicReference<ProviderProcessLaunchReceipt> active =
                new AtomicReference<>(receipt());
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(capture, "scope");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportHandoff delegate = handoff(attempt);
        ProviderTransportHandoff bound = ProviderTransportHandoff.bindProcessReceipt(
                delegate, active::get);
        ProviderTransportAttempt transport = bound.admitTransport(
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody("{}", List.of()));
        boolean[] sendInvoked = {false};

        if (boundary != InvalidationBoundary.SEND) {
            transport.invokeSend(() -> {
                sendInvoked[0] = true;
                return null;
            });
        }
        if (boundary.ordinal() > InvalidationBoundary.HEADERS.ordinal()) {
            transport.responseHeadersObserved();
        }
        if (boundary.ordinal() > InvalidationBoundary.BODY_OPEN.ordinal()) {
            transport.responseBodyOpened(() -> { });
        }
        if (boundary.ordinal() > InvalidationBoundary.TERMINAL.ordinal()) {
            transport.providerTerminalObserved();
        }

        active.set(receipt(8102L));
        assertThrows(IllegalStateException.class, () -> boundary.invoke(transport, sendInvoked));

        assertEquals(boundary != InvalidationBoundary.SEND, sendInvoked[0]);
        assertTrue(capture.lifecycle.stream().anyMatch(event ->
                event.transition() == ProviderTransportAttempt.Transition.PROCESS_BINDING_INVALIDATED
                        && event.processBinding()
                                == ProviderTransportAttempt.ProcessBinding
                                        .MANAGED_QUALIFICATION_INVALIDATED));
        if (boundary == InvalidationBoundary.SEND) {
            assertTrue(capture.lifecycle.stream().noneMatch(event ->
                    event.transition() == ProviderTransportAttempt.Transition.SEND_API_ENTERED));
            ProviderResponseCapture.TransportLifecycleEvent invalidation = capture.lifecycle.stream()
                    .filter(event -> event.transition()
                            == ProviderTransportAttempt.Transition.PROCESS_BINDING_INVALIDATED)
                    .findFirst().orElseThrow();
            assertEquals(ProviderTransportAttempt.WireDisposition.SEND_API_NOT_ENTERED,
                    invalidation.wireDisposition());
        }
        attempt.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
        generation.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
    }

    @Test
    void restartAfterReceiptValidationCannotYieldAnAcceptedOldEpochResponse()
            throws Exception {
        Capture capture = new Capture();
        AtomicReference<ProviderProcessLaunchReceipt> active =
                new AtomicReference<>(receipt());
        CountDownLatch selectedEpochEnteredSendBoundary = new CountDownLatch(1);
        CountDownLatch replacementBound = new CountDownLatch(1);
        AtomicBoolean physicalSendEntered = new AtomicBoolean();
        AtomicReference<Throwable> requestFailure = new AtomicReference<>();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                capture, "restart-send-interleaving");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportHandoff bound = ProviderTransportHandoff.bindProcessReceipt(
                gatedBeforeSendHandoff(
                        attempt,
                        selectedEpochEnteredSendBoundary,
                        replacementBound),
                active::get);
        ProviderTransportAttempt transport = bound.admitTransport(
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                        "{}", List.of()));

        Thread request = new Thread(() -> {
            try {
                transport.invokeSend(() -> {
                    physicalSendEntered.set(true);
                    transport.responseHeadersObserved();
                    return null;
                });
            } catch (Throwable failure) {
                requestFailure.set(failure);
            }
        }, "qualification-old-http-epoch");
        request.start();
        try {
            assertTrue(selectedEpochEnteredSendBoundary.await(2, TimeUnit.SECONDS),
                    "the selected process epoch did not reach its send boundary");
            active.set(receipt(8102L));
        } finally {
            replacementBound.countDown();
        }
        request.join(2_000L);

        assertFalse(request.isAlive(), "the interleaved request did not terminate");
        assertTrue(physicalSendEntered.get(),
                "the contract permits send entry but must reject stale response evidence");
        assertInstanceOf(
                ProviderTransportHandoff.ProcessBindingInvalidated.class,
                requestFailure.get());
        assertNull(capture.completion,
                "a replaced HTTP epoch cannot yield an accepted generation response");
        assertTrue(capture.lifecycle.stream().noneMatch(event ->
                event.transition()
                        == ProviderTransportAttempt.Transition.RESPONSE_HEADERS_OBSERVED),
                "headers from a replaced HTTP epoch cannot enter accepted evidence");
        assertTrue(capture.lifecycle.stream().anyMatch(event ->
                event.transition()
                        == ProviderTransportAttempt.Transition.PROCESS_BINDING_INVALIDATED));
        ProviderResponseCapture.TransportLifecycleEvent settlement = capture.lifecycle.stream()
                .filter(event -> event.transition()
                        == ProviderTransportAttempt.Transition.SETTLED)
                .findFirst().orElseThrow();
        assertEquals(
                ProviderTransportAttempt.ProcessBinding.MANAGED_QUALIFICATION_INVALIDATED,
                settlement.processBinding());
        assertEquals(
                ProviderTransportAttempt.Settlement.FAILED_BEFORE_HEADERS,
                settlement.settlement());
        attempt.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
        generation.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
    }

    @Test
    void invalidationAfterSettlementCannotRewriteACompletedOperation() throws Exception {
        Capture capture = new Capture();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(capture, "scope");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportAttempt transport = attempt.admitTransport(
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody("{}", List.of())
                        .withProcessReceipt(receipt()));
        transport.invokeSend(() -> null);
        transport.responseHeadersObserved();
        transport.responseBodyOpened(() -> { });
        transport.providerTerminalObserved();
        transport.responseBodyCompleted();
        int settledEvents = capture.lifecycle.size();

        transport.processBindingInvalidated();

        assertEquals(settledEvents, capture.lifecycle.size());
        assertTrue(capture.lifecycle.stream().noneMatch(event ->
                event.transition() == ProviderTransportAttempt.Transition.PROCESS_BINDING_INVALIDATED));
    }

    @Test
    void rejectedBodyOpenClosesTheUnownedResponseBody() throws Exception {
        AtomicReference<ProviderProcessLaunchReceipt> active =
                new AtomicReference<>(receipt());
        AtomicBoolean closed = new AtomicBoolean();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                new Capture(), "body-close-scope");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportAttempt transport = ProviderTransportHandoff.bindProcessReceipt(
                handoff(attempt), active::get).admitTransport(
                        ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                                "{}", List.of()));
        transport.invokeSend(() -> null);
        transport.responseHeadersObserved();
        active.set(receipt(8102L));

        assertThrows(IllegalStateException.class,
                () -> transport.responseBodyOpened(() -> closed.set(true)));

        assertTrue(closed.get());
        attempt.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
        generation.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
    }

    @Test
    void staleReceiptFailureAfterAdmissionIsRuntimeOwnedAndCannotEnterSend()
            throws Exception {
        Capture capture = new Capture();
        ProviderProcessLaunchReceipt admitted = receipt();
        AtomicReference<ProviderProcessLaunchReceipt> active =
                new AtomicReference<>(admitted);
        AtomicBoolean receiptUnavailable = new AtomicBoolean();
        AtomicBoolean sendEntered = new AtomicBoolean();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                capture, "stale-receipt-scope");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportAttempt transport = ProviderTransportHandoff.bindProcessReceipt(
                handoff(attempt),
                () -> {
                    if (receiptUnavailable.get()) {
                        throw new EngineException.ConnectionFailed(
                                "stale qualification process", null);
                    }
                    return active.get();
                }).admitTransport(
                        ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                                "{}", List.of()));
        receiptUnavailable.set(true);

        ProviderTransportHandoff.ProcessBindingInvalidated failure = assertThrows(
                ProviderTransportHandoff.ProcessBindingInvalidated.class,
                () -> transport.invokeSend(() -> {
                    sendEntered.set(true);
                    return null;
                }));

        assertTrue(failure.getCause() instanceof EngineException.ConnectionFailed);
        assertFalse(sendEntered.get());
        assertTrue(capture.lifecycle.stream().anyMatch(event ->
                event.transition()
                        == ProviderTransportAttempt.Transition.PROCESS_BINDING_INVALIDATED
                        && event.wireDisposition()
                                == ProviderTransportAttempt.WireDisposition.SEND_API_NOT_ENTERED));
        attempt.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
        generation.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
        assertTrue(capture.lifecycle.stream().anyMatch(event ->
                event.transition() == ProviderTransportAttempt.Transition.SETTLED
                        && event.failure() == ProviderTransportAttempt.Failure.RUNTIME
                        && event.settlement()
                                == ProviderTransportAttempt.Settlement.FAILED_BEFORE_HEADERS));
    }

    @ParameterizedTest
    @EnumSource(NonThrowingInvalidationBoundary.class)
    void receiptChangeDuringBodyFailureOrClosePreservesTheOriginalTerminalCause(
            NonThrowingInvalidationBoundary boundary) throws Exception {
        Capture capture = new Capture();
        AtomicReference<ProviderProcessLaunchReceipt> active =
                new AtomicReference<>(receipt());
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                capture, "body-terminal-race-" + boundary.name());
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportAttempt transport = ProviderTransportHandoff.bindProcessReceipt(
                handoff(attempt), active::get).admitTransport(
                        ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                                "{}", List.of()));
        transport.invokeSend(() -> null);
        transport.responseHeadersObserved();
        transport.responseBodyOpened(() -> { });
        RuntimeException original = new RuntimeException("original body failure");
        active.set(receipt(8102L));

        assertDoesNotThrow(() -> boundary.invoke(transport, original));

        assertTrue(capture.lifecycle.stream().anyMatch(event ->
                event.transition()
                        == ProviderTransportAttempt.Transition.PROCESS_BINDING_INVALIDATED));
        ProviderTransportAttempt.Settlement expectedSettlement = switch (boundary) {
            case FAILURE -> ProviderTransportAttempt.Settlement.FAILED_DURING_BODY;
            case CLOSE -> ProviderTransportAttempt.Settlement.CLOSED_BEFORE_PROVIDER_TERMINAL;
        };
        assertTrue(capture.lifecycle.stream().anyMatch(event ->
                event.transition() == ProviderTransportAttempt.Transition.SETTLED
                        && event.settlement() == expectedSettlement));
        attempt.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
        generation.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
    }

    @Test
    void inFlightCancellationRetainsItsCauseWhenTheProcessBindingIsInvalidated()
            throws Exception {
        Capture capture = new Capture();
        AtomicReference<ProviderProcessLaunchReceipt> active =
                new AtomicReference<>(receipt());
        CountDownLatch bodyCancellationInvoked = new CountDownLatch(1);
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                capture, "cancellation-process-invalidation");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportAttempt transport = ProviderTransportHandoff.bindProcessReceipt(
                handoff(attempt), active::get).admitTransport(
                        ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                                "{}", List.of()));
        transport.invokeSend(() -> null);
        transport.responseHeadersObserved();
        transport.responseBodyOpened(bodyCancellationInvoked::countDown);

        active.set(receipt(8102L));
        attempt.abandon(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);
        assertTrue(bodyCancellationInvoked.await(2, TimeUnit.SECONDS),
                "operator cancellation did not invoke the active body handle");
        transport.responseBodyClosed();
        generation.abandon(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);

        ProviderResponseCapture.TransportLifecycleEvent settlement = capture.lifecycle.stream()
                .filter(event -> event.transition()
                        == ProviderTransportAttempt.Transition.SETTLED)
                .findFirst().orElseThrow();
        assertTrue(capture.lifecycle.stream().anyMatch(event ->
                event.transition()
                        == ProviderTransportAttempt.Transition.PROCESS_BINDING_INVALIDATED));
        assertEquals(
                ProviderTransportAttempt.ProcessBinding.MANAGED_QUALIFICATION_INVALIDATED,
                settlement.processBinding());
        assertEquals(
                ProviderTransportAttempt.Settlement.CANCELLED_IN_FLIGHT,
                settlement.settlement());
        assertEquals(
                ProviderTransportAttempt.CancellationReason.OPERATOR_CANCELLED,
                settlement.cancellationReason());
        assertEquals(
                ProviderTransportAttempt.CancellationState.BODY_CLOSE_ACKNOWLEDGED,
                settlement.cancellationState());
        assertTrue(settlement.quarantineRequired(),
                "an invalidated process response cannot remain score-eligible");
        assertEquals(
                ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED,
                capture.attemptAbandonment.abandonment());
        assertEquals(
                ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED,
                capture.generationAbandonment.abandonment());
    }

    private static void assertTerminalPropagation(boolean abandon) {
        Capture capture = new Capture();
        ProviderProcessLaunchReceipt processReceipt = receipt();
        ProviderTransportHandoff.DispatchEvidence evidence =
                ProviderTransportHandoff.DispatchEvidence
                        .fromProviderBody("{}", List.of())
                        .withProcessReceipt(processReceipt);
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                capture, abandon ? "abandon-scope" : "failure-scope");
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.admitTransport(evidence);

        if (abandon) {
            attempt.abandon(ProviderResponseCapture.Abandonment.SCOPE_CLOSED);
            generation.abandon(ProviderResponseCapture.Abandonment.SCOPE_CLOSED);
            assertEquals(processReceipt.receiptId(),
                    capture.attemptAbandonment.attempt().processReceiptId());
            assertEquals(processReceipt.receiptId(),
                    capture.generationAbandonment.generation().processReceiptId());
        } else {
            attempt.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
            generation.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
            assertEquals(processReceipt.receiptId(),
                    capture.attemptFailure.attempt().processReceiptId());
            assertEquals(processReceipt.receiptId(),
                    capture.generationFailure.generation().processReceiptId());
        }
        String operation = capture.dispatch.transportOperationId();
        assertTrue(capture.lifecycle.stream().allMatch(event ->
                operation.equals(event.transportOperationId())
                        && processReceipt.receiptId().equals(event.processReceiptId())));
    }

    private static ProviderTransportHandoff handoff(ProviderResponseCapture.Attempt attempt) {
        return new ProviderTransportHandoff() {
            @Override public void requestDispatched() { attempt.requestDispatched(); }

            @Override
            public void requestDispatched(DispatchEvidence evidence) {
                attempt.requestDispatched(evidence);
            }

            @Override
            public ProviderTransportAttempt admitTransport(DispatchEvidence evidence) {
                return attempt.admitTransport(evidence);
            }

            @Override public void transportUnobserved() { attempt.transportUnobserved(); }
        };
    }

    private static ProviderTransportHandoff gatedBeforeSendHandoff(
            ProviderResponseCapture.Attempt attempt,
            CountDownLatch sendBoundary,
            CountDownLatch release) {
        ProviderTransportHandoff exact = handoff(attempt);
        return new ProviderTransportHandoff() {
            @Override public void requestDispatched() { exact.requestDispatched(); }

            @Override
            public void requestDispatched(DispatchEvidence evidence) {
                exact.requestDispatched(evidence);
            }

            @Override
            public ProviderTransportAttempt admitTransport(DispatchEvidence evidence) {
                ProviderTransportAttempt target = exact.admitTransport(evidence);
                return new ProviderTransportAttempt() {
                    @Override
                    public <T> T invokeSend(SendOperation<T> operation) throws Exception {
                        sendBoundary.countDown();
                        if (!release.await(2, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "replacement process did not bind before send release");
                        }
                        return target.invokeSend(operation);
                    }

                    @Override
                    public void responseHeadersObserved() {
                        target.responseHeadersObserved();
                    }

                    @Override
                    public void responseBodyOpened(AutoCloseable responseBody) {
                        target.responseBodyOpened(responseBody);
                    }

                    @Override
                    public void providerTerminalObserved() {
                        target.providerTerminalObserved();
                    }

                    @Override
                    public void responseBodyCompleted() {
                        target.responseBodyCompleted();
                    }

                    @Override
                    public void responseBodyFailed(Throwable failure) {
                        target.responseBodyFailed(failure);
                    }

                    @Override
                    public void responseBodyClosed() {
                        target.responseBodyClosed();
                    }

                    @Override
                    public void processBindingInvalidated() {
                        target.processBindingInvalidated();
                    }
                };
            }

            @Override public void transportUnobserved() { exact.transportUnobserved(); }
        };
    }

    private enum InvalidationBoundary {
        SEND {
            @Override void invoke(ProviderTransportAttempt transport, boolean[] sent)
                    throws Exception {
                transport.invokeSend(() -> {
                    sent[0] = true;
                    return null;
                });
            }
        },
        HEADERS {
            @Override void invoke(ProviderTransportAttempt transport, boolean[] sent) {
                transport.responseHeadersObserved();
            }
        },
        BODY_OPEN {
            @Override void invoke(ProviderTransportAttempt transport, boolean[] sent) {
                transport.responseBodyOpened(() -> { });
            }
        },
        TERMINAL {
            @Override void invoke(ProviderTransportAttempt transport, boolean[] sent) {
                transport.providerTerminalObserved();
            }
        },
        COMPLETION {
            @Override void invoke(ProviderTransportAttempt transport, boolean[] sent) {
                transport.responseBodyCompleted();
            }
        };

        abstract void invoke(ProviderTransportAttempt transport, boolean[] sent) throws Exception;
    }

    private enum NonThrowingInvalidationBoundary {
        FAILURE {
            @Override void invoke(
                    ProviderTransportAttempt transport,
                    RuntimeException original) {
                transport.responseBodyFailed(original);
            }
        },
        CLOSE {
            @Override void invoke(
                    ProviderTransportAttempt transport,
                    RuntimeException original) {
                transport.responseBodyClosed();
            }
        };

        abstract void invoke(
                ProviderTransportAttempt transport,
                RuntimeException original);
    }

    private static ProviderProcessLaunchReceipt receipt() {
        return receipt(8101L);
    }

    private static ProviderProcessLaunchReceipt receipt(long processId) {
        return new ProviderProcessLaunchReceipt(
                processId,
                1_721_600_000_000L,
                1_721_600_000_100L,
                1_721_600_000_200L,
                "0123456789abcdef0123456789abcdef",
                "a".repeat(64), "b".repeat(64), "c".repeat(64),
                "e".repeat(64), ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION,
                "f".repeat(64),
                ProviderProcessLaunchReceipt.EnvironmentContract.QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                "127.0.0.1", 8080, true, "1".repeat(64), "qualification-model",
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                "2".repeat(64),
                ProviderProcessLaunchReceipt.EndpointOwnershipBoundary
                        .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
                ProviderProcessLaunchReceipt.StableEndpointAssumption
                        .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
                ProviderProcessLaunchReceipt.ContentVerificationBoundary
                        .IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption
                        .STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
    }

    private static final class Capture implements ProviderResponseCapture.Listener {
        private ProviderResponseCapture.RequestDispatch dispatch;
        private final List<ProviderResponseCapture.TransportLifecycleEvent> lifecycle =
                new ArrayList<>();
        private ProviderResponseCapture.GenerationCompletion completion;
        private ProviderResponseCapture.AttemptFailure attemptFailure;
        private ProviderResponseCapture.GenerationFailure generationFailure;
        private ProviderResponseCapture.AttemptAbandonment attemptAbandonment;
        private ProviderResponseCapture.GenerationAbandonment generationAbandonment;

        @Override
        public void requestDispatched(ProviderResponseCapture.RequestDispatch event) {
            dispatch = event;
        }

        @Override
        public void transportLifecycle(ProviderResponseCapture.TransportLifecycleEvent event) {
            lifecycle.add(event);
        }

        @Override
        public void generationCompleted(ProviderResponseCapture.GenerationCompletion event) {
            completion = event;
        }

        @Override
        public void attemptFailed(ProviderResponseCapture.AttemptFailure event) {
            attemptFailure = event;
        }

        @Override
        public void generationFailed(ProviderResponseCapture.GenerationFailure event) {
            generationFailure = event;
        }

        @Override
        public void attemptAbandoned(ProviderResponseCapture.AttemptAbandonment event) {
            attemptAbandonment = event;
        }

        @Override
        public void generationAbandoned(ProviderResponseCapture.GenerationAbandonment event) {
            generationAbandonment = event;
        }
    }

    private static final class RecordingHandoff implements ProviderTransportHandoff {
        private ProviderTransportHandoff.DispatchEvidence evidence;

        @Override public void requestDispatched() { }

        @Override
        public ProviderTransportAttempt admitTransport(DispatchEvidence evidence) {
            this.evidence = evidence;
            return ProviderTransportAttempt.unobservedAttempt();
        }
    }
}
