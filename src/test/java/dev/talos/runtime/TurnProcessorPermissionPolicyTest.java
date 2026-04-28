package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.tools.*;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TurnProcessorPermissionPolicyTest {

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
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
        Config config = new Config();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(), gateApproves(gateCalls), registry);

        TurnUserRequestCapture.set("read .env");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.read_file", Map.of("path", ".env")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, gateCalls.get(), "protected read should require explicit approval");
        assertTrue(result.output().contains("SECRET=1"));
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
