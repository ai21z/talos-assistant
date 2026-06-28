package dev.talos.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TemplatePlaceholderGuard} - the classifier itself.
 *
 * <p>Anchored to the real transcript shape that destroyed a user's
 * {@code horror-synth-site} playground: {@code content} argument was
 * a bare placeholder identifier like {@code <updated_index_html_content>}.
 * The guard must catch that shape and only that shape - real file
 * content (even tiny stubs) must pass through.
 */
class TemplatePlaceholderGuardTest {

    @Test
    void transcriptObservedPlaceholdersAreFlagged() {
        // Exact strings from test-output.txt Turn 6.
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(
                "<updated_index_html_content>"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(
                "<updated_style_css_content>"));
    }

    @Test
    void otherCommonPlaceholderShapesAreFlagged() {
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<new_content>"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<YOUR_CODE_HERE>"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<TODO>"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<insert-content>"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("  <placeholder>  "),
                "surrounding whitespace must not save a placeholder");
    }

    @Test
    void leadingToolResultPlaceholderWithAppendedContentIsFlagged() {
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(
                "<content from talos.read_file>Release gate note"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(
                "<content from read_file>\nRelease gate note"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(
                "<content of README.md>Release gate note"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(
                "<read_file_content>\nRelease gate note"));
    }

    @Test
    void leadingBracedTemplateVariableWithAppendedContentIsFlagged() {
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(
                "{previous_content}\nRelease gate note"));
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(
                "{current_file_content}Release gate note"));
    }

    @Test
    void realFileContentIsNotFlagged() {
        // Tiny but real stubs - the guard must not false-positive these.
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<html></html>"),
                "closing tag present - not a placeholder");
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<div>hi</div>"));
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<meta charset=\"UTF-8\">"),
                "tag with attributes - real HTML");
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("// TODO"),
                "code comment - no angle brackets");
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("body { margin: 0; }"),
                "CSS stub - no angle brackets");
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<h1>Hello</h1>\n<p>world</p>"),
                "multi-line content must pass through");
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("Hello <name>, welcome."),
                "placeholder inside prose - not a bare placeholder");
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<p>content from talos.read_file</p>"),
                "real tagged content must not be treated as a placeholder");
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("{\"name\":\"Talos\"}"),
                "JSON object content must not be treated as a placeholder");
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("{ color: red; }"),
                "CSS block content must not be treated as a placeholder");
    }

    @Test
    void edgeCasesArePermissive() {
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(null));
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(""));
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("   "));
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<"));
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(">"));
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<>"));
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder("<123>"),
                "leading digit is not a valid identifier - permissive");
    }

    @Test
    void oversizedContentIsNotFlagged() {
        // 121+ char single-token placeholder - unrealistic; the guard
        // only targets short template debris.
        String long120 = "<" + "a".repeat(118) + ">";  // exactly 120 chars
        String long121 = "<" + "a".repeat(119) + ">";  // 121 chars
        assertTrue(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(long120));
        assertFalse(TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(long121));
    }

    @Test
    void rejectionMessageMentionsToolAndParam() {
        String msg = TemplatePlaceholderGuard.rejectionMessage(
                "talos.write_file", "content", "<updated_foo>");
        assertTrue(msg.contains("talos.write_file"));
        assertTrue(msg.contains("content"));
        assertTrue(msg.contains("<updated_foo>"));
        // Model-directed - must not blame the user (avoids qwen's
        // "permissions" hallucination loop).
        assertFalse(msg.toLowerCase().contains("permissions"),
                "rejection must not anchor model to a 'permissions' narrative");
        assertFalse(msg.toLowerCase().contains("user did not approve"),
                "this is a pre-approval rejection, not a denial");
    }
}

