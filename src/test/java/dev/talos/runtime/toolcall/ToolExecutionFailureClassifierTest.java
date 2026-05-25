package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
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
    void approvalDenialRequiresExactExistingPrefix() {
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "README.md", "content", "new"));

        ToolExecutionFailureClassifier.Classification approvalDenial =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.denied("User did not approve talos.write_file.")),
                        "README.md");
        ToolExecutionFailureClassifier.Classification ordinaryDenial =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.denied("User rejected talos.write_file.")),
                        "README.md");

        assertTrue(approvalDenial.userApprovalDenial());
        assertFalse(ordinaryDenial.userApprovalDenial());
    }

    @Test
    void pathPolicyAndExpectedTargetBlocksUseExactExistingPrefixes() {
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "../README.md", "content", "new"));

        ToolExecutionFailureClassifier.Classification pathPolicy =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.invalidParams("Path not allowed before approval: ../README.md")),
                        "../README.md");
        ToolExecutionFailureClassifier.Classification expectedTarget =
                ToolExecutionFailureClassifier.classify(
                        write,
                        ToolResult.fail(ToolError.invalidParams(
                                "Target outside expected targets before approval: docs/other.md")),
                        "docs/other.md");

        assertTrue(pathPolicy.preApprovalPathPolicyBlock());
        assertFalse(pathPolicy.expectedTargetScopeBlock());
        assertTrue(expectedTarget.preApprovalPathPolicyBlock());
        assertTrue(expectedTarget.expectedTargetScopeBlock());
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
    void oldStringNotFoundRequiresInvalidParamsAndExistingMessageText() {
        ToolCall edit = new ToolCall("talos.edit_file", Map.of(
                "path", "README.md",
                "old_string", "old",
                "new_string", "new"));

        ToolExecutionFailureClassifier.Classification invalidOldString =
                ToolExecutionFailureClassifier.classify(
                        edit,
                        ToolResult.fail(ToolError.invalidParams("old_string not found")),
                        "README.md");
        ToolExecutionFailureClassifier.Classification internalOldString =
                ToolExecutionFailureClassifier.classify(
                        edit,
                        ToolResult.fail(ToolError.internal("old_string not found")),
                        "README.md");
        ToolExecutionFailureClassifier.Classification invalidOther =
                ToolExecutionFailureClassifier.classify(
                        edit,
                        ToolResult.fail(ToolError.invalidParams("missing old_string")),
                        "README.md");

        assertTrue(invalidOldString.oldStringNotFound());
        assertFalse(internalOldString.oldStringNotFound());
        assertFalse(invalidOther.oldStringNotFound());
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
