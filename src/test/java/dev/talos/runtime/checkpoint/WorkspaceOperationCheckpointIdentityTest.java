package dev.talos.runtime.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.runtime.workspace.WorkspaceOperationPlanner;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceOperationCheckpointIdentityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serviceRejectsSuccessfulReceiptWithoutAuthorityBinding(
            @TempDir Path workspace
    ) {
        WorkspaceOperationPlan plan = boundCopyPlan();
        CheckpointService service = new CheckpointService(
                storeReturning(CheckpointCaptureResult.captured("cp-1", 1)));

        CheckpointCaptureResult result = service.captureBeforeOperation(
                workspace, enabledConfig(), plan, "trace", 1);

        assertFalse(result.success());
        assertEquals("FAILED", result.status());
        assertEquals(plan.authorityPlanId(), result.authorityPlanId());
        assertEquals(plan.operationId(), result.operationId());
        assertTrue(result.message().contains("did not retain"), result.message());
    }

    @Test
    void serviceRejectsSuccessfulReceiptBoundToDifferentAuthority(
            @TempDir Path workspace
    ) {
        WorkspaceOperationPlan plan = boundCopyPlan();
        CheckpointCaptureResult mismatched = CheckpointCaptureResult.captured(
                "cp-2",
                1,
                "0".repeat(64),
                "1".repeat(64));
        CheckpointService service = new CheckpointService(
                storeReturning(mismatched));

        CheckpointCaptureResult result = service.captureBeforeOperation(
                workspace, enabledConfig(), plan, "trace", 1);

        assertFalse(result.success());
        assertEquals(plan.authorityPlanId(), result.authorityPlanId());
        assertEquals(plan.operationId(), result.operationId());
    }

    @Test
    void serviceAcceptsOnlyTheExactBoundReceipt(@TempDir Path workspace) {
        WorkspaceOperationPlan plan = boundCopyPlan();
        CheckpointCaptureResult exact = CheckpointCaptureResult.captured(
                "cp-3",
                1,
                plan.authorityPlanId(),
                plan.operationId());
        CheckpointService service = new CheckpointService(storeReturning(exact));

        assertEquals(
                exact,
                service.captureBeforeOperation(
                        workspace, enabledConfig(), plan, "trace", 1));
    }

    @Test
    void disabledCheckpointStillRetainsTheExactAuthorityPair(
            @TempDir Path workspace
    ) {
        WorkspaceOperationPlan plan = boundCopyPlan();
        Config disabled = enabledConfig();
        disabled.data.put("checkpoint", Map.of("enabled", false));
        CheckpointService service = new CheckpointService(
                storeReturning(CheckpointCaptureResult.failure("unused")));

        CheckpointCaptureResult result = service.captureBeforeOperation(
                workspace, disabled, plan, "trace", 1);

        assertTrue(result.success());
        assertTrue(result.skipped());
        assertEquals(plan.authorityPlanId(), result.authorityPlanId());
        assertEquals(plan.operationId(), result.operationId());
    }

    @Test
    void legacyUnboundPlanCannotEnterOperationCheckpointPath(
            @TempDir Path workspace
    ) {
        WorkspaceOperationPlan legacy = WorkspaceOperationPlan.copyPath(
                "source.txt",
                "dest.txt",
                WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                false);
        CheckpointService service = new CheckpointService(
                storeReturning(CheckpointCaptureResult.captured("cp-4", 1)));

        CheckpointCaptureResult result = service.captureBeforeOperation(
                workspace, enabledConfig(), legacy, "trace", 1);

        assertFalse(result.success());
        assertTrue(result.authorityPlanId().isEmpty());
        assertTrue(result.operationId().isEmpty());
    }

    @Test
    void checkpointResultRejectsIncoherentFlagsAndStatus() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckpointCaptureResult(
                        true,
                        false,
                        "cp",
                        "FAILED",
                        "bad",
                        1,
                        "",
                        ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CheckpointCaptureResult(
                        false,
                        false,
                        "cp",
                        "FAILED",
                        "bad",
                        0,
                        "",
                        ""));
    }

    @Test
    void typedCheckpointCrossBindsBothRepresentationsAndRestoresOnlyExactPair(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Path checkpointRoot = temp.resolve("checkpoints");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.txt"), "captured-source");
        WorkspaceOperationPlan plan = boundCopyPlan();
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeOperation(
                workspace, enabledConfig(), plan, "trace", 1);

        assertTrue(capture.success(), capture.message());
        Path checkpointDir = checkpointDirectory(
                checkpointRoot, workspace, capture.checkpointId());
        Map<String, Object> metadata = readJson(
                checkpointDir.resolve("metadata.json"));
        Map<String, Object> manifest = readJson(
                checkpointDir.resolve("manifest.json"));
        assertEquals(2, metadata.get("schemaVersion"));
        assertEquals(2, manifest.get("schemaVersion"));
        for (Map<String, Object> representation
                : java.util.List.of(metadata, manifest)) {
            assertEquals(plan.authorityPlanId(),
                    representation.get("authorityPlanId"));
            assertEquals(plan.operationId(),
                    representation.get("operationId"));
            assertTrue(String.valueOf(representation.get(
                    "authorityBindingSha256")).matches("[0-9a-f]{64}"));
        }
        assertEquals(
                metadata.get("authorityBindingSha256"),
                manifest.get("authorityBindingSha256"));

        Files.writeString(workspace.resolve("source.txt"), "current-source");
        Files.writeString(workspace.resolve("dest.txt"), "current-destination");
        CheckpointRestoreResult restored = service.restore(
                workspace,
                capture.checkpointId(),
                plan.authorityPlanId(),
                plan.operationId());

        assertTrue(restored.success(), restored.message());
        assertEquals(plan.authorityPlanId(), restored.authorityPlanId());
        assertEquals(plan.operationId(), restored.operationId());
        assertEquals("current-source",
                Files.readString(workspace.resolve("source.txt")));
        assertFalse(Files.exists(workspace.resolve("dest.txt")));
    }

    @Test
    void omittedTypedIdentityFailsBeforeFilesystemMutation(
            @TempDir Path temp
    ) throws Exception {
        IdentityFixture fixture = captureFixture(temp, "source.txt", "dest.txt");
        Files.writeString(fixture.workspace().resolve("source.txt"),
                "live-must-survive");
        Path manifestFile = fixture.checkpointDir().resolve("manifest.json");
        Map<String, Object> manifest = readJson(manifestFile);
        manifest.remove("operationId");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                manifestFile.toFile(), manifest);

        CheckpointRestoreResult restored = fixture.service().restore(
                fixture.workspace(),
                fixture.capture().checkpointId(),
                fixture.plan().authorityPlanId(),
                fixture.plan().operationId());

        assertFalse(restored.success());
        assertTrue(restored.message().contains("identity preflight"),
                restored.message());
        assertEquals(0, restored.restoredFiles());
        assertEquals(0, restored.deletedFiles());
        assertEquals("live-must-survive",
                Files.readString(fixture.workspace().resolve("source.txt")));
    }

    @Test
    void mismatchedCheckpointRepresentationsFailBeforeFilesystemMutation(
            @TempDir Path temp
    ) throws Exception {
        IdentityFixture fixture = captureFixture(temp, "source.txt", "dest.txt");
        Files.writeString(fixture.workspace().resolve("source.txt"),
                "live-must-survive");
        Path metadataFile = fixture.checkpointDir().resolve("metadata.json");
        Map<String, Object> metadata = readJson(metadataFile);
        metadata.put("operationId", "f".repeat(64));
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                metadataFile.toFile(), metadata);

        CheckpointRestoreResult restored = fixture.service().restore(
                fixture.workspace(),
                fixture.capture().checkpointId(),
                fixture.plan().authorityPlanId(),
                fixture.plan().operationId());

        assertFalse(restored.success());
        assertTrue(restored.message().contains("disagree"), restored.message());
        assertEquals("live-must-survive",
                Files.readString(fixture.workspace().resolve("source.txt")));
    }

    @Test
    void checkpointFromAnotherOperationCannotBeSubstituted(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Path checkpointRoot = temp.resolve("checkpoints");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.txt"), "source-before");
        Files.writeString(workspace.resolve("other.txt"), "other-before");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(checkpointRoot));
        WorkspaceOperationPlan expected = boundCopyPlan();
        WorkspaceOperationPlan other = WorkspaceOperationPlanner.authorityPlan(
                        TaskContractResolver.resolveFromUserRequest(
                                        "Copy other.txt to other-copy.txt.")
                                .workspaceOperationPlan())
                .orElseThrow();
        CheckpointCaptureResult otherCapture = service.captureBeforeOperation(
                workspace, enabledConfig(), other, "trace-other", 2);
        assertTrue(otherCapture.success(), otherCapture.message());
        Files.writeString(workspace.resolve("other.txt"), "live-must-survive");

        CheckpointRestoreResult restored = service.restore(
                workspace,
                otherCapture.checkpointId(),
                expected.authorityPlanId(),
                expected.operationId());

        assertFalse(restored.success());
        assertTrue(restored.message().contains("does not match"),
                restored.message());
        assertEquals("live-must-survive",
                Files.readString(workspace.resolve("other.txt")));
    }

    @Test
    void physicallySwappedTypedManifestFailsBeforeFilesystemMutation(
            @TempDir Path temp
    ) throws Exception {
        IdentityFixture first = captureFixture(
                temp, "source.txt", "dest.txt");
        Files.writeString(first.workspace().resolve("other.txt"), "other-before");
        WorkspaceOperationPlan secondPlan = WorkspaceOperationPlanner.authorityPlan(
                        TaskContractResolver.resolveFromUserRequest(
                                        "Copy other.txt to other-copy.txt.")
                                .workspaceOperationPlan())
                .orElseThrow();
        CheckpointCaptureResult secondCapture = first.service()
                .captureBeforeOperation(
                        first.workspace(),
                        enabledConfig(),
                        secondPlan,
                        "trace-second",
                        2);
        Path secondDir = checkpointDirectory(
                first.checkpointRoot(),
                first.workspace(),
                secondCapture.checkpointId());
        Files.copy(
                secondDir.resolve("manifest.json"),
                first.checkpointDir().resolve("manifest.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(first.workspace().resolve("source.txt"),
                "live-must-survive");

        CheckpointRestoreResult restored = first.service().restore(
                first.workspace(),
                first.capture().checkpointId(),
                first.plan().authorityPlanId(),
                first.plan().operationId());

        assertFalse(restored.success());
        assertTrue(restored.message().contains("checkpoint id"),
                restored.message());
        assertEquals("live-must-survive",
                Files.readString(first.workspace().resolve("source.txt")));
    }

    @Test
    void legacyUntypedCheckpointRemainsReadable(@TempDir Path temp)
            throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("legacy.txt"), "captured");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                enabledConfig(),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "legacy.txt", "content", "after")),
                "legacy-trace",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("legacy.txt"), "live");

        CheckpointRestoreResult restored = service.restore(
                workspace, capture.checkpointId());

        assertTrue(restored.success(), restored.message());
        assertTrue(restored.authorityPlanId().isEmpty());
        assertTrue(restored.operationId().isEmpty());
        assertEquals("captured",
                Files.readString(workspace.resolve("legacy.txt")));
    }

    @Test
    void manualRestoreAcceptsInternallyCrossBoundTypedCheckpoint(
            @TempDir Path temp
    ) throws Exception {
        IdentityFixture fixture = captureFixture(
                temp, "source.txt", "dest.txt");
        Files.writeString(fixture.workspace().resolve("dest.txt"),
                "created-after-capture");

        CheckpointRestoreResult restored = fixture.service().restore(
                fixture.workspace(), fixture.capture().checkpointId());

        assertTrue(restored.success(), restored.message());
        assertEquals(fixture.plan().authorityPlanId(),
                restored.authorityPlanId());
        assertEquals(fixture.plan().operationId(), restored.operationId());
        assertFalse(Files.exists(fixture.workspace().resolve("dest.txt")));
    }

    private static WorkspaceOperationPlan boundCopyPlan() {
        return WorkspaceOperationPlanner.authorityPlan(
                        TaskContractResolver.resolveFromUserRequest(
                                        "Copy source.txt to dest.txt.")
                                .workspaceOperationPlan())
                .orElseThrow();
    }

    private static IdentityFixture captureFixture(
            Path temp,
            String source,
            String destination
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Path checkpointRoot = temp.resolve("checkpoints");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve(source), "captured-source");
        WorkspaceOperationPlan plan = WorkspaceOperationPlanner.authorityPlan(
                        TaskContractResolver.resolveFromUserRequest(
                                        "Copy " + source + " to "
                                                + destination + ".")
                                .workspaceOperationPlan())
                .orElseThrow();
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(checkpointRoot));
        CheckpointCaptureResult capture = service.captureBeforeOperation(
                workspace, enabledConfig(), plan, "trace", 1);
        assertTrue(capture.success(), capture.message());
        return new IdentityFixture(
                workspace,
                checkpointRoot,
                checkpointDirectory(
                        checkpointRoot, workspace, capture.checkpointId()),
                service,
                plan,
                capture);
    }

    private static Path checkpointDirectory(
            Path checkpointRoot,
            Path workspace,
            String checkpointId
    ) {
        return checkpointRoot
                .resolve(JsonSessionStore.sessionIdFor(
                        workspace.toAbsolutePath().normalize()))
                .resolve("checkpoints")
                .resolve(checkpointId);
    }

    private static Map<String, Object> readJson(Path path) throws Exception {
        return new LinkedHashMap<>(MAPPER.readValue(
                Files.readString(path),
                new TypeReference<Map<String, Object>>() {}));
    }

    private record IdentityFixture(
            Path workspace,
            Path checkpointRoot,
            Path checkpointDir,
            CheckpointService service,
            WorkspaceOperationPlan plan,
            CheckpointCaptureResult capture
    ) {}

    private static Config enabledConfig() {
        Config config = new Config();
        config.data.put("checkpoint", Map.of(
                "enabled", true,
                "fail_closed", true,
                "max_file_bytes", 1_000_000,
                "max_turn_bytes", 2_000_000));
        return config;
    }

    private static CheckpointStore storeReturning(
            CheckpointCaptureResult result
    ) {
        return new CheckpointStore() {
            @Override
            public CheckpointCaptureResult captureBeforeMutation(
                    Path workspace,
                    Config config,
                    ToolCall call,
                    String traceId,
                    int turnNumber
            ) {
                return result;
            }

            @Override
            public CheckpointCaptureResult captureBeforeOperation(
                    Path workspace,
                    Config config,
                    WorkspaceOperationPlan plan,
                    String traceId,
                    int turnNumber
            ) {
                return result;
            }

            @Override
            public CheckpointRestoreResult restore(
                    Path workspace,
                    String checkpointId
            ) {
                return CheckpointRestoreResult.failure(
                        checkpointId,
                        "unused");
            }
        };
    }
}
