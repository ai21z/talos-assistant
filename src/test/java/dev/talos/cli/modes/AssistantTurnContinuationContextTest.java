package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnAuditCapture;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.outcome.NoToolAnswerTruthfulnessGuard;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted generation exercises the executor, provider transport and real filesystem read boundary. */
class AssistantTurnContinuationContextTest {
    private static final String PRIOR_PROPOSAL =
            "I propose changing the selected label to cedar. That proposal was not applied.";
    private static final String CURRENT_CONTENT = "The selected label is oak.\n";
    private static final String CONCEPTUAL = "Another thing I've been thinking about. If an AI runs locally and has "
            + "policies, guards and a judge checking its answers, does that mean its answers stop being a black box "
            + "and hallucinations are solved? Give me an honest short explanation, not marketing. "
            + "No web search or project inspection is needed.";
    private static final String CONCEPTUAL_ANSWER = "Local execution determines where an answer is generated. "
            + "It does not by itself make the model's internal reasoning observable or establish factual correctness. "
            + "A policy can constrain permitted actions, and a guard can check a specific property of a response. "
            + "Those checks only establish the properties they actually test. An independent judge can sometimes "
            + "catch errors, but its own conclusions also need evidence. Agreement between two models can preserve "
            + "a shared mistake. Useful assurance comes from examining sources, testing concrete claims, recording "
            + "what was actually executed, and admitting what remains unverified. This explanation concerns the "
            + "general distinction between execution location, observability, and correctness. None of these "
            + "features alone makes hallucinations disappear.";

    @TempDir Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        dev.talos.runtime.TurnUserRequestCapture.clear();
        LastPromptCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void freshReadContinuationRetainsTheExactTurnIdentityAndPreviousProposal() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), CURRENT_CONTENT);
        String request = "Which model is actually answering me? Read notes.md again, explain the current selected "
                + "label and compare it with your earlier proposal. Do not edit files or run commands.";
        String scriptedAnswer = "Talos is using gpt-oss-20b. The current notes select oak. My earlier proposal "
                + "was cedar and was not applied. No files were changed and no commands were run.";
        var fixture = ScriptedNativeLlmClient.recordingWithContextWindow(new Config(null), List.of(
                new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                        "read-current-notes", "talos.read_file", Map.of("path", "notes.md")))),
                new LlmClient.StreamResult(scriptedAnswer, List.of())), 16_384);
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        try (var client = fixture.client()) {
            assertFalse(client.modelIdentity().available(), "Client identity differs from the explicit qualified snapshot");
            client.setToolSpecs(registry.descriptors().stream().map(descriptor ->
                    new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList());
            var context = Context.builder(new Config(null)).llm(client)
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 3)).build();
            var messages = new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                    ChatMessage.user("The notes select pine. What change would you propose? Do not apply it."),
                    ChatMessage.assistant(PRIOR_PROPOSAL), ChatMessage.user(request)));
            beginTrace(request);
            TurnAuditCapture.begin();

            var output = AssistantTurnExecutor.execute(messages, workspace, context,
                    new AssistantTurnExecutor.Options().runtimeIdentity(qualified("gpt-oss-20b")));
            var audit = TurnAuditCapture.end();

            assertEquals(2, fixture.requests().size(), "One initial request and one actual post-read continuation");
            var initial = fixture.requests().getFirst();
            var continuation = fixture.requests().getLast();
            assertTrue(initial.messages.stream().anyMatch(message -> PRIOR_PROPOSAL.equals(message.content())));
            assertTrue(identityFrame(initial.messages).contains("Runtime-qualified model: gpt-oss-20b."));
            assertTrue(LocalTurnTraceCapture.complete().events().stream()
                    .anyMatch(event -> "REPAIR_DECISION_RECORDED".equals(event.type())
                            && "READ_ONLY_INSPECTION_CONTINUED".equals(event.data().get("status"))),
                    "The regression must reach the bounded read continuation, not another reprompt path: "
                            + continuation.messages);
            assertEquals(1, audit.toolCalls().size(), output.text());
            assertEquals("talos.read_file", audit.toolCalls().getFirst().name());
            assertTrue(audit.toolCalls().getFirst().success());
            assertEquals(CURRENT_CONTENT, Files.readString(workspace.resolve("notes.md")));
            assertAll(
                    () -> assertTrue(identityFrame(continuation.messages)
                            .contains("Runtime-qualified model: gpt-oss-20b.")),
                    () -> assertTrue(continuation.messages.stream()
                            .anyMatch(message -> message.content().contains(PRIOR_PROPOSAL)),
                            "The original unapplied cedar proposal must be available to the post-read answer"),
                    () -> assertTrue(continuation.messages.stream().anyMatch(message -> "tool".equals(message.role())
                            && message.content().contains(CURRENT_CONTENT.strip())),
                            "Fresh oak evidence must still be supplied as an authenticated tool result")
            );
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void longConceptualAnswerWithNoEvidenceObligationHasNoWarningOrRetry(boolean streamed) {
        assertTrue(CONCEPTUAL_ANSWER.length() > NoToolAnswerTruthfulnessGuard.UNGROUNDED_MIN_CHARS);
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(
                CONCEPTUAL_ANSWER, ModelIdentitySnapshot::empty);
        try (var client = fixture.client()) {
            var chunks = new ArrayList<String>();
            var builder = Context.builder(new Config(null)).llm(client);
            if (streamed) builder.streamSink(chunks::add);
            beginTrace(CONCEPTUAL);
            TurnAuditCapture.begin();

            var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                            ChatMessage.user(CONCEPTUAL))), workspace, builder.build(), new AssistantTurnExecutor.Options());
            var audit = TurnAuditCapture.end();

            assertTrue(fixture.requests().getFirst().messages.stream().anyMatch(message -> "system".equals(message.role())
                    && message.content().startsWith("[CurrentTurnCapability]")
                    && message.content().contains("evidenceObligation: NONE")));
            assertTrue(audit.toolCalls().isEmpty());
            assertFalse(output.streamed(), "Selected answers must be returned for settled presentation");
            assertTrue(chunks.isEmpty(), "Provider transport fragments must remain private");
            assertAll(
                    () -> assertEquals(1, fixture.requests().size(), "NONE must not cause a buffered grounding retry"),
                    () -> assertEquals(CONCEPTUAL_ANSWER, output.text()),
                    () -> assertFalse(String.join("", chunks).contains("[Grounding check:"))
            );
        }
    }

    private void beginTrace(String request) {
        LocalTurnTraceCapture.begin("continuation-context", "offline", 1,
                "2026-09-29T00:00:00Z", "workspace", "test", "llama_cpp", "fixture", request);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitUnnamedFileRequestCannotFinishWithAnUnqualifiedFabrication(boolean streamed) {
        String request = "Read the main HTML, CSS, and JS files and tell me 3 concrete improvement opportunities. "
                + "Use evidence from the actual files, not generic website advice.";
        String fabricated = "The source files contain a timer and a missing validation function. ".repeat(20);
        var fixture = ScriptedNativeLlmClient.recordingTextToolFallbackWithIdentity(
                fabricated, ModelIdentitySnapshot::empty);
        try (var client = fixture.client()) {
            var builder = Context.builder(new Config(null)).llm(client);
            if (streamed) builder.streamSink(ignored -> {});
            beginTrace(request);
            TurnAuditCapture.begin();
            var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(ChatMessage.system("You are Talos."),
                    ChatMessage.user(request))), workspace, builder.build(), new AssistantTurnExecutor.Options());
            var audit = TurnAuditCapture.end();
            assertTrue(audit.toolCalls().isEmpty());
            assertEquals(streamed ? 1 : 2, fixture.requests().size());
            assertTrue(fixture.requests().getFirst().messages.stream().anyMatch(message -> message.content()
                    .contains("evidenceObligation: FILE_CONTENT_LOOKUP_REQUIRED")));
            assertTrue(output.text().startsWith(dev.talos.runtime.policy.EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX),
                    output.text());
            assertNotEquals(fabricated, output.text());
        }
    }

    private static String identityFrame(List<ChatMessage> messages) {
        var frames = messages.stream().filter(message -> "system".equals(message.role())
                && message.content().startsWith("[RuntimeModelIdentity]")).toList();
        assertEquals(1, frames.size(), "Exactly one runtime-owned identity projection is required");
        return frames.getFirst().content();
    }

    private static ModelIdentitySnapshot qualified(String model) {
        return ModelIdentitySnapshot.builder().backend("llama_cpp").configuredModel(model)
                .resolvedProfile(model).artifactState("EXACT").servedIdentityState("MATCHED")
                .resolution("QUALIFIED").propsState("AVAILABLE").chatAvailable(true).build();
    }
}
