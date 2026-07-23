package dev.talos.spi.types;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Path-free identity receipt for one Talos-managed provider process launch.
 *
 * <p>The receipt binds trusted local observations; it does not claim that a
 * hostile kernel or a competing privileged process could not replace bytes or
 * steal the endpoint between observations. The declared stable-filesystem
 * assumption is therefore part of the receipt identity rather than prose.
 */
public record ProviderProcessLaunchReceipt(
        long processId,
        long processStartEpochMilli,
        long launchObservedEpochMilli,
        long readinessObservedEpochMilli,
        String launchNonce,
        String candidateDeclarationSha256,
        String engineExecutableSha256,
        String modelArtifactSha256,
        String launchCommandSha256,
        LaunchArgumentSet launchArgumentSet,
        String effectiveEnvironmentSha256,
        EnvironmentContract environmentContract,
        String managedHost,
        int managedPort,
        boolean prelaunchNoListener,
        String propsResponseSha256,
        String resolvedProfile,
        ServedIdentity servedIdentity,
        EndpointObserverKind endpointObserverKind,
        String endpointObserverIdentitySha256,
        EndpointOwnershipBoundary endpointOwnershipBoundary,
        StableEndpointAssumption stableEndpointAssumption,
        ContentVerificationBoundary contentVerificationBoundary,
        StableFilesystemAssumption stableFilesystemAssumption) {

    public static final String SCHEMA_NAME = "talos.providerProcessLaunchReceipt";
    public static final int SCHEMA_VERSION = 1;
    public static final Set<String> TRACE_FIELDS = Set.of(
            "schemaName",
            "schemaVersion",
            "processReceiptId",
            "processId",
            "processStartEpochMilli",
            "launchObservedEpochMilli",
            "readinessObservedEpochMilli",
            "launchNonce",
            "candidateDeclarationSha256",
            "engineExecutableSha256",
            "modelArtifactSha256",
            "launchCommandSha256",
            "launchArgumentSet",
            "effectiveEnvironmentSha256",
            "environmentContract",
            "managedHost",
            "managedPort",
            "prelaunchNoListener",
            "propsResponseSha256",
            "resolvedProfile",
            "servedIdentity",
            "endpointObserverKind",
            "endpointObserverIdentitySha256",
            "endpointOwnershipBoundary",
            "stableEndpointAssumption",
            "contentVerificationBoundary",
            "stableFilesystemAssumption");

    public enum EnvironmentContract {
        QUALIFICATION_RELEVANT_ALLOWLIST_V1
    }

    public enum EnvironmentKeySemantics {
        CASE_SENSITIVE,
        CASE_INSENSITIVE
    }

    public enum LaunchArgumentSet {
        STANDARD,
        VERIFICATION
    }

    public enum ServedIdentity {
        MATCHED
    }

    public enum EndpointObserverKind {
        WINDOWS_NETSTAT_NOFOLLOW_SHA256,
        LINUX_PROCFS_MANAGED_PROCESS_TREE_V1;

        public EndpointOwnershipBoundary ownershipBoundary() {
            return switch (this) {
                case WINDOWS_NETSTAT_NOFOLLOW_SHA256 ->
                        EndpointOwnershipBoundary
                                .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT;
                case LINUX_PROCFS_MANAGED_PROCESS_TREE_V1 ->
                        EndpointOwnershipBoundary
                                .LINUX_PROCFS_MANAGED_TREE_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT;
            };
        }

        public StableEndpointAssumption stableAssumption() {
            return switch (this) {
                case WINDOWS_NETSTAT_NOFOLLOW_SHA256 ->
                        StableEndpointAssumption
                                .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION;
                case LINUX_PROCFS_MANAGED_PROCESS_TREE_V1 ->
                        StableEndpointAssumption
                                .LINUX_TRUSTED_PROCFS_STABLE_OWNER_WITHOUT_OUT_OF_MANAGED_TREE_FD_DELEGATION;
            };
        }
    }

    public enum EndpointOwnershipBoundary {
        WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
        LINUX_PROCFS_MANAGED_TREE_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT
    }

    public enum StableEndpointAssumption {
        WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
        LINUX_TRUSTED_PROCFS_STABLE_OWNER_WITHOUT_OUT_OF_MANAGED_TREE_FD_DELEGATION
    }

    public enum ContentVerificationBoundary {
        IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256
    }

    public enum StableFilesystemAssumption {
        STABLE_LOCAL_FILES_DURING_PROCESS_LOAD
    }

    public ProviderProcessLaunchReceipt {
        if (processId < 1) {
            throw new IllegalArgumentException("process ID must be positive");
        }
        if (processStartEpochMilli < 1) {
            throw new IllegalArgumentException("process start identity must be positive");
        }
        if (launchObservedEpochMilli < processStartEpochMilli) {
            throw new IllegalArgumentException(
                    "launch observation cannot precede the process start identity");
        }
        if (readinessObservedEpochMilli < launchObservedEpochMilli) {
            throw new IllegalArgumentException(
                    "readiness observation cannot precede the launch observation");
        }
        launchNonce = requirePattern(
                launchNonce, "[0-9a-f]{32}", "launch nonce");
        candidateDeclarationSha256 = requireHash(
                candidateDeclarationSha256, "candidate declaration");
        engineExecutableSha256 = requireHash(
                engineExecutableSha256, "engine executable");
        modelArtifactSha256 = requireHash(
                modelArtifactSha256, "model artifact");
        launchCommandSha256 = requireHash(
                launchCommandSha256, "launch command");
        launchArgumentSet = Objects.requireNonNull(
                launchArgumentSet, "launch argument set");
        effectiveEnvironmentSha256 = requireHash(
                effectiveEnvironmentSha256, "effective environment");
        environmentContract = Objects.requireNonNull(
                environmentContract, "environment contract");
        managedHost = requireLoopbackHost(managedHost);
        if (managedPort < 1 || managedPort > 65_535) {
            throw new IllegalArgumentException("managed port is outside the valid range");
        }
        if (!prelaunchNoListener) {
            throw new IllegalArgumentException(
                    "an exact managed launch receipt requires a no-listener preflight");
        }
        propsResponseSha256 = requireHash(
                propsResponseSha256, "props response");
        resolvedProfile = requirePattern(
                resolvedProfile, "[a-z0-9][a-z0-9._-]{0,127}", "resolved profile");
        servedIdentity = Objects.requireNonNull(servedIdentity, "served identity");
        endpointObserverKind = Objects.requireNonNull(
                endpointObserverKind, "endpoint observer kind");
        endpointObserverIdentitySha256 = requireHash(
                endpointObserverIdentitySha256, "endpoint observer identity");
        endpointOwnershipBoundary = Objects.requireNonNull(
                endpointOwnershipBoundary, "endpoint ownership boundary");
        stableEndpointAssumption = Objects.requireNonNull(
                stableEndpointAssumption, "stable endpoint assumption");
        if (endpointOwnershipBoundary != endpointObserverKind.ownershipBoundary()
                || stableEndpointAssumption != endpointObserverKind.stableAssumption()) {
            throw new IllegalArgumentException(
                    "endpoint observer kind, boundary, and assumption are inconsistent");
        }
        contentVerificationBoundary = Objects.requireNonNull(
                contentVerificationBoundary, "content verification boundary");
        stableFilesystemAssumption = Objects.requireNonNull(
                stableFilesystemAssumption, "stable filesystem assumption");
    }

    /** Canonical path-free identifier recomputed from every typed receipt field. */
    public String receiptId() {
        List<String> fields = new ArrayList<>();
        fields.add(SCHEMA_NAME);
        fields.add(Integer.toString(SCHEMA_VERSION));
        fields.add(Long.toString(processId));
        fields.add(Long.toString(processStartEpochMilli));
        fields.add(Long.toString(launchObservedEpochMilli));
        fields.add(Long.toString(readinessObservedEpochMilli));
        fields.add(launchNonce);
        fields.add(candidateDeclarationSha256);
        fields.add(engineExecutableSha256);
        fields.add(modelArtifactSha256);
        fields.add(launchCommandSha256);
        fields.add(launchArgumentSet.name());
        fields.add(effectiveEnvironmentSha256);
        fields.add(environmentContract.name());
        fields.add(managedHost);
        fields.add(Integer.toString(managedPort));
        fields.add(Boolean.toString(prelaunchNoListener));
        fields.add(propsResponseSha256);
        fields.add(resolvedProfile);
        fields.add(servedIdentity.name());
        fields.add(endpointObserverKind.name());
        fields.add(endpointObserverIdentitySha256);
        fields.add(endpointOwnershipBoundary.name());
        fields.add(stableEndpointAssumption.name());
        fields.add(contentVerificationBoundary.name());
        fields.add(stableFilesystemAssumption.name());
        return "pr-" + digestLengthDelimited(fields);
    }

    /** Safe, closed trace representation. Raw paths, arguments, and environment values are absent. */
    public Map<String, Object> traceData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaName", SCHEMA_NAME);
        data.put("schemaVersion", SCHEMA_VERSION);
        data.put("processReceiptId", receiptId());
        data.put("processId", processId);
        data.put("processStartEpochMilli", processStartEpochMilli);
        data.put("launchObservedEpochMilli", launchObservedEpochMilli);
        data.put("readinessObservedEpochMilli", readinessObservedEpochMilli);
        data.put("launchNonce", launchNonce);
        data.put("candidateDeclarationSha256", candidateDeclarationSha256);
        data.put("engineExecutableSha256", engineExecutableSha256);
        data.put("modelArtifactSha256", modelArtifactSha256);
        data.put("launchCommandSha256", launchCommandSha256);
        data.put("launchArgumentSet", launchArgumentSet.name());
        data.put("effectiveEnvironmentSha256", effectiveEnvironmentSha256);
        data.put("environmentContract", environmentContract.name());
        data.put("managedHost", managedHost);
        data.put("managedPort", managedPort);
        data.put("prelaunchNoListener", prelaunchNoListener);
        data.put("propsResponseSha256", propsResponseSha256);
        data.put("resolvedProfile", resolvedProfile);
        data.put("servedIdentity", servedIdentity.name());
        data.put("endpointObserverKind", endpointObserverKind.name());
        data.put("endpointObserverIdentitySha256", endpointObserverIdentitySha256);
        data.put("endpointOwnershipBoundary", endpointOwnershipBoundary.name());
        data.put("stableEndpointAssumption", stableEndpointAssumption.name());
        data.put("contentVerificationBoundary", contentVerificationBoundary.name());
        data.put("stableFilesystemAssumption", stableFilesystemAssumption.name());
        return Map.copyOf(data);
    }

    /** Hashes an exact ordered vector without delimiter ambiguity. */
    public static String digestOrderedValues(List<String> values) {
        List<String> exact = Objects.requireNonNull(values, "ordered values").stream()
                .map(value -> Objects.requireNonNull(value, "ordered value"))
                .toList();
        return digestLengthDelimited(exact);
    }

    /**
     * Hashes the complete projected qualification child environment using a
     * stable order. Values are never emitted. Case-sensitive platforms accept
     * controlled names only in their canonical spelling; case-insensitive
     * platforms canonicalize them before hashing and launch. The same digest
     * is frozen in protocol-v6 calibration before the installed CLI starts.
     */
    public static String digestEffectiveEnvironment(Map<String, String> environment) {
        Map<String, String> effective = qualificationEffectiveEnvironment(environment);
        List<Map.Entry<String, String>> entries = effective.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry<String, String>::getKey)
                        .thenComparing(Map.Entry::getValue))
                .toList();
        List<String> fields = new ArrayList<>(1 + entries.size() * 2);
        fields.add(EnvironmentContract.QUALIFICATION_RELEVANT_ALLOWLIST_V1.name());
        for (Map.Entry<String, String> entry : entries) {
            fields.add(entry.getKey());
            fields.add(entry.getValue());
        }
        return digestLengthDelimited(fields);
    }

    /**
     * Produces the complete child environment permitted by the qualification
     * contract. The managed launcher clears its inherited environment before
     * installing this map, so an unlisted variable cannot silently influence
     * the provider while remaining outside the receipt digest.
     */
    public static Map<String, String> qualificationEffectiveEnvironment(
            Map<String, String> environment) {
        return qualificationEffectiveEnvironment(
                environment, detectEnvironmentKeySemantics());
    }

    public static Map<String, String> qualificationEffectiveEnvironment(
            Map<String, String> environment,
            EnvironmentKeySemantics keySemantics) {
        Map<String, String> exact = Objects.requireNonNull(
                environment, "effective environment");
        EnvironmentKeySemantics semantics = Objects.requireNonNull(
                keySemantics, "environment key semantics");
        Map<String, String> effective = new java.util.TreeMap<>();
        Set<String> selectedCanonicalKeys = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : exact.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "environment key");
            String canonicalKey = key.toUpperCase(Locale.ROOT);
            String value = Objects.requireNonNull(entry.getValue(), "environment value");
            if (Set.of(
                            "LD_PRELOAD",
                            "LD_AUDIT",
                            "DYLD_INSERT_LIBRARIES",
                            "JAVA_TOOL_OPTIONS",
                            "JDK_JAVA_OPTIONS",
                            "_JAVA_OPTIONS")
                    .contains(canonicalKey) && !value.isEmpty()) {
                throw new IllegalArgumentException(
                        "qualification forbids process injection variables");
            }
            if (!qualificationRelevantEnvironmentKey(canonicalKey)) continue;
            if (semantics == EnvironmentKeySemantics.CASE_SENSITIVE
                    && !key.equals(canonicalKey)) {
                throw new IllegalArgumentException(
                        "provider-relevant environment key is not canonical");
            }
            if (!selectedCanonicalKeys.add(canonicalKey)) {
                throw new IllegalArgumentException(
                        "provider-relevant environment contains a normalized-key collision");
            }
            effective.put(canonicalKey, value);
        }
        return Map.copyOf(effective);
    }

    /**
     * Detects child-environment key semantics from the active default
     * filesystem provider rather than a mutable operating-system label.
     * Java's Windows ProcessBuilder map itself is case-sensitive even though
     * the native child environment is not, so map mutation is not a valid
     * platform probe.
     */
    public static EnvironmentKeySemantics detectEnvironmentKeySemantics() {
        String separator = FileSystems.getDefault().getSeparator();
        if ("\\".equals(separator) && Path.of("C:\\").isAbsolute()) {
            return EnvironmentKeySemantics.CASE_INSENSITIVE;
        }
        if ("/".equals(separator) && Path.of("/").isAbsolute()) {
            return EnvironmentKeySemantics.CASE_SENSITIVE;
        }
        throw new IllegalStateException(
                "default filesystem platform semantics are indeterminate");
    }

    private static boolean qualificationRelevantEnvironmentKey(String key) {
        if (Set.of(
                "PATH",
                "LD_LIBRARY_PATH",
                "DYLD_LIBRARY_PATH",
                "LIBRARY_PATH",
                "SYSTEMROOT",
                "WINDIR",
                "HOME",
                "USERPROFILE",
                "LOCALAPPDATA",
                "APPDATA",
                "TEMP",
                "TMP",
                "TMPDIR",
                "XDG_CACHE_HOME",
                "NUMBER_OF_PROCESSORS",
                "PROCESSOR_ARCHITECTURE",
                "GLIBC_TUNABLES").contains(key)) {
            return true;
        }
        return List.of(
                        "CUDA_",
                        "HIP_",
                        "HSA_",
                        "ROCR_",
                        "ROCM_",
                        "GGML_",
                        "LLAMA_",
                        "OMP_",
                        "MKL_",
                        "OPENBLAS_",
                        "BLAS_",
                        "KMP_",
                        "GOMP_",
                        "TBB_",
                        "CUBLAS_",
                        "NCCL_",
                        "VECLIB_",
                        "ACCELERATE_",
                        "VULKAN_",
                        "VK_",
                        "SYCL_",
                        "ZE_",
                        "ONEAPI_",
                        "HF_")
                .stream()
                .anyMatch(key::startsWith);
    }

    private static String digestLengthDelimited(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value, "digest value")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String requireHash(String value, String label) {
        return requirePattern(value, "[0-9a-f]{64}", label + " SHA-256");
    }

    private static String requirePattern(String value, String pattern, String label) {
        String exact = Objects.toString(value, "");
        if (!exact.matches(pattern)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return exact;
    }

    private static String requireLoopbackHost(String value) {
        String exact = Objects.toString(value, "");
        if (!"127.0.0.1".equals(exact)) {
            throw new IllegalArgumentException(
                    "managed qualification host must be exact IPv4 loopback");
        }
        return exact;
    }
}
