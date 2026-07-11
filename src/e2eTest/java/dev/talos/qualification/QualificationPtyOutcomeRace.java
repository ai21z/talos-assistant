package dev.talos.qualification;

import java.time.Duration;
import java.util.Objects;

/** Anchored approval-versus-terminal race used by the Windows qualification PTY driver. */
final class QualificationPtyOutcomeRace {
    static final String APPROVAL_MARKER = "Allow?";
    static final String PROMPT_MARKER = "talos [auto] >";
    static final String IDLE_PROMPT_SUFFIX = "\u001B[0K\u001B[16G";
    static final String CURSOR_RESET_IDLE_PROMPT_SUFFIX = "\u001B[0K\u001B[1G\u001B[16G";
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private QualificationPtyOutcomeRace() {}

    enum Kind {
        APPROVAL,
        TURN_COMPLETE,
        PROCESS_EXIT,
        TIMEOUT
    }

    record Snapshot(String text, boolean processAlive, int exitCode) {
        Snapshot {
            text = text == null ? "" : text;
        }
    }

    record Outcome(Kind kind, int markerIndex, int exitCode, int competingMarkerIndex) {
        Outcome {
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    record ApprovalAction(boolean sendInput, String eventLabel, String response) {
        ApprovalAction {
            eventLabel = eventLabel == null ? "" : eventLabel;
            response = response == null ? "" : response;
        }
    }

    interface Probe {
        Snapshot snapshot();
    }

    interface Waiter {
        long nanoTime();

        void pause(Duration duration) throws Exception;
    }

    static Outcome await(
            Probe probe,
            int from,
            Duration timeout,
            Waiter waiter) throws Exception {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(waiter, "waiter");
        if (from < 0) throw new IllegalArgumentException("race anchor must not be negative");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");

        long started = waiter.nanoTime();
        long deadline = saturatingAdd(started, timeout.toNanos());
        while (true) {
            Snapshot snapshot = Objects.requireNonNull(probe.snapshot(), "probe snapshot");
            int safeFrom = Math.min(from, snapshot.text().length());
            int approval = snapshot.text().indexOf(APPROVAL_MARKER, safeFrom);
            int prompt = idlePromptIndex(snapshot.text(), safeFrom);

            if (approval >= 0 && (prompt < 0 || approval < prompt)) {
                return new Outcome(Kind.APPROVAL, approval, -1, prompt);
            }
            if (prompt >= 0) {
                return new Outcome(Kind.TURN_COMPLETE, prompt, -1, approval);
            }
            if (!snapshot.processAlive()) {
                return new Outcome(Kind.PROCESS_EXIT, -1, snapshot.exitCode(), -1);
            }
            if (waiter.nanoTime() >= deadline) {
                return new Outcome(Kind.TIMEOUT, -1, -1, -1);
            }
            waiter.pause(POLL_INTERVAL);
        }
    }

    static int idlePromptIndex(String text, int from) {
        String value = text == null ? "" : text;
        int cursor = Math.max(0, Math.min(from, value.length()));
        while (cursor < value.length()) {
            int found = value.indexOf(PROMPT_MARKER, cursor);
            if (found < 0) return -1;
            int suffix = found + PROMPT_MARKER.length();
            if (value.startsWith(IDLE_PROMPT_SUFFIX, suffix)
                    || value.startsWith(CURSOR_RESET_IDLE_PROMPT_SUFFIX, suffix)) {
                return found;
            }
            cursor = suffix;
        }
        return -1;
    }

    static ApprovalAction actionFor(String approvalBehavior, Outcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        String behavior = approvalBehavior == null ? "" : approvalBehavior.strip();
        if (outcome.kind() == Kind.TURN_COMPLETE) {
            return new ApprovalAction(false, "turn-completed-before-expected-approval", "");
        }
        if (outcome.kind() != Kind.APPROVAL) {
            throw new IllegalStateException("approval action unavailable for " + outcome.kind());
        }
        return switch (behavior) {
            case "APPROVE_ONCE" -> new ApprovalAction(true, "approval-once", "y");
            case "SESSION_ALLOW" -> new ApprovalAction(true, "approval-session", "a");
            case "DENY" -> new ApprovalAction(true, "approval-deny", "");
            default -> throw new IllegalArgumentException(
                    "unsupported approval behavior: " + approvalBehavior);
        };
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0 && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }
}
