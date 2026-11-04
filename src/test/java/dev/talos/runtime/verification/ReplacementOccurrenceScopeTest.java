package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The occurrence scope of a requested replacement is a pure function of the prior and final content (P17). */
class ReplacementOccurrenceScopeTest {
    private static final String PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=8080\n";

    @Test
    void theSessionEditIsARepeatedPriorWithOneOccurrenceReplaced() {
        ReplacementOccurrenceScope scope = ReplacementOccurrenceScope.of(
                PRIOR, "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n", "8080", "9000");

        assertEquals(ReplacementOccurrenceScope.Kind.REPEATED, scope.kind());
        assertEquals(2, scope.priorOccurrences());
        assertEquals(1, scope.remainingOccurrences());
        assertTrue(scope.landed());
        assertEquals(ReplacementOccurrenceScope.Substitution.YES, scope.substitution());
    }

    @Test
    void replacingEveryOccurrenceIsASubstitutionWithNoneRemaining() {
        ReplacementOccurrenceScope scope = ReplacementOccurrenceScope.of(
                PRIOR, "[SERVER]\nport=9000\n[CLIENT]\nport=9000\n", "8080", "9000");

        assertEquals(0, scope.remainingOccurrences());
        assertTrue(scope.landed());
        assertTrue(scope.substitutionOnly());
    }

    @Test
    void anotherChangedLineIsNotASubstitution() {
        ReplacementOccurrenceScope scope = ReplacementOccurrenceScope.of(
                PRIOR, "[SERVER]\nport=9000\n[CLIENT]\nport=8080\nhost=x\n", "8080", "9000");

        assertTrue(scope.landed());
        assertEquals(ReplacementOccurrenceScope.Substitution.NO, scope.substitution());
    }

    @Test
    void noReplacedOccurrenceDidNotLand() {
        ReplacementOccurrenceScope unchanged = ReplacementOccurrenceScope.of(PRIOR, PRIOR, "8080", "9000");
        ReplacementOccurrenceScope added = ReplacementOccurrenceScope.of(PRIOR, PRIOR + "9000\n", "8080", "9000");

        assertFalse(unchanged.landed());
        assertFalse(unchanged.substitutionOnly());
        assertFalse(added.landed());
        assertFalse(added.substitutionOnly());
    }

    @Test
    void aWrongValueBesidePreExistingNewTextDidNotLand() {
        // Review finding F1(a): the new text was already present elsewhere, and the edited occurrence got another value.
        String prior = PRIOR + "[ADMIN]\nport=9000\n";
        ReplacementOccurrenceScope scope = ReplacementOccurrenceScope.of(
                prior, "[SERVER]\nport=9001\n[CLIENT]\nport=8080\n[ADMIN]\nport=9000\n", "8080", "9000");

        assertEquals(ReplacementOccurrenceScope.Kind.REPEATED, scope.kind());
        assertEquals(1, scope.remainingOccurrences());
        assertFalse(scope.landed());
        assertEquals(ReplacementOccurrenceScope.Substitution.NO, scope.substitution());
    }

    @Test
    void oldTextInsidePriorNewTextIsNotAnOccurrence() {
        // Review finding F1(b): the hello inside the existing hello atlas is not a prior occurrence.
        String prior = "a=\"hello atlas\"; b=\"hello\"; c=1";
        ReplacementOccurrenceScope untouched = ReplacementOccurrenceScope.of(
                prior, "a=\"hello atlas\"; b=\"hello\"; c=2", "hello", "hello atlas");
        ReplacementOccurrenceScope scoped = ReplacementOccurrenceScope.of(
                prior, "a=\"hello atlas\"; b=\"hello atlas\"; c=1", "hello", "hello atlas");

        assertEquals(ReplacementOccurrenceScope.Kind.UNIQUE, untouched.kind());
        assertEquals(1, untouched.priorOccurrences());
        assertFalse(untouched.landed());
        assertEquals(ReplacementOccurrenceScope.Kind.UNIQUE, scoped.kind());
        assertTrue(scoped.landed());
    }

    @Test
    void aNewTextThatContainsTheOldTextIsDecidedExactly() {
        String prior = "String a = \"hello\";\nreturn \"hello\";\n";
        ReplacementOccurrenceScope second = ReplacementOccurrenceScope.of(
                prior, "String a = \"hello\";\nreturn \"hello atlas\";\n", "hello", "hello atlas");
        ReplacementOccurrenceScope both = ReplacementOccurrenceScope.of(
                prior, "String a = \"hello atlas\";\nreturn \"hello atlas\";\n", "hello", "hello atlas");
        ReplacementOccurrenceScope doubled = ReplacementOccurrenceScope.of(
                prior, "String a = \"hello\";\nreturn \"hello atlas atlas\";\n", "hello", "hello atlas");

        assertEquals(2, second.priorOccurrences());
        assertEquals(1, second.remainingOccurrences());
        assertTrue(second.landed());
        assertTrue(second.substitutionOnly());
        assertEquals(0, both.remainingOccurrences());
        assertTrue(both.substitutionOnly());
        assertFalse(doubled.substitutionOnly());
    }

    @Test
    void anOldTextThatContainsTheNewTextLandsWhenTrimmed() {
        ReplacementOccurrenceScope scope = ReplacementOccurrenceScope.of(
                "final int x;\nfinal int x;\n", "int x;\nfinal int x;\n", "final int x", "int x");

        assertEquals(ReplacementOccurrenceScope.Kind.REPEATED, scope.kind());
        assertTrue(scope.landed());
        assertTrue(scope.substitutionOnly());
    }

    @Test
    void aShorterNewTextShiftsLaterOccurrencesExactly() {
        ReplacementOccurrenceScope scope = ReplacementOccurrenceScope.of(
                "aXXXb aXXXb aXXXb", "aYb aXXXb aYb", "XXX", "Y");

        assertEquals(3, scope.priorOccurrences());
        assertEquals(1, scope.remainingOccurrences());
        assertTrue(scope.substitutionOnly());
        assertFalse(ReplacementOccurrenceScope.of("aXXXb aXXXb aXXXb", "aYb aXXXb aYbZ", "XXX", "Y")
                .substitutionOnly());
    }

    @Test
    void lineEndingsAndOneTerminalNewlineAreNormalizedAsInTheOneOccurrenceRule() {
        ReplacementOccurrenceScope crlf = ReplacementOccurrenceScope.of(
                PRIOR.replace("\n", "\r\n"), "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n", "8080", "9000");
        ReplacementOccurrenceScope terminal = ReplacementOccurrenceScope.of(
                PRIOR, "[SERVER]\nport=9000\n[CLIENT]\nport=8080", "8080", "9000");

        assertTrue(crlf.substitutionOnly());
        assertTrue(terminal.substitutionOnly());
    }

    @Test
    void theOtherScopesKeepTheirKindsAndAreNotSearched() {
        assertEquals(ReplacementOccurrenceScope.Kind.UNIQUE,
                ReplacementOccurrenceScope.of("Release 2023\n", "Release 2024\n", "2023", "2024").kind());
        assertEquals(ReplacementOccurrenceScope.Kind.ABSENT_BEFORE,
                ReplacementOccurrenceScope.of("Release 2022\n", "Release 2024\n", "2023", "2024").kind());
        ReplacementOccurrenceScope unknown = ReplacementOccurrenceScope.of(null, "a 2023\n", "2023", "2024");
        assertEquals(ReplacementOccurrenceScope.Kind.PRIOR_UNKNOWN, unknown.kind());
        assertEquals(1, unknown.remainingOccurrences());
        assertEquals(ReplacementOccurrenceScope.Substitution.NOT_EVALUATED, unknown.substitution());
        assertEquals(ReplacementOccurrenceScope.Substitution.NOT_EVALUATED,
                ReplacementOccurrenceScope.of("Release 2023\n", "Release 2024\n", "2023", "2024").substitution());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void manyOccurrencesWithOneReplacedAreDecidedInLinearWork() {
        // Review finding F4: a single substitution path among 300,000 occurrences stays within the window.
        String prior = "a,b,c\n" + "1,2,3\n".repeat(150_000);
        String after = "a;b,c\n" + "1,2,3\n".repeat(150_000);

        ReplacementOccurrenceScope scope = ReplacementOccurrenceScope.of(prior, after, ",", ";");

        assertEquals(300_002, scope.priorOccurrences());
        assertTrue(scope.landed());
        assertEquals(ReplacementOccurrenceScope.Substitution.YES, scope.substitution());
    }

    @Test
    void aSearchThatExceedsItsBoundIsUndecidedNotRefuted() {
        // Review finding F7: a bound that stops the search neither proves nor refutes the substitution.
        String prior = "a,b,c\n" + "1,2,3\n".repeat(100);
        String after = "a;b,c\n" + "1,2,3\n".repeat(100);

        assertEquals(ReplacementOccurrenceScope.Substitution.YES,
                ReplacementOccurrenceScope.substitution(prior, after, ",", ";"));
        assertEquals(ReplacementOccurrenceScope.Substitution.UNDECIDED,
                ReplacementOccurrenceScope.substitution(prior, after, ",", ";", 50));
    }

    @Test
    void theStatedSentenceIsBuiltOnlyFromTheTypedRecord() {
        assertEquals(
                "Requested replacement not verified: config.ini held the old text 2 times before the change "
                        + "and 1 remains.",
                TaskVerificationOutcomeSelector.notAssertedSentence(
                        new ReplacementOccurrenceScope.NotAsserted("config.ini", 2, 1)));
        assertEquals(
                "Requested replacement not verified: notes.md held the old text 3 times before the change "
                        + "and none remain.",
                TaskVerificationOutcomeSelector.notAssertedSentence(
                        new ReplacementOccurrenceScope.NotAsserted("notes.md", 3, 0)));
        assertEquals(
                "Requested replacement not verified: notes.md held the old text 3 times before the change "
                        + "and 2 remain.",
                TaskVerificationOutcomeSelector.notAssertedSentence(
                        new ReplacementOccurrenceScope.NotAsserted("notes.md", 3, 2)));
    }
}
