package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T792: the command-verification upgrade is additive-only and fail-closed -
 * only an approved, successful, verification-class run_command ordered
 * AFTER the last successful mutation upgrades READBACK_ONLY to PASSED.
 */
class CommandVerificationEvidenceTest {

    @TempDir Path tempDir;

    @Test
    void passingCheckAfterTheMutationIsAccepted() {
        Optional<String> profile = CommandVerificationEvidence.verificationProfilePassedAfterMutations(
                List.of(
                        mutation(true),
                        commandSuccess("gradle_check")));

        assertEquals(Optional.of("gradle_check"), profile);
    }

    @Test
    void workspaceProfilesAreVerificationClass() {
        Optional<String> profile = CommandVerificationEvidence.verificationProfilePassedAfterMutations(
                List.of(
                        mutation(true),
                        commandSuccess("ws:check")));

        assertEquals(Optional.of("ws:check"), profile);
    }

    @Test
    void runBeforeTheMutationProvesNothing() {
        Optional<String> profile = CommandVerificationEvidence.verificationProfilePassedAfterMutations(
                List.of(
                        commandSuccess("gradle_check"),
                        mutation(true)));

        assertTrue(profile.isEmpty(),
                "a check that ran before the mutation cannot verify the mutation");
    }

    @Test
    void failedRunsBuildsAndAmbiguousSummariesProveNothing() {
        // Failed command after the mutation.
        assertTrue(CommandVerificationEvidence.verificationProfilePassedAfterMutations(List.of(
                mutation(true),
                new ToolCallLoop.ToolOutcome("talos.run_command", "", false, false, false,
                        "", "Command failed: gradle_check exited with code 1 after 5ms.")))
                .isEmpty());
        // Build profiles are not verification-class in v1.
        assertTrue(CommandVerificationEvidence.verificationProfilePassedAfterMutations(List.of(
                mutation(true),
                commandSuccess("gradle_build")))
                .isEmpty());
        // Unknown summary shape fails closed.
        assertTrue(CommandVerificationEvidence.verificationProfilePassedAfterMutations(List.of(
                mutation(true),
                new ToolCallLoop.ToolOutcome("talos.run_command", "", true, false, false,
                        "something unexpected", "")))
                .isEmpty());
    }

    @Test
    void verifierUpgradesReadbackOnlyToPassedOnCommandEvidence() throws Exception {
        Path workspace = tempDir.resolve("ws");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("notes.txt"), "updated", StandardCharsets.UTF_8);

        TaskVerificationResult withoutCommand = StaticTaskVerifier.verify(
                workspace, "Update notes.txt",
                loopResult(List.of(writeOutcome("notes.txt"))), 0);
        assertEquals(TaskVerificationStatus.READBACK_ONLY, withoutCommand.status(),
                "baseline: plain readback stays READBACK_ONLY - " + withoutCommand.summary());

        TaskVerificationResult withCommand = StaticTaskVerifier.verify(
                workspace, "Update notes.txt",
                loopResult(List.of(writeOutcome("notes.txt"), commandSuccess("gradle_check"))), 0);
        assertEquals(TaskVerificationStatus.PASSED, withCommand.status(), withCommand.summary());
        assertEquals("Command verification passed: gradle_check exited 0.",
                withCommand.summary());
    }

    @Test
    void verifierNeverUpgradesAFailedVerdict() throws Exception {
        Path workspace = tempDir.resolve("ws-failed");
        Files.createDirectories(workspace);
        // The mutation target does NOT exist - readback fails the verification.

        TaskVerificationResult result = StaticTaskVerifier.verify(
                workspace, "Update missing.txt",
                loopResult(List.of(writeOutcome("missing.txt"), commandSuccess("gradle_check"))), 0);

        assertEquals(TaskVerificationStatus.FAILED, result.status(),
                "command success must never mask a failed readback: " + result.summary());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static ToolCallLoop.ToolOutcome mutation(boolean success) {
        return new ToolCallLoop.ToolOutcome("talos.write_file", "notes.txt",
                success, true, false, "wrote", "");
    }

    private static ToolCallLoop.ToolOutcome writeOutcome(String path) {
        return new ToolCallLoop.ToolOutcome("talos.write_file", path,
                true, true, false, "wrote " + path, "");
    }

    private static ToolCallLoop.ToolOutcome commandSuccess(String profile) {
        return new ToolCallLoop.ToolOutcome("talos.run_command", "",
                true, false, false,
                "Command succeeded: " + profile + " exited with code 0 after 1234ms.", "");
    }

    private static ToolCallLoop.LoopResult loopResult(List<ToolCallLoop.ToolOutcome> outcomes) {
        return new ToolCallLoop.LoopResult(
                "", 1, 1, List.of("talos.write_file"), List.of(), 1, 0, false, 0,
                List.of(), 0, 0, 0, 0, outcomes);
    }
}
