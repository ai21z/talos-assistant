package dev.talos.runtime;

import dev.talos.core.io.AtomicFileWriter;
import dev.talos.runtime.trace.LocalTurnTrace;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/** Child-JVM entry point for simultaneous deterministic T1162 allocations. */
public final class JsonSessionStoreReservationProcessMain {
    private JsonSessionStoreReservationProcessMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 7) System.exit(64);
        Path sessions = Path.of(args[0]).toAbsolutePath().normalize();
        Path workspace = Path.of(args[1]).toAbsolutePath().normalize();
        Instant createdAt = Instant.parse(args[2]);
        Queue<byte[]> entropy = new ArrayDeque<>();
        for (String value : args[3].split(",")) {
            entropy.add(HexFormat.of().parseHex(value));
        }
        String label = args[4];
        Path ready = Path.of(args[5]).toAbsolutePath().normalize();
        Path release = Path.of(args[6]).toAbsolutePath().normalize();

        AtomicFileWriter atomicFileWriter = new AtomicFileWriter();
        JsonSessionStore store = new JsonSessionStore(
                sessions,
                atomicFileWriter,
                () -> {
                    byte[] next = entropy.poll();
                    if (next == null) {
                        throw new IllegalStateException("injected entropy exhausted");
                    }
                    return next;
                });
        try (SessionWriterLease lease = store.reserveNewSession(workspace, createdAt)) {
            String sessionId = lease.sessionId();
            lease.save(new SessionData(
                    sessionId,
                    workspace.toString(),
                    label + "-snapshot",
                    1,
                    createdAt,
                    List.of(new SessionData.Turn("user", label + "-user", "ok")),
                    "test-model"));
            lease.appendTurn(sessionId, new TurnRecord(
                    1,
                    createdAt,
                    1L,
                    label + "-request",
                    label + "-answer",
                    List.of(),
                    0,
                    0,
                    0,
                    "",
                    "ok",
                    TurnPolicyTrace.empty(),
                    ""));
            lease.saveTrace(
                    sessionId,
                    LocalTurnTrace.builder(
                                    label + "-trace",
                                    sessionId,
                                    1,
                                    createdAt.toString())
                            .workspaceHash("workspace-hash")
                            .mode("ask")
                            .model("llama.cpp", "test-model")
                            .build());

            Files.createDirectories(ready.getParent());
            atomicFileWriter.replace(
                    ready,
                    (sessionId + "\n").getBytes(StandardCharsets.UTF_8),
                    AtomicFileWriter.Preimage.absent());
            awaitFile(release, Duration.ofSeconds(20));
        } finally {
            store.close();
        }
    }

    private static void awaitFile(Path path, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("release timed out");
            }
            Thread.sleep(10L);
        }
    }
}
