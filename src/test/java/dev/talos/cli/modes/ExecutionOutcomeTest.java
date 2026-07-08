package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.outcome.MutationOutcomeStatus;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.verification.ProofKind;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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
                ).withFailureReason(ToolFailureReason.USER_APPROVAL_DENIED)));

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
                ).withFailureReason(ToolFailureReason.USER_APPROVAL_DENIED)));

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
                                null, ToolError.INVALID_PARAMS)
                                .withFailureReason(ToolFailureReason.EDIT_EMPTY_ARGUMENTS),
                        new ToolCallLoop.ToolOutcome(
                                "talos.edit_file", "index.html", false, true, true,
                                "", "User did not approve the talos.edit_file call.",
                                null, ToolError.DENIED)
                                .withFailureReason(ToolFailureReason.USER_APPROVAL_DENIED)
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
    void failedCommandDominatesModelSuccessProse() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Verify that the Gradle tests pass."));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of("talos.run_command"),
                List.of("talos.run_command"),
                List.of());
        var loopResult = new ToolCallLoop.LoopResult(
                "All tests passed. The work is complete and ready to use.",
                1, 1,
                List.of("talos.run_command"),
                List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.run_command", "", false, false, false,
                        "", "Command failed: gradle_test exited with code 1 after 25ms.\n"
                        + "profile: gradle_test\nstdout:\nFAILED", null, ToolError.INTERNAL_ERROR
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), plan, messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Command failed:"), outcome.finalAnswer());
        String lower = outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("all tests passed"), outcome.finalAnswer());
        assertFalse(lower.contains("complete"), outcome.finalAnswer());
        assertFalse(lower.contains("ready to use"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.COMMAND_FAILED));
    }

    @Test
    void deniedCommandDominatesModelSuccessProse() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Verify that the Gradle tests pass."));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of("talos.run_command"),
                List.of("talos.run_command"),
                List.of());
        var loopResult = new ToolCallLoop.LoopResult(
                "All tests passed and everything is complete.",
                1, 1,
                List.of("talos.run_command"),
                List.of(),
                1, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.run_command", "", false, false, true,
                        "", "User did not approve the talos.run_command call.",
                        null, ToolError.DENIED
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), plan, messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Command not run:"), outcome.finalAnswer());
        String lower = outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("all tests passed"), outcome.finalAnswer());
        assertFalse(lower.contains("complete"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.COMMAND_DENIED));
    }

    @Test
    void successfulVerifyCommandUsesRuntimeOwnedSummary() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Verify that the Gradle tests pass."));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of("talos.run_command"),
                List.of("talos.run_command"),
                List.of());
        var loopResult = new ToolCallLoop.LoopResult(
                "All tests passed and everything is complete.",
                1, 1,
                List.of("talos.run_command"),
                List.of(),
                0, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.run_command", "", true, false, false,
                        "Command succeeded: gradle_test exited with code 0 after 31ms",
                        "", null, ""
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), plan, messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus());
        assertEquals(
                "Command succeeded: gradle_test exited with code 0 after 31ms.",
                outcome.finalAnswer());
        assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
    }

    @Test
    void successfulCommandDoesNotCompleteUnperformedMutationRequest() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Edit index.html to add the CTA button, then run the tests."));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.APPLY,
                List.of("talos.write_file", "talos.run_command"),
                List.of("talos.write_file", "talos.run_command"),
                List.of());
        var loopResult = new ToolCallLoop.LoopResult(
                "I updated index.html and the tests passed.",
                1, 1,
                List.of("talos.run_command"),
                List.of(),
                0, 0, false, 0, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.run_command", "", true, false, false,
                        "Command succeeded: gradle_test exited with code 0 after 31ms",
                        "", null, ""
                )));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), plan, messages, loopResult, null, 0);

        assertFalse(outcome.completionStatus() == ExecutionOutcome.CompletionStatus.COMPLETE,
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().equals(
                "Command succeeded: gradle_test exited with code 0 after 31ms."));
    }

    @Test
    void explicitCommandRequestWithoutRunCommandIsBlockedAndSanitizedAfterReadOnlyTools() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Run the approved Gradle test command profile for this workspace and report the exact command result. "
                        + "Do not invent a pass if the command cannot run."));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.run_command"),
                List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.run_command"),
                List.of());
        var loopResult = new ToolCallLoop.LoopResult(
                "There is no Gradle project here, so I cannot run the tests.",
                2,
                2,
                List.of("talos.list_dir", "talos.grep"),
                List.of(),
                0,
                0,
                false,
                0,
                List.of("."),
                2,
                0,
                0,
                0,
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.list_dir", ".", true, false, false,
                                "README.md", "", null, ""),
                        new ToolCallLoop.ToolOutcome(
                                "talos.grep", "", true, false, false,
                                "No matches found.", "", null, "")
                ));

        assertEquals(
                "explicit-command-verification-request",
                plan.taskContract().classificationReason());

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), plan, messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Command not run: talos.run_command was required for this explicit command request.]"),
                outcome.finalAnswer());
        String lower = outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("no gradle project"), outcome.finalAnswer());
        assertFalse(lower.contains("cannot run"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
    }

    @Test
    void unsupportedNaturalCommandRequestWithReadOnlyFallbackDoesNotInventApprovalDenial() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Run the command Get-ChildItem -Name to list workspace file names. "
                        + "I will deny command approval."));

        var contract = dev.talos.runtime.task.TaskContractResolver.fromMessages(messages);
        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                contract,
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("talos.list_dir", "talos.read_file"),
                List.of());
        var loopResult = new ToolCallLoop.LoopResult(
                "The command was not executed because approval was denied.",
                1,
                1,
                List.of("talos.list_dir"),
                List.of(),
                0,
                0,
                false,
                0,
                List.of("."),
                1,
                0,
                0,
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", true, false, false,
                        "README.md\nsrc", "", null, "")));

        assertEquals("unsupported-command-verification-request", contract.classificationReason());

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), plan, messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Command not run: the requested command is outside the current bounded command profile.]"),
                outcome.finalAnswer());
        String lower = outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("approval was denied"), outcome.finalAnswer());
        assertFalse(lower.contains("approval denied"), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
    }

    @Test
    void fabricatedGitStatusOutputWithoutRunCommandIsWithheldAfterReadOnlyToolUse() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("what is the git status of this workspace?"));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                new dev.talos.runtime.task.TaskContract(
                        dev.talos.runtime.task.TaskType.READ_ONLY_QA,
                        false,
                        false,
                        false,
                        java.util.Set.of(),
                        java.util.Set.of(),
                        "what is the git status of this workspace?",
                        "read-only-command-status-question"),
                dev.talos.runtime.phase.ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file", "talos.grep"),
                List.of("talos.list_dir", "talos.read_file", "talos.grep"),
                List.of());
        var loopResult = new ToolCallLoop.LoopResult(
                """
                        On branch main
                        Your branch is up to date with 'origin/main'.

                        Changes not staged for commit:
                          modified: src/App.java
                        """,
                1,
                1,
                List.of("talos.list_dir"),
                List.of(),
                0,
                0,
                false,
                0,
                List.of("."),
                0,
                0,
                0,
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", true, false, false,
                        "README.md\nsrc", "", null, "")));

        LocalTurnTraceCapture.begin(
                "trc-t866-command-output-truth",
                "sid-t866",
                1,
                "2026-06-24T00:00:00Z",
                "workspace-hash",
                "auto",
                "llama.cpp",
                "gpt-oss:20b",
                "what is the git status of this workspace?");
        try {
            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), plan, messages, loopResult, null, 0);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Command output truth check:"), outcome.finalAnswer());
            String lower = outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("on branch main"), outcome.finalAnswer());
            assertFalse(lower.contains("changes not staged"), outcome.finalAnswer());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.UNSUPPORTED_COMMAND_OUTPUT_CLAIM));
            assertNotNull(trace);
            assertTrue(trace.warnings().stream().anyMatch(warning ->
                    "UNSUPPORTED_COMMAND_OUTPUT_CLAIM".equals(warning.code())
                            && warning.message().contains("without a successful talos.run_command")),
                    trace.warnings().toString());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void fabricatedTestRunOutputWithoutRunCommandIsWithheldWithShapeTrace() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Did the project tests pass?"));

        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                new dev.talos.runtime.task.TaskContract(
                        dev.talos.runtime.task.TaskType.READ_ONLY_QA,
                        false,
                        false,
                        false,
                        java.util.Set.of(),
                        java.util.Set.of(),
                        "Did the project tests pass?",
                        "read-only-command-test-question"),
                dev.talos.runtime.phase.ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file", "talos.grep"),
                List.of("talos.list_dir", "talos.read_file", "talos.grep"),
                List.of());
        var loopResult = new ToolCallLoop.LoopResult(
                """
                        BUILD SUCCESSFUL in 12s
                        5378 tests completed, 0 failed
                        """,
                1,
                1,
                List.of("talos.list_dir"),
                List.of(),
                0,
                0,
                false,
                0,
                List.of("."),
                0,
                0,
                0,
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.list_dir", ".", true, false, false,
                        "README.md\nsrc", "", null, "")));

        LocalTurnTraceCapture.begin(
                "trc-t873-command-output-truth",
                "sid-t873",
                1,
                "2026-06-30T00:00:00Z",
                "workspace-hash",
                "auto",
                "llama.cpp",
                "qwen2.5-coder:14b",
                "Did the project tests pass?");
        try {
            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), plan, messages, loopResult, null, 0);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Command output truth check:"), outcome.finalAnswer());
            String lower = outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("build successful"), outcome.finalAnswer());
            assertFalse(lower.contains("tests completed"), outcome.finalAnswer());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.UNSUPPORTED_COMMAND_OUTPUT_CLAIM));
            assertNotNull(trace);
            assertTrue(trace.warnings().stream().anyMatch(warning ->
                    "UNSUPPORTED_COMMAND_OUTPUT_CLAIM".equals(warning.code())
                            && warning.message().contains("command/tool output")),
                    trace.warnings().toString());
            assertTrue(trace.events().stream().anyMatch(event ->
                    "PROTOCOL_SANITIZED".equals(event.type())
                            && String.valueOf(event.data().get("reason")).contains("shape=test-run")
                            && String.valueOf(event.data().get("reason")).contains("missingProducer=talos.run_command")),
                    trace.events().toString());
        } finally {
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void explicitCommandRequestWithoutAnyToolIsBlockedAndSanitized() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Run the approved Gradle test command profile for this workspace and report the exact command result. "
                        + "Do not invent a pass if the command cannot run."));
        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.run_command"),
                List.of("talos.grep", "talos.list_dir", "talos.read_file", "talos.run_command"),
                List.of());

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                "The Gradle tests passed.",
                plan,
                messages,
                null,
                true);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Command not run: talos.run_command was required for this explicit command request.]"),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT).contains("passed"),
                outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
    }

    @Test
    void unsupportedPythonCommandGetsDeterministicDirectAnswer() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Run python -m pytest."));
        var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                dev.talos.runtime.phase.ExecutionPhase.VERIFY,
                List.of(),
                List.of(),
                List.of());

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(
                "pytest passed.",
                plan,
                messages,
                null,
                true);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith(
                "[Command not run: Python execution is outside the current bounded command profile.]"),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT).contains("pytest passed"),
                outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
    }

    @Test
    void createPythonAndRunTestsDoesNotClaimExecution() throws Exception {
        Path ws = Files.createTempDirectory("talos-python-command-boundary-");
        try {
            Files.writeString(ws.resolve("dijkstra.py"), "def shortest_path():\n    return 7\n");
            Files.writeString(ws.resolve("test_dijkstra.py"), "def test_shortest_path():\n    assert True\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Create dijkstra.py and test_dijkstra.py, then run pytest."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Created both files and pytest passed.",
                    1,
                    2,
                    List.of("talos.write_file", "talos.write_file"),
                    List.of(),
                    2,
                    0,
                    false,
                    2,
                    List.of("dijkstra.py", "test_dijkstra.py"),
                    0, 0, 0, 0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "dijkstra.py", true, true, false,
                                    "Created dijkstra.py", "", dev.talos.tools.VerificationStatus.PASS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "test_dijkstra.py", true, true, false,
                                    "Created test_dijkstra.py", "", dev.talos.tools.VerificationStatus.PASS)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Created both files and pytest passed.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().contains(
                            "Python execution is outside the current bounded command profile"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT).contains("pytest passed"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT).contains("tests passed"),
                    outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void pythonReadbackOnlyDoesNotClaimAlgorithmVerified() throws Exception {
        Path ws = Files.createTempDirectory("talos-python-readback-only-");
        try {
            Files.writeString(ws.resolve("solver.py"), "def solve(items):\n    return sorted(items)\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Create solver.py, then run python solver.py to verify the algorithm."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Created solver.py. The algorithm is verified.",
                    1,
                    1,
                    List.of("talos.write_file"),
                    List.of(),
                    1,
                    0,
                    false,
                    1,
                    List.of("solver.py"),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "solver.py", true, true, false,
                            "Created solver.py", "", dev.talos.tools.VerificationStatus.PASS)));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Created solver.py. The algorithm is verified.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[File write/readback passed."),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains(
                            "No Python, pytest, or .py command result is available"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT).contains("algorithm is verified"),
                    outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
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
                assertTrue(outcome.finalAnswer().startsWith(
                        "[Truth check: Talos applied mutation(s) before this action-obligation block.]"),
                        outcome.finalAnswer());
                assertTrue(outcome.finalAnswer().contains(
                        "Changed target(s) before the block: index.html, styles.css, scripts.js."),
                        outcome.finalAnswer());
                assertTrue(outcome.finalAnswer().contains("[Action obligation failed:"),
                        outcome.finalAnswer());
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
    void blockedActionObligationAfterSuccessfulMutationDisclosesChangedTarget() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Rewrite styles.css so index.html still works. Do not edit scripts.js."));

        String answer = """
                [Action obligation failed: expected-target progress was not satisfied.]

                Remaining target(s): scripts.js.
                The model attempted talos.write_file(styles.css) instead.
                No approval was requested and no additional file was changed.
                """;
        var loopResult = new ToolCallLoop.LoopResult(
                answer,
                2,
                1,
                List.of("talos.write_file"),
                List.of(),
                0,
                0,
                false,
                1,
                List.of(),
                0,
                0,
                0,
                0,
                FailureDecision.stop(
                        FailureAction.ASK_USER,
                        "Pending action obligation EXPECTED_TARGETS_REMAINING was ignored after a progress reprompt."),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.write_file",
                        "styles.css",
                        true,
                        true,
                        false,
                        "wrote styles.css",
                        "",
                        dev.talos.tools.VerificationStatus.PASS)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
        assertTrue(outcome.finalAnswer().contains("Changed target(s) before the block: styles.css."),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("No approval was requested"),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("no additional file was changed"),
                outcome.finalAnswer());
    }

    @Test
    void partialMutationDoesNotHideActionObligationBlock() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Update index.html and scripts.js. Make #teaser-button update #teaser-status."));

        String answer = """
                [Action obligation failed: pending static repair progress was not satisfied.]

                Remaining target(s): scripts.js.
                The model returned prose instead of the required write_file repair call.
                """;
        var loopResult = new ToolCallLoop.LoopResult(
                answer,
                4,
                3,
                List.of("talos.read_file", "talos.write_file", "talos.edit_file"),
                List.of(),
                1,
                0,
                false,
                1,
                List.of(),
                0,
                0,
                0,
                0,
                FailureDecision.stop(
                        FailureAction.ASK_USER,
                        "COMPACT_MUTATION_CONTINUATION_NO_TOOL: compact mutation continuation returned no write/edit tool calls."),
                List.of(
                        new ToolCallLoop.ToolOutcome(
                                "talos.write_file",
                                "index.html",
                                true,
                                true,
                                false,
                                "wrote index.html",
                                "",
                                dev.talos.tools.VerificationStatus.PASS),
                        new ToolCallLoop.ToolOutcome(
                                "talos.edit_file",
                                "scripts.js",
                                false,
                                true,
                                false,
                                "",
                                "old_string not found in scripts.js.",
                                null)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
        assertTrue(outcome.finalAnswer().startsWith(
                        "[Truth check: Talos applied mutation(s) before this action-obligation block.]"),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("Changed target(s) before the block: index.html."),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("[Action obligation failed:"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("Remaining target(s): scripts.js."), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("Succeeded:"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("Failed:"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("scripts.js: old_string not found in scripts.js."),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().startsWith(
                        "[Truth check: some requested file changes succeeded and some failed."),
                outcome.finalAnswer());
    }

    @Test
    void preMutationActionObligationBlockKeepsNoFileChangedWording() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Edit styles.css."));

        String answer = """
                [Action obligation failed: expected-target progress was not satisfied.]

                Remaining target(s): styles.css.
                The model returned prose instead of the required write/edit tool call.
                No approval was requested and no additional file was changed.
                """;
        var loopResult = new ToolCallLoop.LoopResult(
                answer,
                1,
                0,
                List.of(),
                List.of(),
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0,
                FailureDecision.stop(
                        FailureAction.ASK_USER,
                        "Pending action obligation EXPECTED_TARGETS_REMAINING was ignored after a progress reprompt."),
                List.of());

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().contains("No approval was requested"),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("no additional file was changed"),
                outcome.finalAnswer());
    }

    @Test
    void embeddedStaticVerificationFailureInBlockedToolLoopIsRecordedInOutcomeAndTrace() throws Exception {
        Path ws = Files.createTempDirectory("talos-embedded-static-failure-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="style.css"></head>
                      <body><script src="script.js"></script></body>
                    </html>
                    """);
            Files.writeString(ws.resolve("style.css"), "body { background: #100020; }\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "But make sure there is a real modern synthwave style and JavaScript interaction. Fix the files if needed."));

            String answer = """
                    [Task incomplete: Static verification failed - HTML references missing JavaScript file: `script.js`]

                    Unresolved static verification problems:
                    - HTML references missing JavaScript file: `script.js`

                    The requested task is not verified complete.

                    [Action obligation failed: pending expected target progress was not satisfied.]

                    Remaining target(s): script.js.
                    """;
            var loopResult = new ToolCallLoop.LoopResult(
                    answer,
                    4,
                    3,
                    List.of("talos.read_file", "talos.list_dir", "talos.write_file"),
                    List.of(),
                    0,
                    0,
                    false,
                    1,
                    List.of("index.html"),
                    0,
                    0,
                    0,
                    0,
                    FailureDecision.stop(
                            FailureAction.ASK_USER,
                            "Pending action obligation EXPECTED_TARGET_PROGRESS was ignored."),
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "style.css", true, true, false,
                            "wrote style.css", "", dev.talos.tools.VerificationStatus.PASS)));

            LocalTurnTraceCapture.begin(
                    "trc-embedded-static-failure",
                    "sid",
                    1,
                    "2026-05-20T12:00:00Z",
                    "workspace-hash",
                    "auto",
                    "test",
                    "model",
                    messages.get(1).content());
            try {
                ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                        loopResult.finalAnswer(), messages, loopResult, ws, 0);

                LocalTurnTrace trace = LocalTurnTraceCapture.complete();
                assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
                assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
                assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
                assertTrue(outcome.finalAnswer().contains("Static verification failed"), outcome.finalAnswer());
                assertNotNull(trace);
                assertNotNull(trace.outcome());
                assertEquals("FAILED", trace.outcome().verificationStatus());
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
                ).withFailureReason(ToolFailureReason.USER_APPROVAL_DENIED)));

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
    void completedMutationWithFileVerificationWarningIsNotRenderedAsVerifiedSuccess() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-warn-static-");
        try {
            Files.writeString(ws.resolve("README.md"), "# Talos\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Create README.md with a short project headline."));
            var plan = new dev.talos.runtime.turn.CurrentTurnPlan(
                    dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                    null,
                    dev.talos.runtime.phase.ExecutionPhase.APPLY,
                    null,
                    null,
                    null,
                    List.of("talos.write_file"),
                    List.of("talos.write_file"),
                    List.of(),
                    "NONE",
                    null,
                    null,
                    null,
                    null);

            var loopResult = new ToolCallLoop.LoopResult(
                    "[ok] Wrote README.md", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    1, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "README.md", true, true, false,
                            "Wrote README.md", "", dev.talos.tools.VerificationStatus.WARN)));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Done. I updated README.md and verified it.", plan, messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.FAILED, outcome.taskOutcome().verificationResult().status());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STATIC_VERIFICATION_FAILED));
            assertTrue(outcome.finalAnswer().startsWith("[Task incomplete: Static verification failed -"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("file-level verification reported warning"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("[Static verification: passed"),
                    outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void partialInvalidStaticWebRepairRunsStaticVerificationForChangedWorkspace() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-partial-invalid-static-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                      <meta charset="UTF-8">
                      <title>Broken Repair</title>
                      <link rel="stylesheet" href="style.css">
                    </head>
                    <body>
                      <main class="hero-content"><h1>Broken Repair</h1></main>
                      <script src="script.js">
                    </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("style.css"), ".hero-content { max-width: 720px; }");
            Files.writeString(ws.resolve("script.js"), "document.querySelector('.cta-button');");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Fix this website with the smallest exact edits so the HTML, CSS, and JavaScript remain valid and linked."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "[ok] Edited index.html\n[failed] index.html", 1, 2,
                    List.of("talos.write_file", "talos.edit_file"), List.of(),
                    1, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    FailureDecision.stop(
                            FailureAction.STOP_WITH_PARTIAL,
                            "failure policy stopped the tool loop after 3 consecutive no-progress iteration(s)."),
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "index.html", true, true, false,
                                    "Updated index.html", "", dev.talos.tools.VerificationStatus.PASS),
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
            assertTrue(outcome.finalAnswer().contains("Remaining static verification problems:"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("some requested file changes succeeded and some failed"),
                    outcome.finalAnswer());
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
                                    null, ToolError.INVALID_PARAMS)
                                    .withFailureReason(ToolFailureReason.EDIT_EMPTY_ARGUMENTS),
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
    void workspaceOperationReadbackSummaryUsesOperationWording() throws Exception {
        Path ws = Files.createTempDirectory("talos-workspace-operation-readback-wording-");
        try {
            Files.createDirectories(ws.resolve("archive"));
            Files.createDirectories(ws.resolve("copies"));
            Files.createDirectories(ws.resolve("scratch/nested/reports"));
            Files.writeString(ws.resolve("archive/source.md"), "# Source\n");
            Files.writeString(ws.resolve("copies/source-final.md"), "# Source\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Move source.md to archive/source.md, copy archive/source.md to copies/source-copy.md, "
                            + "rename copies/source-copy.md to source-final.md, and create directory "
                            + "scratch/nested/reports."));

            WorkspaceOperationPlan movePlan = WorkspaceOperationPlan.movePath(
                    "source.md",
                    "archive/source.md",
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS);
            WorkspaceOperationPlan copyPlan = WorkspaceOperationPlan.copyPath(
                    "archive/source.md",
                    "copies/source-copy.md",
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                    false);
            WorkspaceOperationPlan renamePlan = WorkspaceOperationPlan.batch(
                    WorkspaceOperationPlan.OperationKind.RENAME_PATH,
                    List.of(
                            WorkspaceOperationPlan.PathEffect.source(
                                    "copies/source-copy.md", true, WorkspaceOperationPlan.OperationKind.RENAME_PATH),
                            WorkspaceOperationPlan.PathEffect.destination(
                                    "copies/source-final.md", true, WorkspaceOperationPlan.OperationKind.RENAME_PATH)),
                    dev.talos.tools.ToolRiskLevel.WRITE,
                    true,
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                    false,
                    "Rename copies/source-copy.md to copies/source-final.md.",
                    "Rename: copies/source-copy.md -> copies/source-final.md");
            WorkspaceOperationPlan mkdirPlan = WorkspaceOperationPlan.batch(
                    WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY,
                    List.of(WorkspaceOperationPlan.PathEffect.absentBefore(
                            "scratch/nested/reports", true, WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY)),
                    dev.talos.tools.ToolRiskLevel.WRITE,
                    true,
                    WorkspaceOperationPlan.OverwritePolicy.NOT_APPLICABLE,
                    false,
                    "Create directory scratch/nested/reports.",
                    "Mkdir: scratch/nested/reports");

            var loopResult = new ToolCallLoop.LoopResult(
                    "Workspace operations applied.", 1, 4,
                    List.of("talos.move_path", "talos.copy_path", "talos.rename_path", "talos.mkdir"),
                    List.of(), 4, 0, false, 4, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            workspaceOutcome("talos.move_path", "archive/source.md", true,
                                    "Moved source.md -> archive/source.md", "", "", movePlan),
                            workspaceOutcome("talos.copy_path", "copies/source-copy.md", true,
                                    "Copied archive/source.md -> copies/source-copy.md", "", "", copyPlan),
                            workspaceOutcome("talos.rename_path", "copies/source-final.md", true,
                                    "Renamed copies/source-copy.md -> copies/source-final.md", "", "", renamePlan),
                            workspaceOutcome("talos.mkdir", "scratch/nested/reports", true,
                                    "Created directory scratch/nested/reports", "", "", mkdirPlan)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Workspace operations applied.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Workspace operation/readback passed."),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("File write/readback passed"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("task completion was not verified"),
                    outcome.finalAnswer());
            assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void exactFileTargetCreatedAsDirectoryIsFailureDominant() throws Exception {
        Path ws = Files.createTempDirectory("talos-workspace-operation-directory-file-target-");
        try {
            Files.createDirectories(ws.resolve("workspace-notes/summary.txt"));

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a directory named workspace-notes and create workspace-notes/summary.txt "
                            + "containing exactly created by audit."));

            WorkspaceOperationPlan mkdirWorkspaceNotes = WorkspaceOperationPlan.batch(
                    WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY,
                    List.of(WorkspaceOperationPlan.PathEffect.absentBefore(
                            "workspace-notes", true, WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY)),
                    dev.talos.tools.ToolRiskLevel.WRITE,
                    true,
                    WorkspaceOperationPlan.OverwritePolicy.NOT_APPLICABLE,
                    false,
                    "Create directory workspace-notes.",
                    "Mkdir: workspace-notes");
            WorkspaceOperationPlan mkdirSummaryTxt = WorkspaceOperationPlan.batch(
                    WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY,
                    List.of(WorkspaceOperationPlan.PathEffect.absentBefore(
                            "workspace-notes/summary.txt",
                            true,
                            WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY)),
                    dev.talos.tools.ToolRiskLevel.WRITE,
                    true,
                    WorkspaceOperationPlan.OverwritePolicy.NOT_APPLICABLE,
                    false,
                    "Create directory workspace-notes/summary.txt.",
                    "Mkdir: workspace-notes/summary.txt");

            var loopResult = new ToolCallLoop.LoopResult(
                    "Done. The file is complete and ready to use.", 1, 2,
                    List.of("talos.mkdir", "talos.mkdir"), List.of(),
                    2, 0, false, 2, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            workspaceOutcome("talos.mkdir", "workspace-notes", true,
                                    "Created directory workspace-notes", "", "", mkdirWorkspaceNotes),
                            workspaceOutcome("talos.mkdir", "workspace-notes/summary.txt", true,
                                    "Created directory workspace-notes/summary.txt", "", "", mkdirSummaryTxt)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Task incomplete: Static verification failed -"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Exact content verification failed"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Workspace operation/readback passed"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("complete and ready to use"),
                    outcome.finalAnswer());
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void partialWorkspaceOperationDoesNotUseReadbackSuccessBanner() throws Exception {
        Path ws = Files.createTempDirectory("talos-workspace-operation-partial-wording-");
        try {
            Files.createDirectories(ws.resolve("scratch/reports"));

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create directory scratch/reports and move missing.md to archive/missing.md."));

            WorkspaceOperationPlan mkdirPlan = WorkspaceOperationPlan.batch(
                    WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY,
                    List.of(WorkspaceOperationPlan.PathEffect.absentBefore(
                            "scratch/reports", true, WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY)),
                    dev.talos.tools.ToolRiskLevel.WRITE,
                    true,
                    WorkspaceOperationPlan.OverwritePolicy.NOT_APPLICABLE,
                    false,
                    "Create directory scratch/reports.",
                    "Mkdir: scratch/reports");
            WorkspaceOperationPlan movePlan = WorkspaceOperationPlan.movePath(
                    "missing.md",
                    "archive/missing.md",
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS);

            var loopResult = new ToolCallLoop.LoopResult(
                    "Created the folder and moved the file.", 1, 2,
                    List.of("talos.mkdir", "talos.move_path"),
                    List.of(), 1, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            workspaceOutcome("talos.mkdir", "scratch/reports", true,
                                    "Created directory scratch/reports", "", "", mkdirPlan),
                            workspaceOutcome("talos.move_path", "archive/missing.md", false,
                                    "", "Source not found: missing.md",
                                    ToolError.NOT_FOUND, movePlan)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Created the folder and moved the file.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.PARTIAL, outcome.completionStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Partial verification: static checks failed"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains(AssistantTurnExecutor.PARTIAL_MUTATION_ANNOTATION),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Workspace operation/readback passed"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("File write/readback passed"),
                    outcome.finalAnswer());
            assertEquals(TaskCompletionStatus.PARTIAL, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.PARTIAL_MUTATION));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void failedWorkspaceOperationDoesNotUseReadbackSuccessBanner() throws Exception {
        Path ws = Files.createTempDirectory("talos-workspace-operation-failed-wording-");
        try {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Copy README.md to docs/README-copy.md."));

            WorkspaceOperationPlan copyPlan = WorkspaceOperationPlan.copyPath(
                    "README.md",
                    "docs/README-copy.md",
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                    false);

            var loopResult = new ToolCallLoop.LoopResult(
                    "I have created docs/README-copy.md.", 1, 1,
                    List.of("talos.copy_path"),
                    List.of(), 1, 0, false, 0, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            workspaceOutcome("talos.copy_path", "docs/README-copy.md", false,
                                    "", "Invalid destination path: docs/README-copy.md",
                                    ToolError.INVALID_PARAMS, copyPlan)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "I have created docs/README-copy.md.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.INVALID_MUTATION_ANNOTATION),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Workspace operation/readback passed"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("File write/readback passed"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("I have created docs/README-copy.md"),
                    outcome.finalAnswer());
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.INVALID_MUTATION_ARGUMENTS));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void satisfiedWorkspaceOperationPostconditionsRecoverLaterDuplicateFailures() throws Exception {
        Path ws = Files.createTempDirectory("talos-workspace-operation-duplicate-recovery-");
        try {
            Files.createDirectories(ws.resolve("docs/notes"));
            Files.createDirectories(ws.resolve("scratch"));
            Files.writeString(ws.resolve("README.md"), "# Fixture\n");
            Files.writeString(ws.resolve("docs/notes/README-copy.md"), "# Fixture\n");
            Files.writeString(ws.resolve("docs/tasks.md"), "todo\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Organize these files using workspace operation tools only: copy README.md to "
                            + "docs/notes/README-copy.md, move scratch/todo.md to docs/todo.md, "
                            + "then rename docs/todo.md to tasks.md."));

            WorkspaceOperationPlan copyPlan = WorkspaceOperationPlan.copyPath(
                    "README.md",
                    "docs/notes/README-copy.md",
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                    false);
            WorkspaceOperationPlan movePlan = WorkspaceOperationPlan.movePath(
                    "scratch/todo.md",
                    "docs/todo.md",
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS);
            WorkspaceOperationPlan renamePlan = WorkspaceOperationPlan.batch(
                    WorkspaceOperationPlan.OperationKind.RENAME_PATH,
                    List.of(
                            WorkspaceOperationPlan.PathEffect.source(
                                    "docs/todo.md", true, WorkspaceOperationPlan.OperationKind.RENAME_PATH),
                            WorkspaceOperationPlan.PathEffect.destination(
                                    "docs/tasks.md", true, WorkspaceOperationPlan.OperationKind.RENAME_PATH)),
                    dev.talos.tools.ToolRiskLevel.WRITE,
                    true,
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                    false,
                    "Rename docs/todo.md to docs/tasks.md.",
                    "Rename: docs/todo.md -> docs/tasks.md");

            var loopResult = new ToolCallLoop.LoopResult(
                    "Organized the workspace.", 2, 6,
                    List.of(
                            "talos.copy_path", "talos.move_path", "talos.rename_path",
                            "talos.copy_path", "talos.move_path", "talos.rename_path"),
                    List.of(), 3, 0, false, 3, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            workspaceOutcome("talos.copy_path", "docs/notes/README-copy.md", true,
                                    "Copied README.md -> docs/notes/README-copy.md", "", "", copyPlan),
                            workspaceOutcome("talos.move_path", "docs/todo.md", true,
                                    "Moved scratch/todo.md -> docs/todo.md", "", "", movePlan),
                            workspaceOutcome("talos.rename_path", "docs/tasks.md", true,
                                    "Renamed docs/todo.md -> docs/tasks.md", "", "", renamePlan),
                            workspaceOutcome("talos.copy_path", "docs/notes/README-copy.md", false,
                                    "", "Destination already exists: docs/notes/README-copy.md.",
                                    ToolError.INVALID_PARAMS, copyPlan),
                            workspaceOutcome("talos.move_path", "docs/todo.md", false,
                                    "", "Source not found: scratch/todo.md",
                                    ToolError.NOT_FOUND, movePlan),
                            workspaceOutcome("talos.rename_path", "docs/tasks.md", false,
                                    "", "Source not found: docs/todo.md",
                                    ToolError.NOT_FOUND, renamePlan)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Organized the workspace.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertFalse(outcome.partialMutation());
            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertFalse(outcome.finalAnswer().startsWith(AssistantTurnExecutor.PARTIAL_MUTATION_ANNOTATION),
                    outcome.finalAnswer());
            assertEquals(MutationOutcomeStatus.SUCCEEDED, outcome.taskOutcome().mutationOutcome().status());
            assertEquals(0, outcome.taskOutcome().mutationOutcome().failed().size());
            assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
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
    void verifiedChangedFilesSummaryUsesWorkspaceOperationDestinationsWhenPathHintsAreSources() throws Exception {
        Path ws = Files.createTempDirectory("talos-workspace-operation-destination-summary-");
        try {
            Files.createDirectories(ws.resolve("archive"));
            Files.writeString(ws.resolve("notes.md"), "notes\n");
            Files.writeString(ws.resolve("archive/final-notes.md"), "notes\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Copy notes.md to notes-copy.md, move notes-copy.md to archive/notes-copy.md, "
                            + "then rename archive/notes-copy.md to final-notes.md."));

            WorkspaceOperationPlan copyPlan = WorkspaceOperationPlan.copyPath(
                    "notes.md",
                    "notes-copy.md",
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                    false);
            WorkspaceOperationPlan movePlan = WorkspaceOperationPlan.movePath(
                    "notes-copy.md",
                    "archive/notes-copy.md",
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS);
            WorkspaceOperationPlan renamePlan = WorkspaceOperationPlan.batch(
                    WorkspaceOperationPlan.OperationKind.RENAME_PATH,
                    List.of(
                            WorkspaceOperationPlan.PathEffect.source(
                                    "archive/notes-copy.md", true, WorkspaceOperationPlan.OperationKind.RENAME_PATH),
                            WorkspaceOperationPlan.PathEffect.destination(
                                    "archive/final-notes.md", true, WorkspaceOperationPlan.OperationKind.RENAME_PATH)),
                    dev.talos.tools.ToolRiskLevel.WRITE,
                    true,
                    WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                    false,
                    "Rename archive/notes-copy.md to archive/final-notes.md.",
                    "Rename: archive/notes-copy.md -> archive/final-notes.md");

            var loopResult = new ToolCallLoop.LoopResult(
                    "Done.", 1, 3,
                    List.of("talos.copy_path", "talos.move_path", "talos.rename_path"),
                    List.of(), 3, 0, false, 3, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            workspaceOutcome("talos.copy_path", "notes.md", true,
                                    "Copied notes.md -> notes-copy.md", "", "", copyPlan),
                            workspaceOutcome("talos.move_path", "notes-copy.md", true,
                                    "Moved notes-copy.md -> archive/notes-copy.md", "", "", movePlan),
                            workspaceOutcome("talos.rename_path", "archive/notes-copy.md", true,
                                    "Renamed archive/notes-copy.md -> archive/final-notes.md", "", "", renamePlan)
                    ));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Done.", messages, loopResult, ws, 0);

            assertTrue(outcome.finalAnswer().contains(
                            "Updated 3 files: notes-copy.md, archive/notes-copy.md, archive/final-notes.md."),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Updated 3 files: notes.md"),
                    outcome.finalAnswer());
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
    void postApplyGenericSourceDerivedSummaryIsCompletedUnverified() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-source-derived-unverified-");
        try {
            Files.createDirectories(ws.resolve("docs"));
            Files.writeString(ws.resolve("long-notes.txt"), """
                    Alice shipped the prototype.
                    Beta users asked for clearer onboarding.
                    Publish a short release note next.
                    """);
            Files.writeString(ws.resolve("docs/summary.md"), """
                    - Alice shipped the prototype.
                    - Beta users need clearer onboarding.
                    - Publish a short release note next.
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Summarize long-notes.txt into docs/summary.md. Keep it under 8 bullets."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Created docs/summary.md.", 2, 2,
                    List.of("talos.read_file", "talos.write_file"), List.of(),
                    0, 0, false, 1, List.of("long-notes.txt"),
                    0, 0, 0, 0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.read_file", "long-notes.txt", true, false, false,
                                    "read long-notes.txt", "", dev.talos.tools.VerificationStatus.UNKNOWN
                            ),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "docs/summary.md", true, true, false,
                                    "wrote docs/summary.md", "", dev.talos.tools.VerificationStatus.PASS
                            )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Created docs/summary.md.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
            assertEquals(TaskVerificationStatus.READBACK_ONLY, outcome.taskOutcome().verificationResult().status());
            assertTrue(outcome.finalAnswer().startsWith("[File write/readback passed."), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains(
                    "Task-specific verification did not satisfy the requested claim"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Source-derived coverage checks passed"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("[Static verification: passed"), outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void documentExtractionExactTextParserEvidenceDoesNotVerifyFinalAnswerExactness() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-document-extract-verified-");
        try {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Extract the exact text from report.pdf."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Extracted text from report.pdf.",
                    1,
                    1,
                    List.of("talos.read_file"),
                    List.of(),
                    0,
                    0,
                    false,
                    0,
                    List.of("report.pdf"),
                    0,
                    0,
                    0,
                    0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file",
                            "report.pdf",
                            true,
                            false,
                            false,
                            "Extracted document text from report.pdf (status: SUCCESS)",
                            "",
                            dev.talos.tools.VerificationStatus.UNKNOWN)));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Extracted text from report.pdf.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.verificationReport().authoritativeProofKinds()
                    .contains(ProofKind.PARSER_EXTRACTION.name()), outcome.verificationReport().toString());
            assertTrue(outcome.finalAnswer().contains("final-answer exactness was not verified"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("PDF text extraction may not match visual order"),
                    outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void documentSummaryParserExtractionDoesNotBecomeCompletedVerified() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-document-summary-unverified-");
        try {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Summarize report.pdf."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Report summary.",
                    1,
                    1,
                    List.of("talos.read_file"),
                    List.of(),
                    0,
                    0,
                    false,
                    0,
                    List.of("report.pdf"),
                    0,
                    0,
                    0,
                    0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file",
                            "report.pdf",
                            true,
                            false,
                            false,
                            "Extracted document text from report.pdf (status: SUCCESS)",
                            "",
                            dev.talos.tools.VerificationStatus.UNKNOWN)));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Report summary.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertFalse(outcome.finalAnswer().contains("[Static verification: passed"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("summary semantics were not verified"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("PDF text extraction may not match visual order"),
                    outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void postApplyScopedCssVerificationDoesNotOverclaimFullWebCoherence() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-scoped-css-verify-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="styles.css"></head>
                      <body><main class="hero"><button class="cta-button">Join</button></main></body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), """
                    body { margin: 0; font-family: system-ui, sans-serif; }
                    .hero { padding: 4rem; }
                    .cta-button { border: 0; padding: 1rem; }
                    """);
            Files.writeString(ws.resolve("scripts.js"), "console.log('existing interaction');\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Rewrite styles.css so index.html still works. Do not edit index.html. Do not edit scripts.js."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated styles.css.", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "styles.css", true, true, false,
                            "wrote styles.css", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Updated styles.css.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.PASSED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Static verification: passed - "
                    + "Scoped static web checks passed"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Contextual static-web finding outside this turn"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("HTML does not link JavaScript file: `scripts.js`"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Static web coherence checks passed"),
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
    void markdownDocumentAboutWebpageCompletesAsReadbackOnlyNotStaticWebFailure() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-markdown-webpage-doc-");
        try {
            Files.createDirectories(ws.resolve("docs"));
            Files.writeString(ws.resolve("index.html"), "<!doctype html><html><body></body></html>");
            Files.writeString(ws.resolve("styles.css"), "body { font-family: sans-serif; }");
            Files.writeString(ws.resolve("script.js"), "console.log('fixture');");
            Files.writeString(ws.resolve("docs/synthwave-webpage-plan.md"), """
                    # Synthwave Webpage Plan

                    - Use neon accent colors.
                    - Keep band tour dates easy to scan.
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create docs/synthwave-webpage-plan.md with a concise plan for a cool looking "
                            + "synthwave webpage for a band. Use a supported text format."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Created docs/synthwave-webpage-plan.md.", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "docs/synthwave-webpage-plan.md", true, true, false,
                            "wrote docs/synthwave-webpage-plan.md", "", dev.talos.tools.VerificationStatus.PASS
                    )));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    "Created docs/synthwave-webpage-plan.md.", messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
            assertFalse(outcome.finalAnswer().contains("Task incomplete"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Static verification failed"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("File write/readback passed"), outcome.finalAnswer());
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
    void verifiedStaticWebMultiFileSuccessListsEveryChangedTarget() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-multifile-success-summary-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <head>
                        <title>BMI Calculator</title>
                        <link rel="stylesheet" href="styles.css">
                      </head>
                      <body>
                        <main class="app">
                          <form id="bmi-form">
                            <label for="height">Height</label>
                            <input id="height" name="height" type="number">
                            <label for="weight">Weight</label>
                            <input id="weight" name="weight" type="number">
                            <button id="calculate" type="submit">Calculate BMI</button>
                            <output id="result"></output>
                          </form>
                        </main>
                        <script src="scripts.js"></script>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), """
                    body { font-family: system-ui, sans-serif; }
                    .app { max-width: 420px; margin: 2rem auto; }
                    """);
            Files.writeString(ws.resolve("scripts.js"), """
                    const form = document.getElementById('bmi-form');
                    const height = document.getElementById('height');
                    const weight = document.getElementById('weight');
                    const result = document.getElementById('result');
                    form.addEventListener('submit', event => {
                      event.preventDefault();
                      const meters = Number(height.value) / 100;
                      const bmi = Number(weight.value) / (meters * meters);
                      result.textContent = `BMI ${bmi.toFixed(1)}`;
                    });
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, "
                            + "and scripts.js. It should calculate BMI from height and weight."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html and styles.css.", 1, 3,
                    List.of("talos.write_file", "talos.write_file", "talos.write_file"), List.of(),
                    0, 0, false, 3, List.of(),
                    0, 0, 0, 0,
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

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.PASSED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().contains("Static verification: passed"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains(
                            "Updated 3 files: index.html, styles.css, scripts.js."),
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
    void partialStaticWebFailureDoesNotEmitVerifiedMultiFileSuccessSummary() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-partial-summary-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!DOCTYPE html>
                    <html>
                      <head><link rel="stylesheet" href="styles.css"></head>
                      <body>
                        <form id="bmi-form">
                          <input id="height" name="height">
                          <input id="weight" name="weight">
                          <button type="submit">Calculate BMI</button>
                          <output id="result"></output>
                        </form>
                        <script src="scripts.js"></script>
                      </body>
                    </html>
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a complete static BMI calculator in this folder with index.html, styles.css, "
                            + "and scripts.js. It should calculate BMI from height and weight."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Everything is complete.", 1, 2,
                    List.of("talos.write_file", "talos.write_file"), List.of(),
                    1, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "index.html", true, true, false,
                                    "wrote index.html", "", dev.talos.tools.VerificationStatus.PASS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "styles.css", false, true, false,
                                    "", "write failed before content was applied",
                                    null, ToolError.TOOL_ERROR)));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.PARTIAL, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Partial verification: static checks failed -"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Succeeded:\n- index.html: wrote index.html"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Failed:\n- styles.css: write failed before content was applied"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Updated 2 files:"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Updated 3 files:"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Everything is complete."), outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void partialReplacementFailureDoesNotKeepCompleteLabelWhenOneRequestedTargetIsUnchanged() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-partial-replacement-");
        try {
            Files.writeString(ws.resolve("notes.md"), "status=new\n");
            Files.writeString(ws.resolve("more.md"), "status2=old\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Use talos.edit_file twice. First replace status=old with status=new in notes.md. "
                            + "Then replace status2=old with status2=new in more.md."));

            var loopResult = new ToolCallLoop.LoopResult(
                    """
                            Updated 2 files: notes.md, more.md.
                            ✓ Updated notes.md (1 lines, 11 bytes)
                            ✓ Updated more.md (1 lines, 11 bytes)
                            """,
                    1,
                    2,
                    List.of("talos.edit_file", "talos.write_file"),
                    List.of(),
                    0,
                    0,
                    false,
                    2,
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.edit_file", "notes.md", true, true, false,
                                    "edited notes.md", "", dev.talos.tools.VerificationStatus.PASS),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.write_file", "more.md", true, true, false,
                                    "wrote more.md", "", dev.talos.tools.VerificationStatus.PASS)));

            LocalTurnTraceCapture.begin(
                    "trc-t869-partial-replacement",
                    "sid-t869",
                    1,
                    "2026-06-30T12:00:00Z",
                    "workspace-hash",
                    "agent",
                    "llama.cpp",
                    "gpt-oss:20b",
                    messages.getLast().content());
            ExecutionOutcome outcome;
            LocalTurnTrace trace;
            try {
                outcome = ExecutionOutcome.fromToolLoop(
                        loopResult.finalAnswer(), messages, loopResult, ws, 0);
                trace = LocalTurnTraceCapture.complete();
            } finally {
                LocalTurnTraceCapture.clear();
            }

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertEquals(MutationOutcomeStatus.SUCCEEDED, outcome.taskOutcome().mutationOutcome().status());
            assertNotNull(trace);
            assertNotNull(trace.outcome());
            assertEquals("FAILED", trace.outcome().status());
            assertEquals("FAILED", trace.outcome().verificationStatus());
            assertEquals("SUCCEEDED", trace.outcome().mutationStatus());
            assertEquals("FAILED", trace.outcome().classification());
            assertTrue(outcome.finalAnswer().contains("Static verification failed"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("more.md: replacement new text was not observed"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Updated 2 files:"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("✓ Updated more.md"), outcome.finalAnswer());
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
    void approvedProtectedReadRefusalIsRuntimeRepairedAndAdvisory() throws Exception {
        Path ws = Files.createTempDirectory("talos-approved-protected-read-postcondition-");
        try {
            Files.writeString(ws.resolve(".env"), "SECRET=manual-test\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and tell me what it says."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "I'm sorry, but I can't provide that.",
                    1,
                    1,
                    List.of("talos.read_file"),
                    List.of(),
                    1,
                    0,
                    false,
                    0,
                    List.of(".env"),
                    0,
                    0,
                    0,
                    0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file", ".env", true, false, false,
                            "1 | SECRET=manual-test", "")));

            LocalTurnTraceCapture.begin(
                    "trc-approved-protected-read-postcondition",
                    "sid",
                    1,
                    "2026-05-05T12:00:00Z",
                    "workspace-hash",
                    "auto",
                    "test",
                    "model",
                    "Read .env and tell me what it says.");
            try {
                ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                        loopResult.finalAnswer(), messages, loopResult, ws, 0);

                LocalTurnTrace trace = LocalTurnTraceCapture.complete();

                assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
                assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
                assertTrue(outcome.finalAnswer().contains("SECRET=manual-test"), outcome.finalAnswer());
                assertFalse(outcome.finalAnswer().contains("can't provide"), outcome.finalAnswer());
                assertFalse(outcome.finalAnswer().toLowerCase(java.util.Locale.ROOT).contains("complete"),
                        outcome.finalAnswer());
                assertTrue(outcome.taskOutcome().hasWarning(
                        TruthWarningType.APPROVED_PROTECTED_READ_POSTCONDITION));
                assertNotNull(trace);
                assertEquals("ADVISORY_ONLY", trace.outcome().classification());
                assertTrue(trace.warnings().stream().anyMatch(warning ->
                        "APPROVED_PROTECTED_READ_POSTCONDITION".equals(warning.code())));
                assertTrue(trace.events().stream().anyMatch(event ->
                        "PROTECTED_READ_POSTCONDITION_CHECKED".equals(event.type())));
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
    void failedProtectedPathVariantThenApprovedReadSatisfiesPostcondition() throws Exception {
        Path ws = Files.createTempDirectory("talos-protected-read-path-variant-");
        try {
            Files.writeString(ws.resolve(".env"), "SAFE_AUDIT_SECRET=fake\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Read .env and tell me what it says."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "The .env file contains SAFE_AUDIT_SECRET=fake.",
                    2,
                    2,
                    List.of("talos.read_file", "talos.read_file"),
                    List.of(),
                    2,
                    1,
                    false,
                    0,
                    List.of(".env"),
                    0,
                    0,
                    0,
                    0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.read_file", " .env", false, false, false,
                                    "", "File not found:  .env", null, ToolError.NOT_FOUND),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.read_file", ".env", true, false, false,
                                    "1 | SAFE_AUDIT_SECRET=fake", "")));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.finalAnswer().contains("SAFE_AUDIT_SECRET=fake"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().startsWith("[Protected read incomplete:"),
                    outcome.finalAnswer());
            assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
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
    void toolLoopReadTargetNotFoundDoesNotCountAsGatheredEvidence() {
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

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        assertTrue(outcome.finalAnswer().startsWith("[Evidence incomplete:"));
    }

    @Test
    void verificationRequiredReadOnlyWithEvidenceButNoPostApplyVerifierIsReadOnlyAnswered() {
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

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertFalse(outcome.finalAnswer().startsWith("[Task not verified:"), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("task verifier ran"), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("The BMI page appears to be working."), outcome.finalAnswer());
        assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        assertFalse(outcome.finalAnswer().startsWith("[Evidence incomplete:"));
    }

    @Test
    void verificationRequiredReadOnlyWithMissingEvidenceStillReportsIncompleteEvidence() {
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
        assertFalse(outcome.finalAnswer().contains("[Task not verified:"), outcome.finalAnswer());
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
                        "", "User did not approve the talos.read_file call.", null, ToolError.DENIED)
                        .withFailureReason(ToolFailureReason.USER_APPROVAL_DENIED)));

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
    void pathExistenceAnswerPrependsExactStatusWhenListDirEvidenceIsSatisfied() throws Exception {
        Path ws = Files.createTempDirectory("talos-path-existence-summary-");
        try {
            Files.writeString(ws.resolve("scripts.js"), "console.log('present');\n");
            Files.writeString(ws.resolve("styles.css"), "body { color: red; }\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Check whether scripts.js exists and whether script.js exists. Do not change anything."));

            var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                    dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                    dev.talos.runtime.phase.ExecutionPhase.INSPECT,
                    List.of("talos.list_dir", "talos.read_file"),
                    List.of("talos.list_dir", "talos.read_file"),
                    List.of());

            var loopResult = new ToolCallLoop.LoopResult(
                    "I checked the files.",
                    1,
                    1,
                    List.of("talos.list_dir"),
                    List.of(),
                    0,
                    0,
                    false,
                    0,
                    List.of(),
                    0,
                    0,
                    0,
                    0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.list_dir", ".", true, false, false,
                            "scripts.js\nstyles.css\n", "")));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), plan, messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Path existence verified]"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("scripts.js: exists"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("script.js: not found"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().startsWith("[Evidence incomplete:"), outcome.finalAnswer());
            assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void pathExistenceAnswerWithDirectNotFoundEvidencePrependsDeterministicNegativeStatus() throws Exception {
        Path ws = Files.createTempDirectory("talos-path-existence-not-found-");
        try {
            Files.createDirectories(ws.resolve("docs"));

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user("Check whether docs/target.md exists. Do not change anything."));

            var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                    dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                    dev.talos.runtime.phase.ExecutionPhase.INSPECT,
                    List.of("talos.list_dir", "talos.read_file"),
                    List.of("talos.list_dir", "talos.read_file"),
                    List.of());

            var loopResult = new ToolCallLoop.LoopResult(
                    "docs/target.md does not exist.",
                    1,
                    1,
                    List.of("talos.read_file"),
                    List.of(),
                    1,
                    0,
                    false,
                    0,
                    List.of("docs/target.md"),
                    0,
                    0,
                    0,
                    0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file", "docs/target.md", false, false, false,
                            "", "docs/target.md was not found.", null, ToolError.NOT_FOUND)));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), plan, messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
            assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.finalAnswer().startsWith("[Path existence verified]"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("docs/target.md: not found"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().startsWith("[Evidence incomplete:"), outcome.finalAnswer());
            assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void pathExistenceAnswerWithOnlyIrrelevantReadEvidenceRemainsContained() throws Exception {
        Path ws = Files.createTempDirectory("talos-path-existence-irrelevant-read-");
        try {
            Files.writeString(ws.resolve("scripts.js"), "console.log('present');\n");
            Files.writeString(ws.resolve("styles.css"), "body { color: red; }\n");

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Check whether scripts.js exists and whether script.js exists. Do not change anything."));

            var plan = dev.talos.runtime.turn.CurrentTurnPlan.create(
                    dev.talos.runtime.task.TaskContractResolver.fromMessages(messages),
                    dev.talos.runtime.phase.ExecutionPhase.INSPECT,
                    List.of("talos.list_dir", "talos.read_file"),
                    List.of("talos.list_dir", "talos.read_file"),
                    List.of());

            var loopResult = new ToolCallLoop.LoopResult(
                    "scripts.js does not exist.",
                    1,
                    1,
                    List.of("talos.read_file"),
                    List.of(),
                    1,
                    0,
                    false,
                    0,
                    List.of("styles.css"),
                    0,
                    0,
                    0,
                    0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file", "styles.css", true, false, false,
                            "body { color: red; }", "")));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), plan, messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertTrue(outcome.finalAnswer().startsWith(
                    "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"),
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("scripts.js does not exist"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("scripts.js: exists"), outcome.finalAnswer());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
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
    void staticWebDiagnosisWithLinkedScriptButOnlyIndexReadIsEvidenceIncomplete() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-web-linked-script-missing-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <body>
                        <button id="run-button">Run</button>
                        <script src="script.js"></script>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('.missing-button').addEventListener('click', () => {
                      document.body.dataset.clicked = 'true';
                    });
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Will the current static web page button work in a browser? "
                            + "Inspect the files and do not change anything."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "The button markup is present, but script.js still needs inspection before I can say whether it works.",
                    1, 1,
                    List.of("talos.read_file"), List.of(),
                    0, 0, false, 0, List.of("index.html"),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file", "index.html", true, false, false,
                            "read index", "")));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
            assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
            assertTrue(outcome.finalAnswer().startsWith(
                    "[Evidence incomplete: required workspace evidence was not gathered in this turn.]"));
            assertFalse(outcome.finalAnswer().contains("button markup is present"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("script.js"), outcome.finalAnswer());
            assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void staticWebDiagnosisWithLinkedScriptReadCanComplete() throws Exception {
        Path ws = Files.createTempDirectory("talos-static-web-linked-script-read-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <body>
                        <button id="run-button">Run</button>
                        <script src="script.js"></script>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("script.js"), """
                    document.querySelector('#run-button').addEventListener('click', () => {
                      document.body.dataset.clicked = 'true';
                    });
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Will the current static web page button work in a browser? "
                            + "Inspect the files and do not change anything."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "index.html defines the button and script.js attaches the click listener to #run-button.",
                    2, 2,
                    List.of("talos.read_file", "talos.read_file"), List.of(),
                    0, 0, false, 0, List.of("index.html", "script.js"),
                    0, 0, 0, 0,
                    List.of(
                            new ToolCallLoop.ToolOutcome(
                                    "talos.read_file", "index.html", true, false, false,
                                    "read index", ""),
                            new ToolCallLoop.ToolOutcome(
                                    "talos.read_file", "script.js", true, false, false,
                                    "read script", "")));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertFalse(outcome.finalAnswer().startsWith("[Evidence incomplete:"), outcome.finalAnswer());
            assertFalse(outcome.taskOutcome().hasWarning(TruthWarningType.MISSING_EVIDENCE));
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
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

    @Test
    void staticWebCoherenceDoesNotVerifyRequestedButtonStatusInteractionNoOp() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-t623-interaction-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="styles.css"></head>
                      <body>
                        <button id="teaser-button">Show teaser</button>
                        <p id="teaser-status">Waiting.</p>
                        <script src="scripts.js"></script>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), "button { font: inherit; }\n");
            Files.writeString(ws.resolve("scripts.js"), """
                    document.getElementById('teaser-button').addEventListener('click', function() {
                      document.getElementById('teaser-status').textC;
                    });
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Update scripts.js so #teaser-button updates #teaser-status when clicked."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated scripts.js.", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    0, 0, false, 1, List.of(),
                    0, 0, 0, 0,
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.write_file", "scripts.js", true, true, false,
                            "wrote scripts.js", "", dev.talos.tools.VerificationStatus.PASS)));

            LocalTurnTraceCapture.begin(
                    "trc-t624-unsatisfied-interaction",
                    "sid-t624",
                    1,
                    "2026-06-01T00:00:00Z",
                    "workspace-hash",
                    "auto",
                    "test",
                    "model",
                    "Update scripts.js so #teaser-button updates #teaser-status when clicked.");
            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals(ExecutionOutcome.CompletionStatus.FAILED, outcome.completionStatus());
            assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
            assertNotNull(outcome.verificationReport());
            assertEquals(1, outcome.verificationReport().requiredClaimCount());
            assertEquals(1, outcome.verificationReport().unsatisfiedRequiredClaimCount());
            assertTrue(outcome.verificationReport().problems().stream()
                    .anyMatch(line -> line.contains("did not change")), outcome.verificationReport().problems().toString());
            assertTrue(outcome.verificationReport().limitations().stream()
                    .anyMatch(line -> line.contains("does not assign visible text")), outcome.verificationReport().limitations().toString());
            assertFalse(outcome.finalAnswer().contains("Static verification: passed"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("No task-specific verifier was applicable"),
                    outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Static verification failed"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("did not change"), outcome.finalAnswer());
            assertNotNull(trace);
            assertEquals(1, trace.verification().requiredClaimCount());
            assertEquals(1, trace.verification().unsatisfiedRequiredClaimCount());
            assertTrue(trace.verification().problems().stream()
                    .anyMatch(line -> line.contains("did not change")), trace.verification().problems().toString());
            assertTrue(trace.verification().limitations().stream()
                    .anyMatch(line -> line.contains("does not assign visible text")), trace.verification().limitations().toString());
        } finally {
            LocalTurnTraceCapture.clear();
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void approvedPrivateDocumentContainmentPromptRepairsBlockedHistoryAnswer() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-private-doc-approved-");
        try {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Read medical-notes.docx and tell me whether it contains a patient name. Do not print the name."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "[Approval blocked: protected content was redacted from history]",
                    1,
                    1,
                    List.of("talos.read_file"),
                    List.of(),
                    0,
                    0,
                    false,
                    0,
                    List.of("medical-notes.docx"),
                    0,
                    0,
                    0,
                    0,
                    dev.talos.runtime.failure.FailureDecision.continueLoop(),
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file",
                            "medical-notes.docx",
                            true,
                            false,
                            false,
                            "Extracted document text from medical-notes.docx (status: SUCCESS)",
                            "",
                            dev.talos.tools.VerificationStatus.UNKNOWN)),
                    Map.of("medical-notes.docx", """
                            Extracted document text from medical-notes.docx (status: SUCCESS)
                            Warning: DOCX extraction is text-oriented; layout, comments, tracked changes, and embedded objects may be partial or omitted.
                            Extractor: poi-docx 5.5.1

                            Patient name: [redacted-private-document-canary]
                            """));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.VerificationStatus.READBACK_ONLY, outcome.verificationStatus());
            assertEquals("Yes. The document contains a patient name, but the name is not printed.",
                    outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Eleni Nikolaou"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("[redacted-private-document-canary]"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Approval blocked"), outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void deniedPrivateDocumentContainmentPromptKeepsWithheldNotice() throws Exception {
        Path ws = Files.createTempDirectory("talos-execution-outcome-private-doc-denied-");
        try {
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Read medical-notes.docx and tell me whether it contains a patient name. Do not print the name."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Private document content was read locally but withheld from model context by privacy policy.",
                    1,
                    1,
                    List.of("talos.read_file"),
                    List.of(),
                    0,
                    0,
                    false,
                    0,
                    List.of("medical-notes.docx"),
                    0,
                    0,
                    0,
                    0,
                    dev.talos.runtime.failure.FailureDecision.continueLoop(),
                    List.of(new ToolCallLoop.ToolOutcome(
                            "talos.read_file",
                            "medical-notes.docx",
                            true,
                            false,
                            false,
                            "Private document content was read locally but withheld from model context by privacy policy.",
                            "",
                            dev.talos.tools.VerificationStatus.UNKNOWN)),
                    Map.of("medical-notes.docx",
                            "Private document content was read locally but withheld from model context by privacy policy."));

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertTrue(outcome.finalAnswer().contains("withheld from model context"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Yes. The document contains"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("Eleni Nikolaou"), outcome.finalAnswer());
            assertFalse(outcome.finalAnswer().contains("[redacted-private-document-canary]"), outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void passedStaticWebVerificationSurfacesRemoteAssetLimitationInFinalAnswer() throws Exception {
        Path ws = Files.createTempDirectory("talos-t640-remote-asset-limitation-");
        try {
            Files.writeString(ws.resolve("index.html"), """
                    <!doctype html>
                    <html>
                      <head><link rel="stylesheet" href="styles.css"></head>
                      <body>
                        <button id="teaser-button">Show teaser</button>
                        <p id="teaser-status">Waiting.</p>
                        <script src="scripts.js"></script>
                      </body>
                    </html>
                    """);
            Files.writeString(ws.resolve("styles.css"), """
                    body {
                      background: #050010 url("https://images.example.test/neon-stage.jpg") center / cover no-repeat;
                    }
                    """);
            Files.writeString(ws.resolve("scripts.js"), """
                    document.getElementById('teaser-button').addEventListener('click', function() {
                      document.getElementById('teaser-status').textContent = 'Teaser ready';
                    });
                    """);

            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.system("sys"));
            messages.add(ChatMessage.user(
                    "Create a synthwave website with a button with id teaser-button "
                            + "that updates visible text in #teaser-status when clicked."));

            var loopResult = new ToolCallLoop.LoopResult(
                    "Updated index.html, styles.css, and scripts.js.", 1, 1,
                    List.of("talos.write_file"), List.of(),
                    0, 0, false, 3, List.of(),
                    0, 0, 0, 0,
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

            ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                    loopResult.finalAnswer(), messages, loopResult, ws, 0);

            assertEquals(ExecutionOutcome.VerificationStatus.PASSED, outcome.verificationStatus());
            assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus());
            assertNotNull(outcome.verificationReport());
            assertTrue(outcome.verificationReport().limitations().stream()
                            .anyMatch(line -> line.contains("Remote static-web asset references were not fetched")),
                    outcome.verificationReport().limitations().toString());
            assertTrue(outcome.finalAnswer().contains("Static verification limitations"), outcome.finalAnswer());
            assertTrue(outcome.finalAnswer().contains("Remote static-web asset references were not fetched"),
                    outcome.finalAnswer());
        } finally {
            try (var walk = Files.walk(ws)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void embeddedStaticVerificationPassMarkerCannotSelfCertifyWhenPostApplyVerificationSkipped() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Update README.md with the new note."));

        var loopResult = new ToolCallLoop.LoopResult(
                "[Static verification: passed - README.md was verified.]\n\nUpdated README.md.",
                1,
                1,
                List.of("talos.write_file"),
                List.of(),
                0,
                0,
                false,
                1,
                List.of(),
                0,
                0,
                0,
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.write_file", "README.md", true, true, false,
                        "wrote README.md", "", dev.talos.tools.VerificationStatus.PASS)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.VerificationStatus.NOT_RUN, outcome.verificationStatus());
        assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
        assertFalse(outcome.finalAnswer().contains("[Static verification: passed"), outcome.finalAnswer());
        assertNotNull(outcome.verificationReport());
        assertEquals(0, outcome.verificationReport().requiredClaimCount());
        assertEquals(0, outcome.verificationReport().unsatisfiedRequiredClaimCount());
    }

    @Test
    void embeddedStaticVerificationFailureIsNegativeOnlyAndNotAuthoritativeReportEvidence() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Update README.md with the new note."));

        var loopResult = new ToolCallLoop.LoopResult("""
                [Task incomplete: Static verification failed - README.md was not updated.]

                Unresolved static verification problems:
                - README.md did not contain the requested note.
                """,
                1,
                1,
                List.of("talos.write_file"),
                List.of(),
                0,
                0,
                false,
                1,
                List.of(),
                0,
                0,
                0,
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.write_file", "README.md", true, true, false,
                        "wrote README.md", "", dev.talos.tools.VerificationStatus.PASS)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.VerificationStatus.FAILED, outcome.verificationStatus());
        assertEquals(TaskCompletionStatus.FAILED, outcome.taskOutcome().completionStatus());
        assertNotNull(outcome.verificationReport());
        assertEquals(0, outcome.verificationReport().requiredClaimCount());
        assertTrue(outcome.verificationReport().limitations().stream()
                .anyMatch(line -> line.toLowerCase().contains("embedded assistant-authored")),
                outcome.verificationReport().limitations().toString());
    }

    private static ToolCallLoop.ToolOutcome workspaceOutcome(
            String toolName,
            String pathHint,
            boolean success,
            String summary,
            String errorMessage,
            String errorCode,
            WorkspaceOperationPlan plan
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName,
                pathHint,
                success,
                true,
                false,
                summary,
                errorMessage,
                null,
                errorCode,
                plan);
    }
}
