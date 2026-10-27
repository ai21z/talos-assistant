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
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.intent.TaskContractCompiler;
import dev.talos.runtime.intent.TaskIntentResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallExecutionStageCharacterizationTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearCaptures() {
        LocalTurnTraceCapture.clear();
        ContextLedgerCapture.clear();
        TurnSourceEvidenceCapture.clear();
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
    void progressCancellationStopsBeforeToolExecution() {
        AtomicInteger invocations = new AtomicInteger();
        ToolRegistry registry = registry(new StubTool(
                "talos.echo",
                ToolRiskLevel.READ_ONLY,
                call -> {
                    invocations.incrementAndGet();
                    return ToolResult.ok("must not execute");
                }));
        CancellationException cancellation = new CancellationException("cancel progress rendering");
        StageHarness harness = harness(
                textToolCall("talos.echo", "\"input\":\"text\""),
                List.of(),
                registry,
                new NoOpApprovalGate(),
                new Config(null),
                (toolName, action, detail) -> { throw cancellation; });

        assertSame(cancellation, assertThrows(CancellationException.class, () -> execute(harness)));
        assertEquals(0, invocations.get());
        assertEquals(0, harness.state().totalToolsInvoked);
    }

    @Test
    void progressInterruptionStopsBeforeToolExecutionAndRestoresStatus() {
        AtomicInteger invocations = new AtomicInteger();
        ToolRegistry registry = registry(new StubTool(
                "talos.echo",
                ToolRiskLevel.READ_ONLY,
                call -> {
                    invocations.incrementAndGet();
                    return ToolResult.ok("must not execute");
                }));
        RuntimeException interruption = new RuntimeException(
                new InterruptedException("interrupt progress rendering"));
        StageHarness harness = harness(
                textToolCall("talos.echo", "\"input\":\"text\""),
                List.of(),
                registry,
                new NoOpApprovalGate(),
                new Config(null),
                (toolName, action, detail) -> { throw interruption; });

        try {
            assertSame(interruption, assertThrows(RuntimeException.class, () -> execute(harness)));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, invocations.get());
            assertEquals(0, harness.state().totalToolsInvoked);
        } finally {
            Thread.interrupted();
        }
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
        List<String> toolEvents = trace.events().stream()
                .filter(event -> "talos.read_file".equals(event.toolName()))
                .map(event -> event.type())
                .filter(type -> "TOOL_CALL_PARSED".equals(type) || "TOOL_CALL_BLOCKED".equals(type))
                .toList();

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
                () -> assertTrue(harness.state().readEvidence.latestReadFileBodiesByNormalizedPath().isEmpty()),
                () -> assertEquals(List.of("TOOL_CALL_PARSED", "TOOL_CALL_BLOCKED"), toolEvents,
                        "a pre-execution block must settle one previously parsed call"),
                () -> assertTrue(trace.events().stream().anyMatch(event ->
                        "TOOL_CALL_BLOCKED".equals(event.type())
                                && "talos.read_file".equals(event.toolName())
                                && String.valueOf(event.data().get("reason"))
                                .contains("outside the current requested private document target set")),
                        trace.events().toString()));
    }

    @Test
    void requiredPostReadMutationWithoutReadStopsBeforeApprovalAndExecution() throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        StageHarness harness = harness(
                textToolCall("talos.write_file", "\"path\":\"style.css\",\"content\":\"after\""),
                List.of(),
                registry(new ReadFileTool(), countingFileWriteTool(writes)),
                approvalGate(approvals, ApprovalResponse.APPROVED),
                new Config(null));
        TurnTaskContractCapture.set(requiredPostReadMutationContract(true));

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        assertAll(
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(0, approvals.get()),
                () -> assertEquals(0, writes.get()),
                () -> assertEquals("before", Files.readString(workspace.resolve("style.css"))),
                () -> assertTrue(harness.state().toolOutcomes.getLast().errorMessage()
                        .contains("requires reading"), harness.state().toolOutcomes.toString()));
    }

    @Test
    void failedRequiredReadDoesNotAuthorizeMutation() throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        TalosTool failingRead = new StubTool(
                "talos.read_file",
                ToolRiskLevel.READ_ONLY,
                call -> {
                    reads.incrementAndGet();
                    return ToolResult.fail(ToolError.invalidParams("forced read failure"));
                });
        StageHarness harness = harness(
                textToolCall("talos.read_file", "\"path\":\"style.css\""),
                List.of(),
                registry(failingRead, countingFileWriteTool(writes)),
                approvalGate(approvals, ApprovalResponse.APPROVED),
                new Config(null));
        TurnTaskContractCapture.set(requiredPostReadMutationContract(true));

        ToolCallExecutionStage.IterationOutcome readOutcome = execute(harness);
        ToolCallExecutionStage.IterationOutcome writeOutcome = executeTextCall(
                harness,
                textToolCall(
                        "talos.write_file",
                        "\"path\":\"style.css\",\"content\":\"after\""));

        assertAll(
                () -> assertEquals(1, reads.get()),
                () -> assertEquals(1, readOutcome.failuresThisIteration()),
                () -> assertEquals(0, readOutcome.successesThisIteration()),
                () -> assertEquals(1, writeOutcome.failuresThisIteration()),
                () -> assertEquals(0, approvals.get()),
                () -> assertEquals(0, writes.get()),
                () -> assertTrue(harness.state().pathsReadThisTurn.isEmpty()),
                () -> assertEquals("before", Files.readString(workspace.resolve("style.css"))));
    }

    @Test
    void successfulWrongPathReadDoesNotAuthorizeRequiredTargetMutation() throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        Files.writeString(workspace.resolve("other.css"), "other");
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        StageHarness harness = harness(
                textToolCall("talos.read_file", "\"path\":\"other.css\""),
                List.of(),
                registry(new ReadFileTool(), countingFileWriteTool(writes)),
                approvalGate(approvals, ApprovalResponse.APPROVED),
                new Config(null));
        TurnTaskContractCapture.set(requiredPostReadMutationContract(true));

        execute(harness);
        ToolCallExecutionStage.IterationOutcome writeOutcome = executeTextCall(
                harness,
                textToolCall(
                        "talos.write_file",
                        "\"path\":\"style.css\",\"content\":\"after\""));

        assertAll(
                () -> assertEquals(1, writeOutcome.failuresThisIteration()),
                () -> assertEquals(Set.of("other.css"), harness.state().pathsReadThisTurn),
                () -> assertEquals(0, approvals.get()),
                () -> assertEquals(0, writes.get()),
                () -> assertEquals("before", Files.readString(workspace.resolve("style.css"))));
    }

    @Test
    void successfulExactReadAdmitsOneOrdinaryApprovalAndMutation() throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger fullApprovals = new AtomicInteger();
        AtomicInteger onceApprovals = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        List<String> approvalPrompts = new ArrayList<>();
        StageHarness harness = harness(
                textToolCall("talos.read_file", "\"path\":\"style.css\""),
                List.of(),
                registry(new ReadFileTool(), countingFileWriteTool(writes)),
                approvalGate(
                        approvals,
                        fullApprovals,
                        onceApprovals,
                        approvalPrompts,
                        ApprovalResponse.APPROVED),
                new Config(null));
        TurnTaskContractCapture.set(requiredPostReadMutationContract(true));

        ToolCallExecutionStage.IterationOutcome readOutcome = execute(harness);
        ToolCallExecutionStage.IterationOutcome writeOutcome = executeTextCall(
                harness,
                textToolCall(
                        "talos.write_file",
                        "\"path\":\"style.css\",\"content\":\"after\""));

        assertAll(
                () -> assertEquals(1, readOutcome.successesThisIteration()),
                () -> assertEquals(Set.of("style.css"), harness.state().pathsReadThisTurn),
                () -> assertEquals(1, writeOutcome.successesThisIteration()),
                () -> assertEquals(1, writeOutcome.mutationsThisIteration()),
                () -> assertEquals(1, approvals.get()),
                () -> assertEquals(1, fullApprovals.get()),
                () -> assertEquals(0, onceApprovals.get()),
                () -> assertEquals(1, approvalPrompts.size()),
                () -> assertTrue(
                        approvalPrompts.getFirst().contains("style.css"),
                        approvalPrompts.toString()),
                () -> assertTrue(
                        approvalPrompts.getFirst().toLowerCase(java.util.Locale.ROOT)
                                .contains("write"),
                        approvalPrompts.toString()),
                () -> assertEquals(1, writes.get()),
                () -> assertEquals("after", Files.readString(workspace.resolve("style.css"))));
    }

    @Test
    void exactReadDoesNotAuthorizeOffTargetMutation() throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        StageHarness harness = harness(
                textToolCall("talos.read_file", "\"path\":\"style.css\""),
                List.of(),
                registry(new ReadFileTool(), countingFileWriteTool(writes)),
                approvalGate(approvals, ApprovalResponse.APPROVED),
                new Config(null));
        TurnTaskContractCapture.set(requiredPostReadMutationContract(true));

        execute(harness);
        ToolCallExecutionStage.IterationOutcome writeOutcome = executeTextCall(
                harness,
                textToolCall(
                        "talos.write_file",
                        "\"path\":\"other.css\",\"content\":\"outside\""));

        assertAll(
                () -> assertEquals(1, writeOutcome.failuresThisIteration()),
                () -> assertEquals(0, approvals.get()),
                () -> assertEquals(0, writes.get()),
                () -> assertFalse(Files.exists(workspace.resolve("other.css"))),
                () -> assertTrue(harness.state().toolOutcomes.getLast().errorMessage()
                        .contains("outside the current expected target set"),
                        harness.state().toolOutcomes.toString()));
    }

    @Test
    void exactReadCannotCreateMutationAuthorityForReadOnlyOverlap() throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        StageHarness harness = harness(
                textToolCall("talos.read_file", "\"path\":\"style.css\""),
                List.of(),
                registry(new ReadFileTool(), countingFileWriteTool(writes)),
                approvalGate(approvals, ApprovalResponse.APPROVED),
                new Config(null));
        TurnTaskContractCapture.set(requiredPostReadMutationContract(false));

        ToolCallExecutionStage.IterationOutcome readOutcome = execute(harness);
        ToolCallExecutionStage.IterationOutcome writeOutcome = executeTextCall(
                harness,
                textToolCall(
                        "talos.write_file",
                        "\"path\":\"style.css\",\"content\":\"after\""));

        assertAll(
                () -> assertEquals(1, readOutcome.successesThisIteration()),
                () -> assertEquals(0, readOutcome.failuresThisIteration()),
                () -> assertEquals(Set.of("style.css"), harness.state().pathsReadThisTurn),
                () -> assertEquals(1, writeOutcome.failuresThisIteration()),
                () -> assertEquals(0, approvals.get()),
                () -> assertEquals(0, writes.get()),
                () -> assertEquals("before", Files.readString(workspace.resolve("style.css"))));
    }

    @Test
    void directoryOnlyStaticContinuationPreservesCompositeReadBeforeWrite() throws Exception {
        Files.writeString(workspace.resolve("style.css"), "before");
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        StageHarness harness = harness(
                textToolCall("talos.write_file", "\"path\":\"style.css\",\"content\":\"after\""),
                List.of(),
                registry(new ReadFileTool(), countingFileWriteTool(writes)),
                approvalGate(approvals, ApprovalResponse.APPROVED),
                new Config(null));
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of("style.css"),
                Set.of(),
                "Improve the functional static website. Read style.css before updating "
                        + "index.html, style.css, and script.js.",
                "t1201-static-web-composite");
        TurnTaskContractCapture.set(contract);
        harness.state().toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.mkdir",
                "site",
                true,
                true,
                false,
                "Created directory site",
                ""));
        harness.state().mutatingToolSuccesses = 1;
        StaticWebContinuationPlanner.Plan continuation =
                StaticWebContinuationPlanner.directoryOnlyPlan(
                                harness.state(),
                                List.of(
                                        new ToolSpec("talos.read_file", "Read", "{}"),
                                        new ToolSpec("talos.write_file", "Write", "{}"),
                                        new ToolSpec("talos.edit_file", "Edit", "{}")))
                        .orElseThrow();
        harness.state().messages.clear();
        harness.state().messages.addAll(continuation.messages());

        ToolCallExecutionStage.IterationOutcome writeBeforeRead = execute(harness);
        int approvalsAfterBlockedWrite = approvals.get();
        ToolCallExecutionStage.IterationOutcome readOutcome = executeTextCall(
                harness,
                textToolCall("talos.read_file", "\"path\":\"style.css\""));
        ToolCallExecutionStage.IterationOutcome writeAfterRead = executeTextCall(
                harness,
                textToolCall(
                        "talos.write_file",
                        "\"path\":\"style.css\",\"content\":\"after\""));

        assertAll(
                () -> assertEquals(
                        List.of("talos.read_file", "talos.write_file"),
                        continuation.tools().stream().map(ToolSpec::name).toList()),
                () -> assertEquals(1, writeBeforeRead.failuresThisIteration()),
                () -> assertEquals(0, approvalsAfterBlockedWrite),
                () -> assertEquals(1, readOutcome.successesThisIteration()),
                () -> assertEquals(1, writeAfterRead.successesThisIteration()),
                () -> assertEquals(1, approvals.get()),
                () -> assertEquals(1, writes.get()),
                () -> assertEquals("after", Files.readString(workspace.resolve("style.css"))));
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
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        var parsedEvents = trace.events().stream()
                .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                .toList();
        assertEquals(1, parsedEvents.size(),
                "the loop and processor must not duplicate parsed-call evidence");
        var parsedEvent = parsedEvents.getFirst();

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
                () -> assertEquals("<unrecognized-tool>", parsedEvent.toolName()),
                () -> assertEquals("UNKNOWN",
                        parsedEvent.data().get("toolNameClassification")),
                () -> assertEquals(ProviderResponseCapture.toolNameHash("talos.echo"),
                        parsedEvent.data().get("toolNameHash")),
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
    void failedEditAliasRecordsEditRepairAccountingAndPreservesRawToolName() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "missing\n");
        ToolRegistry registry = registry(new StubTool(
                "talos.edit_file",
                ToolRiskLevel.WRITE,
                call -> ToolResult.fail(ToolError.invalidParams(
                        ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND,
                        "old_string not found"))));
        StageHarness harness = harness(
                textToolCall(
                        "file_utils:edit_file",
                        "\"path\":\"README.md\",\"old_string\":\"missing\",\"new_string\":\"replacement\""),
                List.of(),
                registry,
                fixedApprovalGate(ApprovalResponse.APPROVED),
                new Config(null));

        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        ToolCallLoop.ToolOutcome toolOutcome = harness.state().toolOutcomes.get(0);
        ChatMessage result = harness.state().messages.get(harness.state().messages.size() - 1);
        assertAll(
                () -> assertEquals(1, outcome.failuresThisIteration()),
                () -> assertEquals(0, outcome.successesThisIteration()),
                () -> assertEquals(1, harness.state().editFailuresByPath.get("README.md")),
                () -> assertFalse(harness.state().failedCallSignatures.isEmpty()),
                () -> assertEquals("file_utils:edit_file", toolOutcome.toolName()),
                () -> assertEquals("talos.edit_file", toolOutcome.canonicalToolName()),
                () -> assertTrue(toolOutcome.mutating()),
                () -> assertFalse(toolOutcome.success()),
                () -> assertTrue(result.content().contains("old_string not found"), result.content()));
    }

    private ToolCallExecutionStage.IterationOutcome execute(StageHarness harness) {
        if (!LocalTurnTraceCapture.isActive()) {
            beginTrace("Run the deterministic test tool.");
        }
        harness.state().setRuntimeNativeCalls(
                harness.state().currentText,
                harness.state().currentNativeCalls,
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.DIRECT_RUNTIME_API);
        ToolCallParseStage.ParsedCalls parsed =
                new ToolCallParseStage().parse(harness.state(), 1);
        assertFalse(parsed.calls().isEmpty(), "test fixture must produce parsed tool calls");
        return harness.stage().execute(harness.state(), parsed);
    }

    private ToolCallExecutionStage.IterationOutcome executeTextCall(
            StageHarness harness,
            String text
    ) {
        harness.state().currentText = text;
        harness.state().currentNativeCalls = List.of();
        return execute(harness);
    }

    private StageHarness harness(
            String currentText,
            List<NativeToolCall> nativeCalls,
            ToolRegistry registry,
            ApprovalGate approvalGate,
            Config cfg
    ) {
        return harness(currentText, nativeCalls, registry, approvalGate, cfg, null);
    }

    private StageHarness harness(
            String currentText,
            List<NativeToolCall> nativeCalls,
            ToolRegistry registry,
            ApprovalGate approvalGate,
            Config cfg,
            dev.talos.tools.ToolProgressSink progressSink
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
        return new StageHarness(new ToolCallExecutionStage(processor, progressSink, false), state);
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

    private static TaskContract requiredPostReadMutationContract(boolean mutationAllowed) {
        TaskContract legacy = new TaskContract(
                mutationAllowed ? TaskType.FILE_EDIT : TaskType.READ_ONLY_QA,
                mutationAllowed,
                mutationAllowed,
                mutationAllowed,
                Set.of("style.css"),
                Set.of("style.css"),
                Set.of(),
                "Read style.css, then update style.css.",
                "t1201-required-post-read-mutation");
        return TaskContractCompiler.compile(
                TaskIntentResolver.fromLegacyContract(legacy));
    }

    private static TalosTool countingFileWriteTool(AtomicInteger invocations) {
        FileWriteTool delegate = new FileWriteTool();
        return new TalosTool() {
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
            public ToolResult execute(ToolCall call, ToolContext ctx) {
                invocations.incrementAndGet();
                return delegate.execute(call, ctx);
            }
        };
    }

    private static ApprovalGate fixedApprovalGate(ApprovalResponse response) {
        return approvalGate(new AtomicInteger(), response);
    }

    private static ApprovalGate approvalGate(AtomicInteger approvals, ApprovalResponse response) {
        return approvalGate(
                approvals,
                new AtomicInteger(),
                new AtomicInteger(),
                new ArrayList<>(),
                response);
    }

    private static ApprovalGate approvalGate(
            AtomicInteger approvals,
            AtomicInteger fullApprovals,
            AtomicInteger onceApprovals,
            List<String> prompts,
            ApprovalResponse response
    ) {
        return new ApprovalGate() {
            @Override
            public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }

            @Override
            public ApprovalResponse approveFull(String description, String detail) {
                approvals.incrementAndGet();
                fullApprovals.incrementAndGet();
                prompts.add(description + "\n" + detail);
                return response;
            }

            @Override
            public ApprovalResponse approveOnce(String description, String detail) {
                approvals.incrementAndGet();
                onceApprovals.incrementAndGet();
                prompts.add(description + "\n" + detail);
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
