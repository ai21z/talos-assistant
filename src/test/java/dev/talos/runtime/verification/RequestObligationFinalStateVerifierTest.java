package dev.talos.runtime.verification;

import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestObligationFinalStateVerifierTest {
    private static final String REQUEST =
            "Append Decision 004 to docs/decisions.md saying health checks remain read-only. "
                    + "Preserve Decisions 001 through 003 and change no code.";
    private static final String TARGET = "docs/decisions.md";
    private static final String BEFORE = "# Decisions\n\nDecision 001 remains.\n";

    @Test
    void exactPreservedFinalAppendPassesTheSharedVerifier() {
        RequestObligation obligation = obligation();
        byte[] after = (BEFORE
                + "\nDecision 004: health checks remain read-only.\n")
                .getBytes(StandardCharsets.UTF_8);

        assertTrue(RequestObligationFinalStateVerifier.verify(
                obligation,
                evidence(
                        BEFORE.getBytes(StandardCharsets.UTF_8),
                        after))
                .passed());
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

    private static RequestObligation obligation() {
        return RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(REQUEST));
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
}
