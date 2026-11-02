package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.verification.TaskVerificationStatus;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verified completion needs proof bound to the request (MICA invariant I3, decision D5, P4). The verifier, the
 * outcome owner and the workspace are real. The tool loop result is supplied.
 */
class ProofScopedCompletionTest {

    @Test
    void aCoherenceOnlyPassForAnUnboundRequestEndsAsChecksPassedWithoutVerifiedCompletion(@TempDir Path ws)
            throws Exception {
        site(ws);

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("Updated index.html.",
                messages("Now edit index.html so it looks like a modern landing page."),
                loop("Updated index.html.", edit("talos.edit_file", "index.html")), ws, 0);

        assertEquals(ExecutionOutcome.VerificationStatus.PASSED, outcome.verificationStatus());
        assertEquals(TaskVerificationStatus.PASSED, outcome.taskOutcome().verificationResult().status());
        assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Checks passed - Static web coherence checks passed"),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("[Static verification: passed"), outcome.finalAnswer());
    }

    @Test
    void anyApprovedCommandOnAChangeTurnIsNotProofOfTheChange(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("notes.txt"), "draft\n");

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("Edited notes.txt and built it.",
                messages("Edit notes.txt to say final."),
                loop("Edited notes.txt and built it.", edit("talos.edit_file", "notes.txt"), command("gradle_build")),
                ws, 0);

        assertNotEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus(),
                outcome.finalAnswer());
    }

    @Test
    void aPassingTestCommandAfterAChangeIsAChecksPass(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("notes.txt"), "draft\n");

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("Edited notes.txt and the tests pass.",
                messages("Edit notes.txt to say final."),
                loop("Edited notes.txt and the tests pass.", edit("talos.edit_file", "notes.txt"),
                        command("gradle_test")),
                ws, 0);

        assertEquals(ExecutionOutcome.VerificationStatus.PASSED, outcome.verificationStatus());
        assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().startsWith("[Checks passed - Command checks passed: gradle_test exited 0."),
                outcome.finalAnswer());
    }

    @Test
    void aVerifyOnlyRequestIsStillVerifiedByItsCommand() {
        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("All tests passed.",
                messages("Verify that the Gradle tests pass."),
                loop("All tests passed.", command("gradle_test")), null, 0);

        assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus(),
                outcome.finalAnswer());
    }

    @Test
    void anExactContentRequestStillEndsVerified(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("todo.md"), "Review the local setup.");

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop("Created todo.md.",
                messages("Create todo.md containing exactly: Review the local setup. Do not change any other file."),
                loop("Created todo.md.", edit("talos.write_file", "todo.md")), ws, 0);

        assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.finalAnswer().contains("[Static verification: passed - Exact content verification passed"),
                outcome.finalAnswer());
    }

    private static List<ChatMessage> messages(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ToolCallLoop.ToolOutcome edit(String tool, String path) {
        return new ToolCallLoop.ToolOutcome(tool, path, true, true, false,
                "changed " + path, "", dev.talos.tools.VerificationStatus.PASS);
    }

    private static ToolCallLoop.ToolOutcome command(String profile) {
        return new ToolCallLoop.ToolOutcome("talos.run_command", "", true, false, false,
                "Command succeeded: " + profile + " exited with code 0 after 31ms", "", null, "");
    }

    private static ToolCallLoop.LoopResult loop(String answer, ToolCallLoop.ToolOutcome... outcomes) {
        long mutations = java.util.Arrays.stream(outcomes).filter(ToolCallLoop.ToolOutcome::mutating).count();
        return new ToolCallLoop.LoopResult(answer, 1, outcomes.length,
                java.util.Arrays.stream(outcomes).map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(),
                0, 0, false, (int) mutations, List.of(),
                0, 0, 0, 0,
                List.of(outcomes));
    }

    private static void site(Path ws) throws Exception {
        Files.writeString(ws.resolve("index.html"), """
                <!DOCTYPE html>
                <html>
                  <head><link rel="stylesheet" href="style.css"></head>
                  <body><main id="hero"><a class="cta-button">Listen</a></main><script src="script.js"></script></body>
                </html>
                """);
        Files.writeString(ws.resolve("style.css"), "#hero {}\n.cta-button {}\n");
        Files.writeString(ws.resolve("script.js"), "document.querySelector('.cta-button');");
    }
}
