package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityProfileRegistryTest {

    @Test
    void explicitHtmlCssJavaScriptWebTaskSelectsStaticWebProfile() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create index.html, styles.css, and scripts.js for a BMI calculator.");

        CapabilityProfile profile = CapabilityProfileRegistry.select(contract);

        assertTrue(profile.staticWeb());
        assertEquals("static-web", profile.id());
        assertEquals(ArtifactKind.STATIC_WEB, profile.artifactKind());
        assertEquals(ArtifactOperation.CREATE, profile.operation());
        assertEquals(TargetSurface.HTML_CSS_JS, profile.targetSurface());
        assertEquals(VerifierProfile.STATIC_WEB, profile.verifierProfile());
        assertEquals(RepairProfile.STATIC_WEB, profile.repairProfile());
    }

    @Test
    void naturalBmiWebCreationSelectsFunctionalStaticWebProfile() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Can you make me a working BMI calculator webpage here?");

        CapabilityProfile profile = CapabilityProfileRegistry.select(contract);

        assertTrue(profile.staticWeb());
        assertEquals(ArtifactOperation.CREATE, profile.operation());
        assertEquals(TargetSurface.FUNCTIONAL_WEB, profile.targetSurface());
        assertEquals(VerifierProfile.STATIC_WEB, profile.verifierProfile());
    }

    @Test
    void readmeAndConfigTasksDoNotSelectStaticWebProfile() {
        for (String prompt : java.util.List.of(
                "Update README.md with the new setup instructions.",
                "Create config.yaml for the service.")) {
            CapabilityProfile profile = CapabilityProfileRegistry.select(
                    TaskContractResolver.fromUserRequest(prompt));

            assertFalse(profile.staticWeb(), prompt);
            assertEquals(VerifierProfile.NONE, profile.verifierProfile(), prompt);
            assertEquals(RepairProfile.NONE, profile.repairProfile(), prompt);
        }
    }

    @Test
    void sourceDerivedSummarySelectsSourceDerivedVerifierProfile() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("summary.md"),
                Set.of("alpha.txt", "beta.txt"),
                Set.of(),
                "Summarize alpha.txt and beta.txt into summary.md.",
                "test-source-derived-summary");

        CapabilityProfile profile = CapabilityProfileRegistry.select(contract);

        assertFalse(profile.staticWeb());
        assertEquals("source-derived", profile.id());
        assertEquals(ArtifactKind.SOURCE_DERIVED_FILE, profile.artifactKind());
        assertEquals(ArtifactOperation.CREATE, profile.operation());
        assertEquals(TargetSurface.SOURCE_DERIVED_TEXT, profile.targetSurface());
        assertEquals(VerifierProfile.SOURCE_DERIVED, profile.verifierProfile());
        assertEquals(RepairProfile.NONE, profile.repairProfile());
    }

    @Test
    void staticWebProfileWinsForWebSurfaceEvenWhenTaskHasSourceEvidence() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("index.html", "styles.css", "scripts.js"),
                Set.of("brief.txt"),
                Set.of(),
                "Create index.html, styles.css, and scripts.js from brief.txt.",
                "test-web-from-brief");

        CapabilityProfile profile = CapabilityProfileRegistry.select(contract);

        assertTrue(profile.staticWeb());
        assertEquals("static-web", profile.id());
        assertEquals(ArtifactKind.STATIC_WEB, profile.artifactKind());
        assertEquals(VerifierProfile.STATIC_WEB, profile.verifierProfile());
    }

    @Test
    void markdownDocumentAboutWebpageDoesNotSelectStaticWebProfile() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create docs/synthwave-webpage-plan.md with a concise plan for a cool looking "
                        + "synthwave webpage for a band. Use a supported text format.");

        CapabilityProfile profile = CapabilityProfileRegistry.select(contract);

        assertFalse(profile.staticWeb());
        assertEquals(VerifierProfile.NONE, profile.verifierProfile());
        assertEquals(RepairProfile.NONE, profile.repairProfile());
    }

    @Test
    void deicticSiteCreationWithInferredExactTargetsSelectsStaticWebProfile() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Create a txt file about how to build a synthwave band's web page."),
                ChatMessage.assistant("[ok] Created synthwave_webpage_tutorial.txt"),
                ChatMessage.user("Great! now can you create that site?")));
        TaskContract contract = TaskContractResolver.fromMessages(messages);

        CapabilityProfile profile = CapabilityProfileRegistry.select(contract);

        assertEquals(java.util.Set.of("index.html", "style.css", "script.js"), contract.expectedTargets());
        assertTrue(profile.staticWeb());
        assertEquals(TargetSurface.HTML_CSS_JS, profile.targetSurface());
        assertEquals(VerifierProfile.STATIC_WEB, profile.verifierProfile());
    }
}
