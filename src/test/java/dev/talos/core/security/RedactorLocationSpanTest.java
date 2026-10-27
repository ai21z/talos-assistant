package dev.talos.core.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The low-level field range is synthetic. Admission is exercised by runtime tests. */
class RedactorLocationSpanTest {
    private static final String LOCATION = "C:\\work\\project";

    @Test void anAdmittedLocationWithSpacesRemainsUsefulWithoutAdmittingAdjacentPaths() {
        String location = "C:\\Work Area\\Project";
        String answer = "Workspace " + location + "\nPrivate C:\\other\\private";
        assertEquals("Workspace " + location + "\nPrivate [path]", project(new Redactor(), answer, 10, location));
    }

    @Test void emptyLinesRetainTheirOriginalCustomRegexBehavior() {
        assertEquals("\n", configured("^").redactBlock("\n"));
    }

    @Test void onlyTheExactAdmittedMatchSurvives() {
        String answer = "Workspace: " + LOCATION + "\nProvider repeats " + LOCATION;
        assertEquals("Workspace: " + LOCATION + "\nProvider repeats [path]",
                project(new Redactor(), answer, answer.indexOf(LOCATION), LOCATION));
    }

    @Test void customSecretSpanningTheFieldBoundaryIsMaskedAsOneUnit() {
        String prefix = "private-label ";
        String answer = prefix + LOCATION + " adjacent prose";
        Redactor redactor = configured(Pattern.quote(prefix + LOCATION));
        assertEquals("[secret] adjacent prose", project(redactor, answer, prefix.length(), LOCATION));
    }

    @Test void customSecretInsideTheFieldRevokesThePathException() {
        String secret = "PRIVATE_ABCDEFGH";
        String location = "C:\\work\\" + secret + "\\project";
        String projected = project(configured(Pattern.quote(secret)), "Workspace " + location, 10, location);
        assertFalse(projected.contains(secret));
        assertFalse(projected.contains("C:\\work"));
        assertTrue(projected.contains("[path]"));
    }

    @Test void ordinarySecretRulesStillApplyAcrossAnAssembledSegmentBoundary() {
        String location = "C:\\work\\sk-ABCDEFGHIJKLmnop1234\\project";
        String answer = "Workspace " + location;
        String projected = project(new Redactor(), answer, 10, location);
        assertFalse(projected.contains("sk-ABCDEFGHIJKLmnop1234"));
        assertFalse(projected.contains("C:\\work"));
    }

    @Test void ipMaskingInsideTheFieldRevokesItsException() {
        String location = "C:\\work\\10.20.30.40\\project";
        String projected = project(new Redactor(), "Workspace " + location, 10, location);
        assertFalse(projected.contains("10.20.30.40"));
        assertFalse(projected.contains("C:\\work"));
    }

    @Test void strongerMasksBeforeTheFieldMoveItsExactRange() {
        String prefix = "password=ABCDEFGHIJKLMNOP ";
        assertEquals("password=[secret] " + LOCATION,
                project(new Redactor(), prefix + LOCATION, prefix.length(), LOCATION));
    }

    @Test void controlSanitationRevokesTheExceptionInsteadOfGuessingAnOffset() {
        String prefix = "\u001b[31mWorkspace ";
        assertEquals("Workspace [path]", project(new Redactor(), prefix + LOCATION, prefix.length(), LOCATION));
    }

    @Test void wrongRangeAndChildPathCannotInheritAnException() {
        String answer = "Workspace " + LOCATION + "\\private";
        assertEquals(new Redactor().redactBlock(answer),
                project(new Redactor(), answer, 10, LOCATION));
        assertEquals(new Redactor().redactBlock(answer),
                project(new Redactor(), answer, 9, LOCATION));
    }

    @Test void blockTerminatorsAndConfiguredLineSemanticsArePreserved() {
        String answer = "first\r\nWorkspace " + LOCATION + "\rthird\nlast";
        assertEquals(answer, project(new Redactor(), answer, answer.indexOf(LOCATION), LOCATION));
        Redactor multiline = configured("(?s)first.*last");
        assertEquals(multiline.redactBlock(answer),
                multiline.redactBlockWithValidatedLocation(answer, -1, -1, ""));
    }

    @Test void fingerprintIdentifiesEffectivePolicyOnly() {
        assertEquals(new Redactor().policyFingerprint(), new Redactor(Map.of("unrelated", true)).policyFingerprint());
        assertNotEquals(new Redactor().policyFingerprint(), configured("PRIVATE_[A-Z]+").policyFingerprint());
        assertNotEquals(new Redactor().policyFingerprint(),
                new Redactor(Map.of("redact", Map.of("ips", false))).policyFingerprint());
        assertTrue(new Redactor().policyFingerprint().matches("[0-9a-f]{64}"));
    }

    private static String project(Redactor redactor, String answer, int offset, String location) {
        return redactor.redactBlockWithValidatedLocation(answer, offset, offset + location.length(), location);
    }

    private static Redactor configured(String expression) {
        return new Redactor(Map.of("redact", Map.of("secrets", List.of(expression))));
    }
}
