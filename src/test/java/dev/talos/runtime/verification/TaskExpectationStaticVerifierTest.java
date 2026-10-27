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
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.tools.ToolFailureReason;

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
        assertFalse(reader.contains("Files.isRegularFile"));
        assertFalse(reader.contains("Files.readString"));
        assertTrue(reader.contains("StaticWebFileAccess.isRegularFile"));
        assertTrue(reader.contains("StaticWebFileAccess.readString"));
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
                "llama_cpp",
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

    @Test
    void writeThroughTheAbsolutePathIsCoveredByTheTargetExpectation() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("index.html"), "AFTER");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest(
                        "Overwrite index.html with exactly AFTER. Use talos.write_file."),
                root,
                List.of(successfulWrite(root.resolve("index.html").toString(), VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.coversAllSuccessfulMutations());
    }

    @Test
    void writeToASiblingIsNotCoveredByTheTargetExpectation() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("index.html"), "AFTER");
        Files.writeString(root.resolve("other.html"), "AFTER");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest(
                        "Overwrite index.html with exactly AFTER. Use talos.write_file."),
                root,
                List.of(successfulWrite("index.html", VerificationStatus.PASS),
                        successfulWrite(root.resolve("other.html").toString(), VerificationStatus.PASS)),
                false);

        assertFalse(result.coversAllSuccessfulMutations());
    }

    @Test
    void appendEvidenceIsCheckedForAWriteThroughTheAbsolutePath() throws Exception {
        Path root = workspace.toRealPath();
        Files.writeString(root.resolve("notes.md"), "first\nAFTER\n");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest("Append exactly this line to notes.md: AFTER"),
                root,
                List.of(successfulWrite(root.resolve("notes.md").toString(), VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains("cannot prove append-only preservation")),
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

    private static final String NESTED_REPLACE_REQUEST =
            "In docs/notes.md, change draft to final. If an exact edit fails, "
                    + "recover without deleting any other line.";
    private static final String NESTED_APPEND_SECTION_REQUEST =
            "Append a Contributing section to docs/README.md with one sentence saying "
                    + "Run tests before review. Preserve all current text.";
    private static final String SECTION_BEFORE =
            "# Atlas Notes\n\nA small local project for qualification tests.\n\n"
                    + "## Setup\n\nRun the local checks before committing.\n";
    private static final String SECTION_AFTER =
            SECTION_BEFORE + "\n### Contributing\nRun tests before review.\n";

    @Test
    void replacementObligationCountsAnAbsoluteEditOfTheSameNestedFileAsASecondMutation() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/notes.md"), "Status: final\nKeep this final line unchanged.\n");
        TaskContract contract = TaskContractResolver.fromUserRequest(NESTED_REPLACE_REQUEST);
        assertCompleteObligation(contract, RequestObligation.ActionKind.REPLACE_TEXT_PRESERVE_REST, "docs/notes.md");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                contract,
                root,
                List.of(successfulExactEdit(
                                "docs/notes.md", "Status: draft", "Status: final", VerificationStatus.PASS),
                        successfulExactEdit(
                                root.resolve("docs/notes.md").toString(),
                                "Status: draft",
                                "Status: final",
                                VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains(
                                "preserve-rest replacement requires exactly one successful matching mutation")),
                result.problems().toString());
    }

    @Test
    void replacementObligationDoesNotCountAnAbsoluteEditOfASameNamedFileInAnotherFolder() throws Exception {
        Path root = workspace.toRealPath();
        String updated = "Status: final\nKeep this final line unchanged.\n";
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("docs/notes.md"), updated);
        Files.writeString(root.resolve("other/notes.md"), updated);
        TaskContract contract = TaskContractResolver.fromUserRequest(NESTED_REPLACE_REQUEST);
        assertCompleteObligation(contract, RequestObligation.ActionKind.REPLACE_TEXT_PRESERVE_REST, "docs/notes.md");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                contract,
                root,
                List.of(successfulExactEdit(
                                "docs/notes.md", "Status: draft", "Status: final", VerificationStatus.PASS),
                        successfulExactEdit(
                                root.resolve("other/notes.md").toString(),
                                "Status: draft",
                                "Status: final",
                                VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().stream()
                        .noneMatch(problem -> problem.contains(
                                "requires exactly one successful matching mutation")),
                result.problems().toString());
        assertTrue(result.problems().contains(
                        "Request-obligation evidence did not cover every successful mutation."),
                result.problems().toString());
        assertFalse(result.coversAllSuccessfulMutations());
    }

    @Test
    void appendSectionObligationCountsAnAbsoluteWriteOfTheSameNestedFileAsASecondMutation() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/README.md"), SECTION_AFTER);
        TaskContract contract = TaskContractResolver.fromUserRequest(NESTED_APPEND_SECTION_REQUEST);
        assertCompleteObligation(contract, RequestObligation.ActionKind.APPEND_SECTION, "docs/README.md");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                contract,
                root,
                List.of(successfulFullWrite(
                                "docs/README.md", SECTION_BEFORE, SECTION_AFTER, VerificationStatus.PASS),
                        successfulFullWrite(
                                root.resolve("docs/README.md").toString(),
                                SECTION_BEFORE,
                                SECTION_AFTER,
                                VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains(
                                "append-section verification requires exactly one successful matching mutation")),
                result.problems().toString());
    }

    @Test
    void appendSectionObligationDoesNotCountAnAbsoluteWriteOfASameNamedFileInAnotherFolder() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("docs/README.md"), SECTION_AFTER);
        Files.writeString(root.resolve("other/README.md"), SECTION_AFTER);
        TaskContract contract = TaskContractResolver.fromUserRequest(NESTED_APPEND_SECTION_REQUEST);
        assertCompleteObligation(contract, RequestObligation.ActionKind.APPEND_SECTION, "docs/README.md");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                contract,
                root,
                List.of(successfulFullWrite(
                                "docs/README.md", SECTION_BEFORE, SECTION_AFTER, VerificationStatus.PASS),
                        successfulFullWrite(
                                root.resolve("other/README.md").toString(),
                                SECTION_BEFORE,
                                SECTION_AFTER,
                                VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().stream()
                        .noneMatch(problem -> problem.contains(
                                "requires exactly one successful matching mutation")),
                result.problems().toString());
        assertTrue(result.problems().contains(
                        "Request-obligation evidence did not cover every successful mutation."),
                result.problems().toString());
        assertFalse(result.coversAllSuccessfulMutations());
    }

    @Test
    void appendLineExactEditThroughTheAbsolutePathMustAppendOnlyTheRequestedLine() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/notes.md"), "first\nEXTRA\nAFTER\n");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest("Append exactly this line to docs/notes.md: AFTER"),
                root,
                List.of(successfulExactEdit(
                        root.resolve("docs/notes.md").toString(),
                        "first",
                        "first\nEXTRA\nAFTER",
                        VerificationStatus.PASS)),
                false);

        assertTrue(result.appendLineRequired());
        assertTrue(result.problems().stream()
                        .anyMatch(problem -> problem.contains(
                                "exact edit did not preserve prior content before appended line")),
                result.problems().toString());
    }

    @Test
    void appendLineExactEditOfASameNamedFileInAnotherFolderIsNotCheckedAsTheTarget() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("docs/notes.md"), "first\nAFTER\n");
        Files.writeString(root.resolve("other/notes.md"), "first\nEXTRA\nAFTER\n");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                TaskContractResolver.fromUserRequest("Append exactly this line to docs/notes.md: AFTER"),
                root,
                List.of(successfulExactEdit(
                                "docs/notes.md", "first", "first\nAFTER", VerificationStatus.PASS),
                        successfulExactEdit(
                                root.resolve("other/notes.md").toString(),
                                "first",
                                "first\nEXTRA\nAFTER",
                                VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().stream()
                        .noneMatch(problem -> problem.contains("exact edit did not preserve prior content")),
                result.problems().toString());
        assertTrue(result.facts().contains(
                        "docs/notes.md: exact edit evidence preserved prior content before appended line."),
                result.facts().toString());
        assertFalse(result.coversAllSuccessfulMutations());
    }

    private static void assertCompleteObligation(
            TaskContract contract,
            RequestObligation.ActionKind actionKind,
            String target) {
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        assertEquals(RequestObligation.DerivationStatus.COMPLETE, obligation.derivationStatus());
        assertEquals(actionKind, obligation.actionKind());
        assertEquals(List.of(target), obligation.requiredMutationTargets());
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
                ToolMutationEvidence.exactEdit(oldString, newString),
                ToolFailureReason.NONE,
                List.of(),
                "call-1-1");
    }

    private static ToolCallLoop.ToolOutcome successfulFullWrite(
            String path,
            String previousContent,
            String newContent,
            VerificationStatus verificationStatus) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file", path, true, true, false,
                "wrote " + path, "", verificationStatus, "",
                null,
                ToolMutationEvidence.fullWriteReplacement(previousContent, newContent),
                ToolFailureReason.NONE,
                List.of(),
                "call-1-1");
    }

    // Uses existing members: @TempDir workspace, NESTED_REPLACE_REQUEST (:283),
    // assertCompleteObligation(TaskContract, ActionKind, String) (:464),
    // successfulExactEdit(String, String, String, VerificationStatus) (:474, id call-1-1).
    // No new imports are needed. Expected red cause today: the receipt assertion only.

    @Test
    void replacementWrittenOnlyThroughTheAbsolutePathMintsAReceiptForThatCall() throws Exception {
        Path root = workspace.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/notes.md"), "Status: final\nKeep this final line unchanged.\n");
        TaskContract contract = TaskContractResolver.fromUserRequest(NESTED_REPLACE_REQUEST);
        assertCompleteObligation(contract, RequestObligation.ActionKind.REPLACE_TEXT_PRESERVE_REST, "docs/notes.md");

        TaskExpectationStaticVerifier.Result result = TaskExpectationStaticVerifier.verify(
                contract, root,
                List.of(successfulExactEdit(root.resolve("docs/notes.md").toString(),
                        "Status: draft", "Status: final", VerificationStatus.PASS)),
                false);

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.coversAllSuccessfulMutations());
        assertTrue(result.requestObligationReceipt().proves(RequestObligationCompiler.compile(contract)));
        assertEquals(List.of("call-1-1"), result.requestObligationReceipt().coveredToolCallIds());
    }
}
