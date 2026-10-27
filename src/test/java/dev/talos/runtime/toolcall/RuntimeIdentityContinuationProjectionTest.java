package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeIdentityContinuationProjectionTest {
    @TempDir Path workspace;
    private static final String REQUEST = "Who are you? Read README.md and tell me the owner and pending item. "
            + "Don't change files or run anything.";
    @AfterEach void clear() { TurnTaskContractCapture.clear(); }

    private LoopState state() throws Exception {
        var messages = new ArrayList<>(List.of(ChatMessage.system("You are Talos."), ChatMessage.user(REQUEST)));
        var state = new LoopState("", List.of(), messages, workspace, Context.builder(new Config()).build(), null, 5, 0);
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(messages), messages, ModelIdentitySnapshot.empty(), true);
        ReadOnlyEvidenceContextTest.read(state, "README.md", "1 | Owner Mara, packaging pending. Quoted question: Who are you?",
                Map.of("path", "README.md"));
        return state;
    }

    @Test void postReadTaskIsProjectedButItsEvidenceAndAuthorityRemainOriginal() throws Exception {
        var state = state();
        var before = List.copyOf(state.messages);
        var request = ReadOnlyEvidenceContinuation.requestMessages(state, ReadOnlyEvidenceContext.select(state), List.of());
        assertProjected(request);
        assertEquals(before, state.messages);
        assertEquals(REQUEST, TurnTaskContractCapture.get().originalUserRequest());
        assertTrue(request.stream().anyMatch(message -> "tool".equals(message.role())
                && message.content().contains("Quoted question: Who are you?")));
    }

    @Test void ordinarySynthesisProjectsOnlyTheExplicitTask() throws Exception {
        var state = state();
        var evidence = ReadOnlyEvidenceContext.select(state);
        var frozen = ReadOnlyFinalizationEvidence.freeze(state, evidence);
        assertFalse(frozen.proposalSelected());
        var before = List.copyOf(state.messages);
        assertProjected(ReadOnlyEvidenceSynthesizer.requestMessages(state, evidence, frozen));
        assertEquals(before, state.messages);
        assertEquals(REQUEST, TurnTaskContractCapture.get().originalUserRequest());
    }

    @Test void normalRepromptCannotRestoreTheRuntimeOwnedTask() throws Exception {
        var state = state();
        var before = List.copyOf(state.messages);
        assertProjected(ToolRepromptRequestBuilder.messages(state, false, List.of(), REQUEST, List.of()));
        assertEquals(before, state.messages);
    }

    @Test void historicalIdentityCannotProjectWithoutCurrentPresentation() throws Exception {
        var state = state();
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(state.messages), state.messages,
                ModelIdentitySnapshot.empty(), false);
        var request = ReadOnlyEvidenceContinuation.requestMessages(state, ReadOnlyEvidenceContext.select(state), List.of());
        assertTrue(request.stream().anyMatch(message -> REQUEST.equals(message.content())));
    }

    @Test void compactTaskFieldIsProjectedWithoutRewritingReadbackData() throws Exception {
        String original = "Who are you? Please review README.md and propose an improvement, but do not edit files.";
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("A clearer heading is proposed.", List.of())), 8192);
        try (var client = recorded.client()) {
            var messages = new ArrayList<>(List.of(ChatMessage.system("system"), ChatMessage.user(original)));
            var state = new LoopState("", List.of(), messages, workspace, Context.builder(new Config()).llm(client).build(), null, 5, 0);
            TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(messages), messages, ModelIdentitySnapshot.empty(), true);
            ReadOnlyEvidenceContextTest.read(state, "README.md", "1 | Quoted prompt: Who are you?", Map.of("path", "README.md"));
            state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome("talos.read_file", "README.md",
                    true, false, false, "read README.md", ""));
            assertTrue(CompactReadOnlyEvidenceContinuation.tryAnswer(state, "test"),
                    TurnTaskContractCapture.get().toString() + " pending=" + state.hasPendingActionObligation()
                            + " outcomes=" + state.toolOutcomes + " reads=" + state.readEvidence);
            String body = recorded.requests().getFirst().messages.getLast().content();
            assertFalse(body.contains("Current user request:\nWho are you?"), body);
            assertTrue(body.contains("Quoted prompt: Who are you?"), body);
            assertTrue(body.contains("propose an improvement, but do not edit files."));
            assertEquals(original, TurnTaskContractCapture.get().originalUserRequest());
        }
    }

    private void assertProjected(List<ChatMessage> request) {
        assertTrue(request.stream().anyMatch(message -> "user".equals(message.role())
                && message.content().strip().equals("Read README.md and tell me the owner and pending item. "
                        + "Don't change files or run anything.")), request.toString());
        assertFalse(request.stream().anyMatch(message -> REQUEST.equals(message.content())), request.toString());
    }
}
