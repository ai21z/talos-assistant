package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallRepromptStageTest {

    @Test
    void directoryListingStopsAfterSuccessfulListDir() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("What files are in this folder?"),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "list_dir", java.util.Map.of("path", ".")))),
                ChatMessage.toolResult("call-1", """
                        [tool_result: list_dir]
                        README.md
                        index.html
                        notes.md
                        [/tool_result]""")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                null,
                null,
                10,
                0);
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 0, false, false, false, 1);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertFalse(shouldReprompt);
        assertEquals("""
                Directory entries:
                - README.md
                - index.html
                - notes.md""", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void readOnlyQaStopsAfterSuccessfulNamedReadAliasWhenLoopMakesNoProgress() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Read config.json and tell me the name."),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "read_file", java.util.Map.of("path", "config.json")))),
                ChatMessage.toolResult("call-1", """
                        [tool_result: read_file]
                        1 | {"name":"t57-fixture"}
                        [/tool_result]"""),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-2", "talos.read_file", java.util.Map.of("path", "config.json")))),
                ChatMessage.toolResult("call-2", """
                        [tool_result: talos.read_file]
                        You already gathered this information and the workspace has not changed since then.
                        [/tool_result]""")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                null,
                null,
                10,
                0);
        state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                "read_file",
                "config.json",
                true,
                false,
                false,
                "read config.json",
                ""));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 0, false, false, false, 0);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertFalse(shouldReprompt);
        assertEquals("""
                Read config.json:
                1 | {"name":"t57-fixture"}""", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void emptyEditRepairIsAvailableOnlyAfterTargetWasReadAndOnlyOnce() {
        LoopState state = new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"))),
                Path.of("."),
                null,
                null,
                10,
                0);

        state.emptyEditArgumentFailuresByPath.put("index.html", 1);

        assertTrue(ToolCallRepromptStage.nextEmptyEditRepair(state).isEmpty(),
                "An empty edit failure alone is not enough; the model must read the target first.");

        state.pathsReadThisTurn.add("index.html");

        var repair = ToolCallRepromptStage.nextEmptyEditRepair(state);
        assertTrue(repair.isPresent());
        assertEquals("index.html", repair.get().path());
        assertTrue(repair.get().instruction().contains("[Edit repair required]"));
        assertTrue(repair.get().instruction().contains("non-empty old_string"));
        assertTrue(repair.get().instruction().contains("new_string parameter"));
        assertTrue(repair.get().instruction().contains("empty only for an explicit deletion task"));
        assertTrue(repair.get().instruction().chars().allMatch(c -> c <= 127),
                "Repair instruction should stay ASCII-safe for terminal transcripts.");

        state.emptyEditRepairPromptedPaths.add("index.html");

        assertTrue(ToolCallRepromptStage.nextEmptyEditRepair(state).isEmpty(),
                "The specialized repair instruction is one-shot per path.");
    }
}
