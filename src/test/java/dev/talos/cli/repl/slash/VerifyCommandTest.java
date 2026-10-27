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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"revoke", "edit"})
    void directVerifyRejectsTrustChangesDuringApproval(String change) throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        VerifyCommand command = new VerifyCommand(ws, store, plan -> {
            throw new AssertionError("trust changed during approval must stop before execution");
        });
        Result result = command.execute("ws:check", ctxWithGate(new AtomicReference<>(), () -> {
            if (change.equals("revoke")) {
                store.unpin(ws);
            } else {
                try {
                    Path declaration = ws.resolve(".talos/profiles.yaml");
                    Files.writeString(declaration, Files.readString(declaration) + "# changed\n");
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        }));
        assertInstanceOf(Result.Error.class, result);
        assertTrue(text(result).contains("No command was executed"), text(result));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void timeoutRetainsUnconfirmedCleanupDiagnostic(boolean terminationAttempted) throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        String diagnostic = "Command process termination did not settle; process-tree quiescence is unconfirmed.";
        VerifyCommand command = new VerifyCommand(ws, store, plan -> new CommandResult(
                plan, -1, 1_001, true, terminationAttempted, "", "", false, false, false, diagnostic));

        String message = text(command.execute("ws:check", ctxWithGate(new AtomicReference<>(), null)));

        assertTrue(message.contains("Verification timed out: ws:check"), message);
        assertTrue(message.contains(diagnostic), message);
        assertEquals(terminationAttempted, message.contains("termination attempted"));
        assertFalse(message.contains("Verification passed"));
        assertFalse(message.contains("process killed"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void timeoutWithoutCleanupDiagnosticDoesNotInventOne(boolean terminationAttempted) throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        VerifyCommand command = new VerifyCommand(ws, store, plan -> new CommandResult(
                plan, -1, 1_001, true, terminationAttempted, "", "", false, false, false, ""));

        String message = text(command.execute("ws:check", ctxWithGate(new AtomicReference<>(), null)));

        assertTrue(message.contains("Verification timed out: ws:check"), message);
        assertEquals(terminationAttempted, message.contains("termination attempted"));
        assertFalse(message.contains("diagnostic:"));
        assertFalse(message.contains("process killed"));
    }

    @Test
    void timeoutDiagnosticIsSanitizedAndBoundedWithoutLosingUncertaintyTail() throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        String diagnostic = "discarded-prefix " + "x".repeat(3_000)
                + " API_TOKEN=fixture-secret; process-tree quiescence is unconfirmed.";
        VerifyCommand command = new VerifyCommand(ws, store, plan -> new CommandResult(
                plan, -1, 1_001, true, true, "", "", false, false, false, diagnostic));

        String message = text(command.execute("ws:check", ctxWithGate(new AtomicReference<>(), null)));

        assertTrue(message.contains("Verification timed out: ws:check"));
        assertTrue(message.contains("process-tree quiescence is unconfirmed."), message);
        assertTrue(message.contains("diagnostic:\n..."));
        assertFalse(message.contains("fixture-secret"));
        assertFalse(message.contains("discarded-prefix"));
        assertTrue(message.length() < 2_200, "diagnostic must not include the full unbounded message");
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
