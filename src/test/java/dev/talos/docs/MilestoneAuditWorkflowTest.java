package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneAuditWorkflowTest {

    @Test
    void manualQaRunbookKeepsExactIndexOverwriteAfterStaticWebProbes() throws Exception {
        String workflow = Files.readString(Path.of("work-cycle-docs", "runbooks", "manual-qa.md"));

        int selectorRepair = workflow.indexOf("Make script.js fix the selector bug");
        int staticWebReview = workflow.indexOf("Review the current static web page");
        int bmiReviewFix = workflow.indexOf("Review the BMI calculator you just created and fix any obvious issue");
        int exactIndexOverwrite = workflow.indexOf("Overwrite index.html with exactly AFTER");

        assertTrue(selectorRepair >= 0, "selector-repair prompt missing");
        assertTrue(staticWebReview >= 0, "static-web review prompt missing");
        assertTrue(bmiReviewFix >= 0, "BMI review/fix prompt missing");
        assertTrue(exactIndexOverwrite >= 0, "exact index overwrite prompt missing");
        assertTrue(exactIndexOverwrite > selectorRepair,
                "exact index overwrite must not contaminate selector-repair evidence");
        assertTrue(exactIndexOverwrite > staticWebReview,
                "exact index overwrite must not contaminate static-web review evidence");
        assertTrue(exactIndexOverwrite > bmiReviewFix,
                "exact index overwrite must not contaminate BMI repair evidence");
        assertTrue(workflow.contains("Exact `index.html` overwrite probes must be isolated"),
                "workflow must document the fixture isolation rule");
    }

    @Test
    void findingsTemplatesIncludeAuditDesignFailureBucket() throws Exception {
        String workflow = Files.readString(Path.of("work-cycle-docs", "runbooks", "manual-qa.md"));
        String summaryTemplate = Files.readString(Path.of(
                "work-cycle-docs", "templates", "talosbench-summary-template.md"));

        assertTrue(workflow.contains("audit-design failure"),
                "milestone workflow must tell auditors to separate audit-design failures");
        assertTrue(summaryTemplate.contains("AUDIT_DESIGN"),
                "summary template must include an audit-design bucket");
    }
}
