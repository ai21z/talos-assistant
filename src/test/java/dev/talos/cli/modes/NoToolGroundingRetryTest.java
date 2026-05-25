package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NoToolGroundingRetryTest {

    @Test
    void retriesLongEvidenceLookingAnswerAndReturnsDifferentRetryText() throws Exception {
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .build();
        List<ChatMessage> messages = messages("Read the main files and verify the wiring.");
        String answer = longAnswer();
        AtomicReference<List<ChatMessage>> sentMessages = new AtomicReference<>();

        String result = NoToolGroundingRetry.retryIfNeeded(
                answer,
                plan(TaskContractResolver.fromUserRequest("Read the main files and verify the wiring.")),
                messages,
                ctx,
                retryMessages -> {
                    sentMessages.set(List.copyOf(retryMessages));
                    return new LlmClient.StreamResult("Grounded retry answer.", List.of());
                });

        assertEquals("Grounded retry answer.", result);
        assertEquals(4, messages.size(), "retry appends assistant answer and corrective user prompt");
        assertEquals(sentMessages.get(), messages);
        assertEquals("assistant", messages.get(2).role());
        assertEquals(answer, messages.get(2).content());
        assertEquals("user", messages.get(3).role());
        assertEquals(correctionPrompt(), messages.get(3).content());
    }

    @Test
    void annotatesOriginalWhenRetryIsBlankOrIdentical() throws Exception {
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .build();
        List<ChatMessage> messages = messages("Use evidence from the actual files.");
        String answer = longAnswer();

        String result = NoToolGroundingRetry.retryIfNeeded(
                answer,
                plan(TaskContractResolver.fromUserRequest("Use evidence from the actual files.")),
                messages,
                ctx,
                ignored -> new LlmClient.StreamResult("   ", List.of()));

        assertTrue(result.startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION), result);
        assertTrue(result.contains(answer), result);
        assertEquals(4, messages.size());
    }

    @Test
    void directAnswerOnlyPlanDoesNotRetryEvenWhenTextLooksLikeEvidenceRequest() throws Exception {
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .build();
        List<ChatMessage> messages = messages("Read the source files and verify this.");
        String answer = longAnswer();

        String result = NoToolGroundingRetry.retryIfNeeded(
                answer,
                directAnswerPlan("Read the source files and verify this."),
                messages,
                ctx,
                ignored -> {
                    throw new AssertionError("chat should not be called");
                });

        assertSame(answer, result);
        assertEquals(2, messages.size(), "direct-answer-only turns must not append retry messages");
    }

    @Test
    void shortAnswerDoesNotRetry() throws Exception {
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .build();
        List<ChatMessage> messages = messages("Read the source files and verify this.");
        String answer = "Too little evidence.";

        String result = NoToolGroundingRetry.retryIfNeeded(
                answer,
                plan(TaskContractResolver.fromUserRequest("Read the source files and verify this.")),
                messages,
                ctx,
                ignored -> {
                    throw new AssertionError("chat should not be called");
                });

        assertSame(answer, result);
        assertEquals(2, messages.size(), "short answers must not append retry messages");
    }

    private static CurrentTurnPlan plan(TaskContract contract) {
        return CurrentTurnPlan.compatibility(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("talos.list_dir", "talos.read_file"),
                List.of());
    }

    private static CurrentTurnPlan directAnswerPlan(String request) {
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                request,
                "test-direct-answer-only");
        return new CurrentTurnPlan(
                contract,
                request,
                ExecutionPhase.RESPOND,
                ExecutionPhase.RESPOND,
                ActionObligation.DIRECT_ANSWER_ONLY,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);
    }

    private static List<ChatMessage> messages(String request) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static String longAnswer() {
        return "a".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS + 20);
    }

    private static String correctionPrompt() {
        return "Your previous answer was produced without reading any files. "
                + "The user asked for an answer grounded in the actual workspace. "
                + "Use the available file tools to read the relevant files, then "
                + "answer concretely from what you read. Do not guess about file "
                + "contents. Do not describe files you have not read.";
    }
}
