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
    void restoreCreatesSafetyCheckpointBeforeDirectRestore(@TempDir Path temp) throws Exception {
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

        Result result = command.execute("restore " + capture.checkpointId(), context(new AtomicInteger()));

        assertInstanceOf(Result.Ok.class, result);
        String safetyId = service.listSummaries(workspace).stream()
                .filter(summary -> ("restore of " + capture.checkpointId()).equals(summary.trigger()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("direct restore must create a safety checkpoint"))
                .id();
        assertTrue(service.restore(workspace, safetyId).success());
        assertEquals("after", Files.readString(workspace.resolve("index.html")),
                "restoring the safety checkpoint must recover the pre-restore state");
    }

    @Test
    void restoreAbortsWhenPreRestoreSafetyCheckpointIsSkipped(@TempDir Path temp) throws Exception {
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

        Result result = command.execute(
                "restore " + capture.checkpointId(),
                context(new AtomicInteger(), config(false)));

        assertInstanceOf(Result.Error.class, result);
        assertTrue(((Result.Error) result).message.toLowerCase(java.util.Locale.ROOT).contains("safety"),
                ((Result.Error) result).message);
        assertEquals("after", Files.readString(workspace.resolve("index.html")),
                "restore must not modify files when the safety checkpoint is skipped");
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
     * T787 pinned the pre-T793 ordering (reverse-lexicographic on random
     * UUIDs - arbitrary). T793 deliberately flipped the listing to true
     * createdAt-descending; this test now pins THAT, with fabricated
     * timestamps that would invert any id-based ordering.
     */
    @Test
    void listOrdersNewestFirstByCreatedAtSinceT793(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("a.txt"), "a");
        Path storeRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(storeRoot));
        String first = service.captureBeforeMutation(workspace, config(),
                new ToolCall("talos.write_file", Map.of("path", "a.txt", "content", "x")),
                "trc-1", 1).checkpointId();
        String second = service.captureBeforeMutation(workspace, config(),
                new ToolCall("talos.write_file", Map.of("path", "a.txt", "content", "y")),
                "trc-2", 2).checkpointId();
        setCreatedAt(storeRoot, workspace, first, "2026-06-12T10:00:00Z");
        setCreatedAt(storeRoot, workspace, second, "2026-06-12T09:00:00Z");
        CheckpointCommand command = new CheckpointCommand(workspace, service);

        Result result = command.execute("list", contextDenied());

        assertInstanceOf(Result.Info.class, result);
        String text = ((Result.Info) result).text;
        assertTrue(text.indexOf(first) < text.indexOf(second),
                "newest (by createdAt) must list first regardless of id order:\n" + text);
    }

    /** T794: timeline columns rendered deterministically with a fixed zone. */
    @Test
    void timelineRendersTimeTurnTriggerAndSize() {
        var summary = new dev.talos.runtime.checkpoint.CheckpointSummary(
                "chk-abc", java.time.Instant.parse("2026-06-12T10:30:00Z"), 7,
                "talos.write_file app.js", 2, 2048, "CREATED");

        String text = CheckpointCommand.renderTimeline(
                java.util.List.of(summary), java.time.ZoneId.of("UTC"));

        assertTrue(text.startsWith("Checkpoints (newest first):"), text);
        assertTrue(text.contains(
                "chk-abc | 2026-06-12 10:30 | turn 7 | talos.write_file app.js | 2 file(s), 2.0 KiB"),
                text);
        assertTrue(text.contains("/undo to restore the newest"), text);
    }

    /** T794: show renders per-file stats, restore diffs, and delete warnings. */
    @Test
    void showRendersRestoreDiffAndDeleteAnnotations(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("index.html"), "before");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        // Captures "before" for index.html AND records new.txt as absent.
        String withFile = service.captureBeforeMutation(workspace, config(),
                new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "after")),
                "trc", 1).checkpointId();
        String withAbsent = service.captureBeforeMutation(workspace, config(),
                new ToolCall("talos.write_file", Map.of("path", "new.txt", "content", "x")),
                "trc", 2).checkpointId();
        Files.writeString(workspace.resolve("index.html"), "after");
        CheckpointCommand command = new CheckpointCommand(workspace, service);

        String shown = ((Result.Info) command.execute("show " + withFile, contextDenied())).text;
        assertTrue(shown.contains("index.html"), shown);
        assertTrue(shown.contains("restore diff (+1 -1):"), shown);
        assertTrue(shown.contains("-after"), shown);
        assertTrue(shown.contains("+before"), shown);

        String shownAbsent = ((Result.Info) command.execute("show " + withAbsent, contextDenied())).text;
        assertTrue(shownAbsent.contains("new.txt  (did not exist at capture - restore DELETES it)"),
                shownAbsent);

        assertInstanceOf(Result.Error.class, command.execute("show chk-missing", contextDenied()));
    }

    private static void setCreatedAt(Path storeRoot, Path workspace,
                                     String checkpointId, String instant) throws Exception {
        String workspaceId = dev.talos.runtime.JsonSessionStore
                .sessionIdFor(workspace.toAbsolutePath().normalize());
        Path file = storeRoot.resolve(workspaceId).resolve("checkpoints")
                .resolve(checkpointId).resolve("metadata.json");
        Files.writeString(file, Files.readString(file)
                .replaceAll("\"createdAt\"\\s*:\\s*\"[^\"]*\"",
                        "\"createdAt\" : \"" + instant + "\""));
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
        return config(true);
    }

    private static Config config(boolean enabled) {
        Config config = new Config();
        config.data.put("checkpoint", Map.of("enabled", enabled, "fail_closed", true));
        return config;
    }

    private static Context context(AtomicInteger approvals) {
        return context(approvals, config());
    }

    private static Context context(AtomicInteger approvals, Config config) {
        return Context.builder(config)
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
