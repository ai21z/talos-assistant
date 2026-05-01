package dev.talos.runtime.capability;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

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
}
