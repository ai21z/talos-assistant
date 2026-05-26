package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ExpectedTargetScopeRepairPlannerTest {
    @TempDir
    Path workspace;

    @Test
    void planBuildsExactReplacementRepairCallForExpectedTarget() {
        String request = "Read script.js, then fix the selector bug by changing .missing-button to .cta-button. "
                + "Do not edit scripts.js.";
        LoopState state = loopState(request);
        addReadback(state, "script.js", "1 | document.querySelector('.missing-button')\n");
        state.toolOutcomes.add(expectedTargetFailure("scripts.js"));

        Optional<ExpectedTargetScopeRepairPlanner.Plan> plan =
                ExpectedTargetScopeRepairPlanner.nextPlan(state, baseTools(), request);

        assertTrue(plan.isPresent(), "wrong-target scope block should produce expected-target repair");
        ExpectedTargetScopeRepairPlanner.Plan repair = plan.get();
        assertEquals(List.of("script.js"), repair.expectedTargets());
        assertEquals("scripts.js", repair.failedTarget());
        assertEquals("scripts.js->script.js", repair.key());
        assertEquals("expected-target scope compact repair", repair.retryName());
        assertEquals(List.of("talos.edit_file", "talos.write_file"), toolNames(repair.tools()));
        assertEquals(ToolChoiceMode.REQUIRED, repair.controls().toolChoice());
        assertEquals(List.of("pending-action-obligation", "expected-target-scope-compact-repair"),
                repair.controls().debugTags());

        ChatMessage.NativeToolCall exactRepair = repair.exactReplacementRepair();
        assertNotNull(exactRepair, "single-target replacement should stay runtime-owned");
        assertEquals("runtime_expected_target_repair", exactRepair.id());
        assertEquals("talos.edit_file", exactRepair.name());
        assertEquals("script.js", exactRepair.arguments().get("path"));
        assertEquals(".missing-button", exactRepair.arguments().get("old_string"));
        assertEquals(".cta-button", exactRepair.arguments().get("new_string"));
        assertTrue(repair.traceDetail().contains("target=script.js"), repair.traceDetail());
        assertTrue(repair.traceDetail().contains("wrong-target block=scripts.js"), repair.traceDetail());

        String prompt = prompt(repair.messages());
        assertTrue(prompt.contains("[ExpectedTargetRepair]"), prompt);
        assertTrue(prompt.contains("Expected target(s): script.js"), prompt);
        assertTrue(prompt.contains("Failed attempted target: scripts.js"), prompt);
        assertTrue(prompt.contains("Exact replacement: old_string=`.missing-button` new_string=`.cta-button`"), prompt);
        assertTrue(prompt.contains("Current readback for script.js"), prompt);
        assertTrue(prompt.contains(request), prompt);
        assertFalse(prompt.contains("large-system-token"), prompt);
        assertFalse(prompt.contains("Earlier unrelated request"), prompt);
    }

    @Test
    void planIncludesGeneratedStaticWebReadbacksForMissingTargetRepair() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<!doctype html><button id=\"playBtn\">Play</button>\n");
        Files.writeString(workspace.resolve("style.css"), "body { color: white; }\n");
        String request = "Create the full synthwave frontend now with exactly index.html, style.css, and script.js.";
        LoopState state = loopState(request);
        state.mutatingToolSuccesses = 2;
        state.toolOutcomes.add(successfulWrite("index.html"));
        state.toolOutcomes.add(successfulWrite("style.css"));
        state.toolOutcomes.add(expectedTargetFailure("readme_site.txt"));

        Optional<ExpectedTargetScopeRepairPlanner.Plan> plan =
                ExpectedTargetScopeRepairPlanner.nextPlan(state, baseTools(), request);

        assertTrue(plan.isPresent(), "static-web wrong-target block should produce missing-target repair");
        ExpectedTargetScopeRepairPlanner.Plan repair = plan.get();
        assertEquals(List.of("script.js"), repair.expectedTargets());
        assertEquals("readme_site.txt", repair.failedTarget());
        assertNull(repair.exactReplacementRepair(), "missing static web target should go through compact reprompt");

        String prompt = prompt(repair.messages());
        assertTrue(prompt.contains("[ExpectedTargetRepair]"), prompt);
        assertTrue(prompt.contains("Expected target(s): script.js"), prompt);
        assertTrue(prompt.contains("Failed attempted target: readme_site.txt"), prompt);
        assertTrue(prompt.contains("Current generated static web file index.html"), prompt);
        assertTrue(prompt.contains("<!doctype html><button id=\"playBtn\">Play</button>"), prompt);
        assertTrue(prompt.contains("Current generated static web file style.css"), prompt);
        assertTrue(prompt.contains("body { color: white; }"), prompt);
        assertTrue(prompt.contains(request), prompt);
        assertFalse(prompt.contains("large-system-token"), prompt);
        assertFalse(prompt.contains("Earlier unrelated request"), prompt);
    }

    @Test
    void pathPolicyDecisionDelegatesExpectedTargetScopeRepairPlanningToOwner() throws Exception {
        String stageSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String decisionSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptPathPolicyBlockedDecision.java"));

        assertFalse(stageSource.contains("ExpectedTargetScopeRepairPlanner.nextPlan"), stageSource);
        assertTrue(decisionSource.contains("ExpectedTargetScopeRepairPlanner.nextPlan"), decisionSource);
        assertFalse(stageSource.contains("private static Optional<ExpectedTargetRepair> "
                + "nextExpectedTargetScopeRepair"), stageSource);
        assertFalse(stageSource.contains("private static List<ChatMessage> expectedTargetRepairMessages"), stageSource);
        assertFalse(stageSource.contains("private static ChatMessage.NativeToolCall "
                + "exactExpectedTargetReplacementRepairCall"), stageSource);
    }

    private LoopState loopState(String request) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys " + "large-system-token ".repeat(100)),
                ChatMessage.user("Earlier unrelated request that must not enter compact repair."),
                ChatMessage.user(request)));
        var llm = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of())),
                16_384).client();
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm)
                .nativeToolSpecs(baseTools())
                .build();
        return new LoopState(
                "",
                List.of(),
                messages,
                workspace,
                ctx,
                null,
                10,
                0);
    }

    private static void addReadback(LoopState state, String path, String readback) {
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "Read " + path,
                ""));
        state.successfulReadCallBodies.put("talos.read_file:path=" + path + ";", readback);
    }

    private static ToolCallLoop.ToolOutcome expectedTargetFailure(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "Target outside expected targets before approval: attempted `" + path
                        + "` while current expected target set: script.js. Similar filenames are not interchangeable.",
                null,
                ToolError.INVALID_PARAMS);
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                true,
                true,
                false,
                "Wrote " + path,
                "",
                null,
                "",
                WorkspaceOperationPlan.batch(
                        WorkspaceOperationPlan.OperationKind.WRITE_FILE,
                        List.of(WorkspaceOperationPlan.PathEffect.destination(
                                path,
                                false,
                                WorkspaceOperationPlan.OperationKind.WRITE_FILE)),
                        ToolRiskLevel.WRITE,
                        false,
                        WorkspaceOperationPlan.OverwritePolicy.OVERWRITE,
                        false,
                        "Wrote " + path,
                        "Wrote " + path));
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"));
    }

    private static List<String> toolNames(List<ToolSpec> specs) {
        return specs.stream().map(ToolSpec::name).toList();
    }

    private static String prompt(List<ChatMessage> messages) {
        return messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
