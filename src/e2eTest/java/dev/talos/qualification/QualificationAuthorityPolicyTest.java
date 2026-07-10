package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationAuthorityPolicyTest {

    @Test
    void validAuthorityShapesCoverEveryAdmissionCategoryAndApprovalDisposition() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<AuthorityCase> cases = List.of(
                new AuthorityCase("A01-simple-arithmetic-abstention", "SMALL_TALK", false,
                        "DIRECT_ANSWER_ONLY"),
                new AuthorityCase("A04-technical-list-root", "DIRECTORY_LISTING", false,
                        "LIST_DIR_ONLY"),
                new AuthorityCase("A07-simple-create-file", "FILE_CREATE", true,
                        "MUTATING_TOOL_REQUIRED"),
                new AuthorityCase("A09-contradictory-denied-mutation", "FILE_CREATE", true,
                        "MUTATING_TOOL_REQUIRED"),
                new AuthorityCase("A11-recovery-append-anchor", "FILE_EDIT", true,
                        "REPAIR_FROM_VERIFIER_FINDINGS"),
                new AuthorityCase("A12-recovery-interruption", "FILE_CREATE", true,
                        "MUTATING_TOOL_REQUIRED"));

        for (AuthorityCase testCase : cases) {
            assertTrue(QualificationAuthorityPolicy.mismatch(
                            catalog.scenario(testCase.scenarioId()),
                            testCase.taskType(),
                            testCase.mutationAllowed(),
                            testCase.actionObligation()).isEmpty(),
                    testCase::scenarioId);
        }
    }

    @Test
    void readAuthorityCannotBecomeMutatingAndMutationAuthorityCannotDisappear() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();

        assertTrue(QualificationAuthorityPolicy.mismatch(
                        catalog.scenario("A04-technical-list-root"),
                        "FILE_EDIT",
                        true,
                        "MUTATING_TOOL_REQUIRED").isPresent());
        assertTrue(QualificationAuthorityPolicy.mismatch(
                        catalog.scenario("A07-simple-create-file"),
                        "READ_ONLY_QA",
                        false,
                        "DIRECT_ANSWER_ONLY").isPresent());
    }

    private record AuthorityCase(
            String scenarioId,
            String taskType,
            boolean mutationAllowed,
            String actionObligation) {}
}
