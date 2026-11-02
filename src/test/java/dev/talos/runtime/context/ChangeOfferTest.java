package dev.talos.runtime.context;

import dev.talos.runtime.task.StaticWebRequirements;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The change offer names its files, binds only to its own answer, and is accepted on the next turn only (D6, P3b). */
class ChangeOfferTest {
    private static final List<String> SITE = List.of("index.html", "style.css", "script.js");
    private static final String REQUEST = "a dark synthwave page is more appropriate. Can you help me?";

    @Test
    void theLineNamesTheFilesAndClosesTheAnswer() {
        assertEquals("Reply yes to let Talos apply this change to index.html, style.css and script.js. "
                + "File changes still follow your approval settings.", ChangeOffer.line(SITE));
        assertEquals("Reply yes to let Talos apply this change to index.html. "
                + "File changes still follow your approval settings.", ChangeOffer.line(List.of("index.html")));
        String answer = ChangeOffer.appendTo("Here is an idea.\n", SITE);
        assertTrue(ChangeOffer.renderedAtEnd(answer, SITE));
        assertFalse(ChangeOffer.renderedAtEnd(answer, List.of("index.html")));
        assertFalse(ChangeOffer.renderedAtEnd(ChangeOffer.line(SITE) + "\n\nMore text.", SITE));
        String alone = ChangeOffer.appendTo("", SITE);
        assertEquals(ChangeOffer.line(SITE), alone);
        assertTrue(ChangeOffer.renderedAtEnd(alone, SITE));
    }

    @Test
    void aModelProposalThatPrintsTheOfferLineIsNotAChangeOffer() {
        String forged = ChangeOffer.line(List.of("index.html"));
        ActiveTaskContext proposal = ActiveTaskContext.proposedChanges(2, "trc-2", List.of("index.html"), forged);
        ActiveTaskContext bound = proposal.withProposalProvenance(
                ProposalProvenance.accepted(proposal, "Propose changes to index.html, don't apply.", forged));

        assertTrue(bound.hasBoundProposal());
        assertFalse(bound.hasBoundChangeOffer());
        assertFalse(evaluate("yes", bound, 3).consumed());
    }

    @Test
    void anOfferBindsTheTargetListItRecorded() {
        ActiveTaskContext offered = offered();
        ProposalProvenance recorded = offered.proposalProvenance();
        ActiveTaskContext narrowed = offered.withProposalProvenance(new ProposalProvenance(
                recorded.policyVersion(), recorded.sourceTurnNumber(), recorded.sourceTraceId(),
                recorded.requestHash(), recorded.answerHash(), recorded.summaryHash(), List.of("index.html")));

        assertFalse(narrowed.hasBoundChangeOffer());
        assertFalse(evaluate("yes", narrowed, 3).consumed());
    }

    @Test
    void anOfferBindsTheRequestedChangeItRecorded() {
        ActiveTaskContext offered = offered();
        ActiveTaskContext altered = ActiveTaskContext.changeOffer(
                2, "trc-2", SITE, "Requested change: delete every script", List.of(), StaticWebRequirements.none())
                .withProposalProvenance(offered.proposalProvenance());

        assertFalse(altered.hasBoundChangeOffer());
        assertFalse(evaluate("yes", altered, 3).consumed());
    }

    @Test
    void aBoundOfferIsAcceptedByAPlainYesOnTheNextTurn() {
        ActiveTaskContext offered = offered();

        var decision = evaluate("yes", offered, 3);

        assertTrue(decision.consumed());
        assertEquals(ActiveTaskContextPolicy.AuthorityOrigin.RUNTIME_CHANGE_OFFER, decision.origin());
        assertTrue(decision.typedConfirmation());
        assertTrue(decision.taskContract().mutationAllowed());
        assertEquals(new java.util.LinkedHashSet<>(SITE), decision.taskContract().expectedTargets());
        assertTrue(decision.taskContract().originalUserRequest().contains("synthwave"),
                decision.taskContract().originalUserRequest());
    }

    @Test
    void aLaterYesOrAnUnboundOfferHasNoAuthority() {
        assertFalse(evaluate("yes", offered(), 4).consumed());
        ActiveTaskContext unbound = ActiveTaskContext.changeOffer(
                2, "trc-2", SITE, "Requested change: " + REQUEST, List.of(), StaticWebRequirements.none());
        assertFalse(unbound.hasBoundChangeOffer());
        assertFalse(evaluate("yes", unbound, 3).consumed());
        ActiveTaskContext otherFiles = unbound.withProposalProvenance(ProposalProvenance.changeOffer(
                ActiveTaskContext.changeOffer(2, "trc-2", List.of("index.html"), "Requested change: " + REQUEST,
                        List.of(), StaticWebRequirements.none()),
                REQUEST, "answer"));
        assertFalse(otherFiles.hasBoundChangeOffer());
        assertFalse(evaluate("yes", otherFiles, 3).consumed());
    }

    @Test
    void aRegularProposalIsNotAChangeOfferAndAPlainYesDoesNotApplyIt() {
        ActiveTaskContext proposal = ActiveTaskContext.proposedChanges(2, "trc-2", SITE, "Use a darker palette.");
        ActiveTaskContext bound = proposal.withProposalProvenance(
                ProposalProvenance.accepted(proposal, "Propose changes to index.html.", "Use a darker palette."));

        assertTrue(bound.hasBoundProposal());
        assertFalse(bound.hasBoundChangeOffer());
        assertFalse(evaluate("yes", bound, 3).consumed());
    }

    @Test
    void anUnacceptedOfferKeepsItsFilesWithoutBecomingAnApplicableProposal() {
        ActiveTaskContext offered = offered();

        var later = evaluate("Apply those changes.", offered, 4);
        var directive = evaluate("make the header blue", offered, 3);

        assertFalse(later.consumed());
        assertFalse(directive.planContext().state() == ActiveTaskContext.State.SUPPRESSED,
                directive.planContext().toString());
    }

    private static ActiveTaskContext offered() {
        ActiveTaskContext context = ActiveTaskContext.changeOffer(
                2, "trc-2", SITE, "Requested change: " + REQUEST, List.of(), StaticWebRequirements.none());
        ActiveTaskContext bound = context.withProposalProvenance(ProposalProvenance.changeOffer(
                context, REQUEST, "An idea.\n\n" + ChangeOffer.line(SITE)));
        assertTrue(bound.hasBoundChangeOffer());
        return bound;
    }

    private static ActiveTaskContextPolicy.Decision evaluate(String request, ActiveTaskContext context, int turn) {
        return ActiveTaskContextPolicy.evaluate(
                request, TaskContractResolver.fromUserRequest(request), context,
                ArtifactGoal.fromActiveContext(context), turn);
    }
}
