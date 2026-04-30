package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
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
