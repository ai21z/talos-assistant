package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EditFilePreApprovalGuardTest {
    @TempDir
    Path workspace;

    @Test
    void fullRewriteRepairTargetReturnsExactDiagnostic() {
        LoopState state = loopState();
        ToolCall edit = editFile("script.js", "old", "new");

        EditFilePreApprovalGuard.Decision decision = EditFilePreApprovalGuard.decision(
                edit,
                state,
                "script.js",
                false,
                Set.of(),
                Set.of("script.js"));

        assertNotNull(decision);
        assertEquals(EditFilePreApprovalGuard.Kind.FULL_REWRITE_REPAIR_REQUIRED, decision.kind());
        assertEquals("script.js", decision.normalizedPath());
        assertFalse(decision.emptyEditArguments());
        assertEquals(
                "Static verification repair requires a complete talos.write_file replacement for "
                        + "`script.js`. This talos.edit_file call was not executed, no approval was requested, "
                        + "and no file was changed. Use talos.write_file with the full corrected file content "
                        + "for this small web file.",
                decision.diagnostic());
    }

    @Test
    void fullRewriteRepairTargetRecognizesEditFileAlias() {
        LoopState state = loopState();
        ToolCall edit = new ToolCall("file_utils:edit_file", Map.of(
                "path", "script.js",
                "old_string", "old",
                "new_string", "new"));

        EditFilePreApprovalGuard.Decision decision = EditFilePreApprovalGuard.decision(
                edit,
                state,
                "script.js",
                false,
                Set.of(),
                Set.of("script.js"));

        assertNotNull(decision);
        assertEquals(EditFilePreApprovalGuard.Kind.FULL_REWRITE_REPAIR_REQUIRED, decision.kind());
    }

    @Test
    void staleRereadRequiredPathReturnsExactDiagnostic() {
        LoopState state = loopState();
        ToolCall edit = editFile("index.html", "beta\n", "beta-fixed\n");

        EditFilePreApprovalGuard.Decision decision = EditFilePreApprovalGuard.decision(
                edit,
                state,
                "index.html",
                false,
                Set.of("index.html"),
                Set.of());

        assertNotNull(decision);
        assertEquals(EditFilePreApprovalGuard.Kind.STALE_REREAD_REQUIRED, decision.kind());
        assertEquals("index.html", decision.normalizedPath());
        assertEquals(
                "A previous edit changed `index.html`, then another edit for the same file failed "
                        + "because old_string was not found. Call talos.read_file for `index.html` "
                        + "in a separate follow-up step before attempting another talos.edit_file. "
                        + "No approval was requested and no additional file change was made.",
                decision.diagnostic());
    }

    @Test
    void duplicateFailedEditReturnsExactDiagnosticAndCallSignature() {
        LoopState state = loopState();
        ToolCall edit = editFile("README.md", "missing", "replacement");
        String signature = ToolCallSupport.buildCallSignature(edit);
        state.failedCallSignatures.add(signature);

        EditFilePreApprovalGuard.Decision decision = EditFilePreApprovalGuard.decision(
                edit,
                state,
                "README.md",
                false,
                Set.of(),
                Set.of());

        assertNotNull(decision);
        assertEquals(EditFilePreApprovalGuard.Kind.DUPLICATE_FAILED_EDIT, decision.kind());
        assertEquals(signature, decision.callSignature());
        assertFalse(decision.emptyEditArguments());
        assertEquals(
                "This exact edit was already attempted and failed. "
                        + "Call talos.read_file to see the file's current state, "
                        + "then provide the exact raw content (without line-number prefixes) in old_string. "
                        + "Alternatively, use talos.write_file to replace the entire file content.",
                decision.diagnostic());
    }

    @Test
    void duplicateEmptyEditAfterReadReturnsExactDiagnostic() {
        LoopState state = loopState();
        state.pathsReadThisTurn.add("index.html");
        ToolCall edit = editFile("index.html", "", "");
        state.failedCallSignatures.add(ToolCallSupport.buildCallSignature(edit));

        EditFilePreApprovalGuard.Decision decision = EditFilePreApprovalGuard.decision(
                edit,
                state,
                "index.html",
                false,
                Set.of(),
                Set.of());

        assertNotNull(decision);
        assertEquals(EditFilePreApprovalGuard.Kind.DUPLICATE_FAILED_EDIT, decision.kind());
        assertTrue(decision.emptyEditArguments());
        assertEquals(
                "Repeated empty or missing talos.edit_file arguments for `index.html` after the file was read. "
                        + "`old_string` was empty or `new_string` was missing, so no approval was requested "
                        + "and no file was changed. Copy the exact `old_string` from the latest "
                        + "talos.read_file result and provide the intended `new_string`, or stop "
                        + "and explain why the edit cannot be formed.",
                decision.diagnostic());
    }

    @Test
    void strictModeAndNonEditCallsReturnNoDecision() {
        LoopState state = loopState();
        ToolCall edit = editFile("script.js", "old", "new");
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "script.js"));

        assertNull(EditFilePreApprovalGuard.decision(
                edit,
                state,
                "script.js",
                true,
                Set.of("script.js"),
                Set.of("script.js")));
        assertNull(EditFilePreApprovalGuard.decision(
                read,
                state,
                "script.js",
                false,
                Set.of("script.js"),
                Set.of("script.js")));
    }

    @Test
    void executionStageDelegatesEditPreApprovalDecisionsToGuard() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallPreExecutionGuardChain.java"));

        assertTrue(source.contains("EditFilePreApprovalGuard.decision"), source);
        assertFalse(source.contains("private static String emptyEditArgumentDiagnostic"), source);
        assertFalse(source.contains("private static String staleEditRereadRequiredDiagnostic"), source);
        assertFalse(source.contains("private static String fullRewriteRepairRequiredDiagnostic"), source);
    }

    private LoopState loopState() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Edit the workspace.")));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of()))
                .build();
        return new LoopState("", List.of(), messages, workspace, ctx, null, 5, 0);
    }

    private static ToolCall editFile(String path, String oldString, String newString) {
        return new ToolCall("talos.edit_file", Map.of(
                "path", path,
                "old_string", oldString,
                "new_string", newString));
    }
}
