package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T762: evidence-derived grounding for read-only review/proposal answers. */
class ReadOnlyProposalGroundingGuardTest {

    private static final String REVIEW_REQUEST =
            "Please review README.md and propose concise improvements, but do not edit any files yet.";

    // ── audit-fixture compatibility: fixture filenames still flag ───────

    @Test
    void unevidencedFixtureFilenamesStillTriggerTheWarning() {
        String answer = """
                Add a file overview:
                - `.env`: configuration for environment variables.
                - `report.docx`: report document.
                - `script.js`: JavaScript logic.
                """;

        String result = apply(answer, readmeOnlyEvidence());

        assertTrue(result.startsWith(ReadOnlyProposalGroundingGuard.GROUNDED_PROPOSAL_WARNING), result);
        assertTrue(result.contains("report document"), result);
    }

    // ── the new capability: NON-fixture filenames flag too ─────────────

    @Test
    void unevidencedNonFixtureFilenameTriggersTheWarning() {
        // Fails on the pre-T762 design: main.py and data.csv were not in the
        // hardcoded audit-fixture marker list, so claims about them went
        // unchecked in real workspaces (teaching-to-the-test).
        String answer = "The README should document that main.py loads data.csv at startup.";

        String result = apply(answer, readmeOnlyEvidence());

        assertTrue(result.startsWith(ReadOnlyProposalGroundingGuard.GROUNDED_PROPOSAL_WARNING), result);
    }

    @Test
    void evidencedFilenameMentionsAreClean() {
        String answer = "The README documents that main.py loads data.csv at startup; keep that section.";
        ToolCallLoop.LoopResult evidence = loopResult(
                List.of("README.md"),
                "# Fixture\nUsage: main.py loads data.csv at startup.\n");

        String result = apply(answer, evidence);

        assertEquals(answer, result);
    }

    @Test
    void reviewedFilesOwnNameCountsAsEvidencedViaReadPaths() {
        // Tool-result text does not echo target paths (read output is
        // numbered lines), so the reviewed file's own name must be evidenced
        // by the read-path ledger, not its content.
        String answer = "In README.md, add a short purpose sentence under the title.";

        String result = apply(answer, readmeOnlyEvidence());

        assertEquals(answer, result);
    }

    // ── prose false-positive suite ──────────────────────────────────────

    @Test
    void prosePatternsDoNotFalsePositive() {
        // node/python prose is deliberately absent here: those words are
        // internal-content markers with no conditional exemption (unchanged
        // pre-existing behavior, not a T762 concern).
        for (String answer : List.of(
                "Add version v1.2.3 notes, e.g. a short changelog entry.",
                "Link the project homepage at example.com for context.",
                "For example, if this project uses gradle, mention the wrapper version.")) {
            assertEquals(answer, apply(answer, readmeOnlyEvidence()), answer);
        }
    }

    @Test
    void nonProposalTurnIsUntouched() {
        TaskContract contract = TaskContractResolver.fromUserRequest("What does the README say?");
        String answer = "It mentions main.py and data.csv.";

        String result = ReadOnlyProposalGroundingGuard.apply(
                answer, contract, "What does the README say?", readmeOnlyEvidence());

        assertEquals(answer, result);
    }

    // ── command/internal markers and .env exclusion (relocated logic) ───

    @Test
    void unevidencedCommandsTriggerTheWarning() {
        String answer = "1. Install dependencies using `npm install`.\n2. Run the audit with `node script.js`.";

        String result = apply(answer, readmeOnlyEvidence());

        assertTrue(result.startsWith(ReadOnlyProposalGroundingGuard.GROUNDED_PROPOSAL_WARNING), result);
    }

    @Test
    void excludedEnvLinesAreStrippedAndWarned() {
        String answer = "Add usage instructions.\nAdd a section documenting `.env` variables.\nKeep the title.";
        String request = "I do not want the .env, I want README.md. Please review README.md and propose improvements.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);

        String result = ReadOnlyProposalGroundingGuard.apply(
                answer, contract, request, readmeOnlyEvidence());

        assertTrue(result.startsWith(ReadOnlyProposalGroundingGuard.GROUNDED_PROPOSAL_WARNING), result);
        assertFalse(result.contains("documenting `.env` variables"), result);
        assertTrue(result.contains("Add usage instructions"), result);
        assertTrue(result.contains("Keep the title"), result);
    }

    @Test
    void blankAnswerIsUntouched() {
        assertEquals("", apply("", readmeOnlyEvidence()));
        assertNull(apply(null, readmeOnlyEvidence()));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String apply(String answer, ToolCallLoop.LoopResult loopResult) {
        TaskContract contract = TaskContractResolver.fromUserRequest(REVIEW_REQUEST);
        return ReadOnlyProposalGroundingGuard.apply(answer, contract, REVIEW_REQUEST, loopResult);
    }

    private static ToolCallLoop.LoopResult readmeOnlyEvidence() {
        return loopResult(
                List.of("README.md"),
                "# Focused Audit Fixture\nThis workspace checks response grounding.\n");
    }

    private static ToolCallLoop.LoopResult loopResult(List<String> readPaths, String toolResultBody) {
        return new ToolCallLoop.LoopResult(
                "model answer",
                1,
                1,
                List.of("talos.read_file"),
                List.of(ChatMessage.assistant(
                        "[tool_result: talos.read_file]\n" + toolResultBody + "[/tool_result]")),
                0,
                0,
                false,
                0,
                readPaths,
                0,
                0,
                0,
                0,
                List.of());
    }
}
