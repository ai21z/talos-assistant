package dev.talos.runtime.policy;

import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.task.TaskContract;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic evidence bases isolate presentation. Source acquisition and semantic correctness are not exercised. */
class WorkspaceResultPresentationTest {
    @Test void acquiredPurposeEvidenceDoesNotPromoteModelExplanationToRuntimeFact() {
        var inventory = RequestedResultInventory.compile(TaskContract.unknown(
                "Where is this workspace and what does this project do?"));
        var assessments = inventory.results().stream().map(result ->
                new WorkspaceResultEvidencePolicy.ResultAssessment(result,
                        EvidenceObligationVerifier.Result.satisfied("Synthetic acquired evidence basis."), null)).toList();
        var assessment = new WorkspaceResultEvidencePolicy.Assessment(true, assessments, "fixture/workspace");
        String explanation = "This project appears to maintain a catalog.";
        String output = assessment.compose(explanation);
        assertTrue(output.contains("Runtime workspace facts:"), output);
        assertTrue(output.contains("The current workspace is:\nfixture/workspace"), output);
        assertTrue(output.contains("Model explanation from admitted project sources"), output);
        assertTrue(output.contains("meaning and completeness unassessed"), output);
        assertTrue(output.endsWith(explanation), output);
        assertTrue(assessment.aggregate().message().contains("Answer meaning remains unassessed."));
    }
}
