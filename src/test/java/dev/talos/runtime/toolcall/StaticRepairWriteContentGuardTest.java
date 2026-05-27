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

class StaticRepairWriteContentGuardTest {

    @Test
    void guardOwnsStaticRepairWriteContentClassificationAndFailureWording() throws Exception {
        String loopState = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/LoopState.java"));
        String breachGuard = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/PendingActionObligationBreachGuard.java"));
        String guard = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/StaticRepairWriteContentGuard.java"));

        assertTrue(loopState.contains("StaticRepairWriteContentGuard.evaluate(messages, calls)"),
                loopState);
        assertFalse(loopState.contains("StaticRepairWriteContentGuard.invalidWriteDetail("),
                loopState);
        assertTrue(breachGuard.contains("StaticRepairWriteContentGuard.invalidWriteDetail("),
                breachGuard);
        assertFalse(loopState.contains("TemplatePlaceholderGuard"), loopState);
        assertFalse(loopState.contains("RepairPolicy.fullRewriteTargetsFromRepairContext(messages)"),
                loopState);
        assertFalse(loopState.contains("staticRepairInvalidWriteFailureAnswer("), loopState);

        assertTrue(guard.contains("RepairPolicy.fullRewriteTargetsFromRepairContext(messages)"),
                guard);
        assertTrue(guard.contains("TemplatePlaceholderGuard.looksLikeTemplatePlaceholder"),
                guard);
        assertTrue(guard.contains("[Action obligation failed: static repair write content was invalid.]"),
                guard);
    }

    @Test
    void missingContentFailsWithExistingReasonAndAnswer() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                repairMessages(),
                List.of(writeFile(Map.of("path", "styles.css"))));

        assertTrue(failure.isPresent());
        assertEquals(
                "STATIC_REPAIR_INVALID_WRITE_CONTENT: Static web repair rejected "
                        + "talos.write_file(styles.css) before apply because missing required "
                        + "`content` argument. No approval was requested and no file was changed.",
                failure.get().reason());
        assertEquals(
                "[Action obligation failed: static repair write content was invalid.]\n\n"
                        + "Static web repair rejected talos.write_file(styles.css) before apply "
                        + "because missing required `content` argument. No approval was requested "
                        + "and no file was changed.\n"
                        + "Talos stopped this turn deterministically.",
                failure.get().answer());
    }

    @Test
    void blankContentFailsWithExistingReasonAndAnswer() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                repairMessages(),
                List.of(writeFile(Map.of("path", "styles.css", "content", "   "))));

        assertTrue(failure.isPresent());
        assertEquals(
                "STATIC_REPAIR_INVALID_WRITE_CONTENT: Static web repair rejected "
                        + "talos.write_file(styles.css) before apply because empty or blank content. "
                        + "No approval was requested and no file was changed.",
                failure.get().reason());
        assertTrue(failure.get().answer().contains("empty or blank content"),
                failure.get().answer());
    }

    @Test
    void templatePlaceholderContentFailsWithExistingReason() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                repairMessages(),
                List.of(writeFile(Map.of("path", "styles.css", "content", "<updated_style_css_content>"))));

        assertTrue(failure.isPresent());
        assertEquals(
                "STATIC_REPAIR_INVALID_WRITE_CONTENT: Static web repair rejected "
                        + "talos.write_file(styles.css) before apply because literal "
                        + "template-placeholder content. No approval was requested and no file was changed.",
                failure.get().reason());
    }

    @Test
    void validTargetWriteContentDoesNotFail() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                repairMessages(),
                List.of(writeFile(Map.of("path", "styles.css", "content", "body { color: red; }\n"))));

        assertFalse(failure.isPresent());
    }

    @Test
    void nonTargetWriteDoesNotFailThisGuard() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                repairMessages(),
                List.of(writeFile(Map.of("path", "index.html", "content", ""))));

        assertFalse(failure.isPresent());
    }

    @Test
    void noRepairContextDoesNotFailThisGuard() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                List.of(ChatMessage.system("sys"), ChatMessage.user("Fix styles.css.")),
                List.of(writeFile(Map.of("path", "styles.css", "content", ""))));

        assertFalse(failure.isPresent());
    }

    @Test
    void alternateContentParameterNamesRemainAccepted() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                repairMessages(),
                List.of(writeFile(Map.of("path", "styles.css", "text", "body { margin: 0; }\n"))));

        assertFalse(failure.isPresent());
    }

    private static List<ChatMessage> repairMessages() {
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
                        """),
                ChatMessage.user("Fix the static web page."));
    }

    private static ToolCall writeFile(Map<String, String> parameters) {
        return new ToolCall("talos.write_file", parameters);
    }
}
