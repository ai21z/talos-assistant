package dev.talos.runtime.policy;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UnsupportedDocumentMutationPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"Create a DOCX file about music.", "Save a Word document about music.",
            "Save an Excel workbook.", "Save a PDF document.", "Save result.docx."})
    void unsupportedClarificationIsBoundToTheDirectOutputObject(String request) {
        var save = dev.talos.runtime.intent.ContentSaveRequest.parse(request);
        assertTrue(save.present());
        assertTrue(UnsupportedDocumentMutationPolicy.answerIfUnsupportedSaveClarification(save).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Create a Word document about music.", "Create a PDF document."})
    void untypedDocumentCreationKeepsItsExistingCapabilityRoute(String request) {
        var contract = TaskContractResolver.fromUserRequest(request);
        assertTrue(UnsupportedDocumentMutationPolicy.answerIfUnsupportedMutation(contract).isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Save an explanation of a docx file.", "Save \"create a docx file\".",
            "Save a text file with a story about a docx file.",
            "Save a short story and explain how to create a docx file.",
            "If needed, create a docx file about music.", "The guide says create a docx file."})
    void contentOrScopedInstructionsCannotBecomeUnsupportedOutputObjects(String request) {
        var save = dev.talos.runtime.intent.ContentSaveRequest.parse(request);
        assertTrue(UnsupportedDocumentMutationPolicy.answerIfUnsupportedSaveClarification(save).isEmpty());
    }

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
