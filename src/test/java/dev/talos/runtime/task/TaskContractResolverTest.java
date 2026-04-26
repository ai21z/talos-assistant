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
    void buildWebsiteRequestBecomesFileCreateContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Can you build a small BMI calculator website here with separate CSS and JavaScript files? "
                        + "Use the file tools if you can; do not just show code.");

        assertEquals(TaskType.FILE_CREATE, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
    }

    @Test
    void prefixedMakeWebsiteRequestBecomesFileCreateContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Ah okay can you make a cool looking BMI calculator website? "
                        + "I want different files for styling and scripting please. "
                        + "I want it modern user friendly and functioning.");

        assertEquals(TaskType.FILE_CREATE, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void makeItRequestRemainsMutationCapableForFollowUpTurns() {
        TaskContract contract = TaskContractResolver.fromUserRequest("Can you make it?");

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void trivialGreetingBecomesSmallTalkContract() {
        for (String input : List.of("hello", "hey", "hi!", "good morning", "thanks")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.SMALL_TALK, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
        }
    }

    @Test
    void greetingWithWorkspaceIntentStillInspectsWorkspace() {
        TaskContract contract = TaskContractResolver.fromUserRequest("Hey, what is in this workspace?");

        assertEquals(TaskType.WORKSPACE_EXPLAIN, contract.type());
        assertFalse(contract.mutationAllowed());
    }

    @Test
    void buildAndMakeQuestionsRemainReadOnlyWhenNotAskingForWorkspaceMutation() {
        List<String> inputs = List.of(
                "What can you build?",
                "Can you explain how to build a BMI calculator?",
                "Can you make sense of this code?",
                "Why did you not make changes?",
                "Show me how to make one, do not edit files.");

        for (String input : inputs) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);
            assertEquals(TaskType.READ_ONLY_QA, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
        }
    }

    @Test
    void scopedNoOtherFilesLanguageDoesNotSuppressExplicitEditIntent() {
        List<String> inputs = List.of(
                "Change TODO to DONE in notes.txt. Use the edit tool and do not modify anything else.",
                "Edit notes.txt to replace TODO with DONE. Do not modify anything else.",
                "Update notes.txt only; do not edit any other files.",
                "Only change notes.txt.");

        for (String input : inputs) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);
            assertEquals(TaskType.FILE_EDIT, contract.type(), input);
            assertTrue(contract.mutationRequested(), input);
            assertTrue(contract.mutationAllowed(), input);
            assertTrue(contract.verificationRequired(), input);
            assertTrue(contract.expectedTargets().contains("notes.txt"), input);
        }
    }

    @Test
    void globalNoMutationLanguageStillSuppressesEditIntent() {
        List<String> inputs = List.of(
                "Check notes.txt. Do not modify anything.",
                "What would you change in notes.txt? Do not modify files.",
                "Inspect notes.txt without changing it.",
                "Show me how to replace TODO with DONE in notes.txt, do not edit files.");

        for (String input : inputs) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
        }
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
