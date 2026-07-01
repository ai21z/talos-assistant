package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionFailureClassifierTest {
    @Test
    void deniedMutatingResultIsDeniedAndMutatingDenied() {
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "new"));

        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.denied("Permission denied")),
                        "README.md");

        assertTrue(classification.failed());
        assertTrue(classification.denied());
        assertTrue(classification.mutatingDenied());
        assertFalse(classification.userApprovalDenial());
    }

    @Test
    void approvalDenialIsDrivenByTypedReasonNotMessageProse() {
        // T758 prose-freedom proof: an arbitrary message with the typed
        // reason classifies; the old magic message without a reason does not.
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "new"));

        ToolExecutionFailureClassifier.Classification approvalDenial =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.denied(
                                ToolFailureReason.USER_APPROVAL_DENIED,
                                "The user declined this change; rephrased prose is fine.")),
                        "README.md");
        ToolExecutionFailureClassifier.Classification legacyProseOnly =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.denied("User did not approve talos.write_file.")),
                        "README.md");

        assertTrue(approvalDenial.userApprovalDenial());
        assertFalse(legacyProseOnly.userApprovalDenial(),
                "message sniffing must be gone: prose without a typed reason does not classify");
    }

    @Test
    void pathPolicyAndExpectedTargetBlocksAreDrivenByTypedReasons() {
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "../README.md", "content", "new"));

        ToolExecutionFailureClassifier.Classification pathPolicy =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.invalidParams(
                                ToolFailureReason.PRE_APPROVAL_PATH_NOT_ALLOWED,
                                "any wording works here")),
                        "../README.md");
        ToolExecutionFailureClassifier.Classification expectedTarget =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.invalidParams(
                                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                                "any wording works here too")),
                        "docs/other.md");
        ToolExecutionFailureClassifier.Classification legacyProseOnly =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.invalidParams(
                                "Path not allowed before approval: ../README.md")),
                        "../README.md");

        assertTrue(pathPolicy.preApprovalPathPolicyBlock());
        assertFalse(pathPolicy.expectedTargetScopeBlock());
        assertTrue(expectedTarget.preApprovalPathPolicyBlock());
        assertTrue(expectedTarget.expectedTargetScopeBlock());
        assertFalse(legacyProseOnly.preApprovalPathPolicyBlock(),
                "message sniffing must be gone: prose without a typed reason does not classify");
    }

    @Test
    void unsupportedReadFileReturnsNormalizedUnsupportedPathOnlyForReadFile() {
        ToolExecutionFailureClassifier.Classification readFailure =
                ToolExecutionFailureClassifier.classify(
                        new ToolCall("talos.read_file", Map.of("path", "docs\\report.pdf")),
                        ToolResult.fail(ToolError.unsupportedFormat("unsupported binary document")),
                        "docs\\report.pdf");
        ToolExecutionFailureClassifier.Classification grepFailure =
                ToolExecutionFailureClassifier.classify(
                        new ToolCall("talos.grep", Map.of("pattern", "x")),
                        ToolResult.fail(ToolError.unsupportedFormat("unsupported binary document")),
                        "docs\\report.pdf");

        assertEquals("docs/report.pdf", readFailure.unsupportedReadPath());
        assertFalse(readFailure.unsupportedReadPath().isBlank());
        assertEquals("", grepFailure.unsupportedReadPath());
    }

    @Test
    void oldStringNotFoundRequiresInvalidParamsAndTypedReason() {
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "README.md",
                "old_string", "old",
                "new_string", "new"));

        ToolExecutionFailureClassifier.Classification invalidOldString =
                ToolExecutionFailureClassifier.classify(
                        edit,
                        ToolResult.fail(ToolError.invalidParams(
                                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND, "any wording")),
                        "README.md");
        ToolExecutionFailureClassifier.Classification internalOldString =
                ToolExecutionFailureClassifier.classify(
                        edit,
                        ToolResult.fail(ToolError.internal(
                                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND, "any wording")),
                        "README.md");
        ToolExecutionFailureClassifier.Classification legacyProseOnly =
                ToolExecutionFailureClassifier.classify(
                        edit,
                        ToolResult.fail(ToolError.invalidParams("old_string not found")),
                        "README.md");

        assertTrue(invalidOldString.oldStringNotFound());
        assertFalse(internalOldString.oldStringNotFound(), "INVALID_PARAMS gate still applies");
        assertFalse(legacyProseOnly.oldStringNotFound(),
                "message sniffing must be gone: prose without a typed reason does not classify");
    }

    @Test
    void executionStageDelegatesFailureClassification() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));

        assertTrue(source.contains("ToolExecutionFailureClassifier.classify"), source);
        assertFalse(source.contains("private static boolean isUserApprovalDenial"), source);
        assertFalse(source.contains("private static boolean isPreApprovalPathPolicyBlock"), source);
        assertFalse(source.contains("private static boolean isExpectedTargetScopeBlock"), source);
        assertFalse(source.contains("private static boolean isOldStringNotFound"), source);
    }
}
