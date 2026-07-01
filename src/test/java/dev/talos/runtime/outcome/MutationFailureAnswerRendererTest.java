package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MutationFailureAnswerRendererTest {

    @Test
    void falseMutationClaimIsAnnotatedWhenNoMutationSucceeded() {
        String answer = "I updated index.html with the requested change.";

        String out = MutationFailureAnswerRenderer.annotateIfFalseMutationClaim(
                answer,
                loopResult(List.of(readOnlyOutcome())),
                0);

        assertTrue(out.startsWith(MutationFailureAnswerRenderer.FALSE_MUTATION_ANNOTATION));
        assertTrue(out.endsWith(answer));
    }

    @Test
    void noChangeAnswersAreNotMutationClaims() {
        List<String> answers = List.of(
                "I created or modified zero files during this Ask-mode test.",
                "I created zero files and modified zero files.",
                "I changed no files during this turn.",
                "I edited no files.",
                "I wrote no files.",
                "I saved zero files.",
                "No changes were applied.",
                "Zero changes were applied.",
                "Nothing was created or modified in the workspace.");

        for (String answer : answers) {
            assertFalse(MutationFailureAnswerRenderer.containsMutationClaim(answer), answer);
            assertEquals(
                    answer,
                    MutationFailureAnswerRenderer.annotateIfFalseMutationClaim(
                            answer,
                            loopResult(List.of(readOnlyOutcome())),
                            0),
                    answer);
        }
    }

    @Test
    void realMutationClaimsStillTriggerWhenNoMutationSucceeded() {
        List<String> answers = List.of(
                "I created README.md.",
                "I modified index.html.",
                "I changed script.js.",
                "No files were modified, but I created report.md.");

        for (String answer : answers) {
            assertTrue(MutationFailureAnswerRenderer.containsMutationClaim(answer), answer);
            String out = MutationFailureAnswerRenderer.annotateIfFalseMutationClaim(
                    answer,
                    loopResult(List.of(readOnlyOutcome())),
                    0);
            assertTrue(out.startsWith(MutationFailureAnswerRenderer.FALSE_MUTATION_ANNOTATION), answer);
        }
    }

    @Test
    void deniedMutationSummarySeparatesPolicyAndApprovalDenials() {
        var messages = messages("Edit index.html and .env.");
        var loopResult = loopResult(List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file",
                        "index.html",
                        false,
                        true,
                        true,
                        "",
                        "User did not approve the talos.edit_file call.",
                        null,
                        ToolError.DENIED)
                        .withFailureReason(ToolFailureReason.USER_APPROVAL_DENIED),
                new ToolCallLoop.ToolOutcome(
                        "talos.write_file",
                        ".env",
                        false,
                        true,
                        true,
                        "",
                        "Permission policy denied mutation of protected path `.env`.",
                        null,
                        ToolError.DENIED)
                        .withFailureReason(ToolFailureReason.PERMISSION_POLICY_DENIED)));

        String out = MutationFailureAnswerRenderer.summarizeDeniedMutationOutcomesIfNeeded(
                "manual replacement prose",
                plan("Edit index.html and .env."),
                messages,
                loopResult,
                0);

        assertTrue(out.startsWith(MutationFailureAnswerRenderer.MIXED_DENIED_MUTATION_ANNOTATION));
        assertTrue(out.contains("permission policy denied or blocked"));
        assertTrue(out.contains(".env"));
        assertTrue(out.contains("approval was denied"));
        assertTrue(out.contains("index.html: approval denied"));
        assertFalse(out.contains("manual replacement prose"));
    }

    @Test
    void readOnlyDeniedMutationKeepsOnlyCleanInspectedAnswer() {
        String answer = """
                I inspected the page and found the selector mismatch.
                Please approve these changes so I can apply them.
                """;
        var loopResult = loopResult(List.of(new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                "index.html",
                false,
                true,
                true,
                "",
                "The user did not ask to modify files on this turn, so do not call talos.edit_file.",
                null,
                ToolError.DENIED)));

        String out = MutationFailureAnswerRenderer.summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
                answer,
                plan("Diagnose index.html without changing files."),
                messages("Diagnose index.html without changing files."),
                loopResult,
                0);

        assertTrue(out.startsWith(MutationFailureAnswerRenderer.READ_ONLY_DENIED_MUTATION_REPLACEMENT));
        assertTrue(out.contains("Read-only answer from inspected evidence:"));
        assertTrue(out.contains("I inspected the page and found the selector mismatch."));
        assertFalse(out.contains("Please approve these changes"));
    }

    @Test
    void readOnlyDeniedMutationDropsManualSnippetAndCapabilityDeflection() {
        String answer = """
                It seems I cannot create files in this workspace.

                ### `index.html`
                ```html
                <h1>Retrocats</h1>
                ```

                You can copy and paste these snippets into their respective files.
                """;
        var loopResult = loopResult(List.of(new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                "index.html",
                false,
                true,
                true,
                "",
                "The user did not ask to modify files on this turn, so do not call talos.write_file.",
                null,
                ToolError.DENIED)));

        String out = MutationFailureAnswerRenderer.summarizeReadOnlyDeniedMutationOutcomesIfNeeded(
                answer,
                plan("Can you diagnose this page without changing files?"),
                messages("Can you diagnose this page without changing files?"),
                loopResult,
                0);

        assertEquals(MutationFailureAnswerRenderer.READ_ONLY_DENIED_MUTATION_REPLACEMENT, out);
        assertFalse(out.contains("cannot create files"), out);
        assertFalse(out.contains("copy and paste"), out);
        assertFalse(out.contains("index.html"), out);
    }

    @Test
    void invalidMutationSummaryPreservesFailurePolicyReason() {
        var loopResult = new ToolCallLoop.LoopResult(
                "I updated index.html.",
                1,
                1,
                List.of("talos.edit_file"),
                List.of(),
                1,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0,
                dev.talos.runtime.failure.FailureDecision.stop(
                        dev.talos.runtime.failure.FailureAction.ASK_USER,
                        "failure policy stopped after invalid edit arguments"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file",
                        "index.html",
                        false,
                        true,
                        false,
                        "",
                        "Invalid talos.edit_file call: `old_string` must be present and non-empty.",
                        null,
                        ToolError.INVALID_PARAMS)));

        String out = MutationFailureAnswerRenderer.summarizeInvalidMutationOutcomesIfNeeded(
                "I updated index.html.",
                plan("Edit index.html."),
                messages("Edit index.html."),
                loopResult,
                0);

        assertTrue(out.startsWith(MutationFailureAnswerRenderer.INVALID_MUTATION_ANNOTATION));
        assertTrue(out.contains("old_string"));
        assertTrue(out.contains("Failure policy reason:"));
        assertTrue(out.contains("failure policy stopped after invalid edit arguments"));
        assertFalse(out.contains("I updated index.html."));
    }

    private static CurrentTurnPlan plan(String request) {
        var contract = TaskContractResolver.fromUserRequest(request);
        return CurrentTurnPlan.create(
                contract,
                contract.mutationAllowed() ? ExecutionPhase.APPLY : ExecutionPhase.INSPECT,
                List.of(),
                List.of(),
                List.of());
    }

    private static ArrayList<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ToolCallLoop.LoopResult loopResult(List<ToolCallLoop.ToolOutcome> outcomes) {
        return new ToolCallLoop.LoopResult(
                "answer",
                1,
                outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(),
                List.of(),
                0,
                0,
                false,
                (int) outcomes.stream().filter(outcome -> outcome.mutating() && outcome.success()).count(),
                List.of(),
                0,
                0,
                0,
                0,
                outcomes);
    }

    private static ToolCallLoop.ToolOutcome readOnlyOutcome() {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                "index.html",
                true,
                false,
                false,
                "Read index.html",
                "");
    }
}
