package dev.talos.runtime.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CommandProfileRegistryTest {

    @Test
    void defaultRegistryExposesOnlyV1Profiles() {
        CommandProfileRegistry registry = CommandProfileRegistry.defaultRegistry();

        assertEquals(Set.of(
                        "gradle_test",
                        "gradle_check",
                        "gradle_build",
                        "gradle_install_dist",
                        "gradle_e2e_test",
                        "git_status",
                        "git_diff",
                        "git_log",
                        "java_version",
                        "talos_version"),
                registry.profileIds());
    }

    @Test
    void gradleTestPlanUsesFixedProfileAndCallerArgs(@TempDir Path workspace) {
        CommandPlan plan = CommandProfileRegistry.defaultRegistry().plan(
                "gradle_test",
                List.of("--tests", "dev.talos.runtime.SomeTest"),
                workspace,
                ".");

        assertEquals("gradle_test", plan.profileId());
        assertEquals(CommandRuntimePlatform.current().gradleWrapperExecutable(), plan.executable());
        assertEquals(List.of("--no-daemon", "test", "--tests", "dev.talos.runtime.SomeTest"),
                plan.argv());
        assertEquals(workspace.toAbsolutePath().normalize(), plan.cwd());
        assertEquals(CommandRisk.BUILD_OR_TEST, plan.risk());
        assertFalse(plan.networkAccess());
        assertFalse(plan.interactive());
        assertTrue(plan.requiresApproval());
        assertFalse(plan.requiresCheckpoint());
        assertEquals(List.of("build/", ".gradle/"), plan.expectedWrites());
        assertEquals(120_000, plan.timeoutMs());
        assertEquals(65_536, plan.outputLimits().stdoutLimitBytes());
        assertEquals(65_536, plan.outputLimits().stderrLimitBytes());
    }

    @Test
    void gradleProfilesResolveWindowsWrapperForWindowsPlatform(@TempDir Path workspace) {
        CommandPlan plan = CommandProfileRegistry.defaultRegistry(CommandRuntimePlatform.windows()).plan(
                "gradle_check",
                List.of(),
                workspace,
                ".");

        assertEquals(".\\gradlew.bat", plan.executable());
    }

    @Test
    void gradleProfilesResolvePosixWrapperForPosixPlatform(@TempDir Path workspace) {
        CommandPlan plan = CommandProfileRegistry.defaultRegistry(CommandRuntimePlatform.posix()).plan(
                "gradle_check",
                List.of(),
                workspace,
                ".");

        assertEquals("./gradlew", plan.executable());
    }

    @Test
    void readOnlyGitProfilePlansAsDiagnostic(@TempDir Path workspace) {
        CommandPlan plan = CommandProfileRegistry.defaultRegistry().plan(
                "git_status",
                List.of(),
                workspace,
                ".");

        assertEquals("git", plan.executable());
        assertEquals(List.of("status", "--short"), plan.argv());
        assertEquals(CommandRisk.READ_ONLY_DIAGNOSTIC, plan.risk());
        assertTrue(plan.expectedWrites().isEmpty());
        assertTrue(plan.requiresApproval(), "V1 command execution asks even for diagnostics");
    }

    @Test
    void unknownProfileFailsClosed(@TempDir Path workspace) {
        CommandPlanRejectedException ex = assertThrows(
                CommandPlanRejectedException.class,
                () -> CommandProfileRegistry.defaultRegistry().plan(
                        "shell",
                        List.of("-Command", "Get-ChildItem"),
                        workspace,
                        "."));

        assertTrue(ex.getMessage().contains("Unknown command profile"), ex.getMessage());
    }

    @Test
    void cwdEscapeFailsClosed(@TempDir Path workspace) {
        CommandPlanRejectedException ex = assertThrows(
                CommandPlanRejectedException.class,
                () -> CommandProfileRegistry.defaultRegistry().plan(
                        "git_status",
                        List.of(),
                        workspace,
                        ".."));

        assertTrue(ex.getMessage().contains("cwd escapes workspace"), ex.getMessage());
    }

    @Test
    void planCollectionsAreImmutable(@TempDir Path workspace) {
        CommandPlan plan = CommandProfileRegistry.defaultRegistry().plan(
                "gradle_check",
                List.of(),
                workspace,
                ".");

        assertThrows(UnsupportedOperationException.class, () -> plan.argv().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> plan.expectedWrites().add("src/"));
    }
}
