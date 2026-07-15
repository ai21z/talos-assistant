package dev.talos.engine.llamacpp;

import org.junit.jupiter.api.Test;
import dev.talos.spi.types.RequestMessageLayout;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaCppModelCatalogTest {

    @Test
    void onlyQwen35UsesTheEvidenceGatedSingleLeadingSystemLayout() {
        for (String alias : List.of(
                "qwen2.5-coder-14b",
                "gpt-oss-20b",
                "qwen36vf-q4km",
                "qwen36vf-q6k",
                "deepseek-v2lite-q4km",
                "qwen3-8b",
                "granite-4.1-3b",
                "qwen3-4b",
                "granite-4.1-8b")) {
            var profile = LlamaCppModelCatalog.byAlias(alias).orElseThrow();
            assertEquals(RequestMessageLayout.PRESERVE_AS_WRITTEN,
                    profile.runtime().requestLayout(), alias);
        }

        var qwen35 = LlamaCppModelCatalog.byAlias("qwen3.5-9b").orElseThrow();
        assertEquals(RequestMessageLayout.SINGLE_LEADING_SYSTEM,
                qwen35.runtime().requestLayout());
    }

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
    void ownsAllSelectableProfilesInStableDisplayOrder() {
        assertEquals(List.of(
                        "qwen2.5-coder-14b",
                        "gpt-oss-20b",
                        "qwen36vf-q4km",
                        "qwen36vf-q6k",
                        "deepseek-v2lite-q4km",
                        "qwen3-8b",
                        "granite-4.1-3b",
                        "qwen3-4b",
                        "granite-4.1-8b",
                        "qwen3.5-9b"),
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
    void graniteProjectsTheExactImmutableExperimentalQualification() {
        var granite = LlamaCppModelCatalog.byAlias("granite-4.1-3b").orElseThrow();

        assertEquals("ibm-granite/granite-4.1-3b-GGUF", granite.repository());
        assertEquals("ab4701481089b58a082ef63cc1cee738887293ff", granite.revision());
        assertEquals("granite-4.1-3b-Q4_K_M.gguf", granite.artifactFile());
        assertEquals("662b0626cd58f443baea23559b469df6576a81d349649c59413b36a9fb32eb29",
                granite.artifactSha256());
        assertEquals(2_099_501_664L, granite.artifactSizeBytes());
        assertEquals("Apache-2.0", granite.license());
        assertEquals(LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE, granite.disposition());
        assertTrue(granite.selectable());
        assertTrue(granite.requiresLocalModelPath());
        assertEquals(false, granite.wizardDownload());
        assertEquals(true, granite.nativeCalling());
        assertEquals("native/default", granite.toolMode());
        assertEquals(8_192, granite.runtime().contextTokens());
        assertEquals(8_192, granite.runtime().maxContextTokens());
        assertEquals(List.of(), granite.runtime().managedServerArgs());
        assertEquals(List.of("win-x64-cuda-13.3:b9918"), granite.runtime().compatibleEngineLanes());
        assertEquals(5, granite.qualification().protocolRevision());
        assertEquals(
                "local/model-qualification/granite-4.1-3b-full-p5-3b691d80-a10-b9918-cuda13-20260713",
                granite.evidencePacket());
        assertEquals("3b691d801f54f3dfa006e2270f689ae6e2f94305", granite.qualification().talosCommit());
        assertEquals("4848a952a99dca994f31765779a2e999faa95021b6179193fcc6b0bb56c7945b",
                granite.qualification().scorecardSha256());
        assertEquals(LocalDate.of(2026, 7, 13), granite.qualification().qualifiedOn());
        assertTrue(granite.evidenceSummary().contains("16 of 24 scenario definitions"),
                granite.evidenceSummary());
        assertTrue(granite.evidenceSummary().contains("zero trust failures"),
                granite.evidenceSummary());
    }

    @Test
    void qwen3FourBProjectsTheExactControlledExperimentalQualification() {
        var qwen = LlamaCppModelCatalog.byAlias("qwen3-4b").orElseThrow();

        assertEquals("Qwen/Qwen3-4B-Instruct-2507", qwen.repository());
        assertEquals("cdbee75f17c01a7cc42f958dc650907174af0554", qwen.revision());
        assertEquals("qwen3-4b-instruct-2507-Q4_K_M.gguf", qwen.artifactFile());
        assertEquals("1e5b123fcb51bcffa922c21f00e47a8ee3450c455cdacb0aafba0573827b3f73",
                qwen.artifactSha256());
        assertEquals(2_497_279_008L, qwen.artifactSizeBytes());
        assertEquals("Apache-2.0", qwen.license());
        assertEquals(LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE, qwen.disposition());
        assertTrue(qwen.selectable());
        assertTrue(qwen.requiresLocalModelPath());
        assertEquals(false, qwen.wizardDownload());
        assertEquals(true, qwen.nativeCalling());
        assertEquals("native/default", qwen.toolMode());
        assertEquals(8_192, qwen.runtime().contextTokens());
        assertEquals(8_192, qwen.runtime().maxContextTokens());
        assertEquals(List.of("--reasoning", "off"), qwen.runtime().managedServerArgs());
        assertEquals(List.of("win-x64-cuda-13.3:b9918"), qwen.runtime().compatibleEngineLanes());
        assertTrue(qwen.runtime().architecture().contains("36 layers"), qwen.runtime().architecture());
        assertTrue(qwen.runtime().parameterShape().contains("4.022468096B"), qwen.runtime().parameterShape());
        assertEquals(5, qwen.qualification().protocolRevision());
        assertEquals(
                "local/model-qualification/qwen3-4b-full-p5-f6a2edf6-a8-b9918-cuda13-20260714",
                qwen.evidencePacket());
        assertEquals("f6a2edf644512d90100b871a90876469eeb2482d", qwen.qualification().talosCommit());
        assertEquals("118c04c92758ce068cd06c69e3ac77995dd6ad18fd05cd866a4dce7cb008cb94",
                qwen.qualification().scorecardSha256());
        assertEquals(LocalDate.of(2026, 7, 14), qwen.qualification().qualifiedOn());
        assertTrue(qwen.evidenceSummary().contains("20 of 24 scenario definitions"),
                qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("58 of 72 trials"), qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("47 of 54 required-tool decisions"),
                qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("zero trust failures"), qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("not accepted beta"), qwen.evidenceSummary());
    }

    @Test
    void graniteEightBProjectsTheExactImmutableExperimentalQualification() {
        var granite = LlamaCppModelCatalog.byAlias("granite-4.1-8b").orElseThrow();

        assertEquals("ibm-granite/granite-4.1-8b-GGUF", granite.repository());
        assertEquals("865b82c2e7970d82e3731278c88c57ae7138359c", granite.revision());
        assertEquals("granite-4.1-8b-Q4_K_M.gguf", granite.artifactFile());
        assertEquals("ed902ac9eb6adce5a90c6a08c8ea201b50e23fdc5976d1cd0362006afac5309e",
                granite.artifactSha256());
        assertEquals(5_347_914_400L, granite.artifactSizeBytes());
        assertEquals("Apache-2.0", granite.license());
        assertEquals(LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE, granite.disposition());
        assertTrue(granite.selectable());
        assertTrue(granite.requiresLocalModelPath());
        assertEquals(false, granite.wizardDownload());
        assertEquals(true, granite.nativeCalling());
        assertEquals("native/default", granite.toolMode());
        assertEquals(8_192, granite.runtime().contextTokens());
        assertEquals(8_192, granite.runtime().maxContextTokens());
        assertEquals(List.of(), granite.runtime().managedServerArgs());
        assertEquals(List.of("win-x64-cuda-13.3:b9918"), granite.runtime().compatibleEngineLanes());
        assertTrue(granite.runtime().architecture().contains("40 layers"), granite.runtime().architecture());
        assertTrue(granite.runtime().parameterShape().contains("8.791592960B"),
                granite.runtime().parameterShape());
        assertEquals(5, granite.qualification().protocolRevision());
        assertEquals(
                "local/model-qualification/granite-4.1-8b-full-p5-d93e263f-a1-b9918-cuda13-20260714",
                granite.evidencePacket());
        assertEquals("d93e263f847ec04939b7ccbcf586b117aa24e140",
                granite.qualification().talosCommit());
        assertEquals("14dc40e19221533985a89fa2dbc109164c28a6988738173849aa01ebb9405c4e",
                granite.qualification().scorecardSha256());
        assertEquals(LocalDate.of(2026, 7, 14), granite.qualification().qualifiedOn());
        assertTrue(granite.evidenceSummary().contains("19 of 24 scenario definitions"),
                granite.evidenceSummary());
        assertTrue(granite.evidenceSummary().contains("57 of 72 trials"), granite.evidenceSummary());
        assertTrue(granite.evidenceSummary().contains("51 of 54 required-tool decisions"),
                granite.evidenceSummary());
        assertTrue(granite.evidenceSummary().contains("zero trust failures"), granite.evidenceSummary());
        assertTrue(granite.evidenceSummary().contains("not accepted beta"), granite.evidenceSummary());
    }

    @Test
    void qwen35ProjectsTheExactControlledExperimentalQualification() {
        var qwen = LlamaCppModelCatalog.byAlias("qwen3.5-9b").orElseThrow();

        assertEquals("Qwen/Qwen3.5-9B", qwen.repository());
        assertEquals("c202236235762e1c871ad0ccb60c8ee5ba337b9a", qwen.revision());
        assertEquals("Qwen3.5-9B-Q4_K_M.gguf", qwen.artifactFile());
        assertEquals("1fddc46c7ebc2180481b59c2111b83ebc0e397c183d3746a6548d07d30f63a9f",
                qwen.artifactSha256());
        assertEquals(5_780_090_592L, qwen.artifactSizeBytes());
        assertEquals("Apache-2.0", qwen.license());
        assertEquals(LlamaCppModelCatalog.Disposition.EXPERIMENTAL_SELECTABLE, qwen.disposition());
        assertTrue(qwen.selectable());
        assertTrue(qwen.requiresLocalModelPath());
        assertEquals(false, qwen.wizardDownload());
        assertEquals(true, qwen.nativeCalling());
        assertEquals("native/default", qwen.toolMode());
        assertEquals(8_192, qwen.runtime().contextTokens());
        assertEquals(8_192, qwen.runtime().maxContextTokens());
        assertEquals(RequestMessageLayout.SINGLE_LEADING_SYSTEM, qwen.runtime().requestLayout());
        assertEquals(List.of("--reasoning", "off"), qwen.runtime().managedServerArgs());
        assertEquals(List.of("win-x64-cuda-13.3:b9918"), qwen.runtime().compatibleEngineLanes());
        assertTrue(qwen.runtime().architecture().contains("32 base layers and one MTP layer"),
                qwen.runtime().architecture());
        assertTrue(qwen.runtime().parameterShape().contains("9.197093888B total parameters"),
                qwen.runtime().parameterShape());
        assertTrue(qwen.runtime().parameterShape().contains("8.953803264B base text parameters"),
                qwen.runtime().parameterShape());
        assertEquals(5, qwen.qualification().protocolRevision());
        assertEquals(
                "local/model-qualification/qwen3.5-9b-full-p5-aa4bb8dc-fq2-b9918-cuda13-20260715",
                qwen.evidencePacket());
        assertEquals("aa4bb8dc9743b367dd46143bdd03d835688c65fd",
                qwen.qualification().talosCommit());
        assertEquals("72e7b15dc965bdc5c97f75c77be49ac19cd7039c70e5ffb19fa3de7f2d6b3f6d",
                qwen.qualification().scorecardSha256());
        assertEquals(LocalDate.of(2026, 7, 15), qwen.qualification().qualifiedOn());
        assertTrue(qwen.evidenceSummary().contains("19 of 24 scenario definitions"),
                qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("56 of 72 trial outcomes"),
                qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("117 of 129 requirements"),
                qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("52 of 54 required-tool decisions"),
                qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("135 of 135 valid native calls"),
                qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("every trust gate passed"),
                qwen.evidenceSummary());
        assertTrue(qwen.evidenceSummary().contains("not accepted beta"), qwen.evidenceSummary());
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
