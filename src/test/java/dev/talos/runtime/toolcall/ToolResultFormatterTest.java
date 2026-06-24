package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultFormatterTest {

    @Test
    void formatsSuccessErrorEmptyTruncationAndVerificationStatus() {
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ToolCall write = new ToolCall("talos.write_file", Map.of("path", "index.html"));
        String longOutput = "x".repeat(32_010);

        String success = ToolResultFormatter.formatToolResult(read, ToolResult.ok("hello"));
        String empty = ToolResultFormatter.formatToolResult(read, ToolResult.ok("  "));
        String truncated = ToolResultFormatter.formatToolResult(
                write,
                ToolResult.ok(longOutput, VerificationStatus.WARN));
        String failed = ToolResultFormatter.formatToolResult(write, ToolResult.fail("write failed"));
        String failedWithVerification = ToolResultFormatter.formatToolResult(
                write,
                ToolResult.fail(
                        dev.talos.tools.ToolError.internal("read-back mismatch"),
                        VerificationStatus.INTEGRITY_FAIL));

        assertAll(
                () -> assertEquals("""
                        [tool_result: talos.read_file]
                        hello
                        [/tool_result]""", success),
                () -> assertEquals("""
                        [tool_result: talos.read_file]
                        (empty result)
                        [/tool_result]""", empty),
                () -> assertTrue(truncated.startsWith("[tool_result: talos.write_file]\n"
                        + "x".repeat(32_000))),
                () -> assertTrue(truncated.contains("\n... (output truncated at 32K chars)")),
                () -> assertTrue(truncated.contains("[verification_status: WARN]")),
                () -> assertEquals("""
                        [tool_result: talos.write_file]
                        [error] write failed
                        [/tool_result]""", failed),
                () -> assertEquals("""
                        [tool_result: talos.write_file]
                        [error] read-back mismatch
                        [verification_status: INTEGRITY_FAIL]
                        [/tool_result]""", failedWithVerification));
    }

    @Test
    void sanitizesProtectedOutputUnlessPreservationIsRequested() {
        ToolCall grep = new ToolCall("talos.grep", Map.of("pattern", "DO_NOT_LEAK"));
        ToolResult result = ToolResult.ok("""
                notes.md:1 | PRIVATE_MARKER = DO_NOT_LEAK_T831_FORMATTER
                safe.txt:1 | ordinary text
                """);

        String sanitized = ToolResultFormatter.formatToolResult(grep, result);
        String preserved = ToolResultFormatter.formatToolResult(grep, result, true);

        assertAll(
                () -> assertFalse(sanitized.contains("DO_NOT_LEAK_T831_FORMATTER")),
                () -> assertTrue(sanitized.contains("PRIVATE_MARKER=[redacted]")),
                () -> assertTrue(sanitized.contains("ordinary text")),
                () -> assertTrue(preserved.contains("DO_NOT_LEAK_T831_FORMATTER")),
                () -> assertTrue(preserved.contains("PRIVATE_MARKER = DO_NOT_LEAK_T831_FORMATTER")));
    }

    @Test
    void sanitizesBareSecretShapesUnlessPreservationIsRequested() {
        String token = "ghp_AbCdEfGhIjKlMnOpQrStUvWxYz1234567890";
        ToolCall grep = new ToolCall("talos.grep", Map.of("pattern", "token"));
        ToolResult result = ToolResult.ok("README.md:1 | leaked token " + token);

        String sanitized = ToolResultFormatter.formatToolResult(grep, result);
        String preserved = ToolResultFormatter.formatToolResult(grep, result, true);

        assertAll(
                () -> assertFalse(sanitized.contains(token), sanitized),
                () -> assertTrue(sanitized.contains("[redacted]"), sanitized),
                () -> assertTrue(preserved.contains(token), preserved));
    }

    @Test
    void extractsVerificationWarningSummary() {
        assertAll(
                () -> assertEquals(
                        "CSS selector .missing-button was not found",
                        ToolResultFormatter.extractVerificationSummary(
                                "Warning: CSS selector .missing-button was not found. [verification: WARN]")),
                () -> assertEquals(
                        "JSON parse failed",
                        ToolResultFormatter.extractVerificationSummary("Warning: JSON parse failed")),
                () -> assertEquals(null, ToolResultFormatter.extractVerificationSummary("all good")),
                () -> assertEquals(null, ToolResultFormatter.extractVerificationSummary(null)));
    }

    @Test
    void firstSentenceSummaryStripsToolHeadersAnnotationsPunctuationAndCapsLength() {
        String longText = "a".repeat(200);

        assertAll(
                () -> assertEquals(
                        "The file defines the app shell",
                        ToolResultFormatter.firstSentenceSummary("""
                                [tool_result: talos.read_file]
                                The file defines the app shell. It also has more detail.
                                [/tool_result]""")),
                () -> assertEquals(
                        "Build succeeded",
                        ToolResultFormatter.firstSentenceSummary("Build succeeded. [verification: PASS]")),
                () -> assertEquals(
                        "First line only",
                        ToolResultFormatter.firstSentenceSummary("First line only\nSecond line")),
                () -> assertEquals("", ToolResultFormatter.firstSentenceSummary("  ")),
                () -> assertEquals("a".repeat(157) + "…",
                        ToolResultFormatter.firstSentenceSummary(longText)));
    }

    @Test
    void toolCallSupportDelegatesRemainCompatible() {
        ToolCall read = new ToolCall("talos.read_file", Map.of("path", "README.md"));
        ToolResult result = ToolResult.ok("hello");
        String warning = "Warning: target changed. [verification: WARN]";

        assertAll(
                () -> assertEquals(
                        ToolResultFormatter.formatToolResult(read, result),
                        ToolCallSupport.formatToolResult(read, result)),
                () -> assertEquals(
                        ToolResultFormatter.formatToolResult(read, result, true),
                        ToolCallSupport.formatToolResult(read, result, true)),
                () -> assertEquals(
                        ToolResultFormatter.extractVerificationSummary(warning),
                        ToolCallSupport.extractVerificationSummary(warning)),
                () -> assertEquals(
                        ToolResultFormatter.firstSentenceSummary("Done."),
                        ToolCallSupport.firstSentenceSummary("Done.")));
    }
}
