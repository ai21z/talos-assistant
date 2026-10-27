package dev.talos.runtime;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Scripted provider choices cross real file reads, executor settlement and durable trace reload. */
class ReadEvidenceDeliveryOutcomePersistenceTest {
    @TempDir Path dir;

    @AfterEach void clearCaptures() {
        TurnTraceCapture.clear();
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        TurnSourceEvidenceCapture.clear();
        LocalTurnTraceCapture.clear();
        PromptDebugCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void sourceDeliveryOverflowCannotSettleAnAnchoredWorkspaceExplanationAsComplete() throws Exception {
        String request = "What is this project?";
        var contract = TaskContractResolver.fromUserRequest(request);
        assertEquals(TaskType.WORKSPACE_EXPLAIN, contract.type());
        assertFalse(contract.mutationAllowed());
        assertTrue(contract.expectedTargets().isEmpty(), "The source-count cap must apply to unnamed sources");
        Path workspace = Files.createDirectory(dir.resolve("workspace"));
        Path storeRoot = dir.resolve("store");
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("README.md", "# Project\nThis project is a command-line utility for reviewing notes.\n");
        for (int index = 1; index <= 8; index++)
            bodies.put("support" + index + ".txt", "Distinct source observation " + index + ".\n");
        for (var entry : bodies.entrySet()) Files.writeString(workspace.resolve(entry.getKey()), entry.getValue());
        List<ChatMessage.NativeToolCall> reads = bodies.keySet().stream().map(path ->
                new ChatMessage.NativeToolCall("read-" + path, "talos.read_file",
                        Map.<String, Object>of("path", path))).toList();
        String nextRequest = "Explain recursion briefly.";
        String nextAnswer = "Recursion is a function calling itself until a stopping condition is reached.";
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult("", reads),
                new LlmClient.StreamResult(nextAnswer, List.of())), 16_384);
        String sessionId = JsonSessionStore.newSessionInstanceId(workspace, Instant.parse("2026-09-30T00:00:00Z"));
        var store = new JsonSessionStore(storeRoot);
        try (LlmClient llm = recorded.client(); var writer = store.acquireSessionWriter(sessionId)) {
            var registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            List<ToolSpec> specs = registry.descriptors().stream().map(descriptor ->
                    new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList();
            var config = new Config();
            var context = Context.builder(config).llm(llm).sandbox(new Sandbox(workspace, Map.of()))
                    .toolRegistry(registry).nativeToolSpecs(specs)
                    .toolCallLoop(new ToolCallLoop(new TurnProcessor(null, new NoOpApprovalGate(), registry), 4))
                    .build();
            TurnRouter router = (raw, routedWorkspace, ignoredContext) -> {
                var output = AssistantTurnExecutor.execute(new ArrayList<>(List.of(
                        ChatMessage.system("You are Talos."), ChatMessage.user(raw))), routedWorkspace,
                        context, new AssistantTurnExecutor.Options());
                return Optional.of(new Result.Ok(output.text()));
            };
            var processor = new TurnProcessor(router);
            processor.addListener(new JsonTurnLogAppender(writer, sessionId));
            var session = new Session(workspace, config);
            var result = processor.process(session, request, context);
            String answer = assertInstanceOf(Result.Ok.class, result.result()).text;
            assertTrue(answer.contains("Acquired read evidence could not be preserved"), answer);
            assertTrue(answer.contains("requested answer remains incomplete"), answer);
            assertEquals(1, recorded.requests().size(), "The delivery stop must precede the next provider call");
            assertEquals(9, result.audit().toolCalls().size(), "The project anchor and eight support files were read");

            var reopened = new JsonSessionStore(storeRoot);
            var row = reopened.loadTurns(sessionId).getFirst();
            assertEquals("ok", row.status());
            assertEquals(MemoryUpdateListener.assistantTextForPersistence(answer, request), row.assistantText());
            assertEquals(9, row.toolCalls().size());
            assertEquals(bodies.keySet(), row.toolCalls().stream().map(call ->
                    Path.of(call.pathHint()).getFileName().toString()).collect(Collectors.toSet()));
            assertTrue(row.toolCalls().stream().allMatch(call -> call.success()
                    && call.executionDisposition() == TurnRecord.ToolCallDisposition.EXECUTED
                    && !call.mutationEffects().hasEffects()));
            var trace = reopened.loadTrace(sessionId, row.traceId()).orElseThrow();
            assertEquals("WORKSPACE_EXPLAIN", trace.taskContract().type());
            assertEquals("ADVISORY_ONLY", trace.outcome().status());
            assertEquals("ADVISORY_ONLY", trace.outcome().classification());
            assertEquals("NOT_RUN", trace.outcome().verificationStatus());
            assertTrue(trace.warnings().stream().anyMatch(warning ->
                    "READ_ONLY_ANSWER_INCOMPLETE".equals(warning.code())
                            && "READ_EVIDENCE_DELIVERY_UNAVAILABLE".equals(warning.message())), trace.warnings().toString());
            assertTrue(trace.warnings().stream().anyMatch(warning ->
                    "READ_EVIDENCE_DELIVERY_UNAVAILABLE".equals(warning.code())), trace.warnings().toString());
            assertEquals(9, trace.events().stream().filter(event -> "TOOL_EXECUTED".equals(event.type())).count());

            var next = processor.process(session, nextRequest, context);
            assertEquals(nextAnswer, assertInstanceOf(Result.Ok.class, next.result()).text);
            assertEquals(2, recorded.requests().size());
            var reloaded = new JsonSessionStore(storeRoot);
            var nextRow = reloaded.loadTurns(sessionId).get(1);
            var nextTrace = reloaded.loadTrace(sessionId, nextRow.traceId()).orElseThrow();
            assertEquals("COMPLETE", nextTrace.outcome().status());
            assertTrue(nextTrace.warnings().stream().noneMatch(warning ->
                    "READ_EVIDENCE_DELIVERY_UNAVAILABLE".equals(warning.code())
                            || "READ_ONLY_ANSWER_INCOMPLETE".equals(warning.code())));
            for (var entry : bodies.entrySet())
                assertEquals(entry.getValue(), Files.readString(workspace.resolve(entry.getKey())));
        }
    }
}
