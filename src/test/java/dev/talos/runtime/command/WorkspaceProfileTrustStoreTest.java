package dev.talos.runtime.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T789: trust pins are explicit consent records over declaration bytes —
 * never written implicitly, any byte change invalidates them, and a
 * corrupted pin fails closed to untrusted.
 */
class WorkspaceProfileTrustStoreTest {

    @TempDir Path tempDir;

    @Test
    void statesProgressNoneDeclaredToTrustedToChangedAcrossTheLifecycle() throws IOException {
        Path ws = workspace();
        WorkspaceProfileTrustStore store =
                new WorkspaceProfileTrustStore(tempDir.resolve("trust"));

        // No declaration at all.
        assertEquals(WorkspaceProfileTrustStore.TrustState.NONE_DECLARED,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));

        // A fresh, valid, never-pinned declaration.
        declare(ws, "profiles:\n  - id: check\n    executable: npm\n");
        var loaded = WorkspaceCommandProfilesLoader.load(ws);
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_NEW,
                store.state(ws, loaded));

        // Explicit consent pins the exact bytes.
        store.pin(ws, loaded.declarationSha256(),
                loaded.profiles().profiles().size(), Instant.parse("2026-06-12T00:00:00Z"));
        assertEquals(WorkspaceProfileTrustStore.TrustState.TRUSTED,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));

        // Any byte change demands re-consent.
        declare(ws, "profiles:\n  - id: check\n    executable: npm\n# changed\n");
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_CHANGED,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));

        // Revoking trust returns to never-pinned.
        store.unpin(ws);
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_NEW,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));
    }

    @Test
    void invalidDeclarationsAreInvalidRegardlessOfAnyPin() throws IOException {
        Path ws = workspace();
        WorkspaceProfileTrustStore store =
                new WorkspaceProfileTrustStore(tempDir.resolve("trust"));
        declare(ws, "profiles: [unclosed");

        assertEquals(WorkspaceProfileTrustStore.TrustState.INVALID,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));
    }

    @Test
    void pinRoundTripsAndRecordsTheConsentFields() throws IOException {
        Path ws = workspace();
        declare(ws, "profiles:\n  - id: check\n    executable: npm\n");
        var loaded = WorkspaceCommandProfilesLoader.load(ws);
        WorkspaceProfileTrustStore store =
                new WorkspaceProfileTrustStore(tempDir.resolve("trust"));

        store.pin(ws, loaded.declarationSha256(), 1, Instant.parse("2026-06-12T01:02:03Z"));
        Optional<WorkspaceProfileTrustStore.Pin> pin =
                store.readPin(WorkspaceProfileTrustStore.workspaceId(ws));

        assertTrue(pin.isPresent());
        assertEquals(1, pin.get().schemaVersion());
        assertEquals(loaded.declarationSha256(), pin.get().declarationSha256());
        assertEquals("2026-06-12T01:02:03Z", pin.get().pinnedAt());
        assertEquals(1, pin.get().profileCount());
    }

    @Test
    void corruptedOrForeignPinFilesFailClosedToUntrusted() throws IOException {
        Path ws = workspace();
        declare(ws, "profiles:\n  - id: check\n    executable: npm\n");
        Path trustDir = tempDir.resolve("trust");
        WorkspaceProfileTrustStore store = new WorkspaceProfileTrustStore(trustDir);
        String workspaceId = WorkspaceProfileTrustStore.workspaceId(ws);
        Files.createDirectories(trustDir);

        Files.writeString(trustDir.resolve(workspaceId + ".json"), "{not json",
                StandardCharsets.UTF_8);
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_NEW,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)),
                "corrupted pin must fail closed to untrusted");

        // A pin recorded for a DIFFERENT workspace id must not transfer.
        Files.writeString(trustDir.resolve(workspaceId + ".json"),
                "{\"schemaVersion\":1,\"workspaceId\":\"someoneelse\","
                        + "\"declarationSha256\":\"abc\",\"pinnedAt\":\"x\",\"profileCount\":1}",
                StandardCharsets.UTF_8);
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_NEW,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));
    }

    private Path workspace() {
        Path ws = tempDir.resolve("ws-" + System.nanoTime());
        try {
            Files.createDirectories(ws.resolve(".talos"));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return ws;
    }

    private static void declare(Path workspace, String yaml) throws IOException {
        Files.writeString(workspace.resolve(".talos").resolve("profiles.yaml"),
                yaml, StandardCharsets.UTF_8);
    }
}
