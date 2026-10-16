package dev.talos.runtime;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Synthetic provider choices exercise real reads, executor settlement, durable turn logging,
 * trace reload and the next turn. This does not measure live-model reasoning quality.
 */
class ReadOnlyEvidenceLimitationPersistenceTest {
    private static final String REQUEST =
            "Please inspect retry.py again. With the configured retry limit, list the attempt numbers that should run "
                    + "and explain when should_retry becomes false. Use the current files, not just your previous answer.";
    private static final String LIMITED_ANSWER =
            "The acquired source observations exceed the bounded answer context. "
                    + "A complete diagnosis cannot be established without discarding evidence. "
                    + "No further tools were executed and no files were changed. The diagnosis remains incomplete.";
    private static final String NEXT_REQUEST = "Explain briefly why the sky appears blue.";
    private static final String NEXT_ANSWER =
            "Sunlight scatters in the atmosphere. Shorter blue wavelengths scatter more strongly.";

    @AfterEach void clearTurnState() {
        TurnTraceCapture.consume();
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        TurnSourceEvidenceCapture.clear();
        LocalTurnTraceCapture.clear();
        PromptDebugCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void blankContinuationAfterCompleteReadsPersistsAnExplicitLimitation(@TempDir Path dir) throws Exception {
        assertContinuationLimitation(dir, REQUEST, 4, false, 2,
                TerminalReadOnlyStopAnswer.INSPECTION_UNAVAILABLE_REASON,
                "The bounded diagnosis continuation did not produce a usable answer or tool continuation.");
    }

    @Test void mandatoryFailedContinuationAfterCompleteReadsPersistsNoAssistantAnswer(@TempDir Path dir) throws Exception {
        assertContinuationLimitation(dir, REQUEST, 4, true, 2, "", "");
    }

    @Test void oversizedCompleteContinuationRequestPersistsItsExactLimitReason(@TempDir Path dir) throws Exception {
        String request = REQUEST + "\nAdditional background: " + "Neutral background detail. ".repeat(1_400);
        assertContinuationLimitation(dir, request, 4, false, 1,
                TerminalReadOnlyStopAnswer.INSPECTION_REQUEST_LIMIT_REASON,
                "The bounded diagnosis request cannot retain the acquired evidence safely.");
    }

    @Test void acquisitionCapWithARequiredReadPendingPersistsItsExactLimitReason(@TempDir Path dir) throws Exception {
        assertContinuationLimitation(dir, REQUEST + " Also inspect support1.txt.", 1, false, 1,
                TerminalReadOnlyStopAnswer.ACQUISITION_LIMIT_REASON,
                "Required diagnosis reads remain, but the tool iteration limit was reached.");
    }

    private void assertContinuationLimitation(Path dir, String request, int iterationAllowance,
            boolean providerFailure, int expectedRequests, String reason, String expectedExplanation) throws Exception {
        Path workspace = Files.createDirectory(dir.resolve("workspace"));
        Path storeRoot = dir.resolve("store");
        Map<String, String> bodies = Map.of(
                "retry.py", "def should_retry(attempt, limit):\n    return attempt < limit\n",
                "support1.txt", "The configured limit is two.\n");
        for (var entry : bodies.entrySet()) Files.writeString(workspace.resolve(entry.getKey()), entry.getValue());
        var initial = new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                "read-retry", "talos.read_file", Map.of("path", "retry.py"))));
        Config config = new Config();
        config.data.put("limits", Map.of("llm_context_max_tokens", 65_536));
        String failureCanary = "SYNTHETIC_CONTINUATION_PRIVATE_FAILURE";
        var failure = new dev.talos.spi.EngineException.ConnectionFailed(failureCanary, null);
        var recorded = providerFailure
                ? ScriptedNativeLlmClient.recordingThenFailureWithContextWindow(List.of(initial), 65_536,
                    failure)
                : ScriptedNativeLlmClient.recordingWithContextWindow(config, List.of(initial,
                    new LlmClient.StreamResult("", List.of())), 65_536);
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.parse("2026-09-27T00:00:00Z"));
        var store = new JsonSessionStore(storeRoot);
        try (LlmClient llm = recorded.client(); var writer = store.acquireSessionWriter(sessionId)) {
            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            List<ToolSpec> specs = registry.descriptors().stream().map(descriptor -> new ToolSpec(
                    descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList();
            var context = Context.builder(config).llm(llm)
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).nativeToolSpecs(specs)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), iterationAllowance))
                    .build();
            TurnRouter router = (raw, routedWorkspace, ignoredContext) -> {
                var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                                ChatMessage.system("You are Talos."), ChatMessage.user(raw))),
                        routedWorkspace, context, new AssistantTurnExecutor.Options());
                return Optional.of(new Result.Ok(output.text()));
            };
            var processor = new TurnProcessor(router);
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            if (providerFailure) {
                assertSame(failure, assertThrows(dev.talos.spi.EngineException.ConnectionFailed.class,
                        () -> processor.process(new Session(workspace, config), request, context)));
                assertEquals(expectedRequests, recorded.requests().size(), "No fallback generation may hide the failure");
                assertTrue(recorded.requests().getLast().messages.stream().anyMatch(message ->
                        "tool".equals(message.role()) && message.content().contains("return attempt < limit")),
                        "A real source read must precede mandatory provider failure");
                var reopened = new JsonSessionStore(storeRoot);
                assertEquals(1, reopened.loadTurns(sessionId).size());
                var row = reopened.loadTurns(sessionId).getFirst();
                assertEquals("failed", row.status());
                assertEquals("", row.userInput());
                assertEquals("", row.assistantText());
                assertTrue(row.toolCalls().isEmpty(), "Failed turns retain the safe terminal evidence projection");
                var trace = reopened.loadTrace(sessionId, row.traceId()).orElseThrow();
                var terminal = trace.events().stream().filter(event -> "TURN_SETTLED".equals(event.type()))
                        .findFirst().orElseThrow();
                assertEquals("FAILED", terminal.data().get("settlementKind"));
                assertEquals("GENERATION_COORDINATION", terminal.data().get("stage"));
                assertEquals("ENGINE_CONNECTION_FAILED", terminal.data().get("errorKind"));
                assertTrue(trace.events().stream().anyMatch(event -> "GENERATION_ATTEMPT_FAILED".equals(event.type())));
                try (var paths = Files.walk(storeRoot)) {
                    for (Path path : paths.filter(Files::isRegularFile)
                            .filter(path -> !path.startsWith(storeRoot.resolve(".ownership"))).toList())
                        assertFalse(Files.readString(path).contains(failureCanary));
                }
                for (var entry : bodies.entrySet()) assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey())));
                return;
            }
            TurnResult result = processor.process(new Session(workspace, config), request, context);
            String answer = assertInstanceOf(Result.Ok.class, result.result()).text;
            assertTrue(answer.contains(expectedExplanation), answer);
            assertTrue(answer.contains("diagnosis remains incomplete"), answer);
            if (iterationAllowance > 1) assertFalse(answer.contains("Required diagnosis reads remain"), answer);
            assertEquals(expectedRequests, recorded.requests().size(), "No fallback generation may reopen the stop");

            var reopened = new JsonSessionStore(storeRoot);
            assertEquals(1, reopened.loadTurns(sessionId).size());
            var row = reopened.loadTurns(sessionId).getFirst();
            assertEquals("ok", row.status());
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(answer, request), row.assistantText());
            assertTrue(row.assistantText().contains(expectedExplanation), row.assistantText());
            assertEquals(1, row.toolCalls().size(), "A real read must precede the tested continuation stop");
            var read = row.toolCalls().getFirst();
            assertEquals("talos.read_file", read.name());
            assertEquals("retry.py", Path.of(read.pathHint()).getFileName().toString());
            assertTrue(read.success());
            assertEquals(TurnRecord.ToolCallDisposition.EXECUTED, read.executionDisposition());
            assertFalse(read.mutationEffects().hasEffects());
            var trace = reopened.loadTrace(sessionId, row.traceId()).orElseThrow();
            assertEquals("ADVISORY_ONLY", trace.outcome().status());
            assertEquals("ADVISORY_ONLY", trace.outcome().classification());
            assertEquals("NOT_RUN", trace.outcome().verificationStatus());
            assertTrue(trace.warnings().stream().anyMatch(warning ->
                    "READ_ONLY_ANSWER_INCOMPLETE".equals(warning.code()) && reason.equals(warning.message())),
                    trace.warnings().toString());
            if (TerminalReadOnlyStopAnswer.ACQUISITION_LIMIT_REASON.equals(reason)) {
                assertTrue(row.assistantText().contains(
                        dev.talos.runtime.policy.EvidenceObligationVerifier.MISSING_EVIDENCE_PREFIX), row.assistantText());
                assertTrue(trace.warnings().stream().anyMatch(warning -> "MISSING_EVIDENCE".equals(warning.code())),
                        "Preserving the precise stop explanation must not clear the missing-evidence warning");
            }
            if (TerminalReadOnlyStopAnswer.INSPECTION_REQUEST_LIMIT_REASON.equals(reason)) {
                assertTrue(row.assistantText().contains("The request exceeds the bounded interpretation limit."),
                        "The request-coverage limitation must remain alongside the precise acquisition stop");
                assertTrue(trace.warnings().stream().anyMatch(warning -> "MISSING_EVIDENCE".equals(warning.code())),
                        "An oversized request must not acquire a complete-evidence verdict");
            }
            assertEquals(1, trace.events().stream().filter(event -> "TOOL_EXECUTED".equals(event.type())).count());
            for (var entry : bodies.entrySet()) assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey())));
        }
    }

    @Test void retentionOverflowSurvivesSettlementAndReloadWithoutContaminatingNextTurn(@TempDir Path dir)
            throws Exception {
        Path workspace = Files.createDirectory(dir.resolve("workspace"));
        Path storeRoot = dir.resolve("store");
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("retry.py", "def should_retry(attempt, limit):\n    return attempt < limit\n");
        for (int index = 1; index <= 8; index++)
            bodies.put("support" + index + ".txt", "Distinct support observation " + index + ".\n");
        for (var entry : bodies.entrySet()) Files.writeString(workspace.resolve(entry.getKey()), entry.getValue());

        var contract = TaskContractResolver.resolveFromMessages(List.of(ChatMessage.user(REQUEST))).contract();
        assertEquals(TaskType.DIAGNOSE_ONLY, contract.type(), "Fixture must retain natural diagnosis admission");
        assertFalse(contract.mutationAllowed());
        assertFalse(contract.verificationRequired());
        var targets = new LinkedHashSet<>(contract.expectedTargets());
        targets.addAll(contract.sourceEvidenceTargets());
        assertEquals(Set.of("retry.py"), targets, "Supporting reads are not additional declared obligations");

        List<ChatMessage.NativeToolCall> reads = bodies.keySet().stream()
                .map(path -> new ChatMessage.NativeToolCall("read-" + path, "talos.read_file",
                        Map.<String, Object>of("path", path))).toList();
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult("", reads),
                new LlmClient.StreamResult(NEXT_ANSWER, List.of())), 16_384);
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.parse("2026-09-27T00:00:00Z"));
        var store = new JsonSessionStore(storeRoot);
        try (LlmClient llm = recorded.client(); var writer = store.acquireSessionWriter(sessionId)) {
            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            List<ToolSpec> specs = registry.descriptors().stream().map(descriptor -> new ToolSpec(
                    descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList();
            var context = Context.builder(new Config()).llm(llm)
                    .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry).nativeToolSpecs(specs)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 4))
                    .build();
            TurnRouter router = (raw, routedWorkspace, ignoredContext) -> {
                var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                                ChatMessage.system("You are Talos."), ChatMessage.user(raw))),
                        routedWorkspace, context, new AssistantTurnExecutor.Options());
                return Optional.of(new Result.Ok(output.text()));
            };
            var processor = new TurnProcessor(router);
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            var session = new Session(workspace, new Config());

            TurnResult first = processor.process(session, REQUEST, context);
            var firstText = assertInstanceOf(Result.Ok.class, first.result()).text;
            assertTrue(firstText.contains(LIMITED_ANSWER), firstText);
            assertEquals(1, recorded.requests().size(), "Overflow must stop before another provider request");
            assertEquals(9, first.audit().toolCalls().size(), "One batch really executes nine distinct source reads");

            var reopened = new JsonSessionStore(storeRoot);
            assertEquals(1, reopened.loadTurns(sessionId).size());
            TurnRecord row = reopened.loadTurns(sessionId).getFirst();
            assertEquals("ok", row.status(), "An incomplete diagnosis is an ordinary conversational settlement");
            assertEquals(REQUEST, row.userInput());
            assertEquals(LIMITED_ANSWER, row.assistantText());
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(firstText, REQUEST), row.assistantText());
            assertEquals(9, row.toolCalls().size());
            assertEquals(bodies.keySet(), row.toolCalls().stream().map(call -> Path.of(call.pathHint())
                    .getFileName().toString()).collect(Collectors.toSet()));
            assertTrue(row.toolCalls().stream().allMatch(call -> call.success()
                    && "talos.read_file".equals(call.name())
                    && call.executionDisposition() == TurnRecord.ToolCallDisposition.EXECUTED
                    && !call.mutationEffects().hasEffects()));
            LocalTurnTrace trace = reopened.loadTrace(sessionId, row.traceId()).orElseThrow();
            assertEquals("DIAGNOSE_ONLY", trace.taskContract().type());
            assertEquals("ADVISORY_ONLY", trace.outcome().status());
            assertEquals("ADVISORY_ONLY", trace.outcome().classification());
            assertEquals("NOT_RUN", trace.outcome().verificationStatus());
            assertEquals(9, trace.events().stream().filter(event -> "TOOL_EXECUTED".equals(event.type())).count());
            assertTrue(trace.warnings().stream().anyMatch(warning ->
                    "READ_ONLY_ANSWER_INCOMPLETE".equals(warning.code())
                            && TerminalReadOnlyStopAnswer.RETENTION_LIMIT_REASON.equals(warning.message())),
                    trace.warnings().toString());

            TurnResult second = processor.process(session, NEXT_REQUEST, context);
            assertEquals(NEXT_ANSWER, assertInstanceOf(Result.Ok.class, second.result()).text);
            assertEquals(2, recorded.requests().size(), "The second response belongs only to the new turn");
            var afterSecondRestart = new JsonSessionStore(storeRoot);
            List<TurnRecord> rows = afterSecondRestart.loadTurns(sessionId);
            assertEquals(2, rows.size());
            TurnRecord clean = rows.get(1);
            assertEquals("ok", clean.status());
            assertEquals(NEXT_REQUEST, clean.userInput());
            assertEquals(NEXT_ANSWER, clean.assistantText());
            assertTrue(clean.toolCalls().isEmpty());
            assertNotEquals(row.traceId(), clean.traceId());
            LocalTurnTrace cleanTrace = afterSecondRestart.loadTrace(sessionId, clean.traceId()).orElseThrow();
            assertNotEquals("ADVISORY_ONLY", cleanTrace.outcome().classification());
            assertTrue(cleanTrace.warnings().stream().noneMatch(warning ->
                    TerminalReadOnlyStopAnswer.RETENTION_LIMIT_REASON.equals(warning.code())
                            || "READ_ONLY_ANSWER_INCOMPLETE".equals(warning.code())), cleanTrace.warnings().toString());
            assertTrue(cleanTrace.events().stream().noneMatch(event -> "TOOL_EXECUTED".equals(event.type())));
            for (var entry : bodies.entrySet()) assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey())));
            try (var paths = Files.list(workspace)) {
                assertEquals(bodies.keySet(), paths.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
            }
        }
    }
}
