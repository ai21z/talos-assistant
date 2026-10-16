package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NoToolGroundingRetryTest {

    @ParameterizedTest
    @CsvSource({
            "Explain evidence policies. No project inspection is needed., NONE",
            "Read the main files and verify the wiring., VERIFY_FROM_TRACE_OR_EVIDENCE"
    })
    void lateFileReadRetryDoesNotReclassifyExistingObligations(String request, String obligation) {
        var resolved = plan(TaskContractResolver.fromUserRequest(request));
        assertEquals(obligation, resolved.evidenceObligation());
        var messages = messages(request);
        var context = Context.builder(new Config(null)).llm(LlmClient.scripted("unused")).build();
        String answer = longAnswer();
        assertSame(answer, NoToolGroundingRetry.retryIfNeeded(answer, resolved, messages, context,
                ignored -> { throw new AssertionError("No file-read retry was authorized by this obligation"); }));
        assertEquals(2, messages.size());
    }

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void retriesLongEvidenceLookingAnswerAndReturnsDifferentRetryText() throws Exception {
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .build();
        List<ChatMessage> messages = messages("Read README.md and explain the actual file contents.");
        String answer = longAnswer();
        AtomicReference<List<ChatMessage>> sentMessages = new AtomicReference<>();

        String result = NoToolGroundingRetry.retryIfNeeded(
                answer,
                plan(TaskContractResolver.fromUserRequest("Read README.md and explain the actual file contents.")),
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
        List<ChatMessage> messages = messages("Read README.md and explain the actual file contents.");
        String answer = longAnswer();

        String result = NoToolGroundingRetry.retryIfNeeded(
                answer,
                plan(TaskContractResolver.fromUserRequest("Read README.md and explain the actual file contents.")),
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

    @ParameterizedTest(name = "{0} retry restores original provider before annotation")
    @EnumSource(FallbackKind.class)
    void fallbackRetryRestoresOriginalProviderBeforeAnnotation(FallbackKind fallbackKind) {
        beginTrace("no-tool-grounding-" + fallbackKind.name().toLowerCase());
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .build();
        String request = "Read README.md and explain the actual file contents.";
        String answer = longAnswer();
        LlmClient.StreamResult originalProviderResult = providerTextResult(answer);

        String result = NoToolGroundingRetry.retryIfNeeded(
                answer,
                plan(TaskContractResolver.fromUserRequest(request)),
                messages(request),
                ctx,
                ignored -> fallbackProviderResult(fallbackKind, answer));

        assertEquals(AssistantTurnExecutor.UNGROUNDED_ANNOTATION + answer, result,
                "fallback annotation bytes must remain unchanged");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertRestoredProviderOwner(
                trace,
                originalProviderResult.provenance(),
                fallbackKind.restorationReason(),
                true);
    }

    @Test
    void wrappedCancellationEscapesNoToolGroundingRetry() {
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .build();
        String request = "Read README.md and explain the actual file contents.";
        RuntimeException wrapped = new RuntimeException(
                new CancellationException("cancel no-tool grounding retry"));

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                NoToolGroundingRetry.retryIfNeeded(
                        longAnswer(),
                        plan(TaskContractResolver.fromUserRequest(request)),
                        messages(request),
                        ctx,
                        ignored -> {
                            throw wrapped;
                        }));

        assertSame(wrapped, thrown,
                "retry cancellation must preserve the caller-owned runtime wrapper");
    }

    @Test
    void wrappedInterruptionEscapesNoToolGroundingRetryAndRestoresInterruptStatus() {
        Thread.interrupted();
        try {
            Context ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("unused"))
                    .build();
            String request = "Read README.md and explain the actual file contents.";
            RuntimeException wrapped = new RuntimeException(
                    new InterruptedException("interrupt no-tool grounding retry"));

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    NoToolGroundingRetry.retryIfNeeded(
                            longAnswer(),
                            plan(TaskContractResolver.fromUserRequest(request)),
                            messages(request),
                            ctx,
                            ignored -> {
                                throw wrapped;
                            }));

            assertSame(wrapped, thrown,
                    "retry interruption must preserve the caller-owned runtime wrapper");
            assertTrue(Thread.currentThread().isInterrupted(),
                    "propagated interruption must restore the caller interrupt status");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void semanticBarrierKeepsNestedInterruptionInOrdinaryRetryFailureHandling() {
        Thread.interrupted();
        try {
            Context ctx = Context.builder(new Config())
                    .llm(LlmClient.scripted("unused"))
                    .build();
            String request = "Read README.md and explain the actual file contents.";
            String answer = longAnswer();
            RuntimeException semanticallyTyped = new RuntimeException(
                    new IllegalArgumentException(
                            "invalid retry request",
                            new InterruptedException("non-control-flow cause")));

            String result = NoToolGroundingRetry.retryIfNeeded(
                    answer,
                    plan(TaskContractResolver.fromUserRequest(request)),
                    messages(request),
                    ctx,
                    ignored -> {
                        throw semanticallyTyped;
                    });

            assertEquals(AssistantTurnExecutor.UNGROUNDED_ANNOTATION + answer, result,
                    "a semantic validation failure must retain the ordinary fallback contract");
            assertFalse(Thread.currentThread().isInterrupted(),
                    "an interruption hidden behind a semantic barrier must not alter thread state");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void acceptedDifferentProviderRetryRetainsRetryOwnerWithoutRestoration() {
        beginTrace("no-tool-grounding-accepted");
        Context ctx = Context.builder(new Config())
                .llm(LlmClient.scripted("unused"))
                .build();
        String request = "Read README.md and explain the actual file contents.";
        String answer = longAnswer();
        providerTextResult(answer);
        String accepted = "Grounded retry answer.";
        AtomicReference<ProviderResponseCapture.Receipt> retryReceipt = new AtomicReference<>();

        String result = NoToolGroundingRetry.retryIfNeeded(
                answer,
                plan(TaskContractResolver.fromUserRequest(request)),
                messages(request),
                ctx,
                ignored -> {
                    LlmClient.StreamResult retry = providerTextResult(accepted);
                    retryReceipt.set(retry.provenance());
                    return retry;
                });

        assertEquals(accepted, result, "accepted retry bytes must remain unchanged");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(events(trace, "ANSWER_CANDIDATE_RESTORED").isEmpty(),
                "accepted retry must not restore the original candidate");
        assertTrue(events(trace, "ANSWER_TRANSFORMED").isEmpty(),
                "exact accepted retry bytes need no runtime transform");
        assertTerminalProviderOwner(trace, retryReceipt.get());
    }

    private static CurrentTurnPlan plan(TaskContract contract) {
        CurrentTurnPlan plan = CurrentTurnPlan.compatibility(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("talos.list_dir", "talos.read_file"),
                List.of());
        if (contract.originalUserRequest().equals("Read README.md and explain the actual file contents."))
            assertEquals("READ_TARGET_REQUIRED", plan.evidenceObligation());
        return plan;
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

    private enum FallbackKind {
        BLANK("SPECULATIVE_RETRY_EMPTY"),
        EQUAL("SPECULATIVE_RETRY_REJECTED"),
        EXCEPTION("SPECULATIVE_RETRY_FAILED");

        private final String restorationReason;

        FallbackKind(String restorationReason) {
            this.restorationReason = restorationReason;
        }

        String restorationReason() {
            return restorationReason;
        }
    }

    private static LlmClient.StreamResult fallbackProviderResult(
            FallbackKind fallbackKind,
            String original) {
        return switch (fallbackKind) {
            case BLANK -> providerTextResult("");
            case EQUAL -> providerTextResult(original);
            case EXCEPTION -> {
                failProviderGeneration();
                throw new IllegalStateException("injected grounding retry failure");
            }
        };
    }

    private static LlmClient.StreamResult providerTextResult(String text) {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                attempt, ProviderTransportHandoff.DispatchEvidence.unbound());
        ProviderResponseCapture.Receipt receipt = attempt.respond(new ProviderResponseCapture.Response(
                text,
                List.of(),
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false,
                false));
        generation.complete(receipt);
        return streamResult(text, receipt);
    }

    private static LlmClient.StreamResult streamResult(
            String text,
            ProviderResponseCapture.Receipt receipt) {
        return new LlmClient.StreamResult(
                text, List.of(), "", "", ProviderResponseCapture.AbortOrigin.NONE,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false, false, receipt);
    }

    private static void failProviderGeneration() {
        ProviderResponseCapture.Generation generation = ProviderResponseCapture.openGeneration(
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE);
        ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                ProviderResponseCapture.AttemptKind.PRIMARY,
                ProviderResponseCapture.RetryReason.NONE);
        attempt.requestDispatched();
        generation.fail(ProviderResponseCapture.Failure.PROVIDER_EXCEPTION);
    }

    private static void assertRestoredProviderOwner(
            LocalTurnTrace trace,
            ProviderResponseCapture.Receipt originalReceipt,
            String expectedReason,
            boolean expectTransform) {
        List<TurnTraceEvent> restorations = events(trace, "ANSWER_CANDIDATE_RESTORED");
        assertEquals(1, restorations.size(), "fallback must emit exactly one closed restoration");
        TurnTraceEvent restoration = restorations.getFirst();
        assertEquals("MODEL_PROVIDER", restoration.data().get("origin"));
        assertEquals(originalReceipt.generationId(), restoration.data().get("sourceGenerationId"));
        assertEquals(originalReceipt.attemptId(), restoration.data().get("sourceAttemptId"));
        assertEquals(expectedReason, restoration.data().get("reasonCode"));

        List<TurnTraceEvent> transformations = events(trace, "ANSWER_TRANSFORMED");
        assertEquals(expectTransform ? 1 : 0, transformations.size());
        if (expectTransform) {
            assertTrue(trace.events().indexOf(restoration) < trace.events().indexOf(transformations.getFirst()),
                    "the original source must be restored before the annotation transform");
        }
        assertTerminalProviderOwner(trace, originalReceipt);
    }

    private static void assertTerminalProviderOwner(
            LocalTurnTrace trace,
            ProviderResponseCapture.Receipt receipt) {
        TurnTraceEvent terminal = events(trace, "TERMINAL_ANSWER_SELECTED").getFirst();
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(receipt.generationId(), terminal.data().get("sourceGenerationId"));
        assertEquals(receipt.attemptId(), terminal.data().get("sourceAttemptId"));
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(event -> type.equals(event.type()))
                .toList();
    }

    private static void beginTrace(String traceId) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session-no-tool-grounding",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "verify no-tool grounding retry lineage");
    }
}
