package dev.talos.cli.approval;

import dev.talos.runtime.ApprovalResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CliApprovalGate}: interactive user approval via stdin
 * and JLine-integrated line reader.
 */
class CliApprovalGateTest {

    // ── Legacy Scanner-based tests (InputStream constructor) ────────────

    @Nested
    class ScannerBased {

        @Test
        void approvesOnY() {
            var gate = gateWith("y\n");
            assertTrue(gate.approve("write file", "path/to/file"));
        }

        @Test
        void approvesOnYes() {
            var gate = gateWith("yes\n");
            assertTrue(gate.approve("write file", null));
        }

        @Test
        void approvesOnYesCaseInsensitive() {
            var gate = gateWith("YES\n");
            assertTrue(gate.approve("write file", null));
        }

        @Test
        void approvesOnYWithWhitespace() {
            var gate = gateWith("  y  \n");
            assertTrue(gate.approve("write file", null));
        }

        @Test
        void deniesOnN() {
            var gate = gateWith("n\n");
            assertFalse(gate.approve("delete file", null));
        }

        @Test
        void deniesOnNo() {
            var gate = gateWith("no\n");
            assertFalse(gate.approve("delete file", null));
        }

        @Test
        void deniesOnEmptyLine() {
            var gate = gateWith("\n");
            assertFalse(gate.approve("delete file", null));
        }

        @Test
        void deniesOnArbitraryInput() {
            var gate = gateWith("maybe\n");
            assertFalse(gate.approve("operation", null));
        }

        @Test
        void deniesOnEOF() {
            var gate = gateWith("");
            assertFalse(gate.approve("operation", null));
        }

        @Test
        void outputIncludesDescription() {
            var bout = new ByteArrayOutputStream();
            var gate = new CliApprovalGate(
                    new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(bout));

            gate.approve("write to database", null);

            String output = bout.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("write to database"),
                    "Output should include the operation description");
            assertTrue(output.contains("Action"),
                    "Output should label the action");
            assertTrue(output.contains("Risk"),
                    "Output should label the inferred risk");
            assertTrue(output.contains("approve once"),
                    "Output should show choices");
            assertTrue(output.contains("Allow?"),
                    "Output should include the approval prompt");
            assertTrue(output.contains("approval required"),
                    "Output should use the semantic approval trust window");
        }

        @Test
        void approveOnceDoesNotOfferOrAcceptSessionRemember() {
            var bout = new ByteArrayOutputStream();
            var gate = new CliApprovalGate(
                    new ByteArrayInputStream("a\n".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(bout));

            ApprovalResponse response = gate.approveOnce("private document model handoff", "target: report.docx");

            assertEquals(ApprovalResponse.DENIED, response);
            String output = bout.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("approve this turn"), output);
            assertFalse(output.contains("approve for session"), output);
        }

        @Test
        void outputIncludesDetail() {
            var bout = new ByteArrayOutputStream();
            var gate = new CliApprovalGate(
                    new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(bout));

            gate.approve("write file", "target: src/main/Main.java");

            String output = bout.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("src/main/Main.java"),
                    "Output should include the detail");
            assertTrue(output.contains("target: src/main/Main.java"),
                    "Output should render detail lines");
        }

        @Test
        void outputUsesAsciiWarningMarker() {
            var bout = new ByteArrayOutputStream();
            var gate = new CliApprovalGate(
                    new ByteArrayInputStream("n\n".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(bout));

            gate.approve("write file", "target: src/main/Main.java");

            String output = bout.toString(StandardCharsets.UTF_8);
            assertTrue(output.toLowerCase(java.util.Locale.ROOT).contains("approval required"));
            assertFalse(output.contains("⚠"));
        }

        @Test
        void labelsProtectedReadAsSensitiveRead() {
            var out = new ByteArrayOutputStream();
            var gate = new CliApprovalGate(
                    new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(out, true, StandardCharsets.UTF_8));

            gate.approveFull(
                    "protected read: talos.read_file",
                    "permission: Permission policy requires approval before reading protected path `.env`.\n"
                            + "    target: .env");

            String text = out.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("Action  protected read: talos.read_file"), text);
            assertTrue(text.contains("Risk    sensitive read"), text);
            assertFalse(text.contains("Risk    write"), text);
        }

        @Test
        void handlesNullDescription() {
            var gate = gateWith("y\n");
            assertTrue(gate.approve(null, null));
        }

        @Test
        void diffBlockContentDoesNotEscalateRiskLabel() {
            // T756: the diff body quotes arbitrary file content; "remove" or
            // "delete" inside the user's own code must not flip the label.
            var out = new ByteArrayOutputStream();
            var gate = new CliApprovalGate(
                    new ByteArrayInputStream("\n".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(out, true, StandardCharsets.UTF_8));

            gate.approveFull(
                    "write operation: talos.write_file",
                    "target: app.js (40 bytes, 2 lines)\n"
                            + "    diff (+1 -1):\n"
                            + "    @@ -1 +1 @@\n"
                            + "    -button.removeEventListener('click', deleteItem);\n"
                            + "    +button.addEventListener('click', addItem);");

            String text = out.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("Risk    write"), text);
            assertFalse(text.contains("Risk    destructive"), text);
        }

        private static CliApprovalGate gateWith(String userInput) {
            return new CliApprovalGate(
                    new ByteArrayInputStream(userInput.getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(new ByteArrayOutputStream()));
        }
    }

    // ── Function-based tests (JLine-integrated constructor) ─────────────

    @Nested
    class FunctionBased {

        @Test
        void approvesViaFunction() {
            var gate = functionGate("y");
            assertTrue(gate.approve("write file", null));
        }

        @Test
        void deniesViaFunction() {
            var gate = functionGate("n");
            assertFalse(gate.approve("write file", null));
        }

        @Test
        void deniesOnNullReturn() {
            // Simulates EOF from JLine
            var gate = new CliApprovalGate(prompt -> null,
                    new PrintStream(new ByteArrayOutputStream()), null);
            assertFalse(gate.approve("operation", null));
        }

        @Test
        void deniesOnException() {
            // Simulates JLine EndOfFileException
            var gate = new CliApprovalGate(prompt -> { throw new RuntimeException("EOF"); },
                    new PrintStream(new ByteArrayOutputStream()), null);
            assertFalse(gate.approve("operation", null));
        }

        @Test
        void promptPassedToFunction() {
            var capturedPrompt = new String[1];
            Function<String, String> reader = prompt -> {
                capturedPrompt[0] = prompt;
                return "n";
            };
            var gate = new CliApprovalGate(reader,
                    new PrintStream(new ByteArrayOutputStream()), null);
            gate.approve("write file", null);

            assertNotNull(capturedPrompt[0]);
            assertTrue(capturedPrompt[0].contains("Allow?"),
                    "Prompt passed to function should contain 'Allow?'");
        }

        @Test
        void approvalWindowWithoutTerminalRendersAtHistoricalWidth80() {
            // T773 characterization: no-terminal paths keep the fixed
            // pre-T773 window width byte-for-byte.
            var bout = new ByteArrayOutputStream();
            var gate = new CliApprovalGate(prompt -> "n",
                    new PrintStream(bout, true, StandardCharsets.UTF_8), null);

            gate.approveFull("write file", "target: src/main/Main.java");

            String output = bout.toString(StandardCharsets.UTF_8);
            String oracle = new dev.talos.cli.ui.ApprovalPromptRenderer(
                    dev.talos.cli.ui.CliTheme.current(), 80)
                    .render("write file", "target: src/main/Main.java", "write");
            assertTrue(output.contains(oracle),
                    "no-terminal approval window must render the exact width-80 bytes\n" + output);
        }

        @Test
        void approvalWindowFollowsTheLiveTerminalWidth() {
            var bout = new ByteArrayOutputStream();
            var gate = new CliApprovalGate(prompt -> "n",
                    new PrintStream(bout, true, StandardCharsets.UTF_8), null, () -> 70);

            gate.approveFull("write file", "target: src/main/Main.java");

            String output = bout.toString(StandardCharsets.UTF_8);
            String oracle = new dev.talos.cli.ui.ApprovalPromptRenderer(
                    dev.talos.cli.ui.CliTheme.current(), 70)
                    .render("write file", "target: src/main/Main.java", "write");
            assertTrue(output.contains(oracle),
                    "live width 70 must drive the approval window\n" + output);
        }

        @Test
        void sessionPromptBytesAreTheEvidenceChainContract() {
            // T765 characterization: this exact string is matched by the PTY
            // manual-audit validator, the talosbench forbidden-substring bank,
            // and the scripted harness. It is byte-frozen — any change must go
            // through ApprovalPromptText and the e2e contract test.
            var capturedPrompt = new String[1];
            var gate = new CliApprovalGate(prompt -> {
                capturedPrompt[0] = prompt;
                return "n";
            }, new PrintStream(new ByteArrayOutputStream()), null);

            gate.approveFull("write file", null);

            assertEquals("  Allow? [y=yes, a=yes for session, N=no] ", capturedPrompt[0]);
        }

        @Test
        void oncePromptBytesAreTheEvidenceChainContract() {
            // T765 characterization: byte-frozen once-only form (see above).
            var capturedPrompt = new String[1];
            var gate = new CliApprovalGate(prompt -> {
                capturedPrompt[0] = prompt;
                return "n";
            }, new PrintStream(new ByteArrayOutputStream()), null);

            gate.approveOnce("private document model handoff", null);

            assertEquals("  Allow? [y=yes, N=no] ", capturedPrompt[0]);
        }

        @Test
        void approveOncePromptPassedToFunctionHasNoSessionChoice() {
            var capturedPrompt = new String[1];
            Function<String, String> reader = prompt -> {
                capturedPrompt[0] = prompt;
                return "a";
            };
            var gate = new CliApprovalGate(reader,
                    new PrintStream(new ByteArrayOutputStream()), null);

            ApprovalResponse response = gate.approveOnce("private document model handoff", null);

            assertEquals(ApprovalResponse.DENIED, response);
            assertNotNull(capturedPrompt[0]);
            assertTrue(capturedPrompt[0].contains("Allow?"));
            assertFalse(capturedPrompt[0].contains("session"), capturedPrompt[0]);
        }

        @Test
        void multipleApprovalsUseFunction() {
            Queue<String> responses = new ArrayDeque<>();
            responses.add("y");
            responses.add("n");
            responses.add("yes");

            var gate = new CliApprovalGate(prompt -> responses.poll(),
                    new PrintStream(new ByteArrayOutputStream()), null);

            assertTrue(gate.approve("op1", null));
            assertFalse(gate.approve("op2", null));
            assertTrue(gate.approve("op3", null));
        }

        private static CliApprovalGate functionGate(String response) {
            return new CliApprovalGate(prompt -> response,
                    new PrintStream(new ByteArrayOutputStream()), null);
        }
    }

    // ── Pre-prompt hook tests ───────────────────────────────────────────

    @Nested
    class PrePromptHook {

        @Test
        void hookFiresBeforePrompt() {
            var hookFired = new AtomicBoolean(false);
            var hookFiredBeforeRead = new AtomicBoolean(false);

            Function<String, String> reader = prompt -> {
                // When the reader is invoked, check if hook already fired
                hookFiredBeforeRead.set(hookFired.get());
                return "n";
            };

            var gate = new CliApprovalGate(reader,
                    new PrintStream(new ByteArrayOutputStream()),
                    () -> hookFired.set(true));

            gate.approve("write file", null);

            assertTrue(hookFired.get(), "Pre-prompt hook should have fired");
            assertTrue(hookFiredBeforeRead.get(),
                    "Hook should fire before the line reader is called");
        }

        @Test
        void hookExceptionDoesNotBreakApproval() {
            var gate = new CliApprovalGate(prompt -> "y",
                    new PrintStream(new ByteArrayOutputStream()),
                    () -> { throw new RuntimeException("spinner crash"); });

            // Approval should still work even if the hook throws
            assertTrue(gate.approve("write file", null));
        }

        @Test
        void noHookIsHarmless() {
            // null hook should not cause NPE
            var gate = new CliApprovalGate(prompt -> "y",
                    new PrintStream(new ByteArrayOutputStream()), null);
            assertTrue(gate.approve("write file", null));
        }

        @Test
        void hookCalledOncePerApproval() {
            var callCount = new AtomicInteger(0);
            var gate = new CliApprovalGate(prompt -> "y",
                    new PrintStream(new ByteArrayOutputStream()),
                    callCount::incrementAndGet);

            gate.approve("op1", null);
            gate.approve("op2", null);

            assertEquals(2, callCount.get(),
                    "Hook should be called once per approve() call");
        }
    }
}

