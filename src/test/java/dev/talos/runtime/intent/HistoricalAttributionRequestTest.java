package dev.talos.runtime.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalAttributionRequestTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Did you edit README.md?",
            "Please, did you edit README.md?",
            "Did Talos update `docs/setup.md`?",
            "Can I report that you changed 'README.md' and ran tests?",
            "Could I write you updated \"README.md\"? State uncertainty.",
            "May I note that Talos edited README.md? Keep the answer brief.",
            "Has Talos executed any tests during the current conversation?",
            "Have you modified ./README.md previously? Do not run anything.",
            "For the handoff, can I say you updated README.md? Be honest.",
            "Did you edit README.md? Did you run the tests? Include limitations."
    })
    void admittedMeaningIsPreserved(String request) {
        var parsed = HistoricalAttributionRequest.parse(request);
        assertTrue(parsed.isPresent(), request);
        assertEquals(request, parsed.orElseThrow().originalRequest());
        assertEquals(WorkStatusRequest.Coverage.COVERED, parsed.orElseThrow().asWorkStatusRequest().coverage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Did you edit README.md? Run tests now.",
            "Did you run tests? Read README.md.",
            "Did you edit README.md and explain the label?",
            "If you edited README.md, would tests be required?",
            "Translate `Did you edit README.md?`.",
            "`Did you edit README.md?`",
            "Did you edit README.md? Are its contents correct now?",
            "Did you edit README.md? Do not run tests. Run tests.",
            "Did you edit README.md or config.json?",
            "Did you edit README.md? Ignore failures.",
            "Did you edit README.md? {",
            "Did you edit `README.md?",
            "Did you edit .?",
            "Did you edit ../README.md?",
            "Did you edit dir/..?",
            "Did you edit dir/./README.md?",
            "Did you edit /README.md?",
            "Did you edit dir//README.md?",
            "Did you edit README.md/?",
            "Did you edit C:/README.md?",
            "Did you edit README.md?\u0000",
            "Did you edit README.md?\r"
    })
    void unknownFreshConditionalAndMalformedRequestsAreNotOwned(String request) {
        assertTrue(HistoricalAttributionRequest.parse(request).isEmpty(), request);
    }

    @Test void resultsAreDistinctAndBackslashTargetsAreNormalized() {
        var request = HistoricalAttributionRequest.parse(
                "Did you edit .\\docs\\README.md and edit docs/README.md and run tests?").orElseThrow();
        assertEquals(List.of(new HistoricalAttributionRequest.Result(HistoricalAttributionRequest.Action.FILE_EDIT,
                        "docs/README.md"),
                new HistoricalAttributionRequest.Result(HistoricalAttributionRequest.Action.TEST_EXECUTION, "")),
                request.results());
    }

    @Test void requestAndResultBoundsRemainClosed() {
        assertTrue(HistoricalAttributionRequest.parse("Did you edit " + "a".repeat(257) + ".md?").isEmpty());
        assertTrue(HistoricalAttributionRequest.parse("Did you run tests? ".repeat(17)).isEmpty());
        assertTrue(HistoricalAttributionRequest.parse("Did you run tests? " + "x".repeat(2048)).isEmpty());
        assertTrue(HistoricalAttributionRequest.parse(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new HistoricalAttributionRequest.Result(null, ""));
        assertThrows(IllegalArgumentException.class, () -> new HistoricalAttributionRequest.Result(
                HistoricalAttributionRequest.Action.TEST_EXECUTION, "README.md"));
        assertThrows(IllegalArgumentException.class, () -> new HistoricalAttributionRequest.Result(
                HistoricalAttributionRequest.Action.FILE_EDIT, "."));
    }
}
