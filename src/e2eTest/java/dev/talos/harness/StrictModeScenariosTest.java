package dev.talos.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5 — Proves that {@link ScenarioRunner#runStrict} produces meaningfully
 * different behavior from the default {@link ScenarioRunner#run}, on two
 * measurement cushions that genuinely exist on the harness path:
 *
 * <ol>
 *   <li><b>Alias rescue</b> — {@link dev.talos.tools.ToolRegistry} fuzzy
 *       tool-name resolution. Normal mode rescues a non-canonical tool name;
 *       strict mode does not.</li>
 *   <li><b>Redundant read suppression</b> — {@link dev.talos.runtime.ToolCallLoop}
 *       in-turn cache of successful read-only calls. Normal mode suppresses
 *       the second identical read and injects an "already gathered" nudge;
 *       strict mode executes both reads.</li>
 * </ol>
 *
 * <p>Seam discipline: these tests operate at the harness seam only
 * ({@link ScenarioRunner} → {@link dev.talos.runtime.ToolCallLoop}).
 * They do not exercise {@code AssistantTurnExecutor},
 * {@code ConversationManager}, compaction, or session history — none of
 * which the scenario runner touches.
 */
@DisplayName("R5 — Strict-mode scenario runs")
class StrictModeScenariosTest {

    // ─────────────────────────────────────────────────────────────────
    // Difference 1 — Alias rescue (ToolRegistry)
    // ─────────────────────────────────────────────────────────────────

    /**
     * The scripted response uses the non-canonical tool name {@code write_file}
     * instead of {@code talos.write_file}. The {@link dev.talos.tools.ToolRegistry}
     * {@code ALIASES} table maps {@code write_file → talos.write_file}.
     *
     * <p>Normal mode: registry rescues it, the file is written, 0 failed calls.
     * Strict mode: registry returns {@code null}, the loop records a failure,
     * the file is NOT written.
     */
    @Test
    @DisplayName("alias rescue: normal resolves non-canonical tool name; strict does not")
    void aliasRescueDifference() {
        String scripted = """
                I'll write the file.
                ```json
                {"name": "write_file", "parameters": {"path": "out.txt", "content": "hello"}}
                ```
                """;

        var scenario = ScenarioDefinition.named("alias rescue")
                .withScriptedResponse(scripted)
                .withUserPrompt("Write out.txt with hello.")
                .build();

        // Normal mode — alias rescue is active.
        try (var normal = ScenarioRunner.run(scenario)) {
            normal.assertFileExists("out.txt")
                  .assertFileContains("out.txt", "hello")
                  .assertNoFailedCalls();
            assertTrue(normal.toolsInvoked() >= 1,
                    "Normal mode: aliased write must resolve and run. Summary: "
                            + normal.loopResult().summary());
        }

        // Strict mode — alias rescue disabled; the exact same scripted response
        // must NOT successfully write the file.
        try (var strict = ScenarioRunner.runStrict(scenario)) {
            strict.assertFileAbsent("out.txt");
            assertTrue(strict.failedCalls() >= 1,
                    "Strict mode: non-canonical tool name must fail at the registry. "
                            + "Summary: " + strict.loopResult().summary());
            assertTrue(
                    strict.anyToolResultContains("Unknown tool")
                            || strict.anyToolResultContains("write_file"),
                    "Strict mode: failure surface should mention the unresolved tool. "
                            + "Tool results: " + strict.toolResultTexts());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Difference 2 — Redundant read suppression (ToolCallLoop)
    // ─────────────────────────────────────────────────────────────────

    /**
     * The scripted response contains two identical {@code read_file} blocks
     * in a single turn. ToolCallLoop's successful-read cache, active in normal
     * mode, suppresses the second call and injects a canned
     * "you already gathered this information" nudge instead of re-executing.
     *
     * <p>Normal mode: {@code toolsInvoked() == 1} and the suppression nudge
     * is visible in the tool-result transcript.
     * Strict mode: {@code toolsInvoked() == 2}, both reads execute, no nudge.
     */
    @Test
    @DisplayName("redundant read suppression: normal skips the duplicate; strict re-executes it")
    void redundantReadSuppressionDifference() {
        // Two fenced blocks describing the SAME read_file call. The JSON text
        // differs (key order is swapped) so ToolCallParser's text-level dedup
        // does NOT collapse them — both reach the loop. At the loop level,
        // buildReadCallSignature normalizes on (tool, params) and treats them
        // as identical, which is what trips the redundant-read cushion in
        // normal mode and must NOT trip in strict mode.
        String scripted = """
                I'll check the file twice.
                ```json
                {"name": "talos.read_file", "parameters": {"path": "src.txt"}}
                ```
                ```json
                {"parameters": {"path": "src.txt"}, "name": "talos.read_file"}
                ```
                """;

        var scenario = ScenarioDefinition.named("redundant reads")
                .withFile("src.txt", "payload")
                .withScriptedResponse(scripted)
                .build();

        final String nudge = "already gathered this information";

        // Normal mode — second identical read is suppressed.
        try (var normal = ScenarioRunner.run(scenario)) {
            assertEquals(1, normal.toolsInvoked(),
                    "Normal mode: the 2nd identical read must be suppressed (not counted). "
                            + "Summary: " + normal.loopResult().summary());
            assertTrue(normal.anyToolResultContains(nudge),
                    "Normal mode: suppression nudge must appear in tool-result transcript. "
                            + "Transcript: " + normal.toolResultTexts());
        }

        // Strict mode — both reads execute, no nudge.
        try (var strict = ScenarioRunner.runStrict(scenario)) {
            assertEquals(2, strict.toolsInvoked(),
                    "Strict mode: both identical reads must execute. "
                            + "Summary: " + strict.loopResult().summary());
            assertFalse(strict.anyToolResultContains(nudge),
                    "Strict mode: suppression nudge must NOT be injected. "
                            + "Transcript: " + strict.toolResultTexts());
            strict.assertNoFailedCalls();
        }
    }
}


