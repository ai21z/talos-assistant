package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkCycleDocsHygieneTest {

    private static final List<Path> REQUIRED_TRACKED_WORK_CYCLE_DOCS = List.of(
            Path.of("work-cycle-docs/README.md"),
            Path.of("work-cycle-docs/work-test-cycle.md"),
            Path.of("work-cycle-docs/runbooks/manual-qa.md"),
            Path.of("work-cycle-docs/runbooks/release-candidate.md"),
            Path.of("work-cycle-docs/runbooks/live-audit.md"),
            Path.of("work-cycle-docs/runbooks/installed-product-smoke.md"),
            Path.of("work-cycle-docs/templates/audit-finding.md"),
            Path.of("work-cycle-docs/templates/qa-packet.md"),
            Path.of("work-cycle-docs/templates/release-checklist.md"),
            Path.of("work-cycle-docs/templates/talosbench-summary-template.md"));
    private static final List<Path> ARCHIVE_DIRS_NOT_TRACKED = List.of(
            Path.of("docs/superpowers"),
            Path.of("docs/evaluation"),
            Path.of("work-cycle-docs/tickets"),
            Path.of("work-cycle-docs/reports"),
            Path.of("work-cycle-docs/research"),
            Path.of("work-cycle-docs/wiki"));
    private static final Pattern TICKET_ID = Pattern.compile("\\bT\\d{3,}\\b");

    @Test
    void workCycleDocsContainOnlyRunbooksAndTemplates() throws Exception {
        for (Path path : REQUIRED_TRACKED_WORK_CYCLE_DOCS) {
            assertTrue(Files.isRegularFile(path), "missing maintained work-cycle doc: " + path);
            assertFalse(Files.readString(path).isBlank(), "empty maintained work-cycle doc: " + path);
        }

        for (Path path : ARCHIVE_DIRS_NOT_TRACKED) {
            assertFalse(Files.exists(path), "archive path must not remain tracked/public: " + path);
        }
    }

    @Test
    void maintainedWorkCycleDocsDoNotLeakTicketArchiveLanguage() throws Exception {
        StringBuilder text = new StringBuilder();
        for (Path path : REQUIRED_TRACKED_WORK_CYCLE_DOCS) {
            text.append(Files.readString(path)).append('\n');
        }
        String docs = text.toString();

        assertFalse(TICKET_ID.matcher(docs).find(), "work-cycle runbooks must not refer to local ticket IDs");
        assertFalse(docs.contains("tickets/done"), "work-cycle runbooks must not point at archived done tickets");
        assertFalse(docs.contains("tickets/open"), "work-cycle runbooks must not point at local open tickets");
        assertFalse(docs.contains("wikiEvidenceCloseGate"), "work-cycle runbooks must not reference the archived wiki gate");
    }
}
