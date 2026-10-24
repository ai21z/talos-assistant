package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceObligationVerifierTest {

    @Test
    void readTargetSuccessSatisfiesRequiredTarget() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED,
                Set.of("README.md"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "./README.md", true, false, false,
                        "read README.md", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void readTargetAliasSuccessSatisfiesRequiredTarget() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED,
                Set.of("config.json"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "read_file", "config.json", true, false, false,
                        "{\"name\":\"t57-fixture\"}", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void readTargetExplicitFailureDoesNotSatisfyRequiredTarget() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED,
                Set.of("README.md"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "README.md", false, false, false,
                        "", "README.md was not found.", null, ToolError.NOT_FOUND)));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void readTargetAcceptsSuccessfulTextFallbackForUnsupportedSameStemDocument() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED,
                Set.of("report.docx", "report.txt"),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "report.docx", false, false, false,
                                "", "Unsupported binary document format.", null, ToolError.UNSUPPORTED_FORMAT),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "report.txt", true, false, false,
                                "Converted report text fixture.", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void zeroToolsLeavesReadTargetUnsatisfied() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED,
                Set.of("README.md"),
                List.of());

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void protectedReadDenialBlocksObligation() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                Set.of(".env"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", ".env", false, false, true,
                        "", "User did not approve the talos.read_file call.", null, ToolError.DENIED)));

        assertEquals(EvidenceObligationVerifier.Status.BLOCKED, result.status());
    }

    @Test
    void protectedReadFailedPathVariantThenSuccessfulReadSatisfiesObligation() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                Set.of(".env"),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", " .env", false, false, false,
                                "", "File not found:  .env", null, ToolError.NOT_FOUND),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", ".env", true, false, false,
                                "SAFE_AUDIT_SECRET=fake", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void protectedReadFailedOnlyPathVariantRemainsUnsatisfied() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                Set.of(".env"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", " .env", false, false, false,
                        "", "File not found:  .env", null, ToolError.NOT_FOUND)));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void protectedReadWithoutToolAttemptIsSpecific() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                Set.of(".env"),
                List.of());

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
        assertEquals(
                "Protected read was not attempted; no approval prompt ran and no protected content was read.",
                result.message());
    }

    @Test
    void protectedReadDenialDominatesMissingTarget() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                new java.util.LinkedHashSet<>(List.of("missing.env", ".env")),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", ".env", false, false, true,
                        "", "User did not approve the talos.read_file call.", null, ToolError.DENIED)));

        assertEquals(EvidenceObligationVerifier.Status.BLOCKED, result.status());
    }

    @Test
    void unsupportedDocumentUnsupportedFormatSatisfiesCapabilityCheck() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED,
                Set.of("slides.pptx"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "slides.pptx", false, false, false,
                        "", "Unsupported binary document format.", null, ToolError.UNSUPPORTED_FORMAT)));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void extractableDocumentReadSatisfiesCapabilityCheckIfRecordedFromOldPlan() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED,
                Set.of("sample.pdf"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "sample.pdf", true, false, false,
                        "Extracted document text from sample.pdf (status: SUCCESS)", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void unsupportedCapabilityRequiresEvidenceForEachMixedTarget() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED,
                Set.of("slides.pptx", "config.json"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "slides.pptx", false, false, false,
                        "", "Unsupported binary document format.", null, ToolError.UNSUPPORTED_FORMAT)));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void unsupportedCapabilityAcceptsNormalReadForNonUnsupportedTarget() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED,
                Set.of("slides.pptx", "config.json"),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "slides.pptx", false, false, false,
                                "", "Unsupported binary document format.", null, ToolError.UNSUPPORTED_FORMAT),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "config.json", true, false, false,
                                "{\"name\":\"t57-fixture\"}", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void listOnlyRejectsReadFile() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.LIST_DIRECTORY_ONLY,
                Set.of(),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.list_dir", ".", true, false, false,
                                "listed files", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "README.md", true, false, false,
                                "read README.md", "")));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void listOnlyRejectsRetrieve() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.LIST_DIRECTORY_ONLY,
                Set.of(),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.list_dir", ".", true, false, false,
                                "listed files", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.retrieve", "README.md", true, false, false,
                                "retrieved README.md", "")));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void pathExistenceRejectsIrrelevantReadEvidence() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                Set.of("scripts.js", "script.js"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "styles.css", true, false, false,
                        "body { color: red; }", "")));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void pathExistenceAcceptsParentDirectoryListingEvidence() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                Set.of("scripts.js", "script.js"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", true, false, false,
                        "index.html\nscripts.js\nstyles.css\n", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void pathExistenceRejectsParentDirectoryListingThatDoesNotNameTarget() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                Set.of("docs/target.md"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", "docs", true, false, false,
                        "README.md\nnotes.md\n", "")));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void listOnlyDoesNotCountFailedDirectoryListingAsEvidence() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.LIST_DIRECTORY_ONLY,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", false, false, false,
                        "", "Listing failed.")));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void workspaceInspectionDoesNotCountFailedGrepAsEvidence() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.WORKSPACE_INSPECTION_REQUIRED,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.grep", "", false, false, false,
                        "", "Invalid include glob.", null, ToolError.INVALID_PARAMS)));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void projectSummaryRejectsUnrelatedReadWhenReadmeExists(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Local project facts.\n");
        Files.writeString(workspace.resolve("script.js"), "export function reset() { return 'ready'; }\n");

        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "script.js", true, false, false,
                        "read script.js", "")),
                workspace);

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
        assertEquals("Project summary requires source-bearing evidence from README.md.", result.message());
    }

    @Test
    void projectSummaryAcceptsDirectReadAndStructuredSearchSources(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Local project facts.\n");

        for (ToolCallLoop.ToolOutcome outcome : List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "./README.md", true, false, false,
                        "read README.md", ""),
                sourceOutcome("talos.grep", "README.md"),
                sourceOutcome("talos.retrieve", "./README.md"))) {
            var result = EvidenceObligationVerifier.verify(
                    EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED,
                    Set.of(),
                    List.of(outcome),
                    workspace);

            assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status(), outcome.toolName());
        }
    }

    @Test
    void projectSummaryRejectsDirectoryListingWithoutAnchorContent(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "Local project facts.\n");

        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", true, false, false,
                        "README.md\nscript.js\n", "")),
                workspace);

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void projectSummaryFallsBackToContentInspectionWhenNoReadmeExists(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("script.js"), "export function reset() { return 'ready'; }\n");

        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "script.js", true, false, false,
                        "read script.js", "")),
                workspace);

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void projectSummaryFailsClosedWhenWorkspaceCannotBeInspected(@TempDir Path workspace) {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "script.js", true, false, false,
                        "read script.js", "")),
                workspace.resolve("missing"));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
        assertEquals("Project summary anchor availability could not be checked.", result.message());
    }

    @Test
    void traceVerificationDoesNotCountDeniedReadAsEvidence() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.VERIFY_FROM_TRACE_OR_EVIDENCE,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "notes.md", false, false, true,
                        "", "Read denied.", null, ToolError.DENIED)));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void traceVerificationKeepsFailedCommandAsOutcomeEvidence() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.VERIFY_FROM_TRACE_OR_EVIDENCE,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.run_command", "", false, false, false,
                        "", "Command exited with code 1.", null, ToolError.INTERNAL_ERROR)));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void unnamedFileLookupRequiresSourceBearingContentEvidence() {
        EvidenceObligation obligation = EvidenceObligation.valueOf("FILE_CONTENT_LOOKUP_REQUIRED");

        var directRead = EvidenceObligationVerifier.verify(
                obligation,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "src/App.java", true, false, false,
                        "read App.java", "")));
        var listOnly = EvidenceObligationVerifier.verify(
                obligation,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", true, false, false,
                        "config.json\nsrc", "")));
        var noResultGrep = EvidenceObligationVerifier.verify(
                obligation,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.grep", "", true, false, false,
                        "No matches found.", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, directRead.status());
        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, listOnly.status());
        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, noResultGrep.status());
    }

    @Test
    void pathExistenceAcceptsDirectTargetNotFoundReadAsNegativeEvidence() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                Set.of("docs/target.md"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "docs/target.md", false, false, false,
                        "", "docs/target.md was not found.", null, ToolError.NOT_FOUND)));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void pathExistenceRejectsGenericFailedDirectTargetRead() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                Set.of("docs/target.md"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "docs/target.md", false, false, false,
                        "", "Read failed.", null, ToolError.INTERNAL_ERROR)));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void pathExistenceBlocksDeniedDirectTargetRead() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                Set.of("docs/target.md"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "docs/target.md", false, false, true,
                        "", "User denied read.", null, ToolError.DENIED)));

        assertEquals(EvidenceObligationVerifier.Status.BLOCKED, result.status());
    }

    @Test
    void pathExistenceAcceptsDirectTargetReadAttempts() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                Set.of("scripts.js", "script.js"),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "scripts.js", true, false, false,
                                "console.log('ok');", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "script.js", false, false, false,
                                "", "script.js was not found.", null, ToolError.NOT_FOUND)));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void staticWebDiagnosisRejectsDirectoryListingOnlyWhenIndexIsPresent() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", true, false, false,
                        "index.html\nscript.js\nstyles.css\n", "")));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
        assertEquals("Static web diagnosis requires reading index.html when it is present.", result.message());
    }

    @Test
    void staticWebDiagnosisAcceptsIndexReadWhenIndexIsPresent() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED,
                Set.of(),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.list_dir", ".", true, false, false,
                                "index.html\nscript.js\nstyles.css\n", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "index.html", true, false, false,
                                "<button id=\"go\">Go</button><script src=\"script.js\"></script>", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void staticWebDiagnosisRequiresExpectedIndexReadEvenAfterOtherWebReads() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED,
                Set.of("index.html"),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "script.js", true, false, false,
                                "document.querySelector('.missing-button')", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "styles.css", true, false, false,
                                "button { color: red; }", "")));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
        assertEquals("Static web diagnosis requires reading index.html.", result.message());
    }

    @Test
    void staticWebDiagnosisAcceptsContentInspectionWhenNoIndexPresenceIsKnown() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED,
                Set.of(),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "script.js", true, false, false,
                        "document.querySelector('.missing-button')", "")));

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void missingLinkedScriptReadTargetsNamesExistingUnreadLocalScripts() throws Exception {
        Path workspace = Files.createTempDirectory("talos-linked-script-evidence-");
        try {
            Files.writeString(workspace.resolve("index.html"),
                    "<script src=\"script.js?v=1#main\"></script>");
            Files.writeString(workspace.resolve("script.js"), "console.log('public');\n");

            List<String> missing = EvidenceObligationVerifier.missingLinkedScriptReadTargets(
                    workspace,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file", "index.html", true, false, false,
                            "read index.html", "")));

            assertEquals(List.of("script.js"), missing);
        } finally {
            try (var walk = Files.walk(workspace)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void missingLinkedScriptReadTargetsEmptyAfterLinkedScriptRead() throws Exception {
        Path workspace = Files.createTempDirectory("talos-linked-script-evidence-satisfied-");
        try {
            Files.writeString(workspace.resolve("index.html"),
                    "<script src=\"script.js\"></script>");
            Files.writeString(workspace.resolve("script.js"), "console.log('public');\n");

            List<String> missing = EvidenceObligationVerifier.missingLinkedScriptReadTargets(
                    workspace,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.read_file", "index.html", true, false, false,
                                    "read index.html", ""),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.read_file", "./script.js", true, false, false,
                                    "read script.js", "")));

            assertEquals(List.of(), missing);
        } finally {
            try (var walk = Files.walk(workspace)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void linkedExternalIndexCannotDefineEvidenceTargets(@TempDir Path workspace)
            throws Exception {
        Path external = Files.createTempFile(
                "talos-t1170-obligation-external-",
                ".html");
        try {
            Files.writeString(
                    external,
                    "<script src=\"T1170_EXTERNAL_OBLIGATION_CANARY.js\"></script>");
            Files.writeString(
                    workspace.resolve("T1170_EXTERNAL_OBLIGATION_CANARY.js"),
                    "console.log('ordinary workspace control');\n");
            createSymbolicLinkOrAbort(workspace.resolve("index.html"), external);

            List<String> missing =
                    EvidenceObligationVerifier.missingLinkedScriptReadTargets(
                            workspace,
                            List.of(new ToolCallLoop.ToolOutcome(
                                    "talos.read_file",
                                    "index.html",
                                    true,
                                    false,
                                    false,
                                    "read index.html",
                                    "")));

            assertEquals(List.of(), missing);
        } finally {
            Files.deleteIfExists(external);
        }
    }

    private static ToolCallLoop.ToolOutcome sourceOutcome(String toolName, String sourcePath) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                "",
                true,
                false,
                false,
                "source-bearing evidence",
                "",
                null,
                "",
                null,
                null,
                null,
                List.of(sourcePath));
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target)
            throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            assumeTrue(false, "symbolic-link fixture unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
    }

    @Test
    void readThroughTheAbsolutePathSatisfiesTheTargetOnlyWithAnIdentityRoot(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        var outcomes = List.of(read(root.resolve("notes.txt").toString(), true, false, ""));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED, Set.of("notes.txt"), outcomes).status());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED, Set.of("notes.txt"), outcomes, root).status());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED, Set.of("notes.txt"), outcomes, null, Set.of(), root)
                .status());
    }

    @Test
    void siblingOrSubfolderReadDoesNotSatisfyTheTarget(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        Files.writeString(root.resolve("other.txt"), "other\n");
        Files.writeString(root.resolve("docs").resolve("notes.txt"), "docs\n");

        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                Set.of("notes.txt"),
                List.of(read(root.resolve("other.txt").toString(), true, false, ""),
                        read(root.resolve("docs").resolve("notes.txt").toString(), true, false, "")),
                root);

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
    }

    @Test
    void deniedReadUnderAnotherSpellingBlocksTheTarget(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve(".env"), "MODE=local\n");

        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                Set.of(".env"),
                List.of(read(root.resolve(".env").toString(), false, true, "")),
                root);

        assertEquals(EvidenceObligationVerifier.Status.BLOCKED, result.status());
    }

    @Test
    void aSuccessUnderOneSpellingSatisfiesDespiteADenialUnderAnother(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");

        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED,
                Set.of("notes.txt"),
                List.of(read("notes.txt", true, false, ""),
                        read(root.resolve("notes.txt").toString(), false, true, "")),
                root);

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, result.status());
    }

    @Test
    void existenceIsDecidedByEveryMatchWhenTheSpellingsDiffer(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        var failedThenRead = List.of(
                read("notes.txt", false, false, ToolError.TOOL_ERROR),
                read(root.resolve("notes.txt").toString(), true, false, ""));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("notes.txt"), failedThenRead)
                .status());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("notes.txt"), failedThenRead, root)
                .status());
    }

    @Test
    void listingTheParentUnderAnotherSpellingShowsExistence(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("a.md"), "a\n");
        var listing = new ToolCallLoop.ToolOutcome(
                "talos.list_dir", root.resolve("docs").toString(), true, false, false, "a.md", "");

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("docs/a.md"), List.of(listing), root)
                .status());
    }

    private static ToolCallLoop.ToolOutcome read(String path, boolean success, boolean denied, String errorCode) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file", path, success, false, denied,
                success ? "content" : "", success ? "" : "read failed", null, errorCode);
    }

    @Test
    void anAlreadyNormalizedTargetKeepsItsTextMatchInTheFallbackFilter() {
        var outcomes = List.of(
                read("./ a.pdf", false, false, ToolError.UNSUPPORTED_FORMAT),
                read("a.txt", true, false, ""));

        assertEquals(Set.of("a.txt"), EvidenceObligationVerifier.withoutUnsupportedTargetsCoveredByTextFallback(
                Set.of("./ a.pdf", "a.txt"), outcomes));
    }

    @Test
    void aWorkspaceListingDoesNotShowATargetAtTheFileSystemRoot(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        var listing = new ToolCallLoop.ToolOutcome(
                "talos.list_dir", root.toString(), true, false, false, "notes.txt", "");

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("/notes.txt"), List.of(listing), root)
                .status());
    }

    @Test
    void listingTheWorkspaceUnderItsAbsoluteSpellingShowsARootLevelTarget(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        var listing = new ToolCallLoop.ToolOutcome(
                "talos.list_dir", root.toString(), true, false, false, "notes.txt", "");

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("notes.txt"), List.of(listing), root)
                .status());
    }

    @Test
    void listingAnotherFolderUnderAnAbsoluteSpellingDoesNotShowExistence(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("other").resolve("a.md"), "a\n");
        var listing = new ToolCallLoop.ToolOutcome(
                "talos.list_dir", root.resolve("other").toString(), true, false, false, "a.md", "");

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("docs/a.md"), List.of(listing), root)
                .status());
    }

    @Test
    void anySuccessfulReadSatisfiesAndADenialBlocksOnlyWithoutOne(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        var denied = read("notes.txt", false, true, ToolError.DENIED);
        var success = read(root.resolve("notes.txt").toString(), true, false, "");

        for (var outcomes : List.of(List.of(denied, success), List.of(success, denied))) {
            assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                    EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("notes.txt"), outcomes, root)
                    .status(), "a denial does not erase a successful read of the same file");
        }
        var sameSpelling = List.of(read("notes.txt", false, false, ToolError.TOOL_ERROR), read("notes.txt", true, false, ""));
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("notes.txt"), sameSpelling).status(),
                "a later successful read is evidence, whatever failed before it");
        assertEquals(EvidenceObligationVerifier.Status.BLOCKED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("notes.txt"),
                List.of(read("notes.txt", false, false, ToolError.TOOL_ERROR), denied), root).status());
    }

    @Test
    void aListingWithoutAPathShowsAnAbsoluteTargetInTheWorkspace(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("a.md"), "a\n");
        var listing = new ToolCallLoop.ToolOutcome("talos.list_dir", "", true, false, false, "a.md", "");

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                Set.of(root.resolve("a.md").toString().replace('\\', '/')),
                List.of(listing),
                root).status());
    }

    @Test
    void unsupportedReadAndItsTextFallbackUnderAbsoluteSpellingsSatisfyTheTargets(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.toRealPath();
        var outcomes = List.of(
                read(root.resolve("slides.pptx").toString(), false, false, ToolError.UNSUPPORTED_FORMAT),
                read(root.resolve("slides.txt").toString(), true, false, ""));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED, Set.of("slides.pptx", "slides.txt"), outcomes).status());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED, Set.of("slides.pptx", "slides.txt"), outcomes, root)
                .status());
    }

    @Test
    void unsupportedCapabilityCheckUnderAnAbsoluteSpellingSatisfiesTheTarget(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.toRealPath();
        var outcomes = List.of(read(root.resolve("slides.pptx").toString(), false, false, ToolError.UNSUPPORTED_FORMAT));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED, Set.of("slides.pptx"), outcomes).status());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED, Set.of("slides.pptx"), outcomes, root)
                .status());
    }

    @Test
    void staticWebIndexReadUnderItsAbsoluteSpellingSatisfiesTheDiagnosis(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("index.html"), "<html><body>page</body></html>\n");
        var outcomes = List.of(read(root.resolve("index.html").toString(), true, false, ""));

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED, Set.of("index.html"), outcomes).status());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED, Set.of("index.html"), outcomes, root).status());
    }

    @Test
    void aDenialAfterAnApprovedReadOfTheSameFileDoesNotEraseTheRead() {
        var outcomes = List.of(
                read(".env", true, false, ""),
                read(".env", false, true, ToolError.DENIED));

        for (var obligation : List.of(
                EvidenceObligation.READ_TARGET_REQUIRED, EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED)) {
            assertEquals(EvidenceObligationVerifier.Status.SATISFIED,
                    EvidenceObligationVerifier.verify(obligation, Set.of(".env"), outcomes).status(), obligation.name());
        }
    }

    @Test
    void equivalentSpellingsProduceEquivalentAssessments(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        String absolute = root.resolve("notes.txt").toString();
        record Step(boolean success, boolean denied, String error) {}
        Step ok = new Step(true, false, "");
        Step deny = new Step(false, true, ToolError.DENIED);
        Step fail = new Step(false, false, ToolError.TOOL_ERROR);
        List<List<Step>> sequences = List.of(
                List.of(ok, deny), List.of(deny, ok), List.of(fail, ok), List.of(ok, fail),
                List.of(deny), List.of(fail), List.of(fail, deny), List.of(deny, fail));

        for (var obligation : List.of(
                EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED)) {
            for (List<Step> sequence : sequences) {
                EvidenceObligationVerifier.Status reference = null;
                for (int mask = 0; mask < (1 << sequence.size()); mask++) {
                    List<ToolCallLoop.ToolOutcome> outcomes = new java.util.ArrayList<>();
                    for (int i = 0; i < sequence.size(); i++) {
                        Step step = sequence.get(i);
                        String spelling = (mask & (1 << i)) == 0 ? "notes.txt" : absolute;
                        outcomes.add(read(spelling, step.success(), step.denied(), step.error()));
                    }
                    var status = EvidenceObligationVerifier.verify(
                            obligation, Set.of("notes.txt"), outcomes, root).status();
                    if (reference == null) reference = status;
                    assertEquals(reference, status, obligation + " " + sequence + " mask " + mask);
                }
            }
        }
    }

    @Test
    void aReadFollowedByAWriteOfTheSameFileIsNoLongerCurrentEvidence(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        var write = new ToolCallLoop.ToolOutcome(
                "talos.write_file", root.resolve("notes.txt").toString(), true, true, false, "wrote", "");

        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED,
                Set.of("notes.txt"),
                List.of(read("notes.txt", true, false, ""), write),
                root);

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
        assertTrue(result.earlierReadSucceeded(), "the stale read still happened");
    }

    @Test
    void aReadAfterTheWriteOrAWriteOfAnotherFileStaysCurrent(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        var writeTarget = new ToolCallLoop.ToolOutcome(
                "talos.write_file", "notes.txt", true, true, false, "wrote", "");
        var writeOther = new ToolCallLoop.ToolOutcome(
                "talos.write_file", root.resolve("other.txt").toString(), true, true, false, "wrote", "");

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED, Set.of("notes.txt"),
                List.of(writeTarget, read(root.resolve("notes.txt").toString(), true, false, "")), root).status());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED, Set.of("notes.txt"),
                List.of(read("notes.txt", true, false, ""), writeOther), root).status());
    }

    @Test
    void aDenialWithoutAnySuccessfulReadStillBlocks() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                Set.of(".env"),
                List.of(read(".env", false, true, ToolError.DENIED)));

        assertEquals(EvidenceObligationVerifier.Status.BLOCKED, result.status());
        assertFalse(result.earlierReadSucceeded());
    }

    @Test
    void anApprovedReadOfOneProtectedTargetIsRecordedWhenAnotherIsDenied() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.PROTECTED_READ_APPROVAL_REQUIRED,
                Set.of(".env", ".env.local"),
                List.of(read(".env", true, false, ""), read(".env.local", false, true, ToolError.DENIED)));

        assertEquals(EvidenceObligationVerifier.Status.BLOCKED, result.status());
        assertTrue(result.earlierReadSucceeded(), "the approved read of .env still happened");
    }

    @Test
    void aListingThatShowsTheTargetSatisfiesExistenceDespiteADeniedOrFailedAttempt(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("a.md"), "a\n");
        var deniedOther = new ToolCallLoop.ToolOutcome("talos.list_dir", "other", false, false, true, "", "denied");
        var listing = new ToolCallLoop.ToolOutcome("talos.list_dir", "docs", true, false, false, "a.md", "");

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("docs/a.md"),
                List.of(deniedOther, listing), root).status());
        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED, Set.of("docs/a.md"),
                List.of(read("docs/a.md", false, false, ToolError.TOOL_ERROR), listing), root).status());
    }

    @Test
    void aSuccessfulIndexReadSatisfiesTheDiagnosisDespiteAnEarlierDeniedIndexRead() {
        var listing = new ToolCallLoop.ToolOutcome("talos.list_dir", ".", true, false, false, "index.html", "");

        assertEquals(EvidenceObligationVerifier.Status.SATISFIED, EvidenceObligationVerifier.verify(
                EvidenceObligation.STATIC_WEB_DIAGNOSIS_REQUIRED,
                Set.of("styles.css"),
                List.of(listing, read("index.html", false, true, ToolError.DENIED), read("index.html", true, false, "")))
                .status());
    }

    @Test
    void aWorkspaceOperationThatChangesTheFileAfterTheReadMakesTheReadStale(@TempDir Path temporary)
            throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.txt"), "notes\n");
        var plan = new dev.talos.runtime.workspace.WorkspaceOperationPlan(
                "c".repeat(64),
                dev.talos.runtime.workspace.WorkspaceOperationPlan.OperationKind.MOVE_PATH,
                List.of(dev.talos.runtime.workspace.WorkspaceOperationPlan.PathEffect.source("notes.txt", false),
                        dev.talos.runtime.workspace.WorkspaceOperationPlan.PathEffect.destination(
                                "archive/notes.txt", false)),
                dev.talos.tools.ToolRiskLevel.WRITE,
                false,
                dev.talos.runtime.workspace.WorkspaceOperationPlan.OverwritePolicy.OVERWRITE,
                false,
                "Move notes.txt",
                "notes.txt -> archive/notes.txt");
        var move = new ToolCallLoop.ToolOutcome("talos.move_path", "archive/notes.txt", true, true, false,
                "moved", "", null, "", plan, null, null, List.of(), "call-move");

        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED, Set.of("notes.txt"),
                List.of(read("notes.txt", true, false, ""), move), root);

        assertEquals(EvidenceObligationVerifier.Status.UNSATISFIED, result.status());
        assertTrue(result.earlierReadSucceeded());
    }
}
