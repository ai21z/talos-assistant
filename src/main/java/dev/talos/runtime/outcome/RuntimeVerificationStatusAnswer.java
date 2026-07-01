package dev.talos.runtime.outcome;

import dev.talos.runtime.context.ChangeSummaryContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Deterministic answers for verification-status follow-ups from runtime evidence. */
public final class RuntimeVerificationStatusAnswer {
    private RuntimeVerificationStatusAnswer() {}

    public static String renderIfNeeded(String userRequest, ChangeSummaryContext context) {
        if (!looksLikeVerificationStatusQuestion(userRequest)) return null;
        if (!hasRuntimeVerificationEvidence(context)) {
            return """
                    No loaded prior verifier state is available for this session.

                    This read-only status turn did not run post-apply verification, so Talos cannot claim the current workspace is verified from model inference or file reads alone.""";
        }

        boolean verifiedComplete = latestRuntimeVerificationComplete(context);
        StringBuilder out = new StringBuilder();
        if (verifiedComplete) {
            out.append("Yes. Latest Talos-recorded verification is verified complete.");
        } else {
            out.append("No. Latest Talos-recorded verification is not verified complete.");
        }
        String status = runtimeVerificationStatus(context);
        if (!status.isBlank()) {
            out.append("\n\nRuntime verification state: ").append(status).append('.');
        }
        List<String> changed = runtimeVerificationChangedFileStates(context);
        if (!changed.isEmpty()) {
            out.append("\n\nRecorded changed files:\n");
            for (String line : changed) {
                out.append("- ").append(line).append('\n');
            }
        }
        if (!context.unresolvedTargets().isEmpty()) {
            out.append("\nUnresolved expected targets:\n");
            for (String target : context.unresolvedTargets()) {
                out.append("- ").append(target).append('\n');
            }
        }
        if (!context.verifierFindings().isEmpty()) {
            out.append("\nVerifier findings:\n");
            for (String finding : context.verifierFindings()) {
                out.append("- ").append(finding).append('\n');
            }
        }
        if (!context.unresolvedVerificationFailures().isEmpty()) {
            out.append("\nUnresolved verification failures:\n");
            for (ChangeSummaryContext.VerificationFailure failure : context.unresolvedVerificationFailures()) {
                String rendered = renderRuntimeVerificationFailure(failure);
                if (!rendered.isBlank()) out.append("- ").append(rendered).append('\n');
            }
        }
        out.append("\nScope: Talos-recorded runtime mutation history and verifier history only; ")
                .append("external edits and protected file contents are outside this answer.");
        return out.toString().stripTrailing();
    }

    private static boolean looksLikeVerificationStatusQuestion(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return lower.contains("is it verified")
                || lower.contains("is this verified")
                || lower.contains("verified now")
                || lower.contains("what remains unverified")
                || lower.contains("still unverified")
                || lower.contains("anything unverified")
                || lower.contains("anything still unverified")
                || lower.contains("verification status")
                || lower.contains("static verification status");
    }

    private static boolean hasRuntimeVerificationEvidence(ChangeSummaryContext context) {
        if (context == null) return false;
        return !context.verificationStatus().isBlank()
                || !context.completionStatus().isBlank()
                || !context.verifierFindings().isEmpty()
                || !context.unresolvedTargets().isEmpty()
                || !context.unresolvedVerificationFailures().isEmpty()
                || context.changedFiles().stream().anyMatch(RuntimeVerificationStatusAnswer::hasRuntimeFileVerificationState);
    }

    private static boolean latestRuntimeVerificationComplete(ChangeSummaryContext context) {
        if (context == null) return false;
        if (!context.unresolvedTargets().isEmpty()
                || !context.verifierFindings().isEmpty()
                || !context.unresolvedVerificationFailures().isEmpty()) {
            return false;
        }
        boolean latestPassed = "PASSED".equalsIgnoreCase(context.verificationStatus())
                || "COMPLETED_VERIFIED".equalsIgnoreCase(context.completionStatus());
        if (!latestPassed) return false;
        List<ChangeSummaryContext.FileChange> statefulChanges = context.changedFiles().stream()
                .filter(RuntimeVerificationStatusAnswer::hasRuntimeFileVerificationState)
                .toList();
        return statefulChanges.isEmpty()
                || statefulChanges.stream().allMatch(RuntimeVerificationStatusAnswer::runtimeFileVerifiedComplete);
    }

    private static String runtimeVerificationStatus(ChangeSummaryContext context) {
        if (context == null) return "";
        List<String> parts = new ArrayList<>();
        if (!context.verificationStatus().isBlank()) parts.add("verifier=" + context.verificationStatus());
        if (!context.completionStatus().isBlank()) parts.add("completion=" + context.completionStatus());
        return String.join("; ", parts);
    }

    private static List<String> runtimeVerificationChangedFileStates(ChangeSummaryContext context) {
        if (context == null || context.changedFiles().isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (ChangeSummaryContext.FileChange change : context.changedFiles()) {
            if (change == null || change.path().isBlank()) continue;
            List<String> state = new ArrayList<>();
            if (!change.verificationStatus().isBlank()) state.add("verifier=" + change.verificationStatus());
            if (!change.completionStatus().isBlank()) state.add("completion=" + change.completionStatus());
            if (!change.traceId().isBlank()) state.add("trace=" + change.traceId());
            out.add(state.isEmpty()
                    ? change.path()
                    : change.path() + " [" + String.join("; ", state) + "]");
        }
        return List.copyOf(out);
    }

    private static String renderRuntimeVerificationFailure(ChangeSummaryContext.VerificationFailure failure) {
        if (failure == null) return "";
        StringBuilder out = new StringBuilder();
        if (!failure.paths().isEmpty()) {
            out.append(String.join(", ", failure.paths()));
        }
        if (failure.turnNumber() > 0) {
            if (!out.isEmpty()) out.append(' ');
            out.append("(turn ").append(failure.turnNumber()).append(')');
        }
        List<String> state = new ArrayList<>();
        if (!failure.verificationStatus().isBlank()) state.add("verifier=" + failure.verificationStatus());
        if (!failure.completionStatus().isBlank()) state.add("completion=" + failure.completionStatus());
        if (!state.isEmpty()) {
            if (!out.isEmpty()) out.append(": ");
            out.append(String.join("; ", state));
        }
        if (!failure.findings().isEmpty()) {
            if (!out.isEmpty()) out.append(" - ");
            out.append(String.join("; ", failure.findings().stream().limit(3).toList()));
        }
        return out.toString();
    }

    private static boolean hasRuntimeFileVerificationState(ChangeSummaryContext.FileChange change) {
        return change != null
                && (!change.verificationStatus().isBlank() || !change.completionStatus().isBlank());
    }

    private static boolean runtimeFileVerifiedComplete(ChangeSummaryContext.FileChange change) {
        if (change == null) return false;
        return "PASSED".equalsIgnoreCase(change.verificationStatus())
                || "COMPLETED_VERIFIED".equalsIgnoreCase(change.completionStatus());
    }
}
