package dev.talos.qualification;

import java.time.Duration;
import java.util.Objects;

/** Keeps a recognized JLine idle prompt stable before the next PTY input. */
final class QualificationPtyIdleSettle {
    static final Duration SETTLE_DELAY = Duration.ofMillis(100);

    private QualificationPtyIdleSettle() {}

    @FunctionalInterface
    interface IdleWait {
        void await() throws Exception;
    }

    @FunctionalInterface
    interface Pauser {
        void pause(Duration duration) throws Exception;
    }

    static void awaitAndPause(IdleWait idleWait, Pauser pauser) throws Exception {
        Objects.requireNonNull(idleWait, "idleWait").await();
        Objects.requireNonNull(pauser, "pauser").pause(SETTLE_DELAY);
    }
}
