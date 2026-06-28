package dev.talos.docs;

import dev.talos.runtime.toolcall.CanonicalToolDescriptors;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T783: the README "Tool Use" table is a user-facing claim about the
 * registered tool surface. It drifted once - {@code talos.delete_path} was
 * registered and approval-gated but missing from the table through 0.10.4,
 * claims drift on the most dangerous tool - so the table is now pinned
 * bidirectionally against {@link CanonicalToolDescriptors} (the same catalog
 * the T761 parity test holds against a bootstrap-equivalent registry).
 */
class ReadmeToolTableDriftTest {

    private static final Path README = Path.of("README.md");
    private static final String TABLE_HEADER = "| Tool | Purpose | Approval |";
    private static final Pattern ROW =
            Pattern.compile("^\\|\\s*`([a-z0-9_]+)`\\s*\\|[^|]*\\|\\s*([a-z ]+?)\\s*\\|$");

    @Test
    void readmeToolTableMatchesTheCanonicalCatalog() throws IOException {
        Map<String, String> documented = documentedTools();
        Map<String, String> registered = registeredTools();

        assertEquals(registered.keySet(), documented.keySet(),
                "README Tool Use table must list exactly the registered canonical tools"
                        + " (add the missing row / remove the stale one)");
        for (Map.Entry<String, String> tool : registered.entrySet()) {
            assertEquals(tool.getValue(), documented.get(tool.getKey()),
                    "README approval column for '" + tool.getKey()
                            + "' must match the tool's registered operation metadata");
        }
    }

    private static Map<String, String> documentedTools() throws IOException {
        List<String> lines = Files.readAllLines(README);
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (TABLE_HEADER.equals(lines.get(i).trim())) {
                header = i;
                break;
            }
        }
        assertTrue(header >= 0,
                "README must contain the '" + TABLE_HEADER + "' table header");
        Map<String, String> rows = new TreeMap<>();
        for (int i = header + 2; i < lines.size(); i++) { // +2 skips the |---| separator
            Matcher m = ROW.matcher(lines.get(i).trim());
            if (!m.matches()) break;
            rows.put(m.group(1), m.group(2));
        }
        assertFalse(rows.isEmpty(),
                "README tool table parsed zero rows - has the table format changed?");
        return rows;
    }

    private static Map<String, String> registeredTools() {
        Map<String, String> tools = new TreeMap<>();
        CanonicalToolDescriptors.registry().all().forEach((name, tool) -> {
            String shortName = name.startsWith("talos.")
                    ? name.substring("talos.".length())
                    : name;
            boolean approval = tool.descriptor().operationMetadata().requiresApproval();
            tools.put(shortName, approval ? "required" : "not required");
        });
        return tools;
    }
}
