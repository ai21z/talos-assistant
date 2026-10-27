package dev.talos.tools.impl;

import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;
import dev.talos.tools.VerificationStatus;
import dev.talos.tools.ToolMutationEffects;
import dev.talos.core.io.AtomicFileWriter;
import java.io.IOException;

final class FileVerificationToolResult {
    private FileVerificationToolResult() {}

    static ToolMutationEffects failedWriteEffects(IOException error, String path) {
        if (!(error instanceof AtomicFileWriter.AtomicWriteException atomic)) {
            return ToolMutationEffects.none();
        }
        return switch (atomic.commitState()) {
            case NOT_COMMITTED -> ToolMutationEffects.none();
            case COMMITTED_UNVERIFIED -> ToolMutationEffects.applied(path);
            case COMMIT_STATE_UNKNOWN -> ToolMutationEffects.uncertain(path);
        };
    }

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
