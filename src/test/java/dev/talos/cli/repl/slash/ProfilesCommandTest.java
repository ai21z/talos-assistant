package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.command.WorkspaceCommandProfilesLoader;
import dev.talos.runtime.command.WorkspaceProfileTrustStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** T791: /profiles list/trust/revoke — the explicit-consent surface of the trust chain. */
class ProfilesCommandTest {

    @TempDir Path tempDir;

    @Test
    void listShowsStateAndGuidanceForEachLifecyclePhase() throws IOException {
        Path ws = workspace();
        WorkspaceProfileTrustStore store = store();
        ProfilesCommand command = new ProfilesCommand(ws, store);

        Result none = command.execute("list", ctxWithoutGate());
        assertTrue(text(none).contains("none declared"), text(none));

        declare(ws);
        Result untrusted = command.execute("list", ctxWithoutGate());
        assertTrue(text(untrusted).contains("untrusted (never pinned)"), text(untrusted));
        assertTrue(text(untrusted).contains("/profiles trust"), text(untrusted));
        assertTrue(text(untrusted).contains("ws:check"), text(untrusted));

        pin(ws, store);
        Result trusted = command.execute("list", ctxWithoutGate());
        assertTrue(text(trusted).contains("state: trusted"), text(trusted));
        assertTrue(text(trusted).contains("/verify ws:<id>"), text(trusted));
    }

    @Test
    void trustShowsResolvedProfilesAndShaThenPinsOnApproval() throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        ProfilesCommand command = new ProfilesCommand(ws, store);
        String[] captured = new String[2];

        Result result = command.execute("trust", ctxWithGate(captured, ApprovalResponse.APPROVED));

        assertEquals("trust workspace verification profiles", captured[0]);
        assertTrue(captured[1].contains("ws:check"), captured[1]);
        assertTrue(captured[1].contains(
                        ws.resolve("gradlew.bat").toAbsolutePath().normalize().toString()),
                "the consent detail must show the resolved absolute executable: " + captured[1]);
        assertTrue(captured[1].contains("declaration sha256: "), captured[1]);
        assertTrue(text(result).contains("Pinned 1 workspace profile(s)"), text(result));
        assertEquals(WorkspaceProfileTrustStore.TrustState.TRUSTED,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));
    }

    @Test
    void trustDenialPinsNothing() throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        ProfilesCommand command = new ProfilesCommand(ws, store);

        Result result = command.execute("trust", ctxWithGate(new String[2], ApprovalResponse.DENIED));

        assertTrue(text(result).contains("NOT trusted"), text(result));
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_NEW,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));
    }

    @Test
    void revokeReturnsAPinnedDeclarationToUntrusted() throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        ProfilesCommand command = new ProfilesCommand(ws, store);

        Result result = command.execute("revoke", ctxWithoutGate());

        assertTrue(text(result).contains("trust revoked"), text(result));
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_NEW,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));
    }

    @Test
    void invalidDeclarationCannotBeTrusted() throws IOException {
        Path ws = workspace();
        Files.writeString(ws.resolve(".talos").resolve("profiles.yaml"),
                "profiles: [unclosed", StandardCharsets.UTF_8);
        ProfilesCommand command = new ProfilesCommand(ws, store());

        Result result = command.execute("trust", ctxWithoutGate());

        assertInstanceOf(Result.Error.class, result);
        assertTrue(text(result).contains("cannot be trusted"), text(result));
    }

    // ── helpers ──────────────────────────────────────────────────────────

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

    private static Context ctxWithGate(String[] captured, ApprovalResponse response) {
        return Context.builder(new Config())
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

    private static String text(Result result) {
        return result.toString();
    }
}
