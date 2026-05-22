package dev.talos.runtime.command;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolOperationMetadata;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RunCommandToolTest {

    @TempDir
    Path workspace;

    @Test
    void descriptorDeclaresApprovedCommandExecutionNotWorkspaceMutation() {
        RunCommandTool tool = new RunCommandTool(plan -> success(plan, "ok", ""));

        assertEquals("talos.run_command", tool.name());
        assertEquals(ToolRiskLevel.WRITE, tool.descriptor().riskLevel(),
                "command execution must ask in V1");
        ToolOperationMetadata metadata = tool.descriptor().operationMetadata();
        assertEquals(ToolRiskLevel.WRITE, metadata.riskLevel());
        assertTrue(metadata.requiresApproval());
        assertFalse(metadata.mutatesWorkspace(),
                "Gradle verification commands may write generated output but must not be treated as source mutation");
        assertFalse(metadata.requiresCheckpoint());
    }

    @Test
    void gradleCommandRunsThroughValidatedPlan() throws Exception {
        createGradleWrapper();
        AtomicReference<CommandPlan> captured = new AtomicReference<>();
        RunCommandTool tool = new RunCommandTool(plan -> {
            captured.set(plan);
            return success(plan, "BUILD SUCCESSFUL", "");
        });

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_test",
                "args_json", "[\"--tests\",\"dev.talos.runtime.CommandTest\"]",
                "cwd", ".")), context());

        assertTrue(result.success(), result.errorMessage());
        assertEquals("gradle_test", captured.get().profileId());
        assertEquals(
                java.util.List.of("--no-daemon", "test", "--tests", "dev.talos.runtime.CommandTest"),
                captured.get().argv());
        assertTrue(result.output().contains("Command succeeded: gradle_test exited with code 0"));
        assertTrue(result.output().contains("BUILD SUCCESSFUL"));
    }

    @Test
    void gradleProfileWithoutWrapperIsRejectedBeforeRunner() {
        RunCommandTool tool = new RunCommandTool(plan -> fail("runner must not execute without a Gradle wrapper"));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_check")), context());

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("Gradle command profiles require a Gradle wrapper"),
                result.errorMessage());
        assertTrue(result.errorMessage().contains("No approval was requested and no command was executed"),
                result.errorMessage());
    }

    @Test
    void nonGradleProfilesAreUnavailableInT138() {
        RunCommandTool tool = new RunCommandTool(plan -> fail("runner must not execute non-gradle profile"));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "git_status")), context());

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("not available for talos.run_command V1"));
    }

    @Test
    void rawShellShapeIsRejected() {
        RunCommandTool tool = new RunCommandTool(plan -> fail("runner must not execute raw shell"));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "command", "cmd.exe /c gradlew.bat test")), context());

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("Raw shell commands are not supported"));
    }

    @Test
    void invalidArgsAreRejectedBeforeRunner() {
        RunCommandTool tool = new RunCommandTool(plan -> fail("runner must not execute invalid args"));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_test",
                "args_json", "[\"clean\"]")), context());

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("destructive command risk"));
    }

    @Test
    void nonZeroExitIsFailureDominantToolResult() throws Exception {
        createGradleWrapper();
        RunCommandTool tool = new RunCommandTool(plan -> new CommandResult(
                plan, 7, 125, false, false, "tests failed", "stacktrace", false, false, false, ""));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_test")), context());

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertTrue(result.errorMessage().startsWith("Command failed: gradle_test exited with code 7"));
        assertTrue(result.errorMessage().contains("stdout:"));
        assertTrue(result.errorMessage().contains("tests failed"));
        assertFalse(result.errorMessage().toLowerCase().contains("ready to use"));
    }

    @Test
    void timeoutIsFailureDominantToolResult() throws Exception {
        createGradleWrapper();
        RunCommandTool tool = new RunCommandTool(plan -> new CommandResult(
                plan, -1, 1_001, true, true, "", "timeout", false, false, false, ""));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_test",
                "timeout_ms", "1000")), context());

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertTrue(result.errorMessage().startsWith("Command timed out: gradle_test"));
        assertTrue(result.errorMessage().contains("process killed"));
    }

    private ToolContext context() {
        return new ToolContext(workspace, new Sandbox(workspace, Map.of()), new Config());
    }

    private void createGradleWrapper() throws Exception {
        Files.writeString(workspace.resolve("gradlew.bat"), "@echo off\r\n");
    }

    private static CommandResult success(CommandPlan plan, String stdout, String stderr) {
        return new CommandResult(plan, 0, 42, false, false, stdout, stderr, false, false, false, "");
    }
}
