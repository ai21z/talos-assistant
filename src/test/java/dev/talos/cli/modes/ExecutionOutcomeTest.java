package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.MutationOutcomeStatus;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarningType;
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
                        "", "approval denied"
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
    void unsupportedDocumentReadRemovesEmptyContentClaims() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize the documents in this workspace."));

        var loopResult = new ToolCallLoop.LoopResult(
                "notes.txt: Project notes.\n"
                        + "sample.pdf and sample.xlsx: Do not contain any extractable text.\n"
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
        assertTrue(outcome.finalAnswer().contains("notes.txt: Project notes."));
        assertFalse(outcome.finalAnswer().contains("Do not contain any extractable text"));
        assertFalse(outcome.finalAnswer().contains("These files are empty"));
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.UNSUPPORTED_DOCUMENT_CAPABILITY_NOTE));
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
            assertTrue(outcome.finalAnswer().contains("[ok] Created index.html"));
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
        assertTrue(outcome.finalAnswer().startsWith(AssistantTurnExecutor.UNGROUNDED_ANNOTATION));
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.STREAMING_NO_TOOL_UNGROUNDED));
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
        assertTrue(outcome.finalAnswer().startsWith("[Capability correction:"),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("don't have direct access"));
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(
                TruthWarningType.NO_TOOL_LOCAL_ACCESS_CAPABILITY_CORRECTED));
    }

    @Test
    void streamingNoToolUnsupportedBinaryDocumentLimitationIsNotCorrected() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Summarize the documents in this workspace."));

        String limitation = "Talos cannot extract PDF contents with the current local text-tool surface.";

        ExecutionOutcome outcome = ExecutionOutcome.fromNoTool(limitation, messages, null, true);

        assertEquals(limitation, outcome.finalAnswer());
        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
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
}
