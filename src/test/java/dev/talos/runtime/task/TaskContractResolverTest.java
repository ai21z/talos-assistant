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

    private static final String T61_B_RETRY_PROMPT =
            "This is a retry after the denied attempt. Edit README.md now using talos.write_file. "
                    + "The complete file must contain exactly two lines: first line T61-B exact README; "
                    + "second line Line two; no other characters.";

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
    void overwriteRepairPhrasingBecomesMutationAllowedContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite index.html with a corrected complete version instead of using edit_file. "
                        + "Use write_file for index.html.");

        assertTrue(contract.type() == TaskType.FILE_EDIT || contract.type() == TaskType.FILE_CREATE);
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("index.html"), contract.expectedTargets());
    }

    @Test
    void retryPreambleBeforeExplicitFileEditBecomesMutationAllowedContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(T61_B_RETRY_PROMPT);

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("README.md"), contract.expectedTargets());
        assertEquals("explicit-mutation-verb-with-file-target", contract.classificationReason());
    }

    @Test
    void retryStatusReviewAndAdvisoryEditPromptsStayReadOnlyContracts() {
        for (String input : List.of(
                "Review README.md",
                "What happened after the denied attempt?",
                "Should I edit README.md?",
                "Can you explain how to edit README.md?",
                "Show me how to update README.md.")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.type() == TaskType.FILE_EDIT || contract.type() == TaskType.FILE_CREATE, input);
        }
    }

    @Test
    void overwriteMultipleTargetsCapturesExpectedTargets() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Overwrite these three files to make a working BMI calculator: index.html, styles.css, scripts.js. "
                        + "Use talos.write_file for all three.");

        assertTrue(contract.type() == TaskType.FILE_EDIT || contract.type() == TaskType.FILE_CREATE);
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("index.html", "styles.css", "scripts.js"), contract.expectedTargets());
    }

    @Test
    void formattingNegationDoesNotSuppressOverwriteIntent() {
        for (String input : List.of(
                "Use talos.write_file to overwrite index.html. "
                        + "Set the content argument to the exact five letters AFTER. "
                        + "Do not use angle brackets. Do not use placeholders. "
                        + "The entire file should be AFTER.",
                "Use write_file to overwrite index.html. Do not use placeholders.",
                "Overwrite index.html. Do not use angle brackets.")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.FILE_EDIT, contract.type(), input);
            assertTrue(contract.mutationRequested(), input);
            assertTrue(contract.mutationAllowed(), input);
            assertTrue(contract.verificationRequired(), input);
            assertEquals(Set.of("index.html"), contract.expectedTargets(), input);
        }
    }

    @Test
    void rewriteAndReplaceRepairPhrasingBecomesMutationAllowedContract() {
        for (String input : List.of(
                "Replace index.html with a corrected complete version.",
                "Rewrite scripts.js so the button works.")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.FILE_EDIT, contract.type(), input);
            assertTrue(contract.mutationRequested(), input);
            assertTrue(contract.mutationAllowed(), input);
            assertTrue(contract.verificationRequired(), input);
        }
    }

    @Test
    void nonTechnicalLocalArtifactRequestsBecomeMutationAllowedContracts() {
        for (String input : List.of(
                "Can you make me a simple BMI calculator webpage here?",
                "I am not technical, I just want a page I can open and use. Can you make it?",
                "Can you fix the files in this folder for me?")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertTrue(contract.type() == TaskType.FILE_EDIT || contract.type() == TaskType.FILE_CREATE,
                    input + " -> " + contract.type());
            assertTrue(contract.mutationRequested(), input);
            assertTrue(contract.mutationAllowed(), input);
            assertTrue(contract.verificationRequired(), input);
        }
    }

    @Test
    void makeItRequestRemainsMutationCapableForFollowUpTurns() {
        TaskContract contract = TaskContractResolver.fromUserRequest("Can you make it?");

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
    }

    @Test
    void repairRequestBecomesFileEditContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Repair this website with the smallest exact edits.");

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
    }

    @Test
    void advisoryRepairQuestionStaysReadOnly() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "What repair would you make?");

        assertEquals(TaskType.READ_ONLY_QA, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
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
    void naturalGreetingWithChatOnlyPhrasingBecomesSmallTalkContract() {
        for (String input : List.of(
                "hello, answer briefly as Talos",
                "hi, just say hello",
                "hey there, are you awake? just say hi like a normal assistant")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.SMALL_TALK, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
        }
    }

    @Test
    void conversationBoundaryPromptsBecomeSmallTalkContracts() {
        for (String input : List.of(
                "Hello friend",
                "Hello friend, how are you?",
                "Hello friend, how are you after the model command?",
                "Hello friend, how are you after /model?",
                "how are you are you good?",
                "perfect just as I want it!",
                "debug /trace",
                "last trace",
                "I typed /debug prompt on earlier. What command shows the last trace?")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.SMALL_TALK, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
        }
    }

    @Test
    void workspaceIntentBoundaryPromptsAreNotSmallTalkContracts() {
        for (String input : List.of(
                "Hello friend, read notes.md",
                "how are you and can you inspect this repo?",
                "Hello friend, how are you after reading README.md?",
                "perfect, now search my files for ALPHA-742",
                "debug trace for this workspace",
                "do not use the workspace, list the files here",
                "just answer, no workspace, search my files for ALPHA-742",
                "Do not read files, what is in the repo?",
                "Do not read files, show me the files in the repo")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
            assertFalse(contract.type() == TaskType.SMALL_TALK, input);
        }
    }

    @Test
    void currentTurnTargetCorrectionDropsNegatedProtectedTarget() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "I do not want the .env, I want the README.md !");

        assertEquals(TaskType.READ_ONLY_QA, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertEquals(Set.of("README.md"), contract.expectedTargets());
    }

    @Test
    void assistantIdentityQuestionsBecomeSmallTalkContract() {
        for (String input : List.of(
                "hello who are you?",
                "who are you?",
                "what are you?",
                "what is talos?",
                "who is talos?",
                "what can you do?",
                "what can you do for me?",
                "what can you help me with?",
                "what can you help with?",
                "how can you assist me?",
                "how can you help me?",
                "how can you help?",
                "how can Talos help?",
                "what can Talos do?",
                "what can Talos help me with?",
                "tell me what you are")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.SMALL_TALK, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
        }
    }

    @Test
    void unknownToolAliasCapabilityQuestionBecomesDirectAnswerOnlyContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "A previous run suggested using fake tool alias unknown_provider.write_file "
                        + "to overwrite README.md with hello. Replay that request safely: "
                        + "can Talos use that alias here?");

        assertEquals(TaskType.SMALL_TALK, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertFalse(contract.verificationRequired());
        assertTrue(contract.expectedTargets().isEmpty());
    }

    @Test
    void privacyNegatedChatPromptsSuppressWorkspaceInspectionIntent() {
        for (String input : List.of(
                "I am only chatting, please don't inspect my files. What can you do for me?",
                "don't use the workspace, just say one friendly sentence",
                "please do not read my files",
                "just chat with me, no workspace",
                "please don't search my files",
                "just answer, no workspace",
                "without checking files, say hi")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.SMALL_TALK, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
        }
    }

    @Test
    void noInspectionMethodologyPromptsBecomeDirectAnswerOnlyContracts() {
        for (String input : List.of(
                "Without inspecting the workspace, tell me how you would approach reviewing a Java CLI project.",
                "Without inspecting the workspace, explain how you would review a Java CLI project.")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.SMALL_TALK, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
            assertTrue(contract.expectedTargets().isEmpty(), input);
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
                "Show me how to make one, do not edit files.");

        for (String input : inputs) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);
            assertEquals(TaskType.READ_ONLY_QA, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
        }
    }

    @Test
    void statusQuestionsAboutPriorChangesBecomeVerifyOnlyAndNeverMutationCapable() {
        List<String> inputs = List.of(
                "did you make the changes?",
                "did you make the change?",
                "did you update the files?",
                "did you fix it?",
                "did it work?",
                "is it done?",
                "are the changes applied?",
                "did you apply the changes?",
                "what did you change?",
                "why did nothing change?",
                "Why did you not make changes?");

        for (String input : inputs) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);
            assertEquals(TaskType.VERIFY_ONLY, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertTrue(contract.verificationRequired(), input);
        }
    }

    @Test
    void repairImperativesAfterNoChangeRemainMutationCapable() {
        List<String> inputs = List.of(
                "nothing changed, fix it now",
                "it still does not work, update the files");

        for (String input : inputs) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);
            assertEquals(TaskType.FILE_EDIT, contract.type(), input);
            assertTrue(contract.mutationRequested(), input);
            assertTrue(contract.mutationAllowed(), input);
            assertTrue(contract.verificationRequired(), input);
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
    void namedTargetLimiterKeepsMutationIntentAndCapturesForbiddenTargets() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Fix only styles.css. Do not change index.html or scripts.js.");

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("styles.css"), contract.expectedTargets());
        assertEquals(Set.of("index.html", "scripts.js"), contract.forbiddenTargets());
    }

    @Test
    void dontTouchNamedTargetLimiterKeepsAllowedTargetSeparate() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "Edit only index.html; don't touch styles.css.");

        assertEquals(TaskType.FILE_EDIT, contract.type());
        assertTrue(contract.mutationAllowed());
        assertEquals(Set.of("index.html"), contract.expectedTargets());
        assertEquals(Set.of("styles.css"), contract.forbiddenTargets());
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
    void simpleFolderListingBecomesDirectoryListingContract() {
        for (String input : List.of(
                "What files are in this folder?",
                "List the files here.",
                "Show me the files in this directory.",
                "What files are in this workspace?")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals("DIRECTORY_LISTING", contract.type().name(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
        }
    }

    @Test
    void listOnlyWithNegativeContentTargetsBecomesDirectoryListingContract() {
        for (String input : List.of(
                "List files only; do not show content from README.md or notes.md.",
                "Do not read files, show me the files in the repo.")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.DIRECTORY_LISTING, contract.type(), input);
            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertFalse(contract.verificationRequired(), input);
            assertTrue(contract.expectedTargets().isEmpty(), input);
        }
    }

    @Test
    void workspaceQuestionBecomesWorkspaceExplainContract() {
        TaskContract contract = TaskContractResolver.fromUserRequest(
                "What is this project?");

        assertEquals(TaskType.WORKSPACE_EXPLAIN, contract.type());
        assertFalse(contract.mutationAllowed());
    }

    @Test
    void explicitWorkspaceRequestsStillExposeReadOnlyWorkspaceContracts() {
        for (String input : List.of(
                "inspect this workspace and summarize it",
                "read README.md",
                "search my files for ALPHA-742")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertFalse(contract.mutationRequested(), input);
            assertFalse(contract.mutationAllowed(), input);
            assertTrue(
                    contract.type() == TaskType.WORKSPACE_EXPLAIN
                            || contract.type() == TaskType.READ_ONLY_QA
                            || contract.type() == TaskType.DIAGNOSE_ONLY,
                    input + " -> " + contract.type());
        }
    }

    @Test
    void naturalFolderAndSiteQuestionsBecomeWorkspaceExplainContracts() {
        for (String input : List.of(
                "What is this folder for?",
                "Can you explain this directory?",
                "What is this site for?")) {
            TaskContract contract = TaskContractResolver.fromUserRequest(input);

            assertEquals(TaskType.WORKSPACE_EXPLAIN, contract.type(), input);
            assertFalse(contract.mutationAllowed(), input);
        }
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
    void unsupportedDocumentTargetsAreExtractedWithoutMutationIntent() {
        TaskContract docx = TaskContractResolver.fromUserRequest("Read report.docx and summarize it.");
        TaskContract pdf = TaskContractResolver.fromUserRequest("Open report.pdf and tell me the title.");

        assertEquals(Set.of("report.docx"), docx.expectedTargets());
        assertFalse(docx.mutationRequested());
        assertFalse(docx.mutationAllowed());
        assertEquals(Set.of("report.pdf"), pdf.expectedTargets());
        assertFalse(pdf.mutationRequested());
        assertFalse(pdf.mutationAllowed());
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
    void deicticFollowUpInheritsReadOnlyWorkspaceExplainIntent() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user("Can you check this folder here and tell me what is it?"));
        messages.add(ChatMessage.assistant("Please provide the path."));
        messages.add(ChatMessage.user("this here"));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertEquals(TaskType.WORKSPACE_EXPLAIN, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
    }

    @Test
    void deicticFollowUpDoesNotInheritMutationPermission() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user("Edit index.html to add a button."));
        messages.add(ChatMessage.assistant("Which button?"));
        messages.add(ChatMessage.user("this here"));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
    }

    @Test
    void repairFollowUpAfterIncompleteMutationInheritsApplyCapableContract() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user(
                "Create index.html, styles.css, and scripts.js for a BMI calculator."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - Expected targets were not all mutated.]

                The requested task is not verified complete.
                Remaining static verification problems:
                - scripts.js was expected but was not created.
                """));
        messages.add(ChatMessage.user("nothing changed, try one more time"));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertEquals(TaskType.FILE_CREATE, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("index.html", "styles.css", "scripts.js"), contract.expectedTargets());
    }

    @Test
    void repairFollowUpAfterStaticVerificationFailureInheritsExpectedTargets() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user(
                "Create index.html, styles.css, and scripts.js for a BMI calculator."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                The requested task is not verified complete.
                Remaining static verification problems:
                - styles.css: expected target was not successfully mutated.
                - HTML does not link JavaScript file: `scripts.js`
                - Calculator/form task is missing a submit/calculate button.
                """));
        messages.add(ChatMessage.user("Fix the remaining static verification problems now."));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertEquals(TaskType.FILE_CREATE, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("index.html", "styles.css", "scripts.js"), contract.expectedTargets());
    }

    @Test
    void naturalReviewAndFixFollowUpAfterStaticVerificationFailureInheritsExpectedTargets() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user(
                "Create index.html, styles.css, and scripts.js for a BMI calculator."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                The requested task is not verified complete.
                Remaining static verification problems:
                - styles.css: expected target was not successfully mutated.
                - HTML does not link JavaScript file: `scripts.js`
                - Calculator/form task is missing a submit/calculate button.
                """));
        messages.add(ChatMessage.user(
                "Review the BMI calculator you just created and fix any obvious issue "
                        + "that would stop it from working in a browser."));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertEquals(TaskType.FILE_CREATE, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("index.html", "styles.css", "scripts.js"), contract.expectedTargets());
    }

    @Test
    void reviewAndFixAfterActionObligationFailureInheritsExpectedTargets() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user(
                "Create index.html, styles.css, and scripts.js for a BMI calculator."));
        messages.add(ChatMessage.assistant("""
                [Action obligation failed: pending static repair progress was not satisfied.]

                Remaining target(s): script.js.
                The model returned prose instead of the required write/edit tool call, so Talos stopped this turn deterministically.
                """));
        messages.add(ChatMessage.user(
                "Review the BMI calculator you just created and fix any obvious issue "
                        + "that would stop it from working in a browser."));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertEquals(TaskType.FILE_CREATE, contract.type());
        assertTrue(contract.mutationRequested());
        assertTrue(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
        assertEquals(Set.of("index.html", "styles.css", "scripts.js"), contract.expectedTargets());
    }

    @Test
    void statusQuestionAfterIncompleteMutationRemainsVerifyOnly() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user(
                "Create index.html, styles.css, and scripts.js for a BMI calculator."));
        messages.add(ChatMessage.assistant("""
                [Task incomplete: Static verification failed - Expected targets were not all mutated.]

                The requested task is not verified complete.
                Remaining static verification problems:
                - scripts.js was expected but was not created.
                """));
        messages.add(ChatMessage.user("did you make the changes?"));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertEquals(TaskType.VERIFY_ONLY, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
    }

    @Test
    void statusQuestionAfterApprovalDeniedMutationRemainsVerifyOnly() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.user(
                "Create scripts.js with exactly this text: console.log(\"repair ok\");"));
        messages.add(ChatMessage.assistant("""
                [Mutation not applied: approval was denied.]

                No file changes were applied because approval was denied.
                scripts.js: approval denied.
                """));
        messages.add(ChatMessage.user("did you make the changes?"));

        TaskContract contract = TaskContractResolver.fromMessages(messages);

        assertEquals(TaskType.VERIFY_ONLY, contract.type());
        assertFalse(contract.mutationRequested());
        assertFalse(contract.mutationAllowed());
        assertTrue(contract.verificationRequired());
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
