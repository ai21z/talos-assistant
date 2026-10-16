package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.policy.WorkStatusAnswerPolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ModelIdentitySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.talos.runtime.policy.CapabilityPosture;
import dev.talos.runtime.policy.CapabilityPosturePolicy;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalAttributionBoundaryTest {
    static final String REQUEST = "For my notes, can I say that you updated README.md and ran the tests for me? "
            + "Correct that statement if it is not supported. Don't change files or run anything.";
    @TempDir Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {
            REQUEST,
            "Did you edit README.md and run tests?",
            "Have you updated README.md and run the tests in this session?",
            "Is it accurate to say that Talos edited README.md and executed the tests?"
    })
    void pureAttributionIsNotFreshAcquisition(String request) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertFalse(resolution.contract().mutationAllowed());
        assertFalse(resolution.contract().mutationRequested());
        assertTrue(resolution.contract().expectedTargets().isEmpty(), resolution.contract().toString());
        assertTrue(resolution.contract().sourceEvidenceTargets().isEmpty());
        assertTrue(WorkStatusAnswerPolicy.requestFor(resolution.contract()).isPresent());
    }

    @Test void overlappingSessionActionKeepsItsMetadataWithoutFreshAuthority() {
        var contract = TaskContractResolver.fromUserRequest("Have you run tests?");
        assertEquals("session-action-summary", contract.classificationReason());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertTrue(contract.expectedTargets().isEmpty());
        assertTrue(contract.sourceEvidenceTargets().isEmpty());
        var request = WorkStatusAnswerPolicy.requestFor(contract).orElseThrow();
        assertEquals(java.util.Set.of(dev.talos.runtime.intent.WorkStatusRequest.Role.TESTING), request.roles());
    }

    @Test void recordedQuestionSettlesBothResultsWithoutProviderOrRead() throws Exception {
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                new LlmClient.StreamResult("The label is oak.", List.of())), ModelIdentitySnapshot::empty);
        var cfg = new Config(null);
        var memory = new SessionMemory();
        memory.update("The documented expectation was updated.", "The current label is oak.");
        var ctx = Context.builder(cfg).memory(memory).llm(fixture.client()).build();
        var processor = new TurnProcessor((raw, ws, context) ->
                new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        var settled = processor.process(new Session(workspace, cfg), REQUEST, ctx);
        assertTrue(fixture.requests().isEmpty(), "Attribution is owned before generation");
        assertTrue(settled.audit().toolCalls().isEmpty());
        var text = MemoryText.of(settled.result());
        assertTrue(text.contains("README.md"), text);
        assertTrue(text.contains("File edit:"), text);
        assertTrue(text.contains("Test execution:"), text);
        assertTrue(text.contains("not established"), text);
        assertFalse(text.contains("README was not modified"), text);
        assertTrue(settled.audit().workStatusReceipt().active());
        assertTrue(settled.audit().workStatusReceipt().matches(REQUEST, text));
        assertEquals("READ_ONLY_ANSWERED", settled.audit().localTrace().outcome().classification());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Did you edit README.md? Read the current README.md too.",
            "Can I say you changed README.md and ran tests? Run tests now.",
            "If you edited README.md, would tests be required?",
            "Translate `Did you edit README.md and run tests?`.",
            "Did you edit README.md and run tests? Also explain the label."
    })
    void extraWorkAndUnknownMeaningAreNotDiscarded(String request) {
        assertTrue(WorkStatusAnswerPolicy.requestFor(TaskContractResolver.fromUserRequest(request)).isEmpty());
    }

    @Test void readOnlyPosturePreservesHistoricalOwnership() {
        for (var posture : List.of(CapabilityPosture.ASK_READ_ONLY, CapabilityPosture.PLAN_READ_ONLY)) {
            var contract = CapabilityPosturePolicy.apply(posture,
                    TaskContractResolver.fromUserRequest(REQUEST)).taskContract();
            assertTrue(WorkStatusAnswerPolicy.requestFor(contract).isPresent(), contract.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"assist", "ask", "plan", "rag"})
    void allModesOwnBothResultsBeforeColdPreparation(String mode) throws Exception {
        var fixture = dev.talos.core.llm.WorkStatusColdProviderFixture.create();
        try (var llm = fixture.client()) {
            var cfg = new Config(null);
            cfg.data.put("privacy", java.util.Map.of("mode", "developer"));
            cfg.data.put("embed", java.util.Map.of("provider", "disabled", "model", "disabled"));
            cfg.data.put("rag", java.util.Map.of("vectors", java.util.Map.of("enabled", false)));
            var preparations = new java.util.concurrent.atomic.AtomicInteger();
            var rag = new dev.talos.core.rag.RagService(cfg) {
                @Override public Prepared prepare(Path path, String query, Integer topK) {
                    preparations.incrementAndGet();
                    throw new AssertionError("Historical attribution must not retrieve or lazily index");
                }
            };
            var ctx = Context.builder(cfg).memory(new SessionMemory()).llm(llm).rag(rag).build();
            Mode handler = switch (mode) {
                case "ask" -> new AskMode();
                case "plan" -> new PlanMode();
                case "rag" -> new RagMode();
                default -> new UnifiedAssistantMode();
            };
            var processor = new TurnProcessor((raw, ws, context) -> handler.handle(raw, ws, (Context) context));
            var settled = processor.process(new Session(workspace, cfg), REQUEST, ctx);
            var answer = MemoryText.of(settled.result());
            assertTrue(answer.contains("File edit: README.md."), answer);
            assertTrue(answer.contains("Test execution: not established"), answer);
            assertTrue(settled.audit().workStatusReceipt().active(), answer);
            assertTrue(settled.audit().workStatusReceipt().matches(REQUEST, answer));
            assertEquals("READ_ONLY_ANSWERED", settled.audit().localTrace().outcome().classification());
            assertEquals(0, fixture.preparations().get());
            assertEquals(0, fixture.generations().get());
            assertEquals(0, preparations.get());
            assertTrue(settled.audit().toolCalls().isEmpty());
        }
    }

    @Test void lostAttributionPresentationCannotRemainComplete() throws Exception {
        for (var options : List.of(new AssistantTurnExecutor.Options().responseMaxChars(30),
                new AssistantTurnExecutor.Options().answerSanitizer(answer -> answer.replace("Test execution:", "")))) {
            var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                    new LlmClient.StreamResult("Unreachable.", List.of())), ModelIdentitySnapshot::empty);
            var cfg = new Config(null);
            var ctx = Context.builder(cfg).memory(new SessionMemory()).llm(fixture.client()).build();
            var processor = new TurnProcessor((raw, ws, context) -> {
                var output = AssistantTurnExecutor.execute(new java.util.ArrayList<>(List.of(
                        dev.talos.spi.types.ChatMessage.user(raw))), ws, (Context) context, options);
                return java.util.Optional.of(new dev.talos.runtime.Result.Ok(output.text()));
            });
            var settled = processor.process(new Session(workspace, cfg), REQUEST, ctx);
            assertTrue(fixture.requests().isEmpty());
            assertTrue(settled.audit().workStatusReceipt().active());
            assertFalse(settled.audit().workStatusReceipt().presentationComplete());
            assertEquals("ADVISORY_ONLY", settled.audit().localTrace().outcome().classification());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Have you written read-settings.properties earlier?",
            "Have you modified read-settings.properties in this session?"})
    void appliedAuditSupportsNamedHistoryWithoutAProvider(String request) throws Exception {
        var cfg = new Config(null);
        var memory = new SessionMemory();
        var trace = dev.talos.runtime.trace.LocalTurnTrace.builder("attribution-prior", "offline", 1,
                        "2026-10-01T00:00:00Z")
                .workspaceHash(dev.talos.runtime.JsonSessionStore.sessionIdFor(workspace)).build();
        var call = new dev.talos.runtime.TurnRecord.ToolCallSummary("talos.write_file", "read-settings.properties",
                List.of("read-settings.properties"), false, "constructed post-commit failure",
                dev.talos.tools.ToolMutationEffects.applied("read-settings.properties"),
                dev.talos.runtime.TurnRecord.ToolCallDisposition.EXECUTED);
        var prior = new dev.talos.runtime.TurnResult(new dev.talos.runtime.Result.Ok("Constructed answer"), null,
                1, java.time.Duration.ZERO, new dev.talos.runtime.TurnAudit(List.of(call), 0, 0, 0,
                dev.talos.runtime.TurnPolicyTrace.empty(), trace));
        new dev.talos.runtime.MemoryUpdateListener(new dev.talos.core.context.ConversationManager(memory,
                new dev.talos.core.context.TokenBudget()), null, memory).onTurnComplete(prior, "Prior write");
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(List.of(
                new LlmClient.StreamResult("Unreachable.", List.of())), ModelIdentitySnapshot::empty);
        var context = Context.builder(cfg).memory(memory).llm(fixture.client()).build();
        var processor = new TurnProcessor((raw, ws, ctx) -> new UnifiedAssistantMode().handle(raw, ws, (Context) ctx));
        var settled = processor.process(new Session(workspace, cfg), request, context);
        String answer = MemoryText.of(settled.result());
        assertTrue(answer.contains("File edit: read-settings.properties. A Talos edit or write with a matching applied effect"),
                answer);
        assertTrue(fixture.requests().isEmpty());
        assertTrue(settled.audit().toolCalls().isEmpty());
        assertTrue(settled.audit().workStatusReceipt().matches(request, answer));
        assertEquals("READ_ONLY_ANSWERED", settled.audit().localTrace().outcome().classification());
        assertTrue(answer.contains("does not establish the final content"));
    }

    private static final class MemoryText {
        static String of(dev.talos.runtime.Result result) {
            return switch (result) {
                case dev.talos.runtime.Result.Ok ok -> ok.text;
                case dev.talos.runtime.Result.Streamed streamed -> streamed.fullText;
                default -> fail("Expected answer " + result);
            };
        }
    }
}
