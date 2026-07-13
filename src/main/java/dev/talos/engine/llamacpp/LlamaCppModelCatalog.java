package dev.talos.engine.llamacpp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Canonical provenance, runtime, and qualification record for managed GGUF profiles. */
public final class LlamaCppModelCatalog {
    private static final String MODEL_PROFILES_GUIDE = "docs/reference/model-profiles.md";
    private static final String LEGACY_SCORECARD = "legacy-unavailable";
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ALIAS = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private static final List<String> PINNED_CROSS_PLATFORM_LANES = List.of(
            "ubuntu-x64-cpu:b9860",
            "win-x64-cpu:b9918",
            "win-x64-cuda-12.4:b9918",
            "win-x64-cuda-13.3:b9918");
    private static final List<String> PINNED_WINDOWS_LANES = List.of(
            "win-x64-cpu:b9918",
            "win-x64-cuda-12.4:b9918",
            "win-x64-cuda-13.3:b9918");

    private static final Map<String, Profile> PROFILES = build();

    private LlamaCppModelCatalog() {}

    public enum Disposition {
        ACCEPTED_BETA("accepted beta stability"),
        EXPERIMENTAL_SELECTABLE("experimental selectable"),
        CUSTOM_UNVERIFIED("custom unverified"),
        REJECTED_TOOL_USE("rejected for tool use"),
        LAB_ONLY("lab only");

        private final String label;

        Disposition(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record ModelArtifact(
            String repository,
            String revision,
            String fileName,
            String sha256,
            long sizeBytes,
            String license) {}

    public record RuntimeDefaults(
            String architecture,
            String parameterShape,
            boolean nativeCalling,
            String toolMode,
            String reasoningMode,
            String chatTemplate,
            int contextTokens,
            int maxContextTokens,
            String samplingProfile,
            List<String> managedServerArgs,
            List<String> compatibleEngineLanes,
            String hardwareEnvelope,
            boolean requiresLocalModelPath) {

        public RuntimeDefaults {
            managedServerArgs = List.copyOf(managedServerArgs == null ? List.of() : managedServerArgs);
            compatibleEngineLanes = List.copyOf(compatibleEngineLanes == null ? List.of() : compatibleEngineLanes);
        }
    }

    public record QualificationEvidence(
            Disposition disposition,
            boolean selectable,
            boolean wizardDownload,
            int protocolRevision,
            String packetId,
            String talosCommit,
            String scorecardSha256,
            LocalDate qualifiedOn,
            String summary) {}

    public record Profile(
            String alias,
            ModelArtifact artifact,
            RuntimeDefaults runtime,
            QualificationEvidence qualification,
            String guidePath,
            String ramGuidance) {

        public String repository() {
            return artifact.repository();
        }

        public String revision() {
            return artifact.revision();
        }

        public String artifactFile() {
            return artifact.fileName();
        }

        public String artifactSha256() {
            return artifact.sha256();
        }

        public long artifactSizeBytes() {
            return artifact.sizeBytes();
        }

        public String license() {
            return artifact.license();
        }

        public boolean nativeCalling() {
            return runtime.nativeCalling();
        }

        public String toolMode() {
            return runtime.toolMode();
        }

        public boolean requiresLocalModelPath() {
            return runtime.requiresLocalModelPath();
        }

        public Disposition disposition() {
            return qualification.disposition();
        }

        public boolean selectable() {
            return qualification.selectable();
        }

        public boolean wizardDownload() {
            return qualification.wizardDownload();
        }

        public String evidencePacket() {
            return qualification.packetId();
        }

        public String evidenceSummary() {
            return qualification.summary();
        }
    }

    public static Map<String, Profile> profiles() {
        return PROFILES;
    }

    public static List<Profile> selectableProfiles() {
        return PROFILES.values().stream().filter(Profile::selectable).toList();
    }

    public static List<Profile> profilesWithDisposition(Disposition disposition) {
        return PROFILES.values().stream()
                .filter(profile -> profile.disposition() == disposition)
                .toList();
    }

    public static Optional<Profile> byAlias(String alias) {
        return Optional.ofNullable(PROFILES.get(normalize(alias)));
    }

    public static Optional<Profile> profileForGgufFile(String ggufFileOrStem) {
        String wanted = stem(ggufFileOrStem);
        if (wanted.isEmpty()) return Optional.empty();
        return PROFILES.values().stream()
                .filter(Profile::selectable)
                .filter(profile -> stem(profile.artifactFile()).equals(wanted))
                .findFirst();
    }

    public static Optional<Profile> profileForRuntime(String modelAlias, String modelSourceLabel) {
        Optional<Profile> byModel = byAlias(modelAlias).filter(Profile::selectable);
        return byModel.isPresent() ? byModel : profileForGgufFile(modelSourceLabel);
    }

    public static void validateProfiles(List<Profile> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        Map<String, Profile> aliases = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            validateProfile(profile);
            String alias = normalize(profile.alias());
            if (aliases.putIfAbsent(alias, profile) != null) {
                throw new IllegalArgumentException("Duplicate model profile alias: " + alias);
            }
        }
    }

    private static Map<String, Profile> build() {
        List<Profile> profiles = new ArrayList<>();
        profiles.add(qwen14());
        profiles.add(gptOss20b());
        profiles.add(vibeForgedQ4());
        profiles.add(vibeForgedQ6());
        profiles.add(deepSeekV2Lite());
        profiles.add(qwen3EightB());
        profiles.add(granite41ThreeB());
        validateProfiles(profiles);
        LinkedHashMap<String, Profile> indexed = new LinkedHashMap<>();
        for (Profile profile : profiles) {
            indexed.put(profile.alias(), profile);
        }
        return Collections.unmodifiableMap(indexed);
    }

    private static Profile qwen14() {
        return new Profile(
                "qwen2.5-coder-14b",
                new ModelArtifact(
                        "Qwen/Qwen2.5-Coder-14B-Instruct-GGUF",
                        "d0a692ef765eefbf2fabb130b3cb2e8917e3d225",
                        "qwen2.5-coder-14b-instruct-q4_k_m.gguf",
                        "c1e659736d89ac1065fb495330fb824d94001974a4bfa78e7270e43476a8d940",
                        8_988_110_272L,
                        "Apache-2.0"),
                new RuntimeDefaults(
                        "Qwen2.5 dense decoder-only transformer",
                        "14.7B total parameters, dense-active",
                        true,
                        "native/default",
                        "standard instruction behavior with no separate reasoning channel",
                        "embedded Jinja chat template",
                        8_192,
                        16_384,
                        "Talos request defaults; near-greedy mutation obligations",
                        List.of(),
                        PINNED_CROSS_PLATFORM_LANES,
                        "16 GB RAM minimum; 24 GB or more for CPU-only; not the low-resource lane",
                        false),
                legacyEvidence(
                        Disposition.ACCEPTED_BETA,
                        true,
                        "legacy:two-model-large-scale-live-audit-0.10.8",
                        LocalDate.of(2026, 7, 3),
                        "accepted beta stability evidence; run talos doctor --start after configuration for machine-local smoke evidence"),
                MODEL_PROFILES_GUIDE,
                "16 GB RAM minimum; 24 GB+ for CPU-only; large CPU model, not the low-resource lane");
    }

    private static Profile gptOss20b() {
        return new Profile(
                "gpt-oss-20b",
                new ModelArtifact(
                        "ggml-org/gpt-oss-20b-GGUF",
                        "e1dc459feff949ff451ce107337a2026daa80df8",
                        "gpt-oss-20b-mxfp4.gguf",
                        "be37a636aca0fc1aae0d32325f82f6b4d21495f06823b5fbc1898ae0303e9935",
                        12_109_566_560L,
                        "Apache-2.0"),
                new RuntimeDefaults(
                        "GPT-OSS mixture-of-experts transformer",
                        "20.91B total parameters, 3.61B active parameters",
                        true,
                        "native/default",
                        "embedded Harmony reasoning behavior with no Talos reasoning-effort override",
                        "embedded Jinja Harmony template",
                        8_192,
                        8_192,
                        "Talos request defaults; near-greedy mutation obligations",
                        List.of(),
                        PINNED_CROSS_PLATFORM_LANES,
                        "24 GB RAM minimum; 32 GB or more for CPU-only; not the low-resource lane",
                        true),
                legacyEvidence(
                        Disposition.ACCEPTED_BETA,
                        true,
                        "legacy:two-model-large-scale-live-audit-0.10.8",
                        LocalDate.of(2026, 7, 3),
                        "accepted beta stability evidence; run talos doctor --start after configuration for machine-local smoke evidence"),
                MODEL_PROFILES_GUIDE,
                "24 GB RAM minimum; 32 GB+ for CPU-only; large CPU model, not the low-resource lane");
    }

    private static Profile vibeForgedQ4() {
        return vibeForged(
                "qwen36vf-q4km",
                "Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf",
                "273777783847f3a9eba912abc35407d651a575064a478ed9fbd7ad90b962b6b6",
                8_465_553_472L,
                "12 GB or larger GPU screening lane; third-party experimental profile");
    }

    private static Profile vibeForgedQ6() {
        return vibeForged(
                "qwen36vf-q6k",
                "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf",
                "56695b1ce0fa47b7d2d94bbb57bd71e5c24decfcd4af76b6a9c3073ee464fe9c",
                11_325_370_432L,
                "16 GB or larger GPU screening lane; third-party experimental profile");
    }

    private static Profile vibeForged(
            String alias,
            String file,
            String sha256,
            long size,
            String hardware) {
        return new Profile(
                alias,
                new ModelArtifact(
                        "tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF",
                        "4f08fded2aee1e27fb0335104307ade516508d37",
                        file,
                        sha256,
                        size,
                        "Apache-2.0 third-party derivative"),
                new RuntimeDefaults(
                        "third-party pruned Qwen-family mixture-of-experts transformer",
                        "approximately 14B total under A3B naming; active count not independently established",
                        true,
                        "native/default",
                        "custom think boundaries from the third-party model",
                        "embedded Jinja template with custom tool syntax",
                        8_192,
                        8_192,
                        "Talos request defaults; experimental profile",
                        List.of(),
                        PINNED_WINDOWS_LANES,
                        hardware,
                        false),
                legacyEvidence(
                        Disposition.EXPERIMENTAL_SELECTABLE,
                        false,
                        "legacy:managed-profile-evidence-0.10.7",
                        LocalDate.of(2026, 7, 2),
                        "experimental tool-call evidence; not beta stability evidence"),
                MODEL_PROFILES_GUIDE,
                hardware);
    }

    private static Profile deepSeekV2Lite() {
        return new Profile(
                "deepseek-v2lite-q4km",
                new ModelArtifact(
                        "bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF",
                        "8f248fa2072348f77a8bc37754e470de1f61866e",
                        "DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf",
                        "603bd3f8a0281d16571da7c08bd661ee17ff0d1be6fcbd1b42242da257ef0bb8",
                        10_364_416_768L,
                        "DeepSeek model license; third-party Bartowski GGUF"),
                new RuntimeDefaults(
                        "DeepSeek-Coder-V2-Lite mixture-of-experts transformer",
                        "16B total parameters, 2.4B active parameters",
                        false,
                        "text/tool-prompt",
                        "no separate thinking channel documented",
                        "DeepSeek-specific prompt format",
                        8_192,
                        8_192,
                        "Talos text/tool-prompt defaults; experimental profile",
                        List.of(),
                        PINNED_WINDOWS_LANES,
                        "24 GB or more system RAM or a 16 GB GPU screening lane",
                        false),
                legacyEvidence(
                        Disposition.EXPERIMENTAL_SELECTABLE,
                        false,
                        "legacy:managed-profile-evidence-0.10.7",
                        LocalDate.of(2026, 7, 2),
                        "experimental text/tool-prompt evidence; native/default produced zero executable tool calls"),
                MODEL_PROFILES_GUIDE,
                "24 GB+ system RAM or a 16 GB GPU screening lane; third-party experimental profile");
    }

    private static Profile qwen3EightB() {
        return new Profile(
                "qwen3-8b",
                new ModelArtifact(
                        "Qwen/Qwen3-8B-GGUF",
                        "7c41481f57cb95916b40956ab2f0b139b296d974",
                        "Qwen3-8B-Q4_K_M.gguf",
                        "d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785",
                        5_027_783_488L,
                        "Apache-2.0"),
                new RuntimeDefaults(
                        "Qwen3 dense decoder-only transformer, 36 layers, GQA with 32 query heads and 8 KV heads",
                        "8.2B total parameters, dense-active",
                        true,
                        "native/default",
                        "non-thinking via llama.cpp --reasoning off",
                        "embedded Jinja Qwen3 template",
                        8_192,
                        8_192,
                        "Talos standard request controls; three uncontrolled qualification repetitions",
                        List.of("--reasoning", "off"),
                        List.of("win-x64-cuda-13.3:b9918"),
                        "Windows x64 CUDA 13.3 reference lane with 16 GiB-class VRAM and 32 GiB system RAM; no weak-hardware claim",
                        true),
                new QualificationEvidence(
                        Disposition.EXPERIMENTAL_SELECTABLE,
                        true,
                        false,
                        5,
                        "local/model-qualification/qwen3-8b-full-p5a15-3271f5f0-b9918-cuda13-20260712",
                        "3271f5f092fe826a882dd27bdbdfbe53a7904fc9",
                        "91aa1e6a60aed548948d182fe17dab55b09d00a247a78dd9a3c5cf43ab573467",
                        LocalDate.of(2026, 7, 12),
                        "experimental full-bank evidence: 18 of 24 scenario definitions and 57 of 72 trials passed, with zero trust failures; not accepted beta or weak-hardware evidence"),
                MODEL_PROFILES_GUIDE,
                "32 GiB system RAM and a 16 GiB-class CUDA reference lane; experimental evidence only; no weak-hardware claim");
    }

    private static Profile granite41ThreeB() {
        return new Profile(
                "granite-4.1-3b",
                new ModelArtifact(
                        "ibm-granite/granite-4.1-3b-GGUF",
                        "ab4701481089b58a082ef63cc1cee738887293ff",
                        "granite-4.1-3b-Q4_K_M.gguf",
                        "662b0626cd58f443baea23559b469df6576a81d349649c59413b36a9fb32eb29",
                        2_099_501_664L,
                        "Apache-2.0"),
                new RuntimeDefaults(
                        "Granite dense decoder-only text model, 40 layers, hidden size 2560, 40 query heads, and 8 KV heads",
                        "3.40283648B total parameters, dense-active",
                        true,
                        "native/default",
                        "no separate thinking mode, b9918 reports thinking disabled",
                        "embedded Granite template with tools_system_message_prefix",
                        8_192,
                        8_192,
                        "Talos standard request controls, three uncontrolled qualification repetitions",
                        List.of(),
                        List.of("win-x64-cuda-13.3:b9918"),
                        "Windows x64 CUDA 13.3 reference lane with 16 GiB-class VRAM and 32 GiB system RAM, no weak-hardware claim",
                        true),
                new QualificationEvidence(
                        Disposition.EXPERIMENTAL_SELECTABLE,
                        true,
                        false,
                        5,
                        "local/model-qualification/granite-4.1-3b-full-p5-3b691d80-a10-b9918-cuda13-20260713",
                        "3b691d801f54f3dfa006e2270f689ae6e2f94305",
                        "4848a952a99dca994f31765779a2e999faa95021b6179193fcc6b0bb56c7945b",
                        LocalDate.of(2026, 7, 13),
                        "experimental full-bank evidence: 16 of 24 scenario definitions and 47 of 72 trials passed, with zero trust failures, not accepted beta or weak-hardware evidence"),
                MODEL_PROFILES_GUIDE,
                "32 GiB system RAM and a 16 GiB-class CUDA reference lane, experimental evidence only, no weak-hardware claim");
    }

    private static QualificationEvidence legacyEvidence(
            Disposition disposition,
            boolean wizardDownload,
            String packet,
            LocalDate date,
            String summary) {
        return new QualificationEvidence(
                disposition,
                true,
                wizardDownload,
                0,
                packet,
                LEGACY_SCORECARD,
                LEGACY_SCORECARD,
                date,
                summary);
    }

    private static void validateProfile(Profile profile) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(profile.artifact(), "artifact");
        Objects.requireNonNull(profile.runtime(), "runtime");
        Objects.requireNonNull(profile.qualification(), "qualification");
        if (!ALIAS.matcher(nonBlank(profile.alias(), "alias")).matches()) {
            throw new IllegalArgumentException("Invalid model profile alias: " + profile.alias());
        }

        QualificationEvidence evidence = profile.qualification();
        boolean qualifiedDisposition = evidence.disposition() == Disposition.ACCEPTED_BETA
                || evidence.disposition() == Disposition.EXPERIMENTAL_SELECTABLE;
        if (evidence.selectable() != qualifiedDisposition) {
            throw new IllegalArgumentException("Unsupported disposition/selectability for " + profile.alias());
        }
        if (evidence.wizardDownload() && evidence.disposition() != Disposition.ACCEPTED_BETA) {
            throw new IllegalArgumentException("Only accepted-beta profiles may be wizard downloads: " + profile.alias());
        }
        if (!evidence.selectable()) return;

        ModelArtifact artifact = profile.artifact();
        nonBlank(artifact.repository(), "repository");
        if (!REVISION.matcher(nonBlank(artifact.revision(), "revision")).matches()) {
            throw new IllegalArgumentException("Invalid repository revision for " + profile.alias());
        }
        nonBlank(artifact.fileName(), "artifact file");
        if (!SHA256.matcher(nonBlank(artifact.sha256(), "artifact SHA-256")).matches()) {
            throw new IllegalArgumentException("Invalid artifact SHA-256 for " + profile.alias());
        }
        if (artifact.sizeBytes() <= 0) {
            throw new IllegalArgumentException("Missing artifact size for " + profile.alias());
        }
        nonBlank(artifact.license(), "license");

        RuntimeDefaults runtime = profile.runtime();
        nonBlank(runtime.architecture(), "architecture");
        nonBlank(runtime.parameterShape(), "parameter shape");
        nonBlank(runtime.toolMode(), "tool mode");
        nonBlank(runtime.reasoningMode(), "reasoning mode");
        nonBlank(runtime.chatTemplate(), "chat template");
        nonBlank(runtime.samplingProfile(), "sampling profile");
        nonBlank(runtime.hardwareEnvelope(), "hardware envelope");
        if (runtime.contextTokens() <= 0
                || runtime.maxContextTokens() < runtime.contextTokens()
                || runtime.compatibleEngineLanes().isEmpty()) {
            throw new IllegalArgumentException("Missing runtime compatibility for " + profile.alias());
        }

        nonBlank(evidence.packetId(), "evidence packet");
        nonBlank(evidence.talosCommit(), "evidence Talos commit");
        nonBlank(evidence.scorecardSha256(), "scorecard SHA-256");
        Objects.requireNonNull(evidence.qualifiedOn(), "qualification date");
        nonBlank(evidence.summary(), "evidence summary");
        if (evidence.protocolRevision() == 0) {
            if (!evidence.packetId().startsWith("legacy:")
                    || !LEGACY_SCORECARD.equals(evidence.talosCommit())
                    || !LEGACY_SCORECARD.equals(evidence.scorecardSha256())) {
                throw new IllegalArgumentException("Legacy evidence must be explicit for " + profile.alias());
            }
        } else {
            if (evidence.protocolRevision() < 1
                    || !evidence.packetId().startsWith("local/model-qualification/")
                    || !REVISION.matcher(evidence.talosCommit()).matches()
                    || !SHA256.matcher(evidence.scorecardSha256()).matches()) {
                throw new IllegalArgumentException("Current qualification evidence is incomplete for " + profile.alias());
            }
            String packetIdentity = evidence.packetId().toLowerCase(Locale.ROOT);
            String protocolIdentity = "-p" + evidence.protocolRevision();
            String commitIdentity = evidence.talosCommit().substring(0, 8);
            if (!packetIdentity.contains(protocolIdentity) || !packetIdentity.contains(commitIdentity)) {
                throw new IllegalArgumentException("Qualification packet identity is stale for " + profile.alias());
            }
        }
        nonBlank(profile.guidePath(), "guide path");
        nonBlank(profile.ramGuidance(), "RAM guidance");
    }

    private static String nonBlank(String value, String field) {
        String normalized = Objects.toString(value, "").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Missing " + field);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT);
    }

    private static String stem(String value) {
        String normalized = normalize(value);
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        if (normalized.endsWith(".gguf")) normalized = normalized.substring(0, normalized.length() - 5);
        return normalized;
    }
}
