package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.command.CommandToolPlanner;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T792: evidence-based verification upgrade. A user-approved, successful,
 * verification-class {@code run_command} outcome ordered AFTER the last
 * successful mutation of the turn is command-level proof the workspace
 * verifies — strictly stronger than readback.
 *
 * <p>Extraction is deliberately conservative: only the exact success-summary
 * shape {@code RunCommandTool.renderSuccess} emits is accepted; anything
 * ambiguous yields empty and the would-be READBACK_ONLY verdict stands
 * (fail closed). The upgrade is additive only — a FAILED verdict is never
 * overridden (failed runs already dominate the answer path).
 */
final class CommandVerificationEvidence {

    private static final Pattern SUCCESS_SUMMARY =
            Pattern.compile("^Command succeeded: (\\S+) exited with code 0\\b");

    /**
     * Verification-class built-ins only: test/check/e2e prove behavior.
     * gradle_build / gradle_install_dist are builds, not verification —
     * deliberately excluded in v1. Workspace {@code ws:} profiles are
     * verification profiles by declaration intent.
     */
    private static final Set<String> GRADLE_VERIFICATION_PROFILES =
            Set.of("gradle_test", "gradle_check", "gradle_e2e_test");

    private CommandVerificationEvidence() {}

    static Optional<String> verificationProfilePassedAfterMutations(
            List<ToolCallLoop.ToolOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return Optional.empty();
        int lastMutation = -1;
        for (int i = 0; i < outcomes.size(); i++) {
            ToolCallLoop.ToolOutcome outcome = outcomes.get(i);
            if (outcome != null && outcome.mutating() && outcome.success()) {
                lastMutation = i;
            }
        }
        for (int i = outcomes.size() - 1; i > lastMutation; i--) {
            ToolCallLoop.ToolOutcome outcome = outcomes.get(i);
            if (outcome == null || !outcome.success() || outcome.denied()) continue;
            if (!CommandToolPlanner.isRunCommandTool(outcome.toolName())) continue;
            Matcher matcher = SUCCESS_SUMMARY.matcher(Objects.toString(outcome.summary(), ""));
            if (!matcher.find()) continue; // ambiguous shape — fail closed
            String profile = matcher.group(1);
            if (isVerificationClass(profile)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    static boolean isVerificationClass(String profile) {
        return GRADLE_VERIFICATION_PROFILES.contains(profile)
                || CommandToolPlanner.isWorkspaceProfile(profile);
    }
}
