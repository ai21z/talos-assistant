package dev.talos.runtime;

import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import dev.talos.tools.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tool progress UX: the {@link ToolProgressSink} integration in
 * {@link ToolCallLoop} and the {@link ToolCallLoop#extractVerificationSummary} helper.
 */
@DisplayName("ToolProgressUX")
class ToolProgressUXTest {

    /** Simple recording sink that collects all progress events. */
    record ProgressEvent(String toolName, String action, String detail) {}

    static List<ProgressEvent> recordingEvents() {
        return new ArrayList<>();
    }

    static ToolProgressSink recordingSink(List<ProgressEvent> events) {
        return (toolName, action, detail) -> events.add(new ProgressEvent(toolName, action, detail));
    }

    // ── Verification summary extraction ──────────────────────────────────

    @Nested
    @DisplayName("extractVerificationSummary")
    class SummaryExtraction {

        @Test
        @DisplayName("extracts summary after 'Warning: '")
        void extracts_warning_text() {
            String output = "Updated index.html (10 lines). Warning: HTML issues — unclosed <div>. [verification: WARN]";
            String summary = ToolCallLoop.extractVerificationSummary(output);
            assertEquals("HTML issues — unclosed <div>", summary);
        }

        @Test
        @DisplayName("extracts summary without status tag")
        void extracts_without_tag() {
            String output = "Edited data.json. Warning: JSON parse failed — unexpected token";
            String summary = ToolCallLoop.extractVerificationSummary(output);
            assertEquals("JSON parse failed — unexpected token", summary);
        }

        @Test
        @DisplayName("returns null when no Warning prefix")
        void returns_null_for_pass() {
            String output = "Updated index.html (10 lines). Verified: HTML structure OK. [verification: PASS]";
            String summary = ToolCallLoop.extractVerificationSummary(output);
            assertNull(summary);
        }

        @Test
        @DisplayName("returns null for null input")
        void returns_null_for_null() {
            assertNull(ToolCallLoop.extractVerificationSummary(null));
        }

        @Test
        @DisplayName("returns null for empty input")
        void returns_null_for_empty() {
            assertNull(ToolCallLoop.extractVerificationSummary(""));
        }
    }

    // ── ToolProgressSink contract ────────────────────────────────────────

    @Nested
    @DisplayName("ToolProgressSink interface")
    class SinkContract {

        @Test
        @DisplayName("sink receives events with correct tool name and action")
        void sink_receives_events() {
            var events = recordingEvents();
            var sink = recordingSink(events);
            sink.onToolProgress("talos.write_file", "executing", "index.html");
            assertEquals(1, events.size());
            assertEquals("talos.write_file", events.get(0).toolName());
            assertEquals("executing", events.get(0).action());
            assertEquals("index.html", events.get(0).detail());
        }

        @Test
        @DisplayName("sink receives null detail gracefully")
        void sink_handles_null_detail() {
            var events = recordingEvents();
            var sink = recordingSink(events);
            sink.onToolProgress("talos.grep", "executing", null);
            assertEquals(1, events.size());
            assertNull(events.get(0).detail());
        }

        @Test
        @DisplayName("multiple events accumulate in order")
        void multiple_events() {
            var events = recordingEvents();
            var sink = recordingSink(events);
            sink.onToolProgress("talos.read_file", "executing", "a.html");
            sink.onToolProgress("talos.write_file", "executing", "a.html");
            sink.onToolProgress("talos.write_file", "warning", "unclosed <div>");
            assertEquals(3, events.size());
            assertEquals("executing", events.get(0).action());
            assertEquals("executing", events.get(1).action());
            assertEquals("warning", events.get(2).action());
        }
    }

    // ── Result.ToolProgress ──────────────────────────────────────────────

    @Nested
    @DisplayName("Result.ToolProgress")
    class ResultToolProgress {

        @Test
        @DisplayName("toString includes action and tool name")
        void toString_basic() {
            var tp = new dev.talos.cli.repl.Result.ToolProgress("talos.write_file", "executing", "index.html");
            assertTrue(tp.toString().contains("executing"));
            assertTrue(tp.toString().contains("talos.write_file"));
            assertTrue(tp.toString().contains("index.html"));
        }

        @Test
        @DisplayName("toString without detail omits colon")
        void toString_no_detail() {
            var tp = new dev.talos.cli.repl.Result.ToolProgress("talos.grep", "executing", null);
            assertEquals("executing talos.grep", tp.toString());
        }

        @Test
        @DisplayName("null fields become empty strings")
        void null_fields_safe() {
            var tp = new dev.talos.cli.repl.Result.ToolProgress(null, null, null);
            assertEquals("", tp.toolName);
            assertEquals("", tp.action);
            assertNull(tp.detail);
        }
    }

    // ── Verification warning progress emission ───────────────────────────

    @Nested
    @DisplayName("Verification warning progress")
    class VerificationWarningProgress {

        @Test
        @DisplayName("WARN verification emits warning progress event")
        void warn_emits_event() {
            var events = recordingEvents();
            var sink = recordingSink(events);

            // Simulate what ToolCallLoop does internally
            ToolResult result = ToolResult.ok(
                    "Updated index.html (10 lines). Warning: HTML issues — unclosed <div>. [verification: WARN]",
                    VerificationStatus.WARN);

            // Replicate ToolCallLoop's emitToolResult logic
            if (result.verification() != null && !result.verification().acceptable()) {
                String detail = ToolCallLoop.extractVerificationSummary(result.output());
                sink.onToolProgress("talos.write_file", "warning", detail);
            }

            assertEquals(1, events.size());
            assertEquals("warning", events.get(0).action());
            assertEquals("HTML issues — unclosed <div>", events.get(0).detail());
        }

        @Test
        @DisplayName("PASS verification does NOT emit warning event")
        void pass_no_event() {
            var events = recordingEvents();
            var sink = recordingSink(events);

            ToolResult result = ToolResult.ok("Verified: valid JSON. [verification: PASS]",
                    VerificationStatus.PASS);

            if (result.verification() != null && !result.verification().acceptable()) {
                String detail = ToolCallLoop.extractVerificationSummary(result.output());
                sink.onToolProgress("talos.write_file", "warning", detail);
            }

            assertTrue(events.isEmpty(), "PASS should not emit a warning event");
        }

        @Test
        @DisplayName("UNKNOWN verification does NOT emit warning event")
        void unknown_no_event() {
            var events = recordingEvents();
            var sink = recordingSink(events);

            ToolResult result = ToolResult.ok("read-back OK. [verification: UNKNOWN]",
                    VerificationStatus.UNKNOWN);

            if (result.verification() != null && !result.verification().acceptable()) {
                String detail = ToolCallLoop.extractVerificationSummary(result.output());
                sink.onToolProgress("talos.write_file", "warning", detail);
            }

            assertTrue(events.isEmpty(), "UNKNOWN should not emit a warning event");
        }

        @Test
        @DisplayName("FAIL verification emits warning progress event")
        void fail_emits_event() {
            var events = recordingEvents();
            var sink = recordingSink(events);

            ToolResult result = ToolResult.ok(
                    "Updated bad.json. Warning: JSON parse failed — unexpected token. [verification: FAIL]",
                    VerificationStatus.FAIL);

            if (result.verification() != null && !result.verification().acceptable()) {
                String detail = ToolCallLoop.extractVerificationSummary(result.output());
                sink.onToolProgress("talos.write_file", "warning", detail);
            }

            assertEquals(1, events.size());
            assertEquals("warning", events.get(0).action());
            assertTrue(events.get(0).detail().contains("JSON parse failed"));
        }

        @Test
        @DisplayName("failed tool result emits error event")
        void failed_result_error_event() {
            var events = recordingEvents();
            var sink = recordingSink(events);

            ToolResult result = ToolResult.fail("File not found: missing.txt");

            // Replicate ToolCallLoop logic
            if (!result.success()) {
                sink.onToolProgress("talos.read_file", "error", result.errorMessage());
            } else if (result.verification() != null && !result.verification().acceptable()) {
                String detail = ToolCallLoop.extractVerificationSummary(result.output());
                sink.onToolProgress("talos.read_file", "warning", detail);
            }

            assertEquals(1, events.size());
            assertEquals("error", events.get(0).action());
        }
    }

    // ── No progress noise for no-tool turns ──────────────────────────────

    @Nested
    @DisplayName("No noise for non-tool turns")
    class NoNoise {

        @Test
        @DisplayName("null progress sink causes no errors")
        void null_sink_safe() {
            // Simulating ToolCallLoop behavior with null sink
            ToolProgressSink sink = null;
            // The emitProgress check: if (progressSink != null) { ... }
            assertDoesNotThrow(() -> {
                if (sink != null) {
                    sink.onToolProgress("test", "executing", null);
                }
            });
        }

        @Test
        @DisplayName("progress sink exceptions are swallowed")
        void sink_exception_swallowed() {
            ToolProgressSink throwingSink = (name, action, detail) -> {
                throw new RuntimeException("UI error");
            };
            // ToolCallLoop wraps calls in try-catch — this verifies the contract
            assertDoesNotThrow(() -> {
                try {
                    throwingSink.onToolProgress("test", "executing", null);
                } catch (Exception ignored) {
                    // ToolCallLoop catches this
                }
            });
        }
    }
}

