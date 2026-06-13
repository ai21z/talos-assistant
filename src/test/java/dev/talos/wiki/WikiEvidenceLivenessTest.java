package dev.talos.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Talos wiki generated-report evidence liveness")
class WikiEvidenceLivenessTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path WIKI_ROOT = Path.of("work-cycle-docs", "wiki");
    private static final Path CURRENT_STATE_PAGE = Path.of("CURRENT-STATE.md");
    private static final Path REGISTRY_PAGE = Path.of("EVIDENCE-REGISTRY.md");
    private static final Path GENERATED_REPORT_DATA_ROOT = Path.of(
            "build", "reports", "talos", "architecture-intelligence", "current", "data").normalize();
    private static final Path RUN_MANIFEST = GENERATED_REPORT_DATA_ROOT.resolve("run-manifest.json");
    private static final Path IDENTITY_FRESHNESS_REPORT = Path.of(
            "build", "reports", "talos", "wiki-lint", "current", "identity-freshness.json");
    private static final Pattern CLAIM_BLOCK = Pattern.compile(
            "(?m)^```talos-wiki-claims\\R([\\s\\S]*?)\\R```\\s*$");
    private static final Pattern REGISTRY_BLOCK = Pattern.compile(
            "(?m)^```talos-wiki-evidence-registry\\R([\\s\\S]*?)\\R```\\s*$");
    private static final Set<String> OPERATORS = Set.of(
            "exists", "notBlank", "equals", "contains", "equalsGradleProperty");

    @Test
    @DisplayName("wiki claim blocks resolve against generated architecture report JSON")
    void wikiClaimBlocksResolveAgainstGeneratedReports() throws IOException {
        List<ClaimBlock> blocks = claimBlocks();
        Map<String, Path> registry = evidenceRegistry();
        assertTrue(blocks.stream().anyMatch(block -> block.page().equals(Path.of("CURRENT-STATE.md"))),
                "CURRENT-STATE.md must contain at least one talos-wiki-claims block");

        Set<String> ids = new LinkedHashSet<>();
        for (ClaimBlock block : blocks) {
            assertEquals("talos.wikiClaims.v1", block.json().path("schema").asText(), block.label());
            JsonNode claims = block.json().path("claims");
            assertTrue(claims.isArray() && claims.size() > 0, block.label() + " must contain claims");
            for (JsonNode claim : claims) {
                validateClaim(block, claim, ids, registry);
            }
        }
        writeIdentityFreshnessReport();
        assertTrue(Files.isRegularFile(IDENTITY_FRESHNESS_REPORT),
                "wiki evidence lint must write identity freshness advisory report");
    }

    @Test
    @DisplayName("current state does not hard-pin volatile branch or commit report identity")
    void currentStateDoesNotHardPinVolatileGitIdentity() throws IOException {
        JsonNode currentStateClaims = currentStateClaimBlock().json().path("claims");
        for (JsonNode claim : currentStateClaims) {
            String id = claim.path("id").asText();
            String pointer = claim.path("evidence").path("jsonPointer").asText();
            assertFalse(id.equals("current.arch-report.branch") || pointer.equals("/branch"),
                    "CURRENT-STATE.md must not hard-claim generated branch identity: " + claim);
            assertFalse(id.equals("current.arch-report.commit") || pointer.equals("/commit"),
                    "CURRENT-STATE.md must not hard-claim generated commit identity: " + claim);
        }
    }

    @Test
    @DisplayName("current state validates generated version against gradle.properties")
    void currentStateValidatesGeneratedVersionAgainstGradleProperties() throws IOException {
        JsonNode claims = currentStateClaimBlock().json().path("claims");
        JsonNode versionClaim = null;
        for (JsonNode claim : claims) {
            if ("current.arch-report.version-matches-gradle".equals(claim.path("id").asText())) {
                versionClaim = claim;
                break;
            }
        }
        assertTrue(versionClaim != null,
                "CURRENT-STATE.md must use current.arch-report.version-matches-gradle");
        assertEquals("/talosVersion", versionClaim.path("evidence").path("jsonPointer").asText());
        assertEquals("equalsGradleProperty", versionClaim.path("operator").asText());
        assertEquals("talosVersion", versionClaim.path("gradleProperty").asText());
        assertFalse(versionClaim.has("expected"),
                "version claim must not hard-pin a literal expected version: " + versionClaim);
    }

    @Test
    @DisplayName("current-state generated claims use the evidence registry")
    void currentStateGeneratedClaimsUseEvidenceRegistry() throws IOException {
        JsonNode claims = currentStateClaimBlock().json().path("claims");
        for (JsonNode claim : claims) {
            JsonNode evidence = claim.path("evidence");
            assertEquals("generated_report", evidence.path("type").asText(), "unexpected evidence type: " + claim);
            assertEquals("architectureIntelligence.runManifest", evidence.path("id").asText(),
                    "CURRENT-STATE.md generated claims must use the run-manifest registry id: " + claim);
            assertFalse(evidence.has("path"), "CURRENT-STATE.md must not duplicate raw evidence paths: " + claim);
        }
    }

    private static void validateClaim(ClaimBlock block, JsonNode claim, Set<String> ids, Map<String, Path> registry)
            throws IOException {
        String id = requiredText(claim, "id", block.label());
        assertTrue(ids.add(id), block.label(id) + " duplicate claim id");
        assertEquals("DETERMINISTIC_GENERATED", requiredText(claim, "confidence", block.label(id)),
                block.label(id) + " confidence must be DETERMINISTIC_GENERATED");

        JsonNode evidence = claim.path("evidence");
        assertTrue(evidence.isObject(), block.label(id) + " evidence must be an object");
        assertEquals("generated_report", requiredText(evidence, "type", block.label(id)),
                block.label(id) + " evidence type must be generated_report");
        String rawPath = evidencePath(block, id, evidence, registry);
        Path reportPath = Path.of(rawPath).normalize();
        assertFalse(reportPath.isAbsolute(), block.label(id, rawPath, "") + " evidence path must be repo-relative");
        assertTrue(reportPath.startsWith(GENERATED_REPORT_DATA_ROOT),
                block.label(id, rawPath, "") + " evidence path must stay under " + GENERATED_REPORT_DATA_ROOT);
        assertTrue(Files.isRegularFile(reportPath),
                block.label(id, rawPath, "") + " generated report JSON is missing");

        String pointer = requiredText(evidence, "jsonPointer", block.label(id, rawPath, ""));
        assertTrue(pointer.startsWith("/"), block.label(id, rawPath, pointer) + " JSON Pointer must start with /");
        JsonNode report = JSON.readTree(reportPath.toFile());
        JsonNode actual = report.at(pointer);
        assertFalse(actual.isMissingNode(), block.label(id, rawPath, pointer) + " JSON Pointer did not resolve");

        String operator = requiredText(claim, "operator", block.label(id, rawPath, pointer));
        assertTrue(OPERATORS.contains(operator),
                block.label(id, rawPath, pointer) + " unsupported operator: " + operator);
        applyOperator(block, id, rawPath, pointer, operator, claim, actual);
    }

    private static String evidencePath(ClaimBlock block, String claimId, JsonNode evidence, Map<String, Path> registry) {
        if (evidence.has("id")) {
            String evidenceId = requiredText(evidence, "id", block.label(claimId));
            Path path = registry.get(evidenceId);
            assertTrue(path != null, block.label(claimId) + " unknown evidence registry id: " + evidenceId);
            return path.toString();
        }
        return requiredText(evidence, "path", block.label(claimId));
    }

    private static void applyOperator(ClaimBlock block, String id, String rawPath, String pointer,
            String operator, JsonNode claim, JsonNode actual) throws IOException {
        String label = block.label(id, rawPath, pointer);
        switch (operator) {
            case "exists" -> {
                // JSON Pointer resolution above proves existence.
            }
            case "notBlank" -> assertFalse(actual.asText().isBlank(), label + " expected non-blank value");
            case "equals" -> assertEquals(expectedText(claim, label), actual.asText(),
                    label + " expected exact generated-report value");
            case "contains" -> assertContains(actual, expectedText(claim, label), label);
            case "equalsGradleProperty" -> assertEquals(
                    gradlePropertyValue(requiredText(claim, "gradleProperty", label), label),
                    actual.asText(),
                    label + " expected generated-report value to match gradle.properties");
            default -> throw new AssertionError(label + " unsupported operator: " + operator);
        }
    }

    private static void assertContains(JsonNode actual, String expected, String label) {
        if (actual.isArray()) {
            for (JsonNode item : actual) {
                if (expected.equals(item.asText())) {
                    return;
                }
            }
            throw new AssertionError(label + " expected array to contain " + expected + ": " + actual);
        }
        assertTrue(actual.asText().contains(expected), label + " expected text to contain " + expected);
    }

    private static String requiredText(JsonNode node, String field, String label) {
        assertTrue(node.has(field), label + " missing field: " + field);
        String value = node.path(field).asText();
        assertFalse(value.isBlank(), label + " blank field: " + field);
        return value;
    }

    private static String expectedText(JsonNode claim, String label) {
        assertTrue(claim.has("expected"), label + " missing expected value");
        return claim.path("expected").asText();
    }

    private static String gradlePropertyValue(String key, String label) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("gradle.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String value = properties.getProperty(key);
        assertTrue(value != null && !value.isBlank(), label + " missing gradle.properties key: " + key);
        return value.trim();
    }

    private static void writeIdentityFreshnessReport() throws IOException {
        Path pagePath = WIKI_ROOT.resolve(CURRENT_STATE_PAGE);
        String content = Files.readString(pagePath, StandardCharsets.UTF_8);
        String frontmatter = frontmatter(content, CURRENT_STATE_PAGE);
        JsonNode manifest = JSON.readTree(RUN_MANIFEST.toFile());

        List<Map<String, Object>> checks = List.of(
                freshnessRow("branch", stripTicks(lineValue(content, "- Branch:")),
                        manifest.path("branch").asText("")),
                freshnessRow("commit", stripTicks(lineValue(content, "- Commit:")),
                        manifest.path("commit").asText("")),
                freshnessRow("last_verified_commit", scalar(frontmatter, "last_verified_commit", CURRENT_STATE_PAGE),
                        manifest.path("commit").asText("")),
                freshnessRow("talosVersion", stripTicks(lineValue(content, "- Talos version:")),
                        manifest.path("talosVersion").asText("")));
        boolean drift = checks.stream().anyMatch(row -> "DRIFT".equals(row.get("status")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "talos.wikiIdentityFreshness.v1");
        payload.put("page", WIKI_ROOT.resolve(CURRENT_STATE_PAGE).toString());
        payload.put("generatedReport", RUN_MANIFEST.toString());
        payload.put("status", drift ? "DRIFT" : "MATCH");
        payload.put("checks", checks);

        Files.createDirectories(IDENTITY_FRESHNESS_REPORT.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(IDENTITY_FRESHNESS_REPORT.toFile(), payload);
    }

    private static Map<String, Object> freshnessRow(String field, String wikiValue, String generatedValue) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("field", field);
        row.put("wikiValue", wikiValue);
        row.put("generatedValue", generatedValue);
        row.put("status", wikiValue.equals(generatedValue) ? "MATCH" : "DRIFT");
        return row;
    }

    private static String frontmatter(String content, Path page) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        assertTrue(normalized.startsWith("---\n"), "missing opening frontmatter delimiter: " + page);
        int end = normalized.indexOf("\n---\n", 4);
        assertTrue(end >= 0, "missing closing frontmatter delimiter: " + page);
        return normalized.substring(4, end);
    }

    private static String scalar(String frontmatter, String key, Path page) {
        String prefix = key + ":";
        for (String line : frontmatter.split("\n")) {
            if (line.startsWith(prefix)) {
                return stripQuotes(line.substring(prefix.length()).trim());
            }
        }
        throw new AssertionError("missing frontmatter key " + key + " in " + page);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String lineValue(String content, String prefix) {
        return content.lines()
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing line prefix " + prefix));
    }

    private static String stripTicks(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("`") && trimmed.endsWith("`")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static List<ClaimBlock> claimBlocks() throws IOException {
        List<ClaimBlock> blocks = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(WIKI_ROOT)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()) {
                Path page = WIKI_ROOT.relativize(path);
                String content = Files.readString(path, StandardCharsets.UTF_8);
                Matcher matcher = CLAIM_BLOCK.matcher(content);
                int index = 0;
                while (matcher.find()) {
                    index++;
                    blocks.add(new ClaimBlock(page, index, JSON.readTree(matcher.group(1))));
                }
            }
        }
        return blocks;
    }

    private static Map<String, Path> evidenceRegistry() throws IOException {
        String content = Files.readString(WIKI_ROOT.resolve(REGISTRY_PAGE), StandardCharsets.UTF_8);
        Matcher matcher = REGISTRY_BLOCK.matcher(content);
        assertTrue(matcher.find(), "EVIDENCE-REGISTRY.md must contain a talos-wiki-evidence-registry block");
        JsonNode registry = JSON.readTree(matcher.group(1));
        assertEquals("talos.wikiEvidenceRegistry.v1", registry.path("schema").asText());
        JsonNode entries = registry.path("entries");
        assertTrue(entries.isArray(), "evidence registry entries must be an array");
        Map<String, Path> result = new LinkedHashMap<>();
        for (JsonNode entry : entries) {
            String id = requiredText(entry, "id", "evidence registry");
            assertTrue(result.put(id, Path.of(requiredText(entry, "path", id)).normalize()) == null,
                    "duplicate evidence registry id: " + id);
        }
        return result;
    }

    private static ClaimBlock currentStateClaimBlock() throws IOException {
        return claimBlocks().stream()
                .filter(block -> block.page().equals(CURRENT_STATE_PAGE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CURRENT-STATE.md must contain a talos-wiki-claims block"));
    }

    private record ClaimBlock(Path page, int index, JsonNode json) {
        String label() {
            return page + " claim-block " + index;
        }

        String label(String id) {
            return label() + " claim " + id;
        }

        String label(String id, String path, String pointer) {
            return label(id) + " path " + path + " pointer " + pointer;
        }
    }
}
