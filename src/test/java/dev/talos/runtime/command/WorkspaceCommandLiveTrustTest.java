package dev.talos.runtime.command;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ApprovalPolicy;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolRegistry;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** All checks retain the SAME registered profiles/tool across the trust change. No host process runs. */
class WorkspaceCommandLiveTrustTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"revoke", "edit", "edit-and-repin"})
    void existingRegistryAndToolRejectChangedTrustBeforeApprovalAndExecution(String change) throws Exception {
        Path workspace = workspace();
        WorkspaceProfileTrustStore store = new WorkspaceProfileTrustStore(temporary.resolve("trust"));
        var loaded = WorkspaceCommandProfilesLoader.load(workspace);
        store.pin(workspace, loaded.declarationSha256(), 1, Instant.EPOCH);
        CommandProfileRegistry profiles = CommandProfileRegistry.defaultRegistry()
                .withWorkspaceDeclaration(workspace, loaded, store);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger approvals = new AtomicInteger();
        RunCommandTool tool = new RunCommandTool(profiles, plan -> {
            runs.incrementAndGet();
            return new CommandResult(plan, 0, 1, false, false, "ok", "", false, false, false, "");
        });
        ToolRegistry tools = new ToolRegistry();
        tools.register(tool);
        TurnProcessor processor = new TurnProcessor(null, (description, detail) -> {
            approvals.incrementAndGet();
            return true;
        }, tools, ApprovalPolicy.ALWAYS_ASK, new CheckpointService(), profiles);
        Config config = new Config(null);
        Sandbox sandbox = new Sandbox(workspace, Map.of());
        ToolContext toolContext = new ToolContext(workspace, sandbox, config);
        Context context = Context.builder(config).sandbox(sandbox).toolRegistry(tools)
                .executionPhaseState(new ExecutionPhaseState(ExecutionPhase.VERIFY)).build();
        ToolCall call = new ToolCall(CommandToolPlanner.TOOL_NAME, Map.of("profile", "ws:check"));
        assertTrue(CommandToolPlanner.validateBeforeApproval(call, workspace, profiles).isEmpty());
        assertTrue(tool.execute(call, toolContext).success());
        assertEquals(1, runs.get());

        changeTrust(change, workspace, store);

        assertTrue(CommandToolPlanner.validateBeforeApproval(call, workspace, profiles).isPresent());
        var denied = processor.executeTool(new Session(workspace, config), call, context);
        assertFalse(denied.success());
        assertEquals(ToolError.INVALID_PARAMS, denied.error().code(), denied.errorMessage());
        assertTrue(denied.errorMessage().contains("trust was revoked or the declaration changed"), denied.errorMessage());
        assertEquals(0, approvals.get(), "stale trust must fail before spending approval");
        assertFalse(tool.execute(call, toolContext).success(), "direct tool execution must recheck too");
        assertEquals(1, runs.get(), "neither rejected route may invoke the runner again");
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoke", "edit"})
    void approvalCannotKeepAProfileAliveAfterTrustChanges(String change) throws Exception {
        Path workspace = workspace();
        WorkspaceProfileTrustStore store = new WorkspaceProfileTrustStore(temporary.resolve("trust"));
        var loaded = WorkspaceCommandProfilesLoader.load(workspace);
        store.pin(workspace, loaded.declarationSha256(), 1, Instant.EPOCH);
        CommandProfileRegistry profiles = CommandProfileRegistry.defaultRegistry()
                .withWorkspaceDeclaration(workspace, loaded, store);
        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        ToolRegistry tools = new ToolRegistry();
        tools.register(new RunCommandTool(profiles, plan -> {
            runs.incrementAndGet();
            throw new AssertionError("approval-time trust change must prevent execution");
        }));
        TurnProcessor processor = new TurnProcessor(null, (description, detail) -> {
            approvals.incrementAndGet();
            try {
                changeTrust(change, workspace, store);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return true;
        }, tools, ApprovalPolicy.ALWAYS_ASK, new CheckpointService(), profiles);
        Config config = new Config(null);
        Context context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(tools).executionPhaseState(new ExecutionPhaseState(ExecutionPhase.VERIFY)).build();

        var result = processor.executeTool(new Session(workspace, config),
                new ToolCall(CommandToolPlanner.TOOL_NAME, Map.of("profile", "ws:check")), context);

        assertFalse(result.success());
        assertEquals(1, approvals.get());
        assertEquals(0, runs.get());
    }

    private Path workspace() throws Exception {
        Path workspace = temporary.resolve("workspace");
        Files.createDirectories(workspace.resolve(".talos"));
        Files.writeString(workspace.resolve(".talos/profiles.yaml"),
                "profiles:\n  - id: check\n    executable: test-fixture-never-launched\n    args: [check]\n");
        return workspace;
    }

    private static void changeTrust(String change, Path workspace, WorkspaceProfileTrustStore store) throws Exception {
        if ("revoke".equals(change)) {
            store.unpin(workspace);
        } else {
            Path declaration = workspace.resolve(".talos/profiles.yaml");
            Files.writeString(declaration, Files.readString(declaration) + "# changed after registration\n");
            if ("edit-and-repin".equals(change)) {
                store.pin(workspace, WorkspaceCommandProfilesLoader.load(workspace).declarationSha256(), 1, Instant.EPOCH);
            }
        }
    }
}
