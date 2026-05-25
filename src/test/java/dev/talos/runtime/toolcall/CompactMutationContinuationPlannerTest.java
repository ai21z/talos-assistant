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

class CompactMutationContinuationPlannerTest {
    @TempDir
    Path workspace;

    @Test
    void planBuildsCompactMutationFrameWithoutConversationHistory() {
        String request = "Rewrite README.md with a short project note.";
        LoopState state = state(request);
        state.toolOutcomes.add(readOutcome("README.md"));
        state.successfulReadCallBodies.put(
                "talos.read_file:path=readme.md;",
                "1 | # Old\n2 | Existing README content.");

        Optional<CompactMutationContinuationPlanner.Plan> plan =
                CompactMutationContinuationPlanner.planForContextBudget(
                        state,
                        baseTools(),
                        "tool-call loop continuation");

        assertTrue(plan.isPresent(), "read-only progress on a mutation target should produce a compact plan");
        CompactMutationContinuationPlanner.Plan compact = plan.get();
        assertEquals(List.of("talos.write_file", "talos.edit_file"), toolNames(compact.tools()));
        assertEquals(ToolChoiceMode.REQUIRED, compact.controls().toolChoice());
        assertEquals(List.of("compact-mutation-continuation"), compact.controls().debugTags());
        assertTrue(schemaFor(compact.tools(), "talos.write_file").contains("\"content\""));
        assertTrue(schemaFor(compact.tools(), "talos.edit_file").contains("\"old_string\""));

        String prompt = prompt(compact.messages());
        assertTrue(prompt.contains("[CompactMutationContinuation]"), prompt);
        assertTrue(prompt.contains("README.md"), prompt);
        assertTrue(prompt.contains("Existing README content"), prompt);
        assertTrue(prompt.contains(request), prompt);
        assertFalse(prompt.contains("Older unrelated turn"), prompt);
        assertFalse(prompt.contains("Older unrelated answer"), prompt);
    }

    @Test
    void planIncludesSourceEvidenceReadbacksForSourceDerivedWrite() {
        String request = "Create office-summary.md summarizing board-brief.md and client-notes.md. "
                + "Include one distinctive exact evidence phrase from each source so I can audit source coverage.";
        LoopState state = state(request);
        state.toolOutcomes.add(readOutcome("board-brief.md"));
        state.toolOutcomes.add(readOutcome("client-notes.md"));
        state.successfulReadCallBodies.put(
                "talos.read_file:path=board-brief.md;",
                "1 | Board brief marker: ORBITAL-DECK-71.");
        state.successfulReadCallBodies.put(
                "talos.read_file:path=client-notes.md;",
                "1 | Client note marker: NEON-RESPONSE-44.");

        Optional<CompactMutationContinuationPlanner.Plan> plan =
                CompactMutationContinuationPlanner.planForContextBudget(
                        state,
                        baseTools(),
                        "tool-call loop continuation");

        assertTrue(plan.isPresent(), "source-derived write should keep exact source evidence in compact frame");
        String prompt = prompt(plan.get().messages());
        assertTrue(prompt.contains("[RequiredSourceEvidence]"), prompt);
        assertTrue(prompt.contains("office-summary.md"), prompt);
        assertTrue(prompt.contains("board-brief.md: include exact phrase `Board brief marker: ORBITAL-DECK-71.`"),
                prompt);
        assertTrue(prompt.contains("client-notes.md: include exact phrase `Client note marker: NEON-RESPONSE-44.`"),
                prompt);
        assertTrue(prompt.contains("[SourceEvidenceReadbacks]"), prompt);
    }

    @Test
    void planIncludesSimilarSiblingReadbackForTargetTrap() {
        String request = "Create a complete static BMI calculator in this folder with index.html, styles.css, "
                + "and scripts.js. It should calculate BMI from height and weight.";
        LoopState state = state(request);
        state.toolOutcomes.add(readOutcome("index.html"));
        state.toolOutcomes.add(readOutcome("script.js"));
        state.successfulReadCallBodies.put(
                "talos.read_file:path=index.html;",
                "1 | <html><script src=\"scripts.js\"></script></html>");
        state.successfulReadCallBodies.put(
                "talos.read_file:path=script.js;",
                "1 | console.log('similar wrong target');");

        Optional<CompactMutationContinuationPlanner.Plan> plan =
                CompactMutationContinuationPlanner.planForContextBudget(
                        state,
                        baseTools(),
                        "tool-call loop continuation");

        assertTrue(plan.isPresent(), "similar sibling readback should stay available for target disambiguation");
        String prompt = prompt(plan.get().messages());
        assertTrue(prompt.contains("script.js and scripts.js are different target paths"), prompt);
        assertTrue(prompt.contains("Path: script.js"), prompt);
        assertTrue(prompt.contains("similar wrong target"), prompt);
        assertTrue(prompt.contains("Cross-file coherence checklist"), prompt);
    }

    @Test
    void planDoesNotRunAfterMutationProgressOrPendingObligation() {
        LoopState alreadyMutated = state("Rewrite README.md with a short project note.");
        alreadyMutated.toolOutcomes.add(readOutcome("README.md"));
        alreadyMutated.mutationSinceStart = true;

        assertTrue(CompactMutationContinuationPlanner
                .planForContextBudget(alreadyMutated, baseTools(), "tool-call loop continuation")
                .isEmpty());

        LoopState pending = state("Rewrite README.md with a short project note.");
        pending.toolOutcomes.add(readOutcome("README.md"));
        pending.setPendingActionObligation(
                PendingActionObligation.expectedTargetScopeTargets(List.of("README.md")));

        assertTrue(CompactMutationContinuationPlanner
                .planForContextBudget(pending, baseTools(), "tool-call loop continuation")
                .isEmpty());
    }

    @Test
    void repromptStageDelegatesCompactMutationPlanningToOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("CompactMutationContinuationPlanner.planForContextBudget"), source);
        assertFalse(source.contains("private static Optional<CompactMutationContinuation> "
                + "compactMutationContinuationForContextBudget"), source);
        assertFalse(source.contains("private static List<ChatMessage> compactMutationContinuationMessages"), source);
        assertFalse(source.contains("private static List<ToolSpec> compactMutationContinuationToolSpecs"), source);
    }

    private LoopState state(String request) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys large-system-token"),
                ChatMessage.user("Older unrelated turn that must not enter compact mutation continuation."),
                ChatMessage.assistant("Older unrelated answer that must not enter compact mutation continuation."),
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

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }

    private static List<String> toolNames(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
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
