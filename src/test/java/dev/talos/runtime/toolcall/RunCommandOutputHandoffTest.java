package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ContextDecision;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.command.RunCommandTool;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandOutputHandoffTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearCaptures() {
        ContextLedgerCapture.clear();
    }

    @Test
    void commandOutputThatRequiredRedactionIsWithheldFromModelContext() throws Exception {
        String token = "github_pat_11AAAAAAA0abcdefghijklmnopqrstuvwxyz1234567890";
        StageHarness harness = harness(new CommandResult(
                null,
                0,
                42,
                false,
                false,
                "token=[redacted]",
                "",
                false,
                false,
                true,
                ""));

        ContextLedgerCapture.begin("trc-t837-command-output", 1);
        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        ChatMessage resultMessage = harness.state().messages.get(harness.state().messages.size() - 1);
        var ledger = ContextLedgerCapture.snapshot();
        assertAll(
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertEquals(0, outcome.failuresThisIteration()),
                () -> assertTrue(harness.state().contentWithheldFromModelContext),
                () -> assertEquals(1, ledger.summary().byDecision()
                        .get(ContextDecision.Action.WITHHELD_FROM_MODEL.name())),
                () -> assertTrue(resultMessage.content().contains("[tool_result: talos.run_command]"),
                        resultMessage.content()),
                () -> assertTrue(resultMessage.content().contains("Command succeeded: gradle_test exited with code 0"),
                        resultMessage.content()),
                () -> assertTrue(resultMessage.content().contains("withheld from model context"),
                        resultMessage.content()),
                () -> assertFalse(resultMessage.content().contains("stdout:"), resultMessage.content()),
                () -> assertFalse(resultMessage.content().contains("token=[redacted]"), resultMessage.content()),
                () -> assertFalse(resultMessage.content().contains(token), resultMessage.content()));
    }

    @Test
    void nonSensitiveCommandOutputRemainsVisibleToModelContext() throws Exception {
        StageHarness harness = harness(new CommandResult(
                null,
                0,
                42,
                false,
                false,
                "BUILD SUCCESSFUL",
                "",
                false,
                false,
                false,
                ""));

        ContextLedgerCapture.begin("trc-t837-command-output", 1);
        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        ChatMessage resultMessage = harness.state().messages.get(harness.state().messages.size() - 1);
        var ledger = ContextLedgerCapture.snapshot();
        assertAll(
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertFalse(harness.state().contentWithheldFromModelContext),
                () -> assertEquals(1, ledger.summary().byDecision()
                        .get(ContextDecision.Action.INCLUDED_IN_MODEL_PROMPT.name())),
                () -> assertTrue(resultMessage.content().contains("[tool_result: talos.run_command]"),
                        resultMessage.content()),
                () -> assertTrue(resultMessage.content().contains("stdout:\nBUILD SUCCESSFUL"),
                        resultMessage.content()),
                () -> assertTrue(resultMessage.content().contains("redactionApplied: false"),
                        resultMessage.content()));
    }

    @Test
    void highEntropyCommandOutputIsWithheldFromModelContext() throws Exception {
        String highEntropy = "N7k9Qp2vLm8Xr4Ts6Wd0Yh3Za5Bc1Ef7Gj9Kl2Mn";
        StageHarness harness = harness(new CommandResult(
                null,
                0,
                42,
                false,
                false,
                "generated token " + highEntropy,
                "",
                false,
                false,
                false,
                ""));

        ContextLedgerCapture.begin("trc-t837-command-output", 1);
        ToolCallExecutionStage.IterationOutcome outcome = execute(harness);

        ChatMessage resultMessage = harness.state().messages.get(harness.state().messages.size() - 1);
        assertAll(
                () -> assertEquals(1, outcome.successesThisIteration()),
                () -> assertTrue(harness.state().contentWithheldFromModelContext),
                () -> assertTrue(resultMessage.content().contains("withheld from model context"),
                        resultMessage.content()),
                () -> assertFalse(resultMessage.content().contains(highEntropy), resultMessage.content()),
                () -> assertFalse(resultMessage.content().contains("stdout:"), resultMessage.content()));
    }


    private ToolCallExecutionStage.IterationOutcome execute(StageHarness harness) {
        ToolCallParseStage.ParsedCalls parsed = new ToolCallParseStage().parse(
                harness.state().currentText,
                harness.state().currentNativeCalls,
                1);
        assertFalse(parsed.calls().isEmpty(), "test fixture must produce parsed command calls");
        return harness.stage().execute(harness.state(), parsed);
    }

    private StageHarness harness(CommandResult result) throws Exception {
        Files.writeString(workspace.resolve("gradlew.bat"), "@echo off\r\n");
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RunCommandTool(plan -> new CommandResult(
                plan,
                result.exitCode(),
                result.durationMs(),
                result.timedOut(),
                result.killed(),
                result.stdout(),
                result.stderr(),
                result.stdoutTruncated(),
                result.stderrTruncated(),
                result.redactionApplied(),
                result.errorMessage())));
        ApprovalGate gate = new ApprovalGate() {
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
        Config cfg = new Config(null);
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Run the command.")));
        Context ctx = Context.builder(cfg)
                .sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry)
                .build();
        LoopState state = new LoopState(
                """
                ```json
                {"name":"talos.run_command","arguments":{"profile":"gradle_test"}}
                ```
                """,
                List.of(),
                messages,
                workspace,
                ctx,
                new Session(workspace, cfg),
                5,
                0);
        return new StageHarness(new ToolCallExecutionStage(new TurnProcessor(null, gate, registry), null, false), state);
    }

    private record StageHarness(ToolCallExecutionStage stage, LoopState state) {}
}
