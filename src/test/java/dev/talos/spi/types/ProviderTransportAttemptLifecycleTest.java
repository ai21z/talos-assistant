package dev.talos.spi.types;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderTransportAttemptLifecycleTest {

    @AfterEach
    void clearCapture() {
        ProviderResponseCapture.clear();
    }

    @Test
    void cancellationWinningBeforeSendPreventsTheSendInvocation() {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "pre-send-cancel");
        AtomicInteger sends = new AtomicInteger();

        fixture.generation.abandon(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);

        assertThrows(ProviderTransportAttempt.CancelledBeforeSend.class,
                () -> fixture.transport.invokeSend(() -> sends.incrementAndGet()));
        assertEquals(0, sends.get());
        assertEquals(List.of(
                        ProviderTransportAttempt.Transition.HANDOFF_ADMITTED,
                        ProviderTransportAttempt.Transition.CANCELLATION_REQUESTED,
                        ProviderTransportAttempt.Transition.SETTLED),
                capture.transitions());
        ProviderResponseCapture.TransportLifecycleEvent settled = capture.settlement();
        assertEquals(ProviderTransportAttempt.Settlement.CANCELLED_BEFORE_SEND,
                settled.settlement());
        assertEquals(ProviderTransportAttempt.CancellationState.PRE_SEND_PREVENTED,
                settled.cancellationState());
        assertEquals(ProviderTransportAttempt.WireDisposition.SEND_API_NOT_ENTERED,
                settled.wireDisposition());
        assertFalse(settled.quarantineRequired());
        assertTrue(settled.lateAfterGenerationTerminal());
    }

    @Test
    void sendWinningFirstRecordsOneLateInFlightCancellationSettlement() throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "in-flight-cancel");
        CountDownLatch sendEntered = new CountDownLatch(1);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                fixture.transport.invokeSend(() -> {
                    sendEntered.countDown();
                    Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                    return null;
                });
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "provider-transport-test");
        worker.start();
        assertTrue(sendEntered.await(5, TimeUnit.SECONDS));

        fixture.generation.abandon(ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT);
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(worker.isAlive());
        assertTrue(workerFailure.get() instanceof InterruptedException);
        assertTrue(fixture.generation.awaitTransportSettlement(0));
        assertEquals(1, capture.settlementCount());
        ProviderResponseCapture.TransportLifecycleEvent settled = capture.settlement();
        assertEquals(ProviderTransportAttempt.Settlement.CANCELLED_IN_FLIGHT,
                settled.settlement());
        assertEquals(ProviderTransportAttempt.CancellationState.SEND_ABORT_ACKNOWLEDGED,
                settled.cancellationState());
        assertEquals(ProviderTransportAttempt.WireDisposition.UNKNOWN,
                settled.wireDisposition());
        assertTrue(settled.quarantineRequired());
        assertTrue(settled.lateAfterGenerationTerminal());
    }

    @Test
    void exactProviderTerminalAndBodyCompletionBindTheSelectedReceipt() throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "provider-terminal");
        fixture.transport.invokeSend(() -> null);
        fixture.transport.responseHeadersObserved();
        fixture.transport.responseBodyOpened(() -> { });
        fixture.transport.providerTerminalObserved();
        fixture.transport.responseBodyCompleted();

        ProviderResponseCapture.Receipt receipt = fixture.attempt.respond(response(
                "answer", ProviderResponseCapture.Completion.PROVIDER_EOS));
        fixture.generation.complete(receipt);

        ProviderResponseCapture.TransportLifecycleEvent settled = capture.settlement();
        assertEquals(ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL,
                settled.settlement());
        assertFalse(settled.quarantineRequired());
        assertEquals(fixture.generation.generationId() + "-attempt-1-transport-1",
                receipt.transportOperationId());
        assertTrue(receipt.providerTerminalEligible());
        assertEquals(1, capture.settlementCount());
        assertThrows(IllegalStateException.class,
                () -> fixture.transport.invokeSend(() -> null));
        assertEquals(1, capture.settlementCount());
    }

    @Test
    void rawBodyEofWithoutProviderTerminalCannotBecomeProviderTerminalEvidence()
            throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "body-eof");
        fixture.transport.invokeSend(() -> null);
        fixture.transport.responseHeadersObserved();
        fixture.transport.responseBodyOpened(() -> { });
        fixture.transport.responseBodyCompleted();

        ProviderResponseCapture.Receipt receipt = fixture.attempt.respond(response(
                "partial", ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS));
        fixture.generation.complete(receipt);

        assertEquals(ProviderTransportAttempt.Settlement.COMPLETED_BODY_EOF,
                capture.settlement().settlement());
        assertFalse(capture.settlement().quarantineRequired());
        assertFalse(receipt.providerTerminalEligible());
    }

    @Test
    void failedOrEofTransportCannotMintProviderTerminalEligibility() throws Exception {
        Capture failedCapture = new Capture();
        Fixture failed = fixture(failedCapture, "failed-response-binding");
        failed.transport.invokeSend(() -> null);
        failed.transport.responseHeadersObserved();
        failed.transport.responseBodyOpened(() -> { });
        failed.transport.responseBodyFailed(new IOException("body failed"));

        assertThrows(IllegalStateException.class, () -> failed.attempt.respond(response(
                "answer", ProviderResponseCapture.Completion.PROVIDER_EOS)));
        assertThrows(IllegalStateException.class, () -> failed.scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE));

        ProviderResponseCapture.clear();
        Capture eofCapture = new Capture();
        Fixture eof = fixture(eofCapture, "eof-response-binding");
        eof.transport.invokeSend(() -> null);
        eof.transport.responseHeadersObserved();
        eof.transport.responseBodyOpened(() -> { });
        eof.transport.responseBodyCompleted();

        assertThrows(IllegalStateException.class, () -> eof.attempt.respond(response(
                "answer", ProviderResponseCapture.Completion.PROVIDER_EOS)));
        eof.attempt.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
        eof.generation.fail(ProviderResponseCapture.Failure.RUNTIME_EXCEPTION);
        ProviderResponseCapture.Generation successor = eof.scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        assertTrue(successor.attached());
    }

    @Test
    void aLiveOrQuarantinedTransportBlocksAProviderSuccessor() throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "successor-barrier");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean workerDone = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                fixture.transport.invokeSend(() -> {
                    entered.countDown();
                    while (true) {
                        try {
                            release.await();
                            return null;
                        } catch (InterruptedException ignored) {
                            // Deliberately model an uncooperative transport.
                        }
                    }
                });
            } catch (Exception ignored) {
                // The test inspects lifecycle evidence, not worker prose.
            } finally {
                workerDone.set(true);
            }
        }, "provider-transport-uncooperative-test");
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        fixture.generation.abandon(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);
        assertThrows(IllegalStateException.class, () -> fixture.scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE));
        assertFalse(fixture.generation.awaitOrQuarantineTransport(0));
        assertEquals(ProviderTransportAttempt.Settlement.QUARANTINE_REQUIRED,
                capture.settlement().settlement());
        assertThrows(IllegalStateException.class, () -> fixture.scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE));

        release.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(workerDone.get());
        assertEquals(1, capture.settlementCount());
    }

    @Test
    void preopenedGenerationCannotBypassTheTransportSuccessorBarrier() {
        Capture capture = new Capture();
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(
                capture, "preopened-successor");
        ProviderResponseCapture.Generation first = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Generation second = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt firstAttempt = first.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderResponseCapture.Attempt secondAttempt = second.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);

        firstAttempt.admitTransport(ProviderTransportHandoff.DispatchEvidence.unbound());

        assertThrows(IllegalStateException.class, () -> secondAttempt.admitTransport(
                ProviderTransportHandoff.DispatchEvidence.unbound()));
        first.fail(ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH);
        second.abandon(ProviderResponseCapture.Abandonment.SCOPE_CLOSED);
    }

    @Test
    void cancellationNeverInvokesAnArbitraryBodyCloseWhileHoldingLifecycleMonitor()
            throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "nonblocking-body-close");
        fixture.transport.invokeSend(() -> null);
        fixture.transport.responseHeadersObserved();
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        CountDownLatch closeExited = new CountDownLatch(1);
        fixture.transport.responseBodyOpened(() -> {
            closeEntered.countDown();
            callbackFinished.await();
            closeExited.countDown();
        });

        Thread cancellation = new Thread(() -> fixture.generation.abandon(
                ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                "provider-transport-cancellation-test");
        cancellation.start();
        assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
        Thread callback = new Thread(() -> {
            try {
                fixture.transport.responseBodyFailed(new IOException("closed"));
            } finally {
                callbackFinished.countDown();
            }
        }, "provider-transport-body-callback-test");
        callback.start();
        cancellation.join(TimeUnit.SECONDS.toMillis(5));
        callback.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(cancellation.isAlive());
        assertFalse(callback.isAlive());
        assertTrue(closeExited.await(5, TimeUnit.SECONDS));
        assertEquals(1, capture.settlementCount());
        assertEquals(ProviderTransportAttempt.Settlement.CANCELLED_IN_FLIGHT,
                capture.settlement().settlement());
    }

    @Test
    void aReturnedBodyCloseDoesNotManufactureTransportSettlement() throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "close-return-is-not-settlement");
        fixture.transport.invokeSend(() -> null);
        fixture.transport.responseHeadersObserved();
        CountDownLatch closeReturned = new CountDownLatch(1);
        fixture.transport.responseBodyOpened(closeReturned::countDown);

        fixture.generation.abandon(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);
        assertTrue(closeReturned.await(5, TimeUnit.SECONDS));

        assertFalse(fixture.generation.awaitOrQuarantineTransport(0));
        assertEquals(ProviderTransportAttempt.Settlement.QUARANTINE_REQUIRED,
                capture.settlement().settlement());
        assertThrows(IllegalStateException.class, () -> fixture.scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE));
    }

    @Test
    void responseBodyCallbacksCannotRunInsideTheSendArbitration() {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "callback-inside-send");

        assertThrows(IllegalStateException.class, () -> fixture.transport.invokeSend(() -> {
            fixture.transport.responseHeadersObserved();
            fixture.transport.responseBodyOpened(() -> { });
            return null;
        }));

        ProviderResponseCapture.TransportLifecycleEvent settled = capture.settlement();
        assertEquals(ProviderTransportAttempt.Settlement.FAILED_DURING_BODY,
                settled.settlement());
        assertEquals(ProviderTransportAttempt.Failure.RUNTIME, settled.failure());
        assertTrue(settled.quarantineRequired());
    }

    @Test
    void bodyFailureAfterOpenedHeadersIsTypedAndQuarantined() throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "post-header-failure");

        fixture.transport.invokeSend(() -> null);
        fixture.transport.responseHeadersObserved();
        fixture.transport.responseBodyOpened(() -> { });
        fixture.transport.responseBodyFailed(new IOException("body collection failed"));

        ProviderResponseCapture.TransportLifecycleEvent settled = capture.settlement();
        assertEquals(ProviderTransportAttempt.Settlement.FAILED_DURING_BODY,
                settled.settlement());
        assertEquals(ProviderTransportAttempt.Failure.BODY_IO, settled.failure());
        assertEquals(ProviderTransportAttempt.WireDisposition.UNKNOWN,
                settled.wireDisposition());
        assertTrue(settled.quarantineRequired());
        assertThrows(IllegalStateException.class, () -> fixture.scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE));
    }

    @Test
    void bodyCallbacksCannotManufactureImpossiblePredecessorStates() throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "invalid-body-transitions");

        assertThrows(IllegalStateException.class,
                () -> fixture.transport.responseBodyFailed(new IOException("early")));
        assertThrows(IllegalStateException.class, fixture.transport::responseBodyClosed);
        fixture.transport.invokeSend(() -> null);
        fixture.transport.responseHeadersObserved();
        assertThrows(IllegalStateException.class, fixture.transport::responseBodyClosed);

        fixture.attempt.fail(ProviderResponseCapture.Failure.PROVIDER_MALFORMED_RESPONSE);
        fixture.generation.fail(ProviderResponseCapture.Failure.PROVIDER_MALFORMED_RESPONSE);
        assertEquals(1, capture.settlementCount());
    }

    @Test
    void closeFreezesAQuarantineReceiptForAlreadyTerminalLiveTransport()
            throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "close-live-terminal");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                fixture.transport.invokeSend(() -> {
                    entered.countDown();
                    while (true) {
                        try {
                            release.await();
                            return null;
                        } catch (InterruptedException ignored) {
                            // Deliberately refuse cooperative interruption.
                        }
                    }
                });
            } catch (Exception ignored) {
                // Quarantine, rather than exception prose, is authoritative.
            }
        }, "provider-transport-close-test");
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        fixture.generation.abandon(ProviderResponseCapture.Abandonment.SCOPE_CLOSED);

        fixture.scope.close();

        assertEquals(ProviderTransportAttempt.Settlement.QUARANTINE_REQUIRED,
                capture.settlement().settlement());
        assertTrue(capture.settlement().lateAfterGenerationTerminal());
        release.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(worker.isAlive());
    }

    @Test
    void quarantinedSendRejectsAndClosesItsLateResponseBody() throws Exception {
        Capture capture = new Capture();
        Fixture fixture = fixture(capture, "late-body-after-quarantine");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger bodyCloses = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                fixture.transport.invokeSend(() -> {
                    entered.countDown();
                    while (true) {
                        try {
                            release.await();
                            return null;
                        } catch (InterruptedException ignored) {
                            // Deliberately model an uncooperative synchronous send.
                        }
                    }
                });
                fixture.transport.responseHeadersObserved();
                fixture.transport.responseBodyOpened(bodyCloses::incrementAndGet);
            } catch (Throwable failure) {
                workerFailure.set(failure);
            }
        }, "provider-transport-late-body-test");
        worker.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        fixture.generation.abandon(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED);
        assertFalse(fixture.generation.awaitOrQuarantineTransport(0));
        release.countDown();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(worker.isAlive());
        assertTrue(workerFailure.get()
                instanceof ProviderTransportAttempt.ResponseBodyRejectedAfterSettlement);
        assertEquals(1, bodyCloses.get());
        assertEquals(1, capture.settlementCount());
        assertEquals(ProviderTransportAttempt.Settlement.QUARANTINE_REQUIRED,
                capture.settlement().settlement());
    }

    @Test
    void everyLateSettlementIsPublishedAfterBothAbandonmentRecords() throws Exception {
        OrderingCapture capture = new OrderingCapture();
        Fixture fixture = fixture(capture, "terminal-publication-order");
        fixture.transport.invokeSend(() -> null);
        fixture.transport.responseHeadersObserved();
        fixture.transport.responseBodyOpened(() -> { });

        Thread abandonment = new Thread(() -> fixture.generation.abandon(
                ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                "provider-transport-abandonment-order-test");
        abandonment.start();
        assertTrue(capture.attemptPublicationEntered.await(5, TimeUnit.SECONDS));

        Thread settlement = new Thread(fixture.transport::responseBodyClosed,
                "provider-transport-settlement-order-test");
        settlement.start();
        assertFalse(capture.transportSettled.await(100, TimeUnit.MILLISECONDS),
                "a late transport must wait for terminal evidence publication");
        capture.releaseAttemptPublication.countDown();
        abandonment.join(TimeUnit.SECONDS.toMillis(5));
        settlement.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(abandonment.isAlive());
        assertFalse(settlement.isAlive());
        assertTrue(capture.transportSettled.await(1, TimeUnit.SECONDS));
        int attemptIndex = capture.order.indexOf("ATTEMPT_ABANDONED");
        int generationIndex = capture.order.indexOf("GENERATION_ABANDONED");
        int settlementIndex = capture.order.indexOf("TRANSPORT_SETTLED_LATE");
        assertTrue(attemptIndex >= 0 && generationIndex > attemptIndex
                        && settlementIndex > generationIndex,
                capture.order.toString());
    }

    private static Fixture fixture(ProviderResponseCapture.Listener capture, String identity) {
        ProviderResponseCapture.Scope scope = ProviderResponseCapture.begin(capture, identity);
        ProviderResponseCapture.Generation generation = scope.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ProviderTransportAttempt transport = attempt.admitTransport(
                ProviderTransportHandoff.DispatchEvidence.fromProviderBody("{}", List.of()));
        return new Fixture(scope, generation, attempt, transport);
    }

    private static ProviderResponseCapture.Response response(
            String text,
            ProviderResponseCapture.Completion completion) {
        return new ProviderResponseCapture.Response(
                text,
                0,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                completion,
                false,
                false);
    }

    private record Fixture(
            ProviderResponseCapture.Scope scope,
            ProviderResponseCapture.Generation generation,
            ProviderResponseCapture.Attempt attempt,
            ProviderTransportAttempt transport) {}

    private static final class Capture implements ProviderResponseCapture.Listener {
        private final List<ProviderResponseCapture.TransportLifecycleEvent> events =
                new CopyOnWriteArrayList<>();

        @Override
        public void transportLifecycle(ProviderResponseCapture.TransportLifecycleEvent event) {
            events.add(event);
        }

        private List<ProviderTransportAttempt.Transition> transitions() {
            return events.stream().map(ProviderResponseCapture.TransportLifecycleEvent::transition)
                    .toList();
        }

        private int settlementCount() {
            return (int) events.stream()
                    .filter(event -> event.transition()
                            == ProviderTransportAttempt.Transition.SETTLED)
                    .count();
        }

        private ProviderResponseCapture.TransportLifecycleEvent settlement() {
            ProviderResponseCapture.TransportLifecycleEvent settled = events.stream()
                    .filter(event -> event.transition()
                            == ProviderTransportAttempt.Transition.SETTLED)
                    .findFirst()
                    .orElse(null);
            assertNotNull(settled);
            return settled;
        }
    }

    private static final class OrderingCapture implements ProviderResponseCapture.Listener {
        private final List<String> order = new CopyOnWriteArrayList<>();
        private final CountDownLatch attemptPublicationEntered = new CountDownLatch(1);
        private final CountDownLatch releaseAttemptPublication = new CountDownLatch(1);
        private final CountDownLatch transportSettled = new CountDownLatch(1);

        @Override
        public void attemptAbandoned(ProviderResponseCapture.AttemptAbandonment event) {
            order.add("ATTEMPT_ABANDONED");
            attemptPublicationEntered.countDown();
            try {
                releaseAttemptPublication.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }

        @Override
        public void generationAbandoned(ProviderResponseCapture.GenerationAbandonment event) {
            order.add("GENERATION_ABANDONED");
        }

        @Override
        public void transportLifecycle(ProviderResponseCapture.TransportLifecycleEvent event) {
            if (event.transition() == ProviderTransportAttempt.Transition.SETTLED) {
                order.add(event.lateAfterGenerationTerminal()
                        ? "TRANSPORT_SETTLED_LATE"
                        : "TRANSPORT_SETTLED_NOT_LATE");
                transportSettled.countDown();
            }
        }
    }
}
