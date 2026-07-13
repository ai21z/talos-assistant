package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileLookupAnswerGuardTest {
    private static final String REQUEST =
            "Locate the service port, name the file, and quote only the relevant key and value. "
                    + "Do not edit anything.";

    @Test
    void broadNegativeClaimIsReplacedWithBoundedSourceDisclosure() {
        String answer = FileLookupAnswerGuard.boundNegativeClaimIfNeeded(
                "The service port information was not found in the provided files.",
                lookupPlan(),
                loopWith(readOutcome("src/App.java", List.of("src/App.java"))));

        assertTrue(answer.startsWith(FileLookupAnswerGuard.PREFIX), answer);
        assertTrue(answer.contains("src/App.java"), answer);
        assertTrue(answer.contains("does not prove"), answer);
        assertFalse(answer.contains("not found in the provided files"), answer);
    }

    @Test
    void positiveSourceBackedAnswerIsUnchanged() {
        String answer = "The service port is in config.json: \"port\" : 8080.";

        assertEquals(
                answer,
                FileLookupAnswerGuard.boundNegativeClaimIfNeeded(
                        answer,
                        lookupPlan(),
                        loopWith(readOutcome("config.json", List.of("config.json")))));
    }

    @Test
    void positiveSourceBackedAnswerMayQuoteNotFoundAsAFieldValue() {
        String answer = "config.json contains \"status\": \"not found\" and \"port\": 8080.";

        assertEquals(
                answer,
                FileLookupAnswerGuard.boundNegativeClaimIfNeeded(
                        answer,
                        lookupPlan(),
                        loopWith(readOutcome("config.json", List.of("config.json")))));
    }

    @Test
    void positiveSourceBackedAnswerMayQuoteNegativePhraseAsAFieldValue() {
        String answer = "config.json contains \"message\": \"No relevant key-value pair was found\" "
                + "and \"port\": 8080.";

        assertEquals(
                answer,
                FileLookupAnswerGuard.boundNegativeClaimIfNeeded(
                        answer,
                        lookupPlan(),
                        loopWith(readOutcome("config.json", List.of("config.json")))));
    }

    @Test
    void firstPersonBroadNegativeClaimIsReplaced() {
        String answer = FileLookupAnswerGuard.boundNegativeClaimIfNeeded(
                "I couldn't locate the service port in the workspace.",
                lookupPlan(),
                loopWith(readOutcome("src/App.java", List.of("src/App.java"))));

        assertTrue(answer.startsWith(FileLookupAnswerGuard.PREFIX), answer);
    }

    @Test
    void successfulGrepMatchesCannotBeReportedAsNoMatchingLines() {
        String fieldAnswer = "The grep search for port in JavaScript files returned no matching lines, "
                + "indicating that the service port is not defined in any of those files. "
                + "No relevant key\u2011value pair for the port was found.";

        String answer = FileLookupAnswerGuard.boundNegativeClaimIfNeeded(
                fieldAnswer,
                lookupPlan(),
                loopWith(grepOutcome(List.of("script.js", "scripts.js"))));

        assertTrue(answer.startsWith(FileLookupAnswerGuard.PREFIX), answer);
        assertTrue(answer.contains("script.js"), answer);
        assertTrue(answer.contains("scripts.js"), answer);
        assertTrue(answer.contains("does not prove"), answer);
        assertFalse(answer.contains("returned no matching lines"), answer);
        assertFalse(answer.contains("No relevant key\u2011value pair"), answer);
    }

    @Test
    void unsupportedNoRelevantKeyValuePairClaimIsBounded() {
        String answer = FileLookupAnswerGuard.boundNegativeClaimIfNeeded(
                "No relevant key-value pair for the requested setting was found.",
                lookupPlan(),
                loopWith(grepOutcome(List.of())));

        assertTrue(answer.startsWith(FileLookupAnswerGuard.PREFIX), answer);
        assertTrue(answer.contains("did not establish"), answer);
    }

    @Test
    void protectedSourcePathIsCollapsedInBoundedDisclosure() {
        String answer = FileLookupAnswerGuard.boundNegativeClaimIfNeeded(
                "The requested value was not found.",
                lookupPlan(),
                loopWith(readOutcome(".env", List.of(".env"))));

        assertTrue(answer.contains("<protected-path>"), answer);
        assertFalse(answer.contains(".env"), answer);
    }

    private static CurrentTurnPlan lookupPlan() {
        return CurrentTurnPlan.create(
                TaskContractResolver.fromUserRequest(REQUEST),
                ExecutionPhase.INSPECT,
                List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve"),
                List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.retrieve"),
                List.of());
    }

    private static ToolCallLoop.LoopResult loopWith(ToolCallLoop.ToolOutcome outcome) {
        return new ToolCallLoop.LoopResult(
                "", 1, 1,
                List.of(outcome.toolName()), List.of(),
                0, 0, false, 0, List.of(outcome.pathHint()),
                0, 0, 0, 0,
                List.of(outcome));
    }

    private static ToolCallLoop.ToolOutcome readOutcome(String path, List<String> sourcePaths) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file", path, true, false, false,
                "read source", "", null, "", null,
                ToolMutationEvidence.none(), ToolFailureReason.NONE, sourcePaths);
    }

    private static ToolCallLoop.ToolOutcome grepOutcome(List<String> sourcePaths) {
        return new ToolCallLoop.ToolOutcome(
                "talos.grep", ".", true, false, false,
                sourcePaths.isEmpty() ? "No matches found." : "Found 2 match(es).", "", null, "", null,
                ToolMutationEvidence.none(), ToolFailureReason.NONE, sourcePaths);
    }
}
