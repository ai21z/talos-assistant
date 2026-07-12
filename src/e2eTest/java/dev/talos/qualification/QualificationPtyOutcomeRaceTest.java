package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void idlePromptIsRecognizedWhenJlineResetsTheCursorColumnBeforePositioning() {
        String actualLastTraceFrame = PROMPT + "\u001B[0K\u001B[1G\u001B[16G";

        assertEquals(0, QualificationPtyOutcomeRace.idlePromptIndex(actualLastTraceFrame, 0));
    }

    @Test
    void cursorResetIdlePromptCompletesTheOutcomeRace() throws Exception {
        String cursorResetIdlePrompt = PROMPT + "\u001B[0K\u001B[1G\u001B[16G";
        ScriptedProbe probe = new ScriptedProbe(snapshot(
                IDLE_PROMPT + "\r" + PROMPT + " /last trace\r\ntrace output\r\n"
                        + cursorResetIdlePrompt,
                true,
                0));

        QualificationPtyOutcomeRace.Outcome outcome = QualificationPtyOutcomeRace.await(
                probe, IDLE_PROMPT.length(), Duration.ofSeconds(1), new FakeWaiter());

        assertEquals(QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, outcome.kind());
    }

    @Test
    void idlePromptIsRecognizedWhenJlineWrapsAndReturnsOneRow() {
        String wrappedIdlePrompt = PROMPT
                + "\u001B[0K\r\n\u001B[0K\r\u001B[1A\u001B[16G";

        assertEquals(0, QualificationPtyOutcomeRace.idlePromptIndex(wrappedIdlePrompt, 0));
    }

    @Test
    void wrappedIdlePromptCompletesTheOutcomeRace() throws Exception {
        String wrappedIdlePrompt = PROMPT
                + "\u001B[0K\r\n\u001B[0K\r\u001B[1A\u001B[16G";
        ScriptedProbe probe = new ScriptedProbe(snapshot(
                IDLE_PROMPT + "\r" + PROMPT + " What is 2 + 2?\r\n4\r\n"
                        + wrappedIdlePrompt,
                true,
                0));

        QualificationPtyOutcomeRace.Outcome outcome = QualificationPtyOutcomeRace.await(
                probe, IDLE_PROMPT.length(), Duration.ofSeconds(1), new FakeWaiter());

        assertEquals(QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, outcome.kind());
    }

    @Test
    void promptLikeTextWithoutAnExactIdleSuffixIsRejected() {
        for (String candidate : List.of(
                PROMPT + " /last trace",
                PROMPT + "\u001B[0K\u001B[1G",
                PROMPT + "\u001B[0K\u001B[2G\u001B[16G",
                PROMPT + "\u001B[0K\r\n\u001B[0K\r\u001B[1A",
                PROMPT + "\u001B[0K\r\n\u001B[0K\r\u001B[2A\u001B[16G",
                PROMPT + "\u001B[0K\r\n\r\u001B[1A\u001B[16G",
                PROMPT)) {
            assertEquals(-1, QualificationPtyOutcomeRace.idlePromptIndex(candidate, 0), candidate);
        }
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

    @Test
    void denySequenceHandlesTwoApprovalPromptsBeforeCompletion() throws Exception {
        SequenceFixture fixture = new SequenceFixture("DENY", 2);

        QualificationPtyOutcomeRace.SequenceOutcome result = fixture.run(Duration.ofSeconds(1));

        assertEquals(QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, result.outcome().kind());
        assertEquals(2, result.approvalCount());
        assertEquals(List.of("approval-deny", "approval-deny-repeat-2"), fixture.labels());
        assertEquals(List.of("", ""), fixture.responses());
    }

    @Test
    void approveOnceDeniesASecondApprovalPrompt() throws Exception {
        SequenceFixture fixture = new SequenceFixture("APPROVE_ONCE", 2);

        QualificationPtyOutcomeRace.SequenceOutcome result = fixture.run(Duration.ofSeconds(1));

        assertEquals(QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, result.outcome().kind());
        assertEquals(List.of("approval-once", "approval-extra-deny-2"), fixture.labels());
        assertEquals(List.of("y", ""), fixture.responses());
    }

    @Test
    void sessionAllowDeniesAndLabelsAnUnexpectedLaterApproval() throws Exception {
        SequenceFixture fixture = new SequenceFixture("SESSION_ALLOW", 2);

        QualificationPtyOutcomeRace.SequenceOutcome result = fixture.run(Duration.ofSeconds(1));

        assertEquals(QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, result.outcome().kind());
        assertEquals(List.of("approval-session", "approval-unexpected-deny-2"), fixture.labels());
        assertEquals(List.of("a", ""), fixture.responses());
    }

    @Test
    void noApprovalScenarioConservativelyDeniesAnUnexpectedPrompt() throws Exception {
        SequenceFixture fixture = new SequenceFixture("NONE", 1);

        QualificationPtyOutcomeRace.SequenceOutcome result = fixture.run(Duration.ofSeconds(1));

        assertEquals(QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, result.outcome().kind());
        assertEquals(List.of("approval-unexpected-deny-1"), fixture.labels());
        assertEquals(List.of(""), fixture.responses());
    }

    @Test
    void repeatedApprovalsShareOneAbsoluteDeadline() throws Exception {
        FakeWaiter waiter = new FakeWaiter();
        TimedApprovalProbe probe = new TimedApprovalProbe(waiter, 40, 80, 120);
        List<String> responses = new ArrayList<>();

        QualificationPtyOutcomeRace.SequenceOutcome result =
                QualificationPtyOutcomeRace.awaitSequence(
                        "DENY", probe, 0, Duration.ofMillis(100), waiter,
                        (action, ordinal) -> {
                            responses.add(action.response());
                            return probe.consumeApproval();
                        });

        assertEquals(QualificationPtyOutcomeRace.Kind.TIMEOUT, result.outcome().kind());
        assertEquals(2, result.approvalCount());
        assertEquals(2, responses.size());
        assertTrue(waiter.nanos >= Duration.ofMillis(100).toNanos());
        assertTrue(waiter.nanos < Duration.ofMillis(125).toNanos());
    }

    @Test
    void approvalPromptLimitFailsClosed() {
        SequenceFixture fixture = new SequenceFixture(
                "DENY", QualificationPtyOutcomeRace.MAX_APPROVAL_PROMPTS + 1);

        assertThrows(QualificationPtyOutcomeRace.ApprovalPromptLimitException.class,
                () -> fixture.run(Duration.ofSeconds(1)));
        assertEquals(QualificationPtyOutcomeRace.MAX_APPROVAL_PROMPTS,
                fixture.responses().size());
    }

    @Test
    void answeredApprovalEchoDoesNotCountAsAnotherPrompt() throws Exception {
        StringBuilder text = new StringBuilder("approval required\r\nAllow?");
        List<String> labels = new ArrayList<>();
        List<String> responses = new ArrayList<>();

        QualificationPtyOutcomeRace.SequenceOutcome result =
                QualificationPtyOutcomeRace.awaitSequence(
                        "APPROVE_ONCE",
                        () -> snapshot(text.toString(), true, 0),
                        0,
                        Duration.ofSeconds(1),
                        new FakeWaiter(),
                        (action, ordinal) -> {
                            labels.add(action.eventLabel());
                            responses.add(action.response());
                            int anchor = text.length();
                            text.append("\r\nAllow? [y=yes, a=yes for session, N=no] y\r\n")
                                    .append(IDLE_PROMPT);
                            return anchor;
                        });

        assertEquals(QualificationPtyOutcomeRace.Kind.TURN_COMPLETE, result.outcome().kind());
        assertEquals(1, result.approvalCount());
        assertEquals(List.of("approval-once"), labels);
        assertEquals(List.of("y"), responses);
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

    private static final class SequenceFixture {
        private final String behavior;
        private final int approvals;
        private final StringBuilder text = new StringBuilder();
        private final List<String> labels = new ArrayList<>();
        private final List<String> responses = new ArrayList<>();

        private SequenceFixture(String behavior, int approvals) {
            this.behavior = behavior;
            this.approvals = approvals;
            text.append("request\r\nAllow?");
        }

        private QualificationPtyOutcomeRace.SequenceOutcome run(Duration timeout) throws Exception {
            AtomicInteger handled = new AtomicInteger();
            return QualificationPtyOutcomeRace.awaitSequence(
                    behavior,
                    () -> snapshot(text.toString(), true, 0),
                    0,
                    timeout,
                    new FakeWaiter(),
                    (action, ordinal) -> {
                        labels.add(action.eventLabel());
                        responses.add(action.response());
                        int anchor = text.length();
                        if (handled.incrementAndGet() < approvals) {
                            text.append("\r\ndenied\r\napproval required\r\nAllow?");
                        } else {
                            text.append("\r\ndone\r\n").append(IDLE_PROMPT);
                        }
                        return anchor;
                    });
        }

        private List<String> labels() {
            return List.copyOf(labels);
        }

        private List<String> responses() {
            return List.copyOf(responses);
        }
    }

    private static final class TimedApprovalProbe implements QualificationPtyOutcomeRace.Probe {
        private final FakeWaiter waiter;
        private final long[] approvalAtNanos;
        private final StringBuilder text = new StringBuilder();
        private int nextApproval;

        private TimedApprovalProbe(FakeWaiter waiter, long... approvalAtMillis) {
            this.waiter = waiter;
            this.approvalAtNanos = new long[approvalAtMillis.length];
            for (int index = 0; index < approvalAtMillis.length; index++) {
                this.approvalAtNanos[index] = Duration.ofMillis(approvalAtMillis[index]).toNanos();
            }
            text.append("request\r\n");
        }

        @Override
        public QualificationPtyOutcomeRace.Snapshot snapshot() {
            if (nextApproval < approvalAtNanos.length
                    && waiter.nanos >= approvalAtNanos[nextApproval]
                    && !text.toString().endsWith("Allow?")) {
                text.append("approval required\r\nAllow?");
            }
            return QualificationPtyOutcomeRaceTest.snapshot(text.toString(), true, 0);
        }

        private int consumeApproval() {
            int anchor = text.length();
            text.append("\r\ndenied\r\n");
            nextApproval++;
            return anchor;
        }
    }
}
