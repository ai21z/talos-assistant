package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRepromptObligationSelectorTest {

    @Test
    void selectorOwnsTargetAccountingPendingObligationAndToolSurfaceSelection() throws Exception {
        String stage = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String selector = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptObligationSelector.java"));

        assertTrue(stage.contains("ToolRepromptObligationSelector.select("), stage);
        assertFalse(stage.contains("StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets"), stage);
        assertFalse(stage.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"), stage);
        assertFalse(stage.contains("PendingActionObligation.staticRepairTargets"), stage);
        assertFalse(stage.contains("PendingActionObligation.expectedTargets"), stage);
        assertFalse(stage.contains("ToolRepromptRequestBuilder.toolSpecs("), stage);

        assertTrue(selector.contains("StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets"),
                selector);
        assertTrue(selector.contains("ExpectedTargetProgressAccounting.remainingExpectedMutationTargets"),
                selector);
        assertTrue(selector.contains("PendingActionObligation.staticRepairTargets"), selector);
        assertTrue(selector.contains("PendingActionObligation.expectedTargets"), selector);
        assertTrue(selector.contains("ToolRepromptRequestBuilder.toolSpecs("), selector);
    }

    @Test
    void staticRepairObligationSelectsRemainingRepairTargetsAndWriteOnlyTools() {
        LoopState state = loopState(
                List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.system("""
                                [Static verification repair context]
                                Previous static verification problems:
                                - Static verification failed.
                                Full-file replacement targets: index.html, scripts.js, styles.css
                                """),
                        ChatMessage.user("Fix the static web page.")),
                broadTools());
        state.toolOutcomes.add(outcome("talos.write_file", "index.html", true, true));

        ToolRepromptObligationSelector.Selection selection =
                ToolRepromptObligationSelector.select(state, outcome(0, 0));

        assertEquals(List.of("scripts.js", "styles.css"), selection.remainingRepairTargets());
        assertEquals(List.of(), selection.remainingExpectedTargets());
        assertTrue(selection.staticRepairObligationActive());
        assertEquals(List.of("talos.write_file"), toolNames(selection.repromptToolSpecs()));
        assertTrue(state.hasPendingActionObligation());
    }

    @Test
    void expectedTargetObligationSelectsRemainingExpectedTargetsAndWriteEditToolsAfterMutationProgress() {
        LoopState state = loopState(
                List.of(ChatMessage.system("sys"), ChatMessage.user("Create README.md and notes.md.")),
                broadTools());
        state.toolOutcomes.add(outcome("talos.write_file", "README.md", true, true));

        ToolRepromptObligationSelector.Selection selection =
                ToolRepromptObligationSelector.select(state, outcome(1, 0));

        assertEquals(List.of(), selection.remainingRepairTargets());
        assertEquals(List.of("notes.md"), selection.remainingExpectedTargets());
        assertFalse(selection.staticRepairObligationActive());
        assertEquals(List.of("talos.write_file", "talos.edit_file"), toolNames(selection.repromptToolSpecs()));
        assertTrue(state.hasPendingActionObligation());
    }

    @Test
    void expectedTargetFactsBeforeMutationProgressDoNotRaiseObligationOrNarrowTools() {
        LoopState state = loopState(
                List.of(ChatMessage.system("sys"), ChatMessage.user("Create README.md and notes.md.")),
                broadTools());

        ToolRepromptObligationSelector.Selection selection =
                ToolRepromptObligationSelector.select(state, outcome(0, 0));

        assertEquals(List.of(), selection.remainingRepairTargets());
        assertEquals(List.of("README.md", "notes.md"), selection.remainingExpectedTargets());
        assertFalse(selection.staticRepairObligationActive());
        assertEquals(toolNames(broadTools()), toolNames(selection.repromptToolSpecs()));
        assertFalse(state.hasPendingActionObligation());
    }

    @Test
    void noRemainingTargetsClearsExistingPendingObligation() {
        LoopState state = loopState(
                List.of(ChatMessage.system("sys"), ChatMessage.user("Create README.md.")),
                broadTools());
        state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("README.md")));
        state.toolOutcomes.add(outcome("talos.write_file", "README.md", true, true));

        ToolRepromptObligationSelector.Selection selection =
                ToolRepromptObligationSelector.select(state, outcome(1, 0));

        assertEquals(List.of(), selection.remainingRepairTargets());
        assertEquals(List.of(), selection.remainingExpectedTargets());
        assertFalse(selection.staticRepairObligationActive());
        assertEquals(toolNames(broadTools()), toolNames(selection.repromptToolSpecs()));
        assertFalse(state.hasPendingActionObligation());
    }

    private static LoopState loopState(List<ChatMessage> messages, List<ToolSpec> tools) {
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("No tool call."))
                .nativeToolSpecs(tools)
                .build();
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(messages),
                Path.of("."),
                ctx,
                null,
                10,
                0);
    }

    private static ToolCallExecutionStage.IterationOutcome outcome(int mutations, int failures) {
        return new ToolCallExecutionStage.IterationOutcome(
                mutations,
                List.of(),
                failures,
                false,
                false,
                false,
                mutations + failures);
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

    private static List<ToolSpec> broadTools() {
        return List.of(
                tool("talos.read_file"),
                tool("talos.list_dir"),
                tool("talos.write_file"),
                tool("talos.edit_file"),
                tool("talos.run_command"));
    }

    private static ToolSpec tool(String name) {
        return new ToolSpec(name, name, "{}");
    }

    private static List<String> toolNames(List<ToolSpec> tools) {
        return tools.stream().map(ToolSpec::name).toList();
    }
}
