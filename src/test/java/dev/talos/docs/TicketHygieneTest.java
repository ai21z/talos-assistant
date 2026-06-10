package dev.talos.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T748: deterministic ticket-corpus hygiene.
 *
 * <p>Repo-wide rules (zero violations at introduction): directory/filename
 * status-token consistency for bracketed tickets, and ticket-ID uniqueness
 * across open/ + done/. Strict template rules (filename vocabulary and a body
 * {@code Status:} line) apply only to tickets numbered T739 and above —
 * a numeric grandfather threshold in the spirit of the architecture ratchet:
 * new work cannot regress, the 80+ legacy variants stay untouched.
 */
class TicketHygieneTest {

    private static final Path OPEN = Path.of("work-cycle-docs/tickets/open");
    private static final Path DONE = Path.of("work-cycle-docs/tickets/done");
    private static final Pattern BRACKETED = Pattern.compile("^\\[T(\\d+)-(open|done)-([a-z-]+)\\] (.+)\\.md$");
    private static final Pattern STRICT = Pattern.compile("^\\[T\\d+-(open|done)-(high|medium|low)\\] .+\\.md$");
    private static final int STRICT_FROM_TICKET_ID = 739;

    @Test
    void bracketedTicketStatusTokensMatchTheirDirectory() throws IOException {
        List<String> violations = new ArrayList<>();
        for (var entry : ticketFiles(OPEN)) {
            Matcher m = BRACKETED.matcher(entry.getFileName().toString());
            if (m.matches() && !"open".equals(m.group(2))) {
                violations.add(entry + " carries -" + m.group(2) + "- but lives in open/");
            }
        }
        for (var entry : ticketFiles(DONE)) {
            Matcher m = BRACKETED.matcher(entry.getFileName().toString());
            if (m.matches() && !"done".equals(m.group(2))) {
                violations.add(entry + " carries -" + m.group(2) + "- but lives in done/");
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void ticketIdsAreUniqueAcrossOpenAndDone() throws IOException {
        Map<String, String> seen = new HashMap<>();
        List<String> violations = new ArrayList<>();
        for (Path dir : List.of(OPEN, DONE)) {
            for (var entry : ticketFiles(dir)) {
                Matcher m = BRACKETED.matcher(entry.getFileName().toString());
                if (!m.matches()) continue;
                String id = m.group(1);
                String prior = seen.putIfAbsent(id, entry.toString());
                if (prior != null) {
                    violations.add("duplicate ticket id T" + id + ": " + prior + " and " + entry);
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    @Test
    void modernTicketsFollowTheStrictTemplate() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path dir : List.of(OPEN, DONE)) {
            String expectedStatus = dir == OPEN ? "open" : "done";
            for (var entry : ticketFiles(dir)) {
                String fileName = entry.getFileName().toString();
                Matcher m = BRACKETED.matcher(fileName);
                if (!m.matches()) continue;
                int id;
                try {
                    id = Integer.parseInt(m.group(1));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (id < STRICT_FROM_TICKET_ID) continue;

                if (!STRICT.matcher(fileName).matches()) {
                    violations.add(entry + ": filename must match [Txx-(open|done)-(high|medium|low)] slug.md");
                }
                String body = Files.readString(entry);
                if (!body.lines().anyMatch(line -> line.strip().startsWith("Status: " + expectedStatus))) {
                    violations.add(entry + ": body must contain a 'Status: " + expectedStatus + "' line");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    private static List<Path> ticketFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }
}
