package dev.talos.core.embed;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EmbeddingVectors#isValidVector(float[])} and
 * {@link EmbeddingVectors#normalizeEmbedInput(String)}.
 */
class EmbeddingsVectorValidationTest {

    // ─── isValidVector ───

    @Test
    void validVector_passes() {
        assertTrue(EmbeddingVectors.isValidVector(new float[]{0.1f, 0.2f, 0.3f}));
    }

    @Test
    void nanVector_rejected() {
        assertFalse(EmbeddingVectors.isValidVector(new float[]{0.1f, Float.NaN, 0.3f}));
    }

    @Test
    void infinityVector_rejected() {
        assertFalse(EmbeddingVectors.isValidVector(new float[]{0.1f, Float.POSITIVE_INFINITY, 0.3f}));
    }

    @Test
    void negativeInfinityVector_rejected() {
        assertFalse(EmbeddingVectors.isValidVector(new float[]{Float.NEGATIVE_INFINITY, 0.2f}));
    }

    @Test
    void allZeroVector_rejected() {
        assertFalse(EmbeddingVectors.isValidVector(new float[]{0.0f, 0.0f, 0.0f}));
    }

    @Test
    void singleNonZero_passes() {
        assertTrue(EmbeddingVectors.isValidVector(new float[]{0.0f, 0.0f, 0.001f}));
    }

    @Test
    void emptyVector_rejected() {
        assertFalse(EmbeddingVectors.isValidVector(new float[]{}));
    }

    @Test
    void nullVector_rejected() {
        assertFalse(EmbeddingVectors.isValidVector(null));
    }

    // ─── normalizeEmbedInput (P0 fix) ───

    @Nested
    class NormalizeEmbedInput {

        @Test
        void normalText_unchanged() {
            assertEquals("hello world", EmbeddingVectors.normalizeEmbedInput("hello world"));
        }

        @Test
        void collapsesMultipleSpaces() {
            assertEquals("a b c", EmbeddingVectors.normalizeEmbedInput("a   b    c"));
        }

        @Test
        void collapsesTabs() {
            assertEquals("a b", EmbeddingVectors.normalizeEmbedInput("a\t\tb"));
        }

        @Test
        void preservesNewlines() {
            String result = EmbeddingVectors.normalizeEmbedInput("line1\nline2\nline3");
            assertTrue(result.contains("\n"), "Newlines must be preserved");
            assertTrue(result.contains("line1"));
            assertTrue(result.contains("line3"));
        }

        @Test
        void stripsControlChars() {
            // \x01 (SOH), \x02 (STX), \x7F (DEL) - should be stripped
            String result = EmbeddingVectors.normalizeEmbedInput("hello\u0001world\u0002test\u007F");
            assertEquals("helloworldtest", result);
            assertFalse(result.contains("\u0001"));
            assertFalse(result.contains("\u0002"));
            assertFalse(result.contains("\u007F"));
        }

        @Test
        void nullInput_returnsSingleSpace() {
            assertEquals(" ", EmbeddingVectors.normalizeEmbedInput(null));
        }

        @Test
        void emptyInput_returnsSingleSpace() {
            assertEquals(" ", EmbeddingVectors.normalizeEmbedInput(""));
        }

        @Test
        void blankInput_returnsSingleSpace() {
            assertEquals(" ", EmbeddingVectors.normalizeEmbedInput("   "));
        }

        @Test
        void trims_leadingAndTrailing() {
            assertEquals("hello", EmbeddingVectors.normalizeEmbedInput("  hello  "));
        }

        @Test
        void realWorldQuery_preserved() {
            String query = "Review test-website/index.html for accessibility issues";
            assertEquals(query, EmbeddingVectors.normalizeEmbedInput(query));
        }
    }
}

