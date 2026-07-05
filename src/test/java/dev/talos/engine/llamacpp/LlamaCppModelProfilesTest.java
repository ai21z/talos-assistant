package dev.talos.engine.llamacpp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LlamaCppModelProfiles} (T902) - the shared canned-profile
 * registry used by both `talos setup models --profile` and the /set model
 * switch guidance, so the two never drift.
 */
class LlamaCppModelProfilesTest {

    @Test
    void resolvesProfileForKnownGgufFileNames() {
        assertEquals("qwen2.5-coder-14b",
                LlamaCppModelProfiles.profileForGgufFile("qwen2.5-coder-14b-instruct-q4_k_m.gguf").orElseThrow().alias());
        assertEquals("gpt-oss-20b",
                LlamaCppModelProfiles.profileForGgufFile("gpt-oss-20b-mxfp4").orElseThrow().alias());
        // the live owner case, with the repo/file we substitute into the guidance
        var owner = LlamaCppModelProfiles.profileForGgufFile("Qwen3.6-14B-A3B-VibeForged-v2-Q6_K").orElseThrow();
        assertEquals("qwen36vf-q6k", owner.alias());
        assertEquals("tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF", owner.hfRepo());
        assertEquals("Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf", owner.hfFile());
    }

    @Test
    void profileMatchIsCaseAndPathAndSuffixInsensitive() {
        assertEquals("qwen36vf-q6k",
                LlamaCppModelProfiles.profileForGgufFile("some/dir/QWEN3.6-14B-A3B-VIBEFORGED-V2-Q6_K.GGUF").orElseThrow().alias());
    }

    @Test
    void unknownOrBlankGgufHasNoProfile() {
        assertTrue(LlamaCppModelProfiles.profileForGgufFile("mlabonne_Qwen3-14B-abliterated-Q4_K_M").isEmpty());
        assertTrue(LlamaCppModelProfiles.profileForGgufFile(null).isEmpty());
        assertTrue(LlamaCppModelProfiles.profileForGgufFile("").isEmpty());
    }

    @Test
    void profilesTableHasTheFiveCannedProfiles() {
        assertEquals(5, LlamaCppModelProfiles.profiles().size());
        assertTrue(LlamaCppModelProfiles.profiles().containsKey("qwen2.5-coder-14b"));
        assertTrue(LlamaCppModelProfiles.profiles().containsKey("gpt-oss-20b"));
        assertEquals("Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                LlamaCppModelProfiles.profiles().get("qwen2.5-coder-14b").hfRepo());
    }

    @Test
    void supportTiersKeepTheDoctrinePinnedStabilityPairSeparateFromExperimentalProfiles() {
        List<String> accepted = LlamaCppModelProfiles.profiles().values().stream()
                .filter(profile -> profile.supportTier() == LlamaCppModelProfiles.SupportTier.ACCEPTED_BETA)
                .map(LlamaCppModelProfiles.CannedProfile::alias)
                .toList();
        List<String> experimental = LlamaCppModelProfiles.profiles().values().stream()
                .filter(profile -> profile.supportTier() == LlamaCppModelProfiles.SupportTier.EXPERIMENTAL_SELECTABLE)
                .map(LlamaCppModelProfiles.CannedProfile::alias)
                .toList();

        assertEquals(List.of("qwen2.5-coder-14b", "gpt-oss-20b"), accepted);
        assertEquals(List.of("qwen36vf-q4km", "qwen36vf-q6k", "deepseek-v2lite-q4km"), experimental);
    }

    @Test
    void everyCannedProfileHasToolModeEvidenceBoundaryAndGuidePath() {
        for (var profile : LlamaCppModelProfiles.profiles().values()) {
            assertFalse(profile.toolMode().isBlank(), profile.alias());
            assertTrue(profile.evidenceSummary().contains("evidence"), profile.alias());
            assertEquals("docs/reference/model-profiles.md", profile.guidePath(), profile.alias());
        }
    }
}
