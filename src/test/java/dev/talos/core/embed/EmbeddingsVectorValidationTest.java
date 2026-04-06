package dev.talos.core.embed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EmbeddingsClient#isValidVector(float[])}: NaN, Infinity,
 * all-zero, and valid vectors.
 */
class EmbeddingsVectorValidationTest {

    @Test
    void validVector_passes() {
        assertTrue(EmbeddingsClient.isValidVector(new float[]{0.1f, 0.2f, 0.3f}));
    }

    @Test
    void nanVector_rejected() {
        assertFalse(EmbeddingsClient.isValidVector(new float[]{0.1f, Float.NaN, 0.3f}));
    }

    @Test
    void infinityVector_rejected() {
        assertFalse(EmbeddingsClient.isValidVector(new float[]{0.1f, Float.POSITIVE_INFINITY, 0.3f}));
    }

    @Test
    void negativeInfinityVector_rejected() {
        assertFalse(EmbeddingsClient.isValidVector(new float[]{Float.NEGATIVE_INFINITY, 0.2f}));
    }

    @Test
    void allZeroVector_rejected() {
        assertFalse(EmbeddingsClient.isValidVector(new float[]{0.0f, 0.0f, 0.0f}));
    }

    @Test
    void singleNonZero_passes() {
        assertTrue(EmbeddingsClient.isValidVector(new float[]{0.0f, 0.0f, 0.001f}));
    }

    @Test
    void emptyVector_rejected() {
        assertFalse(EmbeddingsClient.isValidVector(new float[]{}));
    }

    @Test
    void nullVector_rejected() {
        assertFalse(EmbeddingsClient.isValidVector(null));
    }
}

