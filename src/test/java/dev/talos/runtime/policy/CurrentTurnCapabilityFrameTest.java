package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentTurnCapabilityFrameTest {

    @Test
    void rendersActiveTaskContextGuidanceWhenPresent() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "make those changes");
        String activeTaskContext = "ACTIVE PROPOSED_CHANGES targets=[README.md] operation=APPLY_EDIT";
        String artifactGoal = "README APPLY_EDIT targets=[README.md] source=ACTIVE_CONTEXT";
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                activeTaskContext,
                artifactGoal,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ActiveTaskContext]"));
        assertTrue(frame.contains(activeTaskContext));
        assertTrue(frame.contains(artifactGoal));
        assertTrue(frame.contains("Active context is a current-turn hint only"));
        assertTrue(frame.contains("Explicit current user instructions win"));
        assertTrue(frame.contains("Use active targets only for narrow deictic follow-ups"));
        assertTrue(frame.contains("Do not broaden to unrelated workspace files"));
    }

    @Test
    void renderIncludesProposalApplyReadbackWriteGuidanceForActiveMarkdownProposal() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "Active task context: Add title and usage.\n\nFollow-up: Apply that README.md proposal now.");
        String activeTaskContext = "activeTaskContext{state=ACTIVE, kind=PROPOSED_CHANGES, "
                + "operation=APPLY_EDIT, targets=[README.md], proposal=Add title and usage.}";
        String artifactGoal = "artifactGoal{kind=README, operation=APPLY_EDIT, "
                + "targets=[README.md], source=ACTIVE_CONTEXT}";
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                List.of(),
                activeTaskContext,
                artifactGoal,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ProposalApply]"), frame);
        assertTrue(frame.contains("Apply the active proposed change to the active target"), frame);
        assertTrue(frame.contains("Read the target file first in this turn"), frame);
        assertTrue(frame.contains("prefer talos.write_file with complete updated content"), frame);
        assertTrue(frame.contains("Do not retry invalid talos.edit_file old_string guesses"), frame);
    }

    @Test
    void legacyRenderOmitsActiveTaskContextWhenNoPlanDerivedContextIsAvailable() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "make those changes");

        String frame = CurrentTurnCapabilityFrame.render(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"));

        assertFalse(frame.contains("[ActiveTaskContext]"));
        assertFalse(frame.contains("activeTaskContext:"));
        assertFalse(frame.contains("artifactGoal:"));
    }

    @Test
    void protectedReadFrameInstructsReadFileApprovalPath() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Read .env and tell me what it says.");

        String frame = CurrentTurnCapabilityFrame.render(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"));

        assertTrue(frame.contains("evidenceObligation: PROTECTED_READ_APPROVAL_REQUIRED"));
        assertTrue(frame.contains("Call talos.read_file for the protected target"));
        assertTrue(frame.contains("runtime will request approval"));
        assertTrue(frame.contains("Do not answer from protected content unless the read succeeds"));
    }

    @Test
    void renderIncludesCurrentTurnExactLiteralWriteExpectation() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ExactFileWrite]"), frame);
        assertTrue(frame.contains("target: index.html"), frame);
        assertTrue(frame.contains("sourcePattern: literal-overwrite-exactly"), frame);
        assertTrue(frame.contains("expectedBytes: 5"), frame);
        assertTrue(frame.contains("expectedChars: 5"), frame);
        assertTrue(frame.contains("expectedLines: 1"), frame);
        assertTrue(frame.contains("TALOS_CURRENT_TURN_EXACT_CONTENT"), frame);
        assertTrue(frame.contains("\nAFTER\n"), frame);
        assertTrue(frame.contains("Use this exact current-turn content for the complete file write"),
                frame);
        assertTrue(frame.contains("complete file content for index.html must equal the expectedContent payload exactly"),
                frame);
        assertTrue(frame.contains("Do not wrap it in HTML"), frame);
        assertTrue(frame.contains("content argument must be exactly the payload"), frame);
        assertTrue(frame.contains("Do not reuse exact-write literals from earlier turns"), frame);
    }

    @Test
    void mutatingGuidanceUsesOnlyVisibleMutatingTools() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html with exactly AFTER. Use talos.write_file.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("visibleTools: talos.write_file"), frame);
        assertTrue(frame.contains("Available mutating tools: talos.write_file."), frame);
        assertFalse(frame.contains("Available mutating tools: talos.write_file, talos.edit_file."), frame);
    }

    @Test
    void renderIncludesExactLiteralForMixedDirectoryAndFileCreate() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create a directory named workspace-notes and create workspace-notes/summary.txt "
                        + "containing exactly created by audit.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.mkdir", "talos.write_file"),
                List.of("talos.mkdir", "talos.write_file"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ExpectedTargets]"), frame);
        assertTrue(frame.contains("requiredTargets: workspace-notes, workspace-notes/summary.txt"), frame);
        assertTrue(frame.contains("[ExactFileWrite]"), frame);
        assertTrue(frame.contains("target: workspace-notes/summary.txt"), frame);
        assertTrue(frame.contains("sourcePattern: literal-create-containing-exactly"), frame);
        assertTrue(frame.contains("\ncreated by audit\n"), frame);
        assertTrue(frame.contains("visibleTools: talos.mkdir, talos.write_file"), frame);
        assertTrue(frame.contains("obligation: MUTATING_TOOL_REQUIRED"), frame);
        assertTrue(frame.contains("Use file tools to apply the requested workspace change"), frame);
        assertFalse(frame.contains("Use the visible workspace operation tool"), frame);
        assertFalse(frame.contains("Do not substitute a generic talos.write_file"), frame);
    }

    @Test
    void renderIncludesExpectedTargetsForMultiFileMutationTurns() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js. "
                        + "It should calculate BMI from height and weight.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file", "talos.edit_file"),
                List.of("talos.write_file", "talos.edit_file"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ExpectedTargets]"), frame);
        assertTrue(frame.contains("requiredTargets:"), frame);
        assertTrue(frame.contains("index.html"), frame);
        assertTrue(frame.contains("styles.css"), frame);
        assertTrue(frame.contains("scripts.js"), frame);
        assertTrue(frame.contains("You must write or edit these exact target paths"), frame);
        assertTrue(frame.contains("Similar filenames are not substitutes"), frame);
        assertTrue(frame.contains("script.js and scripts.js are different target paths"), frame);
        assertTrue(frame.contains("Available mutating tools: talos.write_file, talos.edit_file."), frame);
    }

    @Test
    void renderSeparatesReadThenCreateFromItSourceAndRequiredTargets() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "read long-notes.txt and create ideas/summary.md from it; do not read .env.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ExpectedTargets]"), frame);
        assertTrue(frame.contains("requiredTargets: ideas/summary.md"), frame);
        assertTrue(frame.contains("[SourceEvidenceTargets]"), frame);
        assertTrue(frame.contains("sourceTargets: long-notes.txt"), frame);
        assertFalse(frame.contains("requiredTargets: long-notes.txt"), frame);
        assertFalse(frame.contains(".env"), frame);
    }

    @Test
    void renderDoesNotRequireNegatedSimilarFileMention() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create a BMI calculator web page using exactly index.html, styles.css, scripts.js. "
                        + "Do not use script.js.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file", "talos.edit_file"),
                List.of("talos.write_file", "talos.edit_file"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("[ExpectedTargets]"), frame);
        assertTrue(frame.contains("requiredTargets:"), frame);
        assertTrue(frame.contains("index.html"), frame);
        assertTrue(frame.contains("styles.css"), frame);
        assertTrue(frame.contains("scripts.js"), frame);
        assertFalse(frame.contains("requiredTargets: index.html, styles.css, scripts.js, script.js"), frame);
        assertFalse(frame.contains("script.js, styles.css"), frame);
    }

    @Test
    void renderUsesWorkspaceOperationGuidanceForMoveTurns() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Move workspace-notes/readme-renamed.md to archive/readme-renamed.md.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.move_path"),
                List.of("talos.move_path"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("obligation: WORKSPACE_OPERATION_REQUIRED"), frame);
        assertTrue(frame.contains("Use the visible workspace operation tool"), frame);
        assertTrue(frame.contains("talos.move_path"), frame);
        assertTrue(frame.contains("Do not emulate move, copy, rename, or mkdir"), frame);
        assertFalse(frame.contains("Available mutating tools: talos.write_file, talos.edit_file"), frame);
        assertFalse(frame.contains("You must write or edit these exact target paths"), frame);
    }

    @Test
    void verifyOnlyDirectoryAwareFrameDistinguishesDirectoryAndFileTools() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Verify the final workspace paths for archive/readme-renamed.md, "
                        + "copies/readme-final.md, and scratch/nested/reports. Do not edit files.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.VERIFY,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("talos.list_dir", "talos.read_file"),
                List.of());

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertTrue(frame.contains("visibleTools: talos.list_dir, talos.read_file"), frame);
        assertTrue(frame.contains("Use talos.list_dir for directory paths"), frame);
        assertTrue(frame.contains("Use talos.read_file for file paths"), frame);
        assertTrue(frame.contains("Do not call mutating workspace operation tools"), frame);
        assertFalse(frame.contains("visibleTools: talos.write_file"), frame);
        assertFalse(frame.contains("visibleTools: talos.edit_file"), frame);
    }

    @Test
    void renderOmitsSuppressedContextDetailsFromModelGuidance() {
        TaskContract contract = new TaskContract(
                TaskType.SMALL_TALK,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "I am only chatting, please don't inspect my files.");
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.INSPECT,
                List.of(),
                List.of(),
                List.of(),
                "SUPPRESSED PROPOSED_CHANGES targets=[README.md] operation=APPLY_EDIT summary=Replace the README title",
                CurrentTurnPlan.NONE_OR_NOT_DERIVED,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertFalse(frame.contains("[ActiveTaskContext]"));
        assertFalse(frame.contains("README.md"));
        assertFalse(frame.contains("Replace the README"));
        assertFalse(frame.contains("Use active targets only for narrow deictic follow-ups"));
    }

    @Test
    void renderRedactsAndBoundsPlanDerivedActiveTaskContextFields() {
        TaskContract contract = new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("README.md"),
                Set.of(),
                "make those changes");
        String longBody = "LONG_ACTIVE_BODY ".repeat(2_000);
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                contract,
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                "ACTIVE API_KEY=secret " + longBody,
                "ARTIFACT API_KEY=secret " + longBody,
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        String frame = CurrentTurnCapabilityFrame.render(plan);

        assertFalse(frame.contains("API_KEY=secret"));
        assertTrue(frame.contains("API_KEY=[redacted]"));
        assertTrue(frame.contains("..."));
        assertFalse(frame.contains(longBody));
        assertTrue(frame.length() < 4_000, "frame should not include unbounded active context text");
    }
}
