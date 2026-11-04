package dev.talos.runtime.verification;

import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The complete replacement obligation decides a repeated old text only where every reading agrees (P17). */
class ReplacementOccurrenceFinalStateTest {
    private static final String REQUEST =
            "In config.ini, change only the SERVER port from 8080 to 9000. Leave everything else unchanged.";
    private static final String TARGET = "config.ini";
    private static final String PRIOR = "[SERVER]\nport=8080\n[CLIENT]\nport=8080\n";

    @Test
    void oneReplacedOccurrenceLeavesBothOneRegionPostconditionsUndecidable() {
        RequestObligationFinalStateVerifier.Result result = verify(PRIOR, "[SERVER]\nport=9000\n[CLIENT]\nport=8080\n");

        assertFalse(result.passed());
        assertEquals(List.of(), result.failedPostconditions());
        // Postconditions are in the obligation's canonical order, by kind name.
        assertEquals(List.of("OLD_TEXT_ABSENT:config.ini", "REQUESTED_REPLACEMENT_EXACTLY_ONCE:config.ini"),
                result.undecidablePostconditions());
        assertTrue(result.undecidedWithoutFailure());
        assertFalse(result.passedFor(obligation()));
    }

    @Test
    void everyReplacedOccurrenceMeetsTheAbsencePostconditionLiterally() {
        RequestObligationFinalStateVerifier.Result result = verify(PRIOR, "[SERVER]\nport=9000\n[CLIENT]\nport=9000\n");

        assertEquals(List.of(), result.failedPostconditions());
        assertEquals(List.of("REQUESTED_REPLACEMENT_EXACTLY_ONCE:config.ini"), result.undecidablePostconditions());
        assertTrue(result.undecidedWithoutFailure());
    }

    @Test
    void aChangeThatIsNotAPureSubstitutionFailsUnderEveryReading() {
        RequestObligationFinalStateVerifier.Result extra =
                verify(PRIOR, "[SERVER]\nport=9000\n[CLIENT]\nport=8080\nhost=x\n");
        RequestObligationFinalStateVerifier.Result none = verify(PRIOR, PRIOR + "9000\n");

        assertEquals(List.of(
                        "UNCHANGED_CONTENT_AROUND_REPLACEMENT:config.ini",
                        "REQUESTED_REPLACEMENT_EXACTLY_ONCE:config.ini"),
                extra.failedPostconditions());
        assertEquals(List.of("OLD_TEXT_ABSENT:config.ini"), extra.undecidablePostconditions());
        assertFalse(extra.undecidedWithoutFailure());
        assertTrue(none.failedPostconditions().contains("OLD_TEXT_ABSENT:config.ini"), none.toString());
        assertTrue(none.failedPostconditions().contains("REQUESTED_REPLACEMENT_EXACTLY_ONCE:config.ini"),
                none.toString());
        assertFalse(none.undecidedWithoutFailure());
    }

    @Test
    void aUniqueOldTextKeepsTheOneRegionDecision() {
        RequestObligationFinalStateVerifier.Result passed =
                verify("[SERVER]\nport=8080\n", "[SERVER]\nport=9000\n");
        RequestObligationFinalStateVerifier.Result failed =
                verify("[SERVER]\nport=8080\n", "[SERVER]\nport=8080\n9000\n");

        assertTrue(passed.passed(), passed.toString());
        assertTrue(passed.passedFor(obligation()));
        assertEquals(List.of(), passed.undecidablePostconditions());
        assertFalse(failed.passed());
        assertEquals(List.of(), failed.undecidablePostconditions());
        assertTrue(failed.failedPostconditions().contains("OLD_TEXT_ABSENT:config.ini"), failed.toString());
    }

    @Test
    void aPassedResultCannotHoldAnUndecidablePostcondition() {
        String sha = obligation().canonicalSha256();

        assertThrows(IllegalArgumentException.class, () -> new RequestObligationFinalStateVerifier.Result(
                true, List.of(), sha, List.of("OLD_TEXT_ABSENT:config.ini")));
        assertEquals(List.of(), new RequestObligationFinalStateVerifier.Result(true, List.of(), sha)
                .undecidablePostconditions());
    }

    private static RequestObligation obligation() {
        RequestObligation obligation = RequestObligationCompiler.compile(TaskContractResolver.fromUserRequest(REQUEST));
        assertEquals(RequestObligation.DerivationStatus.COMPLETE, obligation.derivationStatus());
        assertEquals(RequestObligation.ActionKind.REPLACE_TEXT_PRESERVE_REST, obligation.actionKind());
        return obligation;
    }

    private static RequestObligationFinalStateVerifier.Result verify(String before, String after) {
        return RequestObligationFinalStateVerifier.verify(
                obligation(),
                new RequestObligationFinalStateVerifier.Evidence(
                        Map.of(TARGET, before.getBytes(StandardCharsets.UTF_8)),
                        Map.of(TARGET, after.getBytes(StandardCharsets.UTF_8)),
                        Set.of(TARGET)));
    }
}
