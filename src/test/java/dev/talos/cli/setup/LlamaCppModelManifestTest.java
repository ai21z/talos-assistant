package dev.talos.cli.setup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppModelManifestTest {

    @Test
    void exposesOnlyAcceptedBetaWizardModelsWithPinnedHashesAndSizes() {
        var entries = LlamaCppModelManifest.acceptedBeta();

        assertEquals(2, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.alias().equals("qwen2.5-coder-14b")));
        assertTrue(entries.stream().anyMatch(e -> e.alias().equals("gpt-oss-20b")));

        var qwen = LlamaCppModelManifest.byAlias("qwen2.5-coder-14b").orElseThrow();
        assertEquals("Qwen/Qwen2.5-Coder-14B-Instruct-GGUF", qwen.hfRepo());
        assertEquals("qwen2.5-coder-14b-instruct-q4_k_m.gguf", qwen.hfFile());
        assertEquals(8_988_110_272L, qwen.sizeBytes());
        assertEquals("c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940", qwen.sha256());
        assertTrue(qwen.guidanceLine().contains("16 GB RAM minimum"), qwen.guidanceLine());
        assertTrue(qwen.guidanceLine().contains("large CPU model"), qwen.guidanceLine());
        assertTrue(qwen.guidanceLine().contains("not the low-resource lane"), qwen.guidanceLine());

        var gpt = LlamaCppModelManifest.byAlias("gpt-oss-20b").orElseThrow();
        assertEquals("ggml-org/gpt-oss-20b-GGUF", gpt.hfRepo());
        assertEquals("gpt-oss-20b-mxfp4.gguf", gpt.hfFile());
        assertEquals(12_109_566_560L, gpt.sizeBytes());
        assertEquals("be37a636aca0fc1aae0d32325f82f6b4d21495f06823b5fbc1898ae0303e9935", gpt.sha256());
        assertTrue(gpt.guidanceLine().contains("24 GB RAM minimum"), gpt.guidanceLine());
        assertTrue(gpt.guidanceLine().contains("large CPU model"), gpt.guidanceLine());
        assertTrue(gpt.guidanceLine().contains("not the low-resource lane"), gpt.guidanceLine());
    }

    @Test
    void modelPathLivesUnderTalosOwnedGgufCache() {
        var entry = LlamaCppModelManifest.byAlias("qwen2.5-coder-14b").orElseThrow();
        Path userHome = Path.of("/home/ai21z");
        Path path = entry.modelPath(userHome);

        assertEquals(userHome.resolve(".talos/models/gguf/qwen2.5-coder-14b/qwen2.5-coder-14b-instruct-q4_k_m.gguf")
                .toAbsolutePath()
                .normalize(), path);
    }
}
