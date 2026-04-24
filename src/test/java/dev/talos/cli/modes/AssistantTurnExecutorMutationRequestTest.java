package dev.talos.cli.modes;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Regression tests for Point 3 — missing-mutation detection marker set
 * in {@link AssistantTurnExecutor#looksLikeMutationRequest(String)}.
 *
 * <p>Positive prompts are taken verbatim from the real test-output.txt
 * transcript (Turns 5, 6, 7 — "edit / modify / change" requests where
 * Talos read, listed, and then deflected without calling write_file
 * or edit_file).
 */
class AssistantTurnExecutorMutationRequestTest {
    @Test
    void turn5Shape_makeItDarkerAndMoreMinimal() {
        String prompt = "ah okay wait I run it. Hmm I dont like it. I want it darker and "
                + "more minimal. Can you edit it and make it darker and more minimal?";
        assertTrue(AssistantTurnExecutor.looksLikeMutationRequest(prompt));
    }
    @Test
    void turn6Shape_changeEverythingInsideIndex() {
        String prompt = "you can also make styling inside index.html. Dont make a file. "
                + "Just change everything inside index.html";
        assertTrue(AssistantTurnExecutor.looksLikeMutationRequest(prompt));
    }
    @Test
    void turn7Shape_modifyItMakeWebpageDarker() {
        String prompt = "Modify it. Make this webpage darker and more minimal";
        assertTrue(AssistantTurnExecutor.looksLikeMutationRequest(prompt));
    }
    @Test
    void redesignAsSpringGarden() {
        String prompt = "I dont like this site look and feel... I want to completely change it "
                + "and make it look like a garden in the spring where almonds starting blooming";
        assertTrue(AssistantTurnExecutor.looksLikeMutationRequest(prompt));
    }
    @Test
    void createFileRequest() {
        assertTrue(AssistantTurnExecutor.looksLikeMutationRequest(
                "Please create a README.md file with a short project description"));
    }
    @Test
    void writeFileRequest() {
        assertTrue(AssistantTurnExecutor.looksLikeMutationRequest(
                "Write a new helper.js file that exports a greet() function"));
    }
    @Test
    void fixItShape() {
        assertTrue(AssistantTurnExecutor.looksLikeMutationRequest(
                "There is a bug on line 42, fix it please"));
    }
    @Test
    void readQuestionDoesNotFire() {
        assertFalse(AssistantTurnExecutor.looksLikeMutationRequest(
                "What are the contents of this workspace?"));
    }
    @Test
    void syntheticToolResultWithReplaceMarkerDoesNotFire() {
        assertFalse(AssistantTurnExecutor.looksLikeMutationRequest(
                "[tool_result: talos.edit_file]\n"
                        + "[error] This exact edit was already attempted and failed. "
                        + "Alternatively, use talos.write_file to replace the entire file content.\n"
                        + "[/tool_result]"));
    }
    @Test
    void explanationQuestionDoesNotFire() {
        assertFalse(AssistantTurnExecutor.looksLikeMutationRequest(
                "oh nice what is this index.html for?"));
    }
    @Test
    void generalKnowledgeDoesNotFire() {
        assertFalse(AssistantTurnExecutor.looksLikeMutationRequest(
                "Explain what a binary tree is"));
    }
    @Test
    void nullAndBlankAreSafe() {
        assertFalse(AssistantTurnExecutor.looksLikeMutationRequest(null));
        assertFalse(AssistantTurnExecutor.looksLikeMutationRequest(""));
        assertFalse(AssistantTurnExecutor.looksLikeMutationRequest("   "));
    }
}
