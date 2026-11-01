package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.context.RepairOffer;
import dev.talos.runtime.verification.TaskVerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every runtime static-verification failure header carries the typed repair offer (decision D2, part D-d). */
class RepairOfferRenderingTest {
    private static final TaskVerificationResult FAILED = TaskVerificationResult.failed(
            "style.css: expected target was not successfully mutated.",
            List.of(),
            List.of("style.css: expected target was not successfully mutated."));

    @Test
    void failedReplacementOffersRepairAfterTheRuntimeHeader() {
        String answer = StaticVerificationAnswerRenderer.failedReplacement(FAILED, (ToolCallLoop.LoopResult) null);

        assertOfferAfterHeader(answer, "[Task incomplete: Static verification failed - ");
    }

    @Test
    void partialFailureOffersRepairInsideTheRuntimeAnnotation() {
        String answer = StaticVerificationAnswerRenderer.partialFailedAnnotation(FAILED) + "Model text.";

        assertOfferAfterHeader(answer, "[Partial verification: static checks failed - ");
        assertTrue(answer.indexOf(RepairOffer.LINE) < answer.indexOf("Model text."), answer);
    }

    @Test
    void failedAnnotationOffersRepairEvenWithoutListedProblems() {
        String answer = StaticVerificationAnswerRenderer.failedAnnotation(
                TaskVerificationResult.failed("Expected targets were not all mutated.", List.of(), List.of()));

        assertOfferAfterHeader(answer, "[Task incomplete: Static verification failed - ");
    }

    @Test
    void aTurnWithADeniedChangeGetsNoOffer() {
        String replaced = StaticVerificationAnswerRenderer.failedReplacement(FAILED, null, false);
        String partial = StaticVerificationAnswerRenderer.partialFailedAnnotation(FAILED, false);

        org.junit.jupiter.api.Assertions.assertFalse(replaced.contains(RepairOffer.LINE), replaced);
        org.junit.jupiter.api.Assertions.assertFalse(partial.contains(RepairOffer.LINE), partial);
    }

    private static void assertOfferAfterHeader(String answer, String header) {
        assertTrue(answer.startsWith(header), answer);
        assertTrue(answer.contains(RepairOffer.LINE), answer);
        assertTrue(answer.indexOf(RepairOffer.LINE) > answer.indexOf(header), answer);
    }
}
