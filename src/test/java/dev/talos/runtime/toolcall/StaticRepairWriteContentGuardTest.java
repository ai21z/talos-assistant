package dev.talos.runtime.toolcall;

import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.util.ArrayList;

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

        assertTrue(loopState.contains("StaticRepairWriteContentGuard.evaluate(messages, calls, workspace)"),
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
    void missingContentFailsForWriteFileAlias() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                repairMessages(),
                List.of(new ToolCall("file_utils:write_file", Map.of("path", "styles.css"))));

        assertTrue(failure.isPresent());
        assertEquals(
                "STATIC_REPAIR_INVALID_WRITE_CONTENT: Static web repair rejected "
                        + "talos.write_file(styles.css) before apply because missing required "
                        + "`content` argument. No approval was requested and no file was changed.",
                failure.get().reason());
    }

    @Test
    void validWriteFileAliasContentDoesNotFail() {
        var failure = StaticRepairWriteContentGuard.evaluate(
                repairMessages(),
                List.of(new ToolCall("file_utils:write_file", Map.of(
                        "path", "styles.css",
                        "content", "body { color: red; }\n"))));

        assertFalse(failure.isPresent());
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

    @Test
    void invalidWriteUnderTheAbsoluteSpellingOfARepairTargetFailsAsUnderTheRelativeSpelling(
            @TempDir Path temporary) throws IOException {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("web"));
        Files.writeString(root.resolve("web").resolve("styles.css"), ".button { color: red; }\n");
        String absolute = root.resolve("web").resolve("styles.css").toString();

        for (String spelling : List.of("web/styles.css", absolute)) {
            LoopState blank = repairStateAt(root, "web/styles.css");
            assertTrue(blank.failStaticRepairAfterInvalidWriteContent(
                    List.of(writeFile(Map.of("path", spelling, "content", "   ")))), spelling);
            assertTrue(blank.currentText.contains("because empty or blank content"), blank.currentText);

            LoopState placeholder = repairStateAt(root, "web/styles.css");
            assertTrue(placeholder.failStaticRepairAfterInvalidWriteContent(
                    List.of(writeFile(Map.of("path", spelling, "content", "<updated_style_css_content>")))),
                    spelling);
            assertTrue(placeholder.currentText.contains("literal template-placeholder content"),
                    placeholder.currentText);
        }
    }

    @Test
    void invalidWriteOfASameNamedFileElsewhereIsNotThisGuardsTarget(@TempDir Path temporary) throws IOException {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("web"));
        Files.createDirectories(root.resolve("archive"));
        Files.writeString(root.resolve("web").resolve("styles.css"), ".button { color: red; }\n");
        Files.writeString(root.resolve("archive").resolve("styles.css"), "old\n");
        LoopState state = repairStateAt(root, "web/styles.css");

        assertFalse(state.failStaticRepairAfterInvalidWriteContent(List.of(writeFile(Map.of(
                "path", root.resolve("archive").resolve("styles.css").toString(),
                "content", "   ")))));
    }

    private static LoopState repairStateAt(Path root, String targets) {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.system("""
                        [Static verification repair context]
                        Previous static verification problems:
                        - CSS references missing class selectors: `.button`

                        Repair plan:
                        Full-file replacement targets: %s
                        - Verify static checks again before claiming completion.
                        """.formatted(targets)),
                ChatMessage.user("Fix the static web page.")));
        return new LoopState("", List.of(), messages, root, null, null, 10, 0);
    }
}
