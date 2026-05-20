package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.talos.runtime.policy.ConversationBoundaryPolicy.Classification.DIRECT_CHAT;
import static dev.talos.runtime.policy.ConversationBoundaryPolicy.Classification.NEAR_SLASH_COMMAND;
import static dev.talos.runtime.policy.ConversationBoundaryPolicy.Classification.NONE;
import static dev.talos.runtime.policy.ConversationBoundaryPolicy.Classification.PRIVACY_NO_WORKSPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationBoundaryPolicyTest {

    @Test
    void t54SmallTalkPromptsAreDirectAnswerOnly() {
        for (String input : List.of(
                "Hello friend",
                "how are you are you good?",
                "perfect just as I want it!",
                "thanks, that is perfect",
                "looks good")) {
            assertEquals(DIRECT_CHAT, ConversationBoundaryPolicy.classification(input), input);
            assertTrue(ConversationBoundaryPolicy.isDirectAnswerOnly(input), input);
        }
    }

    @Test
    void postModelCommandGreetingIsDirectAnswerOnly() {
        for (String input : List.of(
                "Hello friend, how are you after the model command?",
                "Hello friend, how are you after /model?",
                "Hey there, how are you after the slash command?")) {
            assertEquals(DIRECT_CHAT, ConversationBoundaryPolicy.classification(input), input);
            assertTrue(ConversationBoundaryPolicy.isDirectAnswerOnly(input), input);
        }
    }

    @Test
    void privacyNoWorkspacePromptsAreDirectAnswerOnlyEvenWhenMentioningFiles() {
        for (String input : List.of(
                "I am only chatting, please don't inspect my files. What can you do for me?",
                "Do not read files, just answer normally.",
                "No workspace access please, even though README.md exists.",
                "please do not read my files",
                "without checking files, say hi",
                "Without inspecting or using this workspace, explain entropy in thermodynamics.")) {
            assertEquals(PRIVACY_NO_WORKSPACE, ConversationBoundaryPolicy.classification(input), input);
            assertTrue(ConversationBoundaryPolicy.isDirectAnswerOnly(input), input);
        }
    }

    @Test
    void privacyNoWorkspaceWordingDoesNotOverrideExplicitWorkspaceActionIntent() {
        for (String input : List.of(
                "Do not read files, create index.html",
                "Don't inspect my files, update README.md",
                "do not use the workspace, list the files here",
                "just answer, no workspace, search my files for ALPHA-742",
                "Don't inspect my files, inspect this repo",
                "Do not read files, can you read this workspace?",
                "do not use the workspace, diagnose this project",
                "Do not read files, what is in the repo?",
                "Do not read files, show the repository structure",
                "Do not read files, show me the files in the repo",
                "Do not read files, summarize README.md",
                "Don't inspect my files, explain README.md")) {
            assertEquals(NONE, ConversationBoundaryPolicy.classification(input), input);
            assertFalse(ConversationBoundaryPolicy.isDirectAnswerOnly(input), input);
        }
    }

    @Test
    void nearSlashCommandTyposAreDirectAnswerOnlyWithDeterministicGuidance() {
        for (String input : List.of(
                "debug /trace",
                "debug trace",
                "debug /trace?",
                "debug /trace.",
                "last trace",
                "last /trace",
                "show last trace",
                "show me last trace",
                "what command shows the last trace",
                "I typed /debug prompt on earlier. What command shows the last trace?")) {
            assertEquals(NEAR_SLASH_COMMAND, ConversationBoundaryPolicy.classification(input), input);
            assertTrue(ConversationBoundaryPolicy.isDirectAnswerOnly(input), input);
            assertTrue(ConversationBoundaryPolicy.deterministicAnswer(input).contains("/last trace"), input);
        }
    }

    @Test
    void deterministicAnswerIsOnlyForNearSlashCommandGuidance() {
        assertNull(ConversationBoundaryPolicy.deterministicAnswer("Hello friend"));
        assertNull(ConversationBoundaryPolicy.deterministicAnswer("please do not read my files"));
    }

    @Test
    void workspaceIntentBeatsCasualGreeting() {
        for (String input : List.of(
                "Hey, what is in this workspace?",
                "Hello friend, read notes.md",
                "how are you and can you inspect this repo?",
                "Hello friend, how are you after reading README.md?",
                "perfect, now search my files for ALPHA-742")) {
            assertEquals(NONE, ConversationBoundaryPolicy.classification(input), input);
            assertFalse(ConversationBoundaryPolicy.isDirectAnswerOnly(input), input);
        }
    }

    @Test
    void mutationIntentIsNotDirectAnswerOnly() {
        for (String input : List.of(
                "Create index.html",
                "Edit script.js",
                "Overwrite README.md with hello",
                "Make a BMI calculator website here")) {
            assertEquals(NONE, ConversationBoundaryPolicy.classification(input), input);
            assertFalse(ConversationBoundaryPolicy.isDirectAnswerOnly(input), input);
        }
    }
}
