package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactEditReplacementVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void exactEditReplacementPassesWhenReplacementTextIsObservedAndOldTextIsGone() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=new\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(successfulExactEdit("notes.md", "status=old", "status=new", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertTrue(result.coversAllSuccessfulMutations());
        assertFalse(result.hasProblem());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("notes.md: exact edit replacement observed")),
                result.facts().toString());
    }

    @Test
    void exactEditReplacementFailsWhenReplacementTextIsMissing() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=old\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(successfulExactEdit("notes.md", "status=old", "status=new", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertTrue(result.coversAllSuccessfulMutations());
        assertTrue(result.hasProblem());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("notes.md: exact edit replacement text was not observed")),
                result.problems().toString());
    }

    @Test
    void mixedExactEditAndReadbackOnlyMutationDoesNotCoverAllSuccessfulMutations() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "status=new\n");
        Files.writeString(workspace.resolve("README.md"), "# Talos\n");

        ExactEditReplacementVerifier.Result result = ExactEditReplacementVerifier.verify(
                workspace,
                List.of(
                        successfulExactEdit("notes.md", "status=old", "status=new", VerificationStatus.PASS),
                        successfulWrite("README.md", VerificationStatus.PASS)));

        assertTrue(result.verifiedAny());
        assertFalse(result.coversAllSuccessfulMutations());
        assertFalse(result.hasProblem());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("notes.md: exact edit replacement observed")),
                result.facts().toString());
    }

    private static ToolCallLoop.ToolOutcome successfulExactEdit(
            String path,
            String oldString,
            String newString,
            VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file", path, true, true, false,
                "edited " + path, "", verificationStatus, "",
                null,
                ToolMutationEvidence.exactEdit(oldString, newString));
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(String path, VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", verificationStatus);
    }
}
