package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFailureRepairStateAccountingTest {
    private static final String REPEATED_EDIT_SUGGESTION =
            "Suggestion: edit_file has failed on this file multiple times. "
                    + "Consider using talos.write_file with the complete updated file content instead.";

    @Test
    void preApprovalStaleRereadDecisionRecordsIgnoredPath() {
        LoopState state = loopState();
        EditFilePreApprovalGuard.Decision decision = new EditFilePreApprovalGuard.Decision(
                EditFilePreApprovalGuard.Kind.STALE_REREAD_REQUIRED,
                "diagnostic",
                "src/app.js",
                false,
                "");

        EditFailureRepairStateAccounting.recordPreApprovalDecision(state, decision, "src\\app.js");

        assertEquals("src/app.js", state.staleEditRereadIgnoredPath);
        assertTrue(state.emptyEditArgumentFailuresByPath.isEmpty());
    }

    @Test
    void preApprovalDuplicateEmptyEditRecordsNormalizedEmptyEditFailure() {
        LoopState state = loopState();
        EditFilePreApprovalGuard.Decision decision = new EditFilePreApprovalGuard.Decision(
                EditFilePreApprovalGuard.Kind.DUPLICATE_FAILED_EDIT,
                "diagnostic",
                "src/app.js",
                true,
                "signature");

        EditFailureRepairStateAccounting.recordPreApprovalDecision(state, decision, "src\\app.js");

        assertEquals(1, state.emptyEditArgumentFailuresByPath.get("src/app.js"));
        assertEquals(null, state.staleEditRereadIgnoredPath);
    }

    @Test
    void failedEditRecordsSignatureAndEmptyEditFailure() {
        LoopState state = loopState();
        ToolCall edit = editFile("README.md", "", "new");
        ToolResult failure = ToolResult.fail(ToolError.invalidParams("old_string must be present"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(edit, failure, "README.md");

        EditFailureRepairStateAccounting.Result result =
                EditFailureRepairStateAccounting.recordFailedEditResult(
                        state,
                        edit,
                        classification,
                        "README.md",
                        failure,
                        false);

        assertEquals(failure, result.toolResult());
        assertTrue(state.failedCallSignatures.contains(ToolCallSupport.buildCallSignature(edit)));
        assertEquals(1, state.emptyEditArgumentFailuresByPath.get("README.md"));
        assertEquals(1, state.editFailuresByPath.get("README.md"));
    }

    @Test
    void oldStringMissAfterSameTurnMutationRecordsStaleEditFailure() {
        LoopState state = loopState();
        state.pathsMutatedSinceRead.add("src/app.js");
        ToolCall edit = editFile("src\\app.js", "missing", "new");
        ToolResult failure = ToolResult.fail(ToolError.invalidParams(
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND, "old_string not found"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(edit, failure, "src\\app.js");

        EditFailureRepairStateAccounting.recordFailedEditResult(
                state,
                edit,
                classification,
                "src\\app.js",
                failure,
                false);

        assertEquals(1, state.staleEditFailuresByPath.get("src/app.js"));
    }

    @Test
    void staticWebOldStringMissRecordsFullRewriteRepairTarget() {
        LoopState state = loopState();
        state.messages.add(ChatMessage.user("Fix the static web button behavior in script.js."));
        state.pathsReadThisTurn.add("script.js");
        ToolCall edit = editFile("script.js", "document.querySelector('.missing-button')", "document.querySelector('#submit')");
        ToolResult failure = ToolResult.fail(ToolError.invalidParams(
                ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND, "old_string not found"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(edit, failure, "script.js");

        EditFailureRepairStateAccounting.recordFailedEditResult(
                state,
                edit,
                classification,
                "script.js",
                failure,
                false);

        assertTrue(state.staticWebFullRewriteRequiredTargets.contains("script.js"));
    }

    @Test
    void repeatedFailedEditAppendsExistingSuggestionAndIncrementsCushionOnce() {
        LoopState state = loopState();
        ToolCall edit = editFile("README.md", "missing", "new");
        ToolResult failure = ToolResult.fail(ToolError.invalidParams("old_string not found"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(edit, failure, "README.md");

        EditFailureRepairStateAccounting.Result first =
                EditFailureRepairStateAccounting.recordFailedEditResult(
                        state,
                        edit,
                        classification,
                        "README.md",
                        failure,
                        false);
        EditFailureRepairStateAccounting.Result second =
                EditFailureRepairStateAccounting.recordFailedEditResult(
                        state,
                        edit,
                        classification,
                        "README.md",
                        failure,
                        false);

        assertFalse(first.toolResult().errorMessage().contains(REPEATED_EDIT_SUGGESTION));
        assertTrue(second.toolResult().errorMessage().contains(REPEATED_EDIT_SUGGESTION),
                second.toolResult().errorMessage());
        assertEquals(2, state.editFailuresByPath.get("README.md"));
        assertEquals(1, state.cushionFiresE1Suggestion);
    }

    @Test
    void strictModeDoesNotAppendRepeatedFailedEditSuggestion() {
        LoopState state = loopState();
        ToolCall edit = editFile("README.md", "missing", "new");
        ToolResult failure = ToolResult.fail(ToolError.invalidParams("old_string not found"));
        ToolExecutionFailureClassifier.Classification classification =
                ToolExecutionFailureClassifier.classify(edit, failure, "README.md");

        EditFailureRepairStateAccounting.recordFailedEditResult(
                state,
                edit,
                classification,
                "README.md",
                failure,
                true);
        EditFailureRepairStateAccounting.Result second =
                EditFailureRepairStateAccounting.recordFailedEditResult(
                        state,
                        edit,
                        classification,
                        "README.md",
                        failure,
                        true);

        assertFalse(second.toolResult().errorMessage().contains(REPEATED_EDIT_SUGGESTION));
        assertTrue(state.editFailuresByPath.isEmpty());
        assertEquals(0, state.cushionFiresE1Suggestion);
    }

    @Test
    void executionStageDelegatesEditFailureRepairStateAccounting() throws Exception {
        String stageSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java"));
        String guardSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallPreExecutionGuardChain.java"));
        String source = stageSource + "\n" + guardSource;

        assertTrue(guardSource.contains("EditFailureRepairStateAccounting.recordPreApprovalDecision"), guardSource);
        assertTrue(stageSource.contains("EditFailureRepairStateAccounting.recordFailedEditResult"), stageSource);
        assertFalse(source.contains("private static void recordEmptyEditArgumentFailure"), source);
        assertFalse(source.contains("private static void recordStaleEditFailure"), source);
        assertFalse(source.contains("private static boolean shouldRecoverStaticWebEditFailureWithFullRewrite"), source);
        assertFalse(source.contains("private static void recordStaticWebFullRewriteRequired"), source);
        assertFalse(source.contains("state.failedCallSignatures.add"), source);
        assertFalse(source.contains("state.editFailuresByPath.merge"), source);
    }

    private static ToolCall editFile(String path, String oldString, String newString) {
        return new ToolCall("talos.edit_file", Map.of(
                "path", path,
                "old_string", oldString,
                "new_string", newString));
    }

    private static LoopState loopState() {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"))),
                null,
                null,
                null,
                5,
                0);
    }
}
