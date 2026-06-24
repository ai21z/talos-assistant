package dev.talos.tools.impl;

import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import dev.talos.tools.VerificationStatus;

final class FileVerificationToolResult {
    private FileVerificationToolResult() {}

    static ToolResult from(String base, ContentVerifier.VerifyResult verification) {
        VerificationStatus status = verification.status();
        String statusTag = "[verification: " + status.name() + "]";
        if (status == VerificationStatus.INTEGRITY_FAIL) {
            return ToolResult.fail(
                    ToolError.internal("File verification failed: "
                            + verification.summary() + ". " + statusTag),
                    status);
        }
        if (verification.ok()) {
            return ToolResult.ok(base + ". Verified: " + verification.summary() + ". " + statusTag, status);
        }
        return ToolResult.ok(base + ". Warning: " + verification.summary() + ". " + statusTag, status);
    }
}
