package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallRepromptStageTest {

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
