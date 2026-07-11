package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationPtyOutcomeRaceTest {
    private static final String PROMPT = QualificationPtyOutcomeRace.PROMPT_MARKER;
    private static final String IDLE_PROMPT = PROMPT + QualificationPtyOutcomeRace.IDLE_PROMPT_SUFFIX;

    @Test
    void approvalMarkerBeforeReturnedPromptWinsRace() throws Exception {
        ScriptedProbe probe = new ScriptedProbe(
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " Update index.html\r\n", true, 0),
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " Update index.html\r\nAllow?"
                        + IDLE_PROMPT, true, 0));

        QualificationPtyOutcomeRace.Outcome outcome = QualificationPtyOutcomeRace.await(
                probe, IDLE_PROMPT.length(), Duration.ofSeconds(1), new FakeWaiter());

        assertEquals(QualificationPtyOutcomeRace.Kind.APPROVAL, outcome.kind());
        assertTrue(outcome.markerIndex() < outcome.competingMarkerIndex());
    }

    @Test
    void returnedPromptBeforeApprovalWinsRace() throws Exception {
        ScriptedProbe probe = new ScriptedProbe(
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " Update index.html\r\n", true, 0),
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " Update index.html\r\nTask incomplete\r\n"
                        + IDLE_PROMPT + "Allow?", true, 0));

        QualificationPtyOutcomeRace.Outcome outcome = QualificationPtyOutcomeRace.await(
                probe, IDLE_PROMPT.length(), Duration.ofSeconds(1), new FakeWaiter());

        assertEquals(QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, outcome.kind());
        assertTrue(outcome.markerIndex() < outcome.competingMarkerIndex());
    }

    @Test
    void startupPromptBeforeSubmissionAnchorCannotCompleteTurn() throws Exception {
        ScriptedProbe probe = new ScriptedProbe(
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " Update index.html\r\n", true, 0),
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " Update index.html\r\nAllow?", true, 0));

        QualificationPtyOutcomeRace.Outcome outcome = QualificationPtyOutcomeRace.await(
                probe, IDLE_PROMPT.length(), Duration.ofSeconds(1), new FakeWaiter());

        assertEquals(QualificationPtyOutcomeRace.Kind.APPROVAL, outcome.kind());
    }

    @Test
    void echoedInputPromptCannotBeMistakenForIdleCompletion() throws Exception {
        ScriptedProbe probe = new ScriptedProbe(
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " Update index.html\r\n", true, 0),
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " Update index.html\r\nAllow?", true, 0));

        QualificationPtyOutcomeRace.Outcome outcome = QualificationPtyOutcomeRace.await(
                probe, IDLE_PROMPT.length(), Duration.ofSeconds(1), new FakeWaiter());

        assertEquals(QualificationPtyOutcomeRace.Kind.APPROVAL, outcome.kind());
    }

    @Test
    void idlePromptIsRecognizedWhenCursorWasMadeVisibleBeforePromptText() {
        String actualStartupFrame = "\u001B[?25h" + PROMPT + "\u001B[0K\u001B[16G";

        assertEquals("\u001B[?25h".length(),
                QualificationPtyOutcomeRace.idlePromptIndex(actualStartupFrame, 0));
    }

    @Test
    void processExitBeforeEitherMarkerIsStructuredOutcome() throws Exception {
        ScriptedProbe probe = new ScriptedProbe(
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " request\r\n", true, 0),
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " request\r\nfatal\r\n", false, 23));

        QualificationPtyOutcomeRace.Outcome outcome = QualificationPtyOutcomeRace.await(
                probe, IDLE_PROMPT.length(), Duration.ofSeconds(1), new FakeWaiter());

        assertEquals(QualificationPtyOutcomeRace.Kind.PROCESS_EXIT, outcome.kind());
        assertEquals(23, outcome.exitCode());
    }

    @Test
    void noMarkerAndLiveProcessReachBoundedTimeout() throws Exception {
        ScriptedProbe probe = new ScriptedProbe(
                snapshot(IDLE_PROMPT + "\r" + PROMPT + " request\r\n", true, 0));
        FakeWaiter waiter = new FakeWaiter();

        QualificationPtyOutcomeRace.Outcome outcome = QualificationPtyOutcomeRace.await(
                probe, IDLE_PROMPT.length(), Duration.ofMillis(75), waiter);

        assertEquals(QualificationPtyOutcomeRace.Kind.TIMEOUT, outcome.kind());
        assertTrue(waiter.pauseCount > 0);
    }

    @Test
    void expectedApprovalBehaviorsKeepTheirExactInputs() {
        assertAction("APPROVE_ONCE", "approval-once", "y");
        assertAction("SESSION_ALLOW", "approval-session", "a");
        assertAction("DENY", "approval-deny", "");
    }

    @Test
    void completedTurnNeverSynthesizesApprovalInput() {
        for (String behavior : List.of("APPROVE_ONCE", "SESSION_ALLOW", "DENY")) {
            QualificationPtyOutcomeRace.ApprovalAction action =
                    QualificationPtyOutcomeRace.actionFor(behavior,
                            new QualificationPtyOutcomeRace.Outcome(
                                    QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, 42, -1, -1));

            assertFalse(action.sendInput(), behavior);
            assertEquals("turn-completed-before-expected-approval", action.eventLabel(), behavior);
            assertEquals("", action.response(), behavior);
        }
    }

    private static void assertAction(String behavior, String label, String response) {
        QualificationPtyOutcomeRace.ApprovalAction action =
                QualificationPtyOutcomeRace.actionFor(behavior,
                        new QualificationPtyOutcomeRace.Outcome(
                                QualificationPtyOutcomeRace.Kind.APPROVAL, 10, -1, 20));
        assertTrue(action.sendInput(), behavior);
        assertEquals(label, action.eventLabel(), behavior);
        assertEquals(response, action.response(), behavior);
    }

    private static QualificationPtyOutcomeRace.Snapshot snapshot(
            String text,
            boolean processAlive,
            int exitCode) {
        return new QualificationPtyOutcomeRace.Snapshot(text, processAlive, exitCode);
    }

    private static final class ScriptedProbe implements QualificationPtyOutcomeRace.Probe {
        private final List<QualificationPtyOutcomeRace.Snapshot> snapshots;
        private int index;

        private ScriptedProbe(QualificationPtyOutcomeRace.Snapshot... snapshots) {
            this.snapshots = new ArrayList<>(List.of(snapshots));
        }

        @Override
        public QualificationPtyOutcomeRace.Snapshot snapshot() {
            int current = Math.min(index, snapshots.size() - 1);
            index++;
            return snapshots.get(current);
        }
    }

    private static final class FakeWaiter implements QualificationPtyOutcomeRace.Waiter {
        private long nanos;
        private int pauseCount;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void pause(Duration duration) {
            pauseCount++;
            nanos += Math.max(1L, duration.toNanos());
        }
    }
}
