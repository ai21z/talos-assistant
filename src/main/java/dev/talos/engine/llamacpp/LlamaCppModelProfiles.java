package dev.talos.engine.llamacpp;

import java.util.Collections;
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

    /** Public support posture for a canned chat profile. */
    public enum SupportTier {
        ACCEPTED_BETA("accepted beta stability"),
        EXPERIMENTAL_SELECTABLE("experimental selectable");

        private final String label;

        SupportTier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A canned profile: {@code alias} is the {@code --profile} value. */
    public record CannedProfile(
            String alias,
            String hfRepo,
            String hfFile,
            boolean nativeCalling,
            String toolMode,
            SupportTier supportTier,
            String evidenceSummary,
            String guidePath) {}

    private static final Map<String, CannedProfile> PROFILES = build();

    private static Map<String, CannedProfile> build() {
        LinkedHashMap<String, CannedProfile> out = new LinkedHashMap<>();
        out.put("qwen2.5-coder-14b", new CannedProfile(
                "qwen2.5-coder-14b", "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                "qwen2.5-coder-14b-instruct-q4_k_m.gguf", true,
                "native/default", SupportTier.ACCEPTED_BETA,
                "accepted beta stability evidence; run talos doctor --start after configuration for machine-local smoke evidence",
                "docs/user/model-profiles/qwen2.5-coder-14b.md"));
        out.put("gpt-oss-20b", new CannedProfile(
                "gpt-oss-20b", "ggml-org/gpt-oss-20b-GGUF",
                "gpt-oss-20b-mxfp4.gguf", true,
                "native/default", SupportTier.ACCEPTED_BETA,
                "accepted beta stability evidence; run talos doctor --start after configuration for machine-local smoke evidence",
                "docs/user/model-profiles/gpt-oss-20b.md"));
        out.put("qwen36vf-q4km", new CannedProfile(
                "qwen36vf-q4km", "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                "Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf", true,
                "native/default", SupportTier.EXPERIMENTAL_SELECTABLE,
                "experimental tool-call evidence; not beta stability evidence",
                "docs/user/model-profiles/qwen36vf-q4km.md"));
        out.put("qwen36vf-q6k", new CannedProfile(
                "qwen36vf-q6k", "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf", true,
                "native/default", SupportTier.EXPERIMENTAL_SELECTABLE,
                "experimental tool-call evidence; not beta stability evidence",
                "docs/user/model-profiles/qwen36vf-q6k.md"));
        out.put("deepseek-v2lite-q4km", new CannedProfile(
                "deepseek-v2lite-q4km", "bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF",
                "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf", false,
                "text/tool-prompt", SupportTier.EXPERIMENTAL_SELECTABLE,
                "experimental text/tool-prompt evidence; native/default produced zero executable tool calls",
                "docs/user/model-profiles/deepseek-v2lite-q4km.md"));
        return Collections.unmodifiableMap(out);
    }

    /** All canned profiles, keyed by alias, insertion-ordered. */
    public static Map<String, CannedProfile> profiles() {
        return PROFILES;
    }

    /**
     * The canned profile whose GGUF file matches {@code ggufFileOrStem}
     * (case-insensitive, a {@code .gguf} suffix optional, any directory prefix
     * ignored), or empty if none.
     */
    public static Optional<CannedProfile> profileForGgufFile(String ggufFileOrStem) {
        String want = stem(ggufFileOrStem);
        if (want.isEmpty()) return Optional.empty();
        for (CannedProfile p : PROFILES.values()) {
            if (stem(p.hfFile()).equals(want)) return Optional.of(p);
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
