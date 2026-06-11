package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TargetReadbackCompactRepairPlannerTest {
    @TempDir
    Path workspace;

    @Test
    void planBuildsAppendLineRepairFrame() {
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Demo\n");
        state.toolOutcomes.add(appendLineFailure("README.md"));

        Optional<TargetReadbackCompactRepairPlanner.Plan> plan =
                TargetReadbackCompactRepairPlanner.nextAppendLinePlan(state, baseTools(), request);

        assertTrue(plan.isPresent(), "append-line preservation failure should produce a compact repair plan");
        TargetReadbackCompactRepairPlanner.Plan repair = plan.get();
        assertEquals(TargetReadbackCompactRepairPlanner.Kind.APPEND_LINE, repair.kind());
        assertEquals("README.md", repair.path());
        assertEquals("readme.md", repair.promptedPathKey());
        assertEquals("append-line compact repair", repair.retryName());
        assertEquals(List.of("talos.edit_file", "talos.write_file"), toolNames(repair.tools()));
        assertEquals(ToolChoiceMode.REQUIRED, repair.controls().toolChoice());
        assertEquals(List.of("pending-action-obligation", "append-line-compact-repair"),
                repair.controls().debugTags());

        String prompt = prompt(repair.messages());
        assertTrue(prompt.contains("[AppendLineRepair] Target: README.md"), prompt);
        assertTrue(prompt.contains("Required appended line: Release gate note"), prompt);
        assertTrue(prompt.contains("Current readback for README.md"), prompt);
        assertTrue(prompt.contains("1 | # Demo"), prompt);
        assertTrue(prompt.contains(request), prompt);
        assertFalse(prompt.contains("large-system-token"), prompt);
        assertFalse(prompt.contains("Earlier unrelated request"), prompt);
    }

    @Test
    void planBuildsOldStringMissRepairFrame() {
        String request = "Edit README.md by replacing Original text. with Applied proposal.";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Fixture\n2 | Original text.\n");
        state.toolOutcomes.add(oldStringMissFailure("README.md"));

        Optional<TargetReadbackCompactRepairPlanner.Plan> plan =
                TargetReadbackCompactRepairPlanner.nextOldStringMissPlan(state, baseTools(), request);

        assertTrue(plan.isPresent(), "old-string miss should produce a compact repair plan");
        TargetReadbackCompactRepairPlanner.Plan repair = plan.get();
        assertEquals(TargetReadbackCompactRepairPlanner.Kind.OLD_STRING_MISS, repair.kind());
        assertEquals("README.md", repair.path());
        assertEquals("readme.md", repair.promptedPathKey());
        assertEquals("old-string miss compact repair", repair.retryName());
        assertEquals(List.of("talos.edit_file", "talos.write_file"), toolNames(repair.tools()));
        assertEquals(ToolChoiceMode.REQUIRED, repair.controls().toolChoice());
        assertEquals(List.of("pending-action-obligation", "old-string-miss-compact-repair"),
                repair.controls().debugTags());

        String prompt = prompt(repair.messages());
        assertTrue(prompt.contains("[OldStringMissRepair] Target: README.md"), prompt);
        assertTrue(prompt.contains("Failed reason: old_string not found"), prompt);
        assertTrue(prompt.contains("Current readback for README.md"), prompt);
        assertTrue(prompt.contains("1 | # Fixture"), prompt);
        assertTrue(prompt.contains(request), prompt);
        assertFalse(prompt.contains("large-system-token"), prompt);
        assertFalse(prompt.contains("Earlier unrelated request"), prompt);
    }

    @Test
    void oldStringMissPlanDoesNotUseReadbackBeforeSuccessfulMutation() {
        String request = "Edit README.md by replacing Original text. with Applied proposal.";
        LoopState state = loopState(request);
        addReadback(state, "README.md", "1 | # Fixture\n2 | Original text.\n");
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "README.md",
                true,
                true,
                false,
                "Wrote README.md",
                ""));
        state.toolOutcomes.add(oldStringMissFailure("README.md"));

        Optional<TargetReadbackCompactRepairPlanner.Plan> plan =
                TargetReadbackCompactRepairPlanner.nextOldStringMissPlan(state, baseTools(), request);

        assertTrue(plan.isEmpty(), "stale readbacks from before a same-turn mutation must not seed repair");
    }

    @Test
    void targetReadbackDecisionDelegatesTargetReadbackCompactRepairPlanningToOwner() throws Exception {
        String stageSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String decisionSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptTargetReadbackRepairDecision.java"));

        assertFalse(stageSource.contains("TargetReadbackCompactRepairPlanner.nextAppendLinePlan"), stageSource);
        assertFalse(stageSource.contains("TargetReadbackCompactRepairPlanner.nextOldStringMissPlan"), stageSource);
        assertTrue(decisionSource.contains("TargetReadbackCompactRepairPlanner.nextAppendLinePlan"), decisionSource);
        assertTrue(decisionSource.contains("TargetReadbackCompactRepairPlanner.nextOldStringMissPlan"), decisionSource);
        assertFalse(stageSource.contains("private static Optional<AppendLineRepair> "
                + "nextAppendLineCompactRepair"), stageSource);
        assertFalse(stageSource.contains("private static Optional<OldStringMissRepair> "
                + "nextOldStringMissCompactRepair"), stageSource);
        assertFalse(stageSource.contains("private static List<ChatMessage> appendLineRepairMessages"), stageSource);
        assertFalse(stageSource.contains("private static List<ChatMessage> oldStringMissRepairMessages"), stageSource);
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

    private static ToolCallLoop.ToolOutcome appendLineFailure(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "append-line write_file did not preserve same-turn readback",
                null,
                ToolError.INVALID_PARAMS)
                .withFailureReason(ToolFailureReason.WRITE_APPEND_LINE_PRESERVATION);
    }

    private static ToolCallLoop.ToolOutcome oldStringMissFailure(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                path,
                false,
                true,
                false,
                "",
                "old_string not found",
                null,
                ToolError.INVALID_PARAMS)
                .withFailureReason(ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
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
