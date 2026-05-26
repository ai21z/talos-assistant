package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRepairInspectionBudgetGateTest {

    @TempDir
    Path workspace;

    @Test
    void nonRepairReadOnlyTurnDoesNotStop() {
        LoopState state = readOnlyInspectionState(
                "Read config.json and tell me the name.",
                List.of("config.json"),
                2);

        Optional<Boolean> result = ToolRepairInspectionBudgetGate.tryStop(state, 2);

        assertTrue(result.isEmpty());
        assertFalse(state.failureDecision.shouldStop());
    }

    @Test
    void repairBudgetExhaustionStopsWithDeterministicInspectionOnlyAnswerAndTrace() {
        LoopState state = readOnlyInspectionState(
                "Review the BMI calculator you just created and fix any obvious issue "
                        + "that would stop it from working in a browser.",
                List.of("index.html", "styles.css", "scripts.js"),
                3);

        LocalTurnTraceCapture.begin(
                "trc-t499-repair-budget",
                "sid",
                1,
                "2026-05-26T00:00:00Z",
                "workspace-hash",
                "test",
                "scripted",
                "test-model",
                "Review and fix the BMI calculator.");
        try {
            Optional<Boolean> result = ToolRepairInspectionBudgetGate.tryStop(state, 3);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals(Optional.of(false), result);
            assertTrue(state.failureDecision.shouldStop());
            assertTrue(state.failureDecision.reason().contains("REPAIR_INSPECTION_ONLY"),
                    state.failureDecision.reason());
            assertTrue(state.currentText.contains("repair/fix turn inspected files but did not change them"),
                    state.currentText);
            assertTrue(state.currentNativeCalls.isEmpty());

            var event = trace.events().stream()
                    .filter(e -> "ACTION_OBLIGATION_EVALUATED".equals(e.type()))
                    .filter(e -> "REPAIR_INSPECTION_ONLY".equals(e.data().get("failureKind")))
                    .findFirst()
                    .orElseThrow();
            assertEquals("CONDITIONAL_REVIEW_FIX", event.data().get("obligation"));
            assertEquals("FAILED", event.data().get("status"));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void conditionalReviewFixNoChangeStopsAndClearsPendingObligation() throws Exception {
        writePassingBmiFixture(workspace);
        LoopState state = readOnlyInspectionState(
                "Review the BMI calculator you just created and fix any obvious issue "
                        + "that would stop it from working in a browser.",
                List.of("index.html", "styles.css", "scripts.js"),
                3);
        state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("scripts.js")));

        Optional<Boolean> result = ToolRepairInspectionBudgetGate.tryStop(state, 3);

        assertEquals(Optional.of(false), result);
        assertFalse(state.failureDecision.shouldStop());
        assertTrue(state.currentText.contains("No file change was needed"), state.currentText);
        assertTrue(state.currentText.contains("No files were changed"), state.currentText);
        assertFalse(state.currentText.contains("repair/fix turn inspected files but did not change them"),
                state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
        assertFalse(state.hasPendingActionObligation());
    }

    @Test
    void repromptStageDelegatesRepairInspectionBudgetGateToOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("ToolRepairInspectionBudgetGate.tryStop"), source);
        assertFalse(source.contains("private static boolean repairReadOnlyBudgetExceeded"), source);
        assertFalse(source.contains("private static String conditionalRepairObligationName"), source);
    }

    private LoopState readOnlyInspectionState(
            String request,
            List<String> paths,
            int readOnlyAttempts
    ) {
        LoopState state = new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(request))),
                workspace,
                null,
                null,
                8,
                0);
        for (int i = 0; i < readOnlyAttempts; i++) {
            String path = paths.get(i % paths.size());
            state.toolNames.add("talos.read_file");
            state.pathsReadThisTurn.add(path);
            state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                    "talos.read_file",
                    path,
                    true,
                    false,
                    false,
                    "Read " + path,
                    ""));
        }
        return state;
    }

    private static void writePassingBmiFixture(Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <title>BMI Calculator</title>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <main class="app">
                    <h1>BMI Calculator</h1>
                    <form id="bmi-form">
                      <label>Height <input id="height" name="height" type="number"></label>
                      <label>Weight <input id="weight" name="weight" type="number"></label>
                      <button id="calculate" type="submit">Calculate</button>
                    </form>
                    <output id="result"></output>
                  </main>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                body { font-family: system-ui; }
                .app { max-width: 36rem; margin: 2rem auto; }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                const form = document.getElementById('bmi-form');
                const result = document.getElementById('result');
                form.addEventListener('submit', event => {
                  event.preventDefault();
                  const height = Number(document.getElementById('height').value) / 100;
                  const weight = Number(document.getElementById('weight').value);
                  const bmi = weight / (height * height);
                  result.textContent = `BMI: ${bmi.toFixed(1)}`;
                });
                """);
    }
}
