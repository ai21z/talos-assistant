package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntent;
import dev.talos.runtime.intent.TaskIntentResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1201RepresentationEnforcementTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        TurnSourceEvidenceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void legacyOverlapProjectsOneCompositeRoleAndBothContractDimensions() {
        for (TaskContract legacy : List.of(
                overlappingContract("./assets/style.css", "assets\\style.css"),
                overlappingContract("assets\\style.css", "./assets/style.css"))) {
            TaskIntent intent = TaskIntentResolver.fromLegacyContract(legacy);
            TaskContract compiled = TaskContractCompiler.compile(intent);

            assertAll(
                    () -> assertEquals(1, intent.targets().targets().size()),
                    () -> assertEquals(
                            "MUST_MUTATE_AFTER_SOURCE_EVIDENCE",
                            intent.targets().find("assets/style.css").orElseThrow().role().name()),
                    () -> assertEquals(
                            Set.of("assets/style.css"),
                            compiled.expectedTargets()),
                    () -> assertEquals(
                            Set.of("assets/style.css"),
                            compiled.sourceEvidenceTargets()));
        }
    }

    @Test
    void compiledOverlapBlocksWriteBeforeReadThenUsesOrdinaryApprovalAfterRead()
            throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        TaskContract compiled = TaskContractCompiler.compile(
                TaskIntentResolver.fromLegacyContract(
                        overlappingContract("./style.css", "style.css")));
        TurnTaskContractCapture.set(compiled);
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        StageHarness harness = harness(
                toolCall("talos.write_file", "\"path\":\"style.css\",\"content\":\"after\""),
                registry,
                approvals);

        ToolCallExecutionStage.IterationOutcome beforeRead = execute(harness);
        ToolCallExecutionStage.IterationOutcome read = executeTextCall(
                harness,
                toolCall("talos.read_file", "\"path\":\"style.css\""));
        ToolCallExecutionStage.IterationOutcome afterRead = executeTextCall(
                harness,
                toolCall("talos.write_file", "\"path\":\"style.css\",\"content\":\"after\""));

        assertAll(
                () -> assertEquals(1, beforeRead.failuresThisIteration()),
                () -> assertEquals(0, beforeRead.successesThisIteration()),
                () -> assertEquals(1, read.successesThisIteration()),
                () -> assertEquals(Set.of("style.css"), harness.state().pathsReadThisTurn),
                () -> assertEquals(1, afterRead.successesThisIteration()),
                () -> assertEquals(1, afterRead.mutationsThisIteration()),
                () -> assertEquals(1, approvals.get()),
                () -> assertEquals("after", Files.readString(workspace.resolve("style.css"))));
    }

    @Test
    void repairFollowUpPreservesSourceReadBeforeDerivedWrites()
            throws Exception {
        Files.writeString(workspace.resolve("problem.md"), "Use Dijkstra's algorithm.\n");
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(
                        "Create dijkstra.py and test_dijkstra.py according to problem.md."),
                ChatMessage.assistant(
                        "Task incomplete; the requested files were not created."),
                ChatMessage.user("Fix it."));
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        TurnTaskContractCapture.set(resolution, messages);
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        StageHarness harness = harness(
                toolCall(
                        "talos.write_file",
                        "\"path\":\"dijkstra.py\",\"content\":\"premature\""),
                registry,
                approvals);

        ToolCallExecutionStage.IterationOutcome prematureFirst = execute(harness);
        ToolCallExecutionStage.IterationOutcome prematureSecond = executeTextCall(
                harness,
                toolCall(
                        "talos.write_file",
                        "\"path\":\"test_dijkstra.py\",\"content\":\"premature\""));
        ToolCallExecutionStage.IterationOutcome read = executeTextCall(
                harness,
                toolCall("talos.read_file", "\"path\":\"problem.md\""));
        ToolCallExecutionStage.IterationOutcome firstWrite = executeTextCall(
                harness,
                toolCall(
                        "talos.write_file",
                        "\"path\":\"dijkstra.py\",\"content\":\"def shortest_path(): pass\""));
        ToolCallExecutionStage.IterationOutcome secondWrite = executeTextCall(
                harness,
                toolCall(
                        "talos.write_file",
                        "\"path\":\"test_dijkstra.py\",\"content\":\"def test_shortest_path(): pass\""));

        assertAll(
                () -> assertEquals(
                        Set.of("problem.md"),
                        resolution.contract().sourceEvidenceTargets()),
                () -> assertEquals(1, prematureFirst.failuresThisIteration()),
                () -> assertEquals(0, prematureFirst.successesThisIteration()),
                () -> assertEquals(1, prematureSecond.failuresThisIteration()),
                () -> assertEquals(0, prematureSecond.successesThisIteration()),
                () -> assertEquals(1, read.successesThisIteration()),
                () -> assertEquals(Set.of("problem.md"), harness.state().pathsReadThisTurn),
                () -> assertEquals(1, firstWrite.mutationsThisIteration()),
                () -> assertEquals(1, secondWrite.mutationsThisIteration()),
                () -> assertEquals(2, approvals.get()),
                () -> assertFalse(Files.readString(workspace.resolve("dijkstra.py"))
                        .contains("premature")),
                () -> assertFalse(Files.readString(workspace.resolve("test_dijkstra.py"))
                        .contains("premature")),
                () -> assertTrue(Files.exists(workspace.resolve("dijkstra.py"))),
                () -> assertTrue(Files.exists(workspace.resolve("test_dijkstra.py"))));
    }

    @Test
    void repairFollowUpPreservesSamePathCompositeReadBeforeWrite()
            throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(
                        "Edit index.html so read style.css, then update style.css."),
                ChatMessage.assistant(
                        "Task incomplete; style.css was not updated."),
                ChatMessage.user("Fix it."));
        var resolution = TaskContractResolver.resolveFromMessages(messages);
        TurnTaskContractCapture.set(resolution, messages);
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        StageHarness harness = harness(
                toolCall(
                        "talos.write_file",
                        "\"path\":\"style.css\",\"content\":\"premature\""),
                registry,
                approvals);

        ToolCallExecutionStage.IterationOutcome premature = execute(harness);
        ToolCallExecutionStage.IterationOutcome read = executeTextCall(
                harness,
                toolCall("talos.read_file", "\"path\":\"style.css\""));
        ToolCallExecutionStage.IterationOutcome afterRead = executeTextCall(
                harness,
                toolCall(
                        "talos.write_file",
                        "\"path\":\"style.css\",\"content\":\"after\""));

        assertAll(
                () -> assertEquals(
                        Set.of("index.html", "style.css"),
                        resolution.contract().expectedTargets()),
                () -> assertEquals(
                        Set.of("style.css"),
                        resolution.contract().sourceEvidenceTargets()),
                () -> assertEquals(
                        "MUST_MUTATE_AFTER_SOURCE_EVIDENCE",
                        resolution.intent()
                                .targets()
                                .find("style.css")
                                .orElseThrow()
                                .role()
                                .name()),
                () -> assertEquals(1, premature.failuresThisIteration()),
                () -> assertEquals(0, premature.successesThisIteration()),
                () -> assertEquals(1, read.successesThisIteration()),
                () -> assertEquals(Set.of("style.css"), harness.state().pathsReadThisTurn),
                () -> assertEquals(1, afterRead.successesThisIteration()),
                () -> assertEquals(1, afterRead.mutationsThisIteration()),
                () -> assertEquals(1, approvals.get()),
                () -> assertEquals("after", Files.readString(workspace.resolve("style.css"))));
    }

    private static TaskContract overlappingContract(String expected, String source) {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of(expected),
                Set.of(source),
                Set.of(),
                "Read assets/style.css, then update assets/style.css.",
                "t1201-exact-red");
    }

    private StageHarness harness(
            String currentText,
            ToolRegistry registry,
            AtomicInteger approvals
    ) {
        Config config = new Config(null);
        Context context = Context.builder(config)
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .build();
        LoopState state = new LoopState(
                currentText,
                List.of(),
                new ArrayList<>(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Read style.css, then update style.css."))),
                workspace,
                context,
                new Session(workspace, config),
                5,
                0);
        ApprovalGate approvalGate = new ApprovalGate() {
            @Override
            public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }

            @Override
            public ApprovalResponse approveFull(String description, String detail) {
                approvals.incrementAndGet();
                return ApprovalResponse.APPROVED;
            }
        };
        return new StageHarness(
                new ToolCallExecutionStage(
                        new TurnProcessor(null, approvalGate, registry),
                        null,
                        false),
                state);
    }

    private static ToolCallExecutionStage.IterationOutcome execute(StageHarness harness) {
        harness.state().setRuntimeNativeCalls(
                harness.state().currentText,
                harness.state().currentNativeCalls,
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.DIRECT_RUNTIME_API);
        ToolCallParseStage.ParsedCalls parsed =
                new ToolCallParseStage().parse(harness.state(), 1);
        assertFalse(parsed.calls().isEmpty());
        return harness.stage().execute(harness.state(), parsed);
    }

    private static ToolCallExecutionStage.IterationOutcome executeTextCall(
            StageHarness harness,
            String text
    ) {
        harness.state().currentText = text;
        harness.state().currentNativeCalls = List.of();
        return execute(harness);
    }

    private static String toolCall(String toolName, String argumentsJson) {
        return "```json\n"
                + "{\"name\":\"" + toolName + "\",\"arguments\":{" + argumentsJson + "}}\n"
                + "```";
    }

    private record StageHarness(ToolCallExecutionStage stage, LoopState state) {}
}
