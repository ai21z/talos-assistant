package dev.talos.runtime;

import dev.talos.core.io.AtomicFileWriter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/** Child-JVM holder intentionally terminated without closing its writer lease. */
public final class JsonSessionStoreWriterLeaseCrashProcessMain {
    private JsonSessionStoreWriterLeaseCrashProcessMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) System.exit(64);
        Path sessions = Path.of(args[0]).toAbsolutePath().normalize();
        Path workspace = Path.of(args[1]).toAbsolutePath().normalize();
        Instant createdAt = Instant.parse(args[2]);
        byte[] entropy = HexFormat.of().parseHex(args[3]);
        Path ready = Path.of(args[4]).toAbsolutePath().normalize();
        boolean leavePartialTurn = "partial-turn".equals(args[5]);

        JsonSessionStore store = new JsonSessionStore(
                sessions,
                new AtomicFileWriter(),
                () -> entropy);
        SessionWriterLease lease = store.reserveNewSession(workspace, createdAt);
        lease.save(new SessionData(
                lease.sessionId(),
                workspace.toString(),
                "crash-holder-snapshot",
                0,
                createdAt,
                List.of(),
                "test-model"));
        if (leavePartialTurn) {
            lease.appendTurn(lease.sessionId(), new TurnRecord(
                    1,
                    createdAt,
                    1L,
                    "complete request",
                    "complete answer",
                    List.of(),
                    0,
                    0,
                    0,
                    "",
                    "ok",
                    TurnPolicyTrace.empty(),
                    ""));
            Files.writeString(
                    sessions.resolve(lease.sessionId() + ".turns.jsonl"),
                    "{\"turnNumber\":",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
        }
        Files.createDirectories(ready.getParent());
        Files.writeString(
                ready,
                lease.sessionId() + "\n",
                StandardCharsets.UTF_8);

        // The parent forcibly terminates this process. Deliberately retain
        // strong references to the store and lease and never close either.
        while (store != null && lease.isOpen()) {
            Thread.sleep(1_000L);
        }
        System.exit(65);
    }
}
