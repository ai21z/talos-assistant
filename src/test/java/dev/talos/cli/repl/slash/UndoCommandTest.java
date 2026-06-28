package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.FileBundleCheckpointStore;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T795 contract: {@code /undo} is an approval-gated restore of the newest
 * checkpoint with a safety checkpoint captured first.
 *
 * <p>This file's T787 revision pinned the OPPOSITE behavior - the pre-T795
 * {@code /undo} popped an in-memory stack and wrote files (including
 * protected paths) with no approval gate. That trust hole is closed; the
 * diff of this rewrite is the behavioral-delta documentation.
 */
class UndoCommandTest {

    @TempDir Path tempDir;

    private Path workspace;
    private CheckpointService service;
    private UndoCommand undoCmd;

    @BeforeEach
    void setUp() throws IOException {
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        service = new CheckpointService(
                new FileBundleCheckpointStore(tempDir.resolve("checkpoints")));
        undoCmd = new UndoCommand(workspace, service);
    }

    @Nested class Spec {
        @Test void name() { assertEquals("undo", undoCmd.spec().name()); }
        @Test void groupMovedToSecurityWithTheReroute() {
            assertEquals(CommandGroup.SECURITY, undoCmd.spec().group());
        }
    }

    @Nested class EmptyAndDisabledStates {
        @Test void noCheckpointsKeepsTheHistoricalWording() {
            Result r = undoCmd.execute("", ctxApproving(new AtomicInteger()));
            assertInstanceOf(Result.Info.class, r);
            assertEquals("Nothing to undo.\n", ((Result.Info) r).text,
                    "the empty-state bytes are kept across the T795 reroute");
        }

        @Test void disabledCheckpointingSaysSoExplicitly() {
            Config cfg = new Config();
            cfg.data.put("checkpoint", Map.of("enabled", false));
            Result r = undoCmd.execute("", Context.builder(cfg).build());
            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("Checkpointing is disabled"), r.toString());
        }
    }

    @Nested class GatedRestore {
        @Test void deniedApprovalLeavesEveryFileUntouched() throws IOException {
            Files.writeString(workspace.resolve("app.js"), "original");
            capture("app.js"); // checkpoint holds "original"
            Files.writeString(workspace.resolve("app.js"), "changed");

            Result r = undoCmd.execute("", ctxDenying());

            assertInstanceOf(Result.Info.class, r);
            assertTrue(r.toString().contains("Undo cancelled. No file changed."), r.toString());
            assertEquals("changed", Files.readString(workspace.resolve("app.js")),
                    "a denied approval must leave zero file mutations");
        }

        @Test void approvalDetailShowsTriggerFilesAndDiff() throws IOException {
            Files.writeString(workspace.resolve("app.js"), "original");
            capture("app.js");
            Files.writeString(workspace.resolve("app.js"), "changed");
            String[] captured = new String[2];

            undoCmd.execute("", ctxCapturing(captured, ApprovalResponse.DENIED));

            assertTrue(captured[0].startsWith("undo: restore checkpoint chk-"), captured[0]);
            assertTrue(captured[1].contains("talos.write_file app.js"), captured[1]);
            assertTrue(captured[1].contains("app.js  (restored)"), captured[1]);
            assertTrue(captured[1].contains("diff (+1 -1):"), captured[1]);
            assertTrue(captured[1].contains("-changed"), captured[1]);
            assertTrue(captured[1].contains("+original"), captured[1]);
            assertTrue(captured[1].contains("safety checkpoint"), captured[1]);
        }

        @Test void approvedUndoRestoresAndIsItselfUndoable() throws IOException {
            Files.writeString(workspace.resolve("app.js"), "original");
            capture("app.js");
            Files.writeString(workspace.resolve("app.js"), "changed");
            AtomicInteger approvals = new AtomicInteger();

            Result first = undoCmd.execute("", ctxApproving(approvals));

            assertInstanceOf(Result.Ok.class, first);
            assertEquals(1, approvals.get(), "undo must ask before writing files");
            assertEquals("original", Files.readString(workspace.resolve("app.js")));
            assertTrue(first.toString().contains("/undo again to redo"), first.toString());

            // The safety checkpoint is now the newest - a second /undo redoes.
            Result second = undoCmd.execute("", ctxApproving(approvals));

            assertInstanceOf(Result.Ok.class, second);
            assertEquals("changed", Files.readString(workspace.resolve("app.js")),
                    "/undo twice must redo via the safety checkpoint");
        }

        @Test void createdFileUndoDeletesItWithTheWarningShown() throws IOException {
            // Checkpoint records new.txt as absent (the write creates it).
            capture("new.txt");
            Files.writeString(workspace.resolve("new.txt"), "created");
            String[] captured = new String[2];
            Context approving = ctxCapturing(captured, ApprovalResponse.APPROVED);

            Result r = undoCmd.execute("", approving);

            assertInstanceOf(Result.Ok.class, r);
            assertTrue(captured[1].contains("will be DELETED - did not exist at capture"),
                    captured[1]);
            assertFalse(Files.exists(workspace.resolve("new.txt")),
                    "undoing a creation deletes the created file");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private void capture(String path) {
        var result = service.captureBeforeMutation(workspace, enabledConfig(),
                new ToolCall("talos.write_file", Map.of("path", path, "content", "x")),
                "trc", 1);
        assertTrue(result.success(), result.message());
    }

    private static Config enabledConfig() {
        Config cfg = new Config();
        cfg.data.put("checkpoint", Map.of("enabled", true, "fail_closed", true));
        return cfg;
    }

    private static Context ctxApproving(AtomicInteger approvals) {
        return Context.builder(enabledConfig())
                .approvalGate(new ApprovalGate() {
                    @Override public boolean approve(String description, String detail) {
                        return approveFull(description, detail).isApproved();
                    }
                    @Override public ApprovalResponse approveFull(String description, String detail) {
                        approvals.incrementAndGet();
                        return ApprovalResponse.APPROVED;
                    }
                })
                .build();
    }

    private static Context ctxDenying() {
        return Context.builder(enabledConfig())
                .approvalGate((description, detail) -> false)
                .build();
    }

    private static Context ctxCapturing(String[] captured, ApprovalResponse response) {
        return Context.builder(enabledConfig())
                .approvalGate(new ApprovalGate() {
                    @Override public boolean approve(String description, String detail) {
                        return approveFull(description, detail).isApproved();
                    }
                    @Override public ApprovalResponse approveFull(String description, String detail) {
                        captured[0] = description;
                        captured[1] = detail;
                        return response;
                    }
                })
                .build();
    }
}
