package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.repl.slash.ExplainLastTurnCommand;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.EngineException;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnProcessorFailedTurnPersistenceTest {

    @AfterEach
    void clearTurnState() {
        TurnTraceCapture.consume();
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        TurnSourceEvidenceCapture.clear();
        LocalTurnTraceCapture.clear();
        PromptDebugCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    private static TurnTraceEvent singleGenerationFailure(LocalTurnTrace trace) {
        List<TurnTraceEvent> failures = trace.events().stream()
                .filter(event -> "GENERATION_FAILED".equals(event.type()))
                .toList();
        assertEquals(1, failures.size(), failures.toString());
        return failures.getFirst();
    }

    private static void assertHarnessGenerationFailureIsRedacted(
            TurnTraceEvent failure,
            String generationId
    ) {
        assertEquals(Map.of(
                "providerLedgerVersion", 2,
                "ledgerSequence", 2,
                "generationId", generationId,
                "source", "SCRIPTED_HARNESS",
                "purpose", "HARNESS_FIXTURE",
                "toolAuthorityMode", "NONE",
                "failure", "HARNESS_EXCEPTION"),
                failure.data());
    }

    @Test
    void routeFailurePersistsOneBoundedFailedTurnAndTerminalTrace(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        TurnRouter router = (raw, workspace, ctx) -> {
            throw new IllegalStateException("T1190_EXCEPTION_PROSE_MUST_NOT_PERSIST");
        };
        IllegalStateException failure;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            failure = assertThrows(
                    IllegalStateException.class,
                    () -> processor.process(session(dir), "inspect the workspace", context()));
        }

        assertEquals("T1190_EXCEPTION_PROSE_MUST_NOT_PERSIST", failure.getMessage());
        List<TurnRecord> turns = store.loadTurns(sessionId);
        assertEquals(1, turns.size());
        TurnRecord turn = turns.getFirst();
        assertEquals("failed", turn.status());
        assertEquals("", turn.assistantText());
        assertFalse(turn.traceId().isBlank());

        LocalTurnTrace trace = store.loadTrace(sessionId, turn.traceId()).orElseThrow();
        assertEquals(turn.traceId(), trace.traceId());
        assertEquals("ERROR", trace.outcome().status());
        assertTrue(trace.events().stream().noneMatch(event ->
                event.type().startsWith("GENERATION_")));
        var terminal = trace.events().stream()
                .filter(event -> "TURN_SETTLED".equals(event.type()))
                .toList();
        assertEquals(1, terminal.size());
        assertEquals("FAILED", terminal.getFirst().data().get("settlementKind"));
        assertEquals("MODE_ROUTING", terminal.getFirst().data().get("stage"));
        assertEquals("java.lang.IllegalStateException",
                terminal.getFirst().data().get("exceptionClass"));
        assertEquals("UNKNOWN", terminal.getFirst().data().get("causalOwner"));
        assertFalse(trace.events().toString().contains("T1190_EXCEPTION_PROSE_MUST_NOT_PERSIST"));
        assertEquals("UNAVAILABLE", projection(trace).data().get("retrieval"));
        assertEquals("NOT_OBSERVED", projection(trace).data().get("providerLifecycle"));
        assertEquals("TRACE_COMPLETED", trace.events().getLast().type());
        assertEquals("TURN_SETTLED", trace.events().get(trace.events().size() - 2).type());
    }

    @Test
    void generatedFailureSurvivesStoreRestartAndEveryLastView(@TempDir Path dir) {
        Path workspace = dir.resolve("workspace");
        Path storeRoot = dir.resolve("store");
        String storageSessionId = storageSessionId(workspace);
        String promptCanary = "T1190_RESTART_PROMPT_MUST_NOT_RENDER";
        String failureCanary = "T1190_RESTART_FAILURE_MUST_NOT_RENDER";
        JsonSessionStore writer = new JsonSessionStore(storeRoot);
        try (var lease = writer.acquireSessionWriter(storageSessionId)) {
            TurnProcessor processor = processor((raw, ignoredWorkspace, ignoredContext) -> {
                throw new IllegalStateException(failureCanary);
            }, lease, storageSessionId);

            assertThrows(IllegalStateException.class, () ->
                    processor.process(session(workspace), promptCanary, context()));
        }

        JsonSessionStore reader = new JsonSessionStore(storeRoot);
        TurnRecord row = reader.loadTurns(storageSessionId).getFirst();
        String traceId = row.traceId();
        for (String view : List.of("", "tools", "sources", "trace")) {
            ExplainLastTurnCommand command = new ExplainLastTurnCommand(
                    workspace, reader, null, storageSessionId);
            Result.TrustedInfo rendered = (Result.TrustedInfo) command.execute(
                    view, Context.builder(new Config()).build());
            assertTrue(rendered.text.contains("Trace pair: VALID"), rendered.text);
            assertTrue(rendered.text.contains(
                    "Settlement: FAILED at MODE_ROUTING"), rendered.text);
            assertTrue(rendered.text.contains(
                    "class=java.lang.IllegalStateException"), rendered.text);
            assertFalse(rendered.text.contains(promptCanary), rendered.text);
            assertFalse(rendered.text.contains(failureCanary), rendered.text);
        }
        assertEquals(traceId, reader.loadTrace(storageSessionId, traceId)
                .orElseThrow().traceId());
    }

    @Test
    void providerAbortSettlesOnceWithoutPersistingPartialAssistantBytes(
            @TempDir Path dir
    ) throws IOException {
        String partialCanary = "T1190_PARTIAL_PROVIDER_BYTES_MUST_NOT_PERSIST";
        Path workspace = dir.resolve("workspace");
        String sessionId = storageSessionId(workspace);
        JsonSessionStore store = new JsonSessionStore(dir.resolve("store"));
        LlmClient llm = ScriptedNativeLlmClient.transientAfterPartialOutput(partialCanary);
        Context routeContext = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .build();
        TurnRouter router = (raw, ignoredWorkspace, ignored) -> {
            AssistantTurnExecutor.execute(
                    new java.util.ArrayList<>(List.of(
                            ChatMessage.system("system"), ChatMessage.user(raw))),
                    workspace,
                    routeContext,
                    new AssistantTurnExecutor.Options());
            return Optional.of(new Result.Ok("unreachable"));
        };
        AtomicInteger legacyCompletionCalls = new AtomicInteger();
        RuntimeException failure;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            processor.addListener(new SessionListener() {
                @Override public void onTurnComplete(TurnResult result, String userInput) {
                    legacyCompletionCalls.incrementAndGet();
                }
            });

            failure = assertThrows(RuntimeException.class, () ->
                    processor.process(
                            session(workspace),
                            "explain briefly",
                            routeContext));
        }

        assertEquals("turn generation ended without a completed response", failure.getMessage());
        assertEquals(0, legacyCompletionCalls.get());
        List<TurnRecord> turns = store.loadTurns(sessionId);
        assertEquals(1, turns.size());
        TurnRecord row = turns.getFirst();
        assertEquals("failed", row.status());
        assertEquals("", row.userInput());
        assertEquals("", row.assistantText());
        LocalTurnTrace trace = store.loadTrace(
                sessionId, row.traceId()).orElseThrow();
        assertEquals("FAILED", terminal(trace).data().get("settlementKind"));
        assertEquals("GENERATION_COORDINATION", terminal(trace).data().get("stage"));
        assertEquals("UNKNOWN", terminal(trace).data().get("causalOwner"));
        assertEquals("PRESERVED_TYPED", projection(trace).data().get("providerLifecycle"));
        assertFalse(persistedText(dir.resolve("store")).contains(partialCanary));
    }

    @Test
    void contextBudgetFailureHasTypedNonConversationalSettlement(@TempDir Path dir) {
        Path workspace = dir.resolve("workspace");
        String sessionId = storageSessionId(workspace);
        JsonSessionStore store = new JsonSessionStore(dir.resolve("store"));
        EngineException.ContextBudgetExceeded contextFailure =
                new EngineException.ContextBudgetExceeded(9_001, 8_000, 8_192, 4);
        Context routeContext = Context.builder(new Config())
                .llm(LlmClient.scriptedFailure(contextFailure))
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .build();
        TurnRouter router = (raw, ignoredWorkspace, ignored) -> {
            AssistantTurnExecutor.execute(
                    new java.util.ArrayList<>(List.of(
                            ChatMessage.system("system"), ChatMessage.user(raw))),
                    workspace,
                    routeContext,
                    new AssistantTurnExecutor.Options());
            return Optional.of(new Result.Ok("unreachable"));
        };
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);

            assertSame(contextFailure, assertThrows(
                    EngineException.ContextBudgetExceeded.class,
                    () -> processor.process(
                            session(workspace),
                            "inspect",
                            routeContext)));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertEquals("failed", row.status());
        assertEquals("", row.userInput());
        assertEquals("", row.assistantText());
        LocalTurnTrace trace = store.loadTrace(
                sessionId, row.traceId()).orElseThrow();
        assertEquals("GENERATION_COORDINATION", terminal(trace).data().get("stage"));
        assertEquals("ENGINE_CONTEXT_BUDGET", terminal(trace).data().get("errorKind"));
        assertEquals(400, terminal(trace).data().get("errorCode"));
        assertEquals("UNKNOWN", terminal(trace).data().get("causalOwner"));
        assertTrue(trace.events().stream().noneMatch(event ->
                "GENERATION_REQUEST_DISPATCHED".equals(event.type())));
        assertHarnessGenerationFailureIsRedacted(
                singleGenerationFailure(trace),
                trace.traceId() + "-generation-1");
    }

    @Test
    void contextBudgetRedactionOracleIgnoresUnrelatedEventTimestamp() {
        TurnTraceEvent failure = TurnTraceEvent.simple(
                "GENERATION_FAILED",
                "2026-07-28T09:00:01.000009001Z",
                Map.of(
                        "providerLedgerVersion", 2,
                        "ledgerSequence", 2,
                        "generationId", "context-9001-budget-generation-1",
                        "source", "SCRIPTED_HARNESS",
                        "purpose", "HARNESS_FIXTURE",
                        "toolAuthorityMode", "NONE",
                        "failure", "HARNESS_EXCEPTION"));

        assertTrue(failure.timestamp().contains("9001"), failure.timestamp());
        assertHarnessGenerationFailureIsRedacted(
                failure,
                "context-9001-budget-generation-1");
    }

    @Test
    void mandatoryPostToolProviderFailureCannotBecomeAssistantAnswer(@TempDir Path dir)
            throws Exception {
        Path workspace = dir.resolve("workspace");
        String sessionId = storageSessionId(workspace);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("README.md"), "trusted workspace evidence\n");
        String failureCanary = "T1190_POST_TOOL_PROVIDER_SECRET_PATH_C:\\private\\model.gguf";
        EngineException.ConnectionFailed providerFailure =
                new EngineException.ConnectionFailed(failureCanary, null);
        CountingSuccessfulTool readTool = new CountingSuccessfulTool(new ReadFileTool());
        ToolRegistry registry = new ToolRegistry();
        registry.register(readTool);
        ToolSpec readSpec = new ToolSpec(
                readTool.descriptor().name(),
                readTool.descriptor().description(),
                readTool.descriptor().parametersSchema());
        LlmClient llm = ScriptedNativeLlmClient.ofThenFailure(
                List.of(new LlmClient.StreamResult("", List.of(
                        new ChatMessage.NativeToolCall(
                                "call-read",
                                "talos.read_file",
                                java.util.Map.of("path", "README.md"))))),
                providerFailure);
        llm.setToolSpecs(List.of(readSpec));
        TurnProcessor toolProcessor = new TurnProcessor(
                null, new NoOpApprovalGate(), registry);
        Context routeContext = Context.builder(new Config())
                .llm(llm)
                .sandbox(new Sandbox(workspace, java.util.Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(toolProcessor, 3))
                .nativeToolSpecs(List.of(readSpec))
                .build();
        TurnRouter router = (raw, ignoredWorkspace, ignoredContext) -> {
            AssistantTurnExecutor.execute(
                    new java.util.ArrayList<>(List.of(
                            ChatMessage.system("system"), ChatMessage.user(raw))),
                    workspace,
                    routeContext,
                    new AssistantTurnExecutor.Options());
            return Optional.of(new Result.Ok("unreachable"));
        };
        JsonSessionStore store = new JsonSessionStore(dir.resolve("store"));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);

            assertSame(providerFailure, assertThrows(
                    EngineException.ConnectionFailed.class,
                    () -> processor.process(
                            session(workspace),
                            "Read README.md and summarize it.",
                            routeContext)));
        }
        assertEquals(1, readTool.successfulExecutions());
        assertTrue(readTool.lastSuccessfulOutput().contains("trusted workspace evidence"));

        List<TurnRecord> rows = store.loadTurns(sessionId);
        assertEquals(1, rows.size());
        TurnRecord row = rows.getFirst();
        assertEquals("failed", row.status());
        assertEquals("", row.userInput());
        assertEquals("", row.assistantText());
        assertTrue(row.toolCalls().isEmpty());
        LocalTurnTrace trace = store.loadTrace(
                sessionId, row.traceId()).orElseThrow();
        assertEquals("GENERATION_COORDINATION", terminal(trace).data().get("stage"));
        assertEquals("ENGINE_CONNECTION_FAILED", terminal(trace).data().get("errorKind"));
        assertEquals("UNKNOWN", terminal(trace).data().get("causalOwner"));
        assertEquals("PRESERVED_TYPED", projection(trace).data().get("providerLifecycle"));
        assertTrue(trace.events().stream().anyMatch(event ->
                "GENERATION_RESPONSE_CAPTURED".equals(event.type())));
        assertTrue(trace.events().stream().anyMatch(event ->
                "GENERATION_ATTEMPT_FAILED".equals(event.type())));
        assertFalse(persistedText(dir.resolve("store")).contains(failureCanary));
    }

    @Test
    void evidenceOnlyRetrievalIsCountedWithoutLeakingDetailsOrTheNextTurn(
            @TempDir Path dir)
            throws Exception {
        Path storeRoot = dir.resolve("store");
        JsonSessionStore store = new JsonSessionStore(storeRoot);
        String sessionId = storageSessionId(dir);
        String privatePath = "C:/private/T1190_RETRIEVAL_PATH_CANARY.txt";
        String privateLabel = "T1190_RETRIEVAL_LABEL_CANARY";
        String privateNote = "T1190_RETRIEVAL_NOTE_CANARY";
        int[] calls = {0};
        TurnRouter router = (raw, workspace, ctx) -> {
            calls[0]++;
            if (calls[0] == 1) {
                RetrievalTrace retrieval = new RetrievalTrace();
                retrieval.recordEvidence(
                        "FILE", privatePath, privateLabel, 73, privateNote);
                TurnTraceCapture.capture(retrieval);
                throw new IllegalStateException("retrieval failure");
            }
            return Optional.of(new Result.Ok("second turn"));
        };
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            Session session = session(dir);

            assertThrows(IllegalStateException.class,
                    () -> processor.process(session, "first", context()));
            JsonSessionStore restarted = new JsonSessionStore(storeRoot);
            List<TurnRecord> afterRestart = restarted.loadTurns(sessionId);
            assertEquals(1, afterRestart.size());
            TurnRecord failedRow = afterRestart.getFirst();
            assertEquals("failed", failedRow.status());
            assertEquals("0 stages, evidence=1", failedRow.retrievalTraceSummary());
            assertTrue(failedRow.retrievalTraceSummary().length() <= 32);
            assertFalse(failedRow.traceId().isBlank());
            LocalTurnTrace failedTrace = restarted.loadTrace(
                    sessionId, failedRow.traceId()).orElseThrow();
            assertEquals(failedRow.traceId(), failedTrace.traceId());
            assertEquals("CAPTURE_OBSERVED_UNBOUND",
                    projection(failedTrace).data().get("retrieval"));
            String persisted = persistedText(storeRoot);
            assertFalse(persisted.contains(privatePath));
            assertFalse(persisted.contains(privateLabel));
            assertFalse(persisted.contains(privateNote));

            TurnResult second = processor.process(session, "second", context());

            assertNotNull(second);
            assertNull(second.trace(), "the successful turn must not consume failed-turn retrieval");
            List<TurnRecord> turns = restarted.loadTurns(sessionId);
            assertEquals(2, turns.size());
            assertEquals("ok", turns.get(1).status());
            assertEquals("", turns.get(1).retrievalTraceSummary());
            LocalTurnTrace secondTrace = restarted.loadTrace(
                    sessionId, turns.get(1).traceId()).orElseThrow();
            assertTrue(secondTrace.events().stream().noneMatch(event ->
                    TurnEvidenceProjectionTraceEvent.TYPE.equals(event.type())));
            assertFalse(persistedText(storeRoot).contains(privatePath));
            assertFalse(persistedText(storeRoot).contains(privateLabel));
            assertFalse(persistedText(storeRoot).contains(privateNote));
        }
    }

    @Test
    void preDispatchProviderFailureLedgerSurvivesRouteException(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        TurnRouter router = (raw, workspace, ctx) -> {
            ProviderResponseCapture.Generation generation =
                    ProviderResponseCapture.openGeneration(
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Purpose.TURN_RESPONSE);
            ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                    ProviderResponseCapture.AttemptKind.PRIMARY,
                    ProviderResponseCapture.RetryReason.NONE);
            attempt.fail(ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH);
            generation.fail(ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH);
            throw new IllegalStateException("after typed provider failure");
        };
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);

            assertThrows(IllegalStateException.class,
                    () -> processor.process(session(dir), "run", context()));
        }

        TurnRecord turn = store.loadTurns(sessionId).getFirst();
        LocalTurnTrace trace = store.loadTrace(sessionId, turn.traceId()).orElseThrow();
        var started = trace.events().stream()
                .filter(event -> "GENERATION_ATTEMPT_STARTED".equals(event.type()))
                .findFirst().orElseThrow();
        String generationId = String.valueOf(started.data().get("generationId"));
        String attemptId = String.valueOf(started.data().get("attemptId"));
        assertFalse(generationId.isBlank());
        assertFalse(attemptId.isBlank());
        assertEquals(1, trace.events().stream().filter(event ->
                "GENERATION_ATTEMPT_FAILED".equals(event.type())
                        && generationId.equals(event.data().get("generationId"))
                        && attemptId.equals(event.data().get("attemptId"))
                        && "RUNTIME_PRE_DISPATCH".equals(event.data().get("failure"))).count());
        assertEquals(1, trace.events().stream().filter(event ->
                "GENERATION_FAILED".equals(event.type())
                        && generationId.equals(event.data().get("generationId"))
                        && "RUNTIME_PRE_DISPATCH".equals(event.data().get("failure"))).count());
        assertTrue(trace.events().stream().noneMatch(event ->
                "GENERATION_REQUEST_DISPATCHED".equals(event.type())));
    }

    @Test
    void noResultSurvivesEarlierAssertionErrorAndSkipsLegacyListeners(@TempDir Path dir)
            throws Exception {
        String privatePath = "T1196_NO_RESULT_PRIVATE_PATH/secret.css";
        String privateClause = "update " + privatePath + " too.";
        String privateRequest =
                "Edit index.html so it works, and " + privateClause;
        String sessionId = storageSessionId(dir);
        JsonSessionStore store = new JsonSessionStore(dir);
        TurnProcessor processor = new TurnProcessor((raw, workspace, ctx) -> {
            var resolution = TaskContractResolver.resolveFromUserRequest(raw);
            var exactTarget = resolution.intent().targets()
                    .find(privatePath)
                    .orElseThrow();
            assertEquals(TargetRole.MUST_MUTATE, exactTarget.role());
            assertEquals(TargetSource.USER_REQUEST, exactTarget.derivation().source());
            assertTrue(exactTarget.derivation().startOffset() >= 0);
            assertEquals(
                    raw.indexOf(privatePath) + privatePath.length(),
                    exactTarget.derivation().authorityEndOffset());
            assertTrue(exactTarget.derivation().authorityEndOffset()
                    < exactTarget.derivation().endOffset());
            assertTrue(exactTarget.derivation().endOffset()
                    > exactTarget.derivation().startOffset());
            assertEquals(
                    exactTarget.derivation().sourceText(),
                    raw.substring(
                            exactTarget.derivation().startOffset(),
                            exactTarget.derivation().endOffset()));
            assertEquals(privateClause, exactTarget.derivation().sourceText());
            TurnTaskContractCapture.set(resolution);
            TurnAuditCapture.recordPolicyTrace(TurnPolicyTrace.from(
                    resolution.contract(),
                    resolution.intent(),
                    "PLAN",
                    List.of("talos.write_file"),
                    List.of()));
            LocalTurnTraceCapture.warning(
                    "T1196_NO_RESULT_PRIVATE_WARNING", privateClause);
            return Optional.empty();
        });
        AtomicInteger legacyCalls = new AtomicInteger();
        processor.addListener(new SessionListener() {
            @Override
            public TurnPublicationReceipt onTurnSettled(
                    TurnSettlement settlement,
                    String userInput
            ) {
                throw new AssertionError("T1190_LISTENER_ASSERTION_CANARY");
            }
        });
        processor.addListener(new SessionListener() {
            @Override public void onTurnComplete(TurnResult result, String userInput) {
                legacyCalls.incrementAndGet();
            }
        });
        TurnResult returned;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            returned = processor.process(
                    session(dir), privateRequest, context());
        }

        assertNull(returned);
        assertEquals(0, legacyCalls.get());
        List<TurnRecord> rows = store.loadTurns(sessionId);
        assertEquals(1, rows.size());
        TurnRecord row = rows.getFirst();
        assertEquals("no_result", row.status());
        assertEquals("", row.userInput());
        assertEquals("", row.assistantText());
        assertEquals(List.of(), row.toolCalls());
        assertEquals(TurnPolicyTrace.empty(), row.policyTrace());
        LocalTurnTrace trace = store.loadTrace(sessionId, row.traceId()).orElseThrow();
        assertEquals("NO_RESULT", terminal(trace).data().get("settlementKind"));
        assertEquals(List.of(), trace.taskContract().expectedTargets());
        assertEquals(List.of(), trace.taskContract().rolefulTargets());
        assertEquals(List.of(), trace.warnings());
        assertEquals(1, trace.events().stream()
                .filter(event -> "TURN_SETTLED".equals(event.type()))
                .count());
        TurnPublicationHealth health = processor.consumePublicationHealth();
        assertNotNull(health);
        assertEquals(1, health.completedListeners());
        assertEquals(1, health.skippedListeners());
        assertEquals(1, health.degradedListeners());
        assertEquals(List.of(TurnPublicationReceipt.Reason.LISTENER_EXCEPTION),
                health.reasons());
        assertEquals(List.of(AssertionError.class.getName()), health.exceptionClasses());
        String persisted = persistedText(dir);
        assertFalse(persisted.contains("T1190_LISTENER_ASSERTION_CANARY"));
        assertFalse(persisted.contains(privatePath));
        assertFalse(persisted.contains(privateClause));
        assertFalse(persisted.contains(privateRequest));
        assertFalse(persisted.contains("T1196_NO_RESULT_PRIVATE_WARNING"));
    }

    @Test
    void noResultPartialPersistenceRetainsUnambiguousTerminalRow(@TempDir Path dir)
            throws Exception {
        Path storeRoot = dir.resolve("store");
        Path workspace = dir.resolve("workspace");
        String sessionId = storageSessionId(workspace);
        JsonSessionStore store = new JsonSessionStore(storeRoot);
        Files.createDirectories(storeRoot.resolve("traces"));
        Files.writeString(storeRoot.resolve("traces").resolve(sessionId), "blocked");
        TurnProcessor processor = new TurnProcessor(
                (raw, ignoredWorkspace, ctx) -> Optional.empty());
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            assertNull(processor.process(
                    session(workspace), "unhandled", context()));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertEquals("no_result", row.status());
        assertEquals("", row.traceId());
        assertEquals("", row.userInput());
        assertEquals("", row.assistantText());
        TurnPublicationHealth health = processor.consumePublicationHealth();
        assertNotNull(health);
        assertTrue(health.reasons().contains(
                TurnPublicationReceipt.Reason.PERSISTENCE_PARTIAL));
    }

    @Test
    void listenerFailureCannotReplacePrimaryFailureOrBlockLaterPersistence(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        IllegalStateException primary = new IllegalStateException("primary route failure");
        TurnProcessor processor = new TurnProcessor((raw, workspace, ctx) -> {
            throw primary;
        });
        processor.addListener(new SessionListener() {
            @Override
            public TurnPublicationReceipt onTurnSettled(
                    TurnSettlement settlement,
                    String userInput
            ) {
                throw new AssertionError("listener failure");
            }
        });
        IllegalStateException observed;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            observed = assertThrows(IllegalStateException.class,
                    () -> processor.process(session(dir), "inspect", context()));
        }

        assertSame(primary, observed);
        assertEquals(1, store.loadTurns(sessionId).size());
        TurnPublicationHealth health = processor.consumePublicationHealth();
        assertNotNull(health);
        assertFalse(health.healthy());
        assertEquals(1, health.degradedListeners());
        assertTrue(health.reasons().contains(TurnPublicationReceipt.Reason.LISTENER_EXCEPTION));
        assertEquals(List.of("java.lang.AssertionError"), health.exceptionClasses());
    }

    @Test
    void cancellationWinsOneBoundedTerminalSettlement(@TempDir Path dir)
            throws Exception {
        String privatePath = "T1196_CANCEL_PRIVATE_PATH/secret.js";
        String privateClause = "update " + privatePath + " too.";
        String privateRequest =
                "Edit index.html so it works, and " + privateClause;
        String sessionId = storageSessionId(dir);
        JsonSessionStore store = new JsonSessionStore(dir);
        TurnRouter router = (raw, workspace, ctx) -> {
            var resolution = TaskContractResolver.resolveFromUserRequest(raw);
            var exactTarget = resolution.intent().targets()
                    .find(privatePath)
                    .orElseThrow();
            assertEquals(TargetRole.MUST_MUTATE, exactTarget.role());
            assertEquals(TargetSource.USER_REQUEST, exactTarget.derivation().source());
            assertTrue(exactTarget.derivation().startOffset() >= 0);
            assertEquals(
                    raw.indexOf(privatePath) + privatePath.length(),
                    exactTarget.derivation().authorityEndOffset());
            assertTrue(exactTarget.derivation().authorityEndOffset()
                    < exactTarget.derivation().endOffset());
            assertTrue(exactTarget.derivation().endOffset()
                    > exactTarget.derivation().startOffset());
            assertEquals(
                    exactTarget.derivation().sourceText(),
                    raw.substring(
                            exactTarget.derivation().startOffset(),
                            exactTarget.derivation().endOffset()));
            assertEquals(privateClause, exactTarget.derivation().sourceText());
            TurnTaskContractCapture.set(resolution);
            TurnAuditCapture.recordPolicyTrace(TurnPolicyTrace.from(
                    resolution.contract(),
                    resolution.intent(),
                    "PLAN",
                    List.of("talos.write_file"),
                    List.of()));
            LocalTurnTraceCapture.warning(
                    "T1196_CANCEL_PRIVATE_WARNING", privateClause);
            throw new CancellationException("operator cancelled");
        };

        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            assertThrows(CancellationException.class,
                    () -> processor.process(session(dir), privateRequest, context()));
        }

        List<TurnRecord> rows = store.loadTurns(sessionId);
        assertEquals(1, rows.size());
        TurnRecord row = rows.getFirst();
        assertEquals("cancelled", row.status());
        assertEquals("", row.userInput());
        assertEquals("", row.assistantText());
        assertEquals(List.of(), row.toolCalls());
        assertEquals(TurnPolicyTrace.empty(), row.policyTrace());
        LocalTurnTrace trace = store.loadTrace(
                sessionId, row.traceId()).orElseThrow();
        assertEquals(List.of(), trace.taskContract().expectedTargets());
        assertEquals(List.of(), trace.taskContract().rolefulTargets());
        assertEquals(List.of(), trace.warnings());
        assertEquals(1, trace.events().stream()
                .filter(event -> "TURN_SETTLED".equals(event.type())).count());
        assertEquals("CANCELLED", terminal(trace).data().get("settlementKind"));
        assertEquals("UNKNOWN", terminal(trace).data().get("causalOwner"));
        String persisted = persistedText(dir);
        assertFalse(persisted.contains(privatePath));
        assertFalse(persisted.contains(privateClause));
        assertFalse(persisted.contains(privateRequest));
        assertFalse(persisted.contains("T1196_CANCEL_PRIVATE_WARNING"));
    }

    @Test
    void handoffAdmissionSurvivesWithoutBecomingPhysicalSendProof(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        TurnRouter router = (raw, workspace, ctx) -> {
            ProviderResponseCapture.Generation generation =
                    ProviderResponseCapture.openGeneration(
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Purpose.TURN_RESPONSE);
            ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                    ProviderResponseCapture.AttemptKind.PRIMARY,
                    ProviderResponseCapture.RetryReason.NONE);
            attempt.requestDispatched();
            throw new IllegalStateException("after handoff admission");
        };

        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            assertThrows(IllegalStateException.class,
                    () -> processor.process(session(dir), "inspect", context()));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        LocalTurnTrace trace = store.loadTrace(sessionId, row.traceId()).orElseThrow();
        var dispatch = trace.events().stream()
                .filter(event -> "GENERATION_REQUEST_DISPATCHED".equals(event.type()))
                .findFirst().orElseThrow();
        assertFalse(dispatch.data().containsKey("physicalSendStarted"));
        assertTrue(trace.events().stream().anyMatch(event ->
                "GENERATION_ATTEMPT_ABANDONED".equals(event.type())));
        assertEquals("UNKNOWN", terminal(trace).data().get("causalOwner"));
    }

    @Test
    void priorTaskOutcomeCannotHideLaterFailedSettlement(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        TurnRouter router = (raw, workspace, ctx) -> {
            LocalTurnTraceCapture.recordOutcome(
                    "OK", "PASSED", "NOT_REQUIRED", "NONE", "EARLIER_TASK_OUTCOME");
            throw new IllegalStateException("late runtime failure");
        };

        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            assertThrows(IllegalStateException.class,
                    () -> processor.process(session(dir), "inspect", context()));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertEquals("failed", row.status());
        LocalTurnTrace trace = store.loadTrace(sessionId, row.traceId()).orElseThrow();
        assertEquals("ERROR", trace.outcome().status(),
                "the persisted terminal projection must not retain stale success");
        assertEquals("TURN_FAILED", trace.outcome().classification());
        assertEquals("FAILED", terminal(trace).data().get("settlementKind"));
        assertEquals("TURN_SETTLED", trace.events().get(trace.events().size() - 2).type());
    }

    @Test
    void abnormalEnvelopeAndFilesOmitOrdinaryPolicyToolAndRetrievalProse(@TempDir Path dir)
            throws Exception {
        String privatePath = "T1190_PRIVATE_PATH/secret.css";
        String privateClause = "update " + privatePath + " too.";
        String privateRequest =
                "Edit index.html so it works, and " + privateClause;
        String privateReason = "T1190_TOOL_REASON_CANARY";
        String sessionId = storageSessionId(dir);
        JsonSessionStore store = new JsonSessionStore(dir);
        TurnProcessor processor = new TurnProcessor((raw, workspace, ctx) -> {
            var resolution =
                    TaskContractResolver.resolveFromUserRequest(raw);
            var exactTarget = resolution.intent().targets()
                    .find(privatePath)
                    .orElseThrow();
            assertTrue(exactTarget.derivation().startOffset() >= 0);
            assertEquals(
                    raw.indexOf(privatePath) + privatePath.length(),
                    exactTarget.derivation().authorityEndOffset());
            assertTrue(exactTarget.derivation().authorityEndOffset()
                    < exactTarget.derivation().endOffset());
            assertEquals(
                    exactTarget.derivation().sourceText(),
                    raw.substring(
                            exactTarget.derivation().startOffset(),
                            exactTarget.derivation().endOffset()));
            TurnTaskContractCapture.set(resolution);
            TurnAuditCapture.recordPolicyTrace(TurnPolicyTrace.from(
                    resolution.contract(),
                    resolution.intent(),
                    "PLAN",
                    List.of("talos.read_file"),
                    List.of()));
            TurnAuditCapture.recordToolCall(
                    "talos.read_file", privatePath, false, privateReason);
            LocalTurnTraceCapture.warning("T1190_WARNING", privateClause);
            RetrievalTrace retrieval = new RetrievalTrace();
            retrieval.record("source-selection", 1_000, 1, 0, privateReason);
            retrieval.recordEvidence("FILE", privatePath, privateClause, 1, privateReason);
            TurnTraceCapture.capture(retrieval);
            throw new IllegalStateException("T1190_EXCEPTION_CANARY");
        });
        AtomicReference<String> listenerInput = new AtomicReference<>();
        processor.addListener(new SessionListener() {
            @Override
            public TurnPublicationReceipt onTurnSettled(
                    TurnSettlement settlement,
                    String userInput
            ) {
                listenerInput.set(userInput);
                return TurnPublicationReceipt.complete();
            }
        });
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            assertThrows(IllegalStateException.class,
                    () -> processor.process(session(dir), privateRequest, context()));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertEquals("", listenerInput.get());
        assertEquals(List.of(), row.toolCalls());
        assertEquals(TurnPolicyTrace.empty(), row.policyTrace());
        LocalTurnTrace trace = store.loadTrace(
                sessionId, row.traceId()).orElseThrow();
        assertEquals(List.of(), trace.taskContract().expectedTargets());
        assertEquals(List.of(), trace.taskContract().rolefulTargets());
        assertEquals(List.of(), trace.warnings());
        assertEquals("ERROR", trace.outcome().status());

        String persisted = persistedText(dir);
        assertFalse(persisted.contains(privatePath));
        assertFalse(persisted.contains(privateClause));
        assertFalse(persisted.contains(privateRequest));
        assertFalse(persisted.contains(privateReason));
        assertFalse(persisted.contains("T1190_EXCEPTION_CANARY"));
    }

    @Test
    void listenerCannotMutateSettledRetrievalSnapshotBeforePersistence(@TempDir Path dir)
            throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        TurnProcessor processor = new TurnProcessor((raw, workspace, ctx) -> {
            RetrievalTrace retrieval = new RetrievalTrace();
            retrieval.record("owned-stage", 1_000, 2, 1);
            TurnTraceCapture.capture(retrieval);
            return Optional.of(new Result.Ok("done"));
        });
        processor.addListener(new SessionListener() {
            @Override
            public TurnPublicationReceipt onTurnSettled(
                    TurnSettlement settlement,
                    String userInput
            ) {
                assertThrows(UnsupportedOperationException.class, () ->
                        settlement.result().trace().record("forged-stage", 1, 1, 0));
                return TurnPublicationReceipt.complete();
            }
        });
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            processor.process(session(dir), "inspect", context());
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertTrue(row.retrievalTraceSummary().startsWith("1 stages,"));
        assertFalse(row.retrievalTraceSummary().contains("forged-stage"));
    }

    @Test
    void terminalizerFactoryThrowPersistsInitializationFailureAndNextTurnIsClean(
            @TempDir Path dir
    ) throws Exception {
        Path storeRoot = dir.resolve("store");
        Path workspace = dir.resolve("workspace");
        String sessionId = storageSessionId(workspace);
        String failureCanary = "T1190_TERMINALIZER_FACTORY_THROW_CANARY";
        IllegalStateException factoryFailure = new IllegalStateException(failureCanary);
        AtomicInteger factoryCalls = new AtomicInteger();
        JsonSessionStore store = new JsonSessionStore(storeRoot);
        TurnProcessor processor = processorWithTerminalizerFactory(
                (raw, ignoredWorkspace, ctx) -> Optional.of(new Result.Ok("clean answer")),
                (turn, startNanos) -> {
                    if (factoryCalls.getAndIncrement() == 0) throw factoryFailure;
                    return new TurnTerminalizer(turn, startNanos);
                });
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            Session session = session(workspace);

            IllegalStateException observed = assertThrows(
                    IllegalStateException.class,
                    () -> processor.process(session, "first", context()));

            assertSame(factoryFailure, observed);
            assertOneInitializationFailure(store, sessionId, IllegalStateException.class);
            assertFalse(persistedText(storeRoot).contains(failureCanary));

            TurnResult clean = processor.process(session, "second", context());

            assertTrue(clean.result() instanceof Result.Ok ok && "clean answer".equals(ok.text));
            assertCleanSecondTurn(store, sessionId);
            assertFalse(persistedText(storeRoot).contains(failureCanary));
        }
    }

    @Test
    void nullTerminalizerFactoryResultPersistsInitializationFailureAndNextTurnIsClean(
            @TempDir Path dir
    ) throws Exception {
        Path storeRoot = dir.resolve("store");
        Path workspace = dir.resolve("workspace");
        String sessionId = storageSessionId(workspace);
        String nullFailureMessage = "turn terminalizer factory returned null";
        AtomicInteger factoryCalls = new AtomicInteger();
        JsonSessionStore store = new JsonSessionStore(storeRoot);
        TurnProcessor processor = processorWithTerminalizerFactory(
                (raw, ignoredWorkspace, ctx) -> Optional.of(new Result.Ok("clean answer")),
                (turn, startNanos) -> factoryCalls.getAndIncrement() == 0
                        ? null
                        : new TurnTerminalizer(turn, startNanos));
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            Session session = session(workspace);

            NullPointerException observed = assertThrows(
                    NullPointerException.class,
                    () -> processor.process(session, "first", context()));

            assertEquals(nullFailureMessage, observed.getMessage());
            assertOneInitializationFailure(store, sessionId, NullPointerException.class);
            assertFalse(persistedText(storeRoot).contains(nullFailureMessage));

            TurnResult clean = processor.process(session, "second", context());

            assertTrue(clean.result() instanceof Result.Ok ok && "clean answer".equals(ok.text));
            assertCleanSecondTurn(store, sessionId);
            assertFalse(persistedText(storeRoot).contains(nullFailureMessage));
        }
    }

    @Test
    void terminalizationFailureFallsBackToOneDurableDegradedSettlement(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        IllegalStateException terminalFailure =
                new IllegalStateException("T1190_TERMINALIZATION_FAILURE");
        TurnProcessor processor = processorWithTerminalFreezeFailure(
                (raw, workspace, ctx) -> Optional.of(new Result.Ok("must not be committed")),
                terminalFailure);
        IllegalStateException observed;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            observed = assertThrows(
                    IllegalStateException.class,
                    () -> processor.process(session(dir), "inspect", context()));
        }

        assertSame(terminalFailure, observed);
        List<TurnRecord> rows = store.loadTurns(sessionId);
        assertEquals(1, rows.size());
        assertEquals("failed", rows.getFirst().status());
        assertEquals("", rows.getFirst().assistantText());
        LocalTurnTrace trace = store.loadTrace(
                sessionId, rows.getFirst().traceId()).orElseThrow();
        assertEquals(1, trace.events().stream().filter(event ->
                "TURN_TERMINALIZATION_RECOVERED".equals(event.type())).count());
        assertEquals(1, trace.events().stream().filter(event ->
                "TURN_SETTLED".equals(event.type())).count());
        assertEquals("UNKNOWN", projection(trace).data().get("providerLifecycle"));
        assertEquals("NOT_RETRIED", projection(trace).data().get("captureFinalizers"));
        assertEquals("TURN_FINALIZATION", terminal(trace).data().get("stage"));
        assertFalse(trace.toString().contains("T1190_TERMINALIZATION_FAILURE"));

        TurnPublicationHealth health = processor.consumePublicationHealth();
        assertNotNull(health);
        assertFalse(health.healthy());
        assertTrue(health.reasons().contains(
                TurnPublicationReceipt.Reason.TURN_SETTLEMENT_FAILED));
        assertTrue(health.exceptionClasses().contains(
                IllegalStateException.class.getName()));
        assertEquals(1, health.completedListeners());
    }

    @Test
    void terminalizationRecoveryPreservesOriginalRouteFailure(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        IllegalArgumentException primary = new IllegalArgumentException("primary route failure");
        TurnProcessor processor = processorWithTerminalFreezeFailure(
                (raw, workspace, ctx) -> {
                    throw primary;
                },
                new IllegalStateException("secondary terminalization failure"));
        IllegalArgumentException observed;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            observed = assertThrows(
                    IllegalArgumentException.class,
                    () -> processor.process(session(dir), "inspect", context()));
        }

        assertSame(primary, observed);
        TurnRecord row = store.loadTurns(sessionId).getFirst();
        LocalTurnTrace trace = store.loadTrace(
                sessionId, row.traceId()).orElseThrow();
        assertEquals("MODE_ROUTING", terminal(trace).data().get("stage"));
        assertEquals("VALIDATION", terminal(trace).data().get("errorKind"));
        assertEquals(400, terminal(trace).data().get("errorCode"));
    }

    @Test
    void terminalizationRecoveryRetainsAlreadyConsumedRetrieval(@TempDir Path dir)
            throws Exception {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        AtomicInteger calls = new AtomicInteger();
        TurnRouter router = (raw, workspace, ctx) -> {
            if (calls.getAndIncrement() == 0) {
                RetrievalTrace retrieval = new RetrievalTrace();
                retrieval.record("owned-before-finalization", 1_000, 2, 1);
                TurnTraceCapture.capture(retrieval);
            }
            return Optional.of(new Result.Ok("answer"));
        };
        IllegalStateException phaseFailure =
                new IllegalStateException("final phase unavailable");
        TurnResult second;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);

            assertThrows(IllegalStateException.class, () -> processor.process(
                    session(dir), "first", finalPhaseFailureContext(phaseFailure)));
            second = processor.process(session(dir), "second", context());
        }

        List<TurnRecord> rows = store.loadTurns(sessionId);
        assertEquals(2, rows.size());
        assertEquals("failed", rows.get(0).status());
        assertTrue(rows.get(0).retrievalTraceSummary().startsWith("1 stages,"));
        assertEquals("ok", rows.get(1).status());
        assertEquals("", rows.get(1).retrievalTraceSummary());
        assertNull(second.trace());
        LocalTurnTrace trace = store.loadTrace(
                sessionId, rows.get(0).traceId()).orElseThrow();
        assertEquals(1, trace.events().stream().filter(event ->
                "TURN_TERMINALIZATION_RECOVERED".equals(event.type())).count());
    }

    @Test
    void failedPersistenceDoesNotStoreRawRequestOrExceptionProse(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        String secret = "api_key=T1190_SUPER_SECRET";
        TurnRouter router = (raw, workspace, ctx) -> {
            throw new IllegalStateException("T1190_PRIVATE_EXCEPTION");
        };

        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            assertThrows(IllegalStateException.class,
                    () -> processor.process(session(dir), secret, context()));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertEquals("", row.userInput());
        assertEquals("", row.assistantText());
        LocalTurnTrace trace = store.loadTrace(
                sessionId, row.traceId()).orElseThrow();
        String serialized = trace.toString();
        assertFalse(serialized.contains("T1190_SUPER_SECRET"));
        assertFalse(serialized.contains("T1190_PRIVATE_EXCEPTION"));
        assertTrue(PromptDebugCapture.lastTurnHadNoProviderRequest());
    }

    @Test
    void nullRouterResultIsAFailedTurnRatherThanNoResult(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(
                    (raw, workspace, ctx) -> null, writer, sessionId);
            assertThrows(NullPointerException.class,
                    () -> processor.process(session(dir), "inspect", context()));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        assertEquals("failed", row.status());
        LocalTurnTrace trace = store.loadTrace(sessionId, row.traceId()).orElseThrow();
        assertEquals("FAILED", terminal(trace).data().get("settlementKind"));
        assertEquals("MODE_ROUTING", terminal(trace).data().get("stage"));
    }

    @Test
    void traceMetadataFailureSettlesAtTurnInitialization(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        TurnRouter router = new TurnRouter() {
            @Override
            public Optional<Result> route(String raw, Path workspace, RuntimeTurnContext ctx) {
                return Optional.of(new Result.Ok("must not route"));
            }

            @Override
            public String traceMode(String rawLine) {
                throw new IllegalStateException("trace mode unavailable");
            }
        };
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            assertThrows(IllegalStateException.class,
                    () -> processor.process(session(dir), "inspect", context()));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        LocalTurnTrace trace = store.loadTrace(sessionId, row.traceId()).orElseThrow();
        assertEquals("TURN_INITIALIZATION", terminal(trace).data().get("stage"));
    }

    @Test
    void interruptionRemainsFailedAndCausalOwnerUnknown(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        TurnRouter router = (raw, workspace, ctx) -> {
            throw new InterruptedException("cause unproven");
        };

        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            assertThrows(InterruptedException.class,
                    () -> processor.process(session(dir), "inspect", context()));
        }

        TurnRecord row = store.loadTurns(sessionId).getFirst();
        LocalTurnTrace trace = store.loadTrace(sessionId, row.traceId()).orElseThrow();
        assertEquals("FAILED", terminal(trace).data().get("settlementKind"));
        assertEquals("INTERRUPTION", terminal(trace).data().get("errorKind"));
        assertEquals("UNKNOWN", terminal(trace).data().get("causalOwner"));
    }

    @Test
    void ownedInterruptedFlagSurvivesTerminalPublicationAndPersistence(@TempDir Path dir) {
        JsonSessionStore store = new JsonSessionStore(dir);
        String sessionId = storageSessionId(dir);
        TurnRouter router = (raw, workspace, ctx) -> {
            Thread.currentThread().interrupt();
            throw new InterruptedException("owned interrupt");
        };

        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            try {
                assertThrows(InterruptedException.class,
                        () -> processor.process(session(dir), "inspect", context()));

                assertTrue(Thread.currentThread().isInterrupted());
                TurnRecord row = store.loadTurns(sessionId).getFirst();
                LocalTurnTrace trace = store.loadTrace(
                        sessionId, row.traceId()).orElseThrow();
                assertEquals("FAILED", terminal(trace).data().get("settlementKind"));
                assertEquals("INTERRUPTION", terminal(trace).data().get("errorKind"));
                assertEquals("UNKNOWN", terminal(trace).data().get("causalOwner"));
                assertEquals(1, trace.events().stream()
                        .filter(event -> "TURN_SETTLED".equals(event.type()))
                        .count());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void persistenceFailureIsVisibleWithoutReplacingPrimaryFailure(@TempDir Path dir)
            throws Exception {
        String sessionId = storageSessionId(dir);
        Files.createDirectories(dir.resolve("traces"));
        Files.writeString(
                dir.resolve("traces").resolve(sessionId),
                "not a directory");
        Files.createDirectory(dir.resolve(sessionId + ".turns.jsonl"));
        JsonSessionStore store = new JsonSessionStore(dir);
        IllegalStateException primary = new IllegalStateException("primary");
        TurnRouter router = (raw, workspace, ctx) -> {
            throw primary;
        };

        IllegalStateException observed;
        TurnPublicationHealth health;
        try (var writer = store.acquireSessionWriter(sessionId)) {
            TurnProcessor processor = processor(router, writer, sessionId);
            observed = assertThrows(IllegalStateException.class,
                    () -> processor.process(session(dir), "inspect", context()));
            health = processor.consumePublicationHealth();
        }

        assertSame(primary, observed);
        assertNotNull(health);
        assertFalse(health.healthy());
        assertTrue(health.reasons().contains(
                TurnPublicationReceipt.Reason.PERSISTENCE_FAILED));
        assertTrue(store.loadTurns(sessionId).isEmpty());
    }

    private static dev.talos.runtime.trace.TurnTraceEvent terminal(LocalTurnTrace trace) {
        return trace.events().stream()
                .filter(event -> "TURN_SETTLED".equals(event.type()))
                .findFirst().orElseThrow();
    }

    private static dev.talos.runtime.trace.TurnTraceEvent projection(LocalTurnTrace trace) {
        return trace.events().stream()
                .filter(event -> TurnEvidenceProjectionTraceEvent.TYPE.equals(event.type()))
                .findFirst().orElseThrow();
    }

    private static TurnProcessor processor(
            TurnRouter router,
            SessionWriterLease store,
            String sessionId) {
        TurnProcessor processor = new TurnProcessor(router);
        processor.addListener(new JsonTurnLogAppender(store, sessionId));
        return processor;
    }

    private static TurnProcessor processorWithTerminalFreezeFailure(
            TurnRouter router,
            RuntimeException failure
    ) {
        return new TurnProcessor(
                router,
                new NoOpApprovalGate(),
                new dev.talos.tools.ToolRegistry(),
                ApprovalPolicy.ALWAYS_ASK,
                new dev.talos.runtime.checkpoint.CheckpointService(),
                dev.talos.runtime.command.CommandProfileRegistry.defaultRegistry(),
                (turn, startNanos) -> new TurnTerminalizer(
                        turn, startNanos, () -> { throw failure; }));
    }

    private static TurnProcessor processorWithTerminalizerFactory(
            TurnRouter router,
            TurnProcessor.TerminalizerFactory factory
    ) {
        return new TurnProcessor(
                router,
                new NoOpApprovalGate(),
                new dev.talos.tools.ToolRegistry(),
                ApprovalPolicy.ALWAYS_ASK,
                new dev.talos.runtime.checkpoint.CheckpointService(),
                dev.talos.runtime.command.CommandProfileRegistry.defaultRegistry(),
                factory);
    }

    private static void assertOneInitializationFailure(
            JsonSessionStore store,
            String sessionId,
            Class<? extends Throwable> failureType
    ) {
        List<TurnRecord> rows = store.loadTurns(sessionId);
        assertEquals(1, rows.size());
        TurnRecord row = rows.getFirst();
        assertEquals("failed", row.status());
        assertEquals("", row.userInput());
        assertEquals("", row.assistantText());
        assertFalse(row.traceId().isBlank());
        LocalTurnTrace trace = store.loadTrace(sessionId, row.traceId()).orElseThrow();
        assertEquals(row.traceId(), trace.traceId());
        assertEquals(1, trace.events().stream()
                .filter(event -> "TURN_SETTLED".equals(event.type()))
                .count());
        assertEquals("FAILED", terminal(trace).data().get("settlementKind"));
        assertEquals("TURN_INITIALIZATION", terminal(trace).data().get("stage"));
        assertEquals(failureType.getName(), terminal(trace).data().get("exceptionClass"));
    }

    private static void assertCleanSecondTurn(JsonSessionStore store, String sessionId) {
        List<TurnRecord> rows = store.loadTurns(sessionId);
        assertEquals(2, rows.size());
        TurnRecord cleanRow = rows.get(1);
        assertEquals("ok", cleanRow.status());
        assertEquals("second", cleanRow.userInput());
        assertEquals("clean answer", cleanRow.assistantText());
        assertEquals("", cleanRow.retrievalTraceSummary());
        LocalTurnTrace cleanTrace = store.loadTrace(
                sessionId, cleanRow.traceId()).orElseThrow();
        assertEquals("OK", cleanTrace.outcome().status());
        assertTrue(cleanTrace.events().stream().noneMatch(event ->
                "TURN_SETTLED".equals(event.type())));
    }

    private static Session session(Path workspace) {
        return new Session(workspace, new Config());
    }

    private static String storageSessionId(Path workspace) {
        return JsonSessionStore.newSessionInstanceId(
                workspace,
                Instant.parse("2026-07-21T00:00:00Z"));
    }

    private static Context context() {
        return Context.builder(new Config()).build();
    }

    private static RuntimeTurnContext finalPhaseFailureContext(RuntimeException failure) {
        return new RuntimeTurnContext() {
            @Override public Config cfg() { return new Config(); }
            @Override public dev.talos.core.llm.LlmClient llm() { return null; }
            @Override public dev.talos.core.security.Sandbox sandbox() { return null; }
            @Override
            public dev.talos.runtime.phase.ExecutionPhaseState executionPhaseState() {
                throw failure;
            }
            @Override
            public List<dev.talos.spi.types.ToolSpec> nativeToolSpecs() {
                return List.of();
            }
        };
    }

    private static String persistedText(Path dir) throws IOException {
        StringBuilder out = new StringBuilder();
        Path ownership = dir.resolve(".ownership");
        try (var paths = Files.walk(dir)) {
            for (Path path : paths
                    .filter(path -> !path.startsWith(ownership))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList()) {
                out.append(Files.readString(path));
            }
        }
        return out.toString();
    }

    private static final class CountingSuccessfulTool implements TalosTool {
        private final TalosTool delegate;
        private final AtomicInteger successfulExecutions = new AtomicInteger();
        private final AtomicReference<String> lastSuccessfulOutput =
                new AtomicReference<>("");

        private CountingSuccessfulTool(TalosTool delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public ToolDescriptor descriptor() {
            return delegate.descriptor();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            ToolResult result = delegate.execute(call, context);
            if (result.success()) {
                successfulExecutions.incrementAndGet();
                lastSuccessfulOutput.set(result.output());
            }
            return result;
        }

        private int successfulExecutions() {
            return successfulExecutions.get();
        }

        private String lastSuccessfulOutput() {
            return lastSuccessfulOutput.get();
        }
    }
}
