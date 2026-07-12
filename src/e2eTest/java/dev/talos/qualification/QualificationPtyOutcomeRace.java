package dev.talos.qualification;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/** Anchored approval-versus-terminal race used by the Windows qualification PTY driver. */
final class QualificationPtyOutcomeRace {
    static final String APPROVAL_MARKER = "Allow?";
    static final String APPROVAL_CARD_MARKER = "approval required";
    static final String PROMPT_MARKER = "talos [auto] >";
    static final String IDLE_PROMPT_SUFFIX = "\u001B[0K\u001B[16G";
    static final String CURSOR_RESET_IDLE_PROMPT_SUFFIX = "\u001B[0K\u001B[1G\u001B[16G";
    static final String CURSOR_TOGGLE_STARTUP_PROMPT_SUFFIX =
            "\u001B[0K\u001B[1G\u001B[?25h\u001B[16G\u001B[?25l\r";
    static final String VISIBLE_CURSOR_IDLE_PROMPT_SUFFIX =
            "\u001B[0K\u001B[1G\u001B[?25h\u001B[16G";
    static final String WRAPPED_IDLE_PROMPT_SUFFIX =
            "\u001B[0K\r\n\u001B[0K\r\u001B[1A\u001B[16G";
    static final int MAX_APPROVAL_PROMPTS = 8;
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

    record SequenceOutcome(Outcome outcome, int approvalCount) {
        SequenceOutcome {
            Objects.requireNonNull(outcome, "outcome");
            if (approvalCount < 0) throw new IllegalArgumentException("approval count must not be negative");
        }
    }

    @FunctionalInterface
    interface ApprovalResponder {
        int respond(ApprovalAction action, int ordinal) throws Exception;
    }

    static final class ApprovalPromptLimitException extends IllegalStateException {
        ApprovalPromptLimitException(int limit) {
            super("approval prompt limit exceeded: " + limit);
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
        return await(probe, from, timeout, waiter, false);
    }

    private static Outcome await(
            Probe probe,
            int from,
            Duration timeout,
            Waiter waiter,
            boolean requireFreshApprovalCard) throws Exception {
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
            int approval = approvalPromptIndex(
                    snapshot.text(), safeFrom, requireFreshApprovalCard);
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

    static SequenceOutcome awaitSequence(
            String approvalBehavior,
            Probe probe,
            int from,
            Duration timeout,
            Waiter waiter,
            ApprovalResponder responder) throws Exception {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(waiter, "waiter");
        Objects.requireNonNull(responder, "responder");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");

        long deadline = saturatingAdd(waiter.nanoTime(), timeout.toNanos());
        int anchor = from;
        int approvalCount = 0;
        while (true) {
            long remainingNanos = Math.max(0L, deadline - waiter.nanoTime());
            Outcome outcome = await(
                    probe,
                    anchor,
                    Duration.ofNanos(remainingNanos),
                    waiter,
                    approvalCount > 0);
            if (outcome.kind() != Kind.APPROVAL) {
                return new SequenceOutcome(outcome, approvalCount);
            }

            approvalCount++;
            if (approvalCount > MAX_APPROVAL_PROMPTS) {
                throw new ApprovalPromptLimitException(MAX_APPROVAL_PROMPTS);
            }
            ApprovalAction action = actionFor(approvalBehavior, outcome, approvalCount);
            int nextAnchor = responder.respond(action, approvalCount);
            if (nextAnchor < 0) throw new IllegalStateException("approval responder returned a negative anchor");
            anchor = nextAnchor;
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
                    || value.startsWith(CURSOR_RESET_IDLE_PROMPT_SUFFIX, suffix)
                    || value.startsWith(CURSOR_TOGGLE_STARTUP_PROMPT_SUFFIX, suffix)
                    || visibleCursorIdleAtBufferEnd(value, suffix)
                    || value.startsWith(WRAPPED_IDLE_PROMPT_SUFFIX, suffix)) {
                return found;
            }
            cursor = suffix;
        }
        return -1;
    }

    private static boolean visibleCursorIdleAtBufferEnd(String value, int suffix) {
        return value.startsWith(VISIBLE_CURSOR_IDLE_PROMPT_SUFFIX, suffix)
                && suffix + VISIBLE_CURSOR_IDLE_PROMPT_SUFFIX.length() == value.length();
    }

    private static int approvalPromptIndex(
            String text,
            int from,
            boolean requireFreshApprovalCard) {
        String value = text == null ? "" : text;
        int safeFrom = Math.max(0, Math.min(from, value.length()));
        int cursor = safeFrom;
        String searchable = requireFreshApprovalCard ? value.toLowerCase(Locale.ROOT) : value;
        while (cursor < value.length()) {
            int approval = value.indexOf(APPROVAL_MARKER, cursor);
            if (approval < 0) return -1;
            if (!requireFreshApprovalCard) return approval;
            int card = searchable.lastIndexOf(APPROVAL_CARD_MARKER, approval);
            if (card >= safeFrom) return approval;
            cursor = approval + APPROVAL_MARKER.length();
        }
        return -1;
    }

    static ApprovalAction actionFor(String approvalBehavior, Outcome outcome) {
        return actionFor(approvalBehavior, outcome, 1);
    }

    static ApprovalAction actionFor(
            String approvalBehavior,
            Outcome outcome,
            int approvalOrdinal) {
        Objects.requireNonNull(outcome, "outcome");
        if (approvalOrdinal <= 0) {
            throw new IllegalArgumentException("approval ordinal must be positive");
        }
        String behavior = approvalBehavior == null ? "" : approvalBehavior.strip();
        if (outcome.kind() == Kind.TURN_COMPLETE) {
            return new ApprovalAction(false, "turn-completed-before-expected-approval", "");
        }
        if (outcome.kind() != Kind.APPROVAL) {
            throw new IllegalStateException("approval action unavailable for " + outcome.kind());
        }
        if (approvalOrdinal > 1) {
            return switch (behavior) {
                case "DENY" -> new ApprovalAction(
                        true, "approval-deny-repeat-" + approvalOrdinal, "");
                case "APPROVE_ONCE" -> new ApprovalAction(
                        true, "approval-extra-deny-" + approvalOrdinal, "");
                case "SESSION_ALLOW", "NONE" -> new ApprovalAction(
                        true, "approval-unexpected-deny-" + approvalOrdinal, "");
                default -> throw new IllegalArgumentException(
                        "unsupported approval behavior: " + approvalBehavior);
            };
        }
        return switch (behavior) {
            case "NONE" -> new ApprovalAction(true, "approval-unexpected-deny-1", "");
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
