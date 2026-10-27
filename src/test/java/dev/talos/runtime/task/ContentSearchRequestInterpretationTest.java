package dev.talos.runtime.task;

import dev.talos.runtime.intent.RequestedResultInventory;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.EvidenceObligation;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request is interpreted by the result it asks for, not by a workspace word it happens to contain.
 * A scoped text search, a location question, a project explanation, a combination of them, and a
 * request that quotes or restricts inspection each keep their own contract and obligation.
 */
class ContentSearchRequestInterpretationTest {

    private static CurrentTurnPlan plan(TaskContract contract) {
        return CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file", "talos.grep"), List.of(), List.of());
    }

    private static CurrentTurnPlan plan(String request) {
        return plan(TaskContractResolver.fromUserRequest(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Search the workspace for TODO",
            "grep this repo for calculateBmi",
            "Find every file in this project that mentions calculateBmi",
            "Which file in this codebase contains calculateBmi?",
            "Look for the string SECRET_KEY in this folder"
    })
    void aScopedTextSearchIsAReadOnlyLookupNotAProjectExplanation(String request) {
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        CurrentTurnPlan plan = plan(contract);

        assertEquals(TaskType.READ_ONLY_QA, contract.type(), contract.classificationReason());
        assertEquals(EvidenceObligation.FILE_CONTENT_LOOKUP_REQUIRED.name(), plan.evidenceObligation(),
                "a search is grounded in file-content evidence, not in a project summary");
        assertFalse(plan.requestedResults().requires(RequestedResultInventory.Kind.PROJECT_PURPOSE));
    }

    @Test
    void aGenuineProjectExplanationKeepsItsProjectSummaryObligation() {
        TaskContract contract = TaskContractResolver.fromUserRequest("What does this project do?");
        assertEquals(TaskType.WORKSPACE_EXPLAIN, contract.type());
        assertEquals(EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED.name(),
                plan(contract).evidenceObligation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"What's the path of this directory?", "Where is this workspace located?"})
    void aLocationQuestionIsAnsweredFromTheRuntimeWithoutAnObligation(String request) {
        CurrentTurnPlan plan = plan(request);
        assertTrue(plan.requestedResults().requires(RequestedResultInventory.Kind.WORKSPACE_LOCATION));
        assertEquals(EvidenceObligation.NONE.name(), plan.evidenceObligation());
    }

    @Test
    void aCombinedRequestKeepsEachResultOnItsOwnBasis() {
        CurrentTurnPlan plan = plan("Where is this workspace and what does it do?");
        assertTrue(plan.requestedResults().requires(RequestedResultInventory.Kind.WORKSPACE_LOCATION),
                "the location is a requested result of its own");
        assertTrue(plan.requestedResults().requires(RequestedResultInventory.Kind.PROJECT_PURPOSE),
                "the purpose is a requested result of its own");
        assertEquals(EvidenceObligation.PROJECT_SUMMARY_EVIDENCE_REQUIRED.name(), plan.evidenceObligation());
    }

    @Test
    void quotedTextDoesNotTurnAFileSearchIntoAProjectExplanation() {
        String request = "The README says 'explain this project to newcomers'. Which file holds that sentence?";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        assertEquals(TaskType.READ_ONLY_QA, contract.type(), contract.classificationReason());
        assertEquals(EvidenceObligation.FILE_CONTENT_LOOKUP_REQUIRED.name(), plan(contract).evidenceObligation());
    }

    /** A request that only resembles a search keeps its project-explanation contract. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Look at this project for me and tell me what it does",
            "Can you check the codebase for me?",
            "Which files does this project have?"
    })
    void aRequestThatOnlyResemblesASearchIsNotOne(String request) {
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        assertFalse(contract.type() == TaskType.READ_ONLY_QA
                        && EvidenceObligation.FILE_CONTENT_LOOKUP_REQUIRED.name().equals(plan(contract).evidenceObligation()),
                "not a content search: " + contract.type() + " " + plan(contract).evidenceObligation());
    }

    /** A clause after the search, or a withdrawn clause the resolver strips, does not undo the search. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Search the workspace for TODO and tell me if any are urgent",
            "Find every file in this project that mentions calculateBmi, even if it is in tests",
            "Search the workspace for TODO, don't read anything else"
    })
    void aSearchWithATrailingClauseStaysASearch(String request) {
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        assertEquals(TaskType.READ_ONLY_QA, contract.type(), contract.classificationReason());
        assertEquals(EvidenceObligation.FILE_CONTENT_LOOKUP_REQUIRED.name(), plan(contract).evidenceObligation());
    }

    @Test
    void quotedWorkspaceWordsAloneDoNotMakeAProjectExplanation() {
        String request = "Translate the phrase 'describe this project to newcomers' into German.";
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        assertFalse(contract.type() == TaskType.WORKSPACE_EXPLAIN,
                "a quoted phrase is not the request: " + contract.type() + " " + contract.classificationReason());
    }

    @Test
    void anInspectionRestrictionKeepsTheLocationAsARequestedResult() {
        CurrentTurnPlan plan = plan("Do not read any file, just tell me the path of this workspace");
        assertTrue(plan.requestedResults().requires(RequestedResultInventory.Kind.WORKSPACE_LOCATION),
                "the restriction does not erase what was asked");
        assertEquals(EvidenceObligation.NONE.name(), plan.evidenceObligation());
    }

    @Test
    void aSearchAfterAProjectExplanationDoesNotInheritTheProjectSummaryObligation() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("What does this project do?"),
                ChatMessage.assistant("It is a BMI calculator."),
                ChatMessage.user("Search the workspace for TODO"));
        TaskContract contract = TaskContractResolver.fromMessages(messages);
        assertEquals(TaskType.READ_ONLY_QA, contract.type(), contract.classificationReason());
        assertEquals(EvidenceObligation.FILE_CONTENT_LOOKUP_REQUIRED.name(), plan(contract).evidenceObligation());
    }

    @Test
    void aLocationQuestionAfterAProjectExplanationKeepsTheLocationResult() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("What does this project do?"),
                ChatMessage.assistant("It is a BMI calculator."),
                ChatMessage.user("What's the path of this directory?"));
        CurrentTurnPlan plan = plan(TaskContractResolver.fromMessages(messages));
        assertTrue(plan.requestedResults().requires(RequestedResultInventory.Kind.WORKSPACE_LOCATION));
        assertEquals(EvidenceObligation.NONE.name(), plan.evidenceObligation());
    }
}
