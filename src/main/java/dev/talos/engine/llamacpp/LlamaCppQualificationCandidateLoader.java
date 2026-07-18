package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.util.BuildInfo;
import dev.talos.engine.llamacpp.profile.LlamaCppArtifactVerificationStore;
import dev.talos.engine.llamacpp.profile.LlamaCppProfileResolver;
import dev.talos.spi.types.RequestMessageLayout;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Loads an exact, process-scoped capability ceiling for a frozen qualification campaign. */
final class LlamaCppQualificationCandidateLoader {
    static final String ROOT_PROPERTY = "talos.qualification.root";
    static final String CALIBRATION_PROPERTY = "talos.qualification.calibration";
    static final String CALIBRATION_SHA256_PROPERTY = "talos.qualification.calibrationSha256";

    private static final String CALIBRATION_SCHEMA = "talos.modelQualification.calibrationRules";
    private static final int CALIBRATION_SCHEMA_VERSION = 1;
    private static final int LEGACY_QUALIFICATION_PROTOCOL_VERSION = 5;
    private static final int CURRENT_QUALIFICATION_PROTOCOL_VERSION = 6;
    private static final int MAX_CALIBRATION_BYTES = 1024 * 1024;
    private static final Pattern ALIAS = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    record Settings(Path root, Path calibration, String calibrationSha256) {
        static Settings disabled() {
            return new Settings(null, null, "");
        }

        static Settings fromSystemProperties() {
            String root = clean(System.getProperty(ROOT_PROPERTY));
            String calibration = clean(System.getProperty(CALIBRATION_PROPERTY));
            String hash = clean(System.getProperty(CALIBRATION_SHA256_PROPERTY));
            if (root.isBlank() && calibration.isBlank() && hash.isBlank()) return disabled();
            try {
                return new Settings(
                        root.isBlank() ? null : Path.of(root),
                        calibration.isBlank() ? null : Path.of(calibration),
                        hash);
            } catch (RuntimeException error) {
                throw invalid("qualification paths are invalid", error);
            }
        }

        boolean isDisabled() {
            return root == null && calibration == null && clean(calibrationSha256).isBlank();
        }
    }

    record LoadedCandidate(
            LlamaCppModelCatalog.Profile profile,
            String declarationSha256,
            LlamaCppProfileResolver.ArtifactIdentity artifactIdentity,
            String engineExecutableSha256) {

        LoadedCandidate(LlamaCppModelCatalog.Profile profile, String declarationSha256) {
            this(profile, declarationSha256, null, "");
        }

        LoadedCandidate {
            Objects.requireNonNull(profile, "profile");
            declarationSha256 = normalizedHash(declarationSha256);
            engineExecutableSha256 = normalizedHash(engineExecutableSha256);
        }
    }

    private final Supplier<String> buildCommit;
    private final ObjectMapper json;

    LlamaCppQualificationCandidateLoader() {
        this(BuildInfo::commitSha);
    }

    LlamaCppQualificationCandidateLoader(Supplier<String> buildCommit) {
        this.buildCommit = Objects.requireNonNull(buildCommit, "buildCommit");
        this.json = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    Optional<LoadedCandidate> loadFromSystemProperties(LlamaCppConfig config, Path talosHome) {
        return load(Settings.fromSystemProperties(), config, talosHome);
    }

    Optional<LoadedCandidate> load(Settings settings, LlamaCppConfig config, Path talosHome) {
        Settings safe = settings == null ? Settings.disabled() : settings;
        if (safe.isDisabled()) return Optional.empty();
        requireComplete(safe);
        Objects.requireNonNull(config, "config");

        try {
            Path root = existingDirectory(safe.root(), "campaign root");
            Path calibration = existingFile(safe.calibration(), "calibration rules");
            if (!calibration.startsWith(root)) {
                throw invalid("calibration rules are outside the campaign root");
            }
            Path isolatedHome = existingDirectory(talosHome, "isolated Talos home");
            Path expectedHome = existingDirectory(root.resolve("home/.talos"), "campaign Talos home");
            if (!isolatedHome.equals(expectedHome)) {
                throw invalid("campaign root does not own the isolated Talos home");
            }

            byte[] calibrationBytes = boundedRead(calibration);
            String declaredRulesHash = requireHash(safe.calibrationSha256(), "calibration SHA-256");
            String actualRulesHash = sha256(calibrationBytes);
            if (!declaredRulesHash.equals(actualRulesHash)) {
                throw invalid("calibration SHA-256 does not match the frozen file");
            }
            JsonNode rules = parseObject(calibrationBytes);
            requireEquals(CALIBRATION_SCHEMA, text(rules, "schemaName"), "calibration schema");
            requireEquals(CALIBRATION_SCHEMA_VERSION, integer(rules, "schemaVersion"), "schema version");
            requireEquals("STANDARD", text(rules, "configurationKind"), "configuration kind");
            text(rules, "configurationId");
            if (!optionalText(rules, "baselineConfigurationId").isBlank()) {
                throw invalid("standard configuration must not declare a baseline configuration");
            }

            String alias = text(rules, "candidateAlias").toLowerCase(Locale.ROOT);
            if (!ALIAS.matcher(alias).matches()) throw invalid("candidate alias is invalid");
            requireEquals(config.model(), alias, "candidate alias");
            Path expectedCalibration = existingFile(
                    root.resolve(Path.of("candidates", alias, "calibration-rules.json")),
                    "candidate calibration rules");
            if (!calibration.equals(expectedCalibration)) {
                throw invalid("calibration path does not match the candidate declaration");
            }

            String talosCommit = requireRevision(text(rules, "talosCommit"), "Talos commit");
            String runningCommit = clean(buildCommit.get()).toLowerCase(Locale.ROOT);
            if (runningCommit.equals(BuildInfo.UNKNOWN)
                    || !REVISION.matcher(runningCommit).matches() && !runningCommit.matches("[0-9a-f]{7,39}")
                    || !talosCommit.startsWith(runningCommit)) {
                throw invalid("Talos build does not match the frozen commit");
            }
            int protocolVersion = integer(rules, "protocolVersion");
            if (protocolVersion != LEGACY_QUALIFICATION_PROTOCOL_VERSION
                    && protocolVersion != CURRENT_QUALIFICATION_PROTOCOL_VERSION) {
                throw invalid("qualification protocol is unsupported: " + protocolVersion);
            }
            requireEquals("native", text(rules, "toolMode").toLowerCase(Locale.ROOT), "tool mode");
            if (!config.managed() || !config.nativeCallingRequested() || config.hasHfSource()) {
                throw invalid("qualification candidate requires managed native local-file mode");
            }
            int context = integer(rules, "contextTokens");
            requireEquals(config.context(), context, "context");

            String launchField = config.verificationLogging()
                    ? "verificationLaunchArguments"
                    : "launchArguments";
            List<String> launchArguments = textList(rules, launchField);
            requireLaunchShape(launchArguments, LlamaCppServerManager.buildArguments(config));
            Path modelPath = existingFile(path(config.modelPath(), "configured model path"), "model artifact");
            Path launchModelPath = existingFile(
                    path(argumentValue(launchArguments, "-m"), "launch model path"), "launch model artifact");
            if (!modelPath.equals(launchModelPath)) throw invalid("launch model path does not match config");
            requireEquals(modelPath.getFileName().toString(), text(rules, "artifact"), "artifact file");

            long artifactSize = positiveLong(rules, "artifactSizeBytes");
            requireEquals(Files.size(modelPath), artifactSize, "artifact size");
            String artifactSha256 = requireHash(text(rules, "artifactSha256"), "artifact SHA-256");

            Path enginePath = existingFile(path(config.serverPath(), "configured server path"), "engine executable");
            String engineTag = text(rules, "llamaCppTag").toLowerCase(Locale.ROOT);
            String backend = text(rules, "backend").toLowerCase(Locale.ROOT);
            String engineLane = LlamaCppResolvedProfileService.engineLane(enginePath);
            if (engineLane.isBlank()
                    || !engineLane.endsWith(":" + engineTag)
                    || !engineLane.substring(0, engineLane.indexOf(':')).endsWith("-" + backend)) {
                throw invalid("engine lane does not match the frozen tag and backend");
            }
            String engineSha256 = requireHash(text(rules, "executableSha256"), "engine SHA-256");
            requireEquals(engineSha256, sha256File(enginePath), "engine SHA-256");

            long totalParameters = positiveLong(rules, "totalParameters");
            long activeParameters = positiveLong(rules, "activeParameters");
            if (activeParameters > totalParameters) throw invalid("active parameters exceed total parameters");
            String reasoning = text(rules, "reasoningBehavior");
            RequestMessageLayout requestLayout = requestLayout(optionalText(rules, "requestLayout"));
            LlamaCppModelCatalog.Profile profile = new LlamaCppModelCatalog.Profile(
                    alias,
                    new LlamaCppModelCatalog.ModelArtifact(
                            text(rules, "repository"),
                            requireRevision(text(rules, "revision"), "model revision"),
                            modelPath.getFileName().toString(),
                            artifactSha256,
                            artifactSize,
                            text(rules, "license")),
                    new LlamaCppModelCatalog.RuntimeDefaults(
                            text(rules, "architecture"),
                            activeParameters + " active of " + totalParameters + " total parameters",
                            true,
                            "native",
                            reasoning,
                            text(rules, "chatTemplate"),
                            context,
                            context,
                            "frozen qualification calibration",
                            requestLayout,
                            config.serverArgs(),
                            List.of(engineLane),
                            "qualification campaign host only",
                            true),
                    new LlamaCppModelCatalog.QualificationEvidence(
                            LlamaCppModelCatalog.Disposition.LAB_ONLY,
                            false,
                            false,
                            protocolVersion,
                            "qualification-candidate:" + root.getFileName(),
                            talosCommit,
                            "not-scored",
                            LocalDate.of(1970, 1, 1),
                            "Frozen qualification candidate. No support claim."),
                    "",
                    "Qualification campaign host only");

            LlamaCppProfileResolver.ArtifactIdentity artifactIdentity =
                    new LlamaCppArtifactVerificationStore().verify(
                            modelPath, alias, profile.artifact(), isolatedHome);
            if (artifactIdentity.state() != LlamaCppProfileResolver.ArtifactState.EXACT) {
                throw invalid("artifact verification record is not exact");
            }
            return Optional.of(new LoadedCandidate(
                    profile, actualRulesHash, artifactIdentity, engineSha256));
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("qualification declaration could not be verified", error);
        }
    }

    private static void requireComplete(Settings settings) {
        if (settings.root() == null
                || settings.calibration() == null
                || clean(settings.calibrationSha256()).isBlank()) {
            throw invalid("qualification properties are incomplete");
        }
    }

    private JsonNode parseObject(byte[] bytes) {
        try (JsonParser parser = json.getFactory().createParser(bytes)) {
            JsonNode root = json.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw invalid("calibration rules must contain one JSON object");
            }
            return root;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("calibration rules are not valid JSON", error);
        }
    }

    private static byte[] boundedRead(Path path) throws Exception {
        long size = Files.size(path);
        if (size <= 0 || size > MAX_CALIBRATION_BYTES) {
            throw invalid("calibration rules size is outside the allowed range");
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_CALIBRATION_BYTES + 1);
            if (bytes.length > MAX_CALIBRATION_BYTES) {
                throw invalid("calibration rules exceed the allowed size");
            }
            return bytes;
        }
    }

    private static void requireLaunchShape(List<String> actual, List<String> expected) throws Exception {
        if (actual.size() != expected.size()) throw invalid("launch arguments do not match config");
        for (int i = 0; i < expected.size(); i++) {
            if (i > 0 && "-m".equals(expected.get(i - 1))) {
                Path actualPath = existingFile(path(actual.get(i), "launch model path"), "launch model artifact");
                Path expectedPath = existingFile(path(expected.get(i), "configured model path"), "model artifact");
                if (!actualPath.equals(expectedPath)) throw invalid("launch model path does not match config");
            } else if (!expected.get(i).equals(actual.get(i))) {
                throw invalid("launch arguments do not match config");
            }
        }
    }

    private static String argumentValue(List<String> arguments, String flag) {
        String value = "";
        for (int i = 0; i + 1 < arguments.size(); i++) {
            if (!flag.equals(arguments.get(i))) continue;
            if (!value.isBlank()) throw invalid("launch arguments contain duplicate " + flag);
            value = arguments.get(i + 1);
        }
        if (value.isBlank()) throw invalid("launch arguments do not contain " + flag);
        return value;
    }

    private static List<String> textList(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw invalid(field + " must be a nonempty string array");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.textValue().isBlank()) {
                throw invalid(field + " must contain only nonblank strings");
            }
            out.add(entry.textValue());
        }
        return List.copyOf(out);
    }

    private static RequestMessageLayout requestLayout(String value) {
        try {
            return RequestMessageLayout.parse(value);
        } catch (IllegalArgumentException error) {
            throw invalid("requestLayout is not recognized", error);
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be a nonblank string");
        }
        return value.textValue().trim();
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw invalid(field + " must be a string");
        return value.textValue().trim();
    }

    private static int integer(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(field + " must be an integer");
        }
        return value.intValue();
    }

    private static long positiveLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalid(field + " must be a positive integer");
        }
        return value.longValue();
    }

    private static Path path(String value, String label) {
        try {
            return Path.of(clean(value));
        } catch (RuntimeException error) {
            throw invalid(label + " is invalid", error);
        }
    }

    private static Path existingDirectory(Path value, String label) throws Exception {
        if (value == null || !Files.isDirectory(value)) throw invalid(label + " is missing");
        return value.toRealPath();
    }

    private static Path existingFile(Path value, String label) throws Exception {
        if (value == null || !Files.isRegularFile(value)) throw invalid(label + " is missing");
        return value.toRealPath();
    }

    private static String requireRevision(String value, String label) {
        String normalized = clean(value).toLowerCase(Locale.ROOT);
        if (!REVISION.matcher(normalized).matches()) throw invalid(label + " is not a full revision");
        return normalized;
    }

    private static String requireHash(String value, String label) {
        String normalized = normalizedHash(value);
        if (!SHA256.matcher(normalized).matches()) throw invalid(label + " is not a SHA-256");
        return normalized;
    }

    private static void requireEquals(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) throw invalid(label + " does not match");
    }

    private static String sha256File(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String normalizedHash(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return Objects.toString(value, "").trim();
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("invalid qualification declaration: " + message);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException("invalid qualification declaration: " + message, cause);
    }
}
