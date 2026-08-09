package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSessionStoreTurnLogInventoryObservationTest {

    @Test
    void exactInventoryDistinguishesObservedEmptyAndSortsStableIdentities(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace").toAbsolutePath().normalize();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        JsonSessionStore store = new JsonSessionStore(sessions);

        TurnLogInventoryObservation empty =
                store.observeTurnLogInventory(workspaceId);
        assertTrue(empty.exact());
        assertEquals(List.of(), empty.sessionIds());

        String later = workspaceId
                + "-20260804120001-22222222222222222222222222222222";
        String earlier = workspaceId
                + "-20260804120000-11111111111111111111111111111111";
        Files.writeString(
                sessions.resolve(later + ".turns.jsonl"),
                "",
                StandardCharsets.UTF_8);
        Files.writeString(
                sessions.resolve(earlier + ".turns.jsonl"),
                "",
                StandardCharsets.UTF_8);

        TurnLogInventoryObservation observed =
                store.observeTurnLogInventory(workspaceId);
        assertTrue(observed.exact());
        assertEquals(List.of(earlier, later), observed.sessionIds());

        assertThrows(
                NullPointerException.class,
                () -> TurnLogInventoryObservation.exact(null));
        TurnLogInventoryObservation unavailable =
                new TurnLogInventoryObservation(
                        TurnLogInventoryObservation.State.UNAVAILABLE,
                        List.of("must-not-leak"));
        assertFalse(unavailable.exact());
        assertEquals(List.of(), unavailable.sessionIds());
    }

    @Test
    void indeterminateRootAndMatchingNonRegularCompetitorAreUnavailable(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace").toAbsolutePath().normalize();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);

        Path indeterminateRoot = temp.resolve("indeterminate-root");
        JsonSessionStore indeterminate = new JsonSessionStore(indeterminateRoot);
        Files.delete(indeterminateRoot);
        Files.writeString(
                indeterminateRoot,
                "not-a-directory",
                StandardCharsets.UTF_8);
        assertUnavailable(indeterminate.observeTurnLogInventory(workspaceId));

        Path competitorRoot = temp.resolve("competitor-root");
        JsonSessionStore competitor = new JsonSessionStore(competitorRoot);
        String valid = workspaceId
                + "-20260804120000-11111111111111111111111111111111";
        String hidden = workspaceId
                + "-20260804120001-22222222222222222222222222222222";
        Files.writeString(
                competitorRoot.resolve(valid + ".turns.jsonl"),
                "",
                StandardCharsets.UTF_8);
        Files.createDirectory(
                competitorRoot.resolve(hidden + ".turns.jsonl"));
        assertUnavailable(competitor.observeTurnLogInventory(workspaceId));
    }

    @Test
    void inventoryEntryAndCandidateLimitsPassExactlyAndFailAtOneOver(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace").toAbsolutePath().normalize();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);

        Path entryRoot = temp.resolve("entry-root");
        Files.createDirectories(entryRoot);
        String only = workspaceId
                + "-20260804120000-11111111111111111111111111111111";
        Files.writeString(
                entryRoot.resolve(only + ".turns.jsonl"),
                "",
                StandardCharsets.UTF_8);
        Files.writeString(
                entryRoot.resolve("unrelated.txt"),
                "",
                StandardCharsets.UTF_8);
        JsonSessionStore exactEntries = new JsonSessionStore(
                entryRoot,
                limits(2, 2));
        assertEquals(
                List.of(only),
                exactEntries.observeTurnLogInventory(workspaceId).sessionIds());
        Files.writeString(
                entryRoot.resolve("one-over.txt"),
                "",
                StandardCharsets.UTF_8);
        assertUnavailable(
                exactEntries.observeTurnLogInventory(workspaceId));

        Path logRoot = temp.resolve("log-root");
        Files.createDirectories(logRoot);
        String first = workspaceId
                + "-20260804120000-33333333333333333333333333333333";
        String second = workspaceId
                + "-20260804120001-44444444444444444444444444444444";
        Files.writeString(
                logRoot.resolve(first + ".turns.jsonl"),
                "",
                StandardCharsets.UTF_8);
        Files.writeString(
                logRoot.resolve(second + ".turns.jsonl"),
                "",
                StandardCharsets.UTF_8);
        JsonSessionStore exactLogs = new JsonSessionStore(
                logRoot,
                limits(8, 2));
        assertEquals(
                List.of(first, second),
                exactLogs.observeTurnLogInventory(workspaceId).sessionIds());
        JsonSessionStore oneLogOver = new JsonSessionStore(
                logRoot,
                limits(8, 1));
        assertUnavailable(
                oneLogOver.observeTurnLogInventory(workspaceId));
    }

    @Test
    void soleNonCanonicalCaseSuffixCannotBecomeAnExactInventoryCoordinate(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace").toAbsolutePath().normalize();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String sessionId = workspaceId
                + "-20260804120000-55555555555555555555555555555555";
        Files.writeString(
                sessions.resolve(sessionId + ".TURNS.JSONL"),
                "not-json\n",
                StandardCharsets.UTF_8);

        JsonSessionStore store = new JsonSessionStore(sessions);
        assertUnavailable(store.observeTurnLogInventory(workspaceId));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void nonCanonicalCaseSuffixIsNotAddressableByTheCanonicalTailCoordinate(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace").toAbsolutePath().normalize();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String sessionId = workspaceId
                + "-20260804120000-66666666666666666666666666666666";
        Files.writeString(
                sessions.resolve(sessionId + ".TURNS.JSONL"),
                "not-json\n",
                StandardCharsets.UTF_8);

        JsonSessionStore store = new JsonSessionStore(sessions);
        assertEquals(
                TurnLogTailObservation.State.ABSENT,
                store.observeTurnLogTail(sessionId).state());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void caseVariantSuffixesForOneIdentityRemainTwoPhysicalCompetitors(
            @TempDir Path sessions
    ) throws Exception {
        Path workspace = sessions.resolve("workspace").toAbsolutePath().normalize();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace);
        String sessionId = workspaceId
                + "-20260804120000-55555555555555555555555555555555";
        Files.writeString(
                sessions.resolve(sessionId + ".turns.jsonl"),
                "",
                StandardCharsets.UTF_8);
        Files.writeString(
                sessions.resolve(sessionId + ".TURNS.JSONL"),
                "not-json\n",
                StandardCharsets.UTF_8);

        JsonSessionStore store = new JsonSessionStore(sessions);
        assertUnavailable(store.observeTurnLogInventory(workspaceId));
    }

    private static JsonSessionStore.DiagnosticObservationLimits limits(
            int maxEntries,
            int maxLogs
    ) {
        return new JsonSessionStore.DiagnosticObservationLimits(
                1024 * 1024,
                1024 * 1024,
                16,
                maxEntries,
                maxLogs);
    }

    private static void assertUnavailable(
            TurnLogInventoryObservation observation
    ) {
        assertEquals(
                TurnLogInventoryObservation.State.UNAVAILABLE,
                observation.state());
        assertFalse(observation.exact());
        assertEquals(List.of(), observation.sessionIds());
    }
}
