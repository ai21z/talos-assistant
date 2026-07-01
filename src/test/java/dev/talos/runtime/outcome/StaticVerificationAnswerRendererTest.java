package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.verification.ClaimResult;
import dev.talos.runtime.verification.EvidenceAuthority;
import dev.talos.runtime.verification.EvidenceCoverage;
import dev.talos.runtime.verification.ProofKind;
import dev.talos.runtime.verification.TargetBinding;
import dev.talos.runtime.verification.TaskVerificationResult;
import dev.talos.runtime.verification.VerificationClaim;
import dev.talos.runtime.verification.VerificationObligation;
import dev.talos.runtime.verification.VerificationReport;
import dev.talos.runtime.verification.VerificationVerdict;
import dev.talos.runtime.verification.VerifierResult;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticVerificationAnswerRendererTest {
    @Test
    void passedAnnotationPreservesExistingWording() {
        TaskVerificationResult result = TaskVerificationResult.passed(
                "Static web verification passed.",
                List.of("HTML links script.js"));

        assertEquals(
                "[Static verification: passed - Static web verification passed.]\n\n",
                StaticVerificationAnswerRenderer.passedAnnotation(result));
    }

    @Test
    void readbackOnlyAnnotationSelectsFileWriteLabelWhenNoWorkspaceOperationSucceeded() {
        TaskVerificationResult result = TaskVerificationResult.readbackOnly(
                "Target/readback checks passed for 1 mutated target(s).",
                List.of("readback"));

        assertEquals(
                "[File write/readback passed. No task-specific verifier was applicable, "
                        + "so task completion was not verified. "
                        + "Target/readback checks passed for 1 mutated target(s).]\n\n",
                StaticVerificationAnswerRenderer.readbackOnlyAnnotation(result, loopResult(
                        mutatingOutcome("talos.write_file", "notes.md", "Wrote notes.md"))));
    }

    @Test
    void readbackOnlyAnnotationSelectsWorkspaceOperationLabelWhenWorkspaceOperationSucceeded() {
        TaskVerificationResult result = TaskVerificationResult.readbackOnly(
                "Target/readback checks passed for 1 mutated target(s).",
                List.of("readback"));

        assertEquals(
                "[Workspace operation/readback passed. No task-specific verifier was applicable, "
                        + "so task completion was not verified. "
                        + "Target/readback checks passed for 1 mutated target(s).]\n\n",
                StaticVerificationAnswerRenderer.readbackOnlyAnnotation(result, loopResult(
                        moveOutcome("notes.md", "archive/notes.md"))));
    }

    @Test
    void readbackOnlyAnnotationDoesNotSayNoVerifierWhenRequiredVerificationWasUnsatisfied() {
        TaskVerificationResult result = TaskVerificationResult.readbackOnly(
                "Static interaction #teaser-button -> #teaser-status. "
                        + "Required interaction verification was not satisfied.",
                List.of("readback"));

        assertEquals(
                "[File write/readback passed. Task-specific verification did not satisfy the requested claim, "
                        + "so task completion was not verified. "
                        + "Static interaction #teaser-button -> #teaser-status. "
                        + "Required interaction verification was not satisfied.]\n\n",
                StaticVerificationAnswerRenderer.readbackOnlyAnnotation(result, loopResult(
                        mutatingOutcome("talos.write_file", "scripts.js", "Wrote scripts.js"))));
    }

    @Test
    void readbackOnlyAnnotationCanRenderUnsatisfiedRequiredClaimDetails() {
        TaskVerificationResult result = TaskVerificationResult.readbackOnly(
                "Static interaction #teaser-button -> #teaser-status. "
                        + "Required interaction verification was not satisfied.",
                List.of("readback"));
        VerificationReport report = VerificationReport.ofClaim(claimResult(
                VerificationVerdict.UNVERIFIED,
                List.of(),
                List.of("scripts.js: click handler for `#teaser-button` does not assign visible text "
                        + "to requested output `#teaser-status` with `textContent` or `innerText`.")));

        String rendered = StaticVerificationAnswerRenderer.readbackOnlyAnnotation(
                result,
                loopResult(mutatingOutcome("talos.write_file", "scripts.js", "Wrote scripts.js")),
                report);

        assertTrue(rendered.contains("Unsatisfied verification detail:"), rendered);
        assertTrue(rendered.contains("does not assign visible text"), rendered);
    }

    @Test
    void readbackOnlyAnnotationRendersDocumentExtractionLimitations() {
        TaskVerificationResult result = TaskVerificationResult.readbackOnly(
                "Document parser extraction evidence verified extracted text only; summary semantics were not verified.",
                List.of("report.pdf: parser extraction succeeded"));
        VerificationReport report = new VerificationReport(
                List.of(),
                List.of(parserExtractionResult(
                        "report.pdf: parser extraction succeeded",
                        "PDF text extraction may not match visual order or layout."),
                        parserExtractionResult(
                                "brief.docx: parser extraction succeeded",
                                "DOCX extraction is text-oriented; layout, comments, tracked changes, and embedded objects may be partial or omitted."),
                        parserExtractionResult(
                                "budget.xlsx: parser extraction succeeded",
                                "XLSX extraction reports visible cells and cached display values; formulas are not recalculated.")),
                List.of("report.pdf: parser extraction succeeded"),
                List.of(),
                List.of(
                        "PDF text extraction may not match visual order or layout.",
                        "DOCX extraction is text-oriented; layout, comments, tracked changes, and embedded objects may be partial or omitted.",
                        "XLSX extraction reports visible cells and cached display values; formulas are not recalculated."));

        String rendered = StaticVerificationAnswerRenderer.readbackOnlyAnnotation(
                result,
                loopResult(),
                report);

        assertTrue(rendered.contains("Document extraction limitations:"), rendered);
        assertTrue(rendered.contains("PDF text extraction may not match visual order"), rendered);
        assertTrue(rendered.contains("layout, comments, tracked changes"), rendered);
        assertTrue(rendered.contains("formulas are not recalculated"), rendered);
    }

    @Test
    void failedAnnotationPreservesExistingPartialPrefixWordingForCompleteTurns() {
        TaskVerificationResult result = TaskVerificationResult.failed(
                "HTML does not link JavaScript file: `scripts.js`",
                List.of(),
                List.of("HTML does not link JavaScript file: `scripts.js`"));

        assertEquals("""
                [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                The requested task is not verified complete. Applied changes below are workspace changes only; unresolved static problems remain.

                Unresolved static verification problems:
                - HTML does not link JavaScript file: `scripts.js`

                """, StaticVerificationAnswerRenderer.failedAnnotation(result));
    }

    @Test
    void failedReplacementPreservesProblemAndAppliedMutationRendering() {
        TaskVerificationResult result = TaskVerificationResult.failed(
                "target mismatch",
                List.of(),
                List.of(
                        "problem one",
                        "problem two",
                        "problem three",
                        "problem four",
                        "problem five",
                        "problem six"));

        assertEquals("""
                [Task incomplete: Static verification failed - target mismatch]

                The requested task is not verified complete. Applied changes, if any, are workspace changes only; unresolved static problems remain.

                Unresolved static verification problems:
                - problem one
                - problem two
                - problem three
                - problem four
                - problem five
                - ... 1 more

                Applied mutating tool calls:
                - notes.md: Wrote notes.md

                The assistant success summary was replaced with this runtime verification result because verification failed.""",
                StaticVerificationAnswerRenderer.failedReplacement(
                        result,
                        loopResult(mutatingOutcome("talos.write_file", "notes.md", "Wrote notes.md"))));
    }

    @Test
    void partialFailedAnnotationPreservesExistingPartialWording() {
        TaskVerificationResult result = TaskVerificationResult.failed(
                "HTML does not link CSS file: `styles.css`",
                List.of(),
                List.of("HTML does not link CSS file: `styles.css`"));

        assertEquals("""
                [Partial verification: static checks failed - HTML does not link CSS file: `styles.css`]

                The turn remains partial. Some changes were applied, but unresolved static problems remain.

                Remaining static verification problems:
                - HTML does not link CSS file: `styles.css`

                """, StaticVerificationAnswerRenderer.partialFailedAnnotation(result));
    }

    @Test
    void unavailableAnnotationPreservesExistingWording() {
        TaskVerificationResult result = TaskVerificationResult.unavailable(
                "Workspace could not be inspected.",
                List.of(),
                List.of("missing workspace"));

        assertEquals(
                "[Static verification incomplete: Workspace could not be inspected.]\n\n",
                StaticVerificationAnswerRenderer.unavailableAnnotation(result));
    }

    @Test
    void changedFilesSummaryUsesWorkspacePlanChangedPathsAndPathHints() {
        String summary = StaticVerificationAnswerRenderer.changedFilesSummary(loopResult(
                mutatingOutcome("talos.write_file", "notes.md", "Wrote notes.md"),
                moveOutcome("notes.md", "archive/notes.md"),
                mutatingOutcome("talos.write_file", "docs\\plan.md", "Wrote docs/plan.md")));

        assertEquals(
                "Updated 3 files: notes.md, archive/notes.md, docs/plan.md.\n\n",
                summary);
    }

    @Test
    void verificationSummaryStillTruncatesAtTwoHundredFortyCharacters() {
        String longSummary = "x".repeat(250);
        String expectedSummary = "x".repeat(237) + "...";

        assertEquals(
                "[Static verification: passed - " + expectedSummary + "]\n\n",
                StaticVerificationAnswerRenderer.passedAnnotation(
                        TaskVerificationResult.passed(longSummary, List.of())));
    }

    private static ToolCallLoop.ToolOutcome mutatingOutcome(String toolName, String pathHint, String summary) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                true,
                true,
                false,
                summary,
                "");
    }

    private static ToolCallLoop.ToolOutcome moveOutcome(String source, String destination) {
        return new ToolCallLoop.ToolOutcome(
                "talos.move_path",
                destination,
                true,
                true,
                false,
                "Moved " + source + " to " + destination,
                "",
                null,
                "",
                WorkspaceOperationPlan.movePath(
                        source,
                        destination,
                        WorkspaceOperationPlan.OverwritePolicy.OVERWRITE));
    }

    private static ToolCallLoop.LoopResult loopResult(ToolCallLoop.ToolOutcome... outcomes) {
        return new ToolCallLoop.LoopResult(
                "model answer",
                1,
                outcomes.length,
                List.of(),
                List.of(),
                0,
                0,
                false,
                outcomes.length,
                List.of(),
                0,
                0,
                0,
                0,
                List.of(outcomes));
    }

    private static ClaimResult claimResult(
            VerificationVerdict verdict,
            List<String> problems,
            List<String> limitations
    ) {
        TargetBinding binding = new TargetBinding("#teaser-button", "#teaser-status", "click");
        VerificationClaim claim = new VerificationClaim(
                "static-web-interaction:#teaser-button->#teaser-status",
                "Static interaction #teaser-button -> #teaser-status.",
                ProofKind.STATIC_INTERACTION_GUARD,
                binding,
                true);
        VerificationObligation obligation = new VerificationObligation(
                claim,
                Set.of(ProofKind.STATIC_INTERACTION_GUARD),
                EvidenceAuthority.AUTHORITATIVE,
                binding);
        return new ClaimResult(
                claim,
                obligation,
                verdict,
                ProofKind.STATIC_INTERACTION_GUARD,
                EvidenceAuthority.AUTHORITATIVE,
                EvidenceCoverage.SCOPED,
                List.of(),
                problems,
                limitations);
    }

    private static VerifierResult parserExtractionResult(String fact, String limitation) {
        return new VerifierResult(
                null,
                ProofKind.PARSER_EXTRACTION,
                EvidenceAuthority.AUTHORITATIVE,
                EvidenceCoverage.SCOPED,
                VerificationVerdict.VERIFIED,
                List.of(fact),
                List.of(),
                List.of(limitation));
    }
}
