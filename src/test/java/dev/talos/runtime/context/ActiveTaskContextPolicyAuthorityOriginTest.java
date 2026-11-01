package dev.talos.runtime.context;

import dev.talos.runtime.task.StaticWebRequirements;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The active-context policy consumes carried context only for a directive clause or a typed confirmation. */
class ActiveTaskContextPolicyAuthorityOriginTest {
    private static final ActiveTaskContext FAILED_SITE = ActiveTaskContext.verifierFindings(
            1,
            "trace-turn-13",
            List.of("index.html", "script.js", "style.css"),
            List.of("style.css: expected target was not successfully mutated."),
            "FAILED",
            List.of(),
            StaticWebRequirements.none());
    private static final String FAILED_ANSWER =
            "[Task incomplete: Static verification failed - style.css: expected target was not successfully mutated.]";

    @ParameterizedTest
    @ValueSource(strings = {
            "I fixed it myself",
            "add a prefix to the title",
            "The page looks better this time.",
            "does it look better now?",
            "it is still bad",
            "yes",
            "do you think it looks better now?",
            "could you check if it looks better?",
            "review the remaining problems",
            "give me a list of the remaining issues",
            "do you think it still needs a fix?"
    })
    void cuesOutsideADirectiveClauseDoNotConsumeTheContext(String message) {
        ActiveTaskContextPolicy.Decision decision = evaluate(message, FAILED_SITE);

        assertFalse(decision.consumed(), message);
        assertEquals(ActiveTaskContextPolicy.AuthorityOrigin.CURRENT_MESSAGE, decision.origin(), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"make it look better", "fix the remaining problems", "please try again"})
    void aDirectiveClauseBindsTheCarriedReferent(String message) {
        ActiveTaskContextPolicy.Decision decision = evaluate(message, FAILED_SITE);

        assertTrue(decision.consumed(), message);
        assertEquals(ActiveTaskContextPolicy.AuthorityOrigin.CARRIED_DIRECTIVE, decision.origin(), message);
        assertFalse(decision.typedConfirmation(), message);
    }

    @Test
    void anAffirmationBindsOnlyARenderedRuntimeOffer() {
        ActiveTaskContext offered = FAILED_SITE.withRepairOffer(FAILED_ANSWER + "\n\n" + RepairOffer.LINE);

        ActiveTaskContextPolicy.Decision decision = evaluate("yes", offered);

        assertTrue(decision.consumed());
        assertEquals(ActiveTaskContextPolicy.AuthorityOrigin.RUNTIME_REPAIR_OFFER, decision.origin());
        assertTrue(decision.typedConfirmation());
        assertTrue(decision.taskContract().mutationAllowed());
    }

    @Test
    void anOfferIsAcceptedOnlyOnTheTurnRightAfterIt() {
        ActiveTaskContext offered = FAILED_SITE.withRepairOffer(FAILED_ANSWER + "\n\n" + RepairOffer.LINE);

        ActiveTaskContextPolicy.Decision stale = ActiveTaskContextPolicy.evaluate(
                "yes", TaskContractResolver.fromUserRequest("yes"), offered, ArtifactGoal.fromActiveContext(offered), 3);

        assertFalse(stale.consumed());
        assertFalse(stale.taskContract().mutationAllowed());
    }

    @Test
    void anOfferIsNotBoundToAnotherKindOfContext() {
        ActiveTaskContext pending = ActiveTaskContext.pendingMutation(
                1, "trace-pending", List.of("index.html"), "No required static-web mutation completed.",
                StaticWebRequirements.none());

        assertFalse(pending.withRepairOffer(FAILED_ANSWER + "\n\n" + RepairOffer.LINE).hasBoundRepairOffer());
    }

    private static ActiveTaskContextPolicy.Decision evaluate(String message, ActiveTaskContext saved) {
        return ActiveTaskContextPolicy.evaluate(
                message,
                TaskContractResolver.fromUserRequest(message),
                saved,
                ArtifactGoal.fromActiveContext(saved),
                2);
    }
}
