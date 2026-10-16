package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.Session;
import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnProcessor;
import dev.talos.spi.types.PromptDebugCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssistantAdvisoryHandoffBoundaryTest {
    @TempDir Path workspace;

    @AfterEach void clear() {
        PromptDebugCapture.clear();
        dev.talos.runtime.trace.LocalTurnTraceCapture.clear();
        dev.talos.runtime.TurnTaskContractCapture.clear();
        dev.talos.runtime.TurnTraceCapture.clear();
        if (dev.talos.runtime.TurnAuditCapture.isActive()) dev.talos.runtime.TurnAuditCapture.end();
    }

    @Test void insufficientMandatoryPresentationBudgetDoesNotDispatch() throws Exception {
        var fixture = ScriptedNativeLlmClient.recordingWithJsonSchemaCapability(
                List.of(new LlmClient.StreamResult("{}", List.of())), 16_384, true);
        var memory = new SessionMemory();
        memory.update("What is pending?", "Packaging remains pending with Mara.");
        var config = new Config(null);
        var options = new AssistantTurnExecutor.Options();
        options.responseMaxChars(80);
        var processor = new TurnProcessor((raw, ws, context) -> java.util.Optional.of(
                new dev.talos.runtime.Result.Ok(AssistantTurnExecutor.execute(
                        new java.util.ArrayList<>(List.of(dev.talos.spi.types.ChatMessage.user(raw))),
                        ws, (Context) context, options).text())));
        var result = processor.process(new Session(workspace, config), AssistantWorkStatusBoundaryTest.HANDOFF,
                Context.builder(config).memory(memory).llm(fixture.client()).build());
        assertEquals(0, fixture.requests().size(), "Mandatory runtime and gap reservation precedes generation");
        assertTrue(((dev.talos.runtime.Result.Ok) result.result()).text.length() <= 80);
        assertFalse(result.audit().workStatusReceipt().presentationComplete());
        assertEquals("ADVISORY_ONLY", result.audit().localTrace().outcome().classification());
        assertTrue(result.audit().toolCalls().isEmpty());
    }

    @Test void unsupportedQuestionRemainsVisibleWithoutDispatch() throws Exception {
        var result = run("Give me a short handoff. Who owns packaging?", new AssistantTurnExecutor.Options(), null);
        String answer = ((dev.talos.runtime.Result.Ok) result.result()).text;
        assertTrue(answer.contains("Who owns packaging"), answer);
        assertTrue(answer.contains("project meaning remains unresolved"), answer);
        assertTrue(answer.contains("Request coverage remains incomplete"), answer);
        assertFalse(result.audit().workStatusReceipt().presentationComplete());
    }

    @Test void customSecretInUnsupportedQuestionIsWithheldBeforeLiteralEncoding() throws Exception {
        var redactor = new dev.talos.core.security.Redactor(java.util.Map.of("redact", java.util.Map.of(
                "secrets", List.of("Acme_Internal"))));
        var result = run("Give me a short handoff. What remains Acme_Internal?", new AssistantTurnExecutor.Options(), redactor);
        String answer = ((dev.talos.runtime.Result.Ok) result.result()).text;
        assertTrue(answer.contains("Original question withheld"), answer);
        assertFalse(answer.contains("Acme"), answer);
    }

    @Test void typedCancellationDoesNotBecomeAnOrdinaryAdvisory() throws Exception {
        var config = new Config(null);
        var fixture = ScriptedNativeLlmClient.recordingResponder(request -> {
            throw new java.util.concurrent.CancellationException("Synthetic operator cancellation");
        }, 16_384);
        var memory = new SessionMemory();
        memory.update("Pending?", "Packaging remains pending.");
        var processor = new TurnProcessor((raw, ws, context) -> new UnifiedAssistantMode().handle(raw, ws, (Context) context));
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> processor.process(new Session(workspace, config), AssistantWorkStatusBoundaryTest.HANDOFF,
                        Context.builder(config).memory(memory).llm(fixture.client()).build()));
        assertEquals(1, fixture.requests().size());
    }

    private dev.talos.runtime.TurnResult run(String request, AssistantTurnExecutor.Options options,
                                            dev.talos.core.security.Redactor redactor) throws Exception {
        var config = new Config(null);
        var memory = new SessionMemory();
        memory.update("Pending?", "Packaging remains pending.");
        var processor = new TurnProcessor((raw, ws, context) -> java.util.Optional.of(new dev.talos.runtime.Result.Ok(
                AssistantTurnExecutor.execute(new java.util.ArrayList<>(List.of(dev.talos.spi.types.ChatMessage.user(raw))),
                        ws, (Context) context, options).text())));
        var fixture = ScriptedNativeLlmClient.recordingWithJsonSchemaCapability(
                List.of(new LlmClient.StreamResult("{}", List.of())), 16_384, true);
        var result = processor.process(new Session(workspace, config), request,
                Context.builder(config).memory(memory).llm(fixture.client()).redactor(redactor).build());
        assertEquals(0, fixture.requests().size(), "Unsupported request meaning cannot be bypassed by generation");
        return result;
    }
}
