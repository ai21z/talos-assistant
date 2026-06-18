package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustClaimsHonestyTest {

    private static final String ANTI_OVERCLAIM_BOUNDARY =
            "Talos's deterministic no-change/no-success correction is strongest for file-mutation turns; "
                    + "`run_command` claims and read/answer factual claims are not yet equivalently covered.";
    private static final String SECRET_REDACTION_BOUNDARY =
            "Secret redaction currently catches common key=value secret shapes and known canaries; "
                    + "it does not yet detect standalone API tokens, JWTs, PEM private-key blocks, "
                    + "connection strings, or high-entropy blobs.";
    private static final String COMMAND_OUTPUT_BOUNDARY =
            "`run_command` stdout and stderr are not withheld from model context by default.";
    private static final String WINDOWS_PROTECTED_PATH_BOUNDARY =
            "On Windows, paths that differ only by trailing dots or spaces can bypass exact-name protected-path matching.";
    private static final String CHAT_LOCALHOST_BOUNDARY =
            "The chat transport does not yet enforce a localhost-only guard; a configured remote `ollama.host` can receive prompts.";
    private static final String MASTER_KEY_BOUNDARY =
            "The local master key is still stored beside the encrypted data, so current encryption is casual-inspection protection, not OS-backed key custody.";
    private static final String TRACE_INTEGRITY_BOUNDARY =
            "Local traces and logs are durable evidence artifacts, but they are not tamper-evident.";

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
        String ticket = read("work-cycle-docs/tickets/open/[T833-open-high] wave6-trust-surface-honest-disclosure.md");

        assertContains(ticket, "Status: open");
        assertContains(report, "Wave 6 trust track");
        assertContains(report, "T274, T276, T281, T283, T286, T301, T319");
        assertContains(report, "Capability backlog, explicitly deferred");
        assertContains(report, "T294, T296, T299, T300, T302, T303, T304, T627");
        assertContains(report, "Site copy recommendations");
        assertContains(report, "site/index.html:349");
        assertContains(report, "site/index.html:356");
        assertContains(report, "site/index.html:402");
        assertContains(report, "Do not edit `site/` in T833");
    }

    private static String read(String first, String... more) throws Exception {
        return Files.readString(Path.of(first, more));
    }

    private static void assertContains(String text, String expected) {
        assertTrue(text.contains(expected), "Missing required trust disclosure: " + expected);
    }
}
