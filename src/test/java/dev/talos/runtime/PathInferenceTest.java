package dev.talos.runtime;

import dev.talos.tools.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the path safety logic in ToolCallLoop.repairMissingPath().
 *
 * <p>After the 2026-04-12 safety review, path inference for mutating tools
 * (write_file, edit_file) was disabled because it silently wrote files to
 * guessed targets. The method now returns the call as-is when the path is
 * missing, letting the tool produce its own clear error message.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>Missing path → call returned unchanged (tool will error)</li>
 *   <li>Path present → call returned unchanged (no interference)</li>
 *   <li>Path alias present (file_path) → call returned unchanged</li>
 *   <li>Non-write tools → call returned unchanged (not our concern)</li>
 * </ul>
 */
class PathInferenceTest {

    /**
     * write_file with missing path: should NOT infer - returns call as-is.
     * The tool itself will produce a "missing path" error.
     */
    @Test
    void repair_doesNotInferPathForWriteFile() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "content", "<!DOCTYPE html>"));

        ToolCall result = ToolCallLoop.repairMissingPath(call);

        assertSame(call, result,
                "Should return original call as-is - no path inference for mutating tools");
        assertNull(result.param("path"),
                "Path should remain null - tool will produce its own error");
    }

    /**
     * edit_file with missing path: should NOT infer - returns call as-is.
     */
    @Test
    void repair_doesNotInferPathForEditFile() {
        ToolCall call = new ToolCall("talos.edit_file", Map.of(
                "old_string", "foo",
                "new_string", "bar"));

        ToolCall result = ToolCallLoop.repairMissingPath(call);

        assertSame(call, result,
                "Should return original call as-is - no path inference for mutating tools");
    }

    /**
     * No repair needed: path already present on write_file.
     */
    @Test
    void repair_noRepairWhenPathPresent() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "path", "app.js",
                "content", "hello"));

        ToolCall result = ToolCallLoop.repairMissingPath(call);

        assertSame(call, result, "Should not modify when path is already present");
    }

    /**
     * Path alias present: file_path instead of path.
     * Should return unchanged (alias is present).
     */
    @Test
    void repair_noRepairWhenAliasPresent() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "file_path", "app.js",
                "content", "hello"));

        ToolCall result = ToolCallLoop.repairMissingPath(call);

        assertSame(call, result, "Should not modify when file_path alias is present");
    }

    /**
     * Non-write tools are not checked at all - returned as-is.
     */
    @Test
    void repair_noRepairForReadFile() {
        ToolCall call = new ToolCall("talos.read_file", Map.of());

        ToolCall result = ToolCallLoop.repairMissingPath(call);

        assertSame(call, result, "Should not touch read_file calls");
    }

    /**
     * Non-write tools: grep is returned unchanged.
     */
    @Test
    void repair_noRepairForGrep() {
        ToolCall call = new ToolCall("talos.grep", Map.of(
                "pattern", "TODO"));

        ToolCall result = ToolCallLoop.repairMissingPath(call);

        assertSame(call, result, "Should not touch grep calls");
    }

    /**
     * Exact reproduction of test-output.txt Turn 3 failure scenario.
     * The model called write_file with only "content" - no "path" at all.
     * Previously this would infer "index.html" from context. Now it must
     * return the call as-is so the tool produces a clear error and the
     * model retries with an explicit path.
     */
    @Test
    void endToEnd_testOutputTurn3_noLongerInfersPath() {
        ToolCall call = new ToolCall("talos.write_file", Map.of(
                "content", "<!DOCTYPE html>\n<html>..."));

        ToolCall result = ToolCallLoop.repairMissingPath(call);

        assertSame(call, result,
                "Should NOT infer path - the old inference silently wrote to wrong targets");
        assertNull(result.param("path"),
                "Path should remain null - FileWriteTool will produce a clear error");
        assertEquals("<!DOCTYPE html>\n<html>...", result.param("content"),
                "Content should be preserved unchanged");
    }
}
