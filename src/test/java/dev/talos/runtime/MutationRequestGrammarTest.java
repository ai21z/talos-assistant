package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T1014: one canonical mutation-verb inventory and one deterministic
 * request-shape classifier. Shapes, not example phrases, decide: an
 * assistant-directed clause or a concrete scope anchor classifies as
 * mutation; reported speech, advisory framing, questions, and API
 * references stay read-only regardless of the identifiers they mention.
 */
class MutationRequestGrammarTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Normalize notes.txt line endings to LF.",
            "Normalise notes.txt line endings to LF.",
            "Convert notes.txt to CRLF line endings.",
            "Please normalize notes.txt line endings to LF.",
            "Could you convert notes.txt to Windows line endings?",
    })
    void explicitTextFormatRequestsExecute(String request) {
        var shape = MutationIntent.classifyShape(request);
        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision(),
                request + " -> " + shape);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "The guide says to normalize notes.txt line endings to LF.",
            "The tutorial shows how to convert notes.txt to CRLF line endings.",
            "How do I normalize notes.txt line endings to LF?",
    })
    void reportedAndInstructionalTextFormatMentionsStayReadOnly(String request) {
        var shape = MutationIntent.classifyShape(request);
        assertEquals(MutationIntent.Decision.READ_ONLY, shape.decision(),
                request + " -> " + shape);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Improve only styles.css. Do not create extra files. Do not modify index.html.",
            "Improve only styles.css. Do not create any extra file. Do not modify index.html.",
    })
    void extraFileConstraintsLimitScopeWithoutCancellingMutation(String request) {
        var shape = MutationIntent.classifyShape(request);
        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, shape.decision(),
                request + " -> " + shape);
    }

    // ── the live installed false negative: scoped prefix ────────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "In notes.md, replace beta with BETA.",
            "In notes.md, replace beta with BETA. Change nothing else.",
            "Inside src/app.js, rename reset to resetAll.",
            "Within docs/guide.md, change the title to Quick Start.",
            "In styles.css, update the background color to #222.",
            "in notes.md, replace beta with BETA.",
            "In src\\app\\main.py, replace DEBUG with INFO.",
    })
    void scopedPrefixMutationRequestsExecute(String request) {
        assertTrue(MutationIntent.isExplicitMutationClassificationReason(
                        MutationIntent.classificationReason(request)),
                request + " -> " + MutationIntent.classificationReason(request));
    }

    // ── the live installed false positives: reported/advisory clauses ───

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "The migration guide says, rename oldName to newName.",
            "The docs recommend, rename user_id to account_id.",
            "For readability, rename oldValue to newValue.",
            "The tutorial suggests, rename fooBar to bazQux.",
            "According to the style guide, rename my_var to myVar.",
            "In general, rename variables to improve readability.",
            "As a rule, rename methods to match their behavior.",
    })
    void reportedAndAdvisoryRenameClausesStayReadOnly(String request) {
        assertFalse(MutationIntent.isExplicitMutationClassificationReason(
                        MutationIntent.classificationReason(request)),
                request + " -> " + MutationIntent.classificationReason(request));
    }

    // ── controls that must keep their current classification ────────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Replace beta with BETA in notes.md.",
            "Rename reset to resetAll in src/app.js.",
            "Please rename oldValue to newValue in config.yaml.",
            "Read app.js, then rename reset to resetAll.",
            "Could you replace beta with BETA in notes.md?",
            "I need you to update the version in gradle.properties.",
    })
    void assistantDirectedMutationRequestsExecute(String request) {
        assertTrue(MutationIntent.isExplicitMutationClassificationReason(
                        MutationIntent.classificationReason(request)),
                request + " -> " + MutationIntent.classificationReason(request));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "How do I rename a variable in Java?",
            "What does os.rename(src, dst) do?",
            "os.rename moves a file to a new name.",
            "The example shows edit_file replacing old_string with new_string.",
            "Historically, we renamed util.js to helpers.js last year.",
            "Should I rename the module before the release?",
    })
    void questionsQuotationsAndHistoryStayReadOnly(String request) {
        assertFalse(MutationIntent.isExplicitMutationClassificationReason(
                        MutationIntent.classificationReason(request)),
                request + " -> " + MutationIntent.classificationReason(request));
    }

    // ── scoped prefixes over descriptive prose stay read-only ───────────

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "In app.js, replace is called twice.",
            "In build.gradle, apply is deprecated in favor of the plugins block.",
            "In utils.py, update is imported from datetime.",
            "In app.js, replace is called twice, is that a problem?",
            "In README.md, update instructions are outdated, right?",
            "In notes.md, copy of the header appears twice?",
            "In src/main.rs, move semantics confuse me, can you explain?",
    })
    void scopedPrefixesOverDescriptiveProseStayReadOnly(String request) {
        assertFalse(MutationIntent.isExplicitMutationClassificationReason(
                        MutationIntent.classificationReason(request)),
                request + " -> " + MutationIntent.classificationReason(request));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "In a large module, rename variables carefully.",
            "In any file, delete trailing whitespace before committing.",
            "In a legacy class, refactor slowly.",
            "In a shared component, remove dead code only after checking usages.",
    })
    void indefinitelyScopedGuidanceStaysReadOnly(String request) {
        assertFalse(MutationIntent.isExplicitMutationClassificationReason(
                        MutationIntent.classificationReason(request)),
                request + " -> " + MutationIntent.classificationReason(request));
    }

    @Test
    void definiteTypedScopeImperativesStillExecute() {
        String request = "In the tracker module, rename shippedCount to shippedTotal.";
        assertTrue(MutationIntent.isExplicitMutationClassificationReason(
                        MutationIntent.classificationReason(request)),
                request + " -> " + MutationIntent.classificationReason(request));
    }

    // ── typed classifier surface ─────────────────────────────────────────
    // (added in the green phase; the behavioral shapes above are the
    // red-first evidence because a new API cannot fail behaviorally
    // before it compiles)

    @Test
    void typedClassifierExposesDecisionReasonAndEvidenceSpan() {
        var mutation = MutationIntent.classifyShape("In notes.md, replace beta with BETA.");
        assertEquals(MutationIntent.Decision.EXECUTE_MUTATION, mutation.decision());
        assertTrue(mutation.reason().startsWith("explicit-"), mutation.reason());
        assertFalse(mutation.evidenceSpan().isBlank(), "the matched span must be recorded for trace");

        var readOnly = MutationIntent.classifyShape("For readability, rename oldValue to newValue.");
        assertEquals(MutationIntent.Decision.READ_ONLY, readOnly.decision());

        var ambiguous = MutationIntent.classifyShape(
                "Rename reset to resetAll in app.js, but do not change any files yet.");
        assertEquals(MutationIntent.Decision.AMBIGUOUS, ambiguous.decision());
        assertFalse(MutationIntent.isExplicitMutationClassificationReason(ambiguous.reason()),
                "ambiguity must never open the mutating tool surface");
    }

    @Test
    void reasonStringsStayStableForDownstreamConsumers() {
        assertEquals("explicit-request-pattern",
                MutationIntent.classificationReason("Replace beta with BETA in notes.md."));
        assertEquals("non-mutating",
                MutationIntent.classificationReason("What is in this workspace?"));
    }
}
