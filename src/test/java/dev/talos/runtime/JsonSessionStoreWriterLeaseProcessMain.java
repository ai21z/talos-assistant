package dev.talos.runtime;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Child-JVM entry point shared by the T1162 parent red and candidate tests. */
public final class JsonSessionStoreWriterLeaseProcessMain {
    private JsonSessionStoreWriterLeaseProcessMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) System.exit(64);
        String mode = args[0];
        Path sessions = Path.of(args[1]).toAbsolutePath().normalize();
        String sessionId = args[2];
        Path ready = Path.of(args[3]).toAbsolutePath().normalize();
        Path release = Path.of(args[4]).toAbsolutePath().normalize();
        if (mode.equals("allocate")) {
            System.out.println(JsonSessionStore.newSessionInstanceId(
                    Path.of(sessionId),
                    Instant.parse("2026-08-01T10:15:30Z")));
            return;
        }
        Files.createDirectories(sessions);

        JsonSessionStore store = new JsonSessionStore(sessions);
        AutoCloseable lease = null;
        try {
            SessionStore writer = store;
            try {
                Method acquire = JsonSessionStore.class.getMethod(
                        "acquireSessionWriter", String.class);
                Object acquired = acquire.invoke(store, sessionId);
                lease = (AutoCloseable) acquired;
                writer = (SessionStore) acquired;
            } catch (NoSuchMethodException ignored) {
                // Parent behavior: the unleased store remains directly writable.
            } catch (InvocationTargetException unavailable) {
                System.err.println(unavailable.getCause().getClass().getSimpleName());
                System.exit(23);
            }

            writer.save(new SessionData(
                    sessionId,
                    "/workspace",
                    mode,
                    1,
                    Instant.parse("2026-08-01T10:15:30Z"),
                    List.of()));
            if (mode.equals("hold")) {
                Files.createDirectories(ready.getParent());
                Files.writeString(ready, "ready\n", StandardCharsets.UTF_8);
                waitFor(release, Duration.ofSeconds(20));
            }
        } finally {
            if (lease != null) lease.close();
            try {
                JsonSessionStore.class.getMethod("close").invoke(store);
            } catch (NoSuchMethodException ignored) {
                // The parent store has no explicit lifecycle owner.
            }
        }
    }

    private static void waitFor(Path file, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(file)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("release timed out");
            }
            Thread.sleep(10L);
        }
    }
}
