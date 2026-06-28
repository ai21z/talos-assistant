package dev.talos.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness-seam regression scenarios that exercise the new answer-content
 * assertion surface on {@link ScenarioResult}, and the end-to-end integration
 * of the widened fenced-JSON detection gate (Correction 1 / R1) through
 * {@link dev.talos.runtime.ToolCallLoop}.
 *
 * <h2>Seam discipline</h2>
 * These scenarios operate at the <b>harness seam</b>:
 * {@link ScenarioRunner} drives {@link dev.talos.runtime.ToolCallLoop} directly
 * and does <em>not</em> go through
 * {@code dev.talos.cli.modes.AssistantTurnExecutor}. So:
 *
 * <ul>
 *   <li>answer-text assertions here reflect what the tool loop itself
 *       produced, with its tool-call blocks stripped;</li>
 *   <li>assertions that depend on executor-layer truth (claim-vs-action
 *       annotation, post-tool synthesis retry, deflection gate) are
 *       <b>deliberately not attempted here</b> - they remain covered in
 *       {@code AssistantTurnExecutorTest}, which is the correct seam.</li>
 * </ul>
 *
 * <h2>Determinism</h2>
 * For prose-only scripted responses (no tool calls) the loop returns the
 * scripted text verbatim - assertions on the answer are fully deterministic.
 * For scenarios that fire tool calls, the re-prompt after execution goes to
 * the PLACEHOLDER LLM, whose output is non-deterministic; those scenarios
 * only assert on filesystem / tool outcomes, not on post-tool answer text.
 */
@DisplayName("Harness answer-assertion scenarios")
class AnswerAssertionScenariosTest {

    // ─────────────────────────────────────────────────────────────────
    // R3 - prove the new answer-assertion surface is useful
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R3.A: assertAnswerContains / NotContains work on prose-only scripted responses")
    void proseOnlyAnswerAssertions() {
        String scripted =
                "The workspace contains index.html with inline styles and an inline script. "
              + "No external stylesheet or script file is referenced.";

        var scenario = ScenarioDefinition.named("prose-only answer")
                .withScriptedResponse(scripted)
                .build();

        try (var result = ScenarioRunner.run(scenario)) {
            // Prose-only → tool loop returns the scripted text verbatim.
            result.assertToolsInvoked(0)
                  .assertNoFailedCalls()
                  .assertAnswerContains("inline styles")
                  .assertAnswerContains("No external stylesheet")
                  .assertAnswerNotContains("link rel=\"stylesheet\"")
                  .assertAnswerNotContains("script src=");

            // And the negative case: the helper actually fails when expected.
            assertThrows(AssertionError.class,
                    () -> result.assertAnswerContains("something not in the answer"),
                    "assertAnswerContains must fail when the substring is absent");
            assertThrows(AssertionError.class,
                    () -> result.assertAnswerNotContains("inline styles"),
                    "assertAnswerNotContains must fail when the substring is present");
        }
    }

    @Test
    @DisplayName("R3.B: harness can now demonstrate answer-vs-disk mismatch")
    void harnessCatchesFalseFileCreationClaim() {
        // The scripted response is prose-only and confidently claims a file
        // was created. No tool call is emitted, so no file is actually
        // created. The harness can now assert both halves of the mismatch:
        //   - the answer text makes the claim
        //   - the filesystem disproves it
        //
        // Note: this is NOT a test of the R2 claim-vs-action annotation -
        // that lives at the executor seam (see AssistantTurnExecutorTest
        // ClaimVsActionTests). This test demonstrates that the HARNESS
        // surface can now directly express the mismatch shape, which is the
        // whole point of R3.
        String scripted = "I have created `output.txt` with the requested content. "
                + "The file is now in your workspace.";

        var scenario = ScenarioDefinition.named("false creation claim (harness mismatch demo)")
                .withScriptedResponse(scripted)
                .build();

        try (var result = ScenarioRunner.run(scenario)) {
            result.assertToolsInvoked(0)           // no tool ever ran
                  .assertFileAbsent("output.txt")  // disk disproves the claim
                  .assertAnswerContains("I have created")   // answer makes the claim
                  .assertAnswerContains("output.txt");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // R4 - Transcript Turn 6 shape at the harness seam
    //
    // The parser-level unit coverage for fenced JSON with alias keys lives
    // in ToolCallParserTest (5 tests added in PR-1). This scenario proves
    // the same fix works end-to-end via ToolCallLoop + the real tool
    // registry: a model emitting `tool_name`/`params` aliases actually
    // reaches the tool executor and mutates the workspace.
    // ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R4.T6: fenced JSON with tool_name/params aliases reaches ToolCallLoop and writes the file")
    void turn6AliasKeysTriggerRealToolCallEndToEnd() {
        // Real Turn 6 pattern from test-output.txt: the model emitted a
        // fenced JSON block using "tool_name" and "params" instead of the
        // canonical "name"/"parameters". Before PR-1's CODE_FENCE_PATTERN
        // widening, this block was silently dropped at the detection gate
        // and the write was lost.
        String scripted = """
                I'll update the CTA button text now.
                ```json
                {"tool_name": "talos.write_file", "params": {"path": "index.html", "content": "<!doctype html><title>updated</title>"}}
                ```
                """;

        var scenario = ScenarioDefinition.named("turn6 fenced alias keys end-to-end")
                .withUserPrompt("Write index.html so the title becomes updated.")
                .withScriptedResponse(scripted)
                .build();

        try (var result = ScenarioRunner.run(scenario)) {
            // The tool actually ran. (Using >= because the PLACEHOLDER LLM
            // re-prompt may produce additional calls after our scripted
            // turn - same convention as Phase0ScenariosTest.)
            assertTrue(result.toolsInvoked() >= 1,
                    "Fenced JSON with tool_name/params alias must reach the tool executor "
                    + "(Turn 6 regression). Loop summary: " + result.loopResult().summary());

            // Deterministic truth: the scripted write succeeded on disk.
            result.assertFileExists("index.html")
                  .assertFileContains("index.html", "<title>updated</title>");

            // Post-tool answer text is non-deterministic (PLACEHOLDER
            // re-prompt) - we intentionally do NOT assert on it here.
        }
    }
}


