package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelLifecycleDocumentationTest {

    private static final List<String> MAINTAINED_MODEL_WORKFLOW_FILES = List.of(
            "docs/architecture/model-profile-lifecycle.md",
            "docs/development/onboard-new-models.md",
            "work-cycle-docs/runbooks/model-qualification.md",
            "work-cycle-docs/templates/model-calibration-guide.md",
            "work-cycle-docs/templates/model-scorecard.md");

    @Test
    void maintainedModelWorkflowFilesExistAndAreLinked() throws Exception {
        for (String file : MAINTAINED_MODEL_WORKFLOW_FILES) {
            assertTrue(Files.isRegularFile(Path.of(file)), "Missing maintained model workflow file: " + file);
        }

        String docsIndex = read("docs/index.md");
        assertTrue(docsIndex.contains("architecture/model-profile-lifecycle.md"));
        assertTrue(docsIndex.contains("development/onboard-new-models.md"));

        String workIndex = read("work-cycle-docs/README.md");
        assertTrue(workIndex.contains("runbooks/model-qualification.md"));
        assertTrue(workIndex.contains("templates/model-calibration-guide.md"));
        assertTrue(workIndex.contains("templates/model-scorecard.md"));
    }

    @Test
    void lifecycleSeparatesEvidenceDispositionAndHardwareSupport() throws Exception {
        String lifecycle = read("docs/architecture/model-profile-lifecycle.md");

        assertContains(lifecycle, "Evidence progress is not a product disposition.");
        assertContains(lifecycle, "Model quality and hardware support are separate decisions.");
        assertContains(lifecycle, "Passing admission is not a support claim.");
        assertContains(lifecycle, "research candidate");
        assertContains(lifecycle, "artifact frozen");
        assertContains(lifecycle, "compatibility passed");
        assertContains(lifecycle, "admission passed");
        assertContains(lifecycle, "full qualification complete");
        assertContains(lifecycle, "accepted beta stability");
        assertContains(lifecycle, "experimental selectable");
        assertContains(lifecycle, "custom unverified");
        assertContains(lifecycle, "lab only");
        assertContains(lifecycle, "rejected for tool use");
        assertContains(lifecycle, "retired");
        assertContains(lifecycle, "LlamaCppModelCatalog");
        assertContains(lifecycle, "local/model-qualification");
    }

    @Test
    void publicProfileReferenceKeepsLegacyAndHardwareBoundariesExplicit() throws Exception {
        String profiles = read("docs/reference/model-profiles.md");

        assertContains(profiles, "Passing admission is not a support claim.");
        assertContains(profiles, "Model quality and hardware support are separate decisions.");
        assertContains(profiles, "Legacy evidence remains legacy until a current protocol packet replaces it.");
        assertFalse(profiles.contains("admission passed means supported"));
        assertFalse(profiles.contains("doctor --start promotes"));
    }

    @Test
    void sessionSelectionDocumentsLocalOnlyScopeWithoutConflatingReadinessAndQualification() throws Exception {
        String setup = read("docs/getting-started/model-setup.md");
        for (String required : List.of(
                "/models use <alias>", "/set model <alias>",
                "/models add my-agent \"D:/models/agent.gguf\"",
                "~/.talos/models/gguf/<alias>/<artifactFile>",
                "Imports and selections apply to this session.",
                "do not download models", "Connect-only servers are not restarted",
                "Saved defaults and conversation history are preserved",
                "`tools.native_calling`", "sampling and embedding configuration",
                "A local candidate is not proof of readiness.",
                "Registering a custom GGUF does not qualify it",
                "Saved session history is restored even when its saved model cannot be selected",
                "another explicit successful selection is required")) {
            assertContains(setup, required);
        }
        String profiles = read("docs/reference/model-profiles.md");
        assertContains(profiles, "model-setup.md#select-a-model-in-the-current-session");
        assertContains(profiles, "not artifact verification, readiness or qualification");
        assertContains(profiles, "Built-in aliases require the pinned artifact bytes.");
        assertContains(read("README.md"), "docs/getting-started/model-setup.md#select-a-model-in-the-current-session");
    }

    @Test
    void onboardingGuideCoversTheCompleteBoundedWorkflow() throws Exception {
        String guide = read("docs/development/onboard-new-models.md");

        for (String required : List.of(
                "Choose a candidate",
                "Check license and provenance",
                "Freeze the artifact",
                "Run compatibility preflight",
                "Freeze calibration",
                "Run admission",
                "Run full qualification",
                "Validate physical hardware",
                "Project a profile",
                "Demote or retire a profile")) {
            assertContains(guide, required);
        }
        assertContains(guide, "Upstream benchmarks select candidates. They do not promote Talos profiles.");
        assertContains(guide, "A material change creates a new configuration identity.");
    }

    @Test
    void runbookAndTemplatesKeepMachineReadableEvidenceAuthoritative() throws Exception {
        String runbook = read("work-cycle-docs/runbooks/model-qualification.md");
        String calibration = read("work-cycle-docs/templates/model-calibration-guide.md");
        String scorecard = read("work-cycle-docs/templates/model-scorecard.md");

        assertContains(runbook, "Machine-readable JSON is authoritative.");
        assertContains(runbook, "12 admission trials");
        assertContains(runbook, "72 full trials");
        assertContains(runbook, "Stop the campaign");
        assertContains(runbook, "canonical order is repetition-major");
        assertContains(runbook, "finalizeModelQualificationTraceEvidence");
        assertContains(runbook, "finalizeModelQualificationProviderEvidence");
        assertContains(runbook, "finalizeModelQualificationApprovals");
        assertContains(runbook, "finalizeModelQualificationRequirements");
        assertContains(runbook, "finalizeModelQualificationResult");
        assertContains(runbook, "Validate only after completion");
        assertContains(runbook, "exact `COMPLETE_GRID` completion");
        assertContains(runbook, "`HARD_STOP` completion is final evidence but is never scoreable");
        assertContains(runbook, "Invoke the typed reconciler before retrying");
        assertContains(runbook, "Recovery of one phase is not permission");
        assertContains(runbook, "Java selects the packet-contained session trace solely from");
        assertContains(runbook, "PowerShell may capture raw");
        assertContains(runbook, "but it must not select, copy, or");
        assertContains(runbook, "printModelQualificationLaunchEnvironmentIdentity --no-daemon");
        assertContains(runbook, "launchEnvironmentContract");
        assertContains(runbook, "launchEnvironmentSha256");
        assertContains(runbook, "endpointObserverKind");
        assertContains(runbook, "endpointObserverIdentitySha256");
        assertContains(runbook, "wildcard exposure");
        assertContains(runbook, "endpoint observer is itself part of the receipt identity");
        assertContains(runbook, "valid Authenticode signer");
        assertContains(runbook, "versioned procfs managed-process-tree contract");
        assertContains(runbook, "does not delegate the same listener descriptor outside");
        assertContains(runbook, "does not retain an independent raw `/props` response");
        assertContains(runbook, "does not identify every dynamically loaded");
        assertContains(runbook, "local-sensitive evidence, not a");
        assertContains(runbook, "shareable or export-safe artifact");
        assertContains(runbook, "provider-process receipt and every trace, causal, result");
        assertContains(runbook, "never raw launch arguments");
        assertContains(calibration, "Configuration identity");
        assertContains(calibration, "Permitted deviations");
        assertContains(scorecard, "Trust verdict");
        assertContains(scorecard, "Hardware support verdict");
    }

    @Test
    void runbookPinsQualificationHarnessFreezeAndResourceEvidenceRules() throws Exception {
        String runbook = read("work-cycle-docs/runbooks/model-qualification.md");

        assertContains(runbook, "derive the scenario-bank hash from the declared protocol resources");
        assertContains(runbook, "compile the qualification harness before hashing its classes");
        assertContains(runbook, "admission and full scenario banks for the declared protocol");
        assertContains(runbook, "basic, context, privacy, and web fixtures");
        assertContains(runbook, "UTC or an explicit numeric offset");
        assertContains(runbook, "Reject timestamps without a time zone");
        assertContains(runbook, "Windows pagefile usage is system-wide evidence");
        assertContains(runbook, "Do not attribute a small host pagefile change to Talos");
        assertContains(runbook, "4 GiB measured system-memory headroom");
    }

    @Test
    void publicDocsDoNotLeakTicketIdsFromTheModelProgram() throws Exception {
        Pattern ticket = Pattern.compile("\\bT\\d{3,}\\b");
        for (String file : List.of(
                "docs/architecture/model-profile-lifecycle.md",
                "docs/development/onboard-new-models.md",
                "docs/reference/model-profiles.md")) {
            String body = read(file);
            assertFalse(ticket.matcher(body).find(), "Public model documentation leaks a ticket id: " + file);
        }
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static void assertContains(String text, String expected) {
        assertTrue(text.contains(expected), "Missing model lifecycle contract wording: " + expected);
    }
}
