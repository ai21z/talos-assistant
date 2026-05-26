package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptStaleEditRereadStopTest {

    @Test
    void ownsStaleRereadStopMechanics() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptStaleEditRereadStop.java"));

        assertTrue(source.contains("FailureAction.ASK_USER"), source);
        assertTrue(source.contains("SafeLogFormatter.value("), source);
        assertTrue(source.contains("before rereading the file after a same-turn mutation changed it"), source);
    }

    @Test
    void noStaleRereadPathReturnsEmptyDecision() {
        LoopState state = state();

        Optional<Boolean> decision = ToolRepromptStaleEditRereadStop.tryHandle(state);

        assertTrue(decision.isEmpty());
    }

    @Test
    void staleRereadPathStopsWithExistingFailureWordingAndClearsCalls() {
        LoopState state = state();
        state.staleEditRereadIgnoredPath = "src/app.js";
        state.currentNativeCalls = List.of(new ChatMessage.NativeToolCall(
                "stale", "talos.edit_file", Map.of("path", "src/app.js")));

        Optional<Boolean> decision = ToolRepromptStaleEditRereadStop.tryHandle(state);

        assertEquals(Optional.of(false), decision);
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(
                "[Tool loop stopped by failure policy: failure policy stopped the tool loop because "
                        + "talos.edit_file was retried for path `src/app.js` before rereading the file after "
                        + "a same-turn mutation changed it. No approval was requested for the stale retry "
                        + "and no additional file change was made. Review the latest tool errors before retrying.]",
                state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    private static LoopState state() {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Update src/app.js."))),
                Path.of("."),
                null,
                null,
                10,
                0);
    }
}
