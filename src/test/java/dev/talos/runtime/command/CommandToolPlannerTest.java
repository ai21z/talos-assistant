package dev.talos.runtime.command;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T787 pins for the trust surfaces T790 must not move: the gradle
 * approval-detail bytes (the text the user reads before approving a
 * command) and the invalid-call message shape. The T790 `ws:` branch adds
 * NEW strings; everything pinned here stays byte-identical.
 */
class CommandToolPlannerTest {

    @TempDir Path tempDir;

    @Test
    void gradleApprovalDetailBytesPin() throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("gradlew.bat"), "rem wrapper", StandardCharsets.UTF_8);
        ToolCall call = new ToolCall("talos.run_command", Map.of("profile", "gradle_test"));

        String detail = CommandToolPlanner.approvalDetail(call, workspace);

        Path cwd = workspace.toAbsolutePath().normalize();
        assertEquals(
                "profile: gradle_test\n"
                        + "    risk: BUILD_OR_TEST\n"
                        + "    cwd: " + cwd + "\n"
                        + "    argv: .\\gradlew.bat --no-daemon test\n"
                        + "    timeoutMs: 120000\n"
                        + "    outputCaps: stdout=65536 bytes, stderr=65536 bytes\n"
                        + "    expectedWrites: build/, .gradle/\n"
                        + "    checkpoint: not required\n"
                        + "    network: disabled, interactive: disabled",
                detail);
    }

    @Test
    void invalidMessageShapePin() {
        assertEquals(
                "Invalid talos.run_command call: Missing required parameter `profile`."
                        + " No approval was requested and no command was executed.",
                CommandToolPlanner.invalidMessage("Missing required parameter `profile`."));
    }

    @Test
    void unknownProfileIsRejectedBeforeAnyApproval() throws Exception {
        Path workspace = tempDir.resolve("ws2");
        Files.createDirectories(workspace);
        ToolCall call = new ToolCall("talos.run_command", Map.of("profile", "nope"));

        Optional<String> rejection = CommandToolPlanner.validateBeforeApproval(call, workspace);

        assertTrue(rejection.isPresent());
        assertTrue(rejection.get().contains("not available for talos.run_command V1"),
                rejection.get());
        assertTrue(rejection.get().contains("No approval was requested and no command was executed."),
                rejection.get());
    }

    @Test
    void rawCommandShapesAreRejected() throws Exception {
        Path workspace = tempDir.resolve("ws3");
        Files.createDirectories(workspace);
        ToolCall call = new ToolCall("talos.run_command",
                Map.of("profile", "gradle_test", "command", "rm -rf /"));

        Optional<String> rejection = CommandToolPlanner.validateBeforeApproval(call, workspace);

        assertTrue(rejection.isPresent());
        assertTrue(rejection.get().contains("Raw shell commands are not supported"),
                rejection.get());
    }
}
