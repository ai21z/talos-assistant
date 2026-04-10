package dev.talos.core.embed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EmbeddingProfile} — identity, fingerprinting, built-in profiles.
 */
class EmbeddingProfileTest {

    // ── Built-in profiles ────────────────────────────────────────────────

    @Test
    void bgeM3ProfileHasExpectedValues() {
        EmbeddingProfile p = EmbeddingProfile.BGE_M3;
        assertEquals("ollama", p.provider());
        assertEquals("bge-m3", p.model());
        assertEquals(1024, p.dimensions());
        assertFalse(p.instructionAware());
        assertNull(p.queryInstruction());
        assertNull(p.documentInstruction());
        assertEquals(8192, p.maxInputTokens());
        assertTrue(p.normalize());
    }

    @Test
    void qwen3ProfileHasExpectedValues() {
        EmbeddingProfile p = EmbeddingProfile.QWEN3_EMBED_8B;
        assertEquals("vllm", p.provider());
        assertEquals("Qwen/Qwen3-Embedding-8B", p.model());
        assertEquals(1024, p.dimensions());
        assertTrue(p.instructionAware());
        assertNotNull(p.queryInstruction());
        assertTrue(p.queryInstruction().contains("Instruct:"));
        assertNull(p.documentInstruction());
        assertEquals(32768, p.maxInputTokens());
        assertTrue(p.normalize());
    }

    // ── Fingerprint ──────────────────────────────────────────────────────

    @Test
    void fingerprintIsDeterministic() {
        String f1 = EmbeddingProfile.BGE_M3.fingerprint();
        String f2 = EmbeddingProfile.BGE_M3.fingerprint();
        assertEquals(f1, f2);
    }

    @Test
    void fingerprintDiffersWhenProviderDiffers() {
        var a = new EmbeddingProfile("ollama", "model", 1024, false, null, null, 8192, true);
        var b = new EmbeddingProfile("vllm", "model", 1024, false, null, null, 8192, true);
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void fingerprintDiffersWhenModelDiffers() {
        var a = new EmbeddingProfile("ollama", "bge-m3", 1024, false, null, null, 8192, true);
        var b = new EmbeddingProfile("ollama", "other-model", 1024, false, null, null, 8192, true);
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void fingerprintDiffersWhenDimensionsDiffer() {
        var a = new EmbeddingProfile("ollama", "model", 1024, false, null, null, 8192, true);
        var b = new EmbeddingProfile("ollama", "model", 4096, false, null, null, 8192, true);
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void fingerprintDiffersWhenInstructionAwarenessDiffers() {
        var a = new EmbeddingProfile("ollama", "model", 1024, false, null, null, 8192, true);
        var b = new EmbeddingProfile("ollama", "model", 1024, true, "instr", null, 8192, true);
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void fingerprintDiffersWhenNormalizationDiffers() {
        var a = new EmbeddingProfile("ollama", "model", 1024, false, null, null, 8192, true);
        var b = new EmbeddingProfile("ollama", "model", 1024, false, null, null, 8192, false);
        assertNotEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void fingerprintEncodesAllKeyFields() {
        String f = EmbeddingProfile.BGE_M3.fingerprint();
        assertTrue(f.contains("ollama"), "should contain provider");
        assertTrue(f.contains("bge-m3"), "should contain model");
        assertTrue(f.contains("1024"), "should contain dimensions");
        assertTrue(f.contains("plain"), "should contain instruction mode");
        assertTrue(f.contains("norm"), "should contain normalization");
    }

    // ── Cache namespace ──────────────────────────────────────────────────

    @Test
    void cacheNamespaceIsDeterministic() {
        assertEquals(
                EmbeddingProfile.BGE_M3.cacheNamespace(),
                EmbeddingProfile.BGE_M3.cacheNamespace());
    }

    @Test
    void cacheNamespaceForBgeM3MatchesLegacyKey() {
        // Must equal "ollama/bge-m3" to preserve existing Indexer cache keys
        assertEquals("ollama/bge-m3", EmbeddingProfile.BGE_M3.cacheNamespace());
    }

    @Test
    void cacheNamespaceIsolatesModels() {
        assertNotEquals(
                EmbeddingProfile.BGE_M3.cacheNamespace(),
                EmbeddingProfile.QWEN3_EMBED_8B.cacheNamespace());
    }

    // ── Query/document split detection ───────────────────────────────────

    @Test
    void bgeM3DoesNotRequireQueryDocSplit() {
        assertFalse(EmbeddingProfile.BGE_M3.requiresQueryDocumentSplit());
    }

    @Test
    void qwen3RequiresQueryDocSplit() {
        assertTrue(EmbeddingProfile.QWEN3_EMBED_8B.requiresQueryDocumentSplit());
    }

    @Test
    void customProfileWithoutInstructionsDoesNotRequireSplit() {
        var p = new EmbeddingProfile("x", "y", 768, false, null, null, 4096, true);
        assertFalse(p.requiresQueryDocumentSplit());
    }

    // ── Constructor validation ───────────────────────────────────────────

    @Test
    void nullProviderThrows() {
        assertThrows(NullPointerException.class, () ->
                new EmbeddingProfile(null, "model", 1024, false, null, null, 8192, true));
    }

    @Test
    void nullModelThrows() {
        assertThrows(NullPointerException.class, () ->
                new EmbeddingProfile("ollama", null, 1024, false, null, null, 8192, true));
    }
}

