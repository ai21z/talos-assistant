package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void readTargetExplicitFailureSatisfiesRequiredTarget() {
        var result = EvidenceObligationVerifier.verify(
                EvidenceObligation.READ_TARGET_REQUIRED,
                Set.of("README.md"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "README.md", false, false, false,
                        "", "README.md was not found.", null, ToolError.NOT_FOUND)));

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
}
