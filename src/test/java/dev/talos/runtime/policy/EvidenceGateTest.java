package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceGateTest {

    @Test
    void selectedObligationPrefersRecordedPlanValue(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.SMALL_TALK,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "hello");
        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.INSPECT,
                ExecutionPhase.INSPECT,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                EvidenceObligation.READ_TARGET_REQUIRED.name(),
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        assertEquals(EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceGate.selectObligation(plan, workspace));
    }

    @Test
    void readTargetHandoffSkipsProtectedTargets(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of("README.md", ".env"),
                Set.of(),
                "Read README.md and summarize it.");

        List<String> targets = EvidenceGate.handoffTargets(
                contract,
                EvidenceObligation.READ_TARGET_REQUIRED,
                workspace);

        assertTrue(targets.contains("README.md"), targets.toString());
        assertFalse(targets.contains(".env"), targets.toString());
    }

    @Test
    void pathExistenceHandoffUsesNamedNonProtectedTargets(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.DIAGNOSE_ONLY,
                false,
                false,
                false,
                Set.of("scripts.js", "script.js"),
                Set.of(),
                "Check whether scripts.js exists and whether script.js exists. Do not change anything.");

        assertTrue(EvidenceGate.requiresReadEvidenceHandoff(
                EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED));
        assertEquals(
                Set.of("scripts.js", "script.js"),
                Set.copyOf(EvidenceGate.handoffTargets(
                        contract,
                        EvidenceObligation.PATH_EXISTENCE_EVIDENCE_REQUIRED,
                        workspace)));
    }

    @Test
    void protectedReadHandoffRequiresExplicitReadIntent(@TempDir Path workspace) {
        TaskContract readEnv = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(".env"),
                Set.of(),
                "Read .env and tell me what it contains.");
        TaskContract mentionOnly = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(".env"),
                Set.of(),
                "Is .env a protected path?");
        TaskContract negated = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(".env"),
                Set.of(),
                "Do not read .env; explain why it is protected.");

        assertTrue(EvidenceGate.hasExplicitProtectedReadIntent(
                readEnv,
                EvidenceGate.protectedExpectedTargets(readEnv, workspace)));
        assertFalse(EvidenceGate.hasExplicitProtectedReadIntent(
                mentionOnly,
                EvidenceGate.protectedExpectedTargets(mentionOnly, workspace)));
        assertFalse(EvidenceGate.hasExplicitProtectedReadIntent(
                negated,
                EvidenceGate.protectedExpectedTargets(negated, workspace)));
    }

    @Test
    void unsupportedCapabilityTargetsAreSelectedSeparately(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of("slides.pptx", "README.md"),
                Set.of(),
                "Read slides.pptx and README.md.");

        assertFalse(EvidenceGate.hasOnlyUnsupportedExpectedTargets(contract));
        assertEquals(List.of("slides.pptx"), EvidenceGate.handoffTargets(
                contract,
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED,
                workspace));
    }

    @Test
    void configAwareSelectionUpgradesEnabledImageOcrToReadTarget(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of("image.png"),
                Set.of(),
                "Summarize image.png using OCR text only.");
        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract,
                contract.originalUserRequest(),
                ExecutionPhase.INSPECT,
                ExecutionPhase.INSPECT,
                null,
                List.of(),
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of(),
                EvidenceObligation.UNSUPPORTED_CAPABILITY_CHECK_REQUIRED.name(),
                CurrentTurnPlan.NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        assertEquals(EvidenceObligation.READ_TARGET_REQUIRED,
                EvidenceGate.selectObligation(plan, workspace, imageOcrEnabledConfig()));
        assertEquals(List.of("image.png"), EvidenceGate.handoffTargets(
                contract,
                EvidenceObligation.READ_TARGET_REQUIRED,
                workspace,
                imageOcrEnabledConfig()));
    }

    @Test
    void sourceEvidenceTargetsDriveHandoffInsteadOfMutationTargets(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.FILE_CREATE,
                true,
                true,
                true,
                Set.of("docs/summary.md"),
                Set.of("long-notes.txt"),
                Set.of(),
                "Summarize long-notes.txt into docs/summary.md.",
                "explicit-source-to-target-artifact-request");

        assertEquals(List.of("long-notes.txt"), EvidenceGate.handoffTargets(
                contract,
                EvidenceObligation.READ_TARGET_REQUIRED,
                workspace));
    }

    // ── T900: inferred static-web satellites absent on disk must not be hard read evidence ──

    @Test
    void absentInferredStaticWebSatellitesAreDroppedFromReadEvidence(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Acme</h1>");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT, true, false, true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of(), Set.of(),
                "redesign this page to make it visually better",
                "contextual-static-web-follow-up; capability-posture-read-only");

        Set<String> targets = EvidenceGate.withoutAbsentInferredStaticWebSatellites(
                contract, contract.expectedTargets(), workspace);

        assertEquals(Set.of("index.html"), targets);
    }

    @Test
    void presentStaticWebSatelliteIsKeptAsReadEvidence(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Acme</h1>");
        Files.writeString(workspace.resolve("style.css"), "body{}");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT, true, false, true,
                Set.of("index.html", "style.css", "script.js"),
                Set.of(), Set.of(),
                "redesign this page",
                "contextual-static-web-follow-up");

        Set<String> targets = EvidenceGate.withoutAbsentInferredStaticWebSatellites(
                contract, contract.expectedTargets(), workspace);

        assertTrue(targets.contains("index.html"), targets.toString());
        assertTrue(targets.contains("style.css"), targets.toString());   // present on disk -> keep
        assertFalse(targets.contains("script.js"), targets.toString());  // absent + inferred -> drop
    }

    @Test
    void userNamedSatelliteIsKeptEvenWhenAbsent(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), "<h1>Acme</h1>");
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA, false, false, false,
                Set.of("index.html", "style.css"),
                Set.of(), Set.of(),
                "read style.css and tell me the colors",  // user named style.css explicitly
                "test");

        Set<String> targets = EvidenceGate.withoutAbsentInferredStaticWebSatellites(
                contract, contract.expectedTargets(), workspace);

        assertTrue(targets.contains("style.css"), targets.toString());  // named -> keep even though absent
    }

    @Test
    void unrelatedFileSharingASatelliteSubstringDoesNotKeepTheAbsentSatellite(@TempDir Path workspace) throws Exception {
        // T904: the named-check is word-boundary, not raw substring. "myscript.js"
        // must NOT count as naming "script.js", so an absent inferred script.js is
        // still dropped (no residual false-block).
        Files.writeString(workspace.resolve("index.html"), "<h1>Acme</h1>");
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT, true, false, true,
                Set.of("index.html", "script.js"),
                Set.of(), Set.of(),
                "refactor myscript.js to be cleaner",
                "contextual-static-web-follow-up");

        Set<String> targets = EvidenceGate.withoutAbsentInferredStaticWebSatellites(
                contract, contract.expectedTargets(), workspace);

        assertFalse(targets.contains("script.js"), targets.toString());  // absent + not truly named -> dropped
        assertTrue(targets.contains("index.html"), targets.toString());
    }

    @Test
    void nonStaticWebAbsentTargetsAreNotDropped(@TempDir Path workspace) {
        TaskContract contract = new TaskContract(
                TaskType.READ_ONLY_QA, false, false, false,
                Set.of("notes.md"), Set.of(), Set.of(),
                "read notes.md", "test");

        Set<String> targets = EvidenceGate.withoutAbsentInferredStaticWebSatellites(
                contract, contract.expectedTargets(), workspace);

        assertEquals(Set.of("notes.md"), targets);  // not a conventional satellite -> untouched
    }

    private static Config imageOcrEnabledConfig() {
        Config cfg = new Config(null);
        Map<String, Object> extraction = new LinkedHashMap<>();
        extraction.put("enabled", Boolean.TRUE);
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("enabled", Boolean.TRUE);
        extraction.put("image_ocr", image);
        cfg.data.put("document_extraction", extraction);
        return cfg;
    }
}
