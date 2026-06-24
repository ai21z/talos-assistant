package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.command.CommandRunner;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.runtime.command.RunCommandTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TurnProcessorCommandPolicyTest {

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void approvedGradleCommandAsksOnceThenRuns(@TempDir Path workspace) throws Exception {
        createGradleWrapper(workspace);
        AtomicInteger approvals = new AtomicInteger();
        RecordingRunner runner = new RecordingRunner();
        TurnProcessor processor = processor(workspace, approvals, ApprovalResponse.APPROVED, runner);

        ToolResult result = processor.executeTool(
                new Session(workspace, new Config()),
                new ToolCall("talos.run_command", Map.of(
                        "profile", "gradle_test",
                        "args_json", "[\"--tests\",\"dev.talos.runtime.CommandTest\"]")),
                context(workspace, ExecutionPhase.VERIFY));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get(), "command execution must ask in V1");
        assertEquals(1, runner.calls.get());
        assertEquals("gradle_test", runner.lastPlan.get().profileId());
    }

    @Test
    void deniedApprovalPreventsProcessExecution(@TempDir Path workspace) throws Exception {
        createGradleWrapper(workspace);
        AtomicInteger approvals = new AtomicInteger();
        RecordingRunner runner = new RecordingRunner();
        TurnProcessor processor = processor(workspace, approvals, ApprovalResponse.DENIED, runner);

        ToolResult result = processor.executeTool(
                new Session(workspace, new Config()),
                new ToolCall("talos.run_command", Map.of("profile", "gradle_test")),
                context(workspace, ExecutionPhase.VERIFY));

        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
        assertEquals(1, approvals.get());
        assertEquals(0, runner.calls.get(), "denied approval must not run a process");
    }

    @Test
    void rawShellAttemptIsDeniedBeforeApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        RecordingRunner runner = new RecordingRunner();
        TurnProcessor processor = processor(workspace, approvals, ApprovalResponse.APPROVED, runner);

        ToolResult result = processor.executeTool(
                new Session(workspace, new Config()),
                new ToolCall("talos.run_command", Map.of("command", "powershell -Command Get-ChildItem")),
                context(workspace, ExecutionPhase.VERIFY));

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("Raw shell commands are not supported"));
        assertTrue(result.errorMessage().contains("No approval was requested"));
        assertEquals(0, approvals.get());
        assertEquals(0, runner.calls.get());
    }

    @Test
    void cwdEscapeIsDeniedBeforeApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        RecordingRunner runner = new RecordingRunner();
        TurnProcessor processor = processor(workspace, approvals, ApprovalResponse.APPROVED, runner);

        ToolResult result = processor.executeTool(
                new Session(workspace, new Config()),
                new ToolCall("talos.run_command", Map.of(
                        "profile", "gradle_test",
                        "cwd", "..")),
                context(workspace, ExecutionPhase.VERIFY));

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("cwd escapes workspace"));
        assertEquals(0, approvals.get());
        assertEquals(0, runner.calls.get());
    }

    @Test
    void rememberApprovalDoesNotSkipNextCommandApproval(@TempDir Path workspace) throws Exception {
        createGradleWrapper(workspace);
        AtomicInteger approvals = new AtomicInteger();
        RecordingRunner runner = new RecordingRunner();
        TurnProcessor processor = processor(workspace, approvals, ApprovalResponse.APPROVED_REMEMBER, runner);
        Session session = new Session(workspace, new Config());
        Context ctx = context(workspace, ExecutionPhase.VERIFY);

        ToolResult first = processor.executeTool(
                session,
                new ToolCall("talos.run_command", Map.of("profile", "gradle_test")),
                ctx);
        ToolResult second = processor.executeTool(
                session,
                new ToolCall("talos.run_command", Map.of("profile", "gradle_test")),
                ctx);

        assertTrue(first.success(), first.errorMessage());
        assertTrue(second.success(), second.errorMessage());
        assertEquals(2, approvals.get(), "V1 command approvals must not be session-remembered");
        assertEquals(2, runner.calls.get());
    }

    @Test
    void inspectPhaseBlocksCommandBeforeApproval(@TempDir Path workspace) {
        AtomicInteger approvals = new AtomicInteger();
        RecordingRunner runner = new RecordingRunner();
        TurnProcessor processor = processor(workspace, approvals, ApprovalResponse.APPROVED, runner);

        ToolResult result = processor.executeTool(
                new Session(workspace, new Config()),
                new ToolCall("talos.run_command", Map.of("profile", "gradle_test")),
                context(workspace, ExecutionPhase.INSPECT));

        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
        assertTrue(result.errorMessage().contains("Phase policy blocked talos.run_command during INSPECT"));
        assertEquals(0, approvals.get());
        assertEquals(0, runner.calls.get());
    }

    private static TurnProcessor processor(
            Path workspace,
            AtomicInteger approvals,
            ApprovalResponse response,
            CommandRunner runner
    ) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new RunCommandTool(runner));
        ApprovalGate gate = new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                approvals.incrementAndGet();
                assertTrue(description.contains("talos.run_command"));
                assertTrue(detail.contains("profile: gradle_test"));
                assertTrue(detail.contains("argv: " + gradleWrapperExecutable() + " --no-daemon test"));
                return response;
            }
        };
        return new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry,
                new SessionApprovalPolicy());
    }

    private static Context context(Path workspace, ExecutionPhase phase) {
        return Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .executionPhaseState(new ExecutionPhaseState(phase))
                .build();
    }

    private static void createGradleWrapper(Path workspace) throws Exception {
        String executable = gradleWrapperExecutable();
        String fileName = executable.endsWith("gradlew.bat") ? "gradlew.bat" : "gradlew";
        String content = executable.endsWith("gradlew.bat") ? "@echo off\r\n" : "#!/bin/sh\n";
        Files.writeString(workspace.resolve(fileName), content);
    }

    private static String gradleWrapperExecutable() {
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        return osName.contains("win") ? ".\\gradlew.bat" : "./gradlew";
    }

    private static final class RecordingRunner implements CommandRunner {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<CommandPlan> lastPlan = new AtomicReference<>();

        @Override
        public CommandResult run(CommandPlan plan) {
            calls.incrementAndGet();
            lastPlan.set(plan);
            return new CommandResult(plan, 0, 12, false, false, "ok", "", false, false, false, "");
        }
    }
}
