package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticSelectorRepairWriteGuardTest {

    @Test
    void guardOwnsStaticSelectorRepairFailureReasonAndAnswer() throws Exception {
        String loopState = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/LoopState.java"));
        String guard = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/StaticSelectorRepairWriteGuard.java"));

        assertTrue(loopState.contains("StaticSelectorRepairWriteGuard.evaluate(messages, calls)"),
                loopState);
        assertFalse(loopState.contains("StaticSelectorRepairGuard"), loopState);
        assertFalse(loopState.contains("staticSelectorRepairFailureAnswer("), loopState);

        assertTrue(guard.contains("StaticSelectorRepairGuard.violationForWrite"), guard);
        assertTrue(guard.contains("STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR"),
                guard);
        assertTrue(guard.contains(
                "[Action obligation failed: static selector repair write preserved verifier-known missing selectors.]"),
                guard);
    }

    @Test
    void cssSelectorViolationFailsWithExistingReasonAndAnswer() {
        var failure = StaticSelectorRepairWriteGuard.evaluate(
                cssRepairMessages(),
                List.of(writeFile("styles.css", ".button { color: red; }\nbody { margin: 0; }\n")));

        assertTrue(failure.isPresent());
        String detail = "Static selector repair rejected talos.write_file(styles.css) before apply "
                + "because the replacement still references verifier-known missing selector(s): .button. "
                + "No approval was requested and no file was changed.";
        assertEquals(
                "STATIC_SELECTOR_REPAIR_PRESERVED_MISSING_SELECTOR: " + detail,
                failure.get().reason());
        assertEquals(
                "[Action obligation failed: static selector repair write preserved verifier-known missing selectors.]\n\n"
                        + "Target: styles.css.\n"
                        + "Preserved selector(s): .button.\n"
                        + detail + "\n"
                        + "Talos stopped this turn deterministically.",
                failure.get().answer());
    }

    @Test
    void javascriptSelectorViolationFailsWithTargetAndSelector() {
        var failure = StaticSelectorRepairWriteGuard.evaluate(
                jsRepairMessages(),
                List.of(writeFile("scripts.js", """
                        document.querySelector('.missing-button').addEventListener('click', () => {
                          document.querySelector('#result').textContent = 'Clicked';
                        });
                        """)));

        assertTrue(failure.isPresent());
        assertTrue(failure.get().reason().contains("scripts.js"), failure.get().reason());
        assertTrue(failure.get().reason().contains(".missing-button"), failure.get().reason());
        assertTrue(failure.get().answer().contains("Preserved selector(s): .missing-button."),
                failure.get().answer());
    }

    @Test
    void replacementThatRemovesMissingSelectorDoesNotFail() {
        var failure = StaticSelectorRepairWriteGuard.evaluate(
                cssRepairMessages(),
                List.of(writeFile("styles.css", "body { margin: 0; }\n")));

        assertFalse(failure.isPresent());
    }

    @Test
    void noSelectorFactsDoesNotFail() {
        var failure = StaticSelectorRepairWriteGuard.evaluate(
                List.of(ChatMessage.system("sys"), ChatMessage.user("Fix styles.css.")),
                List.of(writeFile("styles.css", ".button { color: red; }\n")));

        assertFalse(failure.isPresent());
    }

    @Test
    void nonTargetWriteDoesNotFailThisGuard() {
        var failure = StaticSelectorRepairWriteGuard.evaluate(
                cssRepairMessages(),
                List.of(writeFile("index.html", ".button { color: red; }\n")));

        assertFalse(failure.isPresent());
    }

    private static List<ChatMessage> cssRepairMessages() {
        return List.of(
                ChatMessage.system("sys"),
                ChatMessage.system("""
                        [Static verification repair context]
                        Expected targets: index.html, scripts.js, styles.css

                        Previous static verification problems:
                        - CSS references missing class selectors: `.button`

                        Repair plan:
                        Full-file replacement targets: styles.css
                        - styles.css: You must use talos.write_file with complete corrected file content for styles.css.
                        - Verify static checks again before claiming completion.

                        [Current static selector facts]
                        I checked the selectors against the actual workspace files:

                        - HTML: `index.html`
                        - CSS: `styles.css`
                        - JavaScript: `scripts.js`

                        Observed in HTML:
                        - Classes: none
                        - IDs: `#result`

                        Mismatches found:
                        - CSS references missing class selectors: `.button`
                        """),
                ChatMessage.user("Fix the static web page."));
    }

    private static List<ChatMessage> jsRepairMessages() {
        return List.of(
                ChatMessage.system("sys"),
                ChatMessage.system("""
                        [Static verification repair context]
                        Expected targets: index.html, scripts.js, styles.css

                        Previous static verification problems:
                        - JavaScript references missing class selectors: `.missing-button`

                        Repair plan:
                        Full-file replacement targets: scripts.js
                        - scripts.js: You must use talos.write_file with complete corrected file content for scripts.js.
                        - Verify static checks again before claiming completion.

                        [Current static selector facts]
                        I checked the selectors against the actual workspace files:

                        - HTML: `index.html`
                        - CSS: `styles.css`
                        - JavaScript: `scripts.js`

                        Observed in HTML:
                        - Classes: none
                        - IDs: `#run-button`, `#result`

                        Mismatches found:
                        - JavaScript references missing class selectors: `.missing-button`
                        """),
                ChatMessage.user("Fix the static web page."));
    }

    private static ToolCall writeFile(String path, String content) {
        return new ToolCall("talos.write_file", Map.of(
                "path", path,
                "content", content));
    }
}
