package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import dev.talos.runtime.repair.RepairPolicy;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallRepromptStageTest {

    @Test
    void directoryListingStopsAfterSuccessfulListDir() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("What files are in this folder?"),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "list_dir", java.util.Map.of("path", ".")))),
                ChatMessage.toolResult("call-1", """
                        [tool_result: list_dir]
                        README.md
                        index.html
                        notes.md
                        [/tool_result]""")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                null,
                null,
                10,
                0);
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 0, false, false, false, 1);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertFalse(shouldReprompt);
        assertEquals("""
                Directory entries:
                - README.md
                - index.html
                - notes.md""", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void readOnlyQaStopsAfterSuccessfulNamedReadAliasWhenLoopMakesNoProgress() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Read config.json and tell me the name."),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "read_file", java.util.Map.of("path", "config.json")))),
                ChatMessage.toolResult("call-1", """
                        [tool_result: read_file]
                        1 | {"name":"t57-fixture"}
                        [/tool_result]"""),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-2", "talos.read_file", java.util.Map.of("path", "config.json")))),
                ChatMessage.toolResult("call-2", """
                        [tool_result: talos.read_file]
                        You already gathered this information and the workspace has not changed since then.
                        [/tool_result]""")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                null,
                null,
                10,
                0);
        state.toolOutcomes.add(new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                "read_file",
                "config.json",
                true,
                false,
                false,
                "read config.json",
                ""));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 0, false, false, false, 0);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertFalse(shouldReprompt);
        assertEquals("""
                Read config.json:
                1 | {"name":"t57-fixture"}""", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void workspaceOperationSuccessesSatisfyExpectedProgressTargetsAndStopReprompt() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(
                        "Organize these files using workspace operation tools only: copy README.md to "
                                + "docs/notes/README-copy.md, move scratch/todo.md to docs/todo.md, "
                                + "then rename docs/todo.md to tasks.md. Do not use command execution.")
        ));
        LoopState state = new LoopState(
                "",
                List.of(),
                messages,
                Path.of("."),
                null,
                null,
                10,
                0);
        WorkspaceOperationPlan copyPlan = WorkspaceOperationPlan.copyPath(
                "README.md",
                "docs/notes/README-copy.md",
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false);
        WorkspaceOperationPlan movePlan = WorkspaceOperationPlan.movePath(
                "scratch/todo.md",
                "docs/todo.md",
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS);
        WorkspaceOperationPlan renamePlan = WorkspaceOperationPlan.batch(
                WorkspaceOperationPlan.OperationKind.RENAME_PATH,
                List.of(
                        WorkspaceOperationPlan.PathEffect.source(
                                "docs/todo.md", true, WorkspaceOperationPlan.OperationKind.RENAME_PATH),
                        WorkspaceOperationPlan.PathEffect.destination(
                                "docs/tasks.md", true, WorkspaceOperationPlan.OperationKind.RENAME_PATH)),
                dev.talos.tools.ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false,
                "Rename docs/todo.md to docs/tasks.md.",
                "Rename: docs/todo.md -> docs/tasks.md");
        state.toolOutcomes.add(workspaceOutcome(
                "talos.copy_path", "docs/notes/README-copy.md", copyPlan));
        state.toolOutcomes.add(workspaceOutcome(
                "talos.move_path", "docs/todo.md", movePlan));
        state.toolOutcomes.add(workspaceOutcome(
                "talos.rename_path", "docs/tasks.md", renamePlan));

        var outcome = new ToolCallExecutionStage.IterationOutcome(
                3,
                List.of("✓ Copied README.md", "✓ Moved scratch/todo.md", "✓ Renamed docs/todo.md"),
                0,
                false,
                false,
                false,
                3);

        boolean shouldReprompt = new ToolCallRepromptStage().reprompt(state, outcome);

        assertFalse(shouldReprompt);
        assertEquals("""
                ✓ Copied README.md
                ✓ Moved scratch/todo.md
                ✓ Renamed docs/todo.md""", state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void emptyEditRepairIsAvailableOnlyAfterTargetWasReadAndOnlyOnce() {
        LoopState state = new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"))),
                Path.of("."),
                null,
                null,
                10,
                0);

        state.emptyEditArgumentFailuresByPath.put("index.html", 1);

        assertTrue(RepairPolicy.nextEmptyEditRepair(state).isEmpty(),
                "An empty edit failure alone is not enough; the model must read the target first.");

        state.pathsReadThisTurn.add("index.html");

        var repair = RepairPolicy.nextEmptyEditRepair(state);
        assertTrue(repair.isPresent());
        assertEquals("index.html", repair.get().path());
        assertTrue(repair.get().instruction().contains("[Edit repair required]"));
        assertTrue(repair.get().instruction().contains("non-empty old_string"));
        assertTrue(repair.get().instruction().contains("new_string parameter"));
        assertTrue(repair.get().instruction().contains("empty only for an explicit deletion task"));
        assertTrue(repair.get().instruction().chars().allMatch(c -> c <= 127),
                "Repair instruction should stay ASCII-safe for terminal transcripts.");

        state.emptyEditRepairPromptedPaths.add("index.html");

        assertTrue(RepairPolicy.nextEmptyEditRepair(state).isEmpty(),
                "The specialized repair instruction is one-shot per path.");
    }

    @Test
    void repromptStageDoesNotExposeRepairPolicyWrappers() throws Exception {
        String stageSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String overlaySource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptMessageOverlay.java"));

        assertFalse(stageSource.contains("RepairPolicy.nextStaleEditRepair(state)"), stageSource);
        assertFalse(stageSource.contains("RepairPolicy.nextEmptyEditRepair(state)"), stageSource);
        assertTrue(overlaySource.contains("RepairPolicy.nextStaleEditRepair(state)"), overlaySource);
        assertTrue(overlaySource.contains("RepairPolicy.nextEmptyEditRepair(state)"), overlaySource);
        assertFalse(stageSource.contains("static Optional<RepairInstruction> nextStaleEditRepair"), stageSource);
        assertFalse(stageSource.contains("static String staleEditRepairInstruction"), stageSource);
        assertFalse(stageSource.contains("static Optional<RepairInstruction> nextEmptyEditRepair"), stageSource);
        assertFalse(stageSource.contains("static String emptyEditRepairInstruction"), stageSource);
    }

    @Test
    void repromptStageDoesNotOwnAliasCanonicalization() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertFalse(source.contains("import dev.talos.tools.ToolAliasPolicy;"), source);
        assertFalse(source.contains("canonicalToolName("), source);
    }

    @Test
    void repromptStageDoesNotImportTaskContractResolvers() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertFalse(source.contains("import dev.talos.runtime.task.TaskContract;"), source);
        assertFalse(source.contains("import dev.talos.runtime.task.TaskContractResolver;"), source);
    }

    @Test
    void repromptStageDelegatesTemporaryMessageOverlayLifecycle() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String overlayContinuation = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptOverlayContinuation.java"));

        assertFalse(source.contains("ToolRepromptMessageOverlay.apply("), source);
        assertTrue(overlayContinuation.contains("ToolRepromptMessageOverlay.apply("), overlayContinuation);
        assertFalse(source.contains("int staleRepairIndex"), source);
        assertFalse(source.contains("int emptyRepairIndex"), source);
        assertFalse(source.contains("int repairProgressIndex"), source);
        assertFalse(source.contains("int expectedProgressIndex"), source);
        assertFalse(source.contains("int anchorIndex"), source);
        assertFalse(source.contains("startsWith(\"[Stale edit repair required]\")"), source);
        assertFalse(source.contains("startsWith(\"[Edit repair required]\")"), source);
        assertFalse(source.contains("startsWith(\"[Static repair progress]\")"), source);
        assertFalse(source.contains("startsWith(\"[Expected target progress]\")"), source);
        assertFalse(source.contains("startsWith(\"[Current task\")"), source);
    }

    @Test
    void repromptStageDelegatesStaticRepairTargetProgressAccounting() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains(
                "StaticRepairTargetProgressAccounting.remainingFullRewriteRepairTargets(state)"), source);
        assertTrue(source.contains("StaticRepairTargetProgressAccounting.hasStaticRepairContext(state)"), source);
        assertFalse(source.contains("private static List<String> remainingFullRewriteRepairTargets"), source);
        assertFalse(source.contains("private static boolean hasStaticRepairContext"), source);
    }

    @Test
    void repromptStageDoesNotOwnNormalChatRepromptExecution() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String executor = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptChatExecutor.java"));

        assertTrue(executor.contains("static boolean execute("), executor);
        assertFalse(source.contains("ToolRepromptChatExecutor.executeResult("), source);
        assertFalse(source.contains("ToolRepromptChatExecutor.executeRetryResult("), source);
        assertFalse(source.contains("private static boolean chatReprompt("), source);
        assertFalse(source.contains("private static boolean chatRepromptResult("), source);
    }

    @Test
    void repromptStageDelegatesGenericOverlayContinuation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("ToolRepromptOverlayContinuation.execute("), source);
        assertFalse(source.contains("ToolRepromptMessageOverlay.apply("), source);
        assertFalse(source.contains("ToolRepromptChatExecutor.executeResult("), source);
        assertFalse(source.contains("ToolRepromptChatExecutor.executeRetryResult("), source);
        assertFalse(source.contains("Thread.sleep(400)"), source);
        assertFalse(source.contains("catch (EngineException.Transient"), source);
    }

    @Test
    void repromptStageDelegatesSuccessfulMutationDecision() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("ToolRepromptSuccessfulMutationDecision.tryHandle("), source);
        assertFalse(source.contains("StaticWebContinuationPlanner.staticWebVerificationAlreadyPasses"), source);
        assertFalse(source.contains("StaticWebContinuationPlanner.nextPlan("), source);
        assertFalse(source.contains("P0: skipping re-prompt"), source);
    }

    @Test
    void repromptStageDelegatesStaleEditRereadStop() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("ToolRepromptStaleEditRereadStop.tryHandle("), source);
        assertFalse(source.contains("import dev.talos.runtime.failure.FailureAction;"), source);
        assertFalse(source.contains("import dev.talos.safety.SafeLogFormatter;"), source);
        assertFalse(source.contains("staleEditRereadIgnoredPath != null"), source);
        assertFalse(source.contains("before rereading the file after a same-turn mutation changed it"), source);
    }

    @Test
    void repromptStageDelegatesSourceEvidenceRepairDecision() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("ToolRepromptSourceEvidenceRepairDecision.tryHandle("), source);
        assertFalse(source.contains("SourceEvidenceExactRepairPlanner.nextPlan("), source);
        assertFalse(source.contains("sourceEvidenceExactRepairPromptedKeys.add"), source);
        assertFalse(source.contains("source-evidence exact compact repair"), source);
    }

    @Test
    void repromptStageDelegatesTargetReadbackRepairDecision() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("ToolRepromptTargetReadbackRepairDecision.tryHandle("), source);
        assertFalse(source.contains("TargetReadbackCompactRepairPlanner.nextAppendLinePlan("), source);
        assertFalse(source.contains("TargetReadbackCompactRepairPlanner.nextOldStringMissPlan("), source);
        assertFalse(source.contains("appendLineRepairPromptedPaths.add"), source);
        assertFalse(source.contains("oldStringMissRepairPromptedPaths.add"), source);
    }

    private static dev.talos.runtime.ToolCallLoop.ToolOutcome workspaceOutcome(
            String toolName,
            String pathHint,
            WorkspaceOperationPlan plan
    ) {
        return new dev.talos.runtime.ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                true,
                true,
                false,
                "workspace operation applied",
                "",
                null,
                "",
                plan);
    }
}
