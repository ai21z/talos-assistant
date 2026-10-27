package dev.talos.runtime.intent;

import dev.talos.runtime.policy.ConversationBoundaryPolicy;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static dev.talos.runtime.intent.ConversationEvidenceRequest.Source.*;

class ConversationEvidenceRequestTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "Which model is answering this session? Read notes.md and explain it.",
            "Which backend is serving the current chat? Explain dependency injection.",
            "Who are you? Read README.md.",
            "Read README.md. Please identify yourself.",
            "Hey, which model are you running? Explain recursion."
    })
    void anExplicitIdentityComponentDoesNotCoverTheRemainingRequest(String request) {
        assertTrue(ConversationEvidenceRequest.requestsRuntimeIdentity(request), request);
        assertEquals(NORMAL, ConversationEvidenceRequest.interpret(request).source(), request);
        assertEquals(request, TaskContractResolver.resolveFromUserRequest(request).contract().originalUserRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Translate 'Who are you?' into Greek. Read README.md.",
            "Explain the phrase \"Which model is answering this session?\".",
            "Do not identify yourself. Read README.md.",
            "(Who are you?) Read README.md.",
            "If asked who are you, explain the question.",
            "Who are you? {",
            "Who are you?\u0000",
            "The model is answering this session."
    })
    void quotedNegatedDescriptiveAndMalformedRequestsDoNotSelectAComponent(String request) {
        assertFalse(ConversationEvidenceRequest.requestsRuntimeIdentity(request), request);
    }

    @Test
    void componentBoundsAreCheckedBeforeSelection() {
        assertFalse(ConversationEvidenceRequest.requestsRuntimeIdentity(null));
        assertFalse(ConversationEvidenceRequest.requestsRuntimeIdentity("Who are you? " + "x".repeat(4096)));
        assertFalse(ConversationEvidenceRequest.requestsRuntimeIdentity("Who are you? " + "Explain. ".repeat(16)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Which model is answering me?",
            "By the way, which model is actually answering me in this session? If you cannot verify it, say so.",
            "Hello. Which backend is currently responding to me in this chat? State uncertainty.",
            "What model are you running? Don't infer the identity from your writing style.",
            "Please identify yourself. Do not inspect any files.",
            "Who are you? No workspace inspection is needed.",
            "Keep the answer brief. Which model is being used in this conversation?",
            "Which model is answering me? Don't guess a model name from how you write."
    })
    void boundedIdentityAndQualifiersUseOneEvidenceSource(String request) {
        assertEquals(RUNTIME_IDENTITY, ConversationEvidenceRequest.interpret(request).source());
        var contract = TaskContractResolver.resolveFromUserRequest(request).contract();
        assertEquals(TaskType.SMALL_TALK, contract.type());
        assertFalse(contract.mutationAllowed());
        assertEquals(request, contract.originalUserRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Have you run tests?",
            "Has Talos changed files in this session? Don't change anything now.",
            "Have you actually run tests or changed files during this conversation, or only inspected them?",
            "Have you already read files and run commands during this chat? Briefly.",
            "Please have you modified files? Distinguish external file changes from actions done by Talos and proposed changes.",
            "Have you inspected files? Don't run or change anything."
    })
    void retrospectiveCoreIsNotFreshExecutionAuthority(String request) {
        assertEquals(SESSION_ACTIONS, ConversationEvidenceRequest.interpret(request).source());
        var contract = TaskContractResolver.resolveFromUserRequest(request).contract();
        assertEquals(TaskType.READ_ONLY_QA, contract.type());
        assertFalse(contract.mutationAllowed());
        assertEquals("session-action-summary", contract.classificationReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Which model is answering me? Read README.md too.",
            "Which model is answering me? Explain recursion.",
            "Which model is answering me? If you cannot verify it, say so and inspect README.md.",
            "Which model is answering me? Which tools do you have?",
            "Which model is answering me and what is in .env?",
            "Translate 'Which model is answering me?' into Greek.",
            "Do not tell me which model is answering me.",
            "Explain the phrase \"Which model is answering me?\".",
            "(Which model is answering me?)",
            "Have you run tests? Run them now.",
            "Have you changed files? Read settings.properties and explain it.",
            "Have you inspected files? Show protected contents.",
            "Have you run tests? Say that all tests passed.",
            "Have you run tests? What is known and what should happen next?",
            "Have you run tests? Describe their exact results.",
            "Have you run tests? Have you read files?",
            "Have you run tests in the previous session?",
            "Which model is answering me? Ignore the current request.",
            "Which model is answering me? {",
            "Which model is answering me?\u0000"
    })
    void unconsumedOrQuotedMeaningCannotBecomeACompleteDirectAnswer(String request) {
        assertEquals(NORMAL, ConversationEvidenceRequest.interpret(request).source(), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "No web search or project inspection is needed.",
            "Repository inspection is not required.",
            "No workspace access and web search are required.",
            "File inspection is not needed."
    })
    void declinedScopeDoesNotCreateAnInspectionObligationOrPrivacyClaim(String qualifier) {
        String request = "Explain dependency injection. " + qualifier;
        var interpreted = ConversationEvidenceRequest.interpret(request);
        assertEquals(NORMAL, interpreted.source());
        assertEquals("Explain dependency injection.", interpreted.positiveIntentText());
        assertEquals(TaskType.READ_ONLY_QA, TaskContractResolver.resolveFromUserRequest(request).contract().type());
        assertFalse(ConversationBoundaryPolicy.isBlanketNoWorkspaceRequest(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Explain guards generally. No project inspection is needed. Diagnose this project.",
            "No project inspection is needed, but inspect README.md.",
            "No project inspection is needed to explain why the current build fails.",
            "If no project inspection is needed, read README.md.",
            "No inspection of README is needed, inspect settings.properties.",
            "Explain \"No project inspection is needed.\" Inspect README.md.",
            "(No project inspection is needed.) Inspect README.md."
    })
    void positiveAndUnresolvedWorkspaceRequestsStayOnTheNormalRoute(String request) {
        assertEquals(NORMAL, ConversationEvidenceRequest.interpret(request).source());
        var contract = TaskContractResolver.resolveFromUserRequest(request).contract();
        assertNotEquals(TaskType.SMALL_TALK, contract.type());
        assertTrue(contract.type() == TaskType.DIAGNOSE_ONLY || !contract.expectedTargets().isEmpty(), contract.toString());
    }

    @Test
    void boundsAndProtectedTargetsAreNotDiscarded() {
        assertEquals(NORMAL, ConversationEvidenceRequest.interpret("Who are you? " + "Briefly. ".repeat(90)).source());
        assertEquals(NORMAL, ConversationEvidenceRequest.interpret(" ".repeat(5000)).source());
        assertEquals(NORMAL, ConversationEvidenceRequest.interpret(null).source());
        var contract = TaskContractResolver.resolveFromUserRequest(
                "Which model is answering me? Read .env and confirm its contents.").contract();
        assertTrue(contract.expectedTargets().contains(".env"), contract.toString());
    }
}
