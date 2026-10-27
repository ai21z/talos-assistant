package dev.talos.spi.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, path-free model identity evidence safe for status, debug, and trace surfaces. */
public record ModelIdentitySnapshot(
        String backend,
        String configuredModel,
        String configuredModelFile,
        String declaredProfile,
        String resolvedProfile,
        String observedModelFile,
        String artifactState,
        String artifactVerificationIdentity,
        String engineLane,
        String engineExecutableIdentity,
        String servedIdentityState,
        String resolution,
        String effectiveToolMode,
        String effectiveRequestLayout,
        int effectiveContextTokens,
        String disposition,
        String propsState,
        String propsResponseSha256,
        String engineBuildInfo,
        boolean chatAvailable,
        List<String> reasonCodes
) {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern WINDOWS_DRIVE_PATH = Pattern.compile(
            "(?i)(?:^|[^A-Za-z0-9])[a-z]:[\\\\/][^\\s]*");
    private static final Pattern UNC_PATH = Pattern.compile(
            "(?:^|[\\s=({\\[,;'\"])(?:\\\\\\\\|//)[^\\s]+");
    private static final Pattern POSIX_PATH = Pattern.compile(
            "(?:^|[\\s=:({\\[,;'\"])/(?!/)[^\\s]+");
    public static final Set<String> TRACE_FIELDS = Set.of(
            "backend", "configuredModel", "configuredModelFile",
            "declaredProfile", "resolvedProfile", "observedModelFile",
            "artifactState", "artifactVerificationIdentity", "engineLane",
            "engineExecutableIdentity", "servedIdentityState", "resolution",
            "effectiveToolMode", "effectiveRequestLayout", "effectiveContextTokens",
            "disposition", "propsState", "propsResponseSha256", "engineBuildInfo",
            "chatAvailable", "reasonCodes");
    public static final Set<String> PROCESS_BINDING_TRACE_FIELDS = Set.of(
            "propsResponseSha256", "engineExecutableIdentity", "resolvedProfile",
            "artifactState", "servedIdentityState", "resolution", "propsState");
    private static final ModelIdentitySnapshot EMPTY = builder().build();

    public ModelIdentitySnapshot {
        backend = pathFreeMetadata(backend, 48);
        configuredModel = pathFreeLabel(configuredModel);
        configuredModelFile = fileNameOnly(configuredModelFile);
        declaredProfile = pathFreeMetadata(declaredProfile, 160);
        resolvedProfile = pathFreeMetadata(resolvedProfile, 160);
        observedModelFile = fileNameOnly(observedModelFile);
        artifactState = token(artifactState, 64);
        artifactVerificationIdentity = hash(artifactVerificationIdentity);
        engineLane = pathFreeMetadata(engineLane, 120);
        engineExecutableIdentity = hash(engineExecutableIdentity);
        servedIdentityState = token(servedIdentityState, 64);
        resolution = token(resolution, 64);
        effectiveToolMode = token(effectiveToolMode, 64);
        effectiveRequestLayout = token(effectiveRequestLayout, 64);
        effectiveContextTokens = Math.max(0, effectiveContextTokens);
        disposition = pathFreeMetadata(disposition, 120);
        propsState = token(propsState, 64);
        propsResponseSha256 = hash(propsResponseSha256);
        engineBuildInfo = pathFreeMetadata(engineBuildInfo, 200);
        reasonCodes = reasons(reasonCodes);
    }

    public static ModelIdentitySnapshot empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Reconstructs only an exact value that this type could have emitted to a trace. */
    public static ModelIdentitySnapshot fromTraceData(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty() || !TRACE_FIELDS.containsAll(raw.keySet())) {
            throw new IllegalArgumentException("model identity trace fields are not closed");
        }
        Object chat = raw.get("chatAvailable");
        if (!(chat instanceof Boolean chatAvailable)) {
            throw new IllegalArgumentException(
                    "model identity chatAvailable must be an exact boolean");
        }
        int contextTokens = 0;
        if (raw.containsKey("effectiveContextTokens")) {
            Object value = raw.get("effectiveContextTokens");
            if (!(value instanceof Integer exact) || exact <= 0) {
                throw new IllegalArgumentException(
                        "model identity effectiveContextTokens must be a positive exact integer");
            }
            contextTokens = exact;
        }
        List<String> reasonCodes = List.of();
        if (raw.containsKey("reasonCodes")) {
            Object value = raw.get("reasonCodes");
            if (!(value instanceof List<?> values)) {
                throw new IllegalArgumentException(
                        "model identity reasonCodes must be an exact string list");
            }
            ArrayList<String> exact = new ArrayList<>(values.size());
            for (Object item : values) {
                if (!(item instanceof String reason)) {
                    throw new IllegalArgumentException(
                            "model identity reasonCodes must contain only strings");
                }
                exact.add(reason);
            }
            reasonCodes = List.copyOf(exact);
        }
        ModelIdentitySnapshot snapshot = builder()
                .backend(optionalString(raw, "backend"))
                .configuredModel(optionalString(raw, "configuredModel"))
                .configuredModelFile(optionalString(raw, "configuredModelFile"))
                .declaredProfile(optionalString(raw, "declaredProfile"))
                .resolvedProfile(optionalString(raw, "resolvedProfile"))
                .observedModelFile(optionalString(raw, "observedModelFile"))
                .artifactState(optionalString(raw, "artifactState"))
                .artifactVerificationIdentity(optionalString(
                        raw, "artifactVerificationIdentity"))
                .engineLane(optionalString(raw, "engineLane"))
                .engineExecutableIdentity(optionalString(
                        raw, "engineExecutableIdentity"))
                .servedIdentityState(optionalString(raw, "servedIdentityState"))
                .resolution(optionalString(raw, "resolution"))
                .effectiveToolMode(optionalString(raw, "effectiveToolMode"))
                .effectiveRequestLayout(optionalString(raw, "effectiveRequestLayout"))
                .effectiveContextTokens(contextTokens)
                .disposition(optionalString(raw, "disposition"))
                .propsState(optionalString(raw, "propsState"))
                .propsResponseSha256(optionalString(raw, "propsResponseSha256"))
                .engineBuildInfo(optionalString(raw, "engineBuildInfo"))
                .chatAvailable(chatAvailable)
                .reasonCodes(reasonCodes)
                .build();
        if (!snapshot.available() || !snapshot.traceData().equals(raw)) {
            throw new IllegalArgumentException(
                    "model identity trace data is not an exact canonical snapshot");
        }
        return snapshot;
    }

    public boolean qualified() {
        return "QUALIFIED".equals(resolution)
                && !resolvedProfile.isBlank()
                && "EXACT".equals(artifactState)
                && "MATCHED".equals(servedIdentityState)
                && "AVAILABLE".equals(propsState);
    }

    public boolean available() {
        return !backend.isBlank() || !configuredModel.isBlank();
    }

    /** Key names are stable because prompt-debug renders them directly. */
    public Map<String, String> diagnostics() {
        if (!available()) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        put(out, "model.backend", backend);
        put(out, "model.configured", configuredModel);
        put(out, "model.configuredFile", configuredModelFile);
        put(out, "model.declaredProfile", declaredProfile);
        put(out, "model.resolvedProfile", resolvedProfile);
        put(out, "model.observedFile", observedModelFile);
        put(out, "model.artifact", artifactState);
        put(out, "model.artifactVerification", artifactVerificationIdentity);
        put(out, "model.engineLane", engineLane);
        put(out, "model.engineExecutable", engineExecutableIdentity);
        put(out, "model.servedIdentity", servedIdentityState);
        put(out, "model.resolution", resolution);
        put(out, "model.toolMode", effectiveToolMode);
        put(out, "model.requestLayout", effectiveRequestLayout);
        if (effectiveContextTokens > 0) {
            out.put("model.contextTokens", Integer.toString(effectiveContextTokens));
        }
        put(out, "model.disposition", disposition);
        put(out, "model.propsState", propsState);
        put(out, "model.propsSha256", propsResponseSha256);
        put(out, "model.engineBuild", engineBuildInfo);
        if (!reasonCodes.isEmpty()) {
            out.put("model.reasons", String.join(",", reasonCodes));
        }
        out.put("model.chatAvailable", Boolean.toString(chatAvailable));
        return Collections.unmodifiableMap(out);
    }

    /** Typed values for local trace events. The record constructor already bounds every field. */
    public Map<String, Object> traceData() {
        if (!available()) return Map.of();
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        putObject(out, "backend", backend);
        putObject(out, "configuredModel", configuredModel);
        putObject(out, "configuredModelFile", configuredModelFile);
        putObject(out, "declaredProfile", declaredProfile);
        putObject(out, "resolvedProfile", resolvedProfile);
        putObject(out, "observedModelFile", observedModelFile);
        putObject(out, "artifactState", artifactState);
        putObject(out, "artifactVerificationIdentity", artifactVerificationIdentity);
        putObject(out, "engineLane", engineLane);
        putObject(out, "engineExecutableIdentity", engineExecutableIdentity);
        putObject(out, "servedIdentityState", servedIdentityState);
        putObject(out, "resolution", resolution);
        putObject(out, "effectiveToolMode", effectiveToolMode);
        putObject(out, "effectiveRequestLayout", effectiveRequestLayout);
        if (effectiveContextTokens > 0) out.put("effectiveContextTokens", effectiveContextTokens);
        putObject(out, "disposition", disposition);
        putObject(out, "propsState", propsState);
        putObject(out, "propsResponseSha256", propsResponseSha256);
        putObject(out, "engineBuildInfo", engineBuildInfo);
        out.put("chatAvailable", chatAvailable);
        if (!reasonCodes.isEmpty()) out.put("reasonCodes", reasonCodes);
        return Collections.unmodifiableMap(out);
    }

    private static void put(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static void putObject(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }

    private static String fileNameOnly(String raw) {
        String clean = bounded(raw, 512).replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        return bounded(slash >= 0 ? clean.substring(slash + 1) : clean, 160);
    }

    private static String pathFreeLabel(String raw) {
        String clean = bounded(raw, 512);
        if (containsAbsolutePath(clean)) {
            return fileNameOnly(clean);
        }
        return bounded(clean, 160);
    }

    private static String pathFreeMetadata(String raw, int max) {
        String clean = bounded(raw, max);
        return containsAbsolutePath(clean) ? "path redacted" : clean;
    }

    private static String hash(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return SHA256.matcher(value).matches() ? value : "";
    }

    private static String token(String raw, int max) {
        String value = pathFreeMetadata(raw, max);
        return value.matches("[A-Za-z0-9_.:/-]+") ? value : "";
    }

    private static boolean containsAbsolutePath(String value) {
        return value != null && (WINDOWS_DRIVE_PATH.matcher(value).find()
                || UNC_PATH.matcher(value).find()
                || POSIX_PATH.matcher(value).find());
    }

    private static String optionalString(Map<String, ?> raw, String key) {
        if (!raw.containsKey(key)) return "";
        Object value = raw.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    "model identity " + key + " must be an exact string");
        }
        return text;
    }

    private static List<String> reasons(List<String> raw) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (raw != null) {
            for (String item : raw) {
                String value = token(item, 80);
                if (!value.isBlank()) unique.add(value);
                if (unique.size() == 32) break;
            }
        }
        return List.copyOf(unique);
    }

    private static String bounded(String raw, int max) {
        if (raw == null || raw.isBlank()) return "";
        StringBuilder clean = new StringBuilder(Math.min(raw.length(), max));
        boolean lastSpace = false;
        for (int i = 0; i < raw.length() && clean.length() < max; i++) {
            char ch = raw.charAt(i);
            boolean space = Character.isWhitespace(ch) || Character.isISOControl(ch);
            if (space) {
                if (!lastSpace && clean.length() > 0) clean.append(' ');
                lastSpace = true;
            } else {
                clean.append(ch);
                lastSpace = false;
            }
        }
        return clean.toString().strip();
    }

    public static final class Builder {
        private String backend = "";
        private String configuredModel = "";
        private String configuredModelFile = "";
        private String declaredProfile = "";
        private String resolvedProfile = "";
        private String observedModelFile = "";
        private String artifactState = "";
        private String artifactVerificationIdentity = "";
        private String engineLane = "";
        private String engineExecutableIdentity = "";
        private String servedIdentityState = "";
        private String resolution = "";
        private String effectiveToolMode = "";
        private String effectiveRequestLayout = "";
        private int effectiveContextTokens;
        private String disposition = "";
        private String propsState = "";
        private String propsResponseSha256 = "";
        private String engineBuildInfo = "";
        private boolean chatAvailable;
        private List<String> reasonCodes = new ArrayList<>();

        public Builder backend(String value) { backend = value; return this; }
        public Builder configuredModel(String value) { configuredModel = value; return this; }
        public Builder configuredModelFile(String value) { configuredModelFile = value; return this; }
        public Builder declaredProfile(String value) { declaredProfile = value; return this; }
        public Builder resolvedProfile(String value) { resolvedProfile = value; return this; }
        public Builder observedModelFile(String value) { observedModelFile = value; return this; }
        public Builder artifactState(String value) { artifactState = value; return this; }
        public Builder artifactVerificationIdentity(String value) { artifactVerificationIdentity = value; return this; }
        public Builder engineLane(String value) { engineLane = value; return this; }
        public Builder engineExecutableIdentity(String value) { engineExecutableIdentity = value; return this; }
        public Builder servedIdentityState(String value) { servedIdentityState = value; return this; }
        public Builder resolution(String value) { resolution = value; return this; }
        public Builder effectiveToolMode(String value) { effectiveToolMode = value; return this; }
        public Builder effectiveRequestLayout(String value) { effectiveRequestLayout = value; return this; }
        public Builder effectiveContextTokens(int value) { effectiveContextTokens = value; return this; }
        public Builder disposition(String value) { disposition = value; return this; }
        public Builder propsState(String value) { propsState = value; return this; }
        public Builder propsResponseSha256(String value) { propsResponseSha256 = value; return this; }
        public Builder engineBuildInfo(String value) { engineBuildInfo = value; return this; }
        public Builder chatAvailable(boolean value) { chatAvailable = value; return this; }
        public Builder reasonCodes(List<String> value) { reasonCodes = value; return this; }

        public ModelIdentitySnapshot build() {
            return new ModelIdentitySnapshot(
                    backend, configuredModel, configuredModelFile, declaredProfile, resolvedProfile, observedModelFile,
                    artifactState, artifactVerificationIdentity, engineLane, engineExecutableIdentity,
                    servedIdentityState, resolution, effectiveToolMode, effectiveRequestLayout,
                    effectiveContextTokens,
                    disposition, propsState, propsResponseSha256, engineBuildInfo, chatAvailable,
                    reasonCodes);
        }
    }
}
