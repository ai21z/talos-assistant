package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import dev.talos.runtime.ToolCallLoop;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptSuccessfulMutationDecisionTest {

    @Test
    void ownsSuccessfulMutationContinuationMechanics() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptSuccessfulMutationDecision.java"));

        assertTrue(source.contains("StaticWebContinuationPlanner.staticWebVerificationAlreadyPasses"), source);
        assertTrue(source.contains("StaticWebContinuationPlanner.nextPlan("), source);
        assertTrue(source.contains("StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets"), source);
        assertTrue(source.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"), source);
        assertTrue(source.contains("P0: skipping re-prompt"), source);
    }

    @Test
    void allSuccessfulMutationWithoutRemainingTargetsStopsWithMutationSummaries() {
        LoopState state = state();
        state.toolOutcomes.add(successfulMutation("talos.write_file", "README.md"));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                1,
                List.of("Updated README.md"),
                0,
                false,
                false,
                false,
                1);

        Optional<Boolean> decision = ToolRepromptSuccessfulMutationDecision.tryHandle(state, outcome);

        assertTrue(decision.isPresent());
        assertFalse(decision.get());
        assertEquals("Updated README.md", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void noSuccessfulMutationReturnsEmptyDecision() {
        LoopState state = state();
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0,
                List.of(),
                0,
                false,
                false,
                false,
                1);

        Optional<Boolean> decision = ToolRepromptSuccessfulMutationDecision.tryHandle(state, outcome);

        assertTrue(decision.isEmpty());
    }

    @Test
    void partialSuccessReturnsEmptyDecisionForStageFallThrough() {
        LoopState state = state();
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                1,
                List.of("Updated README.md"),
                1,
                false,
                false,
                false,
                2);

        Optional<Boolean> decision = ToolRepromptSuccessfulMutationDecision.tryHandle(state, outcome);

        assertTrue(decision.isEmpty());
    }

    private static LoopState state() {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Update README.md."))),
                Path.of("."),
                null,
                null,
                10,
                0);
    }

    private static ToolCallLoop.ToolOutcome successfulMutation(String toolName, String pathHint) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                true,
                true,
                false,
                "mutation applied",
                "");
    }
}
