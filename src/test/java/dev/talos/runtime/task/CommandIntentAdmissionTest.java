package dev.talos.runtime.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandIntentAdmissionTest {

    @Test
    void savedLiveFreshReadRequestDoesNotRequestCommandVerification() {
        String request = "Please inspect retry.py again. Using the current implementation and configured retry limit, "
                + "list the attempt numbers that would run if each attempt failed, and state when should_retry becomes false. "
                + "Separately explain any remaining discrepancy with README. Use the current files, not just your previous answer. "
                + "Don't change files or run tests.";

        TaskContract contract = TaskContractResolver.fromUserRequest(request);

        assertEquals(TaskType.DIAGNOSE_ONLY, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertFalse(contract.verificationRequired());
        assertTrue(contract.expectedTargets().contains("retry.py"));
        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Inspect retry.py. Do not run tests.",
            "Inspect retry.py. Don't change files or run the tests.",
            "Inspect retry.py without running tests.",
            "Inspect retry.py. Never execute python retry.py.",
            "Inspect retry.py. Do not run python -m pytest.",
            "Inspect retry.py. Don't run pytest or execute python retry.py.",
            "Inspect retry.py. Do not execute the command dir.",
            "Inspect retry.py. Do not run the safe command check.",
            "Inspect retry.py. Do not run the Gradle tests with profile gradle_test.",
            "Inspect retry.py. Do not call talos.run_command with profile gradle_test.",
            "Inspect retry.py. The README says \"run python -m pytest\".",
            "Inspect retry.py. The README says `run the command dir`.",
            "Inspect retry.py. The README says 'run Gradle tests with profile gradle_test'.",
            "Inspect retry.py. If I run python retry.py, what would happen?",
            "Inspect retry.py. Suppose I run tests for this Python file.",
            "Inspect retry.py. Yesterday I asked you to run python retry.py.",
            "Inspect retry.py. Did you run python -m pytest earlier?",
            "Inspect retry.py. Explain how to run python retry.py.",
            "Inspect retry.py. Explain how to run the command dir.",
            "Inspect retry.py. Explain how to run Gradle tests.",
            "Do not:\nRun python retry.py.",
            "Do not run tests, execute python retry.py.",
            "Do not:\n- Run python retry.py\n- Execute the command dir.",
            "Do not run tests then execute python retry.py.",
            "Do not run tests; execute python retry.py.",
            "The README says:\nRun the Gradle tests with profile gradle_test.",
            "An example follows:\nRun python retry.py."
    })
    void nonExecutionMentionsDoNotEnterEitherCommandGate(String request) {
        TaskContract contract = TaskContractResolver.fromUserRequest(request);

        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request), request);
        assertFalse(contract.classificationReason().contains("command-verification-request"), request);
        assertFalse(contract.mutationAllowed(), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Run pytest.",
            "Run python -m pytest.",
            "- Run python -m pytest.",
            "Execute python retry.py.",
            "Run `python -m pytest`.",
            "Run tests for the Python file.",
            "Check the tests for retry.py.",
            "Do not change files, but run python retry.py.",
            "Do not delete files, but run python retry.py.",
            "Don't touch files, but run pytest.",
            "Do not delete files. Run python retry.py.",
            "Do not run the command dir, but execute python retry.py.",
            "Do not run Gradle tests, but run python retry.py."
    })
    void affirmativePythonRequestsRetainUnsupportedSemantics(String request) {
        assertTrue(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request), request);
        assertCommandContract(request, "unsupported-command-verification-request");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Run the command Get-ChildItem -Name.",
            "Execute the command dir.",
            "Run the safe command check for this folder. If it can't run, say exactly that.",
            "Do not run python retry.py, but execute the command dir.",
            "Run the command dir, but do not execute python retry.py."
    })
    void affirmativeNativeRequestsRetainUnsupportedSemantics(String request) {
        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request), request);
        assertCommandContract(request, "unsupported-command-verification-request");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Run the Gradle tests with profile gradle_test. Do not edit files.",
            "Call talos.run_command with profile gradle_test and args_json [\"--tests\",\"dev.talos.PassTest\"].",
            "Do not run python retry.py, but run the Gradle tests with profile gradle_test.",
            "Run the Gradle tests with profile gradle_test, but do not execute python retry.py."
    })
    void affirmativeProfileRequestsRetainExplicitSemantics(String request) {
        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request), request);
        assertCommandContract(request, "explicit-command-verification-request");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Do not run Python tests, but run tests.",
            "The README says 'python retry.py'. Run tests.",
            "Run tests, but do not execute python retry.py.",
            "Run tests described by 'python retry.py'.",
            "Run tests without python.",
            "Don't run python retry.py and run tests."
    })
    void unrelatedPythonMentionsCannotSupplyTheCommandTarget(String request) {
        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Run tests.",
            "Inspect retry.py, then run tests.",
            "Run tests for Java. Inspect retry.py.",
            "Run tests. Do not execute python retry.py."
    })
    void genericTestRequestIsUnsupportedWithoutInventingPythonBinding(String request) {
        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request), request);
        assertCommandContract(request, "unsupported-command-verification-request");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Check the tests for missing assertions.",
            "Verify the tests contain assertions."
    })
    void genericTestReviewDoesNotBecomeAnExecutionRequest(String request) {
        assertFalse(TaskContractResolver.fromUserRequest(request).classificationReason()
                .contains("command-verification-request"), request);
        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request), request);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Run python retry.py.\u0000",
            "Run python retry.py [",
            "Run python retry.py.\ud800"
    })
    void malformedRequestCannotSupplyCommandIntent(String request) {
        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request), request);
    }

    @Test
    void oversizedRequestCannotSupplyCommandIntent() {
        String request = "Run python retry.py. " + "x".repeat(AnswerRequirements.MAX_REQUEST_CHARS);
        assertFalse(TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(request));
    }

    private static void assertCommandContract(String request, String reason) {
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        assertEquals(TaskType.VERIFY_ONLY, contract.type(), request);
        assertEquals(reason, contract.classificationReason(), request);
        assertFalse(contract.mutationRequested(), request);
        assertFalse(contract.mutationAllowed(), request);
        assertTrue(contract.verificationRequired(), request);
    }
}
