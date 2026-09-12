package dev.talos.cli.modes;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnswerOutputLimitSelectionTest {
    @Test
    void finalOutcomeRetainsLimitForSearchCapabilityPrefix() {
        LocalTurnTraceCapture.clear();
        String answer = "No matches were found.";
        var messages = List.of(ChatMessage.user("Find needle with the search tool."));
        var loop = new ToolCallLoop.LoopResult(answer, 1, 1, List.of("talos.grep"),
                List.of(ChatMessage.assistant("[tool_result: talos.grep]\nSearch was limited: skipped unsupported files.")),
                0, 0, false, 0, List.of(), 0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome("talos.grep", ".", true, false, false,
                        "Search was limited: skipped unsupported files.", "")));
        var outcome = ExecutionOutcome.fromToolLoop(answer, messages, loop, null, 0);
        assertTrue(outcome.finalAnswer().contains(answer), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("Search was limited"), outcome.finalAnswer());
        assertEquals(AnswerOutputLimit.KEEP_ORIGINAL, outcome.outputLimit());
    }

    @Test
    void protectedHistoryWholeReplacementClearsSelectedLimitWithAndWithoutToolLoop() {
        LocalTurnTraceCapture.clear();
        String secret = "SAFE_AUDIT_TOKEN=history-token";
        var messages = List.of(ChatMessage.assistant("Approved file .env contained " + secret),
                ChatMessage.user("Answer briefly."));
        var noTools = ExecutionOutcome.fromNoTool(secret, messages, null, false);
        var loop = new ToolCallLoop.LoopResult(secret, 0, 0, List.of(), messages,
                0, 0, false, 0, List.of(), 0, 0, 0, 0);
        var afterTools = ExecutionOutcome.fromToolLoop(secret, messages, loop, null, 0);
        for (var outcome : List.of(noTools, afterTools)) {
            assertFalse(outcome.finalAnswer().contains(secret), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("I did not show protected content"), outcome.finalAnswer());
            assertEquals(AnswerOutputLimit.CLEAR, outcome.outputLimit());
        }
    }

    @Test
    void acceptedSynthesisSelectsItsOwnLimitWithoutTrace() {
        LocalTurnTraceCapture.clear();
        for (String finish : List.of("stop", "length")) {
            var result = PostToolSynthesisRetry.selectSynthesisIfNeeded("How can I help?", 1,
                    List.of(ChatMessage.user("Explain the observed value.")),
                    ignored -> new LlmClient.StreamResult("The observed value is 42.", List.of(), finish));
            assertEquals("length".equals(finish), result.outputLimit().apply(true));
            assertEquals("length".equals(finish), result.outputLimit().apply(false));
        }
    }

    @Test
    void rejectedEmptyAndFailedSynthesisRetainOriginalLimitWithoutTrace() {
        LocalTurnTraceCapture.clear();
        String original = "How can I help?";
        for (String rejected : List.of("", original, "What would you like me to do?")) {
            var result = PostToolSynthesisRetry.selectSynthesisIfNeeded(original, 1,
                    List.of(ChatMessage.user("Explain the observed value.")),
                    ignored -> new LlmClient.StreamResult(rejected, List.of(), "length"));
            assertEquals(original, result.answer());
            assertTrue(result.outputLimit().apply(true));
            assertFalse(result.outputLimit().apply(false));
        }
        var failed = PostToolSynthesisRetry.selectSynthesisIfNeeded(original, 1,
                List.of(ChatMessage.user("Explain the observed value.")),
                ignored -> { throw new IllegalStateException("fixture failure"); });
        assertEquals(original, failed.answer());
        assertTrue(failed.outputLimit().apply(true));
        assertFalse(failed.outputLimit().apply(false));
    }

    @Test
    void loopAnnotationsPreserveLimitAndDeterministicSelectionsClearItWithoutTrace(@TempDir Path workspace) {
        LocalTurnTraceCapture.clear();
        var state = new LoopState("", List.of(), List.of(), workspace, null, null, 3, 0);
        var partial = new LlmClient.StreamResult("Observed answer", List.of(), "length");
        state.applyProviderResult(partial);
        assertTrue(state.currentAnswerOutputLimitReached);
        state.transformAnswer("Annotation\nObserved answer",
                LocalTurnTraceCapture.AnswerTransformationReason.TOOL_LOOP_FINALIZATION);
        assertTrue(state.currentAnswerOutputLimitReached);
        state.finishWithAnswer("Runtime answer");
        assertFalse(state.currentAnswerOutputLimitReached);
        state.applyProviderResult(partial);
        state.finishWithToolAnswer("Tool-owned answer");
        assertFalse(state.currentAnswerOutputLimitReached);
        state.applyProviderResult(partial);
        state.finishWithOperatorAnswer("Operator answer");
        assertFalse(state.currentAnswerOutputLimitReached);
        state.applyProviderResult(partial);
        state.setRuntimeNativeCalls("", List.of(),
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.READ_EVIDENCE_HANDOFF);
        assertFalse(state.currentAnswerOutputLimitReached);
    }
}
