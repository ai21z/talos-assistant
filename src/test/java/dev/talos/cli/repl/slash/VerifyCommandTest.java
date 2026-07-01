package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.command.CommandRunner;
import dev.talos.runtime.command.WorkspaceCommandProfilesLoader;
import dev.talos.runtime.command.WorkspaceProfileTrustStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** T791: /verify - live trust evaluation, per-run approval, fixed-argv execution. */
class VerifyCommandTest {

    @TempDir Path tempDir;

    @Test
    void noArgListsRunnableProfilesOrStateGuidance() throws IOException {
        Path ws = workspace();
        WorkspaceProfileTrustStore store = store();
        VerifyCommand command = new VerifyCommand(ws, store, plan -> {
            throw new AssertionError("listing must not run anything");
        });

        assertTrue(text(command.execute("", ctxWithoutGate()))
                .contains("No workspace verification profiles are declared"));

        declare(ws);
        assertTrue(text(command.execute("", ctxWithoutGate()))
                .contains("untrusted. Review and pin them with /profiles trust"));

        pin(ws, store);
        String runnable = text(command.execute("", ctxWithoutGate()));
        assertTrue(runnable.contains("Runnable workspace verification profiles: ws:check"),
                runnable);
    }

    @Test
    void untrustedProfileIsRejectedBeforeAnyApprovalOrExecution() throws IOException {
        Path ws = workspace();
        declare(ws);
        VerifyCommand command = new VerifyCommand(ws, store(), plan -> {
            throw new AssertionError("an untrusted profile must never execute");
        });
        Context gateTrap = ctxWithGate(new AtomicReference<>(), () -> {
            throw new AssertionError("an untrusted profile must never reach the approval gate");
        });

        Result result = command.execute("ws:check", gateTrap);

        assertInstanceOf(Result.Error.class, result);
        assertTrue(text(result).contains("/profiles trust"), text(result));
    }

    @Test
    void trustedProfileRunsItsDeclaredArgvAfterApproval() throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        AtomicReference<CommandPlan> executed = new AtomicReference<>();
        VerifyCommand command = new VerifyCommand(ws, store, plan -> {
            executed.set(plan);
            return success(plan);
        });
        AtomicReference<String[]> approval = new AtomicReference<>();

        Result result = command.execute("check", ctxWithGate(approval, null));

        assertEquals("run workspace verification: ws:check", approval.get()[0]);
        assertTrue(approval.get()[1].startsWith("profile: ws:check\n"), approval.get()[1]);
        assertNotNull(executed.get(), "approved verification must execute");
        assertEquals("ws:check", executed.get().profileId());
        assertEquals(java.util.List.of("--no-daemon", "check"), executed.get().argv());
        assertTrue(text(result).contains("Verification passed: ws:check exited 0"), text(result));
        assertTrue(text(result).contains("all good"), text(result));
    }

    @Test
    void deniedApprovalExecutesNothing() throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        VerifyCommand command = new VerifyCommand(ws, store, plan -> {
            throw new AssertionError("a denied approval must never execute");
        });
        Context denying = Context.builder(new Config())
                .approvalGate((description, detail) -> false)
                .build();

        Result result = command.execute("ws:check", denying);

        assertTrue(text(result).contains("Verification cancelled. No command was executed."),
                text(result));
    }

    @Test
    void failingRunRendersExitCodeAndStderrTail() throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        VerifyCommand command = new VerifyCommand(ws, store, plan -> new CommandResult(
                plan, 3, 4200, false, false, "", "compile failed", false, false, false, ""));

        Result result = command.execute("ws:check", ctxWithGate(new AtomicReference<>(), null));

        assertTrue(text(result).contains("Verification failed: ws:check exited 3"), text(result));
        assertTrue(text(result).contains("compile failed"), text(result));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static CommandResult success(CommandPlan plan) {
        return new CommandResult(plan, 0, 1234, false, false, "all good", "", false, false, false, "");
    }

    private Path workspace() throws IOException {
        Path ws = tempDir.resolve("ws-" + System.nanoTime());
        Files.createDirectories(ws.resolve(".talos"));
        return ws;
    }

    private WorkspaceProfileTrustStore store() {
        return new WorkspaceProfileTrustStore(tempDir.resolve("trust"));
    }

    private void declare(Path ws) throws IOException {
        Files.writeString(ws.resolve("gradlew.bat"), "rem wrapper", StandardCharsets.UTF_8);
        Files.writeString(ws.resolve(".talos").resolve("profiles.yaml"),
                """
                profiles:
                  - id: check
                    executable: ./gradlew.bat
                    args: ["--no-daemon", "check"]
                """,
                StandardCharsets.UTF_8);
    }

    private static void pin(Path ws, WorkspaceProfileTrustStore store) {
        var loaded = WorkspaceCommandProfilesLoader.load(ws);
        store.pin(ws, loaded.declarationSha256(), loaded.profiles().profiles().size(),
                java.time.Instant.parse("2026-06-12T00:00:00Z"));
    }

    private static Context ctxWithoutGate() {
        return Context.builder(new Config()).build();
    }

    private static Context ctxWithGate(AtomicReference<String[]> captured, Runnable onApprove) {
        return Context.builder(new Config())
                .approvalGate(new ApprovalGate() {
                    @Override public boolean approve(String description, String detail) {
                        return approveFull(description, detail).isApproved();
                    }
                    @Override public ApprovalResponse approveFull(String description, String detail) {
                        if (onApprove != null) onApprove.run();
                        captured.set(new String[]{description, detail});
                        return ApprovalResponse.APPROVED;
                    }
                })
                .build();
    }

    private static String text(Result result) {
        return result.toString();
    }
}
