package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.Config;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.FileBundleCheckpointStore;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointCommandTest {

    @Test
    void restoreRequiresApprovalAndRestoresCapturedFiles(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("index.html"), "before");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(),
                new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "after")),
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("index.html"), "after");
        AtomicInteger approvals = new AtomicInteger();
        CheckpointCommand command = new CheckpointCommand(workspace, service);

        Result result = command.execute("restore " + capture.checkpointId(), context(approvals));

        assertInstanceOf(Result.Ok.class, result);
        assertEquals("before", Files.readString(workspace.resolve("index.html")));
        assertEquals(1, approvals.get(), "restore must ask before writing files");
    }

    @Test
    void restoreDenialDoesNotChangeFiles(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("index.html"), "before");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(),
                new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "after")),
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("index.html"), "after");
        CheckpointCommand command = new CheckpointCommand(workspace, service);

        Result result = command.execute("restore " + capture.checkpointId(), contextDenied());

        assertInstanceOf(Result.Info.class, result);
        assertEquals("after", Files.readString(workspace.resolve("index.html")));
    }

    /**
     * T787 pin: {@code /checkpoint list} renders ids only, ordered
     * reverse-lexicographically on random {@code chk-<uuid>} names — i.e.
     * the order is ARBITRARY, not chronological. T793 deliberately changes
     * the listing to createdAt-descending with timestamp/trigger detail.
     */
    @Test
    void listShapeAndArbitraryOrderingPin(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "a");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        String first = service.captureBeforeMutation(workspace, config(),
                new ToolCall("talos.write_file", Map.of("path", "a.txt", "content", "x")),
                "trc-1", 1).checkpointId();
        String second = service.captureBeforeMutation(workspace, config(),
                new ToolCall("talos.write_file", Map.of("path", "a.txt", "content", "y")),
                "trc-2", 2).checkpointId();
        CheckpointCommand command = new CheckpointCommand(workspace, service);

        Result result = command.execute("list", contextDenied());

        assertInstanceOf(Result.Info.class, result);
        String expectedNewestByName = first.compareTo(second) > 0 ? first : second;
        String expectedOldestByName = first.compareTo(second) > 0 ? second : first;
        assertEquals("Checkpoints:\n  " + expectedNewestByName + "\n  " + expectedOldestByName,
                ((Result.Info) result).text,
                "pre-T793 listing is id-sorted (descending), not time-sorted");
    }

    /** T787 pin: the /checkpoint restore approval bytes are frozen this wave. */
    @Test
    void restoreApprovalDescriptionAndDetailBytesPin(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("index.html"), "before");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        String id = service.captureBeforeMutation(workspace, config(),
                new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "after")),
                "trc-test", 1).checkpointId();
        String[] captured = new String[2];
        Context ctx = Context.builder(config())
                .approvalGate(new ApprovalGate() {
                    @Override public boolean approve(String description, String detail) {
                        return approveFull(description, detail).isApproved();
                    }
                    @Override public ApprovalResponse approveFull(String description, String detail) {
                        captured[0] = description;
                        captured[1] = detail;
                        return ApprovalResponse.DENIED;
                    }
                })
                .build();
        CheckpointCommand command = new CheckpointCommand(workspace, service);

        command.execute("restore " + id, ctx);

        assertEquals("restore checkpoint: " + id, captured[0]);
        assertEquals("Restore files captured by checkpoint " + id + " in workspace " + workspace,
                captured[1]);
    }

    private static Config config() {
        Config config = new Config();
        config.data.put("checkpoint", Map.of("enabled", true, "fail_closed", true));
        return config;
    }

    private static Context context(AtomicInteger approvals) {
        return Context.builder(config())
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

    private static Context contextDenied() {
        return Context.builder(config())
                .approvalGate((description, detail) -> false)
                .build();
    }
}
