package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolFailurePolicyStopAnswerTest {

    @Test
    void blankDecisionReasonRendersDeterministicDefaultStopMessage() {
        String answer = ToolFailurePolicyStopAnswer.render(
                loopState("Read config.json and tell me the name."),
                FailureDecision.stop(FailureAction.ASK_USER, "   "));

        assertEquals(
                "[Tool loop stopped by failure policy: repeated tool failures "
                        + "Review the latest tool errors before retrying.]",
                answer);
    }

    @Test
    void nonNoProgressReasonDoesNotAppendRuntimeContext() {
        String answer = ToolFailurePolicyStopAnswer.render(
                loopState("Edit index.html."),
                FailureDecision.stop(
                        FailureAction.ASK_USER,
                        "failure policy stopped the tool loop after 3 failed call(s) for path `index.html`."));

        assertEquals(
                "[Tool loop stopped by failure policy: failure policy stopped the tool loop after 3 failed "
                        + "call(s) for path `index.html`. Review the latest tool errors before retrying.]",
                answer);
        assertFalse(answer.contains("Runtime context:"));
    }

    @Test
    void noProgressReasonAppendsExistingReadOnlyRuntimeContext() {
        String answer = ToolFailurePolicyStopAnswer.render(
                loopState("Propose a fix for the .missing-button bug. Do not edit files."),
                FailureDecision.stop(
                        FailureAction.ASK_USER,
                        "failure policy stopped the tool loop after 3 consecutive no-progress iteration(s)."));

        assertEquals("""
                [Tool loop stopped by failure policy: failure policy stopped the tool loop after 3 consecutive no-progress iteration(s). Review the latest tool errors before retrying.]

                Runtime context:
                - task contract: READ_ONLY_QA
                - mutationAllowed=false
                - successful mutations: 0
                - mutating tools were not available for this turn's contract; use an explicit create/edit/fix request if you intend a workspace change.""", answer);
    }

    @Test
    void repromptStageDelegatesFailurePolicyStopAnswerToOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("ToolFailurePolicyStopAnswer.render"), source);
        assertFalse(source.contains("private static String failurePolicyStopMessage"), source);
        assertFalse(source.contains("private static String failurePolicyRuntimeContext"), source);
    }

    private static LoopState loopState(String userRequest) {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(userRequest))),
                Path.of("."),
                null,
                null,
                5,
                0);
    }
}
