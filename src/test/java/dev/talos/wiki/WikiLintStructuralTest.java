package dev.talos.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Talos living evidence wiki structural lint")
class WikiLintStructuralTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path WIKI_ROOT = Path.of("work-cycle-docs", "wiki");
    private static final List<Path> REQUIRED_PAGES = List.of(
            Path.of("INDEX.md"),
            Path.of("CURRENT-STATE.md"),
            Path.of("EVIDENCE-REGISTRY.md"),
            Path.of("WIKI-SCHEMA.md"),
            Path.of("LOG.md"),
            Path.of("concepts", "living-evidence-wiki.md"));
    private static final Set<String> CONFIDENCE_LABELS = Set.of(
            "UNKNOWN",
            "INFERRED_REVIEW",
            "DETERMINISTIC_STATIC",
            "DETERMINISTIC_GENERATED",
            "OBSERVED_RUNTIME",
            "GATED");
    private static final Set<String> EVIDENCE_TYPES = Set.of(
            "repo_file",
            "generated_report",
            "ticket",
            "external_source",
            "research_note");
    private static final Set<String> TRUST_TIERS = Set.of(
            "REPO_POLICY",
            "REPO_STATIC",
            "GENERATED_REPORT",
            "TICKET",
            "RELEASE_LEDGER",
            "EXTERNAL_SOURCE",
            "MODEL_RESEARCH_NOTE",
            "CHAT_SUMMARY");
    private static final List<String> CONFIDENCE_ORDER = List.of(
            "UNKNOWN",
            "INFERRED_REVIEW",
            "DETERMINISTIC_STATIC",
            "DETERMINISTIC_GENERATED",
            "OBSERVED_RUNTIME",
            "GATED");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]\\n]+]\\(([^)\\n]+)\\)");
    private static final Pattern REGISTRY_BLOCK = Pattern.compile(
            "(?m)^```talos-wiki-evidence-registry\\R([\\s\\S]*?)\\R```\\s*$");

    @Test
    @DisplayName("required wiki files exist")
    void requiredWikiFilesExist() throws IOException {
        for (Path page : REQUIRED_PAGES) {
            Path path = WIKI_ROOT.resolve(page);
            assertTrue(Files.isRegularFile(path), "missing wiki page: " + path);
            assertFalse(Files.readString(path, StandardCharsets.UTF_8).isBlank(),
                    "wiki page must not be empty: " + path);
        }
    }

    @Test
    @DisplayName("wiki frontmatter uses the fixed schema and confidence ladder")
    void wikiFrontmatterUsesFixedSchema() throws IOException {
        for (Path page : REQUIRED_PAGES) {
            String content = readPage(page);
            String frontmatter = frontmatter(content, page);
            assertEquals("talos.wikiPage.v1", scalar(frontmatter, "wiki_schema", page));
            assertFalse(scalar(frontmatter, "title", page).isBlank(), "title is required: " + page);
            assertFalse(scalar(frontmatter, "kind", page).isBlank(), "kind is required: " + page);
            assertFalse(scalar(frontmatter, "status", page).isBlank(), "status is required: " + page);
            assertFalse(scalar(frontmatter, "last_verified_commit", page).isBlank(),
                    "last_verified_commit is required: " + page);
            String confidence = scalar(frontmatter, "min_confidence", page);
            assertFalse(confidence.equalsIgnoreCase("mixed"), "min_confidence must never be mixed: " + page);
            assertTrue(CONFIDENCE_LABELS.contains(confidence),
                    "invalid min_confidence on " + page + ": " + confidence);
            assertConfidenceHistogram(frontmatter, confidence, page);
        }
    }

    @Test
    @DisplayName("current-state and concept pages declare evidence inputs")
    void evidenceBearingPagesDeclareInputs() throws IOException {
        for (Path page : List.of(Path.of("CURRENT-STATE.md"), Path.of("concepts", "living-evidence-wiki.md"))) {
            String frontmatter = frontmatter(readPage(page), page);
            assertTrue(frontmatter.contains("evidence_inputs:"), "missing evidence_inputs: " + page);
            assertTrue(frontmatter.contains("- type:"), "evidence_inputs must contain at least one type: " + page);
            assertTrue(frontmatter.contains("    ref:"), "evidence_inputs must contain at least one ref: " + page);
        }
    }

    @Test
    @DisplayName("local Markdown links resolve")
    void localMarkdownLinksResolve() throws IOException {
        for (Path page : REQUIRED_PAGES) {
            Path path = WIKI_ROOT.resolve(page);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            Matcher matcher = MARKDOWN_LINK.matcher(content);
            while (matcher.find()) {
                String rawTarget = matcher.group(1).trim();
                if (isExternalOrAnchor(rawTarget)) {
                    continue;
                }
                String target = stripAnchor(decode(rawTarget));
                if (target.isBlank()) {
                    continue;
                }
                Path resolved = path.getParent().resolve(target).normalize();
                assertTrue(Files.exists(resolved), "broken local Markdown link in " + path + ": " + rawTarget);
            }
        }
    }

    @Test
    @DisplayName("index lists every non-index wiki page")
    void indexListsEveryNonIndexPage() throws IOException {
        String index = readPage(Path.of("INDEX.md"));
        for (Path page : REQUIRED_PAGES) {
            if (page.equals(Path.of("INDEX.md"))) {
                continue;
            }
            String normalized = page.toString().replace('\\', '/');
            assertTrue(index.contains(normalized), "INDEX.md must list " + normalized);
        }
    }

    @Test
    @DisplayName("current state records run identity and next move")
    void currentStateRecordsRunIdentityAndNextMove() throws IOException {
        String currentState = readPage(Path.of("CURRENT-STATE.md"));
        assertLineHasValue(currentState, "- Branch:");
        String commit = stripTicks(lineValue(currentState, "- Commit:"));
        assertTrue(commit.matches("[0-9a-f]{40}"), "commit must be a full SHA: " + commit);
        assertEquals(expectedTalosVersion(), stripTicks(lineValue(currentState, "- Talos version:")));
        assertLineHasValue(currentState, "- Active tickets:");
        assertLineHasValue(currentState, "- Active wave context:");
        assertLineHasValue(currentState, "- Known caveats:");
        assertLineHasValue(currentState, "- Next move:");
    }

    @Test
    @DisplayName("evidence registry declares only active generated evidence refreshed by the wiki close gate")
    void evidenceRegistryDeclaresActiveGeneratedEvidence() throws IOException {
        JsonNode registry = evidenceRegistry();
        assertEquals("talos.wikiEvidenceRegistry.v1", registry.path("schema").asText());
        JsonNode entries = registry.path("entries");
        assertTrue(entries.isArray() && entries.size() > 0, "registry entries must be a non-empty array");

        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode entry : entries) {
            String id = requiredText(entry, "id", "registry entry");
            assertTrue(ids.add(id), "duplicate evidence registry id: " + id);
            assertTrue(EVIDENCE_TYPES.contains(requiredText(entry, "type", id)), "invalid evidence type: " + entry);
            assertTrue(TRUST_TIERS.contains(requiredText(entry, "trustTier", id)), "invalid trust tier: " + entry);
            String path = requiredText(entry, "path", id);
            Path normalized = Path.of(path).normalize();
            assertFalse(normalized.isAbsolute(), id + " path must be repo-relative: " + path);
            assertFalse(normalized.startsWith(".."), id + " path must not escape the repo: " + path);
            assertFalse(requiredText(entry, "description", id).isBlank(), id + " description is required");
        }

        assertEquals(Set.of(
                        "architectureIntelligence.runManifest",
                        "architectureIntelligence.wave5Sequence"),
                ids,
                "active registry entries must be limited to evidence refreshed by the wiki close gate");
    }

    private static String readPage(Path page) throws IOException {
        return Files.readString(WIKI_ROOT.resolve(page), StandardCharsets.UTF_8);
    }

    private static JsonNode evidenceRegistry() throws IOException {
        String content = readPage(Path.of("EVIDENCE-REGISTRY.md"));
        Matcher matcher = REGISTRY_BLOCK.matcher(content);
        assertTrue(matcher.find(), "EVIDENCE-REGISTRY.md must contain a talos-wiki-evidence-registry block");
        String json = matcher.group(1);
        assertFalse(matcher.find(), "EVIDENCE-REGISTRY.md must contain exactly one evidence registry block");
        return JSON.readTree(json);
    }

    private static String requiredText(JsonNode node, String field, String label) {
        assertTrue(node.has(field), label + " missing field: " + field);
        String value = node.path(field).asText();
        assertFalse(value.isBlank(), label + " blank field: " + field);
        return value;
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

    private static void assertConfidenceHistogram(String frontmatter, String minConfidence, Path page) {
        Map<String, Integer> histogram = confidenceHistogram(frontmatter, page);
        int nonzeroBuckets = 0;
        String lowestNonzero = null;
        for (String label : CONFIDENCE_ORDER) {
            assertTrue(histogram.containsKey(label), "confidence_histogram missing " + label + ": " + page);
            int count = histogram.get(label);
            assertTrue(count >= 0, "confidence_histogram count must be nonnegative for " + label + ": " + page);
            if (count > 0) {
                nonzeroBuckets++;
                if (lowestNonzero == null) {
                    lowestNonzero = label;
                }
            }
        }
        assertTrue(nonzeroBuckets > 0, "confidence_histogram must contain at least one nonzero bucket: " + page);
        assertEquals(lowestNonzero, minConfidence,
                "min_confidence must equal lowest nonzero confidence_histogram bucket: " + page);
    }

    private static Map<String, Integer> confidenceHistogram(String frontmatter, Path page) {
        Map<String, Integer> result = new LinkedHashMap<>();
        String[] lines = frontmatter.split("\n");
        boolean inside = false;
        for (String line : lines) {
            if (line.equals("confidence_histogram:")) {
                inside = true;
                continue;
            }
            if (inside && !line.startsWith("  ")) {
                break;
            }
            if (inside) {
                String trimmed = line.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                int separator = trimmed.indexOf(':');
                assertTrue(separator > 0, "malformed confidence_histogram row in " + page + ": " + line);
                String label = trimmed.substring(0, separator);
                String value = trimmed.substring(separator + 1).trim();
                assertTrue(CONFIDENCE_LABELS.contains(label),
                        "invalid confidence_histogram label in " + page + ": " + label);
                try {
                    result.put(label, Integer.parseInt(value));
                } catch (NumberFormatException ex) {
                    throw new AssertionError(
                            "confidence_histogram value must be an integer in " + page + ": " + line, ex);
                }
            }
        }
        assertFalse(result.isEmpty(), "missing confidence_histogram: " + page);
        return result;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isExternalOrAnchor(String target) {
        String lower = target.toLowerCase();
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("mailto:")
                || lower.startsWith("#");
    }

    private static String stripAnchor(String target) {
        int anchor = target.indexOf('#');
        return anchor >= 0 ? target.substring(0, anchor) : target;
    }

    private static String decode(String target) {
        return URLDecoder.decode(target, StandardCharsets.UTF_8);
    }

    private static void assertLineHasValue(String content, String prefix) {
        assertFalse(lineValue(content, prefix).isBlank(), prefix + " must have a value");
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

    private static String expectedTalosVersion() throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of("gradle.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String version = properties.getProperty("talosVersion");
        assertTrue(version != null && !version.isBlank(), "gradle.properties must declare talosVersion");
        return version.trim();
    }
}
