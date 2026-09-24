package dev.talos.runtime.context;

import java.util.List;

public final class ProposalContextTestSupport {
    private ProposalContextTestSupport() {}

    public static ActiveTaskContext accepted(
            int turn, String trace, List<String> targets, String summary) {
        ActiveTaskContext context = ActiveTaskContext.proposedChanges(turn, trace, targets, summary);
        return context.withProposalProvenance(ProposalProvenance.accepted(
                context, "Propose changes to " + String.join(", ", targets) + ".", summary));
    }
}
