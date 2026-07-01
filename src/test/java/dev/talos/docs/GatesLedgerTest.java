package dev.talos.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T749: every release gate ledger under work-cycle-docs/reports validates against schema v1. */
class GatesLedgerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path REPORTS = Path.of("work-cycle-docs/reports");
    private static final Set<String> STATUSES = Set.of(
            "PASS", "FAIL_REVIEW_REQUIRED", "MANUAL_REQUIRED", "NOT_RUN", "SUPERSEDED");
    private static final Set<String> LANES = Set.of(
            "SAFE_REDIRECTED_STDIN", "SYNC_APPROVAL", "SYNC_APPROVAL_WORKSPACE_OPS",
            "TRUE_PTY_MANUAL", "CAPABILITY_PRIVATE_MODE", "DETERMINISTIC", "STATIC_ANALYSIS");

    @Test
    void everyGatesLedgerValidatesAgainstSchemaV1() throws IOException {
        List<Path> ledgers = ledgerFiles();
        assertFalse(ledgers.isEmpty(), "expected at least one *GATES.json ledger under " + REPORTS);

        List<String> violations = new ArrayList<>();
        for (Path ledger : ledgers) {
            JsonNode root;
            try {
                root = MAPPER.readTree(Files.readString(ledger));
            } catch (Exception e) {
                violations.add(ledger + ": unparseable JSON - " + e.getMessage());
                continue;
            }
            requireText(violations, ledger, root, "schema", "talos.releaseGates.v1");
            requireNonBlank(violations, ledger, root, "packet");
            requireNonBlank(violations, ledger, root, "branch");
            requireNonBlank(violations, ledger, root, "generated");
            String sha = root.path("sha").asText("");
            if (!sha.matches("[0-9a-f]{40}")) {
                violations.add(ledger + ": sha must be a 40-hex git sha (from git rev-parse), got '" + sha + "'");
            }
            JsonNode gates = root.path("gates");
            if (!gates.isArray() || gates.isEmpty()) {
                violations.add(ledger + ": gates must be a non-empty array");
                continue;
            }
            for (JsonNode gate : gates) {
                String name = gate.path("name").asText("");
                if (name.isBlank()) violations.add(ledger + ": gate with blank name");
                if (!LANES.contains(gate.path("lane").asText(""))) {
                    violations.add(ledger + ": gate '" + name + "' has unknown lane '"
                            + gate.path("lane").asText("") + "'");
                }
                if (!STATUSES.contains(gate.path("status").asText(""))) {
                    violations.add(ledger + ": gate '" + name + "' has unknown status '"
                            + gate.path("status").asText("") + "'");
                }
                if (gate.path("evidencePath").asText("").isBlank()) {
                    violations.add(ledger + ": gate '" + name + "' has blank evidencePath");
                }
            }
        }
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    private static void requireText(List<String> violations, Path ledger, JsonNode root,
                                    String field, String expected) {
        if (!expected.equals(root.path(field).asText(""))) {
            violations.add(ledger + ": " + field + " must be '" + expected + "'");
        }
    }

    private static void requireNonBlank(List<String> violations, Path ledger, JsonNode root, String field) {
        if (root.path(field).asText("").isBlank()) {
            violations.add(ledger + ": " + field + " must be non-blank");
        }
    }

    private static List<Path> ledgerFiles() throws IOException {
        if (!Files.isDirectory(REPORTS)) return List.of();
        try (Stream<Path> stream = Files.walk(REPORTS)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("GATES.json"))
                    .sorted()
                    .toList();
        }
    }
}
