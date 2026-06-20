package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustClaimsHonestyTest {

    private static final String SANITIZED_WAVE6_EVIDENCE =
            "work-cycle-docs/reports/wave6-trust-overclaim-sanitized-evidence-20260619.md";
    private static final String RAW_WAVE6_AUDIT =
            "work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md";
    private static final String ANTI_OVERCLAIM_BOUNDARY =
            "Talos's deterministic no-change/no-success correction is strongest for file-mutation turns; "
                    + "`run_command` claims and read/answer factual claims are not yet equivalently covered.";
    private static final String SECRET_REDACTION_BOUNDARY =
            "Secret redaction currently catches common key=value secret shapes and known canaries; "
                    + "it does not yet detect standalone API tokens, JWTs, PEM private-key blocks, "
                    + "connection strings, or high-entropy blobs.";
    private static final String COMMAND_OUTPUT_BOUNDARY =
            "`run_command` stdout and stderr pass through the model-context handoff boundary. "
                    + "Non-sensitive command output remains visible to the model for verification answers; "
                    + "command output that required secret redaction is withheld from model context and replaced "
                    + "with a bounded notice. This is not a complete command-output privacy proof.";
    private static final String WINDOWS_PROTECTED_PATH_BOUNDARY =
            "Windows trailing-dot and trailing-space path aliases are canonicalized before protected-path matching; "
                    + "this is not a complete Windows path-security proof.";
    private static final String CHAT_LOCALHOST_BOUNDARY =
            "Chat model endpoints are localhost-gated by default. Non-localhost configured chat endpoints "
                    + "(`ollama.host`, `engines.llama_cpp.host`, `TALOS_OLLAMA_HOST`, or Ollama's "
                    + "`TALOS_ENGINE_HOST` override) are rejected unless explicit `allow_remote=true` is "
                    + "configured for that backend; when remote chat is "
                    + "explicitly allowed, full prompts can leave this machine.";
    private static final String MASTER_KEY_BOUNDARY =
            "On Windows, the local secret-store master key is protected at rest with DPAPI CurrentUser and is tied to the Windows user account. This is not hardware-backed custody and does not protect against a same-user process that can ask Windows to unprotect it. On non-Windows platforms, master-key custody remains unchanged and is not yet OS-backed.";
    private static final String TRACE_INTEGRITY_BOUNDARY =
            "Local traces and logs are durable evidence artifacts, but they are not tamper-evident.";
    private static final Pattern MISUSED_AGENTHALLU_STAT =
            Pattern.compile("11\\.6%.{0,120}(detect|detection|solution|solves|prove|proves)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern AGENTHALLU_PRESCRIBES_TALOS =
            Pattern.compile("AgentHallu.{0,160}(prescribe|prescribes|solution|mechanism|Talos)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNQUALIFIED_NO_COMPETITOR =
            Pattern.compile("\\bno\\s+competitor\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void readmeAndPolicyDocsBoundTrustClaimsToCurrentCode() throws Exception {
        String readme = read("README.md");
        String agents = read("AGENTS.md");
        String privacy = read("docs/user/local-privacy-and-artifacts.md");
        String localTrust = read("docs/architecture/01-execution-discipline-and-local-trust.md");

        for (String doc : new String[] { readme, agents, privacy, localTrust }) {
            assertContains(doc, ANTI_OVERCLAIM_BOUNDARY);
            assertContains(doc, SECRET_REDACTION_BOUNDARY);
            assertContains(doc, COMMAND_OUTPUT_BOUNDARY);
            assertContains(doc, WINDOWS_PROTECTED_PATH_BOUNDARY);
            assertContains(doc, CHAT_LOCALHOST_BOUNDARY);
            assertContains(doc, MASTER_KEY_BOUNDARY);
            assertContains(doc, TRACE_INTEGRITY_BOUNDARY);
        }
    }

    @Test
    void t833ReportPinsWave6MapAndSiteRecommendationWithoutEditingSite() throws Exception {
        String report = read("work-cycle-docs/reports/t833-wave6-trust-surface-honest-disclosure.md");
        String ticket = read("work-cycle-docs/tickets/done/[T833-done-high] wave6-trust-surface-honest-disclosure.md");

        assertContains(ticket, "Status: done");
        assertContains(report, "Wave 6 trust track");
        assertContains(report, "T274, T276, T281, T283, T286, T301, T319");
        assertContains(report, "Capability backlog, explicitly deferred");
        assertContains(report, "T294, T296, T299, T300, T302, T303, T304, T627");
        assertContains(report, "Site copy recommendations");
        assertContains(report, "site/index.html:349");
        assertContains(report, "site/index.html:356");
        assertContains(report, "site/index.html:402");
        assertContains(report, "Do not edit `site/` in T833");
        assertContains(report, "Post-Review Capture");
        assertContains(report, "T834, T835, T836, T837, T838");
        assertContains(report, "T834-T838 are the code-fix path before public push");
        assertContains(ticket, "T833 remains correct as the Tier 0 disclosure pass");
        assertContains(ticket, "Do not edit or stage `site/` in this pass");
    }

    @Test
    void publicPitchSurfacesAvoidWave6PositioningOverclaims() throws Exception {
        String publicPitch = read("README.md") + "\n"
                + read("AGENTS.md") + "\n"
                + readMarkdownTree("docs");

        assertDoesNotContainIgnoringCase(publicPitch, "provable agent");
        assertDoesNotContainIgnoringCase(publicPitch, "makes the model provable");
        assertDoesNotContainIgnoringCase(publicPitch, "tamper-proof");
        assertDoesNotContainIgnoringCase(publicPitch, "tamper proof");
        assertPatternAbsent(publicPitch, UNQUALIFIED_NO_COMPETITOR);
        assertPatternAbsent(publicPitch, MISUSED_AGENTHALLU_STAT);
        assertPatternAbsent(publicPitch, AGENTHALLU_PRESCRIBES_TALOS);

        assertContains(publicPitch, ANTI_OVERCLAIM_BOUNDARY);
        assertContains(publicPitch, TRACE_INTEGRITY_BOUNDARY);
    }

    @Test
    void externalReviewCaptureAndHighFollowupTicketsArePinned() throws Exception {
        String review = read("work-cycle-docs/research/external-review-wave6-deep-research-review-20260618.md");

        assertContains(review, "captured secondary review");
        assertContains(review, "Workflow: `w352woggx`");
        assertContains(review, "not the full deep-research report");
        assertContains(review, "Talos does not yet perform a post-write readback/re-hash round trip");
        assertContains(review, "Do not publish stronger trust positioning until those code fixes land");

        String t834 = read("work-cycle-docs/tickets/done/[T834-done-high] strong-redaction-model-context-and-durable-sinks.md");
        assertContains(t834, "Status: done");
        assertContains(t834, "Bare `ghp_`, `sk-`, AWS access-key, JWT, PEM private-key");
        assertContains(t834, "over-redaction of SRI hashes, data URIs, and long identifiers");
        String t835 = read("work-cycle-docs/tickets/done/[T835-done-high] chat-transport-localhost-guard.md");
        assertContains(t835, "Status: done");
        assertContains(t835, "Default-deny non-localhost chat hosts");
        String t836 = read("work-cycle-docs/tickets/done/[T836-done-high] windows-protected-path-canonicalization.md");
        assertContains(t836, "Status: done");
        assertContains(t836, "`id_rsa.`, `id_rsa `, `.ssh.`, `secrets.`");
        assertContains(t836, "SSH~1/mykey");
        assertContains(t836, "protected-content-policy-v6");
        String t837 = read("work-cycle-docs/tickets/done/[T837-done-high] run-command-output-handoff-boundary.md");
        assertContains(t837, "Status: done");
        assertContains(t837,
                "route it through the privacy handoff");
        assertContains(read("work-cycle-docs/tickets/done/[T838-done-high] master-key-custody.md"),
                "raw AES master key is not stored beside ciphertext");
    }

    @Test
    void activeWave6TrustTicketsUseTrackedSanitizedEvidence() throws Exception {
        String sanitized = read(SANITIZED_WAVE6_EVIDENCE);

        assertContains(sanitized, "Status: sanitized tracked evidence record");
        assertContains(sanitized, "54 confirmed or partially mitigated overclaims");
        assertContains(sanitized, "Confirmed severity split: 5 high, 19 medium, 30 low.");
        assertContains(sanitized, "T834");
        assertContains(sanitized, "T835");
        assertContains(sanitized, "T836");
        assertContains(sanitized, "T837");
        assertContains(sanitized, "T838");
        assertContains(sanitized, "Do not promote the raw audit file as-is.");

        for (String file : List.of(
                "work-cycle-docs/tickets/done/[T833-done-high] wave6-trust-surface-honest-disclosure.md",
                "work-cycle-docs/tickets/done/[T834-done-high] strong-redaction-model-context-and-durable-sinks.md",
                "work-cycle-docs/tickets/done/[T835-done-high] chat-transport-localhost-guard.md",
                "work-cycle-docs/tickets/done/[T836-done-high] windows-protected-path-canonicalization.md",
                "work-cycle-docs/tickets/done/[T837-done-high] run-command-output-handoff-boundary.md",
                "work-cycle-docs/tickets/done/[T838-done-high] master-key-custody.md",
                "work-cycle-docs/reports/t833-wave6-trust-surface-honest-disclosure.md")) {
            String text = read(file);
            assertContains(text, SANITIZED_WAVE6_EVIDENCE);
            assertFalse(text.contains(RAW_WAVE6_AUDIT),
                    "Active Wave 6 trust records must not depend on the ignored raw audit: " + file);
        }
    }

    private static String read(String first, String... more) throws Exception {
        return Files.readString(Path.of(first, more));
    }

    private static String readMarkdownTree(String directory) throws Exception {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(Path.of(directory))) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            sb.append(Files.readString(file)).append('\n');
        }
        return sb.toString();
    }

    private static void assertContains(String text, String expected) {
        assertTrue(text.contains(expected), "Missing required trust disclosure: " + expected);
    }

    private static void assertDoesNotContainIgnoringCase(String text, String forbidden) {
        assertFalse(text.toLowerCase().contains(forbidden.toLowerCase()),
                "Forbidden trust-positioning phrase is present: " + forbidden);
    }

    private static void assertPatternAbsent(String text, Pattern forbidden) {
        assertFalse(forbidden.matcher(text).find(),
                "Forbidden trust-positioning pattern is present: " + forbidden.pattern());
    }
}
