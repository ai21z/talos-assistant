package dev.talos.engine.llamacpp;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppModelCatalogTest {

    private record ExistingProfile(
            String alias,
            String repository,
            String revision,
            String artifact,
            String sha256,
            long size,
            boolean nativeCalling,
            String toolMode,
            LlamaCppModelCatalog.Disposition disposition,
            boolean localPathRequired) {}

    @Test
    void ownsExistingProfilesAndEvidenceQualifiedQwen3() {
        assertEquals(List.of(
                        "qwen2.5-coder-14b",
                        "gpt-oss-20b",
                        "qwen36vf-q4km",
                        "qwen36vf-q6k",
                        "deepseek-v2lite-q4km",
                        "qwen3-8b"),
                LlamaCppModelCatalog.selectableProfiles().stream()
                        .map(LlamaCppModelCatalog.Profile::alias)
                        .toList());

        var qwen = LlamaCppModelCatalog.byAlias("qwen2.5-coder-14b").orElseThrow();
        assertEquals("Qwen/Qwen2.5-Coder-14B-Instruct-GGUF", qwen.repository());
        assertEquals("d0a692ef765eefbf2fabb130b3cb2e8917e3d225", qwen.revision());
        assertEquals("qwen2.5-coder-14b-instruct-q4_k_m.gguf", qwen.artifactFile());
        assertEquals("c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940",
                qwen.artifactSha256());
        assertEquals(8_988_110_272L, qwen.artifactSizeBytes());
        assertEquals("Apache-2.0", qwen.license());
        assertEquals(LlamaCppModelCatalog.Disposition.ACCEPTED_BETA, qwen.disposition());
        assertTrue(qwen.selectable());
        assertTrue(qwen.wizardDownload());
        assertEquals(8_192, qwen.runtime().contextTokens());
        assertEquals(16_384, qwen.runtime().maxContextTokens());
    }

    @Test
    void qwen3ProjectsTheExactImmutableExperimentalQualification() {
        var qwen3 = LlamaCppModelCatalog.byAlias("qwen3-8b").orElseThrow();

        assertEquals("Qwen/Qwen3-8B-GGUF", qwen3.repository());
        assertEquals("7c41481f57cb95916b40956ab2f0b139b296d974", qwen3.revision());
        assertEquals("Qwen3-8B-Q4_K_M.gguf", qwen3.artifactFile());
        assertEquals("d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
                qwen3.artifactSha256());
        assertEquals(5_027_783_488L, qwen3.artifactSizeBytes());
        assertEquals("Apache-2.0", qwen3.license());
        assertEquals(LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE, qwen3.disposition());
        assertTrue(qwen3.selectable());
        assertTrue(qwen3.requiresLocalModelPath());
        assertEquals(false, qwen3.wizardDownload());
        assertEquals(true, qwen3.nativeCalling());
        assertEquals("native/default", qwen3.toolMode());
        assertEquals(8_192, qwen3.runtime().contextTokens());
        assertEquals(8_192, qwen3.runtime().maxContextTokens());
        assertEquals(List.of("--reasoning", "off"), qwen3.runtime().managedServerArgs());
        assertEquals(List.of("win-x64-cuda-13.3:b9918"), qwen3.runtime().compatibleEngineLanes());
        assertEquals(5, qwen3.qualification().protocolRevision());
        assertEquals(
                "local/model-qualification/qwen3-8b-full-p5a15-3271f5f0-b9918-cuda13-20260712",
                qwen3.evidencePacket());
        assertEquals("3271f5f092fe826a882dd27bdbdfbe53a7904fc9", qwen3.qualification().talosCommit());
        assertEquals("91aa1e6a60aed548948d182fe17dab55b09d00a247a78dd9a3c5cf43ab573467",
                qwen3.qualification().scorecardSha256());
        assertEquals(LocalDate.of(2026, 7, 12), qwen3.qualification().qualifiedOn());
    }

    @Test
    void everyRequiredDispositionIsRepresentableWithoutMakingItSelectable() {
        assertEquals(EnumSet.of(
                        LlamaCppModelCatalog.Disposition.ACCEPTED_BETA,
                        LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                        LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED,
                        LlamaCppModelCatalog.Disposition.REJECTED_TOOL_USE,
                        LlamaCppModelCatalog.Disposition.LAB_ONLY),
                EnumSet.allOf(LlamaCppModelCatalog.Disposition.class));
    }

    @Test
    void migrationPreservesEveryExistingProfileIdentityAndRuntimeMode() {
        List<ExistingProfile> expected = List.of(
                new ExistingProfile(
                        "qwen2.5-coder-14b",
                        "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                        "d0a692ef765eefbf2fabb130b3cb2e8917e3d225",
                        "qwen2.5-coder-14b-instruct-q4_k_m.gguf",
                        "c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940",
                        8_988_110_272L,
                        true,
                        "native/default",
                        LlamaCppModelCatalog.Disposition.ACCEPTED_BETA,
                        false),
                new ExistingProfile(
                        "gpt-oss-20b",
                        "ggml-org/gpt-oss-20b-GGUF",
                        "e1dc459feff949ff451ce107337a2026daa80df8",
                        "gpt-oss-20b-mxfp4.gguf",
                        "be37a636aca0fc1aae0d32325f82f6b4d21495f06823b5fbc1898ae0303e9935",
                        12_109_566_560L,
                        true,
                        "native/default",
                        LlamaCppModelCatalog.Disposition.ACCEPTED_BETA,
                        true),
                new ExistingProfile(
                        "qwen36vf-q4km",
                        "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                        "4f08fded2aee1e27fb0335104307ade516508d37",
                        "Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf",
                        "273777783847f3a9eba912abc35407d651a575064a478ed9fbd7ad90b962b6b6",
                        8_465_553_472L,
                        true,
                        "native/default",
                        LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                        false),
                new ExistingProfile(
                        "qwen36vf-q6k",
                        "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                        "4f08fded2aee1e27fb0335104307ade516508d37",
                        "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf",
                        "56695b1ce0fa47b7d2d94bbb57bd71e5c24decfcd4af76b6a9c3073ee464fe9c",
                        11_325_370_432L,
                        true,
                        "native/default",
                        LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                        false),
                new ExistingProfile(
                        "deepseek-v2lite-q4km",
                        "bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF",
                        "8f248fa2072348f77a8bc37754e470de1f61866e",
                        "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf",
                        "603bd3f8a0281d16571da7c08bd661ee17ff0d1be6fcbd1b42242da257ef0bb8",
                        10_364_416_768L,
                        false,
                        "text/tool-prompt",
                        LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                        false));

        for (ExistingProfile expectedProfile : expected) {
            var actual = LlamaCppModelCatalog.byAlias(expectedProfile.alias()).orElseThrow();
            assertEquals(expectedProfile.repository(), actual.repository(), expectedProfile.alias());
            assertEquals(expectedProfile.revision(), actual.revision(), expectedProfile.alias());
            assertEquals(expectedProfile.artifact(), actual.artifactFile(), expectedProfile.alias());
            assertEquals(expectedProfile.sha256(), actual.artifactSha256(), expectedProfile.alias());
            assertEquals(expectedProfile.size(), actual.artifactSizeBytes(), expectedProfile.alias());
            assertEquals(expectedProfile.nativeCalling(), actual.nativeCalling(), expectedProfile.alias());
            assertEquals(expectedProfile.toolMode(), actual.toolMode(), expectedProfile.alias());
            assertEquals(expectedProfile.disposition(), actual.disposition(), expectedProfile.alias());
            assertEquals(expectedProfile.localPathRequired(), actual.requiresLocalModelPath(), expectedProfile.alias());
            assertEquals(expectedProfile.alias(),
                    LlamaCppModelCatalog.profileForGgufFile(expectedProfile.artifact()).orElseThrow().alias());
        }
    }

    @Test
    void duplicateAliasesFailClosed() {
        var qwen = LlamaCppModelCatalog.byAlias("qwen2.5-coder-14b").orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> LlamaCppModelCatalog.validateProfiles(List.of(qwen, qwen)));
    }

    @Test
    void selectableProfileWithMissingProvenanceFailsClosed() {
        var qwen = LlamaCppModelCatalog.byAlias("qwen2.5-coder-14b").orElseThrow();
        var missingRevision = new LlamaCppModelCatalog.Profile(
                "missing-revision",
                new LlamaCppModelCatalog.ModelArtifact(
                        qwen.repository(), "", qwen.artifactFile(), qwen.artifactSha256(),
                        qwen.artifactSizeBytes(), qwen.license()),
                qwen.runtime(),
                new LlamaCppModelCatalog.QualificationEvidence(
                        LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                        true,
                        false,
                        5,
                        "local/model-qualification/missing-revision",
                        "3271f5f092fe826a882dd27bdbdfbe53a7904fc9",
                        "91aa1e6a60aed548948d182fe17dab55b09d00a247a78dd9a3c5cf43ab573467",
                        LocalDate.of(2026, 7, 12),
                        "experimental evidence"),
                qwen.guidePath(),
                qwen.ramGuidance());

        assertThrows(IllegalArgumentException.class,
                () -> LlamaCppModelCatalog.validateProfiles(List.of(missingRevision)));
    }

    @Test
    void selectableProfileWithMissingCurrentEvidenceFailsClosed() {
        var qwen = LlamaCppModelCatalog.byAlias("qwen2.5-coder-14b").orElseThrow();
        var missingEvidence = new LlamaCppModelCatalog.Profile(
                "missing-evidence",
                qwen.artifact(),
                qwen.runtime(),
                new LlamaCppModelCatalog.QualificationEvidence(
                        LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                        true,
                        false,
                        5,
                        "",
                        "3271f5f092fe826a882dd27bdbdfbe53a7904fc9",
                        "",
                        LocalDate.of(2026, 7, 12),
                        "experimental evidence"),
                qwen.guidePath(),
                qwen.ramGuidance());

        assertThrows(IllegalArgumentException.class,
                () -> LlamaCppModelCatalog.validateProfiles(List.of(missingEvidence)));
    }

    @Test
    void currentEvidencePacketMustBindProtocolAndTalosCommit() {
        var qwen = LlamaCppModelCatalog.byAlias("qwen2.5-coder-14b").orElseThrow();
        var staleEvidence = new LlamaCppModelCatalog.Profile(
                "stale-evidence",
                qwen.artifact(),
                qwen.runtime(),
                new LlamaCppModelCatalog.QualificationEvidence(
                        LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE,
                        true,
                        false,
                        5,
                        "local/model-qualification/old-campaign",
                        "3271f5f092fe826a882dd27bdbdfbe53a7904fc9",
                        "91aa1e6a60aed548948d182fe17dab55b09d00a247a78dd9a3c5cf43ab573467",
                        LocalDate.of(2026, 7, 12),
                        "experimental evidence"),
                qwen.guidePath(),
                qwen.ramGuidance());

        assertThrows(IllegalArgumentException.class,
                () -> LlamaCppModelCatalog.validateProfiles(List.of(staleEvidence)));
    }

    @Test
    void labAndRejectedRecordsCannotBecomeSelectable() {
        var qwen = LlamaCppModelCatalog.byAlias("qwen2.5-coder-14b").orElseThrow();
        for (var disposition : List.of(
                LlamaCppModelCatalog.Disposition.LAB_ONLY,
                LlamaCppModelCatalog.Disposition.REJECTED_TOOL_USE,
                LlamaCppModelCatalog.Disposition.CUSTOM_UNVERIFIED)) {
            var invalid = new LlamaCppModelCatalog.Profile(
                    "invalid-" + disposition.name().toLowerCase(),
                    qwen.artifact(),
                    qwen.runtime(),
                    new LlamaCppModelCatalog.QualificationEvidence(
                            disposition,
                            true,
                            false,
                            0,
                            "legacy:test",
                            "legacy-unavailable",
                            "legacy-unavailable",
                            LocalDate.of(2026, 7, 12),
                            "not selectable"),
                    qwen.guidePath(),
                    qwen.ramGuidance());
            assertThrows(IllegalArgumentException.class,
                    () -> LlamaCppModelCatalog.validateProfiles(List.of(invalid)), disposition.name());
        }
    }
}
