package dev.talos.spi.types;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderProcessLaunchReceiptTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final String C = "c".repeat(64);
    private static final String D = "d".repeat(64);
    private static final String E = "e".repeat(64);
    private static final String F = "f".repeat(64);

    @Test
    void canonicalReceiptIdIsDeterministicAndBindsProcessAndCommandIdentity() {
        ProviderProcessLaunchReceipt receipt = receipt(4812L, 1_721_600_000_000L, A);
        ProviderProcessLaunchReceipt changedProcess = receipt(4813L, 1_721_600_000_000L, A);
        ProviderProcessLaunchReceipt reusedPid = receipt(4812L, 1_721_600_000_001L, A);
        ProviderProcessLaunchReceipt changedCommand = receipt(4812L, 1_721_600_000_000L, B);

        assertTrue(receipt.receiptId().matches("pr-[0-9a-f]{64}"));
        assertEquals(receipt.receiptId(), receipt(4812L, 1_721_600_000_000L, A).receiptId());
        assertNotEquals(receipt.receiptId(), changedProcess.receiptId());
        assertNotEquals(receipt.receiptId(), reusedPid.receiptId());
        assertNotEquals(receipt.receiptId(), changedCommand.receiptId());
    }

    @Test
    void receiptIdentityBindsTheExactEndpointObserverAndRejectsMixedPlatformClaims() {
        ProviderProcessLaunchReceipt windows = receipt(
                4812L, 1_721_600_000_000L, A);
        ProviderProcessLaunchReceipt changedObserver = new ProviderProcessLaunchReceipt(
                windows.processId(),
                windows.processStartEpochMilli(),
                windows.launchObservedEpochMilli(),
                windows.readinessObservedEpochMilli(),
                windows.launchNonce(),
                windows.candidateDeclarationSha256(),
                windows.engineExecutableSha256(),
                windows.modelArtifactSha256(),
                windows.launchCommandSha256(),
                windows.launchArgumentSet(),
                windows.effectiveEnvironmentSha256(),
                windows.environmentContract(),
                windows.managedHost(),
                windows.managedPort(),
                windows.prelaunchNoListener(),
                windows.propsResponseSha256(),
                windows.resolvedProfile(),
                windows.servedIdentity(),
                windows.endpointObserverKind(),
                "0".repeat(64),
                windows.endpointOwnershipBoundary(),
                windows.stableEndpointAssumption(),
                windows.contentVerificationBoundary(),
                windows.stableFilesystemAssumption());

        assertNotEquals(windows.receiptId(), changedObserver.receiptId());
        assertThrows(IllegalArgumentException.class,
                () -> new ProviderProcessLaunchReceipt(
                        windows.processId(),
                        windows.processStartEpochMilli(),
                        windows.launchObservedEpochMilli(),
                        windows.readinessObservedEpochMilli(),
                        windows.launchNonce(),
                        windows.candidateDeclarationSha256(),
                        windows.engineExecutableSha256(),
                        windows.modelArtifactSha256(),
                        windows.launchCommandSha256(),
                        windows.launchArgumentSet(),
                        windows.effectiveEnvironmentSha256(),
                        windows.environmentContract(),
                        windows.managedHost(),
                        windows.managedPort(),
                        windows.prelaunchNoListener(),
                        windows.propsResponseSha256(),
                        windows.resolvedProfile(),
                        windows.servedIdentity(),
                        ProviderProcessLaunchReceipt.EndpointObserverKind
                                .LINUX_PROCFS_MANAGED_PROCESS_TREE_V1,
                        windows.endpointObserverIdentitySha256(),
                        windows.endpointOwnershipBoundary(),
                        windows.stableEndpointAssumption(),
                        windows.contentVerificationBoundary(),
                        windows.stableFilesystemAssumption()));
    }

    @Test
    void traceProjectionContainsOnlyTheClosedPathFreeReceiptContract() {
        ProviderProcessLaunchReceipt receipt = receipt(4812L, 1_721_600_000_000L, A);

        Map<String, Object> trace = receipt.traceData();

        assertEquals(ProviderProcessLaunchReceipt.TRACE_FIELDS, trace.keySet());
        assertEquals(receipt.receiptId(), trace.get("processReceiptId"));
        String rendered = trace.toString();
        assertFalse(rendered.contains("C:\\"));
        assertFalse(rendered.contains("/home/"));
        assertFalse(rendered.contains("llama-server"));
        assertFalse(rendered.contains("TOP_SECRET"));
    }

    @Test
    void orderedCommandAndEffectiveEnvironmentDigestsAreDeterministicAndSensitive() {
        String command = ProviderProcessLaunchReceipt.digestOrderedValues(
                List.of("C:/engine/llama-server.exe", "-m", "C:/models/model.gguf", "--port", "8080"));
        String reordered = ProviderProcessLaunchReceipt.digestOrderedValues(
                List.of("C:/engine/llama-server.exe", "-m", "C:/models/model.gguf", "8080", "--port"));
        Map<String, String> first = new LinkedHashMap<>();
        first.put("PATH", "one");
        first.put("CUDA_VISIBLE_DEVICES", "0");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("CUDA_VISIBLE_DEVICES", "0");
        second.put("PATH", "one");

        assertTrue(command.matches("[0-9a-f]{64}"));
        assertNotEquals(command, reordered);
        assertEquals(
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(first),
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(second));
        second.put("CUDA_VISIBLE_DEVICES", "1");
        assertNotEquals(
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(first),
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(second));

        Map<String, String> irrelevant = new LinkedHashMap<>(first);
        irrelevant.put("TALOS_UNLISTED_TEST_VALUE", "ignored");
        assertEquals(
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(first),
                ProviderProcessLaunchReceipt.digestEffectiveEnvironment(irrelevant));
        assertEquals(
                Map.of("PATH", "one", "CUDA_VISIBLE_DEVICES", "0"),
                ProviderProcessLaunchReceipt.qualificationEffectiveEnvironment(irrelevant));
    }

    @Test
    void qualificationEnvironmentRejectsEveryInjectionValueAndNormalizedDuplicate() {
        for (String key : List.of(
                "LD_PRELOAD",
                "ld_preload",
                "LD_AUDIT",
                "ld_audit",
                "DYLD_INSERT_LIBRARIES",
                "dyld_insert_libraries",
                "JAVA_TOOL_OPTIONS",
                "java_tool_options",
                "JDK_JAVA_OPTIONS",
                "jdk_java_options",
                "_JAVA_OPTIONS")) {
            for (String value : List.of("payload", " ", "\u00a0")) {
                assertThrows(IllegalArgumentException.class,
                        () -> ProviderProcessLaunchReceipt.digestEffectiveEnvironment(
                                Map.of(key, value)), key + "=" + value);
            }
        }

        Map<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("PATH", "same");
        duplicate.put("Path", "same");
        assertThrows(IllegalArgumentException.class,
                () -> ProviderProcessLaunchReceipt.digestEffectiveEnvironment(duplicate));
    }

    @Test
    void caseSensitiveQualificationEnvironmentCannotActivateMixedCaseNames() {
        ProviderProcessLaunchReceipt.EnvironmentKeySemantics sensitive =
                ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_SENSITIVE;

        for (String key : List.of(
                "path", "Path", "ld_library_path", "cuda_visible_devices")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ProviderProcessLaunchReceipt.qualificationEffectiveEnvironment(
                            Map.of(key, "value"), sensitive), key);
        }
        assertEquals(
                Map.of("PATH", "one", "CUDA_VISIBLE_DEVICES", "0"),
                ProviderProcessLaunchReceipt.qualificationEffectiveEnvironment(
                        Map.of("PATH", "one", "CUDA_VISIBLE_DEVICES", "0"), sensitive));
    }

    @Test
    void caseInsensitiveQualificationEnvironmentCanonicalizesWithoutCollision() {
        ProviderProcessLaunchReceipt.EnvironmentKeySemantics insensitive =
                ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_INSENSITIVE;
        assertEquals(
                Map.of("PATH", "one", "CUDA_VISIBLE_DEVICES", "0"),
                ProviderProcessLaunchReceipt.qualificationEffectiveEnvironment(
                        Map.of("Path", "one", "cuda_visible_devices", "0"), insensitive));

        Map<String, String> duplicate = new LinkedHashMap<>();
        duplicate.put("PATH", "one");
        duplicate.put("Path", "two");
        assertThrows(IllegalArgumentException.class,
                () -> ProviderProcessLaunchReceipt.qualificationEffectiveEnvironment(
                        duplicate, insensitive));
    }

    @Test
    void detectedEnvironmentSemanticsMatchTheDefaultFilesystemPlatform() {
        ProviderProcessLaunchReceipt.EnvironmentKeySemantics detected =
                ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics();
        String separator = java.nio.file.FileSystems.getDefault().getSeparator();

        assertEquals("\\".equals(separator)
                        ? ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_INSENSITIVE
                        : ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_SENSITIVE,
                detected);
    }

    @Test
    void operatingSystemNameSpoofCannotChangeDetectedEnvironmentSemantics() throws Exception {
        ProviderProcessLaunchReceipt.EnvironmentKeySemantics expected =
                ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics();
        Path java = ProcessHandle.current().info().command().map(Path::of).orElseThrow();
        String spoofed = expected
                        == ProviderProcessLaunchReceipt.EnvironmentKeySemantics.CASE_INSENSITIVE
                ? "Linux"
                : "Windows 11";
        Process child = new ProcessBuilder(
                java.toString(),
                "-Dos.name=" + spoofed,
                "-cp",
                System.getProperty("java.class.path"),
                EnvironmentSemanticsReporter.class.getName())
                .redirectErrorStream(true)
                .start();

        assertTrue(child.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(0, child.exitValue());
        assertEquals(expected.name(),
                new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip());
    }

    @Test
    void invalidOrPathBearingIdentityFactsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> receipt(0L, 1_721_600_000_000L, A));
        assertThrows(IllegalArgumentException.class,
                () -> receipt(4812L, 0L, A));
        assertThrows(IllegalArgumentException.class,
                () -> fixture("../external", true, A));
        assertThrows(IllegalArgumentException.class,
                () -> fixture("127.0.0.1", false, A));
        assertThrows(IllegalArgumentException.class,
                () -> fixture("127.0.0.1", true, "not-a-hash"));
    }

    private static ProviderProcessLaunchReceipt receipt(
            long pid,
            long processStartEpochMilli,
            String commandSha256) {
        return new ProviderProcessLaunchReceipt(
                pid,
                processStartEpochMilli,
                1_721_600_000_100L,
                1_721_600_000_200L,
                "0123456789abcdef0123456789abcdef",
                A,
                B,
                C,
                commandSha256,
                ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION,
                E,
                ProviderProcessLaunchReceipt.EnvironmentContract.QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                "127.0.0.1",
                8080,
                true,
                F,
                "qualification-model",
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                D,
                ProviderProcessLaunchReceipt.EndpointOwnershipBoundary
                        .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
                ProviderProcessLaunchReceipt.StableEndpointAssumption
                        .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
                ProviderProcessLaunchReceipt.ContentVerificationBoundary.IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption.STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
    }

    private static ProviderProcessLaunchReceipt fixture(
            String host,
            boolean noListener,
            String commandSha256) {
        return new ProviderProcessLaunchReceipt(
                4812L,
                1_721_600_000_000L,
                1_721_600_000_100L,
                1_721_600_000_200L,
                "0123456789abcdef0123456789abcdef",
                A, B, C, commandSha256,
                ProviderProcessLaunchReceipt.LaunchArgumentSet.VERIFICATION, E,
                ProviderProcessLaunchReceipt.EnvironmentContract.QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                host,
                8080,
                noListener,
                F,
                "qualification-model",
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                D,
                ProviderProcessLaunchReceipt.EndpointOwnershipBoundary
                        .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
                ProviderProcessLaunchReceipt.StableEndpointAssumption
                        .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
                ProviderProcessLaunchReceipt.ContentVerificationBoundary.IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption.STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
    }

    public static final class EnvironmentSemanticsReporter {
        private EnvironmentSemanticsReporter() {}

        public static void main(String[] args) {
            System.out.println(
                    ProviderProcessLaunchReceipt.detectEnvironmentKeySemantics().name());
        }
    }
}
