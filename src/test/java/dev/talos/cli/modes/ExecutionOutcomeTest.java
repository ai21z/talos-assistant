package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.outcome.MutationOutcomeStatus;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionOutcomeTest {

    @Test
    void toolLoopDeniedMutationIsClassifiedAsBlocked() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));

        var loopResult = new ToolCallLoop.LoopResult(
                "manual replacement prose", 1, 1,
                List.of("talos.edit_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, true,
                        "", "User did not approve the talos.edit_file call."
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "manual replacement prose", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.deniedMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION));
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().contract().mutationRequested());
        assertEquals(MutationOutcomeStatus.DENIED, outcome.taskOutcome().mutationOutcome().status());
        assertEquals(1, outcome.taskOutcome().mutationOutcome().denied().size());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.DENIED_MUTATION));
    }

    @Test
    void readOnlyDeniedMutationIsClassifiedAsPolicyBlockedAndSanitized() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Can you diagnose this page without changing files?"));

        var loopResult = new ToolCallLoop.LoopResult(
                "Please approve these changes so I can apply them.", 1, 1,
                List.of("talos.edit_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, true,
                        "", "The user did not ask to modify files on this turn, "
                        + "so do not call talos.edit_file for a read-only request.",
                        null, ToolError.DENIED
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "Please approve these changes so I can apply them.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.deniedMutation());
        assertTrue(outcome.finalAnswer().startsWith(
                AssistantTurnExecutor.READ_ONLY_DENIED_MUTATION_REPLACEMENT));
        assertFalse(outcome.finalAnswer().contains("Please approve these changes"));
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.DENIED, outcome.taskOutcome().mutationOutcome().status());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.DENIED_MUTATION));
    }

    @Test
    void deniedProtectedReadIsClassifiedAsApprovalBlockedAndSanitized() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me what it says."));

        var loopResult = new ToolCallLoop.LoopResult(
                "The file says SECRET=original.", 1, 1,
                List.of("talos.read_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", ".env", false, false, true,
                        "", "User did not approve the talos.read_file call.",
                        null, ToolError.DENIED
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "The file says SECRET=original.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertFalse(outcome.deniedMutation());
        assertTrue(outcome.finalAnswer().contains("Protected content was not read"));
        assertTrue(outcome.finalAnswer().contains("approval was denied"));
        assertFalse(outcome.finalAnswer().contains("SECRET=original"));
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.NOT_REQUESTED, outcome.taskOutcome().mutationOutcome().status());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.DENIED_PROTECTED_READ));
    }

    @Test
    void deniedMutationDominatesMixedInvalidAndDeniedNoSuccessTurn() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Edit index.html to add the CTA button."));

        var loopResult = new ToolCallLoop.LoopResult(
                "manual replacement prose", 4, 3,
                List.of("talos.edit_file", "talos.read_file", "talos.edit_file"), List.of(),
                3, 1, false, 0, List.of("index.html"),
                0, 0, 1, 1,
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.edit_file", "index.html", false, true, false,
                                "", "Invalid talos.edit_file call: `old_string` must be present and non-empty.",
                                null, ToolError.INVALID_PARAMS),
                        new ToolCallLoop.ToolOutcome(
                                "talos.edit_file", "index.html", false, true, true,
                                "", "User did not approve the talos.edit_file call.",
                                null, ToolError.DENIED)
                ));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "manual replacement prose", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.deniedMutation());
        assertFalse(outcome.invalidMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION));
        assertTrue(outcome.finalAnswer().contains("approval was denied"));
        assertTrue(outcome.finalAnswer().contains("Earlier invalid mutation attempts"));
        assertTrue(outcome.finalAnswer().contains("old_string"));
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.DENIED, outcome.taskOutcome().mutationOutcome().status());
        assertEquals(1, outcome.taskOutcome().mutationOutcome().failed().size());
        assertEquals(1, outcome.taskOutcome().mutationOutcome().denied().size());
    }

    @Test
    void invalidMutationArgumentsAreClassifiedAsFailedWithoutApprovalDenial() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Edit index.html to add the CTA button."));

        var loopResult = new ToolCallLoop.LoopResult(
                "I updated index.html.", 1, 1,
                List.of("talos.edit_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, false,
                        "", "Invalid talos.edit_file call: `old_string` must be present and non-empty. "
                        + "No approval was requested and no file was changed.",
                        null, ToolError.INVALID_PARAMS
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "I updated index.html.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
        assertTrue(outcome.invalidMutation());
        assertFalse(outcome.deniedMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("invalid mutation arguments"));
        assertTrue(outcome.finalAnswer().contains("old_string"));
        assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.FAILED, outcome.taskOutcome().mutationOutcome().status());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.INVALID_MUTATION_ARGUMENTS));
    }

    @Test
    void mutationRequestStoppedByFailurePolicyWithNoMutationIsNotComplete() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create a complete static BMI calculator in index.html, styles.css, and scripts.js."));

        var loopResult = new ToolCallLoop.LoopResult(
                "[Tool loop stopped by failure policy: failure policy stopped the tool loop after 3 failed call(s) for path `index.html`. Review the latest tool errors before retrying.]",
                3,
                3,
                List.of(
                        "talos.write_file<|channel|>commentary",
                        "talos_write_file<|channel|>commentary"),
                List.of(),
                3,
                3,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0,
                FailureDecision.stop(
                        FailureAction.STOP_WITH_PARTIAL,
                        "failure policy stopped the tool loop after 3 failed call(s) for path `index.html`"),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.write_file<|channel|>commentary",
                        "index.html",
                        false,
                        false,
                        false,
                        "",
                        "Unknown tool: talos.write_file<|channel|>commentary",
                        null,
                        ToolError.NOT_FOUND)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().contains("Tool loop stopped by failure policy"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
    }

    @Test
    void pendingActionObligationFailureDominatesVerifiedMutationOutcomeAndTrace() throws Exception {
        Path ws = Files.createTempDirectory("talos-pending-obligation-outcome-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="styles.css"></head>
                      <body>
                        <form id="bmi-form">
                          <input id="height" type="number">
                          <input id="weight" type="number">
                          <button type="submit">Calculate BMI</button>
                        </form>
                        <output id="result"></output>
                        <script src="scripts.js"></script>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), "form { display: grid; gap: 0.5rem; }\n");
            Files.writeString(ws.resolve("scripts.js"), """
                    document.getElementById('bmi-form').addEventListener('submit', (event) => {
                      event.preventDefault();
                      const height = Number(document.getElementById('height').value) / 100;
                      const weight = Number(document.getElementById('weight').value);
                      document.getElementById('result').textContent = `BMI: ${(weight / (height * height)).toFixed(1)}`;
                    });
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js."));

            String answer = """
                    [Action obligation failed: pending static repair progress was not satisfied.]

                    Remaining target(s): script.js.
                    The model returned prose instead of the required write/edit tool call, so Talos stopped this turn deterministically.
                    """;
            var loopResult = new ToolCallLoop.LoopResult(
                    answer,
                    3,
                    3,
                    List.of("talos.write_file", "talos.write_file", "talos.write_file"),
                    List.of(),
                    0,
                    0,
                    false,
                    3,
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    FailureDecision.stop(
                            FailureAction.ASK_USER,
                            "Pending action obligation STATIC_REPAIR_TARGETS_REMAINING was ignored after a static repair progress reprompt."),
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "index.html", true, true, false,
                                    "wrote index.html", "", dev.talos.tools.VerificationStatus.PASS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "styles.css", true, true, false,
                                    "wrote styles.css", "", dev.talos.tools.VerificationStatus.PASS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "scripts.js", true, true, false,
                                    "wrote scripts.js", "", dev.talos.tools.VerificationStatus.PASS)));

            LocalTurnTraceCapture.begin(
                    "trc-pending-obligation",
                    "sid",
                    1,
                    "2026-05-03T12:00:00Z",
                    "workspace-hash",
                    "auto",
                    "test",
                    "model",
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.");
            try {
                ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                        loopResult.finalAnswer(), messages, loopResult, ws, 0);

                LocalTurnTrace trace = LocalTurnTraceCapture.complete();
                assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
                assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
                assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
                assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
                assertTrue(outcome.finalAnswer().startsWith("[Action obligation failed:"), outcome.finalAnswer());
                assertFalse(outcome.finalAnswer().contains("Static verification: passed"), outcome.finalAnswer());
                assertNotNull(trace);
                assertNotNull(trace.outcome());
                assertEquals("BLOCKED", trace.outcome().status());
                assertEquals("BLOCKED_BY_POLICY", trace.outcome().classification());
            } finally {
                LocalTurnTraceCapture.clear();
            }
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void planContractKeepsDeniedMutationClassificationAfterRetryMessagesAppend() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Edit index.html to add the CTA button."));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.APPLY,
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of());

        messages.add(ChatMessage.assistant("I can help with that."));
        messages.add(ChatMessage.user(
                "The current-turn obligation was not satisfied. Call the write tool now."));

        var loopResult = new ToolCallLoop.LoopResult(
                "manual replacement prose", 1, 1,
                List.of("talos.edit_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, true,
                        "", "User did not approve the talos.edit_file call.",
                        null, ToolError.DENIED
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "manual replacement prose", plan, messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.deniedMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.DENIED_MUTATION_ANNOTATION),
                outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, outcome.taskOutcome().completionStatus());
    }

    @Test
    void planContractKeepsInvalidMutationClassificationAfterRetryMessagesAppend() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Edit index.html to add the CTA button."));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.APPLY,
                List.of("talos.edit_file"),
                List.of("talos.edit_file"),
                List.of());

        messages.add(ChatMessage.assistant("I can help with that."));
        messages.add(ChatMessage.user(
                "The current-turn obligation was not satisfied. Call the write tool now."));

        var loopResult = new ToolCallLoop.LoopResult(
                "I updated index.html.", 1, 1,
                List.of("talos.edit_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, false,
                        "", "Invalid talos.edit_file call: `old_string` must be present and non-empty.",
                        null, ToolError.INVALID_PARAMS
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "I updated index.html.", plan, messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
        assertTrue(outcome.invalidMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION),
                outcome.finalAnswer());
    }

    @Test
    void unsupportedDocumentReadRemovesEmptyContentClaims() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize the documents in this workspace."));

        var loopResult = new ToolCallLoop.LoopResult(
                "notes.txt says Talos should summarize supported text files. "
                        + "sample.pdf and sample.xlsx do not contain any extractable text. "
                        + "These files are empty or do not contain readable text.",
                3, 3,
                List.of("talos.read_file", "talos.read_file", "talos.read_file"), List.of(),
                2, 0, false, 0, List.of("notes.txt"),
                0, 0, 0, 0,
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "notes.txt", true, false, false,
                                "notes read", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "sample.pdf", false, false, false,
                                "", "Unsupported binary document format: sample.pdf (PDF). "
                                + "Talos cannot extract PDF contents with the current local text-tool surface.",
                                null, ToolError.UNSUPPORTED_FORMAT),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "sample.xlsx", false, false, false,
                                "", "Unsupported binary document format: sample.xlsx (Microsoft Excel .xlsx). "
                                + "Talos cannot extract Excel workbook contents with the current local text-tool surface.",
                                null, ToolError.UNSUPPORTED_FORMAT)
                ));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertTrue(outcome.unsupportedDocumentCapabilityOverride());
        assertTrue(outcome.finalAnswer().startsWith("[Document capability note:"));
        assertTrue(outcome.finalAnswer().contains("sample.pdf"));
        assertTrue(outcome.finalAnswer().contains("sample.xlsx"));
        assertTrue(outcome.finalAnswer().contains("notes.txt says Talos should summarize supported text files."));
        assertFalse(outcome.finalAnswer().contains("do not contain any extractable text"));
        assertFalse(outcome.finalAnswer().contains("These files are empty"));
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.UNSUPPORTED_DOCUMENT_CAPABILITY_NOTE));
    }

    @Test
    void unsupportedDocumentReadIsAdvisoryAndTraceOutcomeIsNotComplete() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Can you read report.docx and summarize it?"));

        var loopResult = new ToolCallLoop.LoopResult(
                "I cannot inspect report.docx with the current text-only reader.", 1, 1,
                List.of("talos.read_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "read_file", "report.docx", false, false, false,
                        "", "Unsupported binary document format: report.docx (Microsoft Word .docx). "
                        + "Talos cannot extract Word document contents with the current local text-tool surface.",
                        null, ToolError.UNSUPPORTED_FORMAT
                )));

        LocalTurnTraceCapture.begin(
                "trc-unsupported-docx",
                "sid",
                1,
                "2026-05-01T12:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "Can you read report.docx and summarize it?");
        try {
            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, null, 0);

            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.UNSUPPORTED_DOCUMENT_CAPABILITY_NOTE));
            assertNotNull(trace);
            assertNotNull(trace.outcome());
            assertEquals("ADVISORY_ONLY", trace.outcome().status());
            assertEquals("ADVISORY_ONLY", trace.outcome().classification());
            assertFalse("READ_ONLY_ANSWERED".equals(trace.outcome().classification()));
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void preApprovalPathEscapeIsClassifiedAsInvalidNotDenied() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Create a file at ../outside-talos-qa.txt with the text hello from Talos."));

        var loopResult = new ToolCallLoop.LoopResult(
                "I created the file.", 1, 1,
                List.of("talos.write_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.write_file", "../outside-talos-qa.txt", false, true, false,
                        "", "Path not allowed before approval for `path`: ../outside-talos-qa.txt "
                        + "(path escapes workspace). No approval was requested and no file was changed.",
                        null, ToolError.INVALID_PARAMS
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "I created the file.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
        assertTrue(outcome.invalidMutation());
        assertFalse(outcome.deniedMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("Path not allowed before approval"));
        assertTrue(outcome.finalAnswer().contains("No approval was requested"));
        assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.FAILED, outcome.taskOutcome().mutationOutcome().status());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.INVALID_MUTATION_ARGUMENTS));
    }

    @Test
    void toolLoopPartialMutationIsClassifiedAsPartial() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Update the html and css."));

        var loopResult = new ToolCallLoop.LoopResult(
                "assistant summary", 2, 2,
                List.of("talos.edit_file", "talos.edit_file"), List.of(),
                1, 0, false, 1, List.of(),
                0, 0, 0, 0,
                List.of(
                        new ToolCallLoop.ToolOutcome("talos.edit_file", "index.html", true, true, false,
                                "headline updated", ""),
                        new ToolCallLoop.ToolOutcome("talos.edit_file", "style.css", false, true, false,
                                "", "old_string not found")
                ));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "assistant summary", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.PARTIAL, outcome.completionStatus());
        assertTrue(outcome.partialMutation());
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.PARTIAL_MUTATION_ANNOTATION));
        assertEquals(TaskCompletionStatus.PARTIAL, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.PARTIAL, outcome.taskOutcome().mutationOutcome().status());
        assertEquals(1, outcome.taskOutcome().mutationOutcome().successful().size());
        assertEquals(1, outcome.taskOutcome().mutationOutcome().failed().size());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.PARTIAL_MUTATION));
    }

    @Test
    void partialMutationRunsStaticVerificationButRemainsPartial() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-partial-static-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <head><link rel="stylesheet" href="style.css"></head>
                      <body><main class="calculator"><h1>BMI</h1></main><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(ws.resolve("style.css"), "calculator { max-width: 420px; }");
            Files.writeString(ws.resolve("script.js"), "document.getElementById('bmi-form');");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "This BMI website is not working correctly. Apply the smallest edits needed to make it valid and functioning."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "[ok] Edited index.html\n[failed] index.html", 2, 2,
                    List.of("talos.edit_file", "talos.edit_file"), List.of(),
                    1, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", true, true, false,
                                    "Edited index.html", "", dev.talos.tools.VerificationStatus.WARN),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, false,
                                    "", "Invalid talos.edit_file call: missing required parameter `new_string`. "
                                    + "No approval was requested and no file was changed.",
                                    null, ToolError.INVALID_PARAMS)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "[ok] Edited index.html\n[failed] index.html", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.PARTIAL, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Partial verification: static checks failed -"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("The turn remains partial."));
            assertTrue(outcome.finalAnswer().contains("Remaining static verification problems:"));
            assertTrue(outcome.finalAnswer().contains("file-level verification reported warning"));
            assertTrue(outcome.finalAnswer().contains("some requested file changes succeeded and some failed"));
            assertEquals(TaskCompletionStatus.PARTIAL, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.FAILED, outcome.taskOutcome().verificationResult().status());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.PARTIAL_MUTATION));
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STATIC_VERIFICATION_FAILED));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void recoveredEmptyEditArgumentFailureDoesNotPoisonCompletion() throws Exception {
        Path ws = Files.createTempDirectory("talos-recovered-empty-edit-outcome-");
        try {
            Files.writeString(ws.resolve("index.html"), "<html><body><a class=\"cta-button\">Listen</a></body></html>\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Edit index.html to add the CTA button."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Edited index.html.", 3, 3,
                    List.of("talos.edit_file", "talos.read_file", "talos.edit_file"), List.of(),
                    1, 0, false, 1, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", false, true, false,
                                    "", "Invalid talos.edit_file call: `old_string` must be present and non-empty.",
                                    null, ToolError.INVALID_PARAMS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "index.html", true, true, false,
                                    "Edited index.html", "", dev.talos.tools.VerificationStatus.UNKNOWN)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Edited index.html.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertFalse(outcome.partialMutation());
            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[File write/readback passed."));
            assertEquals(MutationOutcomeStatus.SUCCEEDED, outcome.taskOutcome().mutationOutcome().status());
            assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
            assertEquals(0, outcome.taskOutcome().mutationOutcome().failed().size());
            assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.PARTIAL_MUTATION));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void selectorGroundedOverrideIsClassifiedAsGrounded() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-selector-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <body class="synthwave-theme">
                        <section id="hero">
                          <div class="hero-content"></div>
                        </section>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("style.css"), """
                    body.synthwave-theme {}
                    #hero {}
                    .hero-content {}
                    .cta-button {}
                    """);
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('.cta-button');
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "unused", 4, 4,
                    List.of("talos.list_dir", "talos.read_file", "talos.read_file", "talos.read_file"),
                    List.of(), 0, 0, false, 0, List.of("index.html", "style.css", "script.js"),
                    0, 0, 0, 0);

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "There are no mismatches.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.GroundingStatus.GROUNDED, outcome.groundingStatus());
            assertTrue(outcome.selectorGroundedOverride());
            assertTrue(outcome.finalAnswer().contains("Mismatches found:"));
            assertFalse(outcome.finalAnswer().contains("#ff4500"));
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.SELECTOR_GROUNDED_OVERRIDE));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void selectorGroundingStillOverridesAfterGrepOnlyUnderinspection() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-selector-grep-only-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <body class="synthwave-theme">
                        <section id="hero">
                          <div class="hero-content"></div>
                        </section>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("style.css"), """
                    body.synthwave-theme {}
                    #hero {}
                    .hero-content {}
                    .cta-button {}
                    """);
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('.cta-button');
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "unused", 3, 3,
                    List.of("talos.grep", "talos.grep", "talos.grep"),
                    List.of(), 0, 0, false, 0, List.of(),
                    0, 0, 0, 0);

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Based on the tool results, there are no mismatches.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.GroundingStatus.GROUNDED, outcome.groundingStatus());
            assertTrue(outcome.selectorGroundedOverride());
            assertTrue(outcome.finalAnswer().contains("Mismatches found:"));
            assertTrue(outcome.finalAnswer().contains("`.cta-button`"));
            assertFalse(outcome.finalAnswer().contains("There are no mismatches"));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void postApplySelectorFailureIsClassifiedAsFailedVerification() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-verify-fail-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <head><link rel="stylesheet" href="style.css"></head>
                      <body><main id="hero"><p>No CTA yet</p></main><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(ws.resolve("style.css"), """
                    #hero {}
                    .cta-button {}
                    """);
            Files.writeString(ws.resolve("script.js"), "document.querySelector('.cta-button');");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Now edit index.html so the CSS and JavaScript .cta-button selector has a matching element."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html.", 1, 1,
                    List.of("talos.edit_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.edit_file", "index.html", true, true, false,
                            "edited index.html", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated index.html.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Task incomplete: Static verification failed -"));
            assertTrue(outcome.finalAnswer().chars().allMatch(ch -> ch < 128),
                    "Static verifier annotation should be ASCII-safe in redirected output");
            assertTrue(outcome.finalAnswer().contains("The requested task is not verified complete."));
            assertTrue(outcome.finalAnswer().contains("Unresolved static verification problems:"));
            assertTrue(outcome.finalAnswer().contains("`.cta-button`"));
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.FAILED, outcome.taskOutcome().verificationResult().status());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STATIC_VERIFICATION_FAILED));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void postApplySelectorSuccessIsClassifiedAsPassedVerification() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-verify-pass-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <head><link rel="stylesheet" href="style.css"></head>
                      <body><main id="hero"><a class="cta-button">Listen</a></main><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(ws.resolve("style.css"), """
                    #hero {}
                    .cta-button {}
                    """);
            Files.writeString(ws.resolve("script.js"), "document.querySelector('.cta-button');");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Now edit index.html so the CSS and JavaScript .cta-button selector has a matching element."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html.", 1, 1,
                    List.of("talos.edit_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.edit_file", "index.html", true, true, false,
                            "edited index.html", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated index.html.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.PASSED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Static verification: passed -"));
            assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus());
            assertEquals(List.of("index.html"), outcome.taskOutcome().contract().expectedTargets().stream().toList());
            assertEquals(TaskVerificationStatus.PASSED, outcome.taskOutcome().verificationResult().status());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void postApplyBroadWebAppFailureIsClassifiedAsFailedVerification() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-webapp-verify-fail-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <head><link rel="stylesheet" href="styles.css"></head>
                      <body><main class="calculator"><h1>BMI</h1></main><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), ".calculator { max-width: 28rem; }");
            Files.writeString(ws.resolve("script.js"), "document.getElementById('bmi-form');");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Can you build a small BMI calculator website here with separate CSS and JavaScript files?"));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Created the BMI calculator website files.", 1, 3,
                    List.of("talos.write_file", "talos.write_file", "talos.write_file"),
                    List.of(), 0, 0, false, 3, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "index.html", true, true, false,
                                    "wrote index.html", "", dev.talos.tools.VerificationStatus.PASS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "styles.css", true, true, false,
                                    "wrote styles.css", "", dev.talos.tools.VerificationStatus.PASS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "script.js", true, true, false,
                                    "wrote script.js", "", dev.talos.tools.VerificationStatus.PASS)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Created the BMI calculator website files.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Task incomplete: Static verification failed -"));
            assertTrue(outcome.finalAnswer().contains("The requested task is not verified complete."));
            assertTrue(outcome.finalAnswer().contains("`#bmi-form`"));
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.FAILED, outcome.taskOutcome().verificationResult().status());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STATIC_VERIFICATION_FAILED));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void postApplyBroadWebAppMissingScriptIsDowngradedAsIncomplete() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-webapp-missing-script-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <head><link rel="stylesheet" href="styles.css"></head>
                      <body><main class="calculator"><h1>BMI</h1></main></body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), ".calculator { max-width: 28rem; }");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a modern BMI calculator website with separate index.html, styles.css, and script.js files."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "[ok] Created index.html\n[ok] Created styles.css", 1, 2,
                    List.of("talos.write_file", "talos.write_file"),
                    List.of(), 0, 0, false, 2, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "index.html", true, true, false,
                                    "wrote index.html", "", dev.talos.tools.VerificationStatus.PASS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "styles.css", true, true, false,
                                    "wrote styles.css", "", dev.talos.tools.VerificationStatus.PASS)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "[ok] Created index.html\n[ok] Created styles.css", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Task incomplete: Static verification failed -"));
            assertTrue(outcome.finalAnswer().contains("The requested task is not verified complete."));
            assertTrue(outcome.finalAnswer().contains("script.js: expected target was not successfully mutated."));
            assertTrue(outcome.finalAnswer().contains("Expected web-app build to successfully mutate a JavaScript file."));
            assertTrue(outcome.finalAnswer().contains("Applied mutating tool calls:"));
            assertTrue(outcome.finalAnswer().contains("index.html: wrote index.html"));
            assertTrue(outcome.finalAnswer().contains("styles.css: wrote styles.css"));
            assertFalse(outcome.finalAnswer().contains("[ok] Created index.html"));
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.FAILED, outcome.taskOutcome().verificationResult().status());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STATIC_VERIFICATION_FAILED));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void postApplyNonWebTargetOnlyReadbackDoesNotClaimTaskVerified() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-target-readback-");
        try {
            Files.writeString(ws.resolve("README.md"), "# Talos\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Update README.md."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated README.md.", 1, 1,
                    List.of("talos.edit_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.edit_file", "README.md", true, true, false,
                            "edited README.md", "", dev.talos.tools.VerificationStatus.UNKNOWN
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated README.md.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[File write/readback passed."));
            assertTrue(outcome.finalAnswer().contains("No task-specific verifier was applicable"));
            assertTrue(outcome.finalAnswer().contains("task completion was not verified"));
            assertFalse(outcome.finalAnswer().contains("Static verification: passed"));
            assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.READBACK_ONLY, outcome.taskOutcome().verificationResult().status());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void literalMismatchAfterSuccessfulWriteIsIncompleteNotReadbackOnly() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-literal-mismatch-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <html>
                    <body>
                    <h1>Hello World</h1>
                    </body>
                    </html>
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html.", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "index.html", true, true, false,
                            "wrote index.html", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated index.html.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().contains("Exact content verification failed"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("requested task is not verified complete"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("File write/readback passed"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Updated index.html."),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Applied mutating tool calls:"),
                    outcome.finalAnswer());
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.FAILED, outcome.taskOutcome().verificationResult().status());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void failedStaticVerificationReplacesSuccessAndManualProse() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-failed-static-dominance-");
        try {
            Files.writeString(ws.resolve("script.js"), "document.querySelector('.missing-button');");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js. "
                    + "It should calculate BMI from height and weight."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated script.js successfully.", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "script.js", true, true, false,
                            "wrote script.js", "", dev.talos.tools.VerificationStatus.PASS
                    )));
            String modelAnswer = """
                    The BMI calculator is complete and ready to use.

                    Save these files, then open index.html in your browser.
                    """;

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    modelAnswer, messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Task incomplete: Static verification failed -"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("not verified complete"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("calculator is complete"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("ready to use"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Save these files"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("open index.html in your browser"), outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void planContractKeepsExactLiteralVerificationAfterRetryMessagesAppend() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-plan-literal-drift-");
        try {
            Files.writeString(ws.resolve("index.html"), "WRONG");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                    dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                    dev.talos.runtime.phase.ExecutionPhase.APPLY,
                    List.of("talos.write_file"),
                    List.of("talos.write_file"),
                    List.of());

            messages.add(ChatMessage.assistant("I can help with that."));
            messages.add(ChatMessage.user(
                    "The current-turn obligation was not satisfied. Call the write tool now."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html.", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "index.html", true, true, false,
                            "wrote index.html", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated index.html.", plan, messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().contains("Exact content verification failed"),
                    outcome.finalAnswer());
            assertEquals(List.of("index.html"),
                    outcome.taskOutcome().contract().expectedTargets().stream().toList());
            assertEquals(TaskVerificationStatus.FAILED,
                    outcome.taskOutcome().verificationResult().status());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void literalMatchAfterSuccessfulWriteIsVerifiedComplete() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-literal-match-");
        try {
            Files.writeString(ws.resolve("index.html"), "AFTER");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html.", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "index.html", true, true, false,
                            "wrote index.html", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated index.html.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.PASSED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().contains("Static verification: passed"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Exact content verification passed"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Updated index.html."),
                    outcome.finalAnswer());
            assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.PASSED, outcome.taskOutcome().verificationResult().status());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void streamingNoToolDirectAnswerOnlyMethodologyIsNotUngrounded() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Without inspecting the workspace, explain how you would review a Java CLI project."));

        String methodology = "I would start by clarifying the CLI's expected commands, then review "
                + "the parser, command dispatch, filesystem boundaries, error handling, and tests. "
                + "x".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS);

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(methodology, messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(ExecutionOutcome.GroundingStatus.UNKNOWN, outcome.groundingStatus());
        assertFalse(outcome.advisoryOnly());
        assertFalse(outcome.finalAnswer().contains("Grounding check"), outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.STREAMING_NO_TOOL_UNGROUNDED));
    }

    @Test
    void streamingNoToolEvidenceAnswerIsAdvisoryAndUngrounded() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Check whether this website has mismatches between HTML classes/IDs and the selectors used in CSS or JavaScript. Do not change anything yet."));

        String fabricated = "Based on the workspace contents, index.html contains a CTA button, "
                + "style.css defines `.cta-button`, and script.js wires it up. "
                + "There are no mismatches. "
                + "x".repeat(AssistantTurnExecutor.UNGROUNDED_MIN_CHARS);

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(fabricated, messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(ExecutionOutcome.GroundingStatus.UNGROUNDED, outcome.groundingStatus());
        assertTrue(outcome.advisoryOnly());
        assertFalse(outcome.noToolMutationReplaced());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertTrue(outcome.finalAnswer().contains(AssistantTurnExecutor.UNGROUNDED_ANNOTATION));
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STREAMING_NO_TOOL_UNGROUNDED));
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void streamingNoToolNegativeLocalAccessClaimOnWorkspaceTurnIsCorrected() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "But you told me you can help me with that. What is the problem with this workspace?"));

        String negativeClaim = "I apologize for any confusion. As an AI language model, "
                + "I don't have direct access to your local workspace or files to analyze them.";

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(negativeClaim, messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(ExecutionOutcome.GroundingStatus.UNGROUNDED, outcome.groundingStatus());
        assertTrue(outcome.advisoryOnly());
        assertTrue(outcome.finalAnswer().startsWith(
                        "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("[Capability correction:"),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("don't have direct access"));
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(
                TruthWarningType.NO_TOOL_LOCAL_ACCESS_CAPABILITY_CORRECTED));
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void streamingNoToolUnsupportedBinaryDocumentLimitationIsAdvisoryWithoutCapabilityCorrection() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize the documents in this workspace."));

        String limitation = "Talos cannot extract PDF contents with the current local text-tool surface.";

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(limitation, messages, null, true);

        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertTrue(outcome.finalAnswer().contains(limitation));
        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void streamingNoToolMutationRequestIsNotCapabilityCorrected() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Can you create script.js in this workspace?"));

        String negativeClaim = "I don't have direct access to your local files to create that.";

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(negativeClaim, messages, null, true);

        assertEquals(negativeClaim, outcome.finalAnswer());
        assertFalse(outcome.taskOutcome().hasWarning(
                TruthWarningType.NO_TOOL_LOCAL_ACCESS_CAPABILITY_CORRECTED));
    }

    @Test
    void streamingNoToolMutationNarrativeIsBlocked() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("I think the html is completely wrong. Can you fix it?"));

        String fabricated = """
                Sure! Here is the updated index.html.

                ### Updated `index.html`
                Summary of changes:
                - updated index.html
                - these changes should ensure the selectors now match
                """;

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(fabricated, messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.noToolMutationReplaced());
        assertEquals(AssistantTurnExecutor.STREAMING_NO_TOOL_MUTATION_REPLACEMENT, outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertEquals(MutationOutcomeStatus.NOT_ATTEMPTED, outcome.taskOutcome().mutationOutcome().status());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STREAMING_NO_TOOL_MUTATION_REPLACED));
    }

    @Test
    void malformedProtocolArrayNoToolAnswerIsFailedAndReplaced() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Make the edits please."));

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool("""
                [
                    ,

                ]
                """, messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
        assertTrue(outcome.malformedProtocolDebrisReplaced());
        assertEquals(AssistantTurnExecutor.MALFORMED_TOOL_PROTOCOL_REPLACEMENT, outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(
                TruthWarningType.MALFORMED_TOOL_PROTOCOL_DEBRIS_REPLACED));
    }

    @Test
    void noToolExplicitReadTargetIsAdvisoryWithMissingEvidenceWarning() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read README.md and summarize it."));

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                "README.md describes the project.", messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void noToolReadTargetMissingEvidenceSuppressesDerivedWorkspaceContent() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Please review README.md and propose concise improvements, but do not edit any files yet."));

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                "README.md says Talos is done. Proposed improvements: add install steps.",
                messages,
                null,
                true);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertTrue(outcome.finalAnswer().contains("did not inspect"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("Talos is done"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("Proposed improvements"), outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void readTargetMissingEvidencePreservesRuntimeFailurePolicyNotice() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read README.md and tell me the product name."));

        var loopResult = new ToolCallLoop.LoopResult(
                "[Tool loop stopped by failure policy: repeated tool failures. "
                        + "Review the latest tool errors before retrying.]",
                3,
                3,
                List.of("talos.read_file"),
                List.of(),
                3,
                3,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "READMEE.md", false, false, false,
                        "", "READMEE.md was not found.", null, ToolError.NOT_FOUND)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertTrue(outcome.finalAnswer().contains("Tool loop stopped by failure policy"),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("did not inspect"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void noToolProtectedReadMissingEvidenceFailsClosedAndSuppressesFabricatedContent() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me what it says."));

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                "API_KEY=your_api_key_here\nDATABASE_URL=your_database_url_here",
                messages,
                null,
                true);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Protected read not attempted:"),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("talos.read_file"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("no approval prompt ran"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("no protected content was read"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("[Evidence incomplete:"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("API_KEY"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("DATABASE_URL"), outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void traceOutcomeClassificationMatchesDominantTaskOutcome() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read README.md and summarize it."));

        LocalTurnTraceCapture.begin(
                "trc-test",
                "sid",
                1,
                "2026-04-30T12:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "Read README.md and summarize it.");
        try {
            ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                    "README.md describes the project.", messages, null, true);

            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            assertNotNull(trace);
            assertNotNull(trace.outcome());
            assertEquals(outcome.completionStatus().name(), trace.outcome().status());
            assertEquals(
                    outcome.taskOutcome().completionStatus().name(),
                    trace.outcome().classification());
            assertEquals("ADVISORY_ONLY", trace.outcome().status());
            assertEquals("ADVISORY_ONLY", trace.outcome().classification());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void toolLoopReadTargetNotFoundCountsAsEvidenceAndReadOnlyAnswered() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read README.md and summarize it."));

        var loopResult = new ToolCallLoop.LoopResult(
                "README.md was not found.", 1, 1,
                List.of("talos.read_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", "README.md", false, false, false,
                        "", "README.md was not found.", null, ToolError.NOT_FOUND)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "README.md was not found.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        assertFalse(outcome.finalAnswer().startsWith("[Evidence incomplete:"));
    }

    @Test
    void verificationRequiredReadOnlyWithEvidenceButNoVerifierIsAdvisory() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Is this BMI page working now?"));

        var contract = dev.talos.runtime.task.TaskContractResolver.fromMessages(messages);
        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                contract,
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of("talos.read_file", "talos.grep", "talos.retrieve"),
                List.of("talos.read_file", "talos.grep", "talos.retrieve"),
                List.of());

        var loopResult = new ToolCallLoop.LoopResult(
                "The BMI page appears to be working.", 3, 3,
                List.of("talos.read_file", "talos.read_file", "talos.read_file"), List.of(),
                3, 0, false, 0, List.of("index.html", "styles.css", "scripts.js"),
                0, 0, 0, 0,
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "index.html", true, false, false,
                                "<!doctype html><title>BMI</title><h1>BMI</h1>", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "styles.css", true, false, false,
                                "body { font-family: sans-serif; }", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "scripts.js", true, false, false,
                                "// Your JavaScript logic here", "")
                ));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "The BMI page appears to be working.", plan, messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Task not verified:"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("not verified"), outcome.finalAnswer());
        assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        assertFalse(outcome.finalAnswer().startsWith("[Evidence incomplete:"));
    }

    @Test
    void verificationRequiredReadOnlyWithMissingEvidenceStillSaysNotVerified() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Is this BMI page working now?"));

        var contract = dev.talos.runtime.task.TaskContractResolver.fromMessages(messages);
        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                contract,
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of("talos.read_file", "talos.grep", "talos.retrieve"),
                List.of("talos.read_file", "talos.grep", "talos.retrieve"),
                List.of());

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                "The BMI page appears to be working.", plan, messages, null, true);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertTrue(outcome.finalAnswer().contains("not verified"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void workspaceInspectionMissingEvidenceSuppressesModelBody() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("What files changed during this audit? Do not read protected files."));

        String fabricated = "Changed files:\n"
                + "- README.md now contains public notes.\n"
                + "- notes.md contains SECRET-FAKE audit details.\n";

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                fabricated, messages, null, false);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertFalse(outcome.finalAnswer().contains("README.md now contains"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("SECRET-FAKE"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void legacyLoopReadPathsCountAsReadTargetEvidence() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read README.md and summarize it."));

        var loopResult = new ToolCallLoop.LoopResult(
                "README.md describes the project.", 1, 1,
                List.of("talos.read_file"), List.of(),
                0, 0, false, 0, List.of("README.md"),
                0, 0, 0, 0);

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "README.md describes the project.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        assertFalse(outcome.finalAnswer().startsWith("[Evidence incomplete:"));
    }

    @Test
    void deniedProtectedReadDominatesMissingEvidenceAndSanitizesSecretProse() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me what it says."));

        var loopResult = new ToolCallLoop.LoopResult(
                "The file says SECRET=original.", 1, 1,
                List.of("talos.read_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", ".env", false, false, true,
                        "", "User did not approve the talos.read_file call.", null, ToolError.DENIED)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "The file says SECRET=original.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, outcome.taskOutcome().completionStatus());
        assertFalse(outcome.finalAnswer().contains("SECRET=original"));
        assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.DENIED_PROTECTED_READ));
    }

    @Test
    void attemptedProtectedReadFailureDoesNotReportNoToolAttempt() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Read .env and tell me what it says."));

        var loopResult = new ToolCallLoop.LoopResult(
                "The file says SECRET=original.", 1, 1,
                List.of("talos.read_file"), List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.read_file", ".env", false, false, false,
                        "", "Read failed before protected content was returned.", null, ToolError.NOT_FOUND)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "The file says SECRET=original.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Protected read incomplete:"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("talos.read_file was attempted"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("not attempted"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("SECRET=original"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void listOnlyWithReadFileIsAdvisoryWithMissingEvidenceWarning() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("List the files in this directory."));

        var loopResult = new ToolCallLoop.LoopResult(
                "README.md contains project notes.", 1, 2,
                List.of("talos.list_dir", "talos.read_file"), List.of(),
                0, 0, false, 0, List.of("README.md"),
                0, 0, 0, 0,
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.list_dir", ".", true, false, false,
                                "listed files", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "README.md", true, false, false,
                                "read README.md", "")));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                "README.md contains project notes.", messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertFalse(outcome.finalAnswer().contains("README.md contains project notes."), outcome.finalAnswer());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void staticWebDiagnosisWithOnlyDirectoryListingIsEvidenceIncomplete() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Check whether this website has mismatches between HTML classes/IDs "
                        + "and selectors used in CSS or JavaScript. Do not change anything yet."));

        var loopResult = new ToolCallLoop.LoopResult(
                "I need to inspect index.html, script.js, and styles.css next.",
                1, 1,
                List.of("talos.list_dir"), List.of(),
                0, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", true, false, false,
                        "index.html\nscript.js\nstyles.css\n", "")));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
        assertFalse(outcome.finalAnswer().contains("I need to inspect"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void staticWebDiagnosisWithStaticSourceReadsIsNotEvidenceIncomplete() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Check whether this website has mismatches between HTML classes/IDs "
                        + "and selectors used in CSS or JavaScript. Do not change anything yet."));

        var loopResult = new ToolCallLoop.LoopResult(
                "There are no mismatches.",
                3, 3,
                List.of("talos.read_file", "talos.read_file", "talos.read_file"), List.of(),
                0, 0, false, 0,
                List.of("index.html", "style.css", "script.js"),
                0, 0, 0, 0,
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "index.html", true, false, false,
                                "read index", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "style.css", true, false, false,
                                "read css", ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.read_file", "script.js", true, false, false,
                                "read js", "")));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertFalse(outcome.finalAnswer().startsWith("[Evidence incomplete:"), outcome.finalAnswer());
        assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }
}
