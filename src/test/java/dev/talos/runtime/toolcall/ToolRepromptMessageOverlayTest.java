package dev.talos.runtime.toolcall;

import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptMessageOverlayTest {

    @Test
    void appliesStaleAndEmptyRepairInstructionsAndRecordsPromptedPaths() {
        LoopState state = stateWith(ChatMessage.system("existing"));
        state.staleEditFailuresByPath.put("index.html", 1);
        state.pathsMutatedSinceRead.add("index.html");
        state.emptyEditArgumentFailuresByPath.put("app.js", 1);
        state.pathsReadThisTurn.add("app.js");

        ToolRepromptMessageOverlay overlay = ToolRepromptMessageOverlay.apply(
                state,
                List.of(),
                List.of(),
                "");

        assertEquals(3, state.messages.size());
        assertEquals(RepairPolicy.staleEditRepairInstruction("index.html"),
                state.messages.get(1).content());
        assertEquals(RepairPolicy.emptyEditRepairInstruction("app.js"),
                state.messages.get(2).content());
        assertTrue(state.staleEditRepairPromptedPaths.contains("index.html"));
        assertTrue(state.emptyEditRepairPromptedPaths.contains("app.js"));

        overlay.close();

        assertEquals(List.of(ChatMessage.system("existing")), state.messages);
    }

    @Test
    void appliesProgressAndCurrentTaskMessagesWithExactWordingThenCleansOnlyOverlayMessages() {
        ChatMessage permanent = ChatMessage.system("[Static repair progress] permanent user-visible history");
        LoopState state = stateWith(permanent, ChatMessage.user("original task"));
        String longTask = "x".repeat(501);

        try (ToolRepromptMessageOverlay ignored = ToolRepromptMessageOverlay.apply(
                state,
                List.of("index.html", "styles.css"),
                List.of("script.js"),
                longTask)) {
            assertEquals(5, state.messages.size());
            assertEquals("""
                    [Static repair progress] Continue the bounded repair. Remaining full-file replacement targets: index.html, styles.css. Use talos.write_file with complete corrected file content for each remaining target. Do not claim completion until static verification passes.""",
                    state.messages.get(2).content());
            assertEquals("""
                    [Expected target progress] Continue this mutation task. Remaining expected target paths not successfully mutated in this turn: script.js. Use the visible write/edit tools to mutate these exact paths before answering. Similar filenames are not substitutes. For small static web files, prefer talos.write_file with complete file content. Do not claim completion until static verification passes.""",
                    state.messages.get(3).content());
            assertEquals("[Current task - stay focused on this] " + "x".repeat(500) + "…",
                    state.messages.get(4).content());
        }

        assertEquals(List.of(permanent, ChatMessage.user("original task")), state.messages);
    }

    @Test
    void expectedTargetProgressMessagePreservesExactPluralScriptTarget() {
        LoopState state = stateWith(ChatMessage.system("existing"));

        try (ToolRepromptMessageOverlay ignored = ToolRepromptMessageOverlay.apply(
                state,
                List.of(),
                List.of("scripts.js"),
                "Create index.html, styles.css, and scripts.js.")) {
            String prompt = state.messages.get(1).content();
            assertTrue(prompt.contains(
                    "Remaining expected target paths not successfully mutated in this turn: scripts.js"),
                    prompt);
            assertFalse(prompt.contains(
                    "Remaining expected target paths not successfully mutated in this turn: script.js"),
                    prompt);
        }
    }

    @Test
    void closesOverlayWhenContinuationThrows() {
        LoopState state = stateWith(ChatMessage.system("existing"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
            try (ToolRepromptMessageOverlay ignored = ToolRepromptMessageOverlay.apply(
                    state,
                    List.of("index.html"),
                    List.of("script.js"),
                    "finish the task")) {
                throw new RuntimeException("boom");
            }
        });

        assertEquals("boom", thrown.getMessage());
        assertEquals(List.of(ChatMessage.system("existing")), state.messages);
    }

    private static LoopState stateWith(ChatMessage... messages) {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(messages)),
                Path.of("."),
                null,
                null,
                10,
                0);
    }
}
