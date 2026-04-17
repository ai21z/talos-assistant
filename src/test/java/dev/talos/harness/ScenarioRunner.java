package dev.talos.harness;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
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
}



