package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallSupportBoundaryCharacterizationTest {

    @Test
    void reportPinsCurrentEvidenceAndT830Boundary() throws Exception {
        String report = Files.readString(Path.of(
                "work-cycle-docs/reports/t829-tool-call-support-boundary-scoping.md"));

        assertAll(
                () -> assertTrue(report.contains("Report commit: `de8e3d066a2e5bf10179f4cfb60e0b9212f72898`"),
                        report),
                () -> assertTrue(report.contains("Confidence label: `INFERRED_REVIEW`"), report),
                () -> assertTrue(report.contains("`runtime.toolcall.ToolCallSupport` | `236`"), report),
                () -> assertTrue(report.contains("`runtime.toolcall.LoopState` | `293`"), report),
                () -> assertTrue(report.contains("`runtime.toolcall.ToolCallExecutionStage` | `119`"), report),
                () -> assertTrue(report.contains("`cli.modes.ExecutionOutcome` | `99`"), report),
                () -> assertTrue(report.contains("T829 does not authorize production"), report),
                () -> assertTrue(report.contains("Candidate T830 Seam Hypotheses"), report),
                () -> assertTrue(report.contains("Retry-message utilities remain a hypothesis"), report));
    }

    @Test
    void nativeCallConversionKeepsContainerJsonAndLegacyScalarText() {
        var operation = new LinkedHashMap<String, Object>();
        operation.put("op", "mkdir");
        operation.put("path", "docs");
        var nativeCall = new ChatMessage.NativeToolCall(
                "call-1",
                "talos.apply_workspace_batch",
                Map.of(
                        "operations", List.of(operation),
                        "dry_run", false,
                        "retries", 2));

        List<ToolCall> calls = ToolCallSupport.convertNativeToolCalls(List.of(nativeCall));

        assertAll(
                () -> assertEquals(1, calls.size()),
                () -> assertEquals("talos.apply_workspace_batch", calls.get(0).toolName()),
                () -> assertEquals("[{\"op\":\"mkdir\",\"path\":\"docs\"}]",
                        calls.get(0).param("operations")),
                () -> assertEquals("false", calls.get(0).param("dry_run")),
                () -> assertEquals("2", calls.get(0).param("retries")));
    }

    @Test
    void resultFormattingPreservesPromptShapeSanitizationTruncationAndVerification() {
        ToolCall grep = new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK"));
        ToolResult protectedResult = ToolResult.ok("""
                notes.md:1 | PRIVATE_MARKER = DO_NOT_LEAK_T829_SUPPORT
                safe.txt:1 | ordinary text
                """);

        String sanitized = ToolCallSupport.formatToolResult(grep, protectedResult);

        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "index.html"));
        String longOutput = "x".repeat(32_010);
        String truncated = ToolCallSupport.formatToolResult(
                write,
                ToolResult.ok(longOutput, VerificationStatus.WARN));

        String failed = ToolCallSupport.formatToolResult(
                write,
                ToolResult.fail("write failed"));

        assertAll(
                () -> assertTrue(sanitized.startsWith("[tool_result: talos.grep]\n")),
                () -> assertFalse(sanitized.contains("DO_NOT_LEAK_T829_SUPPORT")),
                () -> assertTrue(sanitized.contains("PRIVATE_MARKER=[redacted]")),
                () -> assertTrue(sanitized.contains("ordinary text")),
                () -> assertTrue(truncated.contains("... (output truncated at 32K chars)")),
                () -> assertTrue(truncated.contains("[verification_status: WARN]")),
                () -> assertTrue(failed.contains("[error] write failed")),
                () -> assertTrue(failed.endsWith("[/tool_result]")));
    }

    @Test
    void retryRequestExtractionSkipsSyntheticToolResultsAndUnwrapsRetryPrompts() {
        var messages = new ArrayList<ChatMessage>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("edit index.html"),
                ChatMessage.assistant("{\"name\":\"talos.edit_file\",\"arguments\":{}}"),
                ChatMessage.user("[tool_result: talos.edit_file]\n[error] failed\n[/tool_result]"),
                ChatMessage.user("[compacted: talos.read_file result, 200 chars — full output elided]")
        ));

        String retryPrompt = """
                Retry task
                Task type: WORKSPACE_EXPLAIN
                User request: "fix the button selector"
                """;

        assertAll(
                () -> assertEquals("edit index.html", ToolCallSupport.latestUserRequestIn(messages)),
                () -> assertTrue(ToolCallSupport.isSyntheticToolResultContent("[tool_result: x]")),
                () -> assertTrue(ToolCallSupport.isSyntheticToolResultContent("[compacted: x]")),
                () -> assertEquals("WORKSPACE_EXPLAIN", ToolCallSupport.embeddedRetryTaskType(retryPrompt)),
                () -> assertEquals("fix the button selector",
                        ToolCallSupport.embeddedRetryUserRequest(retryPrompt)),
                () -> assertEquals("fix the button selector",
                        ToolCallSupport.effectiveUserRequestForRetryWrappedPrompt(retryPrompt)),
                () -> assertEquals("plain request",
                        ToolCallSupport.effectiveUserRequestForRetryWrappedPrompt("plain request")));
    }

    @Test
    void pathAndCallRepairKeepsCanonicalizationSignaturesAndAliasClassification() {
        ToolCall read = new ToolCall("talos.read_file", Map.of(
                "path", "./src/main/",
                "max_lines", "10"));
        ToolCall missingPathWrite = new ToolCall("talos.write_file", Map.of("content", "x"));
        ToolCall editMissingNew = new ToolCall("talos.edit_file", Map.of(
                "path", "script.js",
                "old_string", "const ready = false;"));
        ToolCall editDelete = new ToolCall("talos.edit_file", Map.of(
                "path", "script.js",
                "old_string", "console.log('debug');",
                "new_string", ""));

        assertAll(
                () -> assertEquals("src/main", ToolCallSupport.canonicalizeReadPath("./src/main/")),
                () -> assertEquals("src/main", ToolCallSupport.canonicalizeReadPath("src\\main")),
                () -> assertEquals("talos.read_file:max_lines=10;path=src/main;",
                        ToolCallSupport.buildReadCallSignature(read)),
                () -> assertSame(missingPathWrite, ToolCallSupport.repairMissingPath(missingPathWrite)),
                () -> assertTrue(ToolCallSupport.hasEmptyEditArguments(editMissingNew)),
                () -> assertFalse(ToolCallSupport.hasEmptyEditArguments(editDelete)),
                () -> assertTrue(ToolCallSupport.isReadOnlyTool("tool_use:list_dir")),
                () -> assertTrue(ToolCallSupport.isMutatingTool("file_utils:edit_file")),
                () -> assertEquals("script.js", ToolCallSupport.resolvePathHint(editDelete)),
                () -> assertNotEquals(
                        ToolCallSupport.buildCallSignature(editMissingNew),
                        ToolCallSupport.buildCallSignature(editDelete)));
    }

    @Test
    void compactionSummarizesOlderToolResultsAndPreservesRecentResultsAndCallIds() {
        String readBody = "[tool_result: talos.read_file]\n" + "x".repeat(600) + "\n[/tool_result]";
        String errorBody = "[tool_result: talos.edit_file]\n[error] missing target\n[/tool_result]";
        var messages = new ArrayList<ChatMessage>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("inspect files"),
                ChatMessage.toolResult("old-read", readBody),
                ChatMessage.toolResult("old-error", errorBody),
                ChatMessage.toolResult("recent-read", readBody),
                ChatMessage.toolResult("recent-error", errorBody)
        ));

        ToolCallSupport.compactOlderToolResultsInPlace(messages);

        assertAll(
                () -> assertEquals("old-read", messages.get(2).toolCallId()),
                () -> assertEquals("old-error", messages.get(3).toolCallId()),
                () -> assertTrue(messages.get(2).content().startsWith("[compacted: talos.read_file result")),
                () -> assertTrue(messages.get(3).content().startsWith("[compacted: talos.edit_file error")),
                () -> assertEquals(readBody, messages.get(4).content()),
                () -> assertEquals(errorBody, messages.get(5).content()),
                () -> assertTrue(ToolCallSupport.summarizeToolResult(readBody)
                        .contains(String.valueOf(readBody.length()))));
    }
}
