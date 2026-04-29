package dev.talos.harness;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.*;
import dev.talos.tools.impl.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives a {@link ScenarioDefinition} deterministically without a real LLM.
 *
 * <p>The runner:
 * <ol>
 *   <li>Creates a fresh {@link ScenarioWorkspaceFixture} populated with the scenario's initial files.</li>
 *   <li>Wires the standard tool registry (read_file, write_file, edit_file, grep, list_dir)
 *       against the fixture workspace.</li>
 *   <li>Applies the scenario's {@link ScenarioApprovalPolicy} via a deterministic approval gate.</li>
 *   <li>Injects the scenario's scripted LLM response directly into
 *       {@link ToolCallLoop#run} — no real LLM call is made.</li>
 *   <li>Returns a {@link ScenarioResult} for post-run assertions.</li>
 * </ol>
 *
 * <p>The caller is responsible for closing the workspace via
 * {@link ScenarioResult#closeWorkspace()} when assertions are done.
 *
 * <h2>Usage</h2>
 * <pre>
 *   var scenario = ScenarioDefinition.named("create file")
 *       .withScriptedResponse("""
 *           Here is the file.
 *           ```json
 *           {"name": "talos.write_file", "parameters": {"path": "out.txt", "content": "hello"}}
 *           ```
 *           """)
 *       .build();
 *
 *   try (var result = ScenarioRunner.run(scenario)) {
 *       result.assertFileExists("out.txt")
 *             .assertFileContains("out.txt", "hello")
 *             .assertToolsInvoked(1)
 *             .assertNoFailedCalls();
 *   }
 * </pre>
 */
public final class ScenarioRunner {

    private ScenarioRunner() {}

    /**
     * Run a scenario and return the result.
     *
     * <p>The returned {@link ScenarioResult} holds the workspace open.
     * Call {@link ScenarioResult#closeWorkspace()} or use try-with-resources on it.
     */
    public static ScenarioResult run(ScenarioDefinition scenario) {
        return runInternal(scenario, false);
    }

    /**
     * Run a scenario in <b>strict measurement mode</b>.
     *
     * <p>Strict mode disables harness-path <em>measurement cushions</em> so
     * scenario runs reflect more of the raw model/runtime behavior:
     * <ul>
     *   <li>{@link dev.talos.tools.ToolRegistry} fuzzy/alias/case-insensitive
     *       tool-name rescue is disabled — only exact tool names resolve.</li>
     *   <li>{@link dev.talos.runtime.ToolCallLoop} measurement cushions are
     *       disabled: redundant read-only call suppression, B3
     *       duplicate-failing-edit short-circuit, B2 read-before-write hint
     *       appended to tool results, and E1 error-message rewriting after
     *       repeated edit_file failure.</li>
     * </ul>
     *
     * <p>Strict mode does <b>not</b> disable safety-critical protections:
     * the sandbox, approval gate, iteration cap, missing-path refusal,
     * engine-exception handling, output truncation, and tool-call stripping
     * all remain active.
     *
     * <p>Default harness behavior ({@link #run}) is unchanged.
     */
    public static ScenarioResult runStrict(ScenarioDefinition scenario) {
        return runInternal(scenario, true);
    }

    /**
     * Harness-path follow-up client for tool-loop re-prompts.
     *
     * <p>{@link ToolCallLoop#run} receives the scenario's first scripted model
     * response directly as an argument, so the LLM seam is consulted only for
     * post-tool follow-ups. The default deterministic behavior is therefore a
     * single empty follow-up, which cleanly terminates the loop after the
     * scripted calls execute instead of consulting a real backend.
     */
    private static LlmClient scriptedHarnessFollowUps() {
        return LlmClient.scripted(List.of(""));
    }

    private static ScenarioResult runInternal(ScenarioDefinition scenario, boolean strict) {
        // 1. Set up workspace
        var workspace = ScenarioWorkspaceFixture.withFiles(scenario.initialFiles());
        var llm = scriptedHarnessFollowUps();

        // 2. Wire tool registry against the workspace.
        //    Strict mode disables fuzzy/alias tool-name rescue.
        var undoStack = new FileUndoStack();
        var registry  = new ToolRegistry(strict);
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        registry.register(new GrepTool());
        registry.register(new ListDirTool());
        // RetrieveTool intentionally omitted — requires full RAG stack

        // 3. Approval gate driven by policy
        GateRecorder gate = new GateRecorder(scenario.approvalPolicy());

        // 4. Wire processor + loop (strict flag threaded through to the loop)
        SessionApprovalPolicy approvalPolicy = new SessionApprovalPolicy();
        var processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry, approvalPolicy);
        var loop = new ToolCallLoop(
                processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS, null, strict);

        // 5. Build minimal message list (system + user placeholders)
        String userPrompt = scenario.userPrompt().isBlank()
                ? "scenario: " + scenario.name()
                : scenario.userPrompt();
        var messages = new ArrayList<ChatMessage>(List.of(
                ChatMessage.system("harness"),
                ChatMessage.user(userPrompt)));

        // 6. Run the scripted response through the tool loop.
        // Sandbox MUST be rooted at the temp workspace so relative paths resolve correctly.
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace.path(), Map.of()))
                .llm(llm)
                .executionPhaseState(new ExecutionPhaseState(scenarioPhaseOrApply(scenario)))
                .build();
        ToolCallLoop.LoopResult loopResult;
        TurnUserRequestCapture.set(userPrompt);
        try {
            loopResult = loop.run(scenario.scriptedResponse(), messages,
                    workspace.path(), ctx);
        } finally {
            TurnUserRequestCapture.clear();
        }

        // 7. Collect tool result texts from the conversation for assertions
        List<String> toolResultTexts = messages.stream()
                .filter(m -> "tool_result".equals(m.role()) || isToolResultContent(m.content()))
                .map(ChatMessage::content)
                .filter(c -> c != null)
                .toList();

        return new ScenarioResult(scenario, loopResult, workspace, toolResultTexts,
                gate.asked, gate.granted, gate.denied, gate.remembered, gate.details,
                null, "", null, List.of(), 0, List.of(), List.of(llm));
    }

    // ── Private helpers ──────────────────────────────────────────────

    private static boolean isToolResultContent(String content) {
        return content != null && content.contains("[tool_result:");
    }

    /** Run a scenario through the loop and persist snapshot + turn log for artifact assertions. */
    public static ScenarioResult runWithPersistence(ScenarioDefinition scenario,
                                                    Result assistantResult,
                                                    TurnAudit audit) {
        var base = runInternal(scenario, false);
        Path sessionsDir = null;
        LlmClient llm = null;
        try {
            sessionsDir = java.nio.file.Files.createTempDirectory("talos-e2e-sessions-");
            JsonSessionStore store = new JsonSessionStore(sessionsDir);
            String sessionId = JsonSessionStore.sessionIdFor(base.workspace().path());

            SessionMemory memory = new SessionMemory();
            ConversationManager cm = new ConversationManager(memory);
            // Determinism: persistence path must not consult a real backend.
            // MemoryUpdateListener.onTurnComplete delegates to
            // ConversationManager.maybeCompact(llm), which would otherwise
            // call LlmClient.chatFull(...) for sketch generation and
            // introduce network-dependent nondeterminism into snapshots.
            llm = LlmClient.scripted(java.util.List.of(""));
            MemoryUpdateListener memoryListener = new MemoryUpdateListener(cm, llm);
            JsonTurnLogAppender appender = new JsonTurnLogAppender(store, sessionId);

            TurnResult turnResult = new TurnResult(
                    assistantResult,
                    null,
                    1,
                    Duration.ofMillis(25),
                    audit == null ? TurnAudit.empty() : audit
            );

            String userPrompt = scenario.userPrompt().isBlank()
                    ? "scenario: " + scenario.name()
                    : scenario.userPrompt();
            memoryListener.onTurnComplete(turnResult, userPrompt);
            appender.onTurnComplete(turnResult, userPrompt);

            SessionData snapshot = new SessionData(
                    sessionId,
                    base.workspace().path().toString(),
                    cm.sketch() == null ? "" : cm.sketch(),
                    cm.turnCount(),
                    Instant.now(),
                    memory.getTurns().stream()
                            .map(m -> new SessionData.Turn(m.role(), m.content(),
                                    "assistant".equals(m.role()) ? "ok" : ""))
                            .toList(),
                    llm.getModel()
            );
            store.save(snapshot);

            List<TurnRecord> turnLog = store.loadTurns(sessionId);
            List<AutoCloseable> resourcesToClose = new ArrayList<>(base.resourcesToClose());
            resourcesToClose.add(llm);
            return new ScenarioResult(
                    base.definition(),
                    base.loopResult(),
                    base.workspace(),
                    base.toolResultTexts(),
                    base.approvalsAsked(),
                    base.approvalsGranted(),
                    base.approvalsDenied(),
                    base.approvalsRemembered(),
                    base.approvalDetails(),
                    sessionsDir,
                    sessionId,
                    store.load(sessionId).orElse(snapshot),
                    turnLog,
                    0,
                    List.of(),
                    resourcesToClose
            );
        } catch (Exception e) {
            try {
                if (llm != null) llm.close();
            } catch (Exception ignored) { }
            deleteRecursive(sessionsDir);
            try {
                base.closeWorkspace();
            } catch (Exception ignored) { }
            throw new RuntimeException("Failed to run persistent scenario: " + scenario.name(), e);
        }
    }

    /** Replay turn-log fallback path via TalosBootstrap.replayTurnLog using reflection to avoid widening prod seams. */
    public static ScenarioResult replayTurnLogFallback(ScenarioDefinition scenario,
                                                       List<TurnRecord> records) {
        try {
            var workspace = ScenarioWorkspaceFixture.withFiles(scenario.initialFiles());
            Path sessionsDir = java.nio.file.Files.createTempDirectory("talos-e2e-replay-");
            JsonSessionStore store = new JsonSessionStore(sessionsDir);
            String sessionId = JsonSessionStore.sessionIdFor(workspace.path());
            for (TurnRecord record : records) {
                store.appendTurn(sessionId, record);
            }

            SessionMemory memory = new SessionMemory();
            ConversationManager cm = new ConversationManager(memory);

            Method replay = dev.talos.cli.repl.TalosBootstrap.class.getDeclaredMethod(
                    "replayTurnLog", SessionStore.class, String.class, SessionMemory.class);
            replay.setAccessible(true);
            int replayed = (Integer) replay.invoke(null, store, sessionId, memory);

            List<String> restoredAssistantTurns = memory.getTurns().stream()
                    .filter(m -> "assistant".equals(m.role()))
                    .map(ChatMessage::content)
                    .toList();

            return new ScenarioResult(
                    scenario,
                    new ToolCallLoop.LoopResult("", 0, 0, List.of(), new ArrayList<>(),
                            0, 0, false, 0, List.of(), 0, 0, 0, 0),
                    workspace,
                    List.of(),
                    0, 0, 0, 0, List.of(),
                    sessionsDir,
                    sessionId,
                    null,
                    store.loadTurns(sessionId),
                    replayed,
                    restoredAssistantTurns,
                    List.of()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to replay turn-log fallback scenario: " + scenario.name(), e);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  N4 — harness drives AssistantTurnExecutor end-to-end
    //
    //  runThroughExecutor exercises the full executor path (streaming /
    //  non-streaming dispatch, tool-call loop, R2/R6/N2/N3 gates,
    //  synthesis retry, sanitization) against a scripted LlmClient.
    //  Use this when a scenario needs to assert on the ANSWER text
    //  produced by those gates — in particular the T5-shape end-to-end
    //  regression (scripted false-mutation claim → FALSE_MUTATION_
    //  ANNOTATION prepended to the final answer).
    //
    //  Scenarios that only need ToolCallLoop behavior should keep using
    //  run() / runStrict() — those do NOT invoke the executor gates.
    //  See docs/new-architecture/talos-harness-main-plan.md §8 N4.
    // ══════════════════════════════════════════════════════════════════

    /**
     * Drive a scenario end-to-end through {@link AssistantTurnExecutor#execute}
     * using a scripted {@link LlmClient} (one response per LLM turn,
     * clamps to the last after exhaustion).
     *
     * <p>The {@code scriptedResponses} are emitted by the scripted
     * client in order: response 0 is the initial turn; subsequent
     * entries satisfy re-prompts inside the tool-call loop and any
     * gate retries (R6 / synthesis retry).
     *
     * <p>The {@code scenario}'s own {@link ScenarioDefinition#scriptedResponse()}
     * field is intentionally ignored on this path — the executor
     * needs multiple turns, which the single-string field cannot
     * express. Initial files, name, and approval policy are honored
     * as for {@link #run(ScenarioDefinition)}.
     *
     * <p>Runs non-streaming (no {@code streamSink}) for deterministic
     * assertions. When a future scenario requires the streaming
     * branch, add a sibling {@code runThroughExecutorStreaming}.
     *
     * @param scenario         scenario definition (files, name, policy)
     * @param userPrompt       the verbatim user message for the turn
     *                         (drives R6 / N3 marker matching)
     * @param scriptedResponses ordered model outputs, one per LLM turn
     */
    public static ExecutorScenarioResult runThroughExecutor(
            ScenarioDefinition scenario,
            String userPrompt,
            List<String> scriptedResponses) {
        return runThroughExecutorWithHistory(scenario, List.of(), userPrompt, scriptedResponses);
    }

    /**
     * Drive the executor with explicit prior conversation history before the
     * current user prompt. Used for multi-turn scenario seeds where the runtime
     * behavior depends on previous verified assistant text.
     */
    public static ExecutorScenarioResult runThroughExecutorWithHistory(
            ScenarioDefinition scenario,
            List<ChatMessage> history,
            String userPrompt,
            List<String> scriptedResponses) {

        // 1. Workspace fixture (same as run()).
        var workspace = ScenarioWorkspaceFixture.withFiles(scenario.initialFiles());

        // 2. Tool registry against the fixture workspace.
        var undoStack = new FileUndoStack();
        var registry  = new ToolRegistry(false);
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        registry.register(new GrepTool());
        registry.register(new ListDirTool());

        // 3. Approval gate per scenario policy.
        GateRecorder gate = new GateRecorder(scenario.approvalPolicy());

        // 4. Turn processor + tool-call loop (normal mode; N4 scope).
        var processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry);
        var loop = new ToolCallLoop(
                processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS, null, false);

        // 5. Structured messages: system + optional history + verbatim user prompt.
        var messages = new ArrayList<ChatMessage>(List.of(
                ChatMessage.system("harness (executor path)"),
                ChatMessage.user(userPrompt)));
        if (history != null && !history.isEmpty()) {
            messages = new ArrayList<>();
            messages.add(ChatMessage.system("harness (executor path)"));
            messages.addAll(history);
            messages.add(ChatMessage.user(userPrompt));
        }

        // 6. Scripted LlmClient + Context wired with llm override,
        //    sandbox rooted at workspace, and the tool-call loop.
        //    No streamSink → non-streaming path, deterministic.
        var scriptedLlm = LlmClient.scripted(scriptedResponses);
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace.path(), Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .llm(scriptedLlm)
                .executionPhaseState(new ExecutionPhaseState(scenarioPhaseOrApply(scenario)))
                .build();

        // 7. Drive the executor end-to-end.
        var opts = new AssistantTurnExecutor.Options();
        AssistantTurnExecutor.TurnOutput turnOut;
        LocalTurnTrace localTrace;
        TurnUserRequestCapture.set(userPrompt);
        beginExecutorHarnessTrace(scenario, workspace, userPrompt);
        try {
            turnOut = AssistantTurnExecutor.execute(messages, workspace.path(), ctx, opts);
            LocalTurnTraceCapture.recordModelResponseReceived(turnOut.text());
            LocalTurnTraceCapture.recordOutcomeIfAbsent("OK", "NOT_RUN", "UNKNOWN", "UNKNOWN", "EXECUTOR_SCENARIO");
            localTrace = LocalTurnTraceCapture.complete();
            TurnAuditCapture.end();
        } finally {
            TurnUserRequestCapture.clear();
            LocalTurnTraceCapture.clear();
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        }

        return new ExecutorScenarioResult(
                scenario, turnOut, workspace, scriptedLlm,
                "",
                gate.asked, gate.granted, gate.denied, gate.remembered,
                localTrace);
    }

    /**
     * Streaming sibling of {@link #runThroughExecutor(ScenarioDefinition, String, List)}.
     *
     * <p>Drives {@link AssistantTurnExecutor#execute} with a real {@code streamSink}
     * so the streaming branch executes. The sink buffers emitted chunks only to keep
     * the test seam deterministic; assertions should still use the executor's final
     * answer text via {@link ExecutorScenarioResult#finalAnswer()}.
     */
    public static ExecutorScenarioResult runThroughExecutorStreaming(
            ScenarioDefinition scenario,
            String userPrompt,
            List<String> scriptedResponses) {

        var workspace = ScenarioWorkspaceFixture.withFiles(scenario.initialFiles());

        var undoStack = new FileUndoStack();
        var registry  = new ToolRegistry(false);
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        registry.register(new GrepTool());
        registry.register(new ListDirTool());

        GateRecorder gate = new GateRecorder(scenario.approvalPolicy());

        var processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry);
        var loop = new ToolCallLoop(
                processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS, null, false);

        var messages = new ArrayList<ChatMessage>(List.of(
                ChatMessage.system("harness (executor path, streaming)"),
                ChatMessage.user(userPrompt)));

        var streamedChunks = new StringBuilder();
        var scriptedLlm = LlmClient.scripted(scriptedResponses);
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace.path(), Map.of()))
                .toolRegistry(registry)
                .toolCallLoop(loop)
                .llm(scriptedLlm)
                .streamSink(streamedChunks::append)
                .executionPhaseState(new ExecutionPhaseState(scenarioPhaseOrApply(scenario)))
                .build();

        var opts = new AssistantTurnExecutor.Options();
        AssistantTurnExecutor.TurnOutput turnOut;
        LocalTurnTrace localTrace;
        TurnUserRequestCapture.set(userPrompt);
        beginExecutorHarnessTrace(scenario, workspace, userPrompt);
        try {
            turnOut = AssistantTurnExecutor.execute(messages, workspace.path(), ctx, opts);
            LocalTurnTraceCapture.recordModelResponseReceived(turnOut.text());
            LocalTurnTraceCapture.recordOutcomeIfAbsent("OK", "NOT_RUN", "UNKNOWN", "UNKNOWN", "EXECUTOR_SCENARIO");
            localTrace = LocalTurnTraceCapture.complete();
            TurnAuditCapture.end();
        } finally {
            TurnUserRequestCapture.clear();
            LocalTurnTraceCapture.clear();
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        }

        return new ExecutorScenarioResult(
                scenario, turnOut, workspace, scriptedLlm,
                streamedChunks.toString(),
                gate.asked, gate.granted, gate.denied, gate.remembered,
                localTrace);
    }

    private static void beginExecutorHarnessTrace(
            ScenarioDefinition scenario,
            ScenarioWorkspaceFixture workspace,
            String userPrompt
    ) {
        TurnAuditCapture.begin();
        String name = scenario == null || scenario.name() == null ? "scenario" : scenario.name();
        String traceId = "trc-scenario-" + name.replaceAll("[^A-Za-z0-9._-]", "_");
        LocalTurnTraceCapture.begin(
                traceId,
                "scenario-session",
                1,
                "2026-04-28T00:00:00Z",
                "workspace:" + Integer.toHexString(workspace.path().toString().hashCode()),
                "harness",
                "scripted",
                "scripted",
                userPrompt);
    }

    private static final class GateRecorder implements ApprovalGate {
        private final ScenarioApprovalPolicy policy;
        private int asked;
        private int granted;
        private int denied;
        private int remembered;
        private final List<String> details = new ArrayList<>();

        private GateRecorder(ScenarioApprovalPolicy policy) {
            this.policy = policy == null ? ScenarioApprovalPolicy.APPROVE_ALL : policy;
        }

        @Override
        public boolean approve(String description, String detail) {
            return approveFull(description, detail).isApproved();
        }

        @Override
        public ApprovalResponse approveFull(String description, String detail) {
            asked++;
            if (detail != null) details.add(detail);
            return switch (policy) {
                case APPROVE_ALL -> {
                    granted++;
                    yield ApprovalResponse.APPROVED;
                }
                case APPROVE_REMEMBER_WRITES -> {
                    granted++;
                    remembered++;
                    yield ApprovalResponse.APPROVED_REMEMBER;
                }
                case DENY_WRITES, DENY_ALL -> {
                    denied++;
                    yield ApprovalResponse.DENIED;
                }
            };
        }
    }

    private static ApprovalGate policyGate(ScenarioApprovalPolicy policy) {
        return new GateRecorder(policy == null ? ScenarioApprovalPolicy.APPROVE_ALL : policy);
    }

    private static ExecutionPhase scenarioPhaseOrApply(ScenarioDefinition scenario) {
        return scenario.executionPhase() == null ? ExecutionPhase.APPLY : scenario.executionPhase();
    }

    private static void deleteRecursive(Path path) {
        if (path == null || !java.nio.file.Files.exists(path)) return;
        try (var walk = java.nio.file.Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { java.nio.file.Files.deleteIfExists(p); }
                        catch (Exception ignored) { }
                    });
        } catch (Exception ignored) { }
    }
}


