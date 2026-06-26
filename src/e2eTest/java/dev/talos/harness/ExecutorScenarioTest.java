package dev.talos.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * End-to-end executor-path scenarios — the N4 seam in action.
 *
 * <p>These scenarios drive {@link dev.talos.cli.modes.AssistantTurnExecutor#execute}
 * through {@link ScenarioRunner#runThroughExecutor(ScenarioDefinition, String, List)}
 * with a scripted {@link dev.talos.core.llm.LlmClient}. The key
 * difference from {@link AnswerAssertionScenariosTest} is that the
 * R2 / R6 / N3 gates — which live inside the executor — actually
 * fire on this path. That closes the caveat
 * {@code AssistantTurnExecutorTest.TranscriptRegressions} carries
 * in its class Javadoc: the static-gate anchors there test each
 * gate in isolation, but never exercised the gates through the
 * executor's full streaming / non-streaming / tool-loop pipeline.
 *
 * <p>Scope note: this suite deliberately ships with a single scenario
 * (T5 end-to-end). The purpose of N4 is to prove the seam works and
 * unblock future transcript-shaped end-to-end scenarios. Each addition
 * should pin a distinct transcript failure shape; do not accumulate
 * redundant variants of the same shape here.
 */
class ExecutorScenarioTest {

    @Test
    @DisplayName("T5 end-to-end: scripted false-mutation claim → R2 annotates through executor")
    void t5_false_mutation_claim_end_to_end() {
        // ── Fixture ────────────────────────────────────────────────
        //
        // Workspace has an index.html whose content is known. The
        // user's verbatim T5-shape request asks for a mutation, but
        // the scripted model sequence will:
        //   (0) emit a read_file tool call — the model "inspects"
        //       but never writes.
        //   (1) emit the verbatim T5 false-mutation claim — no tool
        //       calls, just prose.
        // R2 (annotateIfFalseMutationClaim) must then prepend
        // FALSE_MUTATION_ANNOTATION because mutatingToolSuccesses == 0
        // but the answer claims the edit was applied. The actual file
        // must remain unchanged on disk.

        String originalHtml = """
                <!DOCTYPE html>
                <html>
                  <head><title>BMI Calculator</title></head>
                  <body>
                    <button id="cta">Start</button>
                  </body>
                </html>
                """;

        String readFileCall = """
                I'll first inspect index.html to see the current CTA text.
                ```json
                {"name": "read_file", "parameters": {"path": "index.html"}}
                ```
                """;

        // Verbatim Turn-5 phrasing from test-output.txt.
        String falseMutationClaim =
                "I've updated the CTA button text to 'Let's Get Healthy'. "
              + "The changes have been applied to the `index.html` file.";

        var scenario = ScenarioDefinition.named("T5 end-to-end through executor")
                .withFile("index.html", originalHtml)
                .build();

        // ── Run through AssistantTurnExecutor.execute() ────────────
        try (var result = ScenarioRunner.runThroughExecutor(
                scenario,
                "Change the CTA button text to 'Let's Get Healthy' in index.html",
                List.of(readFileCall, falseMutationClaim))) {

            // ── T48 obligation failure must replace the false claim ─────────
            //
            // The executor's full pipeline ran: tool loop executed read_file
            // (0 mutating successes), the scripted model returned a false
            // mutation claim, and the retry still emitted no write/edit call.
            // The current-turn mutating-tool obligation now fails closed
            // instead of surfacing the false "changes applied" prose.
            result.assertAnswerContains("Talos can apply approved file changes in this workspace")
                  .assertAnswerContains("no files were changed")
                  .assertAnswerNotContains("changes have been applied");

            // ── N3 must NOT fire here ──────────────────────────────
            //
            // User prompt contains no INSPECT_REQUEST_MARKERS, so the
            // inspect-under-completion gate should stay silent and
            // only the R2 annotation should be prepended. If this
            // assertion starts failing, something has broadened the
            // N3 marker set into R6 / generic-request territory.
            result.assertAnswerNotContains("Inspect check:");

            // ── Filesystem parity: file is unchanged ───────────────
            //
            // This is the critical integrity check the static-gate
            // test (t5_falseMutationClaim_triggersR2) cannot make —
            // that test only exercises the annotator, not the full
            // pipeline. Here we prove that driving execute() with a
            // scripted read-only turn leaves the workspace untouched.
            result.assertFileContains("index.html", ">Start</button>")
                  .assertFileNotContains("index.html", "Let's Get Healthy");

            // ── Non-streaming path confirmation ────────────────────
            //
            // runThroughExecutor deliberately does not set a stream
            // sink; this asserts the current seam choice so a future
            // streaming variant shows up as a visible API change.
            assertFalse(result.streamed(),
                    "runThroughExecutor should drive the non-streaming branch");

            // T48 intentionally does not preserve the model-authored false
            // claim on an unsatisfied mutating-tool obligation.
            assertFalse(result.finalAnswer().contains(falseMutationClaim),
                    "False mutation prose must not survive obligation failure. Actual:\n"
                            + result.finalAnswer());
        }
    }
}

