package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopStateTerminalResponseTest {

    @Test
    void finishWithAnswerPreservesAnswerAndClearsNativeCallsWithoutChangingFailureDecision() {
        LoopState state = loopState();
        ChatMessage.NativeToolCall call = nativeCall();
        FailureDecision existingDecision = FailureDecision.stop(FailureAction.ASK_USER, "existing failure");
        state.currentNativeCalls = List.of(call);
        state.failureDecision = existingDecision;

        state.finishWithAnswer("terminal answer");

        assertEquals("terminal answer", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
        assertSame(existingDecision, state.failureDecision);
    }

    @Test
    void stopWithFailureSetsDecisionAnswerAndClearsNativeCalls() {
        LoopState state = loopState();
        state.currentNativeCalls = List.of(nativeCall());
        FailureDecision decision = FailureDecision.stop(FailureAction.ASK_USER, "terminal failure");

        state.stopWithFailure(decision, "failure answer");

        assertEquals("failure answer", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
        assertSame(decision, state.failureDecision);
    }

    private static LoopState loopState() {
        return new LoopState(
                "initial answer",
                List.of(),
                List.of(ChatMessage.user("Update README.md.")),
                Path.of("."),
                null,
                null,
                5,
                0);
    }

    private static ChatMessage.NativeToolCall nativeCall() {
        return new ChatMessage.NativeToolCall(
                "call-1",
                "talos.write_file",
                Map.of("path", "README.md", "content", "# Updated\n"));
    }
}
