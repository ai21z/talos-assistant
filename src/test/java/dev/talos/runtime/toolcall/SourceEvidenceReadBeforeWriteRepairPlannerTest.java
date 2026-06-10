package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.SamplingControls;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T741: the read-before-write repair pins talos.read_file via NAMED tool choice. */
class SourceEvidenceReadBeforeWriteRepairPlannerTest {
    @TempDir
    Path workspace;

    @Test
    void repairPlanPinsReadFileViaNamedToolChoiceWithNearGreedySampling() {
        String request = sourceEvidenceRequest();
        LoopState state = sourceEvidenceState(request);
        state.toolOutcomes.add(blockedWriteBeforeRead("office-summary.md"));

        Optional<SourceEvidenceReadBeforeWriteRepairPlanner.Plan> plan =
                SourceEvidenceReadBeforeWriteRepairPlanner.nextPlan(state, baseTools(), request);

        assertTrue(plan.isPresent(), "blocked write-before-read should produce a read repair plan");
        SourceEvidenceReadBeforeWriteRepairPlanner.Plan repair = plan.get();
        assertEquals(ToolChoiceMode.NAMED, repair.controls().toolChoice());
        assertEquals("talos.read_file", repair.controls().namedTool());
        assertEquals(SamplingControls.NEAR_GREEDY, repair.controls().sampling());
        assertEquals(List.of("source-evidence-read-before-write-repair"),
                repair.controls().debugTags());
        assertTrue(repair.missingSourceTargets().contains("board-brief.md"),
                String.join(",", repair.missingSourceTargets()));
    }

    private LoopState sourceEvidenceState(String request) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        var llm = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of())),
                16_384).client();
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm)
                .nativeToolSpecs(baseTools())
                .build();
        return new LoopState(
                "",
                List.of(),
                messages,
                workspace,
                ctx,
                null,
                10,
                0);
    }

    private static String sourceEvidenceRequest() {
        return "Create office-summary.md summarizing board-brief.md, client-notes.md, and revenue.csv. "
                + "Include one distinctive exact evidence phrase from each source so I can audit source coverage.";
    }

    private static ToolCallLoop.ToolOutcome blockedWriteBeforeRead(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "Source-derived artifact write blocked before approval: " + path
                        + " requires reading source target(s) board-brief.md, client-notes.md, revenue.csv first. "
                        + "Call talos.read_file for the missing source target(s).");
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }
}
