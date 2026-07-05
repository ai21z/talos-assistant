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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatesLedgerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURE = Path.of("src/test/resources/release-gates/valid-gates-ledger.json");
    private static final Set<String> STATUSES = Set.of(
            "PASS", "FAIL_REVIEW_REQUIRED", "MANUAL_REQUIRED", "NOT_RUN", "SUPERSEDED");
    private static final Set<String> LANES = Set.of(
            "SAFE_REDIRECTED_STDIN", "SYNC_APPROVAL", "SYNC_APPROVAL_WORKSPACE_OPS",
            "TRUE_PTY_MANUAL", "CAPABILITY_PRIVATE_MODE", "DETERMINISTIC", "STATIC_ANALYSIS");

    @Test
    void sampleGatesLedgerValidatesAgainstSchemaV1() throws IOException {
        assertFalse(Files.readString(FIXTURE).isBlank(), "fixture must not be empty");

        List<String> violations = new ArrayList<>();
        JsonNode root = MAPPER.readTree(Files.readString(FIXTURE));
        requireText(violations, FIXTURE, root, "schema", "talos.releaseGates.v1");
        requireNonBlank(violations, FIXTURE, root, "packet");
        requireNonBlank(violations, FIXTURE, root, "branch");
        requireNonBlank(violations, FIXTURE, root, "generated");
        String sha = root.path("sha").asText("");
        if (!sha.matches("[0-9a-f]{40}")) {
            violations.add(FIXTURE + ": sha must be a 40-hex git sha (from git rev-parse), got '" + sha + "'");
        }
        JsonNode gates = root.path("gates");
        if (!gates.isArray() || gates.isEmpty()) {
            violations.add(FIXTURE + ": gates must be a non-empty array");
        } else {
            for (JsonNode gate : gates) {
                validateGate(violations, gate);
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

    private static void validateGate(List<String> violations, JsonNode gate) {
        String name = gate.path("name").asText("");
        if (name.isBlank()) violations.add(FIXTURE + ": gate with blank name");
        if (!LANES.contains(gate.path("lane").asText(""))) {
            violations.add(FIXTURE + ": gate '" + name + "' has unknown lane '"
                    + gate.path("lane").asText("") + "'");
        }
        if (!STATUSES.contains(gate.path("status").asText(""))) {
            violations.add(FIXTURE + ": gate '" + name + "' has unknown status '"
                    + gate.path("status").asText("") + "'");
        }
        if (gate.path("evidencePath").asText("").isBlank()) {
            violations.add(FIXTURE + ": gate '" + name + "' has blank evidencePath");
        }
    }
}
