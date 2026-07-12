package dev.talos.qualification;

import java.time.Duration;
import java.util.Objects;

/** Performs one bounded, side-effect-free wakeup when WinPTY withholds startup output. */
final class QualificationPtyStartupHandshake {
    private QualificationPtyStartupHandshake() {}

    @FunctionalInterface
    interface IdleWaiter {
        void await(Duration timeout) throws Exception;
    }

    @FunctionalInterface
    interface WakeupSender {
        void send() throws Exception;
    }

    static boolean await(
            IdleWaiter idleWaiter,
            WakeupSender wakeupSender,
            Duration initialWait,
            Duration recoveryWait) throws Exception {
        Objects.requireNonNull(idleWaiter, "idleWaiter");
        Objects.requireNonNull(wakeupSender, "wakeupSender");
        requirePositive(initialWait, "initialWait");
        requirePositive(recoveryWait, "recoveryWait");
        try {
            idleWaiter.await(initialWait);
            return false;
        } catch (QualificationPtyIdlePromptTimeoutException timeout) {
            wakeupSender.send();
            idleWaiter.await(recoveryWait);
            return true;
        }
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
