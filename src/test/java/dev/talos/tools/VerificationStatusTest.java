package dev.talos.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link VerificationStatus} enum behavior and
 * the structured verification integration in {@link ToolResult}.
 */
@DisplayName("VerificationStatus")
class VerificationStatusTest {

    @Nested
    @DisplayName("Acceptable semantics")
    class Acceptable {

        @Test void pass_is_acceptable() {
            assertTrue(VerificationStatus.PASS.acceptable());
        }

        @Test void unknown_is_acceptable() {
            assertTrue(VerificationStatus.UNKNOWN.acceptable());
        }

        @Test void warn_is_not_acceptable() {
            assertFalse(VerificationStatus.WARN.acceptable());
        }

        @Test void fail_is_not_acceptable() {
            assertFalse(VerificationStatus.FAIL.acceptable());
        }

        @Test void integrity_fail_is_not_acceptable() {
            assertFalse(VerificationStatus.INTEGRITY_FAIL.acceptable());
        }
    }

    @Nested
    @DisplayName("Labels")
    class Labels {

        @Test void pass_label() {
            assertEquals("verified", VerificationStatus.PASS.label());
        }

        @Test void warn_label() {
            assertEquals("warning", VerificationStatus.WARN.label());
        }

        @Test void fail_label() {
            assertEquals("verification failed", VerificationStatus.FAIL.label());
        }

        @Test void integrity_fail_label() {
            assertEquals("read-back integrity failed", VerificationStatus.INTEGRITY_FAIL.label());
        }

        @Test void unknown_label() {
            assertEquals("unverified", VerificationStatus.UNKNOWN.label());
        }
    }

    @Nested
    @DisplayName("ToolResult integration")
    class ToolResultIntegration {

        @Test
        @DisplayName("ok without verification - verification is null and acceptable")
        void ok_without_verification() {
            ToolResult r = ToolResult.ok("done");
            assertNull(r.verification());
            assertTrue(r.verificationAcceptable());
        }

        @Test
        @DisplayName("ok with PASS verification - acceptable")
        void ok_with_pass() {
            ToolResult r = ToolResult.ok("done", VerificationStatus.PASS);
            assertEquals(VerificationStatus.PASS, r.verification());
            assertTrue(r.verificationAcceptable());
        }

        @Test
        @DisplayName("ok with UNKNOWN verification - acceptable")
        void ok_with_unknown() {
            ToolResult r = ToolResult.ok("done", VerificationStatus.UNKNOWN);
            assertEquals(VerificationStatus.UNKNOWN, r.verification());
            assertTrue(r.verificationAcceptable());
        }

        @Test
        @DisplayName("ok with WARN verification - not acceptable")
        void ok_with_warn() {
            ToolResult r = ToolResult.ok("wrote file. Warning: unclosed div", VerificationStatus.WARN);
            assertEquals(VerificationStatus.WARN, r.verification());
            assertFalse(r.verificationAcceptable());
        }

        @Test
        @DisplayName("ok with FAIL verification - not acceptable")
        void ok_with_fail() {
            ToolResult r = ToolResult.ok("wrote file. Warning: JSON parse failed", VerificationStatus.FAIL);
            assertEquals(VerificationStatus.FAIL, r.verification());
            assertFalse(r.verificationAcceptable());
        }

        @Test
        @DisplayName("fail result - verification is null")
        void fail_has_no_verification() {
            ToolResult r = ToolResult.fail("something broke");
            assertNull(r.verification());
            assertTrue(r.verificationAcceptable(), "Failed results with null verification are 'acceptable' (no verification was attempted)");
        }

        @Test
        @DisplayName("fail result can preserve verification metadata")
        void fail_can_preserve_verification() {
            ToolResult r = ToolResult.fail(
                    ToolError.internal("read-back mismatch"),
                    VerificationStatus.INTEGRITY_FAIL);
            assertFalse(r.success());
            assertEquals(VerificationStatus.INTEGRITY_FAIL, r.verification());
            assertFalse(r.verificationAcceptable());
            assertEquals("read-back mismatch", r.errorMessage());
        }

        @Test
        @DisplayName("ok with verification preserves output text")
        void preserves_output() {
            String msg = "Updated index.html (42 lines). Verified: HTML structure OK.";
            ToolResult r = ToolResult.ok(msg, VerificationStatus.PASS);
            assertEquals(msg, r.output());
            assertTrue(r.success());
        }
    }
}

