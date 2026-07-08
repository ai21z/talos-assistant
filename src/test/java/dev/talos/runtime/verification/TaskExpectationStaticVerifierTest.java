package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskExpectationStaticVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void traceRecordingIsOwnedByDedicatedRecorder() throws Exception {
        Path sourceRoot = Path.of("src/main/java/dev/talos/runtime/verification");
        Path recorderPath = sourceRoot.resolve("TaskExpectationTraceRecorder.java");
        assertTrue(Files.isRegularFile(recorderPath), "TaskExpectationTraceRecorder must own trace recording.");

        String verifier = Files.readString(sourceRoot.resolve("TaskExpectationStaticVerifier.java"));
        String recorder = Files.readString(recorderPath);

        assertFalse(
                verifier.contains("LocalTurnTraceCapture"),
                "TaskExpectationStaticVerifier should not format trace events directly.");
        assertFalse(
                verifier.contains("recordExpectationVerified"),
                "TaskExpectationStaticVerifier should delegate expectation trace recording.");
        assertTrue(recorder.contains("final class TaskExpectationTraceRecorder"));
        assertTrue(recorder.contains("LocalTurnTraceCapture.recordExpectationVerified"));
        assertTrue(recorder.contains("recordLiteralExpectation"));
        assertTrue(recorder.contains("recordReplacementExpectation"));
        assertTrue(recorder.contains("recordAppendLineExpectation"));
        assertTrue(recorder.contains("recordBulletListExpectation"));
    }

    @Test
    void targetReadingIsOwnedByDedicatedReader() throws Exception {
        Path sourceRoot = Path.of("src/main/java/dev/talos/runtime/verification");
        Path readerPath = sourceRoot.resolve("TaskExpectationTargetReader.java");
        assertTrue(Files.isRegularFile(readerPath), "TaskExpectationTargetReader must own target file reads.");

        String verifier = Files.readString(sourceRoot.resolve("TaskExpectationStaticVerifier.java"));
        String reader = Files.readString(readerPath);

        assertFalse(verifier.contains("InvalidPathException"));
        assertFalse(verifier.contains("Files.isRegularFile"));
        assertFalse(verifier.contains("Files.readString"));
        assertTrue(reader.contains("final class TaskExpectationTargetReader"));
        assertTrue(reader.contains("Files.isRegularFile"));
        assertTrue(reader.contains("Files.readString"));
    }

    @Test
    void targetReaderPreservesExpectationSpecificMissingTargetWording() {
        assertProblem(
                "Overwrite missing.txt with exactly AFTER. Use talos.write_file.",
                "missing.txt: exact content verification target is not a readable file.");
        assertProblem(
                "Replace old with new in missing.txt.",
                "missing.txt: replacement verification target is not a readable file.");
        assertProblem(
                "Append exactly this line to missing.txt: AFTER",
                "missing.txt: appended line verification target is not a readable file.");
        assertProblem(
                "Create missing.md with exactly three bullet points.",
                "missing.md: bullet count verification target is not a readable file.");
    }

    @Test
    void mutationEvidenceProofIsOwnedByDedicatedVerifier() throws Exception {
        Path sourceRoot = Path.of("src/main/java/dev/talos/runtime/verification");
        Path verifierPath = sourceRoot.resolve("TaskExpectationMutationEvidenceVerifier.java");
        assertTrue(
                Files.isRegularFile(verifierPath),
                "TaskExpectationMutationEvidenceVerifier must own mutation evidence proof.");

        String expectationVerifier = Files.readString(sourceRoot.resolve("TaskExpectationStaticVerifier.java"));
        String mutationVerifier = Files.readString(verifierPath);

        assertFalse(expectationVerifier.contains("ToolAliasPolicy"));
        assertFalse(expectationVerifier.contains("mutationEvidence()"));
        assertFalse(expectationVerifier.contains("replacementOnlyChangesRequestedText"));
        assertFalse(expectationVerifier.contains("exactEditAppendsOnlyRequestedLine"));
        assertTrue(mutationVerifier.contains("final class TaskExpectationMutationEvidenceVerifier"));
        assertTrue(mutationVerifier.contains("ToolAliasPolicy"));
        assertTrue(mutationVerifier.contains("mutationEvidence()"));
        assertTrue(mutationVerifier.contains("replacementOnlyChangesRequestedText"));
        assertTrue(mutationVerifier.contains("exactEditAppendsOnlyRequestedLine"));
    }

    @Test
    void literalExpectationResultAndTraceStayRedacted() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "AFTER");
        LocalTurnTraceCapture.begin(
                "trc-t387-literal",
                "session-test",
                1,
                "2026-05-23T00:00:00Z",
                "workspace-hash",
                "auto",
                "ollama",
                "qwen2.5-coder:14b",
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");

        try {
            TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                    TaskContractResolver.fromUserRequest(
                            "Overwrite index.html with exactly AFTER. Use talos.write_file."),
                    workspace,
                    List.of(successfulWrite("index.html", VerificationStatus.PASS)),
                    true);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(result.verifiedAny());
            assertFalse(result.replacementRequired());
            assertFalse(result.appendLineRequired());
            assertFalse(result.bulletCountRequired());
            assertTrue(result.problems().isEmpty(), result.problems().toString());
            assertEquals(
                    List.of("index.html: literal content matched requested exact content."),
                    result.facts());

            var event = trace.events().stream()
                    .filter(e -> e.type().equals("EXPECTATION_VERIFIED"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("LITERAL_CONTENT", event.data().get("kind"));
            assertEquals("PASSED", event.data().get("status"));
            assertEquals("index.html", event.data().get("pathHint"));
            assertTrue(event.data().containsKey("expectedHash"));
            assertTrue(event.data().containsKey("observedHash"));
            assertFalse(event.data().containsValue("AFTER"));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void replacementExpectationPassesWhenNewTextContainsOldTextAndOldTextOnlyAppearsInsideReplacement()
            throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "Release 2023-2024\n");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest(
                        "Replace 2023 with 2023-2024 in notes.md."),
                workspace,
                List.of(successfulWrite("notes.md", VerificationStatus.PASS)),
                false);

        assertTrue(result.verifiedAny());
        assertTrue(result.replacementRequired());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(fact -> fact.contains("notes.md: replacement text observed")),
                result.facts().toString());
    }

    @Test
    void replacementExpectationFailsWhenNewTextContainsOldTextButOldTextAlsoRemainsOutsideReplacement()
            throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "Release 2023\nRelease 2023-2024\n");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest(
                        "Replace 2023 with 2023-2024 in notes.md."),
                workspace,
                List.of(successfulWrite("notes.md", VerificationStatus.PASS)),
                false);

        assertTrue(result.verifiedAny());
        assertTrue(result.replacementRequired());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("notes.md: replacement old text remained")),
                result.problems().toString());
    }

    @Test
    void replacementExpectationFailsWhenTrimEditDidNotLand() throws Exception {
        Files.writeString(workspace.resolve("code.java"), "final int x\n");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest(
                        "Replace final int x with int x in code.java."),
                workspace,
                List.of(successfulWrite("code.java", VerificationStatus.PASS)),
                false);

        assertTrue(result.verifiedAny());
        assertTrue(result.replacementRequired());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("code.java: replacement old text remained")),
                result.problems().toString());
    }

    private void assertProblem(String request, String expectedProblem) {
        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest(request),
                workspace,
                List.of(successfulWrite(targetFromProblem(expectedProblem), VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().contains(expectedProblem), result.problems().toString());
    }

    private static String targetFromProblem(String problem) {
        int separator = problem == null ? -1 : problem.indexOf(':');
        return separator < 0 ? "" : problem.substring(0, separator);
    }

    private static ToolCallLoop.ToolOutcome successfulWrite(String path, VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", verificationStatus);
    }
}
