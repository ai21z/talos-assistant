package dev.loqj.core.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for answer sanitization: strip preambles and model-added Sources/Citations blocks.
 */
public class AnswerSanitizationTest {

    @Test
    public void testStripPreamble_Okay() {
        String input = "Okay, let me explain this.\n\nThe actual answer is here.";
        String sanitized = invokeSanitizeAnswer(input);

        assertFalse(sanitized.startsWith("Okay"), "Should strip 'Okay' preamble");
        assertTrue(sanitized.contains("actual answer"), "Should preserve actual content");
    }

    @Test
    public void testStripPreamble_Sure() {
        String input = "Sure! Here's what you need to know:\n\nContent here.";
        String sanitized = invokeSanitizeAnswer(input);

        assertFalse(sanitized.toLowerCase().startsWith("sure"), "Should strip 'Sure' preamble");
        assertTrue(sanitized.contains("Content"), "Should preserve content");
    }

    @Test
    public void testStripPreamble_LetMe() {
        String input = "Let me help you with that.\n\nActual answer content.";
        String sanitized = invokeSanitizeAnswer(input);

        assertFalse(sanitized.toLowerCase().startsWith("let me"), "Should strip 'Let me' preamble");
        assertTrue(sanitized.contains("Actual answer"), "Should preserve answer");
    }

    @Test
    public void testStripModelAddedSources() {
        String input = "Here is the answer.\n\nSources:\n - file1.md\n - file2.md";
        String sanitized = invokeSanitizeAnswer(input);

        assertTrue(sanitized.contains("answer"), "Should keep answer text");
        assertFalse(sanitized.toLowerCase().contains("sources:"), "Should remove model-added sources");
    }

    @Test
    public void testStripModelAddedCitations() {
        String input = "Answer text here.\n\n[Citations]\n - README.md\n - docs/guide.md";
        String sanitized = invokeSanitizeAnswer(input);

        assertTrue(sanitized.contains("Answer text"), "Should keep answer");
        assertFalse(sanitized.contains("[Citations]"), "Should remove model-added citations block");
    }

    @Test
    public void testNoPreambleOrSources() {
        String input = "This is a clean answer with no preamble or sources.";
        String sanitized = invokeSanitizeAnswer(input);

        assertEquals(input, sanitized, "Should not modify clean answers");
    }

    @Test
    public void testCombinedPreambleAndSources() {
        String input = "Sure, I can help!\n\nThe answer is 42.\n\nSources:\n - hitchhiker.md";
        String sanitized = invokeSanitizeAnswer(input);

        assertFalse(sanitized.toLowerCase().startsWith("sure"), "Should strip preamble");
        assertTrue(sanitized.contains("42"), "Should preserve answer");
        assertFalse(sanitized.toLowerCase().contains("sources"), "Should remove sources");
    }

    @Test
    public void testEmptyOrNullInput() {
        assertEquals("", invokeSanitizeAnswer(null), "Should handle null");
        assertEquals("", invokeSanitizeAnswer(""), "Should handle empty string");
        assertEquals("", invokeSanitizeAnswer("   "), "Should handle blank string");
    }

    // Helper to invoke private sanitizeAnswer method via reflection
    private String invokeSanitizeAnswer(String input) {
        try {
            Class<?> ragModeClass = Class.forName("dev.loqj.cli.modes.RagMode");
            Method method = ragModeClass.getDeclaredMethod("sanitizeAnswer", String.class);
            method.setAccessible(true);
            return (String) method.invoke(null, input);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke sanitizeAnswer", e);
        }
    }
}

