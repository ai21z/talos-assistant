package dev.talos.tools.impl;

import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileVerificationToolResultTest {
    @Test
    void integrityFailureBecomesFailedToolResultWithVerificationMetadata() {
        ToolResult result = FileVerificationToolResult.from(
                "Updated README.md (1 lines, 12 bytes)",
                new ContentVerifier.VerifyResult(
                        VerificationStatus.INTEGRITY_FAIL,
                        "read-back mismatch (wrote 12 chars, read 0 chars)"));

        assertFalse(result.success());
        assertEquals(ToolError.INTERNAL_ERROR, result.error().code());
        assertEquals(VerificationStatus.INTEGRITY_FAIL, result.verification());
        assertTrue(result.errorMessage().contains("read-back mismatch"), result.errorMessage());
        assertTrue(result.errorMessage().contains("[verification: INTEGRITY_FAIL]"), result.errorMessage());
    }

    @Test
    void structuralFailureRemainsSuccessfulWriteWithVerificationFailureSurfaced() {
        ToolResult result = FileVerificationToolResult.from(
                "Updated bad.json (1 lines, 8 bytes)",
                new ContentVerifier.VerifyResult(
                        VerificationStatus.FAIL,
                        "JSON parse failed - bad token"));

        assertTrue(result.success());
        assertEquals(VerificationStatus.FAIL, result.verification());
        assertTrue(result.output().contains("Warning: JSON parse failed"), result.output());
        assertTrue(result.output().contains("[verification: FAIL]"), result.output());
    }

    @Test
    void warningRemainsSuccessfulWriteWithWarningSurfaced() {
        ToolResult result = FileVerificationToolResult.from(
                "Updated index.html (1 lines, 12 bytes)",
                new ContentVerifier.VerifyResult(
                        VerificationStatus.WARN,
                        "HTML issues - unclosed <div>"));

        assertTrue(result.success());
        assertEquals(VerificationStatus.WARN, result.verification());
        assertTrue(result.output().contains("Warning: HTML issues"), result.output());
    }
}
