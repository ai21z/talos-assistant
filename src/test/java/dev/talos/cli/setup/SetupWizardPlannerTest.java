package dev.talos.cli.setup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupWizardPlannerTest {

    @Test
    void freshUbuntuWslPlanIsDryRunOnlyAndQueuesManualChoices() {
        SetupWizardSnapshot snapshot = new SetupWizardSnapshot(
                "Linux",
                "amd64",
                true,
                "Ubuntu 26.04 LTS",
                21,
                Path.of("/home/ai21z/.talos/config.yaml"),
                false,
                null,
                false,
                512_000,
                16_384);

        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot);

        assertFalse(plan.hasSideEffects(), "dry-run planner must not select side effects");
        assertEquals(SetupWizardStep.Action.SKIP, plan.requiredStep("java").action());
        assertEquals(SetupWizardStep.Action.ASK, plan.requiredStep("config").action());
        assertEquals(SetupWizardStep.Action.ASK, plan.requiredStep("llama-server").action());
        assertEquals(SetupWizardStep.Action.ASK, plan.requiredStep("model-profile").action());
        assertTrue(plan.requiredStep("model-profile").detail().contains("qwen2.5-coder-14b"));
        assertTrue(plan.requiredStep("model-profile").detail().contains("gpt-oss-20b"));
        assertTrue(plan.requiredStep("verification").detail().contains("talos doctor --start"));
    }

    @Test
    void existingCompatibleRuntimeStateIsDetectedAsReuseOrSkip() {
        SetupWizardSnapshot snapshot = new SetupWizardSnapshot(
                "Linux",
                "amd64",
                true,
                "Ubuntu 26.04 LTS",
                21,
                Path.of("/home/ai21z/.talos/config.yaml"),
                true,
                Path.of("/home/ai21z/.local/bin/llama-server"),
                true,
                512_000,
                16_384);

        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot);

        assertEquals(SetupWizardStep.Action.SKIP, plan.requiredStep("java").action());
        assertEquals(SetupWizardStep.Action.REUSE_OR_ASK, plan.requiredStep("config").action());
        assertEquals(SetupWizardStep.Action.REUSE_OR_ASK, plan.requiredStep("llama-server").action());
        assertTrue(plan.requiredStep("config").detail().contains("Existing config detected"));
        assertTrue(plan.requiredStep("llama-server").detail().contains("Linux-compatible llama-server detected"));
    }

    @Test
    void windowsExecutableVisibleFromWslIsNotAcceptedAsCompatibleServer() {
        SetupWizardSnapshot snapshot = new SetupWizardSnapshot(
                "Linux",
                "amd64",
                true,
                "Ubuntu 26.04 LTS",
                21,
                Path.of("/home/ai21z/.talos/config.yaml"),
                true,
                Path.of("/mnt/c/Tools/llama-server.exe"),
                true,
                512_000,
                16_384);

        SetupWizardPlan plan = SetupWizardPlanner.plan(snapshot);

        assertEquals(SetupWizardStep.Action.BLOCK_OR_ASK, plan.requiredStep("llama-server").action());
        assertTrue(plan.requiredStep("llama-server").detail().contains("Windows .exe"));
        assertTrue(plan.requiredStep("llama-server").detail().contains("Linux-compatible"));
    }
}
