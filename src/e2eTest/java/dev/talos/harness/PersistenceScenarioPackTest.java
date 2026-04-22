package dev.talos.harness;

import dev.talos.cli.repl.Result;
import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Persistence and replay scenario pack")
class PersistenceScenarioPackTest {

    @Test
    @DisplayName("[json-scenario:scenarios/07-replay-turn-log-fallback.json] 07: turn-log fallback replays only ok turns and skips error residue")
    void replayFromTurnLogFallback() {
        var loaded = JsonScenarioLoader.load("scenarios/07-replay-turn-log-fallback.json");
        String okUser = loaded.raw().path("okUserInput").asText("");
        String okAssistant = loaded.raw().path("okAssistantText").asText("");
        String errorUser = loaded.raw().path("errorUserInput").asText("");
        String errorAssistant = loaded.raw().path("errorAssistantText").asText("");

        List<TurnRecord> records = List.of(
                new TurnRecord(1, Instant.now(), 10L, okUser, okAssistant, List.of(), 0, 0, 0, "", "ok"),
                new TurnRecord(2, Instant.now(), 10L, errorUser, errorAssistant, List.of(), 0, 0, 0, "", "error")
        );

        try (var result = ScenarioRunner.replayTurnLogFallback(loaded.definition(), records)) {
            result.assertReplayedTurns(1)
                    .assertRestoredAssistantTurnContains(okAssistant);

            assertFalse(result.restoredAssistantTurns().stream().anyMatch(t -> t.contains(errorAssistant)),
                    "Error-tagged assistant residue must not be replayed into memory");
            assertEquals(2, result.turnLog().size(), "Both records stay on disk; only one is replayed");
        }
    }

    @Test
    @DisplayName("[json-scenario:scenarios/08-persistence-history-correctness.json] 08: persistence stores chrome-stripped assistant text in turn log and snapshot")
    void persistenceHistoryCorrectness() {
        var loaded = JsonScenarioLoader.load("scenarios/08-persistence-history-correctness.json");
        String rawAssistant = loaded.raw().path("rawAssistantText").asText("");
        String expectedAssistant = loaded.raw().path("expectedAssistantText").asText("");

        try (var result = ScenarioRunner.runWithPersistence(
                loaded.definition(),
                new Result.Streamed(rawAssistant, ""),
                TurnAudit.empty())) {
            result.assertSnapshotExists()
                    .assertTurnLogExists()
                    .assertTurnLogSize(1)
                    .assertTurnLogAssistantTextContains(expectedAssistant)
                    .assertTurnLogAssistantTextNotContains("[Used 1 tool(s)")
                    .assertTurnLogAssistantTextNotContains("✓ Wrote");

            assertNotNull(result.snapshot(), "Snapshot should be written");
            assertEquals(2, result.snapshot().turns().size(),
                    "Snapshot should contain the user turn and the stripped assistant turn");
            assertEquals(expectedAssistant, result.snapshot().turns().get(1).content());
            assertEquals("ok", result.snapshot().turns().get(1).status());
            assertTrue(result.turnLog().get(0).assistantText().equals(expectedAssistant),
                    "Turn log should persist the same stripped assistant text");
        }
    }
}
