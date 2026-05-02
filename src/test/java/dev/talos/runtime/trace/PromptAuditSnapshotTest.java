package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.CurrentTurnCapabilityFrame;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PromptAuditSnapshotTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void redactsSecretLikeCurrentTurnFramePreview() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.assistant("previous answer"));
        messages.add(ChatMessage.system("[CurrentTurnCapability]\nSECRET=changed\nAvailable: talos.write_file"));
        messages.add(ChatMessage.user("Overwrite .env with SECRET=changed. Use talos.write_file."));

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromMessages(
                contract("Overwrite .env with SECRET=changed. Use talos.write_file."),
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                ActionObligation.MUTATING_TOOL_REQUIRED,
                messages,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of());

        assertTrue(snapshot.currentTurnFrameInjected());
        assertEquals("AFTER_HISTORY_BEFORE_USER", snapshot.currentTurnFramePlacement());
        assertTrue(snapshot.currentTurnFramePreviewRedacted().contains("SECRET=[redacted]"));
        assertFalse(snapshot.currentTurnFramePreviewRedacted().contains("SECRET=changed"));

        String json = MAPPER.writeValueAsString(snapshot);
        assertFalse(json.contains("SECRET=changed"), "prompt audit must not store raw secret-like values");
        assertTrue(json.contains("SECRET=[redacted]"));
    }

    @Test
    void recordsMessageLayoutAndHashesWithoutRawPromptText() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("old prompt"));
        messages.add(ChatMessage.assistant("old answer"));
        messages.add(ChatMessage.system("[CurrentTurnCapability]\nTask type: FILE_CREATE"));
        messages.add(ChatMessage.user("I want to create a README file with SECRET=changed."));

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromMessages(
                contract("I want to create a README file with SECRET=changed."),
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                ActionObligation.MUTATING_TOOL_REQUIRED,
                messages,
                List.of("talos.write_file", "talos.edit_file"),
                List.of("talos.write_file", "talos.edit_file"),
                List.of());

        assertEquals("FILE_EDIT", snapshot.taskType());
        assertTrue(snapshot.mutationAllowed());
        assertEquals(2, snapshot.systemMessageCount());
        assertEquals(2, snapshot.userMessageCount());
        assertEquals(5, snapshot.totalMessageCount());
        assertFalse(snapshot.promptHash().isBlank());
        assertEquals(TraceRedactionMode.DEFAULT, snapshot.redactionMode());

        String json = MAPPER.writeValueAsString(snapshot);
        assertFalse(json.contains("SECRET=changed"), "prompt audit stores hashes/counts/previews, not raw prompt text");
    }

    @Test
    void recordsSmallTalkAuditWithNoToolsAndActualHistoryPolicy() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.user("Hello friend"));

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromMessages(
                new TaskContract(TaskType.SMALL_TALK, false, false, false, Set.of(), Set.of(), "Hello friend"),
                ExecutionPhase.INSPECT,
                ExecutionPhase.INSPECT,
                ActionObligation.DIRECT_ANSWER_ONLY,
                messages,
                List.of(),
                List.of(),
                List.of());

        assertEquals("SMALL_TALK", snapshot.taskType());
        assertEquals("DIRECT_ANSWER_ONLY", snapshot.actionObligation());
        assertEquals("SUPPRESSED", snapshot.historyPolicy());
        assertEquals(0, snapshot.historyMessageCount());
        assertTrue(snapshot.nativeTools().isEmpty());
        assertTrue(snapshot.promptTools().isEmpty());
    }

    @Test
    void currentTurnFramePreviewPreservesDirectAnswerPolicyDirectives() {
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                new TaskContract(
                        TaskType.SMALL_TALK,
                        false,
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        "Without inspecting the workspace, explain how you would review a Java CLI project."),
                ExecutionPhase.INSPECT,
                List.of(),
                List.of(),
                List.of());
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.system(CurrentTurnCapabilityFrame.render(plan)),
                ChatMessage.user("Without inspecting the workspace, explain how you would review a Java CLI project."));

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromPlan(plan, messages);

        assertTrue(snapshot.currentTurnFramePreviewRedacted().contains("No workspace tools are visible"),
                snapshot.currentTurnFramePreviewRedacted());
        assertTrue(snapshot.currentTurnFramePreviewRedacted().contains("Do not call tools"),
                snapshot.currentTurnFramePreviewRedacted());
    }

    @Test
    void currentTurnFramePreviewPreservesDirectoryListingPolicyDirectives() {
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                new TaskContract(
                        TaskType.DIRECTORY_LISTING,
                        false,
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        "List files only; do not show content from README.md or notes.md."),
                ExecutionPhase.INSPECT,
                List.of("talos.list_dir"),
                List.of("talos.list_dir"),
                List.of());
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.system(CurrentTurnCapabilityFrame.render(plan)),
                ChatMessage.user("List files only; do not show content from README.md or notes.md."));

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromPlan(plan, messages);

        assertTrue(snapshot.currentTurnFramePreviewRedacted().contains("Use only talos.list_dir"),
                snapshot.currentTurnFramePreviewRedacted());
        assertTrue(snapshot.currentTurnFramePreviewRedacted().contains("do not inspect file contents"),
                snapshot.currentTurnFramePreviewRedacted());
    }

    @Test
    void fromPlanUsesPlanFieldsAndHonestPlaceholders() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.system("[CurrentTurnCapability]\ntype: FILE_EDIT"));
        messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                new TaskContract(
                        TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        Set.of("index.html"),
                        Set.of(),
                        "Overwrite index.html with exactly AFTER. Use talos.write_file."),
                ExecutionPhase.APPLY,
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.shell"));

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromPlan(plan, messages);

        assertEquals("FILE_EDIT", snapshot.taskType());
        assertTrue(snapshot.mutationAllowed());
        assertTrue(snapshot.verificationRequired());
        assertEquals("APPLY", snapshot.phaseInitial());
        assertEquals("APPLY", snapshot.phaseFinal());
        assertEquals("MUTATING_TOOL_REQUIRED", snapshot.actionObligation());
        assertEquals("NONE", snapshot.evidenceObligation());
        assertEquals(PromptAuditSnapshot.NOT_DERIVED, snapshot.outputObligation());
        assertEquals(PromptAuditSnapshot.NONE_OR_NOT_DERIVED, snapshot.activeTaskContext());
        assertEquals(PromptAuditSnapshot.NONE_OR_NOT_DERIVED, snapshot.artifactGoal());
        assertEquals(PromptAuditSnapshot.NONE_OR_NOT_DERIVED, snapshot.verifierProfile());
        assertEquals(List.of("talos.read_file", "talos.write_file"), snapshot.nativeTools());
        assertEquals(List.of("talos.read_file", "talos.write_file"), snapshot.promptTools());
        assertEquals(List.of("talos.shell"), snapshot.blockedTools());
    }

    @Test
    void renderCompactIncludesDerivedReadTargetEvidenceObligation() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.user("Read README.md and summarize it."));
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                new TaskContract(
                        TaskType.READ_ONLY_QA,
                        false,
                        false,
                        false,
                        Set.of("README.md"),
                        Set.of(),
                        "Read README.md and summarize it."),
                ExecutionPhase.INSPECT,
                List.of("talos.read_file"),
                List.of("talos.read_file"),
                List.of());

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromPlan(plan, messages);

        assertTrue(snapshot.renderCompact().contains("evidenceObligation: READ_TARGET_REQUIRED"));
    }

    @Test
    void fromPlanShowsActiveContextPresenceInCompactRender() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("system"),
                ChatMessage.user("make those changes"));
        CurrentTurnPlan plan = CurrentTurnPlan.create(
                new TaskContract(
                        TaskType.FILE_EDIT,
                        true,
                        true,
                        true,
                        Set.of("README.md"),
                        Set.of(),
                        "make those changes"),
                ExecutionPhase.APPLY,
                List.of("talos.write_file"),
                List.of("talos.write_file"),
                List.of(),
                "ACTIVE PROPOSED_CHANGES targets=[README.md] operation=APPLY_EDIT",
                "README APPLY_EDIT targets=[README.md] source=ACTIVE_CONTEXT",
                CurrentTurnPlan.NONE_OR_NOT_DERIVED);

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromPlan(plan, messages);

        String compact = snapshot.renderCompact();
        assertTrue(compact.contains("activeTaskContext: ACTIVE PROPOSED_CHANGES"));
        assertTrue(compact.contains("artifactGoal: README APPLY_EDIT"));
    }

    @Test
    void redactsPlanDerivedAuditFields() throws Exception {
        CurrentTurnPlan plan = new CurrentTurnPlan(
                contract("Use secret-like values for audit fields."),
                "Use secret-like values for audit fields.",
                ExecutionPhase.APPLY,
                ExecutionPhase.APPLY,
                ActionObligation.MUTATING_TOOL_REQUIRED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "evidence SECRET=changed",
                "output TOKEN=abc",
                "context PASSWORD=pw",
                "artifact API_KEY=key",
                "verifier CREDENTIAL=cred");
        List<ChatMessage> messages = List.of(ChatMessage.system("system"));

        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromPlan(plan, messages);

        assertTrue(snapshot.evidenceObligation().contains("SECRET=[redacted]"));
        assertTrue(snapshot.outputObligation().contains("TOKEN=[redacted]"));
        assertTrue(snapshot.activeTaskContext().contains("PASSWORD=[redacted]"));
        assertTrue(snapshot.artifactGoal().contains("API_KEY=[redacted]"));
        assertTrue(snapshot.verifierProfile().contains("CREDENTIAL=[redacted]"));
        assertNoRawSecretValues(
                snapshot.evidenceObligation(),
                snapshot.outputObligation(),
                snapshot.activeTaskContext(),
                snapshot.artifactGoal(),
                snapshot.verifierProfile());

        String json = MAPPER.writeValueAsString(snapshot);
        assertNoRawSecretValues(json);

        String compact = snapshot.renderCompact();
        assertNoRawSecretValues(compact);
    }

    @Test
    void fromMessagesPreservesLegacyNullAuditFields() {
        PromptAuditSnapshot snapshot = PromptAuditSnapshot.fromMessages(
                null,
                null,
                null,
                null,
                List.of(ChatMessage.system("system")),
                null,
                null,
                null);

        assertEquals("", snapshot.taskType());
        assertEquals("", snapshot.phaseInitial());
        assertEquals("", snapshot.phaseFinal());
        assertEquals("", snapshot.actionObligation());
        assertFalse(snapshot.mutationAllowed());
        assertFalse(snapshot.verificationRequired());
        assertTrue(snapshot.nativeTools().isEmpty());
        assertTrue(snapshot.promptTools().isEmpty());
        assertTrue(snapshot.blockedTools().isEmpty());
    }

    private static void assertNoRawSecretValues(String... values) {
        for (String value : values) {
            assertFalse(value.contains("SECRET=changed"), value);
            assertFalse(value.contains("TOKEN=abc"), value);
            assertFalse(value.contains("PASSWORD=pw"), value);
            assertFalse(value.contains("API_KEY=key"), value);
            assertFalse(value.contains("CREDENTIAL=cred"), value);
        }
    }

    private static TaskContract contract(String request) {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of(".env"),
                Set.of(),
                request);
    }
}
