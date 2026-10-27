package dev.talos.runtime.intent;

import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalReportingTokenBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {"workspace-notes", "team-claims", "cache/says", "record-reports"})
    void pathComponentsDoNotOwnTheFollowingCreation(String directory) {
        String target = directory + "/summary.txt";
        String request = "Create a directory named " + directory
                + " and create " + target + " containing exactly created by audit.";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        assertTrue(resolution.contract().mutationAllowed(), resolution.toString());
        assertTrue(resolution.contract().expectedTargets().contains(target), resolution.toString());
        assertTrue(resolution.intent().contentSaveRequest().mutationRequested(), resolution.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace-notes", "team-claims", "cache/says", "record-reports"})
    void pathSuffixDoesNotOpenAReportedPayload(String path) {
        assertEquals(-1, CanonicalMutationClauseMatcher.openReportedPayloadStart("Create " + path));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "The report notes: create result.txt.",
            "The operator claims: create result.txt.",
            "The guide says: create result.txt.",
            "The log reports: create result.txt."
    })
    void actualReportingPredicatesRemainNonAuthoritative(String request) {
        assertFalse(TaskContractResolver.fromUserRequest(request).mutationAllowed(), request);
    }
}
