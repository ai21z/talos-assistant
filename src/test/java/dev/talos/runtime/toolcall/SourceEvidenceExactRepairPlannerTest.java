package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SourceEvidenceExactRepairPlannerTest {
    @TempDir
    Path workspace;

    @Test
    void planBuildsWriteOnlySourceEvidenceRepairFrame() {
        String request = sourceEvidenceRequest();
        LoopState state = sourceEvidenceState(request);
        addSourceReadbacks(state);
        state.toolOutcomes.add(failedSourceEvidenceWrite("office-summary.md"));

        Optional<SourceEvidenceExactRepairPlanner.Plan> plan =
                SourceEvidenceExactRepairPlanner.nextPlan(state, baseTools(), request);

        assertTrue(plan.isPresent(), "failed source-derived write should produce a compact exact-evidence plan");
        SourceEvidenceExactRepairPlanner.Plan repair = plan.get();
        assertEquals("office-summary.md", repair.path());
        assertRepairKeyContainsSources(repair.key(),
                "board-brief.md",
                "client-notes.md",
                "revenue.csv");
        assertEquals(List.of("talos.write_file"), toolNames(repair.tools()));
        assertEquals(ToolChoiceMode.REQUIRED, repair.controls().toolChoice());
        assertEquals(List.of("pending-action-obligation", "source-evidence-exact-compact-repair"),
                repair.controls().debugTags());

        String schema = schemaFor(repair.tools(), "talos.write_file");
        assertTrue(schema.contains("\"enum\":[\"office-summary.md\"]"), schema);
        assertTrue(schema.contains("Board brief marker: ORBITAL-DECK-71."), schema);
        assertTrue(schema.contains("Client note marker: NEON-RESPONSE-44."), schema);
        assertTrue(schema.contains("Revenue marker: LASER-LEDGER-19"), schema);

        String prompt = prompt(repair.messages());
        assertTrue(prompt.contains("[SourceEvidenceExactRepair] Target: office-summary.md"), prompt);
        assertTrue(prompt.contains("Previous write was rejected before approval"), prompt);
        assertTrue(prompt.contains("Required exact source evidence phrases:"), prompt);
        assertTrue(prompt.contains("board-brief.md: `Board brief marker: ORBITAL-DECK-71.`"), prompt);
        assertTrue(prompt.contains("client-notes.md: `Client note marker: NEON-RESPONSE-44.`"), prompt);
        assertTrue(prompt.contains("revenue.csv: `Revenue marker: LASER-LEDGER-19`"), prompt);
        assertTrue(prompt.contains(request), prompt);
        assertFalse(prompt.contains("Older unrelated source task"), prompt);
        assertFalse(prompt.contains("Stale prior source answer"), prompt);
    }

    @Test
    void planDoesNotRunForFailedWriteOutsideRemainingExpectedTarget() {
        String request = sourceEvidenceRequest();
        LoopState state = sourceEvidenceState(request);
        addSourceReadbacks(state);
        state.toolOutcomes.add(failedSourceEvidenceWrite("wrong-summary.md"));

        Optional<SourceEvidenceExactRepairPlanner.Plan> plan =
                SourceEvidenceExactRepairPlanner.nextPlan(state, baseTools(), request);

        assertTrue(plan.isEmpty(), "source-evidence repair must stay scoped to remaining expected targets");
    }

    @Test
    void planDoesNotRunAfterPromptedRepairKey() {
        String request = sourceEvidenceRequest();
        LoopState state = sourceEvidenceState(request);
        addSourceReadbacks(state);
        state.toolOutcomes.add(failedSourceEvidenceWrite("office-summary.md"));
        SourceEvidenceExactRepairPlanner.Plan firstPlan =
                SourceEvidenceExactRepairPlanner.nextPlan(state, baseTools(), request).orElseThrow();
        state.sourceEvidenceExactRepairPromptedKeys.add(firstPlan.key());

        Optional<SourceEvidenceExactRepairPlanner.Plan> plan =
                SourceEvidenceExactRepairPlanner.nextPlan(state, baseTools(), request);

        assertTrue(plan.isEmpty(), "already prompted source-evidence repair keys must not reprompt");
    }

    @Test
    void sourceEvidenceDecisionDelegatesSourceEvidenceExactRepairPlanningToOwner() throws Exception {
        String stageSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String decisionSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptSourceEvidenceRepairDecision.java"));

        assertFalse(stageSource.contains("SourceEvidenceExactRepairPlanner.nextPlan"), stageSource);
        assertTrue(decisionSource.contains("SourceEvidenceExactRepairPlanner.nextPlan"), decisionSource);
        assertFalse(stageSource.contains("private static Optional<SourceEvidenceExactRepair> "
                + "nextSourceEvidenceExactRepair"), stageSource);
        assertFalse(stageSource.contains("private static List<ToolSpec> sourceEvidenceExactRepairToolSpecs"),
                stageSource);
        assertFalse(stageSource.contains("private static List<ChatMessage> sourceEvidenceExactRepairMessages"),
                stageSource);
    }

    private LoopState sourceEvidenceState(String request) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys large-system-token"),
                ChatMessage.user("Older unrelated source task that must not enter compact repair."),
                ChatMessage.assistant("Stale prior source answer that must not enter compact repair."),
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

    private static void addSourceReadbacks(LoopState state) {
        state.toolOutcomes.add(readOutcome("board-brief.md"));
        state.toolOutcomes.add(readOutcome("client-notes.md"));
        state.toolOutcomes.add(readOutcome("revenue.csv"));
        state.successfulReadCallBodies.put(
                "talos.read_file:path=board-brief.md;",
                "1 | Board brief marker: ORBITAL-DECK-71.");
        state.successfulReadCallBodies.put(
                "talos.read_file:path=client-notes.md;",
                "1 | Client note marker: NEON-RESPONSE-44.");
        state.successfulReadCallBodies.put(
                "talos.read_file:path=revenue.csv;",
                "1 | Revenue marker: LASER-LEDGER-19");
    }

    private static ToolCallLoop.ToolOutcome readOutcome(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "Read " + path,
                "");
    }

    private static ToolCallLoop.ToolOutcome failedSourceEvidenceWrite(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "Source-derived write blocked before approval: " + path
                        + " does not include required exact evidence phrase(s).");
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }

    private static List<String> toolNames(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
    }

    private static void assertRepairKeyContainsSources(String key, String... sources) {
        assertTrue(key.startsWith("office-summary.md->"), key);
        for (String source : sources) {
            assertTrue(key.contains(source), key);
        }
    }

    private static String schemaFor(List<ToolSpec> specs, String toolName) {
        return specs.stream()
                .filter(spec -> toolName.equals(spec.name()))
                .findFirst()
                .map(ToolSpec::parametersSchemaJson)
                .orElse("");
    }

    private static String prompt(List<ChatMessage> messages) {
        return messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
