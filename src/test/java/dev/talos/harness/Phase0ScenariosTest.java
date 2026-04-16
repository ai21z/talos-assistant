package dev.talos.harness;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 scenario harness — 10 deterministic, LLM-free scenarios.
 *
 * Scripted responses use XML tool call format so there are no escaping issues.
 * The ToolCallParser supports XML as a compatibility fallback.
 *
 * S1  - write_file creates a new file (empty workspace)
 * S2  - write_file overwrites an existing file
 * S3  - read_file then edit_file succeeds (read-before-write flow)
 * S4  - edit_file without prior read produces nudge hint
 * S5  - denied write approval: file must not be created
 * S6  - unknown tool name produces error result; loop survives
 * S7  - missing path on write_file produces error (no path inference)
 * S8  - grep returns matches from an existing file
 * S9  - list_dir returns workspace file listing
 * S10 - multi-tool turn: read + edit in one response
 */
@DisplayName("Phase 0 - Scenario Harness")
class Phase0ScenariosTest {

    // ── S1 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S1: write_file creates a new file in an empty workspace")
    void s1_writeFileCreatesNewFile() {
        var scenario = ScenarioDefinition.named("S1 create file")
            .withScriptedResponse(
                "I will create the file now.\n" +
                "<tool_call>{\"name\": \"talos.write_file\", \"parameters\": {\"path\": \"hello.txt\", \"content\": \"Hello, Talos!\"}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(1)
                  .assertNoFailedCalls()
                  .assertFileExists("hello.txt")
                  .assertFileContains("hello.txt", "Hello, Talos!");
        }
    }

    // ── S2 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S2: write_file overwrites an existing file with new content")
    void s2_writeFileOverwritesExistingFile() {
        var scenario = ScenarioDefinition.named("S2 overwrite file")
            .withFile("notes.txt", "old content")
            .withScriptedResponse(
                "Replacing the file.\n" +
                "<tool_call>{\"name\": \"talos.write_file\", \"parameters\": {\"path\": \"notes.txt\", \"content\": \"new content\"}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(1)
                  .assertNoFailedCalls()
                  .assertFileContains("notes.txt", "new content")
                  .assertFileNotContains("notes.txt", "old content");
        }
    }

    // ── S3 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S3: read_file then edit_file succeeds (read-before-write flow)")
    void s3_readThenEditSucceeds() {
        var scenario = ScenarioDefinition.named("S3 read then edit")
            .withFile("greeting.txt", "Hello world")
            .withScriptedResponse(
                "Reading first.\n" +
                "<tool_call>{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"greeting.txt\"}}</tool_call>\n" +
                "<tool_call>{\"name\": \"talos.edit_file\", \"parameters\": {\"path\": \"greeting.txt\", \"old_string\": \"Hello world\", \"new_string\": \"Hello Talos\"}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(2)
                  .assertNoFailedCalls()
                  .assertFileContains("greeting.txt", "Hello Talos")
                  .assertFileNotContains("greeting.txt", "Hello world");
        }
    }

    // ── S4 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S4: edit_file without prior read produces read-before-write nudge")
    void s4_editWithoutReadProducesNudge() {
        var scenario = ScenarioDefinition.named("S4 edit without read")
            .withFile("data.txt", "original")
            .withScriptedResponse(
                "<tool_call>{\"name\": \"talos.edit_file\", \"parameters\": {\"path\": \"data.txt\", \"old_string\": \"original\", \"new_string\": \"modified\"}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            // The loop may re-prompt the placeholder LLM which can produce more tool calls.
            // We only assert the nudge appeared — that is what B2 guarantees.
            assertTrue(result.toolsInvoked() >= 1, "At least 1 tool should be invoked");
            boolean nudge = result.anyToolResultContains("did not read this file")
                         || result.anyToolResultContains("read_file");
            assertTrue(nudge, "Tool result should contain read-before-write nudge");
        }
    }

    // ── S5 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S5: DENY_WRITES policy prevents file creation")
    void s5_deniedWriteDoesNotCreateFile() {
        var scenario = ScenarioDefinition.named("S5 denied write")
            .withScriptedResponse(
                "<tool_call>{\"name\": \"talos.write_file\", \"parameters\": {\"path\": \"secret.txt\", \"content\": \"private\"}}</tool_call>\n")
            .withApprovalPolicy(ScenarioApprovalPolicy.DENY_WRITES)
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertFileAbsent("secret.txt");
            result.assertToolsInvoked(1);
        }
    }

    // ── S6 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S6: unknown tool name produces error result; loop does not crash")
    void s6_unknownToolProducesError() {
        var scenario = ScenarioDefinition.named("S6 unknown tool")
            .withScriptedResponse(
                "<tool_call>{\"name\": \"talos.does_not_exist\", \"parameters\": {\"foo\": \"bar\"}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(1)
                  .assertFailedCalls(1);
            boolean hasError = result.anyToolResultContains("[error]")
                            || result.anyToolResultContains("error");
            assertTrue(hasError, "Tool result should contain an error for unknown tool");
        }
    }

    // ── S7 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S7: write_file with missing path parameter produces an error")
    void s7_missingPathProducesError() {
        var scenario = ScenarioDefinition.named("S7 missing path")
            .withScriptedResponse(
                "<tool_call>{\"name\": \"talos.write_file\", \"parameters\": {\"content\": \"no path here\"}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            // The scripted call must have failed (missing path).
            // The placeholder LLM may re-prompt and produce additional calls; we only
            // assert that at least one failure occurred on the path-less call.
            assertTrue(result.failedCalls() >= 1,
                "At least one write_file call must fail when path is missing");
        }
    }

    // ── S8 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S8: grep finds matches in an existing file")
    void s8_grepReturnsMatches() {
        var scenario = ScenarioDefinition.named("S8 grep")
            .withFile("code.js", "function calculate() {\n  return 42;\n}\n")
            .withScriptedResponse(
                "<tool_call>{\"name\": \"talos.grep\", \"parameters\": {\"pattern\": \"function\"}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(1)
                  .assertNoFailedCalls();
            assertTrue(result.anyToolResultContains("function"),
                "Grep result should contain matched line");
        }
    }

    // ── S9 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("S9: list_dir returns workspace file listing")
    void s9_listDirReturnsListing() {
        var scenario = ScenarioDefinition.named("S9 list_dir")
            .withFile("index.html", "<html></html>")
            .withFile("style.css", "body {}")
            .withScriptedResponse(
                "<tool_call>{\"name\": \"talos.list_dir\", \"parameters\": {}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(1)
                  .assertNoFailedCalls();
            boolean listed = result.anyToolResultContains("index.html")
                          || result.anyToolResultContains("style.css");
            assertTrue(listed, "list_dir result should mention workspace files");
        }
    }

    // ── S10 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("S10: multi-tool turn - read_file then edit_file in one response")
    void s10_multiToolTurnReadAndEdit() {
        var scenario = ScenarioDefinition.named("S10 multi-tool")
            .withFile("app.js", "const version = '1.0';\n")
            .withScriptedResponse(
                "First read, then edit.\n" +
                "<tool_call>{\"name\": \"talos.read_file\", \"parameters\": {\"path\": \"app.js\"}}</tool_call>\n" +
                "<tool_call>{\"name\": \"talos.edit_file\", \"parameters\": {\"path\": \"app.js\", \"old_string\": \"const version = '1.0';\", \"new_string\": \"const version = '2.0';\"}}</tool_call>\n")
            .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(2)
                  .assertNoFailedCalls()
                  .assertFileContains("app.js", "2.0")
                  .assertFileNotContains("app.js", "1.0");
        }
    }
}



