package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.*;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.spi.types.ToolSpec;
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

class TurnProcessorPermissionPolicyTest {

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void explicitDenyRuleBlocksBeforeApprovalOrExecution(@TempDir Path workspace) {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        Config config = configWithRules(List.of(
                rule("deny", List.of("test.write"), List.of("WRITE"), List.of("APPLY"), List.of("blocked.txt"))
        ));
        TurnProcessor processor = processor(config, gateApproves(gateCalls), new CountingWriteTool(executions));

        TurnUserRequestCapture.set("write blocked.txt");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("test.write", Map.of("path", "blocked.txt", "content", "x")),
                context(workspace, config));

        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
        assertTrue(result.errorMessage().contains("Permission policy denied"), result.errorMessage());
        assertEquals(0, gateCalls.get(), "deny must not ask the user to approve");
        assertEquals(0, executions.get(), "deny must not execute the tool");
    }

    @Test
    void protectedMutationIsDeniedBeforeApproval(@TempDir Path workspace) {
        AtomicInteger gateCalls = new AtomicInteger();
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gateApproves(gateCalls), registry);

        TurnUserRequestCapture.set("write .env with SECRET=1");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.write_file", Map.of("path", ".env", "content", "SECRET=1")),
                context(workspace, config));

        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
        assertTrue(result.errorMessage().contains("protected path"), result.errorMessage());
        assertEquals(0, gateCalls.get(), "protected mutation denial must happen before approval");
        assertFalse(Files.exists(workspace.resolve(".env")));
    }

    @Test
    void protectedReadAsksBeforeReading(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve(".env"), "SECRET=1");
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicReference<String> approvalDescription = new AtomicReference<>();
        AtomicReference<String> approvalDetail = new AtomicReference<>();
        Config config = new Config(null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), (description, detail) -> {
                    gateCalls.incrementAndGet();
                    approvalDescription.set(description);
                    approvalDetail.set(detail);
                    return true;
                }, registry);

        TurnUserRequestCapture.set("read .env");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.read_file", Map.of("path", ".env")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, gateCalls.get(), "protected read should require explicit approval");
        assertEquals("protected read: talos.read_file", approvalDescription.get());
        assertTrue(approvalDetail.get().contains("protected path `.env`"), approvalDetail.get());
        assertFalse(approvalDetail.get().contains("SECRET=1"), approvalDetail.get());
        assertTrue(result.output().contains("SECRET=1"));
    }

    @Test
    void protectedReadUsesOnceOnlyApprovalSurface(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve(".env"), "SECRET=1");
        AtomicInteger fullApprovals = new AtomicInteger();
        AtomicInteger onceApprovals = new AtomicInteger();
        ApprovalGate gate = new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveOnce(description, detail).isApproved();
            }

            @Override public ApprovalResponse approveFull(String description, String detail) {
                fullApprovals.incrementAndGet();
                return ApprovalResponse.APPROVED;
            }

            @Override public ApprovalResponse approveOnce(String description, String detail) {
                onceApprovals.incrementAndGet();
                return ApprovalResponse.APPROVED;
            }
        };
        Config config = new Config(null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry);

        TurnUserRequestCapture.set("read .env");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.read_file", Map.of("path", ".env")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, fullApprovals.get(), "protected read must not offer session approval");
        assertEquals(1, onceApprovals.get(), "protected read should request once-only approval");
        assertTrue(result.output().contains("SECRET=1"));
    }

    @Test
    void protectedAliasReadRecordsTargetTruthfulKindInPermissionTrace(@TempDir Path workspace) {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicReference<String> approvalDetail = new AtomicReference<>();
        Config config = new Config(null);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), (description, detail) -> {
                    gateCalls.incrementAndGet();
                    approvalDetail.set(detail);
                    return true;
                }, registry);

        TurnUserRequestCapture.set("read SSH~1/id_rsa");
        LocalTurnTraceCapture.begin(
                "trc-t867-protected-alias",
                "sid-t867",
                1,
                "2026-06-30T12:00:00Z",
                "workspace-hash",
                "agent",
                "test",
                "model",
                "read SSH~1/id_rsa");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.read_file", Map.of("path", "SSH~1/id_rsa")),
                context(workspace, config));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertFalse(result.success(), "the alias target need not exist for permission trace classification");
        assertEquals(1, gateCalls.get(), "the protected alias should require approval before read execution");
        assertTrue(approvalDetail.get().contains("protected path `SSH~1/id_rsa`"), approvalDetail.get());
        assertTrue(approvalDetail.get().contains("(SECRET)"), approvalDetail.get());
        var event = trace.events().stream()
                .filter(candidate -> "PERMISSION_DECISION".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(true, event.data().get("protectedPath"));
        assertEquals("SECRET", event.data().get("protectedKind"));
        assertEquals("<protected-path>", event.data().get("pathHint"));
    }

    @Test
    void sessionRememberStillBypassesGateForSafeWriteButNotProtectedPath(@TempDir Path workspace) {
        AtomicInteger gateCalls = new AtomicInteger();
        ApprovalGate gate = new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                gateCalls.incrementAndGet();
                return ApprovalResponse.APPROVED_REMEMBER;
            }
        };
        SessionApprovalPolicy approvalPolicy = new SessionApprovalPolicy();
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry, approvalPolicy);
        Session session = new Session(workspace, config);
        Context ctx = context(workspace, config);

        TurnUserRequestCapture.set("write files");
        ToolResult first = processor.executeTool(session,
                new ToolCall("talos.write_file", Map.of("path", "a.txt", "content", "a")), ctx);
        ToolResult second = processor.executeTool(session,
                new ToolCall("talos.write_file", Map.of("path", "b.txt", "content", "b")), ctx);
        ToolResult protectedPath = processor.executeTool(session,
                new ToolCall("talos.write_file", Map.of("path", ".env", "content", "SECRET=1")), ctx);

        assertTrue(first.success(), first.errorMessage());
        assertTrue(second.success(), second.errorMessage());
        assertFalse(protectedPath.success());
        assertEquals(ToolError.DENIED, protectedPath.error().code());
        assertEquals(1, gateCalls.get(),
                "second safe write should use remember; protected mutation should deny without asking");
    }

    @Test
    void readOnlyToolInsideWorkspaceStillRunsWithoutApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "hello");
        AtomicInteger gateCalls = new AtomicInteger();
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gateApproves(gateCalls), registry);

        TurnUserRequestCapture.set("read README.md");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.read_file", Map.of("path", "README.md")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(0, gateCalls.get(), "ordinary read-only workspace tools should remain usable");
        assertTrue(result.output().contains("hello"));
    }

    @Test
    void operationBeforeContentMkdirRemainsBlockedBeforeApproval(
            @TempDir Path workspace
    ) {
        AtomicInteger gateCalls = new AtomicInteger();
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MakeDirectoryTool());
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gateApproves(gateCalls), registry);

        String request =
                "Create a directory named docs/notes, then create docs/notes/implementation-plan.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        assertTrue(TurnTaskContractCapture.getResolution()
                .blocksWorkspaceOperations());
        Session session = new Session(workspace, config);
        Context context = context(workspace, config);

        ToolResult mkdir = processor.executeTool(
                session,
                new ToolCall("talos.mkdir", Map.of("path", "docs/notes")),
                context);

        assertFalse(mkdir.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                mkdir.error().reason());
        assertTrue(
                mkdir.errorMessage().contains(
                        "not authorized by the current typed task plan"),
                mkdir.errorMessage());
        assertFalse(Files.exists(workspace.resolve("docs/notes")));
        assertEquals(
                0,
                gateCalls.get(),
                "operation-before-content order must fail before approval");
    }

    @Test
    void mkdirOnlyExplicitDirectoryRequestRemainsAllowed(@TempDir Path workspace)
            throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        AtomicInteger gateCalls = new AtomicInteger();
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MakeDirectoryTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gateApproves(gateCalls), registry);

        String request = "Create a directory named docs/notes.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.mkdir", Map.of("path", "docs/notes")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertTrue(Files.isDirectory(workspace.resolve("docs/notes")));
        assertEquals(1, gateCalls.get());
    }

    @Test
    void unrelatedMkdirStillBlockedBeforeApproval(@TempDir Path workspace) {
        AtomicInteger gateCalls = new AtomicInteger();
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MakeDirectoryTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gateApproves(gateCalls), registry);

        String request = "Create docs/notes/implementation-plan.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        assertTrue(TurnTaskContractCapture.workspaceOperationPlan()
                .authorizedOperations().isEmpty());
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.mkdir", Map.of("path", "tmp/unrelated")),
                context(workspace, config));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains(
                        "No executable typed workspace-operation authority is stored"),
                result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("tmp/unrelated")));
        assertEquals(0, gateCalls.get(), "unrelated target must block before approval");
    }

    @Test
    void asNeededMutationTargetIsDeniedUntilT1202(@TempDir Path workspace) throws Exception {
        AtomicInteger gateCalls = new AtomicInteger();
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gateApproves(gateCalls), registry);

        TurnUserRequestCapture.set("Update index.html and scripts.js. Adjust styles.css as needed.");
        assertEquals(
                java.util.Set.of("index.html", "scripts.js"),
                TaskContractResolver.fromUserRequest("Update index.html and scripts.js. Adjust styles.css as needed.")
                        .expectedTargets());
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.write_file", Map.of("path", "styles.css", "content", "body { margin: 0; }\n")),
                context(workspace, config));

        assertFalse(result.success(), result.errorMessage());
        assertTrue(
                result.errorMessage().contains(
                        "outside the current expected target set"),
                result.errorMessage());
        assertEquals(
                0,
                gateCalls.get(),
                "non-durable optional authority must block before approval");
        assertFalse(Files.exists(workspace.resolve("styles.css")));
    }

    @Test
    void unresolvedDependencyAuthorityBlocksWorkspaceOperationBeforeApproval(
            @TempDir Path temp
    ) throws Exception {
        for (String request : List.of(
                "Edit index.html and update style.css as needed, "
                        + "then copy a.md to b.md.",
                "Edit index.html and use style.css as reference, "
                        + "then copy a.md to b.md.")) {
            Path workspace = temp.resolve(Integer.toHexString(
                    request.hashCode()));
            Files.createDirectories(workspace);
            Files.writeString(workspace.resolve("a.md"), "source");
            AtomicInteger gateCalls = new AtomicInteger();
            Config config = checkpointDisabledConfig();
            ToolRegistry registry = new ToolRegistry();
            registry.register(new CopyPathTool());
            TurnProcessor processor = new TurnProcessor(
                    ModeController.defaultController(),
                    gateApproves(gateCalls),
                    registry);
            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromUserRequest(request));

            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    new ToolCall("talos.copy_path", Map.of(
                            "from", "a.md",
                            "to", "b.md")),
                    context(workspace, config));

            assertFalse(result.success(), request);
            assertEquals(
                    ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                    result.error().reason(),
                    request);
            assertEquals(0, gateCalls.get(), request);
            assertFalse(Files.exists(workspace.resolve("b.md")), request);
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void exactRequiredContentPredecessorsStillReachTypedCopyApproval(
            @TempDir Path workspace
    ) throws Exception {
        Files.writeString(workspace.resolve("a.md"), "source");
        AtomicInteger gateCalls = new AtomicInteger();
        Config config = checkpointDisabledConfig();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        registry.register(new CopyPathTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                gateApproves(gateCalls),
                registry);
        String request = "Edit index.html and update style.css, "
                + "then copy a.md to b.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        Context context = context(workspace, config);

        ToolResult index = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.write_file", Map.of(
                        "path", "index.html",
                        "content", "<main>ready</main>\n")),
                context);
        ToolResult style = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.write_file", Map.of(
                        "path", "style.css",
                        "content", "main { display: block; }\n")),
                context);
        ToolResult copy = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.copy_path", Map.of(
                        "from", "a.md",
                        "to", "b.md")),
                context);

        assertAll(
                () -> assertTrue(index.success(), index.errorMessage()),
                () -> assertTrue(style.success(), style.errorMessage()),
                () -> assertTrue(copy.success(), copy.errorMessage()));
        assertEquals(3, gateCalls.get());
        assertEquals("source", Files.readString(workspace.resolve("b.md")));
    }

    @Test
    void incompleteTargetScanBlocksMissingAndForgedWriteSurfacesBeforeApproval(
            @TempDir Path workspace
    ) {
        AtomicInteger gateCalls = new AtomicInteger();
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                gateApproves(gateCalls),
                registry);
        String request = "Repair it. " + "a.txt ".repeat(8_193);
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
        Context forgedSurface = context(workspace, config)
                .withNativeToolSpecs(List.of(new ToolSpec(
                        "talos.write_file",
                        "forged stale writer",
                        "{}")));

        for (Context attemptedSurface : List.of(
                context(workspace, config),
                forgedSurface)) {
            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    new ToolCall(
                            "talos.write_file",
                            Map.of("path", "a.txt", "content", "forbidden")),
                    attemptedSurface);

            assertFalse(result.success());
            assertEquals(
                    ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                    result.error().reason());
        }
        assertEquals(0, gateCalls.get());
        assertFalse(Files.exists(workspace.resolve("a.txt")));
    }

    private static TurnProcessor processor(Config config, ApprovalGate gate, TalosTool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return new TurnProcessor(ModeController.defaultController(), gate, registry);
    }

    private static ApprovalGate gateApproves(AtomicInteger calls) {
        return new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                calls.incrementAndGet();
                return ApprovalResponse.APPROVED;
            }
        };
    }

    private static Context context(Path workspace, Config config) {
        return Context.builder(config)
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
    }

    private static Config configWithRules(List<Map<String, Object>> rules) {
        Config config = new Config();
        config.data.put("permissions", Map.of("rules", rules));
        return config;
    }

    private static Config checkpointDisabledConfig() {
        Config config = new Config();
        config.data.put("checkpoint", Map.of(
                "enabled", false,
                "fail_closed", true,
                "max_file_bytes", 1_000_000,
                "max_turn_bytes", 2_000_000));
        return config;
    }

    private static Map<String, Object> rule(
            String effect,
            List<String> tools,
            List<String> risks,
            List<String> phases,
            List<String> paths
    ) {
        return Map.of(
                "effect", effect,
                "tools", tools,
                "risks", risks,
                "phases", phases,
                "paths", paths,
                "reason", effect + " test rule");
    }

    private record CountingWriteTool(AtomicInteger executions) implements TalosTool {
        @Override public String name() { return "test.write"; }
        @Override public String description() { return "write"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor(name(), description(), null, ToolRiskLevel.WRITE);
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
            executions.incrementAndGet();
            return ToolResult.ok("wrote");
        }
    }
}
