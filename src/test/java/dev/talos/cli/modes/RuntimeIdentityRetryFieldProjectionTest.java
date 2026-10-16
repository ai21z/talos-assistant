package dev.talos.cli.modes;

import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeIdentityRetryFieldProjectionTest {
    @TempDir Path workspace;
    private static final String TASK = "Rewrite README.md with a project note. Preserve existing content.";
    private static final String REQUEST = "Who are you? " + TASK;
    private static final String PRIOR = "Who are you? Earlier request: create previous.md.";

    @AfterEach void clear() { TurnTaskContractCapture.clear(); }

    /** Synthetic private builder checks, not eligibility or execution evidence. */
    @Test void everyRetryTaskCopyPreservesAuthorityAndPriorRequest() throws Exception {
        var messages = List.of(ChatMessage.user(REQUEST));
        var contract = TaskContractResolver.fromUserRequest(REQUEST);
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.APPLY, List.of(), List.of(), List.of());
        for (String field : List.of("inspection", "completeness", "typed", "expected", "compact", "request", "follow-up")) {
            TurnTaskContractCapture.clear();
            String baseline = render(field, plan);
            capture(messages, false);
            assertEquals(baseline, render(field, plan), field + " disabled");
            capture(List.of(ChatMessage.user("Who are you? Explain recursion.")), true);
            assertEquals(baseline, render(field, plan), field + " unbound");
            capture(messages, true);
            var authority = TurnTaskContractCapture.getResolution();
            String projected = render(field, plan);
            String expectedTask = field.equals("request") || field.equals("follow-up") ? " " + TASK : TASK;
            assertEquals(baseline.replace(REQUEST, expectedTask), projected, field);
            assertTrue(projected.contains(TASK), field);
            if (field.equals("follow-up")) assertTrue(projected.contains(PRIOR), projected);
            assertSame(authority, TurnTaskContractCapture.getResolution());
            assertEquals(REQUEST, plan.originalUserRequest());
            assertEquals(REQUEST, authority.contract().originalUserRequest());
            assertSame(contract, plan.taskContract());
        }
    }

    @Test void inspectionProjectsTheCompleteTaskBeforeItsThousandCharacterLimit() {
        String task = "Read README.md and explain it. Keep this label: " + "x".repeat(1200) + ".";
        String original = " \tWho are you? " + task + "\n";
        capture(List.of(ChatMessage.user(original)), true);
        var contract = TaskContractResolver.fromUserRequest(original);
        String prompt = ReadOnlyInspectionRetry.retryPrompt(contract, original, workspace);
        assertTrue(prompt.contains("User request: \"" + task.substring(0, 1000) + "...\""), prompt);
        assertEquals(original, TurnTaskContractCapture.providerTaskRequest());
    }

    @Test void postToolGenerationProjectsBeforePinningAndPreservesEvidenceAndHistory() {
        String task = "Read README.md and explain it. Keep this label: " + "x".repeat(2300) + ".";
        String original = "Who are you? " + task;
        var history = new ArrayList<>(List.of(ChatMessage.user(PRIOR), ChatMessage.assistant("Earlier answer."),
                ChatMessage.user(original), ChatMessage.toolResult("read-id", "Evidence literal: Who are you?")));
        var before = List.copyOf(history);
        for (int mode = 0; mode < 3; mode++) {
            TurnTaskContractCapture.clear();
            if (mode != 0) capture(history, mode == 2);
            var requests = new ArrayList<List<ChatMessage>>();
            String result = PostToolSynthesisRetry.synthesizeIfNeeded("How can I help?", 1, history, request -> {
                requests.add(request);
                return new LlmClient.StreamResult("The requested evidence is summarized.", List.of());
            });
            assertEquals("The requested evidence is summarized.", result);
            assertEquals(1, requests.size());
            var sent = requests.getFirst();
            String providerTask = mode == 2 ? " " + task : original;
            assertTrue(sent.getLast().content().contains("«" + providerTask.substring(0, 2000) + "…»"));
            assertEquals(before, sent.subList(0, before.size()));
            assertEquals(before, history);
            if (mode != 0) assertEquals(original, TurnTaskContractCapture.get().originalUserRequest());
        }
    }

    private void capture(List<ChatMessage> messages, boolean enabled) {
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromMessages(messages), messages,
                ModelIdentitySnapshot.empty(), enabled);
    }

    private String render(String field, CurrentTurnPlan plan) throws Exception {
        Object result = switch (field) {
            case "inspection" -> ReadOnlyInspectionRetry.retryPrompt(plan.taskContract(), plan.originalUserRequest(), workspace);
            case "completeness" -> invoke(InspectCompletenessRetry.class, "retryPrompt",
                    new Class<?>[]{TaskContract.class, String.class, List.class}, plan.taskContract(), plan.originalUserRequest(), List.of("README.md"));
            case "typed" -> invoke(MissingMutationRetry.class, "remainingTypedWorkspacePlanMessages",
                    new Class<?>[]{CurrentTurnPlan.class, List.class, boolean.class}, plan, List.of("README.md"), false);
            case "expected" -> invoke(MissingMutationRetry.class, "remainingExpectedTargetContinuationMessages",
                    new Class<?>[]{CurrentTurnPlan.class, List.class}, plan, List.of("README.md"));
            case "compact" -> invoke(MissingMutationRetry.class, "compactMutationRetryFrame",
                    new Class<?>[]{CurrentTurnPlan.class, List.class, List.class}, plan, List.of(), List.of());
            case "request", "follow-up" -> invoke(MissingMutationRetry.class, "mutationRetryRequestContext",
                    new Class<?>[]{String.class, String.class}, plan.originalUserRequest(), field.equals("follow-up") ? PRIOR : "");
            default -> throw new AssertionError(field);
        };
        if (result instanceof String text) return text;
        @SuppressWarnings("unchecked") var messages = (List<ChatMessage>) result;
        return messages.stream().map(ChatMessage::content).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static Object invoke(Class<?> owner, String name, Class<?>[] types, Object... arguments) throws Exception {
        var method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }
}
