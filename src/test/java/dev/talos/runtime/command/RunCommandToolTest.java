package dev.talos.runtime.command;

import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContentMetadata;
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
    void commandResultsCarryCommandOutputMetadataForHandoff() throws Exception {
        createGradleWrapper();
        RunCommandTool tool = new RunCommandTool(plan -> success(plan, "BUILD SUCCESSFUL", ""));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_test")), context());

        ToolContentMetadata metadata = result.contentMetadata();
        assertEquals(ToolContentMetadata.ContentPrivacyClass.COMMAND_OUTPUT, metadata.privacyClass());
        assertEquals(ToolContentMetadata.ContentSource.COMMAND, metadata.source());
        assertEquals("gradle_test", metadata.sourcePath());
        assertTrue(metadata.modelHandoffAllowed());
        assertFalse(metadata.rawArtifactPersistenceAllowed());
        assertFalse(metadata.ragIndexAllowed());
        assertTrue(metadata.decisionReason().contains("command output"), metadata.decisionReason());
    }

    @Test
    void redactedCommandResultsAreTaggedForWithheldModelHandoff() throws Exception {
        createGradleWrapper();
        RunCommandTool tool = new RunCommandTool(plan -> new CommandResult(
                plan,
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

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_test")), context());

        ToolContentMetadata metadata = result.contentMetadata();
        assertTrue(result.success(), result.errorMessage());
        assertEquals(ToolContentMetadata.ContentPrivacyClass.COMMAND_OUTPUT, metadata.privacyClass());
        assertEquals(ToolContentMetadata.ContentSource.COMMAND, metadata.source());
        assertFalse(metadata.modelHandoffAllowed(),
                "redacted command streams must route through an explicit withhold/summarize boundary");
        assertFalse(metadata.rawArtifactPersistenceAllowed());
        assertFalse(metadata.ragIndexAllowed());
        assertTrue(result.output().contains("redactionApplied: true"), result.output());
    }

    @Test
    void highEntropyCommandResultsAreTaggedForWithheldModelHandoff() throws Exception {
        createGradleWrapper();
        String highEntropy = "N7k9Qp2vLm8Xr4Ts6Wd0Yh3Za5Bc1Ef7Gj9Kl2Mn";
        RunCommandTool tool = new RunCommandTool(plan -> new CommandResult(
                plan,
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

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_test")), context());

        ToolContentMetadata metadata = result.contentMetadata();
        assertTrue(result.success(), result.errorMessage());
        assertFalse(metadata.modelHandoffAllowed(),
                "high-entropy command streams must be withheld even when the universal sanitizer does not redact");
        assertTrue(metadata.decisionReason().contains("high-entropy"), metadata.decisionReason());
        assertTrue(result.output().contains(highEntropy),
                "local command output remains available in the raw candidate result");
    }

    @Test
    void commandHighEntropyDetectorDoesNotWithholdOrdinaryHashesOrProse() throws Exception {
        createGradleWrapper();
        String gitSha = "0123456789abcdef0123456789abcdef01234567";
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        RunCommandTool tool = new RunCommandTool(plan -> new CommandResult(
                plan,
                0,
                42,
                false,
                false,
                "commit " + gitSha + "\nrequest " + uuid + "\nBUILD SUCCESSFUL",
                "",
                false,
                false,
                false,
                ""));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_test")), context());

        assertTrue(result.contentMetadata().modelHandoffAllowed(), result.contentMetadata().decisionReason());
        assertTrue(result.output().contains(gitSha), result.output());
        assertTrue(result.output().contains(uuid), result.output());
    }

    @Test
    void gradleProfileWithoutWrapperIsRejectedBeforeRunner() {
        RunCommandTool tool = new RunCommandTool(plan -> fail("runner must not execute without a Gradle wrapper"));

        ToolResult result = tool.execute(new ToolCall("talos.run_command", Map.of(
                "profile", "gradle_check")), context());

        assertFalse(result.success());
        assertEquals(ToolError.INVALID_PARAMS, result.error().code());
        assertTrue(result.errorMessage().contains("Gradle command profile requires selected wrapper"),
                result.errorMessage());
        assertTrue(result.errorMessage().contains(CommandRuntimePlatform.current().gradleWrapperExecutable()),
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
        String executable = CommandRuntimePlatform.current().gradleWrapperExecutable();
        String fileName = executable.endsWith("gradlew.bat") ? "gradlew.bat" : "gradlew";
        String content = executable.endsWith("gradlew.bat") ? "@echo off\r\n" : "#!/bin/sh\n";
        Files.writeString(workspace.resolve(fileName), content);
    }

    private static CommandResult success(CommandPlan plan, String stdout, String stderr) {
        return new CommandResult(plan, 0, 42, false, false, stdout, stderr, false, false, false, "");
    }
}
