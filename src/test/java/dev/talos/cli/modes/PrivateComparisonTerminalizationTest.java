package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.DocumentComparisonTool;
import dev.talos.runtime.toolcall.ToolResultModelContextHandoff;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.verification.ReadOnlyAnswerComponents;
import dev.talos.runtime.verification.ReadOnlyWebDiagnosticResult;
import dev.talos.safety.ContentRestrictionScope;
import dev.talos.safety.ContentRestrictions;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Real comparison production, finalization and persistence use a synthetic completed loop envelope. */
class PrivateComparisonTerminalizationTest {
    private static final String PRIVATE_TEXT = "The ceramics workshop meets beside the old observatory on Thursday.";
    @TempDir Path workspace;

    private enum Delivery { INTACT, TRUNCATED, REPLACED, BLANK }

    @AfterEach void clearCaptures() {
        TurnAuditCapture.end();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
        PromptDebugCapture.clear();
    }

    static Stream<Arguments> deliveries() {
        return Stream.of(false, true).flatMap(reversed -> Stream.of(false, true)
                .flatMap(streamed -> Stream.of(Delivery.values())
                        .map(delivery -> Arguments.of(reversed, streamed, delivery))));
    }

    @ParameterizedTest @MethodSource("deliveries")
    void privateHistoryProjectionDoesNotReplaceActualDeliveryEvidence(
            boolean reversed, boolean streamed, Delivery delivery) throws Exception {
        Captured captured = capture(reversed, streamed, delivery, false, false);
        String expectedStatus = delivery == Delivery.INTACT ? "COMPLETE" : "ADVISORY_ONLY";
        String expectedClassification = delivery == Delivery.INTACT ? "READ_ONLY_ANSWERED" : "ADVISORY_ONLY";

        assertEquals(expectedStatus, captured.turn().audit().localTrace().outcome().status());
        assertEquals(expectedClassification, captured.turn().audit().localTrace().outcome().classification());
        assertEquals(expectedStatus, captured.persistedTrace().outcome().status());
        assertEquals(expectedClassification, captured.persistedTrace().outcome().classification());
        assertEquals("NOT_REQUESTED", captured.persistedTrace().outcome().mutationStatus());
        assertEquals("NOT_RUN", captured.persistedTrace().outcome().verificationStatus());
        if (delivery == Delivery.INTACT) assertTrue(captured.deliveredAnswer().contains(PRIVATE_TEXT));
        else assertNotEquals(captured.completeAnswer(), captured.deliveredAnswer().strip());
    }

    @Test
    void historyDenialStillProjectsPrivateTraceWhenArtifactsAreOptedIn() throws Exception {
        Captured captured = capture(false, false, Delivery.INTACT, true, false);
        LocalTurnTrace trace = captured.persistedTrace();

        assertTrue(captured.turn().audit().contentRestrictions().artifactAllowed());
        assertFalse(captured.turn().audit().contentRestrictions().historyAllowed());
        assertEquals("COMPLETE", trace.outcome().status());
        assertEquals("READ_ONLY_ANSWERED", trace.outcome().classification());
        assertTrue(trace.events().stream().noneMatch(event -> List.of("READ_ONLY_ANSWER_COMPONENTS_COMMITTED",
                "TERMINAL_ANSWER_SELECTED", "ROUTED_ANSWER_CAPTURED").contains(event.type())));
        assertEquals(1, trace.events().stream().filter(event -> "DOCUMENT_CONTENT_WITHHELD".equals(event.type())).count());
    }

    @Test
    void unrestrictedTraceBoundaryDistinguishesAnalyticalDeliveryAndRetention() {
        // Analytical string projection only. No private document or persistence permission is simulated.
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        LocalTurnTraceCapture.begin("comparison-delivery-projection", "fixture", 1,
                "2026-09-24T00:00:00Z", "workspace", "ask", "fixture", "fixture", "Compare the fixture strings.");
        var components = new ReadOnlyAnswerComponents(List.of(new ReadOnlyAnswerComponents.Component(
                "comparison", "DOCUMENT_COMPARISON", ReadOnlyAnswerComponents.Support.ESTABLISHED,
                "ANALYTICAL_FIXTURE", "The fixture text changed from before to after.", List.of(), "")), true);
        String delivered = components.render();
        String retained = "[Synthetic retention projection.]";
        LocalTurnTraceCapture.recordReadOnlyAnswerComponentsPresented(components, delivered);
        LocalTurnTraceCapture.recordDeterministicAnswerSelected(delivered);
        LocalTurnTraceCapture.recordOutcome("COMPLETE", "NOT_RUN", "NOT_REQUIRED", "NOT_REQUESTED", "READ_ONLY_ANSWERED");
        LocalTurnTraceCapture.recordAnswerTransformedForRouting(retained,
                LocalTurnTraceCapture.AnswerTransformationReason.SESSION_COMMIT_NORMALIZATION);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(delivered, retained);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("COMPLETE", trace.outcome().status());
        assertEquals("READ_ONLY_ANSWERED", trace.outcome().classification());
        TurnTraceEvent committed = event(trace, "READ_ONLY_ANSWER_COMPONENTS_COMMITTED");
        assertEquals(Boolean.TRUE, committed.data().get("deliveredIntact"));
        assertEquals(Boolean.FALSE, committed.data().get("retainedIntact"));
        String retainedHash = "sha256:" + ReadOnlyWebDiagnosticResult.textHash(retained);
        assertEquals(retainedHash, committed.data().get("answerHash"));
        assertEquals(retainedHash,
                event(trace, "TERMINAL_ANSWER_SELECTED").data().get("answerHash"));
        assertEquals(retainedHash,
                event(trace, "ROUTED_ANSWER_CAPTURED").data().get("answerHash"));
        assertEquals("SESSION_COMMIT_NORMALIZATION", event(trace, "ANSWER_TRANSFORMED").data().get("reasonCode"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unsupportedExplanationRemainsIncompleteDespiteIntactPrivateDiff(boolean reversed) throws Exception {
        Captured captured = capture(reversed, false, Delivery.INTACT, false, true);

        assertTrue(captured.deliveredAnswer().contains(PRIVATE_TEXT));
        assertTrue(captured.deliveredAnswer().startsWith("\n[Answer incomplete:"));
        assertEquals("ADVISORY_ONLY", captured.turn().audit().localTrace().outcome().status());
        assertEquals("ADVISORY_ONLY", captured.persistedTrace().outcome().classification());
        assertEquals("NOT_REQUESTED", captured.persistedTrace().outcome().mutationStatus());
    }

    private Captured capture(boolean reversed, boolean streamed, Delivery delivery,
                             boolean allowArtifacts, boolean unsupportedExplanation) throws Exception {
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(PRIVATE_TEXT);
            try (var output = Files.newOutputStream(workspace.resolve("private.docx"))) { document.write(output); }
        }
        Files.writeString(workspace.resolve("public.txt"), "Public counterpart.");
        String counterpart = "public.txt";
        if (allowArtifacts) {
            // Artifact permission is required for both operands. Raw artifact retention is denied for ordinary text.
            counterpart = "counterpart.docx";
            try (var document = new XWPFDocument()) {
                document.createParagraph().createRun().setText("Public counterpart.");
                try (var output = Files.newOutputStream(workspace.resolve(counterpart))) { document.write(output); }
            }
        }
        Config config = new Config(null);
        config.data.put("document_extraction", Map.of("enabled", true, "word", Map.of("enabled", true)));
        config.data.put("privacy", Map.of("mode", "private", "document_extraction",
                Map.of("allow_send_to_model", false, "persist_raw_artifacts", allowArtifacts)));
        String left = reversed ? counterpart : "private.docx";
        String right = reversed ? "private.docx" : counterpart;
        String request = unsupportedExplanation
                ? "Explain how " + left + " differs from " + right + "."
                : "Compare " + left + " and " + right + ".";
        var expectedAnswer = new AtomicReference<String>();
        var deliveredAnswer = new AtomicReference<String>();
        var activeTraceId = new AtomicReference<String>();
        var memory = new ConversationManager(new SessionMemory(), new TokenBudget());
        var store = new JsonSessionStore(workspace.resolve("session-store"));
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.now());
        var processor = new TurnProcessor((raw, path, context) -> {
            activeTraceId.set(LocalTurnTraceCapture.currentTraceId());
            assertEquals(request, TurnUserRequestCapture.get());
            TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(raw));
            var produced = produce(config, left, right);
            assertTrue(produced.success(), produced.errorMessage());
            var handoff = ToolResultModelContextHandoff.decide(new ToolCall(DocumentComparisonTool.NAME,
                    Map.of("left", left, "right", right)), null, "", produced, null);
            assertTrue(handoff.contentWithheldFromModelContext());
            assertFalse(handoff.modelResult().output().contains(PRIVATE_TEXT));
            ExecutionOutcome outcome = finish(request, produced);
            assertEquals(unsupportedExplanation ? ExecutionOutcome.CompletionStatus.ADVISORY_ONLY
                    : ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains(PRIVATE_TEXT), outcome.finalAnswer());
            expectedAnswer.set(outcome.finalAnswer());
            String body = switch (delivery) {
                case INTACT -> outcome.finalAnswer();
                case TRUNCATED -> outcome.finalAnswer().substring(0, outcome.finalAnswer().indexOf(PRIVATE_TEXT));
                case REPLACED -> "Only an unrelated status message was returned.";
                case BLANK -> "";
            };
            // Match the mode's display layout without using prior PRESENTED bytes as terminal evidence.
            body = "\n" + body + "\n\n";
            deliveredAnswer.set(body);
            return Optional.of(streamed ? new Result.Streamed(body, "Display-only suffix.") : new Result.Ok(body));
        });
        processor.addListener(new MemoryUpdateListener(memory));
        TurnResult turn;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            turn = processor.process(new Session(workspace, config), request, Context.builder(config).build());
            writer.save(new SessionData(sessionId, workspace.toString(), "", 1, Instant.now(),
                    memory.buildHistory().stream().map(message -> new SessionData.Turn(message.role(), message.content())).toList()));
        }
        String resultText = streamed ? ((Result.Streamed) turn.result()).fullText : ((Result.Ok) turn.result()).text;
        assertEquals(deliveredAnswer.get(), resultText);
        assertFalse(turn.audit().contentRestrictions().modelAllowed());
        assertFalse(turn.audit().contentRestrictions().historyAllowed());
        assertEquals(allowArtifacts, turn.audit().contentRestrictions().artifactAllowed());
        var rows = store.loadTurns(sessionId);
        assertEquals(1, rows.size());
        assertEquals(request, rows.getFirst().userInput());
        assertEquals(1, turn.turnNumber());
        assertEquals(turn.turnNumber(), rows.getFirst().turnNumber());
        assertEquals(activeTraceId.get(), rows.getFirst().traceId());
        assertEquals(activeTraceId.get(), turn.audit().localTrace().traceId());
        assertEquals(ContentRestrictions.WITHHELD, rows.getFirst().assistantText());
        if (delivery == Delivery.BLANK) assertTrue(memory.buildHistory().isEmpty());
        else assertEquals(ContentRestrictions.WITHHELD, memory.buildHistory().getLast().content());
        LocalTurnTrace trace = store.loadTrace(sessionId, rows.getFirst().traceId()).orElseThrow();
        assertEquals(activeTraceId.get(), trace.traceId());
        assertEquals(turn.turnNumber(), trace.turnNumber());
        assertEquals(JsonSessionStore.sessionIdFor(workspace), trace.sessionId());
        assertEquals(1, trace.events().stream().filter(event -> "TRACE_COMPLETED".equals(event.type())).count());
        assertFalse(trace.toString().contains(PRIVATE_TEXT));
        try (var files = Files.walk(workspace.resolve("session-store"))) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                assertFalse(Files.readString(file).contains(PRIVATE_TEXT), file.toString());
        }
        assertFalse(TurnAuditCapture.isActive());
        assertFalse(LocalTurnTraceCapture.isActive());
        assertEquals(ContentRestrictions.UNRESTRICTED, ContentRestrictionScope.current());
        return new Captured(expectedAnswer.get(), deliveredAnswer.get(), turn, trace);
    }

    private ToolResult produce(Config config, String left, String right) {
        var registry = new ToolRegistry();
        registry.register(new DocumentComparisonTool());
        var gateway = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var context = Context.builder(config).sandbox(new Sandbox(workspace, config.data)).build();
        return gateway.executeTool(new Session(workspace, config),
                new ToolCall(DocumentComparisonTool.NAME, Map.of("left", left, "right", right)), context);
    }

    private ExecutionOutcome finish(String request, ToolResult produced) {
        var messages = List.of(ChatMessage.user(request));
        var toolOutcome = new ToolCallLoop.ToolOutcome(DocumentComparisonTool.NAME, "", true, false, false,
                produced.output(), "");
        var loop = new ToolCallLoop.LoopResult("Untrusted comparison prose.", 1, 1, List.of(DocumentComparisonTool.NAME),
                messages, 0, 0, false, 0, List.of(), 0, 0, 0, 0, List.of(toolOutcome));
        return ExecutionOutcome.fromToolLoop(loop.finalAnswer(), messages, loop, workspace, 0);
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        var events = trace.events().stream().filter(event -> type.equals(event.type())).toList();
        assertEquals(1, events.size(), type);
        return events.getFirst();
    }

    private record Captured(String completeAnswer, String deliveredAnswer, TurnResult turn,
                            LocalTurnTrace persistedTrace) {}
}
