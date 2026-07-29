package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.obligation.RequestObligation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Verifier-owned positive receipt for one exact complete request obligation.
 *
 * <p>Generic post-apply success is deliberately insufficient. A positive
 * receipt can be created only beside the exact final-state verifier and only
 * when its proof covers every successful mutation.
 */
public final class RequestObligationVerificationReceipt {
    private static final RequestObligationVerificationReceipt NONE =
            new RequestObligationVerificationReceipt("", List.of());

    private final String obligationSha256;
    private final List<String> coveredToolCallIds;

    private RequestObligationVerificationReceipt(
            String obligationSha256,
            List<String> coveredToolCallIds
    ) {
        this.obligationSha256 = Objects.requireNonNull(
                obligationSha256, "obligationSha256");
        this.coveredToolCallIds = List.copyOf(
                Objects.requireNonNull(
                        coveredToolCallIds,
                        "coveredToolCallIds"));
    }

    public static RequestObligationVerificationReceipt none() {
        return NONE;
    }

    static RequestObligationVerificationReceipt verified(
            RequestObligation obligation,
            RequestObligationFinalStateVerifier.Result finalState,
            Set<String> verifiedTargets,
            List<ToolCallLoop.ToolOutcome> successfulMutations
    ) {
        if (finalState == null
                || !finalState.passedFor(obligation)) {
            return NONE;
        }

        Set<String> canonicalVerifiedTargets = canonicalTargets(verifiedTargets);
        if (canonicalVerifiedTargets == null
                || !canonicalVerifiedTargets.equals(
                        new LinkedHashSet<>(obligation.requiredMutationTargets()))
                || successfulMutations == null
                || successfulMutations.isEmpty()) {
            return NONE;
        }

        List<String> coveredToolCallIds = new ArrayList<>();
        Set<String> uniqueToolCallIds = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) {
                return NONE;
            }
            String target = canonicalTarget(outcome.pathHint());
            String toolCallId = outcome.toolCallId() == null
                    ? ""
                    : outcome.toolCallId().strip();
            if (target == null
                    || !canonicalVerifiedTargets.contains(target)
                    || toolCallId.isBlank()
                    || !uniqueToolCallIds.add(toolCallId)) {
                return NONE;
            }
            coveredToolCallIds.add(toolCallId);
        }
        return new RequestObligationVerificationReceipt(
                obligation.canonicalSha256(),
                coveredToolCallIds);
    }

    public boolean verified() {
        return !obligationSha256.isBlank();
    }

    public String obligationSha256() {
        return obligationSha256;
    }

    public List<String> coveredToolCallIds() {
        return coveredToolCallIds;
    }

    public boolean coversSuccessfulMutation(String toolCallId) {
        String normalized = toolCallId == null ? "" : toolCallId.strip();
        return verified()
                && !normalized.isBlank()
                && coveredToolCallIds.contains(normalized);
    }

    public boolean proves(RequestObligation obligation) {
        return verified()
                && obligation != null
                && obligation.derivationStatus()
                        == RequestObligation.DerivationStatus.COMPLETE
                && obligation.canonicalSha256().equals(obligationSha256);
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof RequestObligationVerificationReceipt other
                && obligationSha256.equals(other.obligationSha256)
                && coveredToolCallIds.equals(other.coveredToolCallIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                obligationSha256,
                coveredToolCallIds);
    }

    @Override
    public String toString() {
        return verified()
                ? "RequestObligationVerificationReceipt[verified]"
                : "RequestObligationVerificationReceipt[none]";
    }

    private static Set<String> canonicalTargets(Set<String> targets) {
        if (targets == null || targets.isEmpty()) return null;
        Set<String> canonical = new LinkedHashSet<>();
        for (String target : targets) {
            String normalized = canonicalTarget(target);
            if (normalized == null || !canonical.add(normalized)) return null;
        }
        return Set.copyOf(canonical);
    }

    private static String canonicalTarget(String target) {
        try {
            return RequestObligation.canonicalTarget(target);
        } catch (IllegalArgumentException unsafe) {
            return null;
        }
    }
}
