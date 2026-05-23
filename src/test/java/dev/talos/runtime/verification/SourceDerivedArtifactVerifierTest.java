package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceDerivedArtifactVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void multiSourceTextSummaryPassesWhenEachReadableSourceContributesDistinctiveFact() throws Exception {
        Files.writeString(workspace.resolve("alpha.txt"), """
                Alpha source says orbital zinc inventory depends on cobalt ledger entries.
                """);
        Files.writeString(workspace.resolve("beta.txt"), """
                Beta source says amber kelp forecast depends on violet turbine output.
                """);
        Files.writeString(workspace.resolve("summary.md"), """
                - Orbital zinc inventory depends on cobalt ledger entries.
                - Amber kelp forecast depends on violet turbine output.
                """);

        SourceDerivedArtifactVerifier.Result result = SourceDerivedArtifactVerifier.verify(
                multiSourceSummaryContract(),
                workspace);

        assertTrue(result.required());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("summary.md: source-derived artifact includes evidence from")
                                && f.contains("alpha.txt")
                                && f.contains("beta.txt")),
                result.facts().toString());
    }

    @Test
    void officeDocumentSummaryPassesWhenExtractableSourcesContributeDistinctiveFact() throws Exception {
        copyDocumentFixture("canonical-text.pdf", "report.pdf");
        copyDocumentFixture("canonical-report.docx", "report.docx");
        copyDocumentFixture("canonical-workbook.xlsx", "budget.xlsx");
        Files.writeString(workspace.resolve("office-summary.md"), """
                - The PDF evidence includes CANONICAL_PDF_TEXT_ALPHA.
                - The Word document evidence includes CANONICAL_DOCX_TEXT_BETA.
                - The workbook evidence includes CANONICAL_XLSX_TEXT_GAMMA.
                """);

        SourceDerivedArtifactVerifier.Result result = SourceDerivedArtifactVerifier.verify(
                officeDocumentSummaryContract(),
                workspace);

        assertTrue(result.required());
        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.facts().stream()
                        .anyMatch(f -> f.contains("office-summary.md: source-derived artifact includes evidence from")
                                && f.contains("report.pdf")
                                && f.contains("report.docx")
                                && f.contains("budget.xlsx")),
                result.facts().toString());
    }

    @Test
    void hallucinatedOfficeSummaryFailsWithoutLeakingExactMissingMarkers() throws Exception {
        copyDocumentFixture("canonical-text.pdf", "board-brief.pdf");
        copyDocumentFixture("canonical-report.docx", "client-notes.docx");
        copyDocumentFixture("canonical-workbook.xlsx", "revenue.xlsx");
        Files.writeString(workspace.resolve("office-summary.md"), """
                # Office Summary

                ## 1. Board Brief
                - Evidence Phrase: "Strategic Vision: Expand into new markets"

                ## 2. Client Notes
                - Evidence Phrase: "Client feedback indicates faster support response times"

                ## 3. Revenue Data
                - Evidence Phrase: "Total revenue for Q1 2026 reached $4.2 million"
                """);

        SourceDerivedArtifactVerifier.Result result = SourceDerivedArtifactVerifier.verify(
                hallucinatedOfficeSummaryContract(),
                workspace);

        assertTrue(result.required());
        assertTrue(result.problems().stream()
                        .anyMatch(p -> p.contains("source-derived summary includes unsupported distinctive terms")),
                result.problems().toString());
        assertFalse(result.problems().stream().anyMatch(p -> p.contains("CANONICAL_PDF_TEXT_ALPHA")),
                result.problems().toString());
    }

    private static TaskContract multiSourceSummaryContract() {
        return new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("summary.md"),
                Set.of("alpha.txt", "beta.txt"),
                Set.of(),
                "Summarize alpha.txt and beta.txt into summary.md.",
                "test-multi-source-summary");
    }

    private static TaskContract officeDocumentSummaryContract() {
        return new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("office-summary.md"),
                Set.of("report.pdf", "report.docx", "budget.xlsx"),
                Set.of(),
                "Summarize report.pdf, report.docx, and budget.xlsx into office-summary.md.",
                "test-office-document-summary");
    }

    private static TaskContract hallucinatedOfficeSummaryContract() {
        return new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("office-summary.md"),
                Set.of("board-brief.pdf", "client-notes.docx", "revenue.xlsx"),
                Set.of(),
                "Summarize board-brief.pdf, client-notes.docx, and revenue.xlsx into office-summary.md.",
                "test-hallucinated-office-document-summary");
    }

    private void copyDocumentFixture(String fixtureName, String targetName) throws Exception {
        Files.copy(documentFixture(fixtureName), workspace.resolve(targetName), StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path documentFixture(String name) throws URISyntaxException {
        URL url = SourceDerivedArtifactVerifierTest.class.getResource("/document-fixtures/" + name);
        assertNotNull(url, "missing checked-in fixture: " + name);
        return Path.of(url.toURI());
    }
}
