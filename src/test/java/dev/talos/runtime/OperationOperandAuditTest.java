package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.FileBundleCheckpointStore;
import dev.talos.runtime.intent.WorkspaceOperationObligation.Role;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.RenamePathTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The audit of a single workspace operation names each operand with its role at every disposition, through the
 * real TurnProcessor, while pathHints keep naming only the changed paths.
 */
class OperationOperandAuditTest {

    private record Fixture(String name, String request, ToolCall call, List<TurnRecord.OperationOperand> operands,
                           List<String> appliedPathHints) {
    }

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void aDeniedOperationAuditsEachOperandWithItsRole(@TempDir Path temp) throws Exception {
        int ordinal = 0;
        for (Fixture fixture : fixtures()) {
            TurnRecord.ToolCallSummary call = audited(temp.resolve("denied-" + ordinal), fixture, false, ordinal);
            assertEquals(TurnRecord.ToolCallDisposition.BLOCKED, call.executionDisposition(), fixture.name());
            assertEquals(fixture.operands(), call.operands(), fixture.name());
            ordinal++;
        }
    }

    @Test
    void anAppliedOperationAuditsEachOperandAndKeepsItsChangedPaths(@TempDir Path temp) throws Exception {
        int ordinal = 0;
        for (Fixture fixture : fixtures()) {
            TurnRecord.ToolCallSummary call = audited(temp.resolve("applied-" + ordinal), fixture, true, ordinal);
            assertTrue(call.success(), fixture.name());
            assertEquals(fixture.operands(), call.operands(), fixture.name());
            assertEquals(fixture.appliedPathHints(), call.pathHints(), fixture.name());
            ordinal++;
        }
    }

    @Test
    void aDeniedTwoPathOperationKeepsItsSourceAsTheOnlyPathHint(@TempDir Path temp) throws Exception {
        int ordinal = 0;
        for (Fixture fixture : fixtures().subList(0, 3)) {
            TurnRecord.ToolCallSummary call = audited(temp.resolve("hint-" + ordinal), fixture, false, ordinal);
            assertEquals(List.of("source.txt"), call.pathHints(), fixture.name());
            ordinal++;
        }
    }

    @Test
    void aCallNoAuthorityBoundAuditsTheOperandsItNamed(@TempDir Path temp) throws Exception {
        List<Fixture> unbound = List.of(
                new Fixture("move with aliases", "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of("source", "source.txt", "to", "out/other.txt")),
                        List.of(operand("source.txt", Role.SOURCE), operand("out/other.txt", Role.DESTINATION)),
                        List.of()),
                new Fixture("move with a source its tool does not accept", "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of("file", "source.txt", "to", "out/moved.txt")),
                        List.of(operand("source.txt", Role.SOURCE), operand("out/moved.txt", Role.DESTINATION)),
                        List.of()),
                new Fixture("move that names only its destination", "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of("to", "out/moved.txt")),
                        List.of(operand("out/moved.txt", Role.DESTINATION)), List.of()),
                new Fixture("move whose folder parameter is not its source", "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of("to", "out/moved.txt", "directory", "out")),
                        List.of(operand("out/moved.txt", Role.DESTINATION)), List.of()),
                new Fixture("rename with a source named as its target", "Rename source.txt to renamed.txt.",
                        new ToolCall("talos.rename_path", Map.of("target", "source.txt", "new_name", "renamed.txt")),
                        List.of(operand("source.txt", Role.SOURCE), operand("renamed.txt", Role.DESTINATION)),
                        List.of()),
                new Fixture("delete with an alias", "Delete source.txt.",
                        new ToolCall("talos.delete_path", Map.of("target", "source.txt")),
                        List.of(operand("source.txt", Role.TARGET)), List.of()),
                new Fixture("delete with a parameter its tool does not accept", "Delete source.txt.",
                        new ToolCall("talos.delete_path", Map.of("file_path", "source.txt")),
                        List.of(operand("source.txt", Role.TARGET)), List.of()),
                new Fixture("mkdir with an alias", "Create a directory named assets.",
                        new ToolCall("talos.mkdir", Map.of("dir", "assets")),
                        List.of(operand("assets", Role.TARGET)), List.of()),
                new Fixture("rename inside a folder with an alias", "Rename out/notes.md to b.md.",
                        new ToolCall("talos.rename_path", Map.of("from", "out/notes.md", "new_name", "b.md")),
                        List.of(operand("out/notes.md", Role.SOURCE), operand("out/b.md", Role.DESTINATION)),
                        List.of()),
                new Fixture("rename with a source its tool does not accept", "Rename source.txt to renamed.txt.",
                        new ToolCall("talos.rename_path", Map.of("file", "source.txt", "new_name", "renamed.txt")),
                        List.of(operand("source.txt", Role.SOURCE), operand("renamed.txt", Role.DESTINATION)),
                        List.of()));
        int ordinal = 0;
        for (Fixture fixture : unbound) {
            TurnRecord.ToolCallSummary call = audited(temp.resolve("unbound-" + ordinal), fixture, true, ordinal);
            assertEquals(TurnRecord.ToolCallDisposition.BLOCKED, call.executionDisposition(), fixture.name());
            assertEquals(fixture.operands(), call.operands(), fixture.name());
            ordinal++;
        }
    }

    @Test
    void anOperationStoppedAtItsCheckpointAuditsItsOperands(@TempDir Path temp) throws Exception {
        // The checkpoint refuses a file larger than its limit, after approval and before the move.
        Fixture move = fixtures().get(1);
        TurnRecord.ToolCallSummary call = audited(temp, move, true, 0, 1);
        assertFalse(call.success(), call.toString());
        assertEquals(TurnRecord.ToolCallDisposition.UNKNOWN, call.executionDisposition(), call.toString());
        assertTrue(call.reason().contains("checkpoint"), call.reason());
        assertEquals(move.operands(), call.operands());
    }

    @Test
    void aPathThatIsBlankOnceNormalizedIsLeftOutWithoutEndingTheTurn() {
        TurnAuditCapture.begin();
        TurnAuditCapture.recordBlockedToolCall(new ToolCall("talos.delete_path", Map.of("path", "./ ./")),
                "./ ./", "EXECUTE", "blocked");
        TurnAudit audit = TurnAuditCapture.end();
        assertEquals(1, audit.toolCalls().size());
        assertEquals(List.of(), audit.toolCalls().getFirst().operands());
        assertEquals("a.md", operand(" ./ ./a.md", Role.TARGET).path());
        assertEquals("a.md", operand(operand(" ./ ./a.md", Role.TARGET).path(), Role.TARGET).path());
    }

    @Test
    void aRenameWhoseDestinationCannotBeDerivedAuditsOnlyItsSource(@TempDir Path temp) throws Exception {
        Fixture folder = new Fixture(
                "rename of a folder spelled with a slash",
                "Rename out to archive.",
                new ToolCall("talos.rename_path", Map.of("path", "out/", "new_name", "archive")),
                List.of(operand("out/", Role.SOURCE)),
                List.of());
        Fixture nested = new Fixture(
                "rename to a nested name",
                "Rename source.txt to out/renamed.txt.",
                new ToolCall("talos.rename_path", Map.of("path", "source.txt", "new_name", "out/renamed.txt")),
                List.of(operand("source.txt", Role.SOURCE)),
                List.of());
        assertEquals(folder.operands(), audited(temp.resolve("folder"), folder, true, 0).operands());
        assertEquals(nested.operands(), audited(temp.resolve("nested"), nested, true, 1).operands());
    }

    @Test
    void anAuthorityRejectedCallAuditsNoOperands() {
        TurnAuditCapture.begin();
        TurnAuditCapture.recordAuthorityRejectedToolCall(
                new ToolCall("talos.move_path", Map.of("from", "source.txt", "to", "out/moved.txt")),
                "authority rejected");
        TurnAudit audit = TurnAuditCapture.end();
        assertEquals(List.of(), audit.toolCalls().getFirst().operands());
    }

    @Test
    void onlyASingleWorkspaceOperationNamesOperandsAndEachRoleOnce() {
        TurnRecord.OperationOperand source = operand("a.txt", Role.SOURCE);
        TurnRecord.OperationOperand destination = operand("b.txt", Role.DESTINATION);
        TurnRecord.OperationOperand target = operand("a.txt", Role.TARGET);
        assertThrows(IllegalArgumentException.class, () -> summary("talos.write_file", List.of(target)));
        assertThrows(IllegalArgumentException.class, () -> summary("talos.apply_workspace_batch", List.of(source)));
        assertThrows(IllegalArgumentException.class, () -> summary("talos.move_path", List.of(source, source)));
        assertThrows(IllegalArgumentException.class, () -> summary("talos.move_path", List.of(target)));
        assertThrows(IllegalArgumentException.class, () -> summary("talos.delete_path", List.of(source)));
        assertThrows(IllegalArgumentException.class, () -> operand(" ", Role.SOURCE));
        assertEquals(List.of(operand("a.txt", Role.SOURCE), destination),
                summary("talos.copy_path", List.of(operand(".\\a.txt", Role.SOURCE), destination)).operands());
        assertEquals(List.of(target), summary("talos.delete_path", List.of(target)).operands());
        assertEquals(List.of(), new TurnRecord.ToolCallSummary("talos.move_path", "a.txt", true).operands());
    }

    private static TurnRecord.ToolCallSummary summary(String name, List<TurnRecord.OperationOperand> operands) {
        return new TurnRecord.ToolCallSummary(name, "", List.of(), false, "", null,
                TurnRecord.ToolCallDisposition.BLOCKED, null, null, operands);
    }

    private static List<Fixture> fixtures() {
        return List.of(
                new Fixture("copy", "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of("from", "source.txt", "to", "out/copied.txt")),
                        List.of(operand("source.txt", Role.SOURCE), operand("out/copied.txt", Role.DESTINATION)),
                        List.of("out/copied.txt")),
                new Fixture("move", "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of("from", "source.txt", "to", "out/moved.txt")),
                        List.of(operand("source.txt", Role.SOURCE), operand("out/moved.txt", Role.DESTINATION)),
                        List.of("out/moved.txt")),
                new Fixture("rename", "Rename source.txt to renamed.txt.",
                        new ToolCall("talos.rename_path", Map.of("path", "source.txt", "new_name", "renamed.txt")),
                        List.of(operand("source.txt", Role.SOURCE), operand("renamed.txt", Role.DESTINATION)),
                        List.of("renamed.txt")),
                new Fixture("mkdir", "Create a directory named assets.",
                        new ToolCall("talos.mkdir", Map.of("path", "assets")),
                        List.of(operand("assets", Role.TARGET)),
                        List.of("assets")),
                new Fixture("delete", "Delete source.txt.",
                        new ToolCall("talos.delete_path", Map.of("path", "source.txt")),
                        List.of(operand("source.txt", Role.TARGET)),
                        List.of("source.txt")));
    }

    private static TurnRecord.OperationOperand operand(String path, Role role) {
        return new TurnRecord.OperationOperand(path, role);
    }

    private static TurnRecord.ToolCallSummary audited(Path temp, Fixture fixture, boolean approve, int ordinal)
            throws Exception {
        return audited(temp, fixture, approve, ordinal, 1_000_000);
    }

    private static TurnRecord.ToolCallSummary audited(
            Path temp, Fixture fixture, boolean approve, int ordinal, int maxFileBytes) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("out"));
        Files.writeString(workspace.resolve("source.txt"), "source-before");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CopyPathTool());
        registry.register(new MovePathTool());
        registry.register(new RenamePathTool());
        registry.register(new MakeDirectoryTool());
        registry.register(new DeletePathTool());
        ApprovalGate gate = new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approve;
            }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                return approve ? ApprovalResponse.APPROVED : ApprovalResponse.DENIED;
            }
        };
        TurnProcessor processor = new TurnProcessor(ModeController.defaultController(), gate, registry,
                ApprovalPolicy.ALWAYS_ASK,
                new CheckpointService(new FileBundleCheckpointStore(temp.resolve("checkpoints"))));
        Config config = new Config();
        config.data.put("checkpoint", Map.of("enabled", true, "fail_closed", true,
                "max_file_bytes", maxFileBytes, "max_turn_bytes", 2_000_000));
        LocalTurnTraceCapture.begin("trc-operand-" + ordinal, "sid", ordinal + 1, "2026-11-01T00:00:00Z", "sid",
                "auto", "test", "model", fixture.request());
        TurnUserRequestCapture.set(fixture.request());
        TurnTaskContractCapture.set(TaskContractResolver.resolveFromUserRequest(fixture.request()));
        TurnAuditCapture.begin();
        try {
            ToolResult ignored = processor.executeTool(new Session(workspace, config), fixture.call(),
                    Context.builder(config).sandbox(new Sandbox(workspace, Map.of())).build());
            TurnAudit audit = TurnAuditCapture.end();
            assertEquals(1, audit.toolCalls().size(), fixture.name());
            return audit.toolCalls().getFirst();
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
            LocalTurnTraceCapture.clear();
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
        }
    }
}
