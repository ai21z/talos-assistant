package dev.talos.runtime;

import dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip contract between the history-chrome emitters and the stripper
 * (T767): every status line Talos itself injects into assistant text must be
 * removed by {@link MemoryUpdateListener#stripUiChromeForHistory} before the
 * text reaches conversation history, or code-tuned local models memorize the
 * format and start emitting fake status lines as prose (the documented BUG #1
 * confidence-trick failure mode).
 *
 * <p>Each literal below mirrors a concrete emitter site (pinned by file
 * reference) so an emitter rewording that breaks stripping fails here, not in
 * a live transcript.
 */
class UiChromeContractTest {

    private static String strip(String text) {
        return MemoryUpdateListener.stripUiChromeForHistory(text);
    }

    private static void assertStripped(String chromeLine) {
        String text = "Real prose before.\n" + chromeLine + "\nReal prose after.";
        assertEquals("Real prose before.\n\nReal prose after.".replace("\n\n", "\n"),
                strip(text).replace("\n\n", "\n"),
                "chrome line must be stripped from history: " + chromeLine);
    }

    // ── Real emitter round-trips (cheap to construct) ────────────────────

    @Test
    void toolSummaryFormatterOutputIsStripped() {
        // pins ToolLoopResultSummaryFormatter.format
        var result = new ToolCallLoop.LoopResult(
                "answer", 2, 2, List.of("talos.read_file", "talos.edit_file"),
                null, 1, 0, true, 1, null, 0, 0, 0, 0, null, null, Map.of());
        String summary = ToolLoopResultSummaryFormatter.format(result);
        assertTrue(summary.startsWith("[Used 2 tool(s)"), summary);
        assertTrue(summary.contains("[iteration limit reached]"), summary);
        assertStripped(summary);
    }

    @Test
    void iterationLimitNoticeIsStripped() {
        // pins ToolLoopFinalAnswerFinalizer.ITERATION_LIMIT
        String withNotice = ToolLoopFinalAnswerFinalizer.withIterationLimitNotice("Partial answer.");
        String stripped = strip(withNotice);
        assertEquals("Partial answer.", stripped);
    }

    // ── Literal mirrors of emitter sites ─────────────────────────────────

    @Test
    void turnAbortedSentinelsAreStripped() {
        // pins LlmCallBudget wall-clock / idle / repetition / interrupt branches
        assertStripped("[turn aborted: assistant turn exceeded 120s wall-clock budget — model is hung]");
        assertStripped("[turn aborted: assistant turn produced no tokens for 30s — model appears wedged.]");
        assertStripped("[turn aborted: interrupted]");
    }

    @Test
    void toolCallLimitNoticeIsStripped() {
        // pins ToolLoopFinalAnswerFinalizer.ITERATION_LIMIT literal form
        assertStripped("[Tool-call limit reached. Some tool calls were not executed.]");
    }

    @Test
    void engineErrorWrappersAreStripped() {
        // pins ToolRepromptChatExecutor / ToolRepromptOverlayContinuation /
        // AssistantTurnExecutor engine-failure appends
        assertStripped("[Engine error during tool loop: connection reset]");
        assertStripped("[Engine error: Malformed engine response for chat completion. Retry.]");
        assertStripped("[Engine error: boom]");
    }

    @Test
    void modelNotFoundWrappersAreStripped() {
        // pins ToolReprompt* and AssistantTurnExecutor model-not-found appends
        assertStripped("[Model 'qwen2.5-coder:14b' not found — tool loop aborted. Pull the model.]");
        assertStripped("[Model 'qwen2.5-coder:14b' not found. Pull the model.]");
    }

    @Test
    void mutationSuccessSummariesAreStripped() {
        // pins ToolMutationStateAccounting ("✓ " + firstSentence(toolOutput))
        // composed with FileEditTool ("Edited <path>: ...") and FileWriteTool
        // ("Created <path> ...") output shapes.
        assertStripped("✓ Edited script.js: replaced 1 line(s) at line 3");
        assertStripped("✓ Created notes/new-file.md (120 bytes)");
    }

    @Test
    void editFailureSuggestionIsStripped() {
        // pins EditFailureRepairStateAccounting cushion suggestion
        assertStripped("Suggestion: edit_file has failed on this file multiple times. "
                + "Consider using talos.write_file with the complete updated file content instead.");
    }

    @Test
    void lowercasedErrorFormsStillGateMemorizability() {
        // pins MemoryUpdateListener.isMemorizableAssistantReply lowercased checks
        var streamed = new Result.Streamed("[Engine error: boom]", "");
        assertTrue(!MemoryUpdateListener.isMemorizableAssistantReply(
                streamed, "[engine error: boom]"));
        assertTrue(!MemoryUpdateListener.isMemorizableAssistantReply(
                streamed, "[model 'x' not found. pull it.]"));
    }

    @Test
    void modelProseWithBracketsIsPreserved() {
        // Stripping is whole-line prefix-scoped: genuine model prose that
        // merely contains brackets or a checkmark mid-line must survive.
        String prose = "The fix is to use [Used cars] as the heading.\n"
                + "Done - the file shows ✓ Edited in its log section.";
        assertEquals(prose, strip(prose));
    }

    // ── Known gap, pinned deliberately (fixed in T768) ───────────────────

    @Test
    void updatedMutationSummaryCurrentlySurvivesStripping_knownGap() {
        // FileWriteTool emits "Updated <path> ..." on overwrite, so the
        // composed "✓ Updated ..." status line is NOT in the stripper's
        // prefix list and leaks into conversation history — the same
        // confidence-trick surface as BUG #1. Pinned here as current
        // behavior; T768 flips this expectation when the gap is fixed.
        String chrome = "✓ Updated app.css (310 bytes)";
        String text = "Prose.\n" + chrome;
        assertTrue(strip(text).contains(chrome),
                "documents the T768 gap: '✓ Updated' lines are not yet stripped");
    }
}
