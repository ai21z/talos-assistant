package dev.talos.engine.ollama;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard for the system-message merge behavior in OllamaEngine.
 *
 * <p>Background: {@code chatViaMessages} / {@code chatStreamViaMessages}
 * used to extract system messages with a simple overwrite loop, which meant
 * the LAST system message in the request won. When {@code ToolCallLoop}
 * appends a transient task-anchor system message before a re-prompt, that
 * anchor silently clobbered the real 7345-char system prompt, leaving the
 * model with ~118 chars of guidance (no tool rules, no behavior rules).
 * Against gemma4:31b Q4 this produced multi-minute think-spins.
 *
 * <p>These tests pin the fix: multiple system messages are concatenated
 * with a blank-line separator, null/blank inputs are ignored, and an
 * all-empty input yields {@code null}.
 */
class OllamaEngineSystemMergeTest {

    @Test
    void mainPromptPlusTaskAnchor_concatenatedNotReplaced() {
        String main = "You are a local assistant. Behavior rules: ...";  // ~7k chars in prod
        String anchor = "[Current task — stay focused on this] make index.html darker";

        String merged = OllamaEngine.mergeSystemMessages(List.of(main, anchor));

        assertNotNull(merged);
        assertTrue(merged.contains(main), "main system prompt must survive the merge");
        assertTrue(merged.contains(anchor), "task anchor must be appended");
        assertTrue(merged.length() >= main.length() + anchor.length(),
                "merged length must include both parts");
    }

    @Test
    void separatorIsBlankLineBetweenMessages() {
        String merged = OllamaEngine.mergeSystemMessages(List.of("A", "B"));
        assertEquals("A\n\nB", merged);
    }

    @Test
    void blankAndNullEntriesAreIgnored() {
        String merged = OllamaEngine.mergeSystemMessages(
                Arrays.asList("real prompt", "", "   ", null, "anchor"));
        assertEquals("real prompt\n\nanchor", merged);
    }

    @Test
    void emptyListYieldsNull() {
        assertNull(OllamaEngine.mergeSystemMessages(Collections.emptyList()));
    }

    @Test
    void allBlankInputsYieldNull() {
        assertNull(OllamaEngine.mergeSystemMessages(Arrays.asList("", "   ", null)));
    }

    @Test
    void singleMessagePassesThroughUnchanged() {
        String only = "just the main prompt";
        assertEquals(only, OllamaEngine.mergeSystemMessages(List.of(only)));
    }

    @Test
    void appendSystem_idempotentOnBlankBuffer() {
        StringBuilder b = new StringBuilder();
        OllamaEngine.appendSystem(b, null);
        OllamaEngine.appendSystem(b, "");
        OllamaEngine.appendSystem(b, "   ");
        assertEquals(0, b.length(),
                "blank/null inputs must not introduce leading separators");
        OllamaEngine.appendSystem(b, "real");
        assertEquals("real", b.toString(),
                "first real content must start at position 0 (no leading \\n\\n)");
    }

    @Test
    void threeMessagesChainedCorrectly() {
        String merged = OllamaEngine.mergeSystemMessages(List.of("A", "B", "C"));
        assertEquals("A\n\nB\n\nC", merged);
    }
}

