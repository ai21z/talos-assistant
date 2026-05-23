package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationTargetReadbackVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void readableMutationTargetRecordsFactAndMutationTarget() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Talos\n");

        MutationTargetReadbackVerifier.Result result = MutationTargetReadbackVerifier.verify(
                workspace,
                List.of(successfulWrite("README.md", VerificationStatus.UNKNOWN)));

        assertEquals(List.of("README.md"), result.mutationTargets().stream().toList());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertEquals(
                List.of("README.md: mutated target exists and is readable."),
                result.facts());
    }

    @Test
    void placeholderOnlyMutationRecordsProblemWithoutReadbackFact() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<updated_index_html_content>");

        MutationTargetReadbackVerifier.Result result = MutationTargetReadbackVerifier.verify(
                workspace,
                List.of(successfulWrite("index.html", VerificationStatus.PASS)));

        assertEquals(List.of("index.html"), result.mutationTargets().stream().toList());
        assertTrue(result.facts().isEmpty(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("index.html: mutated target contains only a template placeholder")),
                result.problems().toString());
    }

    @Test
    void missingPathHintRecordsToolProblemWithoutMutationTarget() {
        MutationTargetReadbackVerifier.Result result = MutationTargetReadbackVerifier.verify(
                workspace,
                List.of(successfulWrite("", VerificationStatus.PASS)));

        assertTrue(result.mutationTargets().isEmpty(), result.mutationTargets().toString());
        assertTrue(result.facts().isEmpty(), result.facts().toString());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("talos.write_file succeeded but did not expose a target path")),
                result.problems().toString());
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(String path, VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", verificationStatus);
    }
}
