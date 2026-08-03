package dev.talos.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.FileBundleCheckpointStore;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.RenamePathTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.DeletePathTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperationTurnProcessorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void approvedMoveUsesBundleCheckpointAndRestoreCoversSourceAndDestination(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.txt"), "source-before");
        Files.writeString(workspace.resolve("dest.txt"), "dest-before");

        CheckpointService checkpoints = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        AtomicReference<String> approvalDescription = new AtomicReference<>();
        AtomicReference<String> approvalDetail = new AtomicReference<>();
        TurnProcessor processor = processor(
                gateApproves(
                        new AtomicInteger(),
                        approvalDescription,
                        approvalDetail),
                checkpoints);
        Config config = config(true);

        String request = "Move source.txt to dest.txt and overwrite it.";
        LocalTurnTraceCapture.begin("trc-workspace-move", "sid", 1,
                "2026-05-05T00:00:00Z", "sid", "auto", "test", "model", request);
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        var authority = TurnTaskContractCapture.workspaceOperationPlan();
        String operationId = authority.authorizedOperations()
                .getFirst().operationId();

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.move_path", Map.of(
                        "from", "source.txt",
                        "to", "dest.txt",
                        "overwrite", "true")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("source.txt")));
        assertEquals("source-before", Files.readString(workspace.resolve("dest.txt")));
        assertEquals("workspace operation: move_path", approvalDescription.get());
        assertAll(
                () -> assertTrue(approvalDetail.get().contains(
                        "source: source.txt")),
                () -> assertTrue(approvalDetail.get().contains(
                        "destination: dest.txt")),
                () -> assertTrue(approvalDetail.get().contains(
                        "overwrite: overwrite_explicit")),
                () -> assertTrue(approvalDetail.get().contains(
                        "recursive: not_applicable")),
                () -> assertTrue(approvalDetail.get().contains(
                        "parent: require_existing_parent")),
                () -> assertTrue(approvalDetail.get().contains(
                        "authority plan: " + authority.planId())),
                () -> assertTrue(approvalDetail.get().contains(
                        "operation: " + operationId)));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("CREATED", trace.checkpoint().status());
        assertTrue(trace.events().stream().anyMatch(event ->
                "CHECKPOINT_CREATED".equals(event.type())
                        && authority.planId().equals(
                                event.data().get("authorityPlanId"))
                        && operationId.equals(
                                event.data().get("operationId"))));
        List<String> lifecycle = trace.events().stream()
                .filter(event -> event.type().startsWith(
                        "WORKSPACE_OPERATION_"))
                .map(dev.talos.runtime.trace.TurnTraceEvent::type)
                .toList();
        assertTrue(lifecycle.contains(
                "WORKSPACE_OPERATION_APPROVAL_GRANTED"));
        assertTrue(lifecycle.contains(
                "WORKSPACE_OPERATION_CHECKPOINT_CREATED"));
        assertTrue(lifecycle.contains(
                "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED"));
        assertTrue(lifecycle.contains(
                "WORKSPACE_OPERATION_OBLIGATION_CONSUMED"));

        Path metadata;
        try (var files = Files.walk(temp.resolve("checkpoints"))) {
            metadata = files.filter(path -> "metadata.json".equals(
                            path.getFileName().toString()))
                    .findFirst()
                    .orElseThrow();
        }
        JsonNode metadataJson = JSON.readTree(Files.readString(metadata));
        assertEquals(authority.planId(),
                metadataJson.path("authorityPlanId").asText());
        assertEquals(operationId,
                metadataJson.path("operationId").asText());

        CheckpointRestoreResult restore = checkpoints.restore(workspace, trace.checkpoint().checkpointId());
        assertTrue(restore.success(), restore.message());
        assertEquals("source-before", Files.readString(workspace.resolve("source.txt")));
        assertEquals("dest-before", Files.readString(workspace.resolve("dest.txt")));
    }

    @Test
    void everyOperationFamilyUsesRoleCorrectTypedApprovalAndConsumesThatAuthority(
            @TempDir Path temp
    ) throws Exception {
        List<OperationFixture> fixtures = List.of(
                new OperationFixture(
                        "copy",
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt",
                                "to", "out/copied.txt")),
                        List.of("source: source.txt", "destination: out/copied.txt"),
                        "fail_if_exists", "non_recursive", "require_existing_parent"),
                new OperationFixture(
                        "move",
                        "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of(
                                "from", "source.txt",
                                "to", "out/moved.txt")),
                        List.of("source: source.txt", "destination: out/moved.txt"),
                        "fail_if_exists", "not_applicable", "require_existing_parent"),
                new OperationFixture(
                        "rename",
                        "Rename source.txt to renamed.txt.",
                        new ToolCall("talos.rename_path", Map.of(
                                "path", "source.txt",
                                "new_name", "renamed.txt")),
                        List.of("source: source.txt", "destination: renamed.txt"),
                        "fail_if_exists", "not_applicable", "require_existing_parent"),
                new OperationFixture(
                        "mkdir",
                        "Create a directory named assets.",
                        new ToolCall("talos.mkdir", Map.of("path", "assets")),
                        List.of("target: assets"),
                        "not_applicable", "not_applicable", "require_existing_parent"),
                new OperationFixture(
                        "delete_path",
                        "Delete source.txt.",
                        new ToolCall("talos.delete_path", Map.of("path", "source.txt")),
                        List.of("target: source.txt"),
                        "not_applicable", "non_recursive", "not_applicable"));

        int ordinal = 0;
        for (OperationFixture fixture : fixtures) {
            Path workspace = temp.resolve("workspace-" + ordinal++);
            Files.createDirectories(workspace.resolve("out"));
            Files.writeString(workspace.resolve("source.txt"), "source-before");
            AtomicInteger approvals = new AtomicInteger();
            AtomicReference<String> description = new AtomicReference<>();
            AtomicReference<String> detail = new AtomicReference<>();
            Config config = config(false);
            TurnProcessor processor = processorForAllWorkspaceTools(
                    gateApproves(approvals, description, detail),
                    new CheckpointService(new FileBundleCheckpointStore(
                            temp.resolve("checkpoints-" + ordinal))));

            LocalTurnTraceCapture.begin(
                    "trc-operation-" + ordinal,
                    "sid",
                    ordinal,
                    "2026-08-02T00:00:00Z",
                    "sid",
                    "auto",
                    "test",
                    "model",
                    fixture.request());
            TurnUserRequestCapture.set(fixture.request());
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromUserRequest(
                            fixture.request()));
            var authority = TurnTaskContractCapture.workspaceOperationPlan();
            String operationId = authority.authorizedOperations()
                    .getFirst().operationId();

            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    fixture.call(),
                    context(workspace, config));

            assertTrue(result.success(), fixture.name() + ": " + result.errorMessage());
            assertEquals(1, approvals.get(), fixture.name());
            assertEquals("workspace operation: "
                            + fixture.call().toolName().substring("talos.".length()),
                    description.get(), fixture.name());
            for (String expected : fixture.roleDetails()) {
                assertTrue(detail.get().contains(expected),
                        fixture.name() + " detail=" + detail.get());
            }
            assertFalse(detail.get().contains("destination/target:"),
                    fixture.name() + " detail=" + detail.get());
            assertTrue(detail.get().contains(
                    "overwrite: " + fixture.overwrite()), detail.get());
            assertTrue(detail.get().contains(
                    "recursive: " + fixture.recursive()), detail.get());
            assertTrue(detail.get().contains(
                    "parent: " + fixture.parent()), detail.get());
            assertTrue(detail.get().contains(
                    "authority plan: " + authority.planId()), detail.get());
            assertTrue(detail.get().contains(
                    "operation: " + operationId), detail.get());

            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            List<String> lifecycle = trace.events().stream()
                    .filter(event -> event.type().startsWith(
                            "WORKSPACE_OPERATION_"))
                    .map(dev.talos.runtime.trace.TurnTraceEvent::type)
                    .toList();
            assertTrue(lifecycle.contains(
                    "WORKSPACE_OPERATION_APPROVAL_GRANTED"), fixture.name());
            assertTrue(lifecycle.contains(
                    "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED"), fixture.name());
            assertTrue(lifecycle.contains(
                    "WORKSPACE_OPERATION_OBLIGATION_CONSUMED"), fixture.name());
            assertOperationEffect(workspace, fixture.name());
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void everyTypedWorkspaceOperationUsesOnceOnlyApprovalDespiteSessionMemory(
            @TempDir Path temp
    ) throws Exception {
        AtomicInteger fullApprovals = new AtomicInteger();
        AtomicInteger onceApprovals = new AtomicInteger();
        ApprovalGate gate = new ApprovalGate() {
            @Override
            public boolean approve(String description, String detail) {
                throw new AssertionError(
                        "workspace operation must use the explicit once-only surface");
            }

            @Override
            public ApprovalResponse approveFull(
                    String description,
                    String detail
            ) {
                fullApprovals.incrementAndGet();
                return ApprovalResponse.APPROVED_REMEMBER;
            }

            @Override
            public ApprovalResponse approveOnce(
                    String description,
                    String detail
            ) {
                onceApprovals.incrementAndGet();
                return ApprovalResponse.APPROVED;
            }
        };
        SessionApprovalPolicy sessionPolicy = new SessionApprovalPolicy();
        sessionPolicy.rememberApproval(
                temp,
                new ToolCall("talos.write_file", Map.of(
                        "path", "ordinary.txt",
                        "content", "ordinary")),
                dev.talos.tools.ToolRiskLevel.WRITE);
        assertTrue(sessionPolicy.rememberInWorkspaceWritesEnabled());

        List<OperationFixture> fixtures = List.of(
                new OperationFixture(
                        "copy",
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt",
                                "to", "out/copied.txt")),
                        List.of(), "", "", ""),
                new OperationFixture(
                        "move",
                        "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of(
                                "from", "source.txt",
                                "to", "out/moved.txt")),
                        List.of(), "", "", ""),
                new OperationFixture(
                        "rename",
                        "Rename source.txt to renamed.txt.",
                        new ToolCall("talos.rename_path", Map.of(
                                "path", "source.txt",
                                "new_name", "renamed.txt")),
                        List.of(), "", "", ""),
                new OperationFixture(
                        "mkdir",
                        "Create a directory named assets.",
                        new ToolCall("talos.mkdir", Map.of("path", "assets")),
                        List.of(), "", "", ""),
                new OperationFixture(
                        "delete_path",
                        "Delete source.txt.",
                        new ToolCall("talos.delete_path", Map.of(
                                "path", "source.txt")),
                        List.of(), "", "", ""));

        int ordinal = 0;
        for (OperationFixture fixture : fixtures) {
            Path workspace = temp.resolve("once-only-" + ordinal++);
            Files.createDirectories(workspace.resolve("out"));
            Files.writeString(workspace.resolve("source.txt"), "source");
            Config config = config(false);
            ToolRegistry registry = new ToolRegistry();
            registry.register(new CopyPathTool());
            registry.register(new MovePathTool());
            registry.register(new RenamePathTool());
            registry.register(new MakeDirectoryTool());
            registry.register(new DeletePathTool());
            TurnProcessor processor = new TurnProcessor(
                    ModeController.defaultController(),
                    gate,
                    registry,
                    sessionPolicy,
                    new CheckpointService(new FileBundleCheckpointStore(
                            temp.resolve("once-only-checkpoints-" + ordinal))));
            TurnUserRequestCapture.set(fixture.request());
            TurnTaskContractCapture.set(TaskContractResolver
                    .resolveFromUserRequest(fixture.request()));

            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    fixture.call(),
                    context(workspace, config));

            assertTrue(result.success(), fixture.name() + ": "
                    + result.errorMessage());
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
        }

        assertEquals(5, onceApprovals.get());
        assertEquals(0, fullApprovals.get(),
                "typed operations must never expose the session-remember surface");
    }

    @Test
    void everyOperationFamilyUserDenialStopsBeforeCheckpointExecutionAndConsumption(
            @TempDir Path temp
    ) throws Exception {
        int ordinal = 0;
        for (OperationFixture fixture : basicOperationFixtures()) {
            Path workspace = temp.resolve("denied-" + ordinal);
            Files.createDirectories(workspace.resolve("out"));
            Files.writeString(workspace.resolve("source.txt"), "source-before");
            AtomicInteger approvals = new AtomicInteger();
            ApprovalGate gate = (description, detail) -> {
                approvals.incrementAndGet();
                return false;
            };
            CheckpointService checkpoints = new CheckpointService(
                    new FileBundleCheckpointStore(temp.resolve(
                            "denied-checkpoints-" + ordinal)));
            TurnProcessor processor = processorForAllWorkspaceTools(gate, checkpoints);
            Config config = config(true);

            LocalTurnTraceCapture.begin(
                    "trc-user-denied-" + ordinal,
                    "sid",
                    ordinal + 1,
                    "2026-08-02T00:00:00Z",
                    "sid",
                    "auto",
                    "test",
                    "model",
                    fixture.request());
            TurnUserRequestCapture.set(fixture.request());
            TurnTaskContractCapture.set(TaskContractResolver
                    .resolveFromUserRequest(fixture.request()));

            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    fixture.call(),
                    context(workspace, config));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertFalse(result.success(), fixture.name());
            assertEquals(dev.talos.tools.ToolError.DENIED,
                    result.error().code(), fixture.name());
            assertEquals(1, approvals.get(), fixture.name());
            assertEquals(1, lifecycleCount(
                    trace, "WORKSPACE_OPERATION_APPROVAL_REQUIRED"),
                    fixture.name());
            assertEquals(1, lifecycleCount(
                    trace, "WORKSPACE_OPERATION_APPROVAL_DENIED"),
                    fixture.name());
            assertEquals(0, lifecycleCount(
                    trace, "WORKSPACE_OPERATION_CHECKPOINT_CREATED"),
                    fixture.name());
            assertEquals(0, lifecycleCount(
                    trace, "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED"),
                    fixture.name());
            assertEquals(0, lifecycleCount(
                    trace, "WORKSPACE_OPERATION_OBLIGATION_CONSUMED"),
                    fixture.name());
            assertInitialOperationWorkspace(workspace, fixture.name());
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
            ordinal++;
        }
    }

    @Test
    void everyOperationFamilyCheckpointRestoresTheExactPreMutationWorkspace(
            @TempDir Path temp
    ) throws Exception {
        int ordinal = 0;
        for (OperationFixture fixture : basicOperationFixtures()) {
            Path workspace = temp.resolve("restore-" + ordinal);
            Files.createDirectories(workspace.resolve("out"));
            Files.writeString(workspace.resolve("source.txt"), "source-before");
            CheckpointService checkpoints = new CheckpointService(
                    new FileBundleCheckpointStore(temp.resolve(
                            "restore-checkpoints-" + ordinal)));
            TurnProcessor processor = processorForAllWorkspaceTools(
                    gateApproves(), checkpoints);
            Config config = config(true);

            LocalTurnTraceCapture.begin(
                    "trc-restore-" + ordinal,
                    "sid",
                    ordinal + 1,
                    "2026-08-02T00:00:00Z",
                    "sid",
                    "auto",
                    "test",
                    "model",
                    fixture.request());
            TurnUserRequestCapture.set(fixture.request());
            TurnTaskContractCapture.set(TaskContractResolver
                    .resolveFromUserRequest(fixture.request()));

            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    fixture.call(),
                    context(workspace, config));
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertTrue(result.success(), fixture.name() + ": "
                    + result.errorMessage());
            assertEquals("CREATED", trace.checkpoint().status(), fixture.name());
            assertOperationEffect(workspace, fixture.name());
            CheckpointRestoreResult restore = checkpoints.restore(
                    workspace, trace.checkpoint().checkpointId());
            assertTrue(restore.success(), fixture.name() + ": " + restore.message());
            assertInitialOperationWorkspace(workspace, fixture.name());
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
            ordinal++;
        }
    }

    @Test
    void configAllowAndDenyPreserveApprovalTruthAcrossEveryOperationFamily(
            @TempDir Path temp
    ) throws Exception {
        int ordinal = 0;
        for (OperationFixture fixture : basicOperationFixtures()) {
            Path allowWorkspace = temp.resolve("config-allow-" + ordinal);
            Files.createDirectories(allowWorkspace.resolve("out"));
            Files.writeString(
                    allowWorkspace.resolve("source.txt"), "source-before");
            Config allow = config(false);
            allow.data.put("permissions", Map.of("rules", List.of(Map.of(
                    "effect", "allow",
                    "tools", List.of(fixture.call().toolName()),
                    "phases", List.of("APPLY"),
                    "paths", permissionPaths(fixture.name()),
                    "reason", "typed config allow"))));
            AtomicInteger allowGateCalls = new AtomicInteger();
            TurnProcessor allowProcessor = processorForAllWorkspaceTools(
                    gateApproves(allowGateCalls),
                    new CheckpointService(new FileBundleCheckpointStore(
                            temp.resolve("allow-checkpoints-" + ordinal))));
            LocalTurnTraceCapture.begin(
                    "trc-config-allow-" + ordinal,
                    "sid",
                    ordinal + 1,
                    "2026-08-02T00:00:00Z",
                    "sid",
                    "auto",
                    "test",
                    "model",
                    fixture.request());
            TurnUserRequestCapture.set(fixture.request());
            TurnTaskContractCapture.set(TaskContractResolver
                    .resolveFromUserRequest(fixture.request()));
            ToolResult allowed = allowProcessor.executeTool(
                    new Session(allowWorkspace, allow),
                    fixture.call(),
                    context(allowWorkspace, allow));
            LocalTurnTrace allowTrace = LocalTurnTraceCapture.complete();

            assertTrue(allowed.success(), fixture.name() + ": "
                    + allowed.errorMessage());
            assertEquals(0, allowGateCalls.get(), fixture.name());
            assertEquals(0, lifecycleCount(
                    allowTrace, "WORKSPACE_OPERATION_APPROVAL_REQUIRED"),
                    fixture.name());
            assertEquals(1, lifecycleCount(
                    allowTrace, "WORKSPACE_OPERATION_APPROVAL_GRANTED"),
                    fixture.name());
            assertOperationEffect(allowWorkspace, fixture.name());

            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
            Path denyWorkspace = temp.resolve("config-deny-" + ordinal);
            Files.createDirectories(denyWorkspace.resolve("out"));
            Files.writeString(
                    denyWorkspace.resolve("source.txt"), "source-before");
            Config deny = config(false);
            deny.data.put("permissions", Map.of("rules", List.of(Map.of(
                    "effect", "deny",
                    "tools", List.of(fixture.call().toolName()),
                    "phases", List.of("APPLY"),
                    "paths", permissionPaths(fixture.name()),
                    "reason", "typed config deny"))));
            AtomicInteger denyGateCalls = new AtomicInteger();
            TurnProcessor denyProcessor = processorForAllWorkspaceTools(
                    gateApproves(denyGateCalls),
                    new CheckpointService(new FileBundleCheckpointStore(
                            temp.resolve("deny-checkpoints-" + ordinal))));
            LocalTurnTraceCapture.begin(
                    "trc-config-deny-" + ordinal,
                    "sid",
                    ordinal + 101,
                    "2026-08-02T00:01:00Z",
                    "sid",
                    "auto",
                    "test",
                    "model",
                    fixture.request());
            TurnUserRequestCapture.set(fixture.request());
            TurnTaskContractCapture.set(TaskContractResolver
                    .resolveFromUserRequest(fixture.request()));
            ToolResult denied = denyProcessor.executeTool(
                    new Session(denyWorkspace, deny),
                    fixture.call(),
                    context(denyWorkspace, deny));
            LocalTurnTrace denyTrace = LocalTurnTraceCapture.complete();

            assertFalse(denied.success(), fixture.name());
            assertEquals(dev.talos.tools.ToolError.DENIED,
                    denied.error().code(), fixture.name());
            assertEquals(0, denyGateCalls.get(), fixture.name());
            assertEquals(0, lifecycleCount(
                    denyTrace, "WORKSPACE_OPERATION_APPROVAL_REQUIRED"),
                    fixture.name());
            assertEquals(1, lifecycleCount(
                    denyTrace, "WORKSPACE_OPERATION_APPROVAL_DENIED"),
                    fixture.name());
            assertInitialOperationWorkspace(denyWorkspace, fixture.name());
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
            ordinal++;
        }
    }

    @Test
    void typedMkdirRejectsAnExistingDirectoryBeforeApprovalOrConsumption(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("assets"));
        AtomicInteger approvals = new AtomicInteger();
        Config config = config(false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MakeDirectoryTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                gateApproves(approvals),
                registry,
                ApprovalPolicy.ALWAYS_ASK,
                new CheckpointService(new FileBundleCheckpointStore(
                        temp.resolve("checkpoints"))));
        String request = "Create a directory named assets.";
        LocalTurnTraceCapture.begin(
                "trc-existing-mkdir", "sid", 1,
                "2026-08-02T00:00:00Z", "sid", "auto", "test", "model",
                request);
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver
                .resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.mkdir", Map.of("path", "assets")),
                context(workspace, config));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertFalse(result.success());
        assertEquals(0, approvals.get());
        assertEquals(0, lifecycleCount(
                trace, "WORKSPACE_OPERATION_APPROVAL_REQUIRED"));
        assertEquals(0, lifecycleCount(
                trace, "WORKSPACE_OPERATION_OBLIGATION_CONSUMED"));
        assertTrue(trace.checkpoint().status().isBlank());
        assertTrue(Files.isDirectory(workspace.resolve("assets")));
    }

    @Test
    void activeApprovalPolicyDenyBlocksExactWorkspaceOperationBeforeGateOrExecution(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("out"));
        Files.writeString(workspace.resolve("source.txt"), "source-before");
        AtomicInteger approvals = new AtomicInteger();
        ApprovalPolicy deny = new ApprovalPolicy() {
            @Override
            public Decision decide(
                    Path ignoredWorkspace,
                    ToolCall ignoredCall,
                    dev.talos.tools.ToolRiskLevel ignoredRisk
            ) {
                return Decision.DENY;
            }

            @Override
            public void rememberApproval(
                    Path ignoredWorkspace,
                    ToolCall ignoredCall,
                    dev.talos.tools.ToolRiskLevel ignoredRisk
            ) {
                fail("denied operation must not be remembered");
            }
        };
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CopyPathTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                gateApproves(approvals),
                registry,
                deny,
                new CheckpointService(new FileBundleCheckpointStore(
                        temp.resolve("checkpoints"))));
        Config config = config(true);
        String request = "Copy source.txt to out/copied.txt.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.copy_path", Map.of(
                        "from", "source.txt", "to", "out/copied.txt")),
                context(workspace, config));

        assertFalse(result.success());
        assertEquals(dev.talos.tools.ToolError.DENIED, result.error().code());
        assertEquals(0, approvals.get());
        assertEquals("source-before", Files.readString(
                workspace.resolve("source.txt")));
        assertFalse(Files.exists(workspace.resolve("out/copied.txt")));
    }

    @Test
    void typedTwoPathOperationRejectsExistingSameFileAliasBeforeApproval(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Path source = workspace.resolve("same.txt");
        Files.writeString(source, "source-before");
        String aliasName;
        if (System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win")) {
            aliasName = "SAME.txt";
        } else {
            aliasName = "same-alias.txt";
            Files.createLink(workspace.resolve(aliasName), source);
        }
        AtomicInteger approvals = new AtomicInteger();
        Config config = config(true);
        TurnProcessor processor = processorForAllWorkspaceTools(
                gateApproves(approvals),
                new CheckpointService(new FileBundleCheckpointStore(
                        temp.resolve("checkpoints"))));
        String request = "Copy same.txt to " + aliasName
                + " and overwrite it.";
        LocalTurnTraceCapture.begin(
                "trc-same-file-alias", "sid", 1,
                "2026-08-02T00:00:00Z", "sid", "auto", "test", "model",
                request);
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.copy_path", Map.of(
                        "from", "same.txt",
                        "to", aliasName,
                        "overwrite", "true")),
                context(workspace, config));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertFalse(result.success());
        assertEquals(0, approvals.get());
        assertEquals("source-before", Files.readString(source));
        assertEquals(0, lifecycleCount(
                trace, "WORKSPACE_OPERATION_APPROVAL_REQUIRED"));
        assertEquals(0, lifecycleCount(
                trace, "WORKSPACE_OPERATION_OBLIGATION_CONSUMED"));
        assertTrue(trace.checkpoint().status().isBlank());
    }

    @Test
    void mismatchedOrParentCreatingCallsFailBeforeApprovalWithoutMutation(
            @TempDir Path temp
    ) throws Exception {
        List<MismatchFixture> fixtures = List.of(
                new MismatchFixture(
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.move_path", Map.of(
                                "from", "source.txt", "to", "out/copied.txt"))),
                new MismatchFixture(
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "out/copied.txt", "to", "source.txt"))),
                new MismatchFixture(
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "other.txt", "to", "out/copied.txt"))),
                new MismatchFixture(
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt", "to", "out/other.txt"))),
                new MismatchFixture(
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt", "to", "out/copied.txt",
                                "unexpected", "true"))),
                new MismatchFixture(
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt", "to", "out/copied.txt",
                                "overwrite", "true"))),
                new MismatchFixture(
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt", "to", "out/copied.txt",
                                "recursive", "true"))),
                new MismatchFixture(
                        "Copy source.txt to missing/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt", "to", "missing/copied.txt"))));

        int ordinal = 0;
        for (MismatchFixture fixture : fixtures) {
            Path workspace = temp.resolve("mismatch-" + ordinal++);
            Files.createDirectories(workspace.resolve("out"));
            Files.writeString(workspace.resolve("source.txt"), "source-before");
            AtomicInteger approvals = new AtomicInteger();
            Config config = config(false);
            TurnProcessor processor = processorForAllWorkspaceTools(
                    gateApproves(approvals),
                    new CheckpointService(new FileBundleCheckpointStore(
                            temp.resolve("mismatch-checkpoints-" + ordinal))));
            TurnUserRequestCapture.set(fixture.request());
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromUserRequest(
                            fixture.request()));

            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    fixture.call(),
                    context(workspace, config));

            assertFalse(result.success(), fixture.toString());
            assertEquals(0, approvals.get(), fixture.toString());
            assertEquals("source-before",
                    Files.readString(workspace.resolve("source.txt")),
                    fixture.toString());
            assertFalse(Files.exists(workspace.resolve("out/copied.txt")),
                    fixture.toString());
            assertFalse(Files.exists(workspace.resolve("out/other.txt")),
                    fixture.toString());
            assertFalse(Files.exists(workspace.resolve("missing")),
                    fixture.toString());
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void legacyContractWithoutTypedResolutionCannotAuthorizeAnOperation(
            @TempDir Path workspace
    ) throws Exception {
        Files.writeString(workspace.resolve("source.txt"), "source-before");
        AtomicInteger approvals = new AtomicInteger();
        Config config = config(false);
        TurnProcessor processor = processorForAllWorkspaceTools(
                gateApproves(approvals),
                new CheckpointService(new FileBundleCheckpointStore(
                        workspace.resolve(".checkpoints"))));
        String request = "Delete source.txt.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.fromUserRequest(request));

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.delete_path", Map.of("path", "source.txt")),
                context(workspace, config));

        assertFalse(result.success());
        assertEquals(0, approvals.get());
        assertEquals("source-before", Files.readString(
                workspace.resolve("source.txt")));
    }

    @Test
    void protectedDestinationMoveIsDeniedBeforeApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("public.txt"), "public");
        AtomicInteger approvals = new AtomicInteger();
        TurnProcessor processor = processor(gateApproves(approvals),
                new CheckpointService(new FileBundleCheckpointStore(workspace.resolve(".checkpoints"))));
        Config config = config(true);

        String request = "Move public.txt to .env";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.move_path", Map.of("from", "public.txt", "to", ".env")),
                context(workspace, config));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("protected path"), result.errorMessage());
        assertEquals(0, approvals.get(), "protected mutation must be denied before approval");
        assertTrue(Files.exists(workspace.resolve("public.txt")));
        assertFalse(Files.exists(workspace.resolve(".env")));
    }

    @Test
    void auditRecordsWorkspaceOperationDestinationPathsUnderSeparateAuthorities(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("workspace-notes"));
        Files.createDirectories(workspace.resolve("archive"));
        Files.writeString(workspace.resolve("README.md"), "# Fixture\n");
        Config config = config(false);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CopyPathTool());
        registry.register(new MovePathTool());
        registry.register(new RenamePathTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                gateApproves(),
                registry,
                ApprovalPolicy.ALWAYS_ASK,
                new CheckpointService(new FileBundleCheckpointStore(temp.resolve("checkpoints"))));
        Context ctx = context(workspace, config);

        String copyPath = executeAuditedOperation(
                processor,
                workspace,
                config,
                ctx,
                "Copy README.md to workspace-notes/readme-copy.md.",
                new ToolCall("talos.copy_path", Map.of(
                        "from", "README.md",
                        "to", "workspace-notes/readme-copy.md")));
        String movePath = executeAuditedOperation(
                processor,
                workspace,
                config,
                ctx,
                "Move workspace-notes/readme-copy.md to archive/readme-copy.md.",
                new ToolCall("talos.move_path", Map.of(
                        "from", "workspace-notes/readme-copy.md",
                        "to", "archive/readme-copy.md")));
        String renamePath = executeAuditedOperation(
                processor,
                workspace,
                config,
                ctx,
                "Rename archive/readme-copy.md to readme-renamed.md.",
                new ToolCall("talos.rename_path", Map.of(
                        "path", "archive/readme-copy.md",
                        "new_name", "readme-renamed.md")));

        assertEquals(
                List.of(
                        "workspace-notes/readme-copy.md",
                        "archive/readme-copy.md",
                        "archive/readme-renamed.md"),
                List.of(copyPath, movePath, renamePath));
    }

    private static String executeAuditedOperation(
            TurnProcessor processor,
            Path workspace,
            Config config,
            Context context,
            String request,
            ToolCall call
    ) {
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        TurnAuditCapture.begin();
        try {
            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    call,
                    context);
            TurnAudit audit = TurnAuditCapture.end();
            assertTrue(result.success(), result.errorMessage());
            assertEquals(1, audit.toolCalls().size());
            return audit.toolCalls().getFirst().pathHint();
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        }
    }

    private static TurnProcessor processor(ApprovalGate gate, CheckpointService checkpointService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MovePathTool());
        return new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry,
                ApprovalPolicy.ALWAYS_ASK,
                checkpointService);
    }

    private static ApprovalGate gateApproves() {
        return gateApproves(new AtomicInteger());
    }

    private static ApprovalGate gateApproves(AtomicInteger calls) {
        return gateApproves(
                calls,
                new AtomicReference<>(),
                new AtomicReference<>());
    }

    private static TurnProcessor processorForAllWorkspaceTools(
            ApprovalGate gate,
            CheckpointService checkpointService
    ) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CopyPathTool());
        registry.register(new MovePathTool());
        registry.register(new RenamePathTool());
        registry.register(new MakeDirectoryTool());
        registry.register(new DeletePathTool());
        return new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry,
                ApprovalPolicy.ALWAYS_ASK,
                checkpointService);
    }

    private static void assertOperationEffect(Path workspace, String name)
            throws Exception {
        switch (name) {
            case "copy" -> {
                assertEquals("source-before", Files.readString(
                        workspace.resolve("source.txt")));
                assertEquals("source-before", Files.readString(
                        workspace.resolve("out/copied.txt")));
            }
            case "move" -> {
                assertFalse(Files.exists(workspace.resolve("source.txt")));
                assertEquals("source-before", Files.readString(
                        workspace.resolve("out/moved.txt")));
            }
            case "rename" -> {
                assertFalse(Files.exists(workspace.resolve("source.txt")));
                assertEquals("source-before", Files.readString(
                        workspace.resolve("renamed.txt")));
            }
            case "mkdir" -> assertTrue(Files.isDirectory(
                    workspace.resolve("assets")));
            case "delete_path" -> assertFalse(Files.exists(
                    workspace.resolve("source.txt")));
            default -> fail("unknown operation fixture " + name);
        }
    }

    private static void assertInitialOperationWorkspace(
            Path workspace,
            String name
    ) throws Exception {
        assertEquals("source-before", Files.readString(
                workspace.resolve("source.txt")), name);
        assertFalse(Files.exists(workspace.resolve("out/copied.txt")), name);
        assertFalse(Files.exists(workspace.resolve("out/moved.txt")), name);
        assertFalse(Files.exists(workspace.resolve("renamed.txt")), name);
        assertFalse(Files.exists(workspace.resolve("assets")), name);
    }

    private static List<OperationFixture> basicOperationFixtures() {
        return List.of(
                new OperationFixture(
                        "copy",
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt",
                                "to", "out/copied.txt")),
                        List.of(), "", "", ""),
                new OperationFixture(
                        "move",
                        "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of(
                                "from", "source.txt",
                                "to", "out/moved.txt")),
                        List.of(), "", "", ""),
                new OperationFixture(
                        "rename",
                        "Rename source.txt to renamed.txt.",
                        new ToolCall("talos.rename_path", Map.of(
                                "path", "source.txt",
                                "new_name", "renamed.txt")),
                        List.of(), "", "", ""),
                new OperationFixture(
                        "mkdir",
                        "Create a directory named assets.",
                        new ToolCall("talos.mkdir", Map.of("path", "assets")),
                        List.of(), "", "", ""),
                new OperationFixture(
                        "delete_path",
                        "Delete source.txt.",
                        new ToolCall("talos.delete_path", Map.of(
                                "path", "source.txt")),
                        List.of(), "", "", ""));
    }

    private static List<String> permissionPaths(String operationName) {
        return switch (operationName) {
            case "copy", "move" -> List.of("source.txt", "out/**");
            case "rename" -> List.of("source.txt", "renamed.txt");
            case "mkdir" -> List.of("assets");
            case "delete_path" -> List.of("source.txt");
            default -> throw new IllegalArgumentException(
                    "unknown operation fixture " + operationName);
        };
    }

    private record OperationFixture(
            String name,
            String request,
            ToolCall call,
            List<String> roleDetails,
            String overwrite,
            String recursive,
            String parent
    ) {}

    private record MismatchFixture(String request, ToolCall call) {}

    private static long lifecycleCount(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(event -> type.equals(event.type()))
                .count();
    }

    private static ApprovalGate gateApproves(
            AtomicInteger calls,
            AtomicReference<String> descriptionRef,
            AtomicReference<String> detailRef
    ) {
        return new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                calls.incrementAndGet();
                descriptionRef.set(description);
                detailRef.set(detail);
                return ApprovalResponse.APPROVED;
            }
        };
    }

    private static Context context(Path workspace, Config config) {
        return Context.builder(config)
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
    }

    private static Config config(boolean enabled) {
        Config config = new Config();
        config.data.put("checkpoint", Map.of(
                "enabled", enabled,
                "fail_closed", true,
                "max_file_bytes", 1_000_000,
                "max_turn_bytes", 2_000_000));
        return config;
    }
}
