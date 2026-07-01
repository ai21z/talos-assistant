package dev.talos.runtime.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandArgumentPolicyTest {

    @Test
    void gradleTestAllowsOnlySelectorAndDiagnosticFlags(@TempDir Path workspace) {
        CommandPlan plan = CommandProfileRegistry.defaultRegistry().plan(
                "gradle_test",
                List.of("--tests", "dev.talos.runtime.SomeTest", "--stacktrace"),
                workspace,
                ".");

        assertEquals(List.of(
                        "--no-daemon",
                        "test",
                        "--tests",
                        "dev.talos.runtime.SomeTest",
                        "--stacktrace"),
                plan.argv());
        assertEquals(CommandRisk.BUILD_OR_TEST, CommandRiskClassifier.classify(plan));
    }

    @Test
    void gradleRejectsExtraTasksAndNetworkScan(@TempDir Path workspace) {
        assertRejected(workspace, "gradle_test", List.of("clean"), "destructive");
        assertRejected(workspace, "gradle_test", List.of("--scan"), "network");
    }

    @Test
    void shellMetacharactersAreRejectedBeforePlanning(@TempDir Path workspace) {
        assertRejected(workspace, "gradle_test", List.of("--tests", "A; rm -rf ."), "shell syntax");
        assertRejected(workspace, "gradle_test", List.of("test && del README.md"), "shell syntax");
    }

    @Test
    void destructiveAndNetworkTokensAreRejected(@TempDir Path workspace) {
        assertRejected(workspace, "gradle_test", List.of("--delete"), "destructive");
        assertRejected(workspace, "gradle_test", List.of("curl"), "network");
    }

    @Test
    void gitStatusAndLogDoNotAcceptCallerArgs(@TempDir Path workspace) {
        assertRejected(workspace, "git_status", List.of("--ignored"), "does not accept caller arguments");
        assertRejected(workspace, "git_log", List.of("--all"), "does not accept caller arguments");
    }

    @Test
    void gitDiffAcceptsWorkspaceRelativePathspecsOnly(@TempDir Path workspace) {
        CommandPlan plan = CommandProfileRegistry.defaultRegistry().plan(
                "git_diff",
                List.of("src/main/java"),
                workspace,
                ".");

        assertEquals(List.of("diff", "--", "src/main/java"), plan.argv());
        assertRejected(workspace, "git_diff", List.of("../outside"), "escapes workspace");
        assertRejected(workspace, "git_diff", List.of("--output=diff.txt"), "not allowed for profile");
    }

    private static void assertRejected(
            Path workspace,
            String profile,
            List<String> args,
            String expectedMessage
    ) {
        CommandPlanRejectedException ex = assertThrows(
                CommandPlanRejectedException.class,
                () -> CommandProfileRegistry.defaultRegistry().plan(profile, args, workspace, "."));
        assertTrue(ex.getMessage().contains(expectedMessage), ex.getMessage());
    }
}
