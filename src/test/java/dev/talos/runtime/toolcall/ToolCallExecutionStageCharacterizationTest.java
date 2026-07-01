package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.capability.CapabilityKind;
import dev.talos.core.context.ContextDecision;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.Session;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallExecutionStageCharacterizationTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        ContextLedgerCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void textPathAppendsAssistantAndUserToolResultMessages() {
        ToolRegistry registry = registry(new StubTool(
                "talos.echo",
                ToolRiskLevel.READ_ONLY,
                call -> ToolResult.ok("echo: " + call.param("input"))));
        StageHarness harness = harness(
                textToolCall("talos.echo", "\"input\":\"text\""),
                List.of(),
                registry,
                new NoOpApprovalGate(),
                new Config(null));

        int before = harness.state().messages.size();
        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        ChatMessage assistant = harness.state().messages.get(before);
        ChatMessage result = harness.state().messages.get(before + 1);
        assertAll(
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.failuresThisIteration()),
                () -> assertEquals(1, harness.state().totalToolsInvoked),
                () -> assertEquals("assistant", assistant.role()),
                () -> assertFalse(assistant.hasNativeToolCalls()),
                () -> assertEquals("user", result.role()),
                () -> assertTrue(result.content().contains("[tool_result: talos.echo]"), result.content()),
                () -> assertTrue(result.content().contains("echo: text"), result.content()));
    }

    @Test
    void nativePathAppendsAssistantToolCallAndToolRoleResultMessage() {
        ToolRegistry registry = registry(new StubTool(
                "talos.echo",
                ToolRiskLevel.READ_ONLY,
                call -> ToolResult.ok("echo: " + call.param("input"))));
        List<NativeToolCall> nativeCalls = List.of(new NativeToolCall(
                "call-t826-native",
                "talos.echo",
                Map.of("input", "native")));
        StageHarness harness = harness(
                "",
                nativeCalls,
                registry,
                new NoOpApprovalGate(),
                new Config(null));

        int before = harness.state().messages.size();
        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        ChatMessage assistant = harness.state().messages.get(before);
        ChatMessage result = harness.state().messages.get(before + 1);
        assertAll(
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertEquals("assistant", assistant.role()),
                () -> assertTrue(assistant.hasNativeToolCalls()),
                () -> assertEquals("call-t826-native", assistant.toolCalls().get(0).id()),
                () -> assertEquals("tool", result.role()),
                () -> assertEquals("call-t826-native", result.toolCallId()),
                () -> assertTrue(result.content().contains("[tool_result: talos.echo]"), result.content()),
                () -> assertTrue(result.content().contains("echo: native"), result.content()));
    }

    @Test
    void deniedMutatingToolSetsDenialFlagsAndDeniedOutcomeWithoutExecutingTool() {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = registry(new StubTool(
                "talos.write_file",
                ToolRiskLevel.WRITE,
                call -> {
                    executions.incrementAndGet();
                    return ToolResult.ok("write should not run");
                }));
        StageHarness harness = harness(
                textToolCall("talos.write_file", "\"path\":\"README.md\",\"content\":\"changed\""),
                List.of(),
                registry,
                fixedApprovalGate(ApprovalResponse.DENIED),
                new Config(null));

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        ToolCallLoop.ToolOutcome toolOutcome = harness.state().toolOutcomes.get(0);
        ChatMessage result = harness.state().messages.get(harness.state().messages.size() - 1);
        assertAll(
                () -> assertEquals(0, executions.get()),
                () -> assertTrue(outcome.approvalDeniedThisIteration()),
                () -> assertTrue(outcome.mutatingDeniedThisIteration()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals("talos.write_file", toolOutcome.toolName()),
                () -> assertTrue(toolOutcome.mutating()),
                () -> assertTrue(toolOutcome.denied()),
                () -> assertFalse(toolOutcome.success()),
                () -> assertTrue(result.content().contains("User did not approve"), result.content()));
    }

    @Test
    void privateDocumentNamedTargetBlockFiresBeforeReadExecutionAndRecordsTrace() throws Exception {
        Files.writeString(workspace.resolve("private-report.pdf"), "Named PDF fact");
        Files.writeString(workspace.resolve("private-report.docx"), "Sibling DOCX fact");
        AtomicInteger approvals = new AtomicInteger();
        Config cfg = privateModeConfig();
        ToolRegistry registry = registry(new ReadFileTool());
        StageHarness harness = harness(
                textToolCall("talos.read_file", "\"path\":\"private-report.docx\""),
                List.of(),
                registry,
                approvalGate(approvals, ApprovalResponse.APPROVED),
                cfg);
        TurnTaskContractCapture.set(readOnlyContract(
                "Summarize private-report.pdf.",
                Set.of("private-report.pdf"),
                Set.of()));
        beginTrace("Summarize private-report.pdf.");

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        ToolCallLoop.ToolOutcome toolOutcome = harness.state().toolOutcomes.get(0);
        ChatMessage result = harness.state().messages.get(harness.state().messages.size() - 1);
        assertAll(
                () -> assertEquals(0, approvals.get(), "block must happen before private handoff approval"),
                () -> assertTrue(outcome.pathPolicyBlockedThisIteration()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(1, harness.state().totalToolsInvoked,
                        "the stage counts the call before the pre-execution guard blocks it"),
                () -> assertEquals("talos.read_file", toolOutcome.toolName()),
                () -> assertFalse(toolOutcome.success()),
                () -> assertEquals(ToolError.INVALID_PARAMS, toolOutcome.errorCode()),
                () -> assertTrue(toolOutcome.errorMessage().contains(
                        "outside the current requested private document target set"), toolOutcome.errorMessage()),
                () -> assertTrue(result.content().contains(
                        "outside the current requested private document target set"), result.content()),
                () -> assertFalse(result.content().contains("Sibling DOCX fact"), result.content()),
                () -> assertTrue(harness.state().readFileBodiesThisTurn.isEmpty()),
                () -> assertTrue(trace.events().stream().anyMatch(event ->
                        "TOOL_CALL_BLOCKED".equals(event.type())
                                && "talos.read_file".equals(event.toolName())
                                && String.valueOf(event.data().get("reason"))
                                .contains("outside the current requested private document target set")),
                        trace.events().toString()));
    }

    @Test
    void successfulExecutionRecordsContextLedgerDecisionResultMessageAndExecutedOutcome() {
        ToolRegistry registry = registry(new StubTool(
                "talos.echo",
                ToolRiskLevel.READ_ONLY,
                call -> ToolResult.ok(
                        "ledger: " + call.param("input"),
                        ToolContentMetadata.normal())));
        StageHarness harness = harness(
                textToolCall("talos.echo", "\"input\":\"included\""),
                List.of(),
                registry,
                new NoOpApprovalGate(),
                new Config(null));
        beginTrace("Use the echo tool.");

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);
        var ledger = ContextLedgerCapture.snapshot();

        ToolCallLoop.ToolOutcome toolOutcome = harness.state().toolOutcomes.get(0);
        ChatMessage result = harness.state().messages.get(harness.state().messages.size() - 1);
        assertAll(
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.failuresThisIteration()),
                () -> assertEquals(1, ledger.summary().totalItems()),
                () -> assertEquals(1, ledger.summary().byDecision()
                        .get(ContextDecision.Action.INCLUDED_IN_MODEL_PROMPT.name())),
                () -> assertEquals("talos.echo", toolOutcome.toolName()),
                () -> assertTrue(toolOutcome.success()),
                () -> assertTrue(toolOutcome.summary().contains("ledger: included"), toolOutcome.summary()),
                () -> assertTrue(result.content().contains("ledger: included"), result.content()));
    }

    @Test
    void failedEditExecutionRecordsFailureAndEditRepairAccountingWithoutSuccess() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "missing\n");
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = registry(new StubTool(
                "talos.edit_file",
                ToolRiskLevel.WRITE,
                call -> {
                    executions.incrementAndGet();
                    return ToolResult.fail(ToolError.invalidParams(
                            ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                            "old_string not found"));
                }));
        StageHarness harness = harness(
                textToolCall(
                        "talos.edit_file",
                        "\"path\":\"README.md\",\"old_string\":\"missing\",\"new_string\":\"replacement\""),
                List.of(),
                registry,
                fixedApprovalGate(ApprovalResponse.APPROVED),
                new Config(null));

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        ToolCallLoop.ToolOutcome toolOutcome = harness.state().toolOutcomes.get(0);
        ChatMessage result = harness.state().messages.get(harness.state().messages.size() - 1);
        assertAll(
                () -> assertEquals(1, executions.get()),
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.mutationsThisIteration()),
                () -> assertEquals(1, harness.state().editFailuresByPath.get("README.md")),
                () -> assertFalse(harness.state().failedCallSignatures.isEmpty()),
                () -> assertEquals("talos.edit_file", toolOutcome.toolName()),
                () -> assertTrue(toolOutcome.mutating()),
                () -> assertFalse(toolOutcome.success()),
                () -> assertEquals(ToolError.INVALID_PARAMS, toolOutcome.errorCode()),
                () -> assertTrue(result.content().contains("old_string not found"), result.content()));
    }

    @Test
    void reportPinsT828MoveStayBoundary() throws Exception {
        String report = Files.readString(Path.of(
                "work-cycle-docs/reports/t826-tool-call-execution-stage-characterization.md"));

        assertAll(
                () -> assertTrue(report.contains("ToolCallExecutionStage.execute"), report),
                () -> assertTrue(report.contains("public IterationOutcome execute"), report),
                () -> assertTrue(report.contains("public record IterationOutcome"), report),
                () -> assertTrue(report.contains("T826 does not authorize production extraction"), report),
                () -> assertTrue(report.contains("T828 Move/Stay Boundary"), report),
                () -> assertTrue(report.contains("Do not move yet"), report));
    }

    private ToolCallExecutionStage.IterationOutcome execute(StageHarness harness) {
        ToolCallParseStage.ParsedCalls parsed = new ToolCallParseStage().parse(
                harness.state().currentText,
                harness.state().currentNativeCalls,
                1);
        assertFalse(parsed.calls().isEmpty(), "test fixture must produce parsed tool calls");
        return harness.stage().execute(harness.state(), parsed);
    }

    private StageHarness harness(
            String currentText,
            List<NativeToolCall> nativeCalls,
            ToolRegistry registry,
            ApprovalGate approvalGate,
            Config cfg
    ) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Run the tool.")));
        Context ctx = Context.builder(cfg)
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .build();
        Session session = new Session(workspace, cfg);
        TurnProcessor processor = new TurnProcessor(null, approvalGate, registry);
        LoopState state = new LoopState(
                currentText,
                nativeCalls,
                messages,
                workspace,
                ctx,
                session,
                5,
                0);
        return new StageHarness(new ToolCallExecutionStage(processor, null, false), state);
    }

    private static String textToolCall(String toolName, String argumentsJson) {
        return "```json\n"
                + "{\"name\":\"" + toolName + "\",\"arguments\":{" + argumentsJson + "}}\n"
                + "```";
    }

    private static ToolRegistry registry(TalosTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (TalosTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static Config privateModeConfig() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", Map.of("mode", "private"));
        return cfg;
    }

    private static TaskContract readOnlyContract(String request, Set<String> expectedTargets, Set<String> sourceTargets) {
        return new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                expectedTargets,
                sourceTargets,
                Set.of(),
                request,
                "t826-test-contract");
    }

    private static ApprovalGate fixedApprovalGate(ApprovalResponse response) {
        return approvalGate(new AtomicInteger(), response);
    }

    private static ApprovalGate approvalGate(AtomicInteger approvals, ApprovalResponse response) {
        return new ApprovalGate() {
            @Override
            public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }

            @Override
            public ApprovalResponse approveFull(String description, String detail) {
                approvals.incrementAndGet();
                return response;
            }
        };
    }

    private static void beginTrace(String request) {
        LocalTurnTraceCapture.begin(
                "trc-t826-tool-call-execution-stage",
                "sid-t826",
                1,
                "2026-06-16T00:00:00Z",
                "workspace-hash",
                "test",
                "scripted",
                "model",
                request);
    }

    private record StageHarness(ToolCallExecutionStage stage, LoopState state) {}

    private record StubTool(
            String name,
            ToolRiskLevel riskLevel,
            Function<ToolCall, ToolResult> handler
    ) implements TalosTool {
        @Override
        public String description() {
            return "T826 stub " + name;
        }

        @Override
        public ToolDescriptor descriptor() {
            return new ToolDescriptor(name, description(), "{}", riskLevel, metadata());
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext ctx) {
            return handler.apply(call);
        }

        private ToolOperationMetadata metadata() {
            if (riskLevel == ToolRiskLevel.READ_ONLY) {
                return ToolOperationMetadata.defaultFor(name, riskLevel);
            }
            return new ToolOperationMetadata(
                    name,
                    CapabilityKind.EDIT,
                    riskLevel,
                    Map.of("path", ToolOperationMetadata.PathRole.TARGET_FILE),
                    true,
                    false,
                    true,
                    false,
                    false,
                    false,
                    "TOOL_EXECUTED",
                    "");
        }
    }
}
