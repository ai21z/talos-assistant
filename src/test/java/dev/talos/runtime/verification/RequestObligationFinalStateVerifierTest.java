package dev.talos.runtime.verification;

import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestObligationFinalStateVerifierTest {
    private static final String REQUEST =
            "Append Decision 004 to docs/decisions.md saying health checks remain read-only. "
                    + "Preserve Decisions 001 through 003 and change no code.";
    private static final String TARGET = "docs/decisions.md";
    private static final String BEFORE = "# Decisions\n\nDecision 001 remains.\n";
    private static final String F16_REQUEST =
            "Append a Contributing section to README.md with one sentence saying "
                    + "Run tests before review. Preserve all current text.";
    private static final String F17_REQUEST =
            "In notes.md, change draft to final. If an exact edit fails, "
                    + "recover without deleting any other line.";

    @Test
    void exactPreservedFinalAppendPassesTheSharedVerifier() {
        RequestObligation obligation = obligation();
        byte[] after = (BEFORE
                + "\nDecision 004: health checks remain read-only.\n")
                .getBytes(StandardCharsets.UTF_8);

        RequestObligationFinalStateVerifier.Result result =
                RequestObligationFinalStateVerifier.verify(
                obligation,
                evidence(
                        BEFORE.getBytes(StandardCharsets.UTF_8),
                        after));

        assertTrue(result.passed());
        assertEquals(obligation.canonicalSha256(), result.obligationSha256());
        assertTrue(result.passedFor(obligation));
    }

    @Test
    void missingWordSeparatorDoesNotBecomeAnExecutableAppendMatch() {
        RequestObligation obligation = obligation();
        byte[] after = (BEFORE
                + "\nDecision 004sayinghealth checks remain read-only.\n")
                .getBytes(StandardCharsets.UTF_8);

        assertFalse(RequestObligationFinalStateVerifier.verify(
                obligation,
                evidence(
                        BEFORE.getBytes(StandardCharsets.UTF_8),
                        after))
                .passed());
    }

    @Test
    void evidenceRejectsUnavailableBytesAndNonCanonicalAliases() {
        Map<String, byte[]> unavailable = new HashMap<>();
        unavailable.put(TARGET, null);
        assertThrows(IllegalArgumentException.class, () ->
                new RequestObligationFinalStateVerifier.Evidence(
                        unavailable,
                        Map.of(),
                        Set.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new RequestObligationFinalStateVerifier.Evidence(
                        Map.of("./" + TARGET, new byte[0]),
                        Map.of(TARGET, new byte[0]),
                        Set.of(TARGET)));
        assertThrows(IllegalArgumentException.class, () ->
                new RequestObligationFinalStateVerifier.Evidence(
                        Map.of(TARGET, new byte[0]),
                        Map.of(TARGET, new byte[0]),
                        Set.of("./" + TARGET)));
    }

    @Test
    void evidenceDefensivelyCopiesInputAndAccessorBytes() {
        RequestObligation obligation = obligation();
        byte[] before = BEFORE.getBytes(StandardCharsets.UTF_8);
        byte[] after = (BEFORE
                + "\nDecision 004: health checks remain read-only.\n")
                .getBytes(StandardCharsets.UTF_8);
        RequestObligationFinalStateVerifier.Evidence evidence =
                evidence(before, after);

        before[0] = 'X';
        after[0] = 'X';
        evidence.beforeFiles().get(TARGET)[0] = 'Y';
        evidence.afterFiles().get(TARGET)[0] = 'Y';

        assertTrue(RequestObligationFinalStateVerifier.verify(
                obligation,
                evidence).passed());
    }

    @Test
    void appendSectionRequiresTheExactPreservedUniqueFinalBlockAndStructuredEvidence() {
        String target = "README.md";
        String before = "# Talos\n\nExisting text.\n";
        String after = before + "\n## Contributing\nRun tests before review.\n";
        RequestObligation obligation = obligation(F16_REQUEST);

        assertTrue(verify(obligation, target, before, after, true).passed());
        assertFalse(verify(
                obligation,
                target,
                before,
                "# Changed\n\n## Contributing\nRun tests before review.\n",
                true).passed());
        assertFalse(verify(
                obligation,
                target,
                "# Talos\n\n## Contributing\nOld guidance.\n",
                "# Talos\n\n## Contributing\nOld guidance.\n"
                        + "\n## Contributing\nRun tests before review.\n",
                true).passed());
        assertFalse(verify(
                obligation,
                target,
                before,
                after + "\n## Later\nMore text.\n",
                true).passed());
        assertFalse(verify(obligation, target, before, after, false).passed());
    }

    @Test
    void preserveRestReplacementRequiresExactlyOneLiteralReplacementAndNoOtherLineChange() {
        String target = "notes.md";
        String before = "Status: draft\nKeep this final line unchanged.\n";
        String after = "Status: final\nKeep this final line unchanged.\n";
        RequestObligation obligation = obligation(F17_REQUEST);

        assertTrue(verify(obligation, target, before, after, true).passed());
        assertFalse(verify(
                obligation,
                target,
                before,
                "Status: final\n",
                true).passed());
        assertFalse(verify(
                obligation,
                target,
                "Status: draft\nSecond draft marker.\n",
                "Status: final\nSecond draft marker.\n",
                true).passed());
        assertFalse(verify(
                obligation,
                target,
                before,
                before,
                true).passed());
        assertFalse(verify(obligation, target, before, after, false).passed());
    }

    @Test
    void preserveRestGrowthReplacementAllowsNewTextToContainTheOldText() {
        String target = "notes.md";
        String before = "Release: 2023\nKeep this line unchanged.\n";
        String after = "Release: 2023-2024\nKeep this line unchanged.\n";
        RequestObligation obligation = obligation(
                "In notes.md, change 2023 to 2023-2024. "
                        + "If an exact edit fails, recover without deleting any other line.");

        RequestObligationFinalStateVerifier.Result result =
                verify(obligation, target, before, after, true);

        assertTrue(
                result.passed(),
                "An exact growth replacement must not treat old text inside new text "
                        + "as a stale occurrence: " + result);
    }

    @Test
    void preserveRestGrowthReplacementRejectsAnIndependentOldOccurrence() {
        String target = "notes.md";
        String oldText = "alpha\nbeta";
        String newText = "gamma\ndelta";
        String before = "Header\r\nalpha\r\nbeta\r\nTail\r\n";
        String after = "Header\r\ngamma\r\ndelta\r\nTail\r\nalpha\r\nbeta\r\n";
        RequestObligation obligation = replacementObligation(target, oldText, newText);

        RequestObligationFinalStateVerifier.Result result =
                verify(obligation, target, before, after, true);

        assertFalse(result.passed());
        assertTrue(result.failedPostconditions().contains("OLD_TEXT_ABSENT:" + target));
    }

    @Test
    void preserveRestReplacementMatchesLfObligationTextAgainstCrlfContent() {
        String target = "notes.md";
        String oldText = "Release 2023\nStatus draft";
        String newText = "Release 2023\nStatus final";
        String before = "Header\r\nRelease 2023\r\nStatus draft\r\nTail\r\n";
        String after = "Header\r\nRelease 2023\r\nStatus final\r\nTail\r\n";
        RequestObligation obligation = replacementObligation(target, oldText, newText);

        RequestObligationFinalStateVerifier.Result result =
                verify(obligation, target, before, after, true);

        assertTrue(
                result.passed(),
                "Replacement postconditions must compare normalized line endings: " + result);
    }

    private static RequestObligation obligation() {
        return obligation(REQUEST);
    }

    private static RequestObligation obligation(String request) {
        return RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(request));
    }

    private static RequestObligation replacementObligation(
            String target,
            String oldText,
            String newText
    ) {
        return new RequestObligation(
                RequestObligation.SCHEMA_VERSION,
                RequestObligation.DerivationStatus.COMPLETE,
                RequestObligation.ResponseOwner.DETERMINISTIC_RUNTIME_ALLOWED,
                RequestObligation.ActionKind.REPLACE_TEXT_PRESERVE_REST,
                RequestObligation.MutationScope.ONLY_REQUIRED_TARGETS,
                List.of(target),
                List.of(),
                List.of(),
                List.of(new RequestObligation.PreservationConstraint(
                        RequestObligation.PreservationKind.UNCHANGED_CONTENT_AROUND_REPLACEMENT,
                        target)),
                new RequestObligation.OutputShape(
                        RequestObligation.OutputKind.REPLACE_TEXT,
                        target,
                        oldText,
                        newText),
                List.of(
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind.REQUESTED_REPLACEMENT_EXACTLY_ONCE,
                                target),
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind.OLD_TEXT_ABSENT,
                                target),
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind.NEW_TEXT_PRESENT,
                                target),
                        new RequestObligation.VerificationPostcondition(
                                RequestObligation.VerificationKind.STRUCTURED_MUTATION_EVIDENCE,
                                target)));
    }

    private static RequestObligationFinalStateVerifier.Evidence evidence(
            byte[] before,
            byte[] after
    ) {
        return new RequestObligationFinalStateVerifier.Evidence(
                Map.of(TARGET, before),
                Map.of(TARGET, after),
                Set.of(TARGET));
    }

    private static RequestObligationFinalStateVerifier.Result verify(
            RequestObligation obligation,
            String target,
            String before,
            String after,
            boolean structured
    ) {
        return RequestObligationFinalStateVerifier.verify(
                obligation,
                new RequestObligationFinalStateVerifier.Evidence(
                        Map.of(target, before.getBytes(StandardCharsets.UTF_8)),
                        Map.of(target, after.getBytes(StandardCharsets.UTF_8)),
                        structured ? Set.of(target) : Set.of()));
    }
}
