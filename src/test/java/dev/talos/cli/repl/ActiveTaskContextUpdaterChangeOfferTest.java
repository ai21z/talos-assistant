package dev.talos.cli.repl;

import dev.talos.runtime.Result;
import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.context.ChangeOffer;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A change offer is bound only from runtime facts of the turn that rendered it (MICA decision D6, P3b). */
class ActiveTaskContextUpdaterChangeOfferTest {
    private static final List<String> SITE = List.of("index.html", "style.css", "script.js");
    private static final String REQUEST = "a dark synthwave page is more appropriate. Can you help me?";
    private static final String OFFERED = ChangeOffer.appendTo("A darker palette would suit it.", SITE);
    private final ActiveTaskContextUpdater updater = new ActiveTaskContextUpdater();

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void theRenderedOfferBindsAProposalOfTheUsersRequestKeepingTheTaskRequirements() {
        ActiveTaskContext previous = verified();

        var update = updater.updateAfterTurn(turn(OFFERED, false, true, 2), REQUEST, previous,
                ArtifactGoal.fromActiveContext(previous));

        ActiveTaskContext context = update.activeTaskContext();
        assertTrue(context.hasBoundChangeOffer(), context.toString());
        assertFalse(context.hasBoundProposal());
        assertEquals(SITE, context.targets());
        assertEquals(2, context.sourceTurnNumber());
        assertTrue(context.proposalSummary().startsWith("Requested change: "), context.proposalSummary());
        assertTrue(context.proposalSummary().contains("synthwave"), context.proposalSummary());
    }

    @Test
    void theOfferedContextKeepsTheRequirementsOfTheTaskItCameFrom() {
        var requirements = new dev.talos.runtime.task.StaticWebRequirements(
                List.of("Synthwave Night"), java.util.Set.of("vendor.js"));
        ActiveTaskContext previous = ActiveTaskContext.verifiedMutation(1, "trc-1", SITE, "VERIFIED", requirements);

        var update = updater.updateAfterTurn(turn(OFFERED, false, true, 2), REQUEST, previous,
                ArtifactGoal.fromActiveContext(previous));

        assertTrue(update.activeTaskContext().hasBoundChangeOffer());
        assertEquals(requirements, update.activeTaskContext().staticWebRequirements());
    }

    @Test
    void eachMissingRuntimeFactLeavesThePreviousContext() {
        ActiveTaskContext previous = verified();
        ArtifactGoal goal = ArtifactGoal.fromActiveContext(previous);

        assertFalse(updater.updateAfterTurn(turn(OFFERED, false, false, 2), REQUEST, previous, goal)
                .activeTaskContext().hasBoundChangeOffer(), "no runtime offer event");
        assertFalse(updater.updateAfterTurn(turn("A darker palette would suit it.", false, true, 2), REQUEST,
                previous, goal).activeTaskContext().hasBoundChangeOffer(), "answer without the line");
        assertFalse(updater.updateAfterTurn(turn(OFFERED, true, true, 2), REQUEST, previous, goal)
                .activeTaskContext().hasBoundChangeOffer(), "a mutating turn");
        assertFalse(updater.updateAfterTurn(turn(OFFERED, false, true, 2), "what is index.html for?",
                previous, goal).activeTaskContext().hasBoundChangeOffer(), "not an indirect request");
        assertFalse(updater.updateAfterTurn(turn(OFFERED, false, true, 2), REQUEST,
                previous.suppressed("test"), goal).activeTaskContext().hasBoundChangeOffer(), "inactive context");
        assertFalse(updater.updateAfterTurn(turnWithTraceTurn(OFFERED, 2, 5), REQUEST, previous, goal)
                .activeTaskContext().hasBoundChangeOffer(), "trace of another turn");
    }

    private static ActiveTaskContext verified() {
        return ActiveTaskContext.verifiedMutation(1, "trc-1", SITE, "VERIFIED");
    }

    private static TurnResult turn(String answer, boolean mutationAllowed, boolean offerEvent, int turnNumber) {
        return result(answer, mutationAllowed, offerEvent, turnNumber, turnNumber);
    }

    private static TurnResult turnWithTraceTurn(String answer, int turnNumber, int traceTurn) {
        return result(answer, false, true, turnNumber, traceTurn);
    }

    private static TurnResult result(
            String answer, boolean mutationAllowed, boolean offerEvent, int turnNumber, int traceTurn) {
        LocalTurnTraceCapture.clear();
        LocalTurnTraceCapture.begin("trc-offer", "session", traceTurn, "2026-10-27T00:00:00Z", "workspace-hash",
                "test", "llama_cpp", "test-model", REQUEST);
        if (offerEvent) LocalTurnTraceCapture.recordChangeOffer(SITE);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnPolicyTrace policy = new TurnPolicyTrace(
                mutationAllowed ? "FILE_EDIT" : "READ_ONLY_QA", mutationAllowed, false, List.of(), List.of(),
                mutationAllowed ? "APPLY" : "INSPECT", mutationAllowed ? "APPLY" : "INSPECT",
                List.of(), List.of(), List.of());
        return new TurnResult(new Result.Ok(answer), null, turnNumber, Duration.ofMillis(25),
                new TurnAudit(List.of(), 0, 0, 0, policy, trace));
    }
}
