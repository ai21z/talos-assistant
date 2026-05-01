package dev.talos.runtime;

import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActiveTaskContextUpdateListenerTest {

    @Test
    void completedTurnUpdatesSessionMemoryActiveContextAndArtifactGoal() {
        SessionMemory memory = new SessionMemory();
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(memory);

        TurnResult result = new TurnResult(
                new Result.Ok("I would add setup steps to README.md."),
                null,
                3,
                Duration.ofMillis(25),
                new TurnAudit(
                        List.of(),
                        0,
                        0,
                        0,
                        new TurnPolicyTrace(
                                "READ_ONLY_QA",
                                false,
                                false,
                                List.of("README.md"),
                                List.of(),
                                "INSPECT",
                                "INSPECT",
                                List.of(),
                                List.of(),
                                List.of()),
                        LocalTurnTrace.builder("trace-listener", "session", 3, "2026-05-01T00:00:00Z")
                                .taskContract(new LocalTurnTrace.TaskContractSummary(
                                        "READ_ONLY_QA",
                                        false,
                                        false,
                                        false,
                                        List.of("README.md"),
                                        List.of()))
                                .outcome("ADVISORY_ONLY", "NOT_RUN", "NONE", "NOT_REQUESTED", "ADVISORY_ONLY")
                                .build()));

        listener.onTurnComplete(result, "Propose README.md changes without editing.");

        assertEquals(ActiveTaskContext.State.ACTIVE, memory.activeTaskContext().state());
        assertEquals(ActiveTaskContext.Kind.PROPOSED_CHANGES, memory.activeTaskContext().kind());
        assertEquals(List.of("README.md"), memory.activeTaskContext().targets());
        assertEquals(ArtifactGoal.Source.ACTIVE_CONTEXT, memory.artifactGoal().source());
        assertEquals(ArtifactGoal.ArtifactKind.README, memory.artifactGoal().artifactKind());
    }

    @Test
    void nullMemoryIsIgnored() {
        ActiveTaskContextUpdateListener listener = new ActiveTaskContextUpdateListener(null);

        assertDoesNotThrow(() -> listener.onTurnComplete(null, "anything"));
    }
}
