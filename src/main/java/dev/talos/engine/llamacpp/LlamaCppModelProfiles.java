package dev.talos.engine.llamacpp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical registry of the canned managed-llama.cpp model profiles
 * (alias -&gt; Hugging Face repo + GGUF file). Single source of truth shared by
 * {@code talos setup models --profile <alias>} (SetupCmd) and the {@code /set model}
 * switch guidance (SetModelCommand), so the two never drift (T902).
 *
 * <p>Pure reference data with no side effects. Lives in {@code engine.llamacpp}
 * (model knowledge) so both the launcher and the REPL slash-command layer can
 * depend on it without a package cycle.
 */
public final class LlamaCppModelProfiles {

    private LlamaCppModelProfiles() {}

    /** A canned profile: {@code alias} is the {@code --profile} value. */
    public record CannedProfile(String alias, String hfRepo, String hfFile, boolean nativeCalling) {}

    private static final Map<String, CannedProfile> PROFILES = build();

    private static Map<String, CannedProfile> build() {
        LinkedHashMap<String, CannedProfile> out = new LinkedHashMap<>();
        out.put("qwen2.5-coder-14b", new CannedProfile(
                "qwen2.5-coder-14b", "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                "qwen2.5-coder-14b-instruct-q4_k_m.gguf", true));
        out.put("gpt-oss-20b", new CannedProfile(
                "gpt-oss-20b", "ggml-org/gpt-oss-20b-GGUF",
                "gpt-oss-20b-mxfp4.gguf", true));
        out.put("qwen36vf-q4km", new CannedProfile(
                "qwen36vf-q4km", "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                "Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf", true));
        out.put("qwen36vf-q6k", new CannedProfile(
                "qwen36vf-q6k", "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf", true));
        out.put("deepseek-v2lite-q4km", new CannedProfile(
                "deepseek-v2lite-q4km", "bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF",
                "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf", false));
        return Map.copyOf(out);
    }

    /** All canned profiles, keyed by alias, insertion-ordered. */
    public static Map<String, CannedProfile> profiles() {
        return PROFILES;
    }

    /**
     * The canned profile alias whose GGUF file matches {@code ggufFileOrStem}
     * (case-insensitive, a {@code .gguf} suffix optional, any directory prefix
     * ignored), or empty if none.
     */
    public static Optional<String> profileAliasForGgufFile(String ggufFileOrStem) {
        String want = stem(ggufFileOrStem);
        if (want.isEmpty()) return Optional.empty();
        for (CannedProfile p : PROFILES.values()) {
            if (stem(p.hfFile()).equals(want)) return Optional.of(p.alias());
        }
        return Optional.empty();
    }

    private static String stem(String s) {
        if (s == null) return "";
        String t = s.trim();
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0) t = t.substring(slash + 1);
        if (t.toLowerCase(Locale.ROOT).endsWith(".gguf")) t = t.substring(0, t.length() - 5);
        return t.toLowerCase(Locale.ROOT);
    }
}
