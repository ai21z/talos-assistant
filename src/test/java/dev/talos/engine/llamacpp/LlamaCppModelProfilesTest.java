package dev.talos.engine.llamacpp;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LlamaCppModelProfiles} (T902) - the shared canned-profile
 * registry used by both `talos setup models --profile` and the /set model
 * switch guidance, so the two never drift.
 */
class LlamaCppModelProfilesTest {

    @Test
    void resolvesAliasForKnownGgufFileNames() {
        assertEquals(Optional.of("qwen2.5-coder-14b"),
                LlamaCppModelProfiles.profileAliasForGgufFile("qwen2.5-coder-14b-instruct-q4_k_m.gguf"));
        assertEquals(Optional.of("gpt-oss-20b"),
                LlamaCppModelProfiles.profileAliasForGgufFile("gpt-oss-20b-mxfp4"));
        // the live owner case
        assertEquals(Optional.of("qwen36vf-q6k"),
                LlamaCppModelProfiles.profileAliasForGgufFile("Qwen3.6-14B-A3B-VibeForged-v2-Q6_K"));
    }

    @Test
    void aliasMatchIsCaseAndPathAndSuffixInsensitive() {
        assertEquals(Optional.of("qwen36vf-q6k"),
                LlamaCppModelProfiles.profileAliasForGgufFile("some/dir/QWEN3.6-14B-A3B-VIBEFORGED-V2-Q6_K.GGUF"));
    }

    @Test
    void unknownOrBlankGgufHasNoAlias() {
        assertTrue(LlamaCppModelProfiles.profileAliasForGgufFile("mlabonne_Qwen3-14B-abliterated-Q4_K_M").isEmpty());
        assertTrue(LlamaCppModelProfiles.profileAliasForGgufFile(null).isEmpty());
        assertTrue(LlamaCppModelProfiles.profileAliasForGgufFile("").isEmpty());
    }

    @Test
    void profilesTableHasTheFiveCannedProfiles() {
        assertEquals(5, LlamaCppModelProfiles.profiles().size());
        assertTrue(LlamaCppModelProfiles.profiles().containsKey("qwen2.5-coder-14b"));
        assertTrue(LlamaCppModelProfiles.profiles().containsKey("gpt-oss-20b"));
        assertEquals("Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                LlamaCppModelProfiles.profiles().get("qwen2.5-coder-14b").hfRepo());
    }
}
