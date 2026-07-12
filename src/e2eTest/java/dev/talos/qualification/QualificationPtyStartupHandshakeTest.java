package dev.talos.qualification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QualificationPtyStartupHandshakeTest {
    private static final Duration INITIAL_WAIT = Duration.ofSeconds(30);
    private static final Duration RECOVERY_WAIT = Duration.ofSeconds(10);

    @Test
    void ordinaryStartupDoesNotSendWakeupInput() throws Exception {
        List<String> events = new ArrayList<>();

        boolean recovered = QualificationPtyStartupHandshake.await(
                timeout -> events.add("wait:" + timeout.toSeconds()),
                () -> events.add("wakeup"),
                INITIAL_WAIT,
                RECOVERY_WAIT);

        assertFalse(recovered);
        assertEquals(List.of("wait:30"), events);
    }

    @Test
    void oneTypedStartupTimeoutSendsOneWakeupAndRequiresPrompt() throws Exception {
        List<String> events = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();

        boolean recovered = QualificationPtyStartupHandshake.await(
                timeout -> {
                    events.add("wait:" + timeout.toSeconds());
                    if (attempts.getAndIncrement() == 0) {
                        throw new QualificationPtyIdlePromptTimeoutException();
                    }
                },
                () -> events.add("wakeup"),
                INITIAL_WAIT,
                RECOVERY_WAIT);

        assertTrue(recovered);
        assertEquals(List.of("wait:30", "wakeup", "wait:10"), events);
    }

    @Test
    void nonTimeoutFailureIsNotRecovered() {
        List<String> events = new ArrayList<>();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                QualificationPtyStartupHandshake.await(
                        timeout -> {
                            events.add("wait");
                            throw new IllegalStateException("Talos exited");
                        },
                        () -> events.add("wakeup"),
                        INITIAL_WAIT,
                        RECOVERY_WAIT));

        assertEquals("Talos exited", failure.getMessage());
        assertEquals(List.of("wait"), events);
    }

    @Test
    void secondTimeoutFailsClosedAfterExactlyOneWakeup() {
        List<String> events = new ArrayList<>();

        assertThrows(QualificationPtyIdlePromptTimeoutException.class, () ->
                QualificationPtyStartupHandshake.await(
                        timeout -> {
                            events.add("wait:" + timeout.toSeconds());
                            throw new QualificationPtyIdlePromptTimeoutException();
                        },
                        () -> events.add("wakeup"),
                        INITIAL_WAIT,
                        RECOVERY_WAIT));

        assertEquals(List.of("wait:30", "wakeup", "wait:10"), events);
    }
}
