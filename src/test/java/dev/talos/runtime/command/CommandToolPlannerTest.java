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

    // ── T790: workspace ws: profiles through the dispatching planner ────

    @Test
    void trustedWorkspaceProfilePlansItsDeclaredArgvExactly() throws Exception {
        Path workspace = workspaceWithDeclaredProfile();
        CommandProfileRegistry registry = trustedRegistry(workspace);
        ToolCall call = new ToolCall("talos.run_command", Map.of("profile", "ws:check"));

        CommandPlan plan = CommandToolPlanner.plan(call, workspace, registry);

        assertEquals("ws:check", plan.profileId());
        assertEquals(workspace.resolve("gradlew.bat").toAbsolutePath().normalize().toString(),
                plan.executable());
        assertEquals(java.util.List.of("--no-daemon", "check"), plan.argv(),
                "the plan argv must be exactly the declared fixed argv — nothing appended");
        assertTrue(plan.requiresApproval(), "per-run approval is never waived");
        assertTrue(CommandToolPlanner.approvalDetail(plan).startsWith("profile: ws:check\n"));
    }

    @Test
    void workspaceProfilesRejectCallerArguments() throws Exception {
        Path workspace = workspaceWithDeclaredProfile();
        CommandProfileRegistry registry = trustedRegistry(workspace);
        ToolCall call = new ToolCall("talos.run_command",
                Map.of("profile", "ws:check", "args_json", "[\"--info\"]"));

        Optional<String> rejection =
                CommandToolPlanner.validateBeforeApproval(call, workspace, registry);

        assertTrue(rejection.isPresent());
        assertTrue(rejection.get().contains("declared fixed argv only"), rejection.get());
    }

    /** T790 proof obligation: an untrusted declaration can never reach an approval prompt. */
    @Test
    void untrustedDeclarationIsRejectedAtPlanTimeWithTheTrustHint() throws Exception {
        Path workspace = workspaceWithDeclaredProfile();
        var loaded = WorkspaceCommandProfilesLoader.load(workspace);
        CommandProfileRegistry registry = CommandProfileRegistry.defaultRegistry()
                .withWorkspaceDeclaration(loaded.profiles(),
                        WorkspaceProfileTrustStore.TrustState.UNTRUSTED_NEW);
        ToolCall call = new ToolCall("talos.run_command", Map.of("profile", "ws:check"));

        assertTrue(registry.workspaceProfileIds().isEmpty(),
                "untrusted profiles must not even register");
        Optional<String> rejection =
                CommandToolPlanner.validateBeforeApproval(call, workspace, registry);
        assertTrue(rejection.isPresent());
        assertTrue(rejection.get().contains("untrusted or has changed"), rejection.get());
        assertTrue(rejection.get().contains("/profiles trust"), rejection.get());
        assertTrue(rejection.get().contains("No approval was requested and no command was executed."),
                rejection.get());
    }

    @Test
    void undeclaredAndInvalidStatesGetInstructiveMessages() throws Exception {
        Path workspace = tempDir.resolve("ws-none");
        Files.createDirectories(workspace);
        ToolCall call = new ToolCall("talos.run_command", Map.of("profile", "ws:check"));

        Optional<String> none = CommandToolPlanner.validateBeforeApproval(
                call, workspace, CommandProfileRegistry.defaultRegistry());
        assertTrue(none.isPresent());
        assertTrue(none.get().contains("declares no verification profiles"), none.get());

        CommandProfileRegistry invalid = CommandProfileRegistry.defaultRegistry()
                .withWorkspaceDeclaration(
                        WorkspaceCommandProfiles.invalid("YAML parse failed: boom"),
                        WorkspaceProfileTrustStore.TrustState.INVALID);
        Optional<String> rejected = CommandToolPlanner.validateBeforeApproval(
                call, workspace, invalid);
        assertTrue(rejected.isPresent());
        assertTrue(rejected.get().contains("declaration is invalid"), rejected.get());
        assertTrue(rejected.get().contains("YAML parse failed: boom"), rejected.get());
    }

    @Test
    void gradleProfilesStillPlanByteIdenticallyThroughTheDispatchingEntryPoint() throws Exception {
        Path workspace = tempDir.resolve("ws-gradle");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("gradlew.bat"), "rem wrapper", StandardCharsets.UTF_8);
        ToolCall call = new ToolCall("talos.run_command", Map.of("profile", "gradle_test"));

        CommandPlan viaDispatch = CommandToolPlanner.plan(
                call, workspace, CommandProfileRegistry.defaultRegistry());
        CommandPlan viaGradleV1 = CommandToolPlanner.planGradleV1(
                call, workspace, CommandProfileRegistry.defaultRegistry());

        assertEquals(CommandToolPlanner.approvalDetail(viaGradleV1),
                CommandToolPlanner.approvalDetail(viaDispatch));
    }

    private Path workspaceWithDeclaredProfile() throws Exception {
        Path workspace = tempDir.resolve("ws-declared-" + System.nanoTime());
        Files.createDirectories(workspace.resolve(".talos"));
        Files.writeString(workspace.resolve("gradlew.bat"), "rem wrapper", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".talos").resolve("profiles.yaml"),
                """
                profiles:
                  - id: check
                    executable: ./gradlew.bat
                    args: ["--no-daemon", "check"]
                """,
                StandardCharsets.UTF_8);
        return workspace;
    }

    private static CommandProfileRegistry trustedRegistry(Path workspace) {
        var loaded = WorkspaceCommandProfilesLoader.load(workspace);
        return CommandProfileRegistry.defaultRegistry()
                .withWorkspaceDeclaration(loaded.profiles(),
                        WorkspaceProfileTrustStore.TrustState.TRUSTED);
    }
}
