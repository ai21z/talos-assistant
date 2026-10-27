package dev.talos.runtime.intent;

import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SequentialPostfixOwnershipTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Edit index.html so Alice wants Bob / the build bot to update style.css.",
            "Edit index.html so update style.css, or so Alice says.",
            "Edit index.html so the report says to update style.css.",
            "Edit index.html so consider whether to update style.css.",
            "Edit index.html so maybe update style.css.",
            "Edit index.html so update style.css or alternate.css."
    })
    void aSeparatelyOwnedChildCannotRestrictOrPromoteItsParent(String request) {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(Set.of("index.html"), resolution.independentAuthorizedContentMutationPaths(), resolution.toString());
        assertFalse(resolution.independentAuthorizedContentMutationPaths().contains("style.css"));
        assertDoesNotThrow(() -> TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
    }

    @Test
    void plainChildKeepsItsOwnIndependentAuthority() {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(
                "Edit index.html so it works, and update style.css.");
        assertEquals(Set.of("index.html", "style.css"),
                resolution.independentAuthorizedContentMutationPaths(), resolution.toString());
        assertDoesNotThrow(() -> TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
    }

    @Test
    void opaquePrefixRetainsEstablishedPrimaryDelegationWithoutASequentialGrant() {
        String request = "\u0130 Edit index.html so it works, and update ./style.css too.";
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(Set.of("index.html", "style.css"),
                resolution.independentAuthorizedContentMutationPaths(), resolution.toString());
        assertTrue(resolution.sequentialMutationPlan().occurrenceDecisions().stream()
                .noneMatch(decision -> decision.path().equals("index.html")
                        && decision.authority() == SequentialMutationClausePlan.Authority.REQUIRED));
        assertDoesNotThrow(() -> TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"maybe", "perhaps", "optionally", "possibly", "potentially"})
    void laterParentDoesNotOwnItsDirectChildsOptionalAdverb(String modifier) {
        String request = "Edit anchor.js. Edit parent.js so " + modifier + " update child.js.";
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(Set.of("anchor.js", "parent.js"),
                resolution.independentAuthorizedContentMutationPaths(), resolution.toString());
        assertFalse(resolution.independentAuthorizedContentMutationPaths().contains("child.js"));
        assertDoesNotThrow(() -> TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Edit index.html only if approved so it works, but do not delete style.css.",
            "Edit index.html so #b updates #s when clicked, only if approved, but do not delete style.css.",
            "Edit index.html so #b updates #s when clicked, provided that approval exists, but do not delete style.css.",
            "Edit index.html so #b updates #s when clicked, until approval exists, but do not delete style.css."
    })
    void childProhibitionCannotEraseTheParentsActualCondition(String request) {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertTrue(resolution.independentAuthorizedContentMutationPaths().isEmpty(), resolution.toString());
        assertFalse(resolution.contract().expectedTargets().contains("index.html"), resolution.toString());
        assertTrue(resolution.contract().forbiddenTargets().contains("style.css"), resolution.toString());
        assertDoesNotThrow(() -> TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
    }

    @Test
    void childConditionRemainsLocalToItsExactAction() {
        String request = "Edit root.js so it works, and update launch.js  "
                + "so #b updates #s when clicked, if approved.";
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(Set.of("root.js"), resolution.independentAuthorizedContentMutationPaths(), resolution.toString());
        assertDoesNotThrow(() -> TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
    }

    @Test
    void typedTailReceiptsCannotBeAppliedToDifferentCanonicalBytes() {
        String request = "Edit index.html so maybe update style.css.";
        var targets = Set.of("index.html", "style.css");
        var tail = CoordinatedMutationTailParser.parse(request, targets);
        assertThrows(IllegalArgumentException.class,
                () -> SequentialMutationClauseParser.parse("Please " + request, targets, tail));
    }

    @ParameterizedTest
    @ValueSource(strings = {"; ", "\n", "\r\n", "\u2028", "\u2029"})
    void independentConditionalChildDoesNotOwnTheParentsHeader(String separator) {
        String request = "Edit index.html so it works" + separator + "if possible, update style.css.";
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertEquals(Set.of("index.html"), resolution.independentAuthorizedContentMutationPaths(), resolution.toString());
        assertDoesNotThrow(() -> TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "If approved, edit index.html so it works, but update style.css.",
            "If possible, edit index.html so it works, but update style.css."
    })
    void anIndependentChildCannotAuthorizeARestrictedParent(String request, @TempDir Path directory)
            throws Exception {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertTrue(resolution.independentAuthorizedContentMutationPaths().isEmpty(), resolution.toString());
        assertFalse(resolution.contract().mutationAllowed(), resolution.toString());
        assertFalse(resolution.contract().expectedTargets().contains("index.html"), resolution.toString());
        assertTrue(resolution.mutationTailPlan().decisions().stream().anyMatch(decision ->
                decision.path().equals("style.css")
                        && decision.authority() == CoordinatedMutationTailPlan.Authority.REQUIRED));
        assertEquals(Set.of("style.css"), resolution.contract().expectedTargets());
        assertTrue(resolution.activeLexicalPaths().contains("style.css"));
        TurnPolicyTrace trace = assertDoesNotThrow(() ->
                TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
        assertTrue(trace.lexicalDecisions().stream().anyMatch(decision ->
                decision.path().equals("style.css") && decision.authority().equals("REQUIRED")
                        && decision.selectedForPath() && !decision.activeInResolution()));
        assertTrue(trace.rolefulTargets().stream().anyMatch(target ->
                target.path().equals("style.css") && target.role().equals("MUST_READ")));
        assertFalse(trace.mutationAllowed());
        JsonSessionStore store = new JsonSessionStore(directory);
        String sessionId = "conditional-parent-evidence";
        try (var writer = store.acquireSessionWriter(sessionId)) {
            writer.appendTurn(sessionId, new TurnRecord(1, Instant.parse("2026-09-16T00:00:00Z"),
                    1, request, "No mutation was authorized.", List.of(), 0, 0, 0, "", "ok", trace));
        }
        assertEquals(trace.lexicalDecisions(), store.loadTurns(sessionId).getFirst().policyTrace()
                .lexicalDecisions());
        assertEquals(trace.rolefulTargets(), store.loadTurns(sessionId).getFirst().policyTrace()
                .rolefulTargets());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var forged = json.readTree(Files.readString(directory.resolve(sessionId + ".turns.jsonl")));
        boolean changed = false;
        for (var decision : forged.path("policyTrace").path("lexicalDecisions")) {
            if (decision.path("path").asText().equals("style.css")
                    && decision.path("selectedForPath").asBoolean()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) decision).put("activeInResolution", true);
                changed = true;
            }
        }
        assertTrue(changed);
        Files.writeString(directory.resolve("forged-active.turns.jsonl"), json.writeValueAsString(forged) + "\n");
        assertTrue(store.loadTurns("forged-active").isEmpty());
    }

    @Test
    void anIndependentChildCannotAuthorizeAReportedParent() {
        TaskResolution resolution = TaskContractResolver.resolveFromUserRequest(
                "The report says to edit index.html so it works, but update style.css.");
        assertEquals(Set.of("style.css"), resolution.independentAuthorizedContentMutationPaths(),
                resolution.toString());
        assertFalse(resolution.contract().expectedTargets().contains("index.html"), resolution.toString());
        assertDoesNotThrow(() -> TurnPolicyTrace.from(resolution, "APPLY", List.of(), List.of()));
    }
}
