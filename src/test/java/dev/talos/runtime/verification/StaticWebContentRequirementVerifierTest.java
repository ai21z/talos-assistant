package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticWebContentRequirementVerifierTest {

    @TempDir
    Path workspace;

    @Test
    void headingOnlyRequestCreatesOneSatisfiedRequiredClaim() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html><body><h1>Welcome <span>to Atlas</span></h1></body></html>
                """);

        VerificationReport report = StaticWebContentRequirementVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Change the page heading to Welcome to Atlas. Leave JavaScript unchanged."));

        assertEquals(1, report.requiredClaimCount(), report.toString());
        assertTrue(report.requiredClaimsSatisfied(), report.toString());
    }

    @Test
    void vaguePresentationWordingDoesNotManufactureLiteralClaims() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Offline guide</h1>\n");

        VerificationReport report = StaticWebContentRequirementVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Polish the heading and make index.html a page about Offline support."));

        assertFalse(report.hasRequiredClaims(), report.toString());
    }

    @Test
    void ambiguousHtmlTargetMakesEveryExplicitClaimUnavailable() throws Exception {
        Files.writeString(workspace.resolve("one.html"), "<h1>Atlas Control</h1>\n");
        Files.writeString(workspace.resolve("two.html"), "<h1>Atlas Control</h1>\n");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of(),
                Set.of(),
                Set.of(),
                "Set the page heading to Atlas Control.",
                "test-ambiguous-html-target");

        VerificationReport report = StaticWebContentRequirementVerifier.verify(workspace, contract);

        assertEquals(1, report.requiredClaimCount(), report.toString());
        assertEquals(1, report.unsatisfiedRequiredClaimCount(), report.toString());
        assertTrue(report.hasRequiredUnavailable(), report.toString());
    }

    @Test
    void statusLikeParagraphControlsOverUnrelatedParagraph() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <html><body>
                  <p>Offline information for support.</p>
                  <p id="message">Waiting</p>
                </body></html>
                """);

        VerificationReport report = StaticWebContentRequirementVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update index.html so the status paragraph starts Offline."));

        assertEquals(1, report.requiredClaimCount(), report.toString());
        assertEquals(1, report.unsatisfiedRequiredClaimCount(), report.toString());
        assertTrue(report.hasRequiredFailure(), report.toString());
    }

    @Test
    void negatedHeadingExampleDoesNotBecomeARequiredPostcondition() throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Atlas Control</h1>\n");

        VerificationReport report = StaticWebContentRequirementVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update index.html. Do not set the page heading to Offline."));

        assertFalse(report.hasRequiredClaims(), report.toString());
    }

    @Test
    void sameSentenceScopeRestrictionIsNotPartOfExpectedVisibleText() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <html><body>
                  <h1>Atlas Control</h1>
                  <p id="message">Offline now.</p>
                </body></html>
                """);

        VerificationReport report = StaticWebContentRequirementVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "Update index.html so the heading is Atlas Control and the status paragraph starts Offline "
                                + "and do not touch CSS or JavaScript."));

        assertEquals(2, report.requiredClaimCount(), report.toString());
        assertTrue(report.requiredClaimsSatisfied(), report.toString());
    }

    @Test
    void descriptiveCurrentStateDoesNotBecomeARequiredPostcondition() throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <html><body>
                  <h1>Current title</h1>
                  <p id="message">Waiting</p>
                </body></html>
                """);

        VerificationReport heading = StaticWebContentRequirementVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "The heading is wrong. Change only the button label in index.html."));
        VerificationReport paragraph = StaticWebContentRequirementVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(
                        "The status paragraph starts Offline. Change only the button label in index.html."));

        assertFalse(heading.hasRequiredClaims(), heading.toString());
        assertFalse(paragraph.hasRequiredClaims(), paragraph.toString());
    }
}
