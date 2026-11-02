package dev.talos.runtime.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** A local policy decision is bound to its source turn, request and displayed proposal. */
public record ProposalProvenance(
        int policyVersion,
        int sourceTurnNumber,
        String sourceTraceId,
        String requestHash,
        String answerHash,
        String summaryHash,
        List<String> targets) {
    public static final int POLICY_VERSION = 1;

    public ProposalProvenance {
        sourceTraceId = sourceTraceId == null ? "" : sourceTraceId;
        requestHash = requestHash == null ? "" : requestHash;
        answerHash = answerHash == null ? "" : answerHash;
        summaryHash = summaryHash == null ? "" : summaryHash;
        targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public static ProposalProvenance none() {
        return new ProposalProvenance(0, 0, "", "", "", "", List.of());
    }

    public static ProposalProvenance accepted(
            ActiveTaskContext context, String request, String answer) {
        if (context == null || request == null || request.isBlank()
                || answer == null || answer.isBlank()) return none();
        return new ProposalProvenance(POLICY_VERSION, context.sourceTurnNumber(),
                context.sourceTraceId(), hash(request), hash(answer),
                hash(context.proposalSummary()), context.targets());
    }

    /** A runtime repair offer is bound to its source turn, trace, delivered answer, offer text and targets. */
    public static ProposalProvenance repairOffer(ActiveTaskContext context, String answer) {
        if (context == null || answer == null || answer.isBlank()) return none();
        return new ProposalProvenance(POLICY_VERSION, context.sourceTurnNumber(),
                context.sourceTraceId(), "", hash(answer), hash(RepairOffer.LINE), context.targets());
    }

    /**
     * A runtime change offer is bound to its source turn, trace, the user's request, the delivered answer, the offer
     * line, the recorded requested change and the targets (decision D6).
     */
    public static ProposalProvenance changeOffer(ActiveTaskContext context, String request, String answer) {
        if (context == null || request == null || request.isBlank() || answer == null || answer.isBlank()) {
            return none();
        }
        return new ProposalProvenance(POLICY_VERSION, context.sourceTurnNumber(), context.sourceTraceId(),
                hash(request), hash(answer), changeOfferSummaryHash(context), context.targets());
    }

    // The offer hash covers a fixed tag, the offer line and the recorded summary. A proposal hash covers only its
    // summary, and a summary cannot equal a longer text that contains it, so no model proposal passes as an offer.
    private static String changeOfferSummaryHash(ActiveTaskContext context) {
        return hash("change-offer\n" + ChangeOffer.line(context.targets()) + "\n" + context.proposalSummary());
    }

    boolean matchesChangeOffer(ActiveTaskContext context) {
        return policyVersion == POLICY_VERSION
                && sourceTurnNumber > 0
                && sourceTurnNumber == context.sourceTurnNumber()
                && !sourceTraceId.isBlank()
                && sourceTraceId.equals(context.sourceTraceId())
                && requestHash.matches("[a-f0-9]{64}")
                && answerHash.matches("[a-f0-9]{64}")
                && !targets.isEmpty()
                && targets.size() <= ActiveTaskContext.MAX_TARGETS
                && targets.equals(context.targets())
                && summaryHash.equals(changeOfferSummaryHash(context));
    }

    boolean matchesRepairOffer(ActiveTaskContext context) {
        return policyVersion == POLICY_VERSION
                && sourceTurnNumber > 0
                && sourceTurnNumber == context.sourceTurnNumber()
                && !sourceTraceId.isBlank()
                && sourceTraceId.equals(context.sourceTraceId())
                && requestHash.isEmpty()
                && answerHash.matches("[a-f0-9]{64}")
                && summaryHash.equals(hash(RepairOffer.LINE))
                && !targets.isEmpty()
                && targets.size() <= ActiveTaskContext.MAX_TARGETS
                && targets.equals(context.targets());
    }

    boolean matches(ActiveTaskContext context) {
        return policyVersion == POLICY_VERSION
                && sourceTurnNumber > 0
                && sourceTurnNumber == context.sourceTurnNumber()
                && !sourceTraceId.isBlank()
                && sourceTraceId.equals(context.sourceTraceId())
                && requestHash.matches("[a-f0-9]{64}")
                && answerHash.matches("[a-f0-9]{64}")
                && !context.proposalSummary().isBlank()
                && summaryHash.equals(hash(context.proposalSummary()))
                && !targets.isEmpty()
                && targets.size() <= ActiveTaskContext.MAX_TARGETS
                && targets.equals(context.targets());
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
