package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationProcessCleanupTest {

    private static final Duration NO_WAIT = Duration.ZERO;

    @Test
    void absentCampaignListenerIsVerifiedCleanState() throws Exception {
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0)) {
            unusedPort = reservation.getLocalPort();
        }
        String executableName = System.getProperty("os.name", "").startsWith("Windows")
                ? "java.exe"
                : "java";
        Path existingExecutable = Path.of(
                System.getProperty("java.home"), "bin", executableName);

        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                null, existingExecutable, unusedPort, Instant.now());

        assertEquals(QualificationProcessCleanup.Outcome.ALREADY_EXITED, result.outcome());
        assertTrue(result.campaignServerDiscoveryVerified());
        assertTrue(result.campaignServerPids().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void gracefullyExitsRootAndCapturedDescendantsWithoutTermination() {
        FakeTarget child = new FakeTarget(102L);
        FakeTarget root = new FakeTarget(101L).withDescendants(child);
        root.onExitCommand(() -> {
            root.alive = false;
            child.alive = false;
        });

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals("/exit\r", root.inputText());
        assertEquals(QualificationProcessCleanup.Outcome.GRACEFUL, result.outcome());
        assertEquals(List.of(102L), result.capturedDescendantPids());
        assertTrue(result.normalStopPids().isEmpty());
        assertTrue(result.forcedStopPids().isEmpty());
        assertTrue(result.survivingPids().isEmpty());
        assertEquals(0, root.destroyCalls + child.destroyCalls);
        assertEquals(0, root.forceCalls + child.forceCalls);
    }

    @Test
    void terminatesCapturedDescendantEvenWhenRootExitsGracefully() {
        FakeTarget child = new FakeTarget(202L).exitOnDestroy();
        FakeTarget root = new FakeTarget(201L).withDescendants(child);
        root.onExitCommand(() -> root.alive = false);

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals("/exit\r", root.inputText());
        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertEquals(List.of(202L), result.normalStopPids());
        assertTrue(result.forcedStopPids().isEmpty());
        assertFalse(child.alive);
        assertEquals(1, child.destroyCalls);
        assertEquals(0, root.destroyCalls);
    }

    @Test
    void usesForcedFallbackForEveryCapturedSurvivor() {
        FakeTarget child = new FakeTarget(302L).exitOnForce();
        FakeTarget root = new FakeTarget(301L).withDescendants(child).exitOnForce();

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals(QualificationProcessCleanup.Outcome.FORCED, result.outcome());
        assertEquals(List.of(302L, 301L), result.normalStopPids());
        assertEquals(List.of(302L, 301L), result.forcedStopPids());
        assertTrue(result.survivingPids().isEmpty());
        assertFalse(root.alive);
        assertFalse(child.alive);
    }

    @Test
    void neverTouchesProcessOutsideCapturedTree() {
        FakeTarget owned = new FakeTarget(402L).exitOnDestroy();
        FakeTarget unrelated = new FakeTarget(499L);
        FakeTarget root = new FakeTarget(401L).withDescendants(owned);
        root.onExitCommand(() -> root.alive = false);

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertTrue(unrelated.alive);
        assertEquals(0, unrelated.destroyCalls);
        assertEquals(0, unrelated.forceCalls);
        assertFalse(result.capturedDescendantPids().contains(unrelated.pid()));
    }

    @Test
    void terminatesExactCampaignServerAfterRootHasAlreadyExited() {
        FakeTarget campaignServer = new FakeTarget(502L).exitOnDestroy();
        FakeTarget unrelated = new FakeTarget(599L);
        FakeTarget root = new FakeTarget(501L);
        root.alive = false;

        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                root, List.of(campaignServer), NO_WAIT, NO_WAIT, NO_WAIT);

        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertFalse(campaignServer.alive);
        assertEquals(List.of(502L), result.campaignServerPids());
        assertEquals(List.of(502L), result.normalStopPids());
        assertTrue(unrelated.alive);
        assertEquals(0, unrelated.destroyCalls);
        assertEquals(0, unrelated.forceCalls);
    }

    @Test
    void exactCampaignIdentityWinsOverDuplicateGenericDescendant() {
        FakeTarget generic = new FakeTarget(602L).withIdentityMismatch();
        FakeTarget exactCampaignServer = new FakeTarget(602L).exitOnDestroy();
        FakeTarget root = new FakeTarget(601L).withDescendants(generic);
        root.alive = false;

        QualificationProcessCleanup.Result result = QualificationProcessCleanup.stop(
                root, List.of(exactCampaignServer), NO_WAIT, NO_WAIT, NO_WAIT);

        assertEquals(QualificationProcessCleanup.Outcome.TERMINATED, result.outcome());
        assertFalse(exactCampaignServer.alive);
        assertTrue(generic.alive);
        assertEquals(0, generic.destroyCalls);
        assertEquals(List.of(602L), result.normalStopPids());
    }

    @Test
    void identityLossFailsClosedAndIsVisibleInEvidence() {
        FakeTarget child = new FakeTarget(702L).withIdentityMismatch();
        FakeTarget root = new FakeTarget(701L).withDescendants(child);
        root.onExitCommand(() -> root.alive = false);

        QualificationProcessCleanup.Result result = cleanup(root);

        assertEquals(QualificationProcessCleanup.Outcome.FAILED, result.outcome());
        assertEquals(List.of(702L), result.survivingPids());
        assertTrue(result.errors().stream().anyMatch(error -> error.contains("identity changed")));
        assertEquals("FAILED", result.evidence().get("outcome"));
        assertEquals(List.of(702L), result.evidence().get("survivingPids"));
    }

    private static QualificationProcessCleanup.Result cleanup(FakeTarget root) {
        return QualificationProcessCleanup.stop(root, List.of(), NO_WAIT, NO_WAIT, NO_WAIT);
    }

    private static final class FakeTarget implements QualificationProcessCleanup.Target {
        private final long pid;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private final List<QualificationProcessCleanup.Target> descendants = new ArrayList<>();
        private Runnable exitCommand = () -> {};
        private boolean alive = true;
        private boolean destroyStops;
        private boolean forceStops;
        private boolean identityMatches = true;
        private int destroyCalls;
        private int forceCalls;

        private FakeTarget(long pid) {
            this.pid = pid;
        }

        private FakeTarget withDescendants(FakeTarget... targets) {
            descendants.addAll(List.of(targets));
            return this;
        }

        private FakeTarget onExitCommand(Runnable action) {
            exitCommand = action;
            return this;
        }

        private FakeTarget exitOnDestroy() {
            destroyStops = true;
            return this;
        }

        private FakeTarget exitOnForce() {
            forceStops = true;
            return this;
        }

        private FakeTarget withIdentityMismatch() {
            identityMatches = false;
            return this;
        }

        private String inputText() {
            return input.toString(StandardCharsets.UTF_8);
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public boolean identityMatches() {
            return identityMatches;
        }

        @Override
        public List<QualificationProcessCleanup.Target> descendants() {
            return List.copyOf(descendants);
        }

        @Override
        public OutputStream input() {
            return new OutputStream() {
                @Override
                public void write(int value) {
                    input.write(value);
                }

                @Override
                public void flush() {
                    exitCommand.run();
                }
            };
        }

        @Override
        public boolean waitFor(Duration timeout) {
            return !alive;
        }

        @Override
        public void destroy() {
            destroyCalls++;
            if (destroyStops) alive = false;
        }

        @Override
        public void destroyForcibly() {
            forceCalls++;
            if (forceStops) alive = false;
        }
    }
}
