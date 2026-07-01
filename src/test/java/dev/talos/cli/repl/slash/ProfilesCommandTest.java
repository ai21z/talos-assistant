package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.CheckpointStore;
import dev.talos.runtime.checkpoint.FileBundleCheckpointStore;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.command.WorkspaceCommandProfilesLoader;
import dev.talos.runtime.command.WorkspaceProfileTrustStore;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** T791: /profiles list/trust/revoke - the explicit-consent surface of the trust chain. */
class ProfilesCommandTest {

    @TempDir Path tempDir;

    @org.junit.jupiter.api.AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

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
    void specSummaryDisambiguatesFromModelProfiles() throws IOException {
        ProfilesCommand command = new ProfilesCommand(workspace(), store());
        String summary = command.spec().summary();
        assertTrue(summary.contains("verification"), summary);
        assertTrue(summary.contains("not model/GGUF"), summary);
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

    @Test
    void configureDeclaresProfileAfterApprovalButDoesNotTrustIt() throws IOException {
        Path ws = workspace();
        Files.writeString(ws.resolve("gradlew.bat"), "rem wrapper", StandardCharsets.UTF_8);
        WorkspaceProfileTrustStore store = store();
        CheckpointService checkpoints = checkpoints();
        ProfilesCommand command = new ProfilesCommand(ws, store, checkpoints);
        String[] captured = new String[2];

        Result result = command.execute(
                "configure check --exec ./gradlew.bat --arg --no-daemon --arg check "
                        + "--timeout-ms 300000 --expected-write build/",
                ctxWithGate(captured, ApprovalResponse.APPROVED));

        assertEquals("configure workspace verification profile: ws:check", captured[0]);
        assertTrue(captured[1].contains("proposed .talos/profiles.yaml bytes"), captured[1]);
        assertTrue(captured[1].contains("declaration sha256: "), captured[1]);
        assertTrue(captured[1].contains("profiles:"), captured[1]);
        assertTrue(captured[1].contains("id: check"), captured[1]);
        assertTrue(captured[1].contains("args:"), captured[1]);

        Path declaration = ws.resolve(".talos").resolve("profiles.yaml");
        assertTrue(Files.isRegularFile(declaration));
        WorkspaceCommandProfilesLoader.Loaded loaded = WorkspaceCommandProfilesLoader.load(ws);
        assertTrue(loaded.profiles().valid(), loaded.profiles().rejectionReason());
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_NEW,
                store.state(ws, loaded),
                "UI declaration must not auto-pin trust");
        assertTrue(text(result).contains("declared but untrusted"), text(result));
        assertTrue(text(result).contains("/profiles trust"), text(result));

        var summaries = checkpoints.listSummaries(ws);
        assertEquals(1, summaries.size(), summaries.toString());
        assertTrue(summaries.getFirst().trigger().contains("profiles configure"), summaries.toString());
        var detail = checkpoints.describe(ws, summaries.getFirst().id()).orElseThrow();
        assertTrue(detail.entries().stream()
                        .anyMatch(entry -> ".talos/profiles.yaml".equals(entry.relativePath())),
                "checkpoint must capture the protected declaration path before writing");
    }

    @Test
    void configureCanEditExistingPinnedProfileButReturnsItToUntrustedChanged() throws IOException {
        Path ws = workspace();
        declare(ws);
        WorkspaceProfileTrustStore store = store();
        pin(ws, store);
        CheckpointService checkpoints = checkpoints();
        ProfilesCommand command = new ProfilesCommand(ws, store, checkpoints);

        Result result = command.execute(
                "configure check --exec ./gradlew.bat --arg test",
                ctxWithGate(new String[2], ApprovalResponse.APPROVED));

        WorkspaceCommandProfilesLoader.Loaded loaded = WorkspaceCommandProfilesLoader.load(ws);
        assertTrue(loaded.profiles().valid(), loaded.profiles().rejectionReason());
        assertEquals(java.util.List.of("test"), loaded.profiles().profiles().getFirst().fixedArgs());
        assertEquals(WorkspaceProfileTrustStore.TrustState.UNTRUSTED_CHANGED,
                store.state(ws, loaded),
                "editing declared bytes must invalidate the previous trust pin");
        assertTrue(text(result).contains("file changed since the last pin")
                        || text(result).contains("untrusted"),
                text(result));
    }

    @Test
    void configureDenialWritesNothingAndPinsNothing() throws IOException {
        Path ws = workspace();
        Files.writeString(ws.resolve("gradlew.bat"), "rem wrapper", StandardCharsets.UTF_8);
        WorkspaceProfileTrustStore store = store();
        CheckpointService checkpoints = checkpoints();
        ProfilesCommand command = new ProfilesCommand(ws, store, checkpoints);

        Result result = command.execute(
                "configure check --exec ./gradlew.bat --arg check",
                ctxWithGate(new String[2], ApprovalResponse.DENIED));

        assertTrue(text(result).contains("NOT written"), text(result));
        assertFalse(Files.exists(ws.resolve(".talos").resolve("profiles.yaml")));
        assertEquals(WorkspaceProfileTrustStore.TrustState.NONE_DECLARED,
                store.state(ws, WorkspaceCommandProfilesLoader.load(ws)));
        assertTrue(checkpoints.listSummaries(ws).isEmpty(),
                "denied declaration must not create a checkpoint");
    }

    @Test
    void configureCheckpointFailureBlocksWriteAfterApproval() throws IOException {
        Path ws = workspace();
        Files.writeString(ws.resolve("gradlew.bat"), "rem wrapper", StandardCharsets.UTF_8);
        AtomicInteger checkpointCalls = new AtomicInteger();
        ProfilesCommand command = new ProfilesCommand(
                ws,
                store(),
                new CheckpointService(new FailingCheckpointStore(checkpointCalls)));

        Result result = command.execute(
                "configure check --exec ./gradlew.bat --arg check",
                ctxWithGate(new String[2], ApprovalResponse.APPROVED));

        assertInstanceOf(Result.Error.class, result);
        assertTrue(text(result).contains("checkpoint"), text(result));
        assertEquals(1, checkpointCalls.get(), "checkpoint must be attempted after approval");
        assertFalse(Files.exists(ws.resolve(".talos").resolve("profiles.yaml")),
                "write must not happen after required checkpoint failure");
    }

    @Test
    void configureRecordsTraceEventWithHashApprovalChoiceAndTrustState() throws IOException {
        Path ws = workspace();
        Files.writeString(ws.resolve("gradlew.bat"), "rem wrapper", StandardCharsets.UTF_8);
        WorkspaceProfileTrustStore store = store();
        ProfilesCommand command = new ProfilesCommand(ws, store, checkpoints());
        LocalTurnTraceCapture.begin(
                "trc-profiles",
                "sid",
                1,
                "2026-06-30T00:00:00Z",
                "workspace-hash",
                "agent",
                "test",
                "model",
                "/profiles configure check");

        command.execute("configure check --exec ./gradlew.bat --arg check",
                ctxWithGate(new String[2], ApprovalResponse.APPROVED));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "WORKSPACE_PROFILE_DECLARATION_CONFIGURED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("ws:check", event.data().get("profileId"));
        assertEquals("APPROVED", event.data().get("approval"));
        assertEquals("UNTRUSTED_NEW", event.data().get("trustStateAfter"));
        assertEquals(64, String.valueOf(event.data().get("declarationSha256")).length());
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

    private CheckpointService checkpoints() {
        return new CheckpointService(new FileBundleCheckpointStore(
                tempDir.resolve("checkpoints-" + System.nanoTime())));
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

    private static final class FailingCheckpointStore implements CheckpointStore {
        private final AtomicInteger calls;

        FailingCheckpointStore(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public CheckpointCaptureResult captureBeforeMutation(
                Path workspace,
                Config config,
                ToolCall call,
                String traceId,
                int turnNumber
        ) {
            calls.incrementAndGet();
            return CheckpointCaptureResult.failure("simulated checkpoint failure");
        }

        @Override
        public CheckpointRestoreResult restore(Path workspace, String checkpointId) {
            return CheckpointRestoreResult.failure(checkpointId, "not implemented");
        }
    }
}
