package dev.talos.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScopeGuard} - the narrow mutating-target scope guard.
 *
 * <p>Driven by the real Talos CLI transcript failures (Turns 3 and 5 in
 * {@code test-output.txt}): during a clearly web-scoped redesign request
 * on {@code index.html}, the model wrote {@code math_operations.py} and
 * {@code linear_regression.py}. The guard must flag exactly this shape
 * and must <b>not</b> fire for generic requests where the scope is
 * unclear.
 */
@DisplayName("ScopeGuard - narrow mutating-target scope guard")
class ScopeGuardTest {

    // ── looksLikeWebScopedRequest ────────────────────────────────────

    @Nested
    @DisplayName("looksLikeWebScopedRequest")
    class WebScopedRequest {

        @Test
        @DisplayName("null / blank requests → not web-scoped")
        void nullAndBlank() {
            assertFalse(ScopeGuard.looksLikeWebScopedRequest(null));
            assertFalse(ScopeGuard.looksLikeWebScopedRequest(""));
            assertFalse(ScopeGuard.looksLikeWebScopedRequest("   "));
        }

        @Test
        @DisplayName("real-transcript requests → web-scoped")
        void realTranscriptRequests() {
            // Turn 2 / 3
            assertTrue(ScopeGuard.looksLikeWebScopedRequest(
                    "I dont like this site's look and feel... I want to completely change it "
                    + "and make it look like a garden in the spring where almonds starting blooming"));
            // Turn 5
            assertTrue(ScopeGuard.looksLikeWebScopedRequest(
                    "Ok cool! Just made a new BMI calculator site in this index.html and do "
                    + "whatever you think is closer to look like an almond-blossoming spring garden"));
            // Turn 6 (re-ask)
            assertTrue(ScopeGuard.looksLikeWebScopedRequest(
                    "Dude again wrong! Just make a new BMI calculator site in this index.html"));
        }

        @Test
        @DisplayName("generic / non-web requests → not web-scoped")
        void nonWebRequests() {
            assertFalse(ScopeGuard.looksLikeWebScopedRequest(
                    "explain the concept of dependency injection"));
            assertFalse(ScopeGuard.looksLikeWebScopedRequest(
                    "what is this workspace?"));
            assertFalse(ScopeGuard.looksLikeWebScopedRequest(
                    "refactor the ToolCallLoop class"));
        }
    }

    // ── looksLikeOffScopeMutationTarget ──────────────────────────────

    @Nested
    @DisplayName("looksLikeOffScopeMutationTarget")
    class OffScopeTarget {

        @Test
        @DisplayName("Real transcript Turn 3: redesign request → math_operations.py → off-scope")
        void realTranscriptTurn3() {
            String userReq = "I dont like this site's look and feel... I want to completely change it "
                    + "and make it look like a garden in the spring where almonds starting blooming";
            assertTrue(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "math_operations.py"),
                    "Writing a .py file during a web redesign must be flagged off-scope");
        }

        @Test
        @DisplayName("Real transcript Turn 5: BMI calculator site → linear_regression.py → off-scope")
        void realTranscriptTurn5() {
            String userReq = "Ok cool! Just made a new BMI calculator site in this index.html and do "
                    + "whatever you think is closer to look like an almond-blossoming spring garden";
            assertTrue(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "linear_regression.py"),
                    "Writing a .py file during a BMI-calculator-site task must be flagged off-scope");
        }

        @Test
        @DisplayName("On-scope writes (index.html, style.css, script.js) → not flagged")
        void onScopeWritesNotFlagged() {
            String userReq = "redesign this site to look like a spring garden";
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "index.html"));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "style.css"));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "script.js"));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "assets/logo.svg"));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "README.md"));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "package.json"));
        }

        @Test
        @DisplayName("Non-web-scoped request → never flagged regardless of target")
        void nonWebRequestNeverFlagged() {
            String userReq = "write a linear regression example in python";
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "linear_regression.py"),
                    "Python write during an explicitly-python request must not be flagged");
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "math_operations.py"));
        }

        @Test
        @DisplayName("Null/blank path or request → safe default (not flagged)")
        void safeDefaults() {
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget("redesign this site", null));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget("redesign this site", ""));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(null, "math_operations.py"));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget("", "math_operations.py"));
        }

        @Test
        @DisplayName("Extension-less path (Makefile, Dockerfile) → not flagged")
        void extensionlessPathNotFlagged() {
            String userReq = "redesign this site";
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "Makefile"));
            assertFalse(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "Dockerfile"));
        }

        @Test
        @DisplayName("Directory-prefixed off-scope path is still detected")
        void subdirOffScopePath() {
            String userReq = "redesign the page";
            assertTrue(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "src/util/math_ops.py"),
                    "Basename extension should be inspected, not the full path");
            assertTrue(ScopeGuard.looksLikeOffScopeMutationTarget(userReq, "src\\util\\math_ops.py"),
                    "Windows path separators must be handled");
        }
    }

    // ── warningMessage ──────────────────────────────────────────────

    @Test
    @DisplayName("warningMessage contains both the target path and an anchor from the user request")
    void warningMessageIncludesPathAndAnchor() {
        String msg = ScopeGuard.warningMessage(
                "redesign this site as a spring garden", "math_operations.py");
        assertTrue(msg.contains("math_operations.py"),
                "warning must name the off-scope target: " + msg);
        assertTrue(msg.contains("redesign this site"),
                "warning must include a snippet of the user's request so it is grounded: " + msg);
    }

    @Test
    @DisplayName("warningMessage truncates extremely long user requests")
    void warningMessageTruncatesLongRequest() {
        String longReq = "redesign this site " + "x".repeat(500);
        String msg = ScopeGuard.warningMessage(longReq, "math.py");
        assertTrue(msg.length() < longReq.length() + 100,
                "warning message must truncate pathologically long user requests");
        assertTrue(msg.contains("…"), "truncated message should end with ellipsis marker");
    }
}

