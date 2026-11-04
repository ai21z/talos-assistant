package dev.talos.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.intent.SequentialMutationClausePlan;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A typed prohibition clause is persisted under its family's region kind, so stored sessions keep their manifest. */
class ProhibitionPolarityPersistenceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HEAD_SESSIONS = "/dev/talos/runtime/prohibition-polarity-head-sessions/";
    private static final int HEAD_SESSION_COUNT = 10;

    @Test
    void prohibitionTurnsRoundTripWithFamilyRegionKinds(@TempDir Path directory) throws IOException {
        List<String> requests = List.of(
                "List the files. Don't edit or delete anything.",
                "Summarize notes.md. Don't delete it.",
                "Edit index.html. Create style.css. Do not touch it.");
        int ordinal = 0;
        for (String request : requests) {
            String sessionId = "prohibition-polarity-" + ordinal++;
            TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
            assertTrue(resolution.sequentialMutationPlan().ownedRegions().stream()
                    .anyMatch(region -> SequentialMutationClausePlan.isProhibitionKind(region.kind())), request);
            JsonSessionStore store = new JsonSessionStore(directory);
            try (SessionWriterLease writer = store.acquireSessionWriter(sessionId)) {
                writer.appendTurn(sessionId, turnRecord(request, resolution));
            }

            List<TurnRecord> loaded = store.loadTurns(sessionId);

            assertEquals(1, loaded.size(), request);
            assertEquals(request, loaded.getFirst().userInput(), request);
        }
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("PROHIBITED_"), file.toString());
            }
        }
    }

    /** The fixtures were written by the installed build of e9eab050, before prohibition polarity was typed. */
    @Test
    void sessionsSavedBeforeTypedPolarityStillLoad(@TempDir Path directory) throws IOException {
        for (int index = 0; index < HEAD_SESSION_COUNT; index++) {
            String sessionId = "head-prohibition-" + index;
            String name = sessionId + ".turns.jsonl";
            try (InputStream fixture = getClass().getResourceAsStream(HEAD_SESSIONS + name)) {
                assertTrue(fixture != null, name);
                Files.copy(fixture, directory.resolve(name));
            }
            String request = JSON.readTree(Files.readAllLines(directory.resolve(name), StandardCharsets.UTF_8)
                    .getFirst()).get("userInput").asText();

            List<TurnRecord> loaded = new JsonSessionStore(directory).loadTurns(sessionId);

            assertEquals(1, loaded.size(), request);
            assertEquals(request, loaded.getFirst().userInput(), request);
        }
    }

    private static TurnRecord turnRecord(String request, TaskResolution resolution) {
        return new TurnRecord(
                1,
                Instant.parse("2026-11-03T00:00:00Z"),
                1,
                request,
                "answer",
                List.of(),
                0,
                0,
                0,
                "",
                "ok",
                TurnPolicyTrace.from(resolution, "INSPECT", List.of("talos.read_file"),
                        List.of("talos.read_file")));
    }
}
