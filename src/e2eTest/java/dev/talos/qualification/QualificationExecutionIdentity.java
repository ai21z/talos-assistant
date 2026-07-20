package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.core.security.NoFollowPathPolicy;
import dev.talos.core.util.RuntimeArtifactIdentity;
import dev.talos.runtime.policy.ArtifactCanaryScanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Protocol-v6 execution provenance. This binds the runtime that emitted a
 * trace, the compiled finalizer deriving causality, and any adapted-layout
 * preflight to exact packet bytes.
 */
final class QualificationExecutionIdentity {
    static final String RUNTIME_FILE = "runtime-identity.json";
    static final String HARNESS_FILE = "harness-manifest.json";
    static final String PREFLIGHT_FILE = "preflight-manifest.json";
    static final String RUNTIME_SCHEMA = "talos.modelQualification.runtimeIdentity";
    static final String HARNESS_SCHEMA = "talos.modelQualification.harnessManifest";
    static final String PREFLIGHT_SCHEMA = "talos.modelQualification.preflightManifest";
    static final int RUNTIME_SCHEMA_VERSION = 5;
    static final int HARNESS_SCHEMA_VERSION = 2;
    static final int PREFLIGHT_SCHEMA_VERSION = 2;

    private QualificationExecutionIdentity() {}

    record RuntimeManifest(
            String schemaName,
            int schemaVersion,
            String campaignId,
            String talosVersion,
            String talosCommit,
            int protocolVersion,
            String runtimeArtifactKind,
            long runtimeArtifactSizeBytes,
            String runtimeClasspathSha256,
            long runtimeClasspathSizeBytes,
            int runtimeClasspathEntryCount,
            String builtTalosJarSha256,
            String installedTalosJarSha256,
            String candidateManifestSha256,
            String calibrationRulesSha256,
            String engineManifestSha256,
            String machineManifestSha256,
            String harnessManifestSha256,
            String preflightManifestSha256,
            String engineExecutableSha256,
            String modelArtifactSha256) {}

    record HarnessManifest(
            String schemaName,
            int schemaVersion,
            int protocolVersion,
            String talosCommit,
            String finalizerImplementationSha256,
            String finalizerClasspathSha256,
            Map<String, String> files,
            Map<String, String> repositoryFiles,
            Map<String, String> buildFiles) {
        HarnessManifest {
            files = immutable(files);
            repositoryFiles = immutable(repositoryFiles);
            buildFiles = immutable(buildFiles);
        }
    }

    record PreflightManifest(
            String schemaName,
            int schemaVersion,
            String campaignId,
            String candidateAlias,
            String modelArtifactSha256,
            String engineExecutableSha256,
            String requestLayout,
            Map<String, String> files) {
        PreflightManifest {
            files = immutable(files);
        }
    }

    record TraceRuntime(
            String artifactKind,
            String artifactSha256,
            long artifactSizeBytes,
            String classpathSha256,
            long classpathSizeBytes,
            int classpathEntryCount,
            String version,
            String commit) {}

    record Binding(
            String talosCommit,
            String runtimeArtifactSha256,
            String runtimeIdentityManifestSha256,
            String harnessManifestSha256,
            String finalizerImplementationSha256,
            String finalizerClasspathSha256,
            String preflightManifestSha256) {
        Binding {
            requireRevision(talosCommit, "bound Talos commit");
            requireHash(runtimeArtifactSha256, "bound runtime artifact");
            requireHash(runtimeIdentityManifestSha256, "bound runtime identity manifest");
            requireHash(harnessManifestSha256, "bound harness manifest");
            requireHash(finalizerImplementationSha256, "bound finalizer implementation");
            requireHash(finalizerClasspathSha256, "bound finalizer classpath");
            if (preflightManifestSha256 == null) preflightManifestSha256 = "";
            if (!preflightManifestSha256.isEmpty()) {
                requireHash(preflightManifestSha256, "bound preflight manifest");
            }
        }
    }

    static Binding verify(
            Path packetRoot,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.CalibrationRules calibration,
            QualificationRecords.MachineFacts machine,
            QualificationRecords.EngineFacts engine,
            byte[] traceBytes,
            boolean requireTraceRuntime) throws IOException {
        Objects.requireNonNull(packetRoot, "packetRoot");
        Objects.requireNonNull(campaign, "campaign");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(calibration, "calibration");
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(engine, "engine");
        Path root = packetRoot.toAbsolutePath().normalize();
        NoFollowPathPolicy.bindExistingDirectory(root);
        QualificationRecords.CampaignManifest canonicalCampaign =
                QualificationArtifactPublication.requireWritableCampaign(
                        root, QualificationScenarioBank.loadDefault());
        if (!canonicalCampaign.equals(campaign)) {
            throw invalid("provided campaign differs from the canonical packet manifest");
        }

        byte[] runtimeBytes = requiredFile(root, root.resolve(RUNTIME_FILE));
        byte[] harnessBytes = requiredFile(root, root.resolve(HARNESS_FILE));
        byte[] candidateBytes = requiredFile(root, root.resolve("candidates")
                .resolve(safeSegment(campaign.candidateAlias(), "candidate alias"))
                .resolve("candidate.json"));
        byte[] calibrationBytes = requiredFile(root, root.resolve("candidates")
                .resolve(safeSegment(campaign.candidateAlias(), "candidate alias"))
                .resolve("calibration-rules.json"));
        byte[] engineBytes = requiredFile(root, root.resolve("engines")
                .resolve(safeSegment(campaign.engineLaneId(), "engine lane") + ".json"));
        byte[] machineBytes = requiredFile(root, root.resolve("machines")
                .resolve(safeSegment(campaign.machineId(), "machine id") + ".json"));
        RuntimeManifest runtime = strict(runtimeBytes, RuntimeManifest.class, RUNTIME_FILE);
        HarnessManifest harness = strict(harnessBytes, HarnessManifest.class, HARNESS_FILE);
        validateRuntime(runtime, campaign, candidate, calibration, machine, engine,
                sha256(candidateBytes), sha256(calibrationBytes),
                sha256(engineBytes), sha256(machineBytes));
        validateHarness(root, harness, campaign, runtime, candidate, engine);

        String runtimeManifestHash = sha256(runtimeBytes);
        String harnessManifestHash = sha256(harnessBytes);
        equalHash(runtime.harnessManifestSha256(), harnessManifestHash,
                "runtime identity does not bind the harness manifest bytes");

        String currentFinalizer = currentFinalizerImplementationSha256();
        equalHash(harness.finalizerImplementationSha256(), currentFinalizer,
                "packet harness identity does not match the executing finalizer");
        RuntimeArtifactIdentity.ClasspathSnapshot finalizerClasspath =
                executingFinalizerClasspathSnapshot();
        if (!finalizerClasspath.exact()) {
            throw invalid("executing finalizer classpath identity is unavailable");
        }
        equalHash(harness.finalizerClasspathSha256(), finalizerClasspath.sha256(),
                "packet harness identity does not match the executing finalizer classpath");

        String preflightHash = verifyPreflight(root, campaign, candidate, calibration,
                engine, runtime.preflightManifestSha256());

        if (requireTraceRuntime) {
            TraceRuntime traceRuntime = traceRuntime(traceBytes);
            equalText(traceRuntime.artifactKind(), runtime.runtimeArtifactKind(),
                    "trace runtime artifact kind mismatch");
            equalHash(traceRuntime.artifactSha256(), runtime.installedTalosJarSha256(),
                    "trace runtime artifact hash mismatch");
            if (traceRuntime.artifactSizeBytes() != runtime.runtimeArtifactSizeBytes()) {
                throw invalid("trace runtime artifact size mismatch");
            }
            equalHash(traceRuntime.classpathSha256(), runtime.runtimeClasspathSha256(),
                    "trace runtime classpath hash mismatch");
            if (traceRuntime.classpathSizeBytes() != runtime.runtimeClasspathSizeBytes()
                    || traceRuntime.classpathEntryCount()
                            != runtime.runtimeClasspathEntryCount()) {
                throw invalid("trace runtime classpath size/count mismatch");
            }
            equalText(traceRuntime.version(), runtime.talosVersion(),
                    "trace runtime version mismatch");
            equalRevision(traceRuntime.commit(), runtime.talosCommit(),
                    "trace runtime commit mismatch");
        }

        return new Binding(
                runtime.talosCommit(),
                runtime.installedTalosJarSha256(),
                runtimeManifestHash,
                harnessManifestHash,
                currentFinalizer,
                finalizerClasspath.sha256(),
                preflightHash);
    }

    static String currentFinalizerImplementationSha256() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateCodeSource(digest, "qualification", QualificationExecutionIdentity.class);
            updateCodeSource(digest, "runtime", ArtifactCanaryScanner.class);
            RuntimeArtifactIdentity.ClasspathSnapshot classpath =
                    executingFinalizerClasspathSnapshot();
            if (!classpath.exact()) {
                throw invalid("executing finalizer classpath identity is unavailable");
            }
            updateLengthDelimited(digest,
                    "finalizer-classpath-v1".getBytes(StandardCharsets.UTF_8));
            updateLengthDelimited(digest, classpath.sha256().getBytes(StandardCharsets.US_ASCII));
            updateLengthDelimited(digest, Long.toString(classpath.sizeBytes())
                    .getBytes(StandardCharsets.US_ASCII));
            updateLengthDelimited(digest, Integer.toString(classpath.entryCount())
                    .getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw invalid("unable to identify the executing finalizer", e);
        }
    }

    static RuntimeArtifactIdentity.ClasspathSnapshot executingFinalizerClasspathSnapshot() {
        return RuntimeArtifactIdentity.currentClasspath();
    }

    private static void validateRuntime(
            RuntimeManifest runtime,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.CalibrationRules calibration,
            QualificationRecords.MachineFacts machine,
            QualificationRecords.EngineFacts engine,
            String candidateManifestHash,
            String calibrationRulesHash,
            String engineManifestHash,
            String machineManifestHash) {
        if (!RUNTIME_SCHEMA.equals(runtime.schemaName())
                || runtime.schemaVersion() != RUNTIME_SCHEMA_VERSION
                || runtime.protocolVersion() != QualificationProtocol.VERSION) {
            throw invalid("runtime identity schema mismatch");
        }
        equalText(runtime.campaignId(), campaign.campaignId(), "runtime campaign mismatch");
        equalText(runtime.talosVersion(), campaign.talosVersion(), "runtime version mismatch");
        equalRevision(runtime.talosCommit(), campaign.talosCommit(), "runtime commit mismatch");
        requireText(runtime.runtimeArtifactKind(), "runtime artifact kind");
        if (runtime.runtimeArtifactSizeBytes() <= 0) {
            throw invalid("runtime artifact size is invalid");
        }
        requireHash(runtime.runtimeClasspathSha256(), "runtime classpath");
        if (runtime.runtimeClasspathSizeBytes() <= 0
                || runtime.runtimeClasspathEntryCount() <= 0) {
            throw invalid("runtime classpath size/count is invalid");
        }
        requireHash(runtime.builtTalosJarSha256(), "built Talos artifact");
        requireHash(runtime.installedTalosJarSha256(), "installed Talos artifact");
        equalHash(runtime.builtTalosJarSha256(), runtime.installedTalosJarSha256(),
                "built and installed Talos artifact hashes disagree");
        equalHash(runtime.candidateManifestSha256(), candidateManifestHash,
                "runtime candidate manifest hash mismatch");
        equalHash(runtime.calibrationRulesSha256(), calibrationRulesHash,
                "runtime calibration hash mismatch");
        equalHash(runtime.calibrationRulesSha256(), campaign.calibrationRulesSha256(),
                "runtime and campaign calibration hashes disagree");
        equalHash(runtime.engineManifestSha256(), engineManifestHash,
                "runtime engine manifest hash mismatch");
        equalHash(runtime.machineManifestSha256(), machineManifestHash,
                "runtime machine manifest hash mismatch");
        requireHash(runtime.harnessManifestSha256(), "runtime harness manifest");
        requireHash(runtime.engineExecutableSha256(), "runtime engine executable");
        requireHash(runtime.modelArtifactSha256(), "runtime model artifact");
        equalHash(runtime.engineExecutableSha256(), engine.sha256(),
                "runtime engine executable mismatch");
        equalHash(runtime.modelArtifactSha256(), candidate.sha256(),
                "runtime model artifact mismatch");
        equalHash(runtime.modelArtifactSha256(), calibration.artifactSha256(),
                "runtime calibration model mismatch");
    }

    private static void validateHarness(
            Path root,
            HarnessManifest harness,
            QualificationRecords.CampaignManifest campaign,
            RuntimeManifest runtime,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.EngineFacts engine) throws IOException {
        if (!HARNESS_SCHEMA.equals(harness.schemaName())
                || harness.schemaVersion() != HARNESS_SCHEMA_VERSION
                || harness.protocolVersion() != QualificationProtocol.VERSION) {
            throw invalid("harness manifest schema mismatch");
        }
        equalRevision(harness.talosCommit(), campaign.talosCommit(), "harness commit mismatch");
        requireHash(harness.finalizerImplementationSha256(), "harness finalizer implementation");
        requireHash(harness.finalizerClasspathSha256(), "harness finalizer classpath");
        if (harness.files().isEmpty()) throw invalid("harness file manifest is empty");
        verifyFiles(root, root, harness.files(), "harness");
        validateDeclaredHashes(harness.repositoryFiles(), "repository file");
        validateDeclaredHashes(harness.buildFiles(), "build file");
        QualificationExecutionIdentityWriter.verifyLedger(
                root, campaign, candidate, engine, runtime, harness);
    }

    static String verifyPreflight(
            Path root,
            QualificationRecords.CampaignManifest campaign,
            QualificationRecords.CandidateManifest candidate,
            QualificationRecords.CalibrationRules calibration,
            QualificationRecords.EngineFacts engine,
            String runtimeDeclaredHash) throws IOException {
        boolean adapted = "SINGLE_LEADING_SYSTEM".equals(calibration.requestLayout());
        String rulesHash = safe(calibration.directPreflightEvidenceManifestSha256());
        String runtimeHash = safe(runtimeDeclaredHash);
        Path manifestPath = root.resolve(PREFLIGHT_FILE);
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(root);
        if (!adapted) {
            if (!rulesHash.isEmpty() || !runtimeHash.isEmpty()
                    || packetFiles.inspect(manifestPath)
                            != NoFollowPathPolicy.EntryType.MISSING) {
                throw invalid("preserve-as-written layout has undeclared preflight evidence");
            }
            return "";
        }
        requireHash(rulesHash, "direct preflight evidence manifest");
        requireHash(runtimeHash, "runtime preflight evidence manifest");
        byte[] manifestBytes;
        try {
            manifestBytes = requiredFile(root, manifestPath);
        } catch (NoFollowPathPolicy.Violation rejected) {
            if (rejected.reason() == NoFollowPathPolicy.Reason.MISSING) {
                throw invalid(PREFLIGHT_FILE + " is missing", rejected);
            }
            throw rejected;
        }
        String actualHash = sha256(manifestBytes);
        equalHash(rulesHash, actualHash, "calibration preflight manifest hash mismatch");
        equalHash(runtimeHash, actualHash, "runtime preflight manifest hash mismatch");
        PreflightManifest manifest = strict(
                manifestBytes, PreflightManifest.class, PREFLIGHT_FILE);
        if (!PREFLIGHT_SCHEMA.equals(manifest.schemaName())
                || manifest.schemaVersion() != PREFLIGHT_SCHEMA_VERSION) {
            throw invalid("preflight manifest schema mismatch");
        }
        equalText(manifest.campaignId(), campaign.campaignId(), "preflight campaign mismatch");
        equalText(manifest.candidateAlias(), candidate.alias(), "preflight candidate mismatch");
        equalHash(manifest.modelArtifactSha256(), candidate.sha256(),
                "preflight model artifact mismatch");
        equalHash(manifest.engineExecutableSha256(), engine.sha256(),
                "preflight engine executable mismatch");
        equalText(manifest.requestLayout(), calibration.requestLayout(),
                "preflight request-layout mismatch");
        if (manifest.files().isEmpty()) throw invalid("preflight evidence manifest is empty");
        verifyFiles(root, root.resolve("preflight"), manifest.files(), "preflight");
        return actualHash;
    }

    private static TraceRuntime traceRuntime(byte[] traceBytes) throws IOException {
        if (traceBytes == null || traceBytes.length == 0) {
            throw invalid("trace runtime identity is missing");
        }
        JsonNode root = QualificationJson.readTree(traceBytes);
        JsonNode events = root.path("events");
        if (!events.isArray() || events.isEmpty()) {
            throw invalid("trace event sequence is missing");
        }
        JsonNode selected = null;
        int count = 0;
        for (int i = 0; i < events.size(); i++) {
            JsonNode event = events.get(i);
            String eventType = QualificationJson.requiredText(
                    event.path("type"), "trace event type");
            if ("TRACE_STARTED".equals(eventType)) {
                count++;
                if (i == 0) selected = event.path("data");
            }
        }
        if (count != 1 || selected == null || !selected.isObject()) {
            throw invalid("trace runtime identity must appear once on the first event");
        }
        String kind = QualificationJson.requiredText(
                selected.path("runtimeArtifactKind"), "trace runtime artifact kind");
        String hash = QualificationJson.requiredText(
                selected.path("runtimeArtifactSha256"), "trace runtime artifact hash");
        long size = QualificationJson.requiredLong(
                selected.path("runtimeArtifactSizeBytes"), "trace runtime artifact size");
        String classpathHash = QualificationJson.requiredText(
                selected.path("runtimeClasspathSha256"), "trace runtime classpath hash");
        long classpathSize = QualificationJson.requiredLong(
                selected.path("runtimeClasspathSizeBytes"), "trace runtime classpath size");
        int classpathEntries = QualificationJson.requiredInt(
                selected.path("runtimeClasspathEntryCount"), "trace runtime classpath count");
        String version = QualificationJson.requiredText(
                selected.path("runtimeVersion"), "trace runtime version");
        String commit = QualificationJson.requiredText(
                selected.path("runtimeCommit"), "trace runtime commit");
        requireText(kind, "trace runtime artifact kind");
        requireHash(hash, "trace runtime artifact");
        if (size <= 0) throw invalid("trace runtime artifact size is invalid");
        requireHash(classpathHash, "trace runtime classpath");
        if (classpathSize <= 0 || classpathEntries <= 0) {
            throw invalid("trace runtime classpath size/count is invalid");
        }
        requireText(version, "trace runtime version");
        requireRevision(commit, "trace runtime commit");
        return new TraceRuntime(
                kind, hash, size, classpathHash, classpathSize, classpathEntries,
                version, commit);
    }

    private static void verifyFiles(
            Path packetRoot,
            Path contentRoot,
            Map<String, String> declared,
            String label) throws IOException {
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String name = safePath(entry.getKey(), label + " file");
            requireHash(entry.getValue(), label + " file hash");
            Path path = contentRoot.resolve(name).normalize();
            byte[] bytes = requiredFile(packetRoot, path);
            equalHash(entry.getValue(), sha256(bytes), label + " file hash mismatch: " + name);
        }
    }

    private static void validateDeclaredHashes(Map<String, String> declared, String label) {
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            safePath(entry.getKey(), label);
            requireHash(entry.getValue(), label + " hash");
        }
    }

    private static byte[] requiredFile(Path packetRoot, Path requested) throws IOException {
        NoFollowPathPolicy.GuardedRoot packetFiles =
                NoFollowPathPolicy.bindExistingDirectory(packetRoot);
        return packetFiles.readAllBytes(requested);
    }

    private static <T> T strict(byte[] bytes, Class<T> type, String label) throws IOException {
        try {
            return QualificationJson.read(bytes, type);
        } catch (RuntimeException invalid) {
            throw new IOException("invalid " + label, invalid);
        }
    }

    private static void updateCodeSource(
            MessageDigest digest,
            String label,
            Class<?> anchor) throws Exception {
        Path source = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
        updateLengthDelimited(digest, label.getBytes(StandardCharsets.UTF_8));
        try {
            updateLengthDelimited(digest, NoFollowPathPolicy.readAllBytes(source));
            return;
        } catch (NoFollowPathPolicy.Violation notARegularFile) {
            if (notARegularFile.reason() != NoFollowPathPolicy.Reason.NOT_REGULAR_FILE) {
                throw notARegularFile;
            }
        }
        NoFollowPathPolicy.GuardedRoot sourceFiles =
                NoFollowPathPolicy.bindExistingDirectory(source);
        List<NoFollowPathPolicy.TreeFile> classes =
                sourceFiles.readRegularFiles(
                        source,
                        path -> path.getFileName().toString().endsWith(".class"));
        if (classes.isEmpty()) throw invalid("finalizer class tree is empty");
        for (NoFollowPathPolicy.TreeFile file : classes) {
            updateLengthDelimited(digest, source.relativize(file.path()).toString()
                    .replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            updateLengthDelimited(digest, file.bytes());
        }
    }

    private static void updateLengthDelimited(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(bytes.length).array());
        digest.update(bytes);
    }

    private static String sha256(byte[] bytes) {
        return QualificationScenarioBank.sha256(bytes);
    }

    private static Map<String, String> immutable(Map<String, String> value) {
        return value == null ? Map.of() : Map.copyOf(new TreeMap<>(value));
    }

    private static String safePath(String value, String label) {
        String path = safe(value);
        if (path.isBlank() || path.indexOf('\\') >= 0 || path.startsWith("/")
                || path.matches("(?i)^[a-z]:.*")) {
            throw invalid(label + " path is invalid");
        }
        Path normalized;
        try {
            normalized = Path.of(path).normalize();
        } catch (RuntimeException invalid) {
            throw invalid(label + " path is invalid", invalid);
        }
        if (normalized.isAbsolute() || normalized.startsWith("..")
                || !normalized.toString().replace('\\', '/').equals(path)) {
            throw invalid(label + " path is not canonical");
        }
        return path;
    }

    private static String safeSegment(String value, String label) {
        try {
            return QualificationNamespace.requireSegment(value, label);
        } catch (IllegalArgumentException unsafe) {
            throw invalid(unsafe.getMessage(), unsafe);
        }
    }

    private static void requireHash(String value, String label) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw invalid(label + " SHA-256 is invalid");
        }
    }

    private static void requireRevision(String value, String label) {
        if (value == null || !value.matches("(?i)[0-9a-f]{40}")) {
            throw invalid(label + " revision is invalid");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw invalid(label + " is missing");
    }

    private static void equalHash(String actual, String expected, String message) {
        requireHash(actual, message);
        requireHash(expected, message);
        if (!actual.equalsIgnoreCase(expected)) throw invalid(message);
    }

    private static void equalRevision(String actual, String expected, String message) {
        requireRevision(actual, message);
        requireRevision(expected, message);
        if (!actual.equalsIgnoreCase(expected)) throw invalid(message);
    }

    private static void equalText(String actual, String expected, String message) {
        if (!Objects.equals(actual, expected)) throw invalid(message);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }

    private static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }
}
