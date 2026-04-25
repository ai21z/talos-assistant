package dev.talos.runtime.task;

import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskContractResolverTest {

    @Test
    void explicitEditRequestBecomesFileEditContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Edit index.html so the title says Night Signal.");

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("index.html"), contract.expectedTargets());
    }

    @Test
    void createRequestBecomesFileCreateContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Create a README.md file with a short project description.");

        assertEquals(TaskType.FILE_CREATE, contract.type());
        assertTrue(contract.mutationAllowed());
        assertEquals(Set.of("README.md"), contract.expectedTargets());
    }

    @Test
    void readOnlySelectorCheckBecomesDiagnoseOnlyContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Check whether this website has mismatches between HTML classes and CSS selectors. Do not change anything.");

        assertEquals(TaskType.DIAGNOSE_ONLY, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertFalse(contract.verificationRequired());
    }

    @Test
    void workspaceQuestionBecomesWorkspaceExplainContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "What files are in this workspace?");

        assertEquals(TaskType.WORKSPACE_EXPLAIN, contract.type());
        assertFalse(contract.mutationAllowed());
    }

    @Test
    void metaQuestionAboutEditToolStaysReadOnly() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Why didn't you call the edit tool?");

        assertEquals(TaskType.READ_ONLY_QA, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
    }

    @Test
    void targetExtractionFindsMultipleObviousFiles() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Update index.html and style.css, but leave script.js alone.");

        assertEquals(Set.of("index.html", "style.css", "script.js"), contract.expectedTargets());
    }

    @Test
    void syntheticToolResultTailIsSkippedWhenResolvingFromMessages() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user("Edit index.html."));
        messages.add(ChatMessage.assistant("I will call a tool."));
        messages.add(ChatMessage.user("[tool_result: talos.edit_file]\n[ok]\n[/tool_result]"));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationAllowed());
        assertEquals(Set.of("index.html"), contract.expectedTargets());
    }

    @Test
    void nullOrBlankInputIsUnknown() {
        List<String> inputs = List.of("", "   ");
        for (String input : inputs) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);
            assertEquals(TaskType.UNKNOWN, contract.type());
            assertFalse(contract.mutationAllowed());
        }
    }
}
