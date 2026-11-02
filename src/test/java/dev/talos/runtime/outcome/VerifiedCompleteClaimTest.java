package dev.talos.runtime.outcome;

import dev.talos.runtime.context.ChangeSummaryContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A "verified complete" claim follows the recorded completion, not a checks pass (MICA invariant I3, decision D5). */
class VerifiedCompleteClaimTest {

    @Test
    void theRecordedCompletionDecidesAndAPassWithoutOneIsAnOlderRecord() {
        assertTrue(ChangeSummaryContext.verifiedComplete("PASSED", "COMPLETED_VERIFIED"));
        assertFalse(ChangeSummaryContext.verifiedComplete("PASSED", "COMPLETED_UNVERIFIED"));
        assertFalse(ChangeSummaryContext.verifiedComplete("READBACK_ONLY", "COMPLETED_UNVERIFIED"));
        assertTrue(ChangeSummaryContext.verifiedComplete("PASSED", ""));
        assertFalse(ChangeSummaryContext.verifiedComplete("", ""));
    }

    @Test
    void aChecksPassIsNotReportedAsVerifiedComplete() {
        ChangeSummaryContext checks = context("PASSED", "COMPLETED_UNVERIFIED");
        ChangeSummaryContext verified = context("PASSED", "COMPLETED_VERIFIED");

        String checksAnswer = RuntimeVerificationStatusAnswer.renderIfNeeded("is it verified?", checks);
        String verifiedAnswer = RuntimeVerificationStatusAnswer.renderIfNeeded("is it verified?", verified);

        assertTrue(checksAnswer.startsWith("No. Latest Talos-recorded verification is not verified complete."),
                checksAnswer);
        assertTrue(checksAnswer.contains("verifier=PASSED; completion=COMPLETED_UNVERIFIED"), checksAnswer);
        assertTrue(verifiedAnswer.startsWith("Yes. Latest Talos-recorded verification is verified complete."),
                verifiedAnswer);
    }

    @Test
    void theTurnAndEveryFileMustEachBeVerifiedComplete() {
        ChangeSummaryContext checksTurn = new ChangeSummaryContext(ChangeSummaryContext.SCHEMA_VERSION,
                List.of(new ChangeSummaryContext.FileChange(
                        "index.html", "talos.write_file", 3, "trc-3", "SUCCEEDED", "PASSED", "COMPLETED_VERIFIED")),
                List.of(), "PASSED", "COMPLETED_UNVERIFIED", List.of());
        ChangeSummaryContext checksFile = new ChangeSummaryContext(ChangeSummaryContext.SCHEMA_VERSION,
                List.of(new ChangeSummaryContext.FileChange(
                        "index.html", "talos.write_file", 3, "trc-3", "SUCCEEDED", "PASSED", "COMPLETED_UNVERIFIED")),
                List.of(), "PASSED", "COMPLETED_VERIFIED", List.of());

        String turnAnswer = RuntimeVerificationStatusAnswer.renderIfNeeded("is it verified?", checksTurn);
        String fileAnswer = RuntimeVerificationStatusAnswer.renderIfNeeded("is it verified?", checksFile);

        assertTrue(turnAnswer.startsWith("No."), turnAnswer);
        assertTrue(fileAnswer.startsWith("No."), fileAnswer);
    }

    @Test
    void theChangeSummaryListsAChecksPassAsNotVerifiedComplete() {
        String perFile = context("PASSED", "COMPLETED_UNVERIFIED").renderForChangeSummaryQuestion();
        String turnLevel = new ChangeSummaryContext(ChangeSummaryContext.SCHEMA_VERSION,
                List.of(new ChangeSummaryContext.FileChange("index.html", "talos.write_file", 3, "trc-3")),
                List.of(), "PASSED", "COMPLETED_UNVERIFIED", List.of()).renderForChangeSummaryQuestion();

        assertTrue(perFile.contains("Some listed changes are not verified complete"), perFile);
        assertTrue(turnLevel.contains("Latest recorded mutation turn status: not verified complete"), turnLevel);
    }

    private static ChangeSummaryContext context(String verification, String completion) {
        return new ChangeSummaryContext(
                ChangeSummaryContext.SCHEMA_VERSION,
                List.of(new ChangeSummaryContext.FileChange(
                        "index.html", "talos.write_file", 3, "trc-3", "SUCCEEDED", verification, completion)),
                List.of(),
                verification,
                completion,
                List.of());
    }
}
