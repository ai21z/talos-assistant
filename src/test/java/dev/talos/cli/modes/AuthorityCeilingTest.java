package dev.talos.cli.modes;

import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ActiveTaskContextPolicy;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The turn's one authority ceiling, exercised directly. The decisions here are synthetic: they stand for any
 * carrier that would consume context without a directive, which the policy and resolver no longer produce.
 */
class AuthorityCeilingTest {
    private static final ActiveTaskContext SAVED = ActiveTaskContext.verifierFindings(
            1, "trace-ceiling", List.of("index.html"), List.of("index.html is incomplete"), "FAILED");
    private static final TaskContract CARRIED = new TaskContract(
            TaskType.FILE_EDIT, true, true, true, Set.of("index.html"), Set.of(), "carried", "active-static-web-context");

    @Test
    void aConsumedContextWithoutADirectiveIsResolvedFromTheCurrentMessage() {
        TaskContract current = TaskContractResolver.fromUserRequest("the page looks better");

        ActiveTaskContextPolicy.Decision kept = AssistantTurnPreparation.withinAuthorityCeiling(
                "the page looks better", current, consumed(ActiveTaskContextPolicy.AuthorityOrigin.CARRIED_DIRECTIVE),
                false);

        assertFalse(kept.consumed());
        assertSame(current, kept.taskContract());
        assertEquals(SAVED, kept.memoryContext());
        assertEquals(ActiveTaskContext.State.NONE, kept.planContext().state());
    }

    @Test
    void aDirectiveTypedConfirmationOrTypedContinuationKeepsTheDecision() {
        TaskContract current = TaskContractResolver.fromUserRequest("yes");
        ActiveTaskContextPolicy.Decision directive = consumed(ActiveTaskContextPolicy.AuthorityOrigin.CARRIED_DIRECTIVE);
        ActiveTaskContextPolicy.Decision offer = consumed(ActiveTaskContextPolicy.AuthorityOrigin.RUNTIME_REPAIR_OFFER);

        assertSame(directive, AssistantTurnPreparation.withinAuthorityCeiling(
                "make it better", TaskContractResolver.fromUserRequest("make it better"), directive, false));
        assertSame(offer, AssistantTurnPreparation.withinAuthorityCeiling("yes", current, offer, false));
        assertSame(directive, AssistantTurnPreparation.withinAuthorityCeiling("yes", current, directive, true));
    }

    private static ActiveTaskContextPolicy.Decision consumed(ActiveTaskContextPolicy.AuthorityOrigin origin) {
        return new ActiveTaskContextPolicy.Decision(
                CARRIED, SAVED, ArtifactGoal.fromActiveContext(SAVED), SAVED, true, origin);
    }
}
