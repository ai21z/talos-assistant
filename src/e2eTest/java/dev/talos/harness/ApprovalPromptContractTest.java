package dev.talos.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.cli.approval.CliApprovalGate;
import dev.talos.cli.ui.ApprovalPromptText;
import dev.talos.cli.ui.PromptRenderer;
import dev.talos.runtime.ApprovalResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cross-surface byte-identity contract for the approval-prompt chrome (T766).
 *
 * <p>The approval prompt is a load-bearing evidence-chain string: production
 * renders it, the scripted harness publishes it into audit artifacts, the
 * true-PTY manual-audit validator requires it verbatim in real-terminal
 * transcripts, and the talosbench live-prompt matrix forbids it in redirected
 * deny-path transcripts. This test holds all of those surfaces to the same
 * bytes. Every expectation is a string literal typed here - never a constant
 * compared with itself - so no surface can silently re-pin the contract.
 *
 * <p>If this test fails, an evidence surface drifted: recorded manual-audit
 * packets and talosbench banks reference these bytes, so a deliberate change
 * requires a fresh PTY validation cycle, not just a test update.
 */
class ApprovalPromptContractTest {

    private static final String SESSION_LITERAL = "Allow? [y=yes, a=yes for session, N=no]";
    private static final String ONCE_LITERAL = "Allow? [y=yes, N=no]";
    private static final String PREFIX_LITERAL = "Allow? [y=yes";

    @Test
    void productionConstantsMatchTheEvidenceChainBytes() {
        assertEquals(SESSION_LITERAL, ApprovalPromptText.SESSION_PROMPT);
        assertEquals(ONCE_LITERAL, ApprovalPromptText.ONCE_PROMPT);
        assertEquals(PREFIX_LITERAL, ApprovalPromptText.PROMPT_PREFIX);
        assertEquals("  " + SESSION_LITERAL + " ", ApprovalPromptText.SESSION_PROMPT_LINE);
        assertEquals("  " + ONCE_LITERAL + " ", ApprovalPromptText.ONCE_PROMPT_LINE);
    }

    @Test
    void productionGatePassesTheExactLineFormsToTheTerminalReader() {
        List<String> prompts = new ArrayList<>();
        var gate = new CliApprovalGate(prompt -> {
            prompts.add(prompt);
            return "n";
        }, new PrintStream(new ByteArrayOutputStream()), null);

        gate.approveFull("write file", null);
        gate.approveOnce("private document model handoff", null);

        assertEquals(List.of("  " + SESSION_LITERAL + " ", "  " + ONCE_LITERAL + " "), prompts);
    }

    @Test
    void scriptedHarnessPublishesTheSameCoreFormsIntoAuditEvents() {
        var gate = new ScriptedApprovalGate(List.of(
                ScriptedApprovalGate.Step.deny("write file", ""),
                new ScriptedApprovalGate.Step("handoff", "", ApprovalResponse.DENIED)));

        gate.approveFull("write file", null);
        gate.approveOnce("handoff", null);

        assertEquals(SESSION_LITERAL, gate.events().get(0).prompt());
        assertEquals(ONCE_LITERAL, gate.events().get(1).prompt());
    }

    @Test
    void ptyValidatorAcceptsTranscriptsCarryingExactlyTheProductionBytes() {
        // The validator's required-substring check and production's rendered
        // prompt must agree byte-for-byte: a transcript carrying exactly the
        // production session prompt must not raise the missing-prompt finding,
        // and one without it must.
        String finding = "completed transcript must show the ordinary protected-read approval prompt";

        List<String> withPrompt = SynchronizedCliPtyManualAuditValidator
                .auditTranscriptFindings("prefix " + SESSION_LITERAL + " suffix");
        assertFalse(withPrompt.contains(finding),
                "validator must accept the production prompt bytes: " + withPrompt);

        List<String> withoutPrompt = SynchronizedCliPtyManualAuditValidator
                .auditTranscriptFindings("no approval prompt here");
        assertTrue(withoutPrompt.contains(finding),
                "validator must require the production prompt bytes: " + withoutPrompt);
    }

    @Test
    void talosbenchForbiddenSubstringsArePrefixesOfTheProductionPrompt() throws Exception {
        Path cases = locateTalosbenchCases();
        JsonNode root = new ObjectMapper().readTree(Files.readString(cases));
        List<String> allowEntries = new ArrayList<>();
        for (JsonNode caseNode : root.path("cases")) {
            JsonNode forbidden = caseNode.get("forbiddenOutputSubstrings");
            if (forbidden == null) continue;
            for (JsonNode entry : forbidden) {
                String text = entry.asText();
                if (text.startsWith("Allow")) {
                    allowEntries.add(text);
                }
            }
        }
        assertFalse(allowEntries.isEmpty(),
                "talosbench-cases.json no longer forbids any approval-prompt substring - "
                        + "the redirected-deny-path evidence contract was removed");
        for (String entry : allowEntries) {
            assertTrue(SESSION_LITERAL.startsWith(entry),
                    "talosbench forbidden substring [" + entry + "] no longer prefixes the "
                            + "production session prompt [" + SESSION_LITERAL + "]");
        }
    }

    @Test
    void replPromptDrivenByTheProcessHarnessIsByteFrozen() {
        // SynchronizedCliProcessDriver steps key on this prompt to synchronize
        // scripted stdin with the REPL; recorded transcripts carry it.
        assertEquals("talos [auto] > ", PromptRenderer.render("auto", false, null));
    }

    private static Path locateTalosbenchCases() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("tools").resolve("manual-eval").resolve("talosbench-cases.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return fail("tools/manual-eval/talosbench-cases.json not found within 4 levels above "
                + System.getProperty("user.dir") + " - adjust the walk-up or the bank moved");
    }
}
