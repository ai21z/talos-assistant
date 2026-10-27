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
