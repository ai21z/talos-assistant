package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestObligationVerificationReceiptTest {
    private static final String F21_REQUEST =
            "Append Decision 004 to docs/decisions.md saying health checks remain read-only. "
                    + "Preserve Decisions 001 through 003 and change no code.";

    @Test
    void exactFinalStateAndCompleteMutationCoverageMintPositiveReceipt() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));
        RequestObligationFinalStateVerifier.Result finalState =
                RequestObligationFinalStateVerifier.verify(
                        obligation,
                        evidence(
                                "Decision 001: baseline\n",
                                "Decision 001: baseline\n"
                                        + "Decision 004: health checks remain read-only\n"));

        RequestObligationVerificationReceipt receipt =
                RequestObligationVerificationReceipt.verified(
                        obligation,
                        finalState,
                        Set.of("docs/decisions.md"),
                        List.of(successfulMutation(
                                "docs/decisions.md",
                                "call-1-2")));

        assertTrue(receipt.verified());
        assertTrue(receipt.proves(obligation));
        assertEquals(
                List.of("call-1-2"),
                receipt.coveredToolCallIds());
        assertTrue(receipt.coversSuccessfulMutation("call-1-2"));
    }

    @Test
    void failedMismatchedOrUncoveredEvidenceCannotMintPositiveReceipt() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));
        RequestObligation other = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(
                        "Append a Contributing section to README.md with one sentence saying "
                                + "Run tests before review. Preserve all current text."));
        RequestObligationFinalStateVerifier.Result passed =
                RequestObligationFinalStateVerifier.verify(
                        obligation,
                        evidence(
                                "Decision 001: baseline\n",
                                "Decision 001: baseline\n"
                                        + "Decision 004: health checks remain read-only\n"));
        RequestObligationFinalStateVerifier.Result failed =
                new RequestObligationFinalStateVerifier.Result(
                        false,
                        List.of("FAILED"),
                        obligation.canonicalSha256());

        assertFalse(RequestObligationVerificationReceipt.verified(
                obligation,
                passed,
                Set.of("docs/decisions.md"),
                List.of()).verified());
        assertFalse(RequestObligationVerificationReceipt.verified(
                obligation,
                failed,
                Set.of("docs/decisions.md"),
                List.of(successfulMutation(
                        "docs/decisions.md",
                        "call-1-2"))).verified());
        assertFalse(RequestObligationVerificationReceipt.verified(
                other,
                passed,
                Set.of("docs/decisions.md"),
                List.of(successfulMutation(
                        "docs/decisions.md",
                        "call-1-2"))).verified());
        assertFalse(RequestObligationVerificationReceipt.none().proves(obligation));
    }

    @Test
    void blankUnsafeOrUnverifiedMutationTargetsCannotMintReceipt() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));
        RequestObligationFinalStateVerifier.Result passed =
                RequestObligationFinalStateVerifier.verify(
                        obligation,
                        evidence(
                                "Decision 001: baseline\n",
                                "Decision 001: baseline\n"
                                        + "Decision 004: health checks remain read-only\n"));

        for (String path : List.of("", "../outside.txt", "README.md")) {
            assertFalse(RequestObligationVerificationReceipt.verified(
                    obligation,
                    passed,
                    Set.of("docs/decisions.md"),
                    List.of(successfulMutation(path, "call-1-2"))).verified());
        }
        assertFalse(RequestObligationVerificationReceipt.verified(
                obligation,
                passed,
                Set.of("../docs/decisions.md"),
                List.of(successfulMutation(
                        "docs/decisions.md",
                        "call-1-2"))).verified());
    }

    @Test
    void blankOrDuplicateToolCallIdentitiesCannotMintReceipt() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));
        RequestObligationFinalStateVerifier.Result passed =
                RequestObligationFinalStateVerifier.verify(
                        obligation,
                        evidence(
                                "Decision 001: baseline\n",
                                "Decision 001: baseline\n"
                                        + "Decision 004: health checks remain read-only\n"));

        assertFalse(RequestObligationVerificationReceipt.verified(
                obligation,
                passed,
                Set.of("docs/decisions.md"),
                List.of(successfulMutation(
                        "docs/decisions.md",
                        ""))).verified());
        assertFalse(RequestObligationVerificationReceipt.verified(
                obligation,
                passed,
                Set.of("docs/decisions.md"),
                List.of(
                        successfulMutation("docs/decisions.md", "call-1-2"),
                        successfulMutation("docs/decisions.md", "call-1-2")))
                .verified());
    }

    @Test
    void coveredToolCallIdentitiesRetainSuccessfulMutationOrder() {
        RequestObligation obligation = RequestObligationCompiler.compile(
                TaskContractResolver.fromUserRequest(F21_REQUEST));
        RequestObligationFinalStateVerifier.Result passed =
                RequestObligationFinalStateVerifier.verify(
                        obligation,
                        evidence(
                                "Decision 001: baseline\n",
                                "Decision 001: baseline\n"
                                        + "Decision 004: health checks remain read-only\n"));

        RequestObligationVerificationReceipt receipt =
                RequestObligationVerificationReceipt.verified(
                        obligation,
                        passed,
                        Set.of("docs/decisions.md"),
                        List.of(
                                successfulMutation(
                                        "docs/decisions.md",
                                        "call-1-2"),
                                successfulMutation(
                                        "docs/decisions.md",
                                        "call-1-3")));

        assertEquals(
                List.of("call-1-2", "call-1-3"),
                receipt.coveredToolCallIds());
    }

    private static RequestObligationFinalStateVerifier.Evidence evidence(
            String before,
            String after
    ) {
        String target = "docs/decisions.md";
        return new RequestObligationFinalStateVerifier.Evidence(
                Map.of(target, before.getBytes(StandardCharsets.UTF_8)),
                Map.of(target, after.getBytes(StandardCharsets.UTF_8)),
                Set.of(target));
    }

    private static ToolCallLoop.ToolOutcome successfulMutation(
            String path,
            String toolCallId
    ) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                true,
                true,
                false,
                "wrote",
                "",
                null,
                "",
                null,
                ToolMutationEvidence.fullWriteReplacement(
                        "Decision 001: baseline\n",
                        "Decision 001: baseline\n"
                                + "Decision 004: health checks remain read-only\n"),
                ToolFailureReason.NONE,
                List.of(),
                toolCallId);
    }
}
