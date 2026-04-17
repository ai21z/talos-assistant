package dev.talos.harness;

import dev.talos.cli.modes.AssistantTurnExecutor;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.*;
import dev.talos.tools.impl.*;

import java.nio.file.Path;
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

    private static ScenarioResult runInternal(ScenarioDefinition scenario, boolean strict) {
        // 1. Set up workspace
        var workspace = ScenarioWorkspaceFixture.withFiles(scenario.initialFiles());

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
        ApprovalGate gate = policyGate(scenario.approvalPolicy());

        // 4. Wire processor + loop (strict flag threaded through to the loop)
        var processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry);
        var loop = new ToolCallLoop(
                processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS, null, strict);

        // 5. Build minimal message list (system + user placeholders)
        var messages = new ArrayList<ChatMessage>(List.of(
                ChatMessage.system("harness"),
                ChatMessage.user("scenario: " + scenario.name())));

        // 6. Run the scripted response through the tool loop.
        // Sandbox MUST be rooted at the temp workspace so relative paths resolve correctly.
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace.path(), Map.of()))
                .build();
        var loopResult = loop.run(scenario.scriptedResponse(), messages,
                workspace.path(), ctx);

        // 7. Collect tool result texts from the conversation for assertions
        List<String> toolResultTexts = messages.stream()
                .filter(m -> "tool_result".equals(m.role()) || isToolResultContent(m.content()))
                .map(ChatMessage::content)
                .filter(c -> c != null)
                .toList();

        return new ScenarioResult(scenario, loopResult, workspace, toolResultTexts);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private static ApprovalGate policyGate(ScenarioApprovalPolicy policy) {
        return switch (policy) {
            case APPROVE_ALL -> (description, detail) -> true;
            case DENY_WRITES -> (description, detail) -> false;
            case DENY_ALL    -> (description, detail) -> false;
        };
    }

    private static boolean isToolResultContent(String content) {
        return content != null && content.contains("[tool_result:");
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
        ApprovalGate gate = policyGate(scenario.approvalPolicy());

        // 4. Turn processor + tool-call loop (normal mode; N4 scope).
        var processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry);
        var loop = new ToolCallLoop(
                processor, ToolCallLoop.DEFAULT_MAX_ITERATIONS, null, false);

        // 5. Structured messages: system + verbatim user prompt.
        var messages = new ArrayList<ChatMessage>(List.of(
                ChatMessage.system("harness (executor path)"),
                ChatMessage.user(userPrompt)));

        // 6. Scripted LlmClient + Context wired with llm override,
        //    sandbox rooted at workspace, and the tool-call loop.
        //    No streamSink → non-streaming path, deterministic.
        var scriptedLlm = LlmClient.scripted(scriptedResponses);
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace.path(), Map.of()))
                .toolCallLoop(loop)
                .llm(scriptedLlm)
                .build();

        // 7. Drive the executor end-to-end.
        var opts = new AssistantTurnExecutor.Options();
        AssistantTurnExecutor.TurnOutput turnOut =
                AssistantTurnExecutor.execute(messages, workspace.path(), ctx, opts);

        return new ExecutorScenarioResult(scenario, turnOut, workspace);
    }
}



