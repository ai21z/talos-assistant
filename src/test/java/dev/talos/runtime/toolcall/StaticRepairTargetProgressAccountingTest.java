package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticRepairTargetProgressAccountingTest {

    @Test
    void remainingFullRewriteRepairTargetsSubtractsSuccessfulMutations() {
        LoopState state = stateWithRepairContext("styles.css, assets/index.html, scripts.js");
        state.toolOutcomes.add(outcome("talos.write_file", "assets\\index.html", true, true));
        state.toolOutcomes.add(outcome("talos.read_file", "scripts.js", true, false));
        state.toolOutcomes.add(outcome("talos.write_file", "styles.css", false, true));

        assertEquals(
                List.of("scripts.js", "styles.css"),
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state));
    }

    @Test
    void remainingFullRewriteRepairTargetsIncludesRuntimeRequiredTargetsWithoutRenderedContext() {
        LoopState state = emptyState();
        state.staticWebFullRewriteRequiredTargets.add("scripts.js");
        state.staticWebFullRewriteRequiredTargets.add("index.html");
        state.toolOutcomes.add(outcome("talos.write_file", "scripts.js", true, true));

        assertEquals(
                List.of("index.html"),
                StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state));
        assertFalse(StaticRepairTargetProgressAccounting.hasStaticRepairContext(state));
    }

    @Test
    void hasStaticRepairContextRequiresRenderedFullRewriteTargets() {
        LoopState state = stateWithRepairContext("index.html, styles.css");

        assertTrue(StaticRepairTargetProgressAccounting.hasStaticRepairContext(state));
        assertFalse(StaticRepairTargetProgressAccounting.hasStaticRepairContext(emptyState()));
        assertFalse(StaticRepairTargetProgressAccounting.hasStaticRepairContext(null));
    }

    private static LoopState stateWithRepairContext(String targets) {
        LoopState state = emptyState();
        state.messages.add(ChatMessage.system("""
                [Static verification repair context]
                Previous static verification problems:
                - Static verification failed.
                Full-file replacement targets: %s
                """.formatted(targets)));
        return state;
    }

    private static LoopState emptyState() {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(),
                Path.of("."),
                null,
                null,
                10,
                0);
    }

    private static ToolCallLoop.ToolOutcome outcome(
            String toolName,
            String pathHint,
            boolean success,
            boolean mutating
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                success,
                mutating,
                false,
                "summary",
                "");
    }
}
