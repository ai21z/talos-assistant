package dev.talos.runtime.policy;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsupportedDocumentMutationPolicyTest {

    @Test
    void markdownReportFromOfficeDocumentSourcesIsNotUnsupportedBinaryCreation() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create office-summary.md summarizing board-brief.pdf, client-notes.docx, and revenue.xlsx.");

        assertTrue(UnsupportedDocumentMutationPolicy.answerIfUnsupportedMutation(contract).isEmpty());
    }

    @Test
    void naturalPdfOutputCreationStillGetsUnsupportedBinaryCreationAnswer() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create a PDF file that talks about how to build a synthwave band's web page.");

        assertTrue(UnsupportedDocumentMutationPolicy.answerIfUnsupportedMutation(contract).isPresent());
    }
}
