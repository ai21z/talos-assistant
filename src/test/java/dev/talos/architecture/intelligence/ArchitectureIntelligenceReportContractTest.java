package dev.talos.architecture.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Architecture intelligence report contract")
class ArchitectureIntelligenceReportContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of("build", "reports", "talos", "architecture-intelligence", "current");
    private static final Path DATA = ROOT.resolve("data");

    private static final List<String> MARKDOWN_REPORTS = List.of(
            "00-ARCHITECTURE-INTELLIGENCE-REPORT.md",
            "01-run-manifest.md",
            "02-wave5-roadmap-alignment.md",
            "03-package-boundary-and-cycle-map.md",
            "04-manual-di-and-composition-map.md",
            "05-object-lifecycle-and-ownership-map.md",
            "06-method-call-hotspot-map.md",
            "07-static-global-and-threadlocal-map.md",
            "08-tool-execution-and-approval-gate-map.md",
            "09-trace-artifact-and-private-content-map.md",
            "10-coverage-qodana-hotspot-overlay.md",
            "11-wave5-ticket-sequence.md",
            "12-jdeps-cross-check.md",
            "13-toolchain-and-qodana-readiness.md");

    private static final List<String> JSON_REPORTS = List.of(
            "run-manifest.json",
            "dependency-cycle-map.json",
            "manual-di-composition-map.json",
            "lifecycle-ownership-map.json",
            "method-call-hotspot-map.json",
            "static-global-threadlocal-map.json",
            "approval-tool-execution-map.json",
            "trace-privacy-map.json",
            "coverage-qodana-overlay.json",
            "wave5-sequence-recommendations.json",
            "jdeps-cross-check.json",
            "toolchain-readiness.json");

    @BeforeAll
    static void generateReportSuite() throws IOException {
        ArchitectureIntelligenceReporter.generate();
    }

    @Test
    @DisplayName("generates the required deterministic Markdown and JSON report set")
    void generatesRequiredReportSet() throws IOException {
        for (String report : MARKDOWN_REPORTS) {
            Path path = ROOT.resolve(report);
            assertTrue(Files.isRegularFile(path), "missing Markdown report: " + path);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            assertFalse(content.isBlank(), "empty Markdown report: " + path);
            assertFalse(content.contains("generatedAt"), "reports must not contain wall-clock generatedAt fields");
            assertFalse(content.matches("(?s).*20\\d\\d-\\d\\d-\\d\\dT\\d\\d:.*"),
                    "reports must not contain ISO wall-clock timestamps: " + path);
        }

        for (String report : JSON_REPORTS) {
            Path path = DATA.resolve(report);
            assertTrue(Files.isRegularFile(path), "missing JSON report: " + path);
            JsonNode json = JSON.readTree(path.toFile());
            assertTrue(json.isObject(), "JSON report must be an object: " + path);
            assertFalse(json.has("generatedAt"), "JSON report must not contain generatedAt: " + path);
        }
        assertFalse(Files.exists(DATA.resolve("wave5-ticket-sequence.json")),
                "current report data must not retain obsolete generated files");

        JsonNode manifest = JSON.readTree(DATA.resolve("run-manifest.json").toFile());
        assertEquals("talos.architectureIntelligence.v1", manifest.path("schema").asText());
        assertEquals(expectedTalosVersion(), manifest.path("talosVersion").asText());
        assertTrue(manifest.path("reportPaths").isArray(), "manifest must enumerate report paths");
    }

    @Test
    @DisplayName("manual DI and hotspot reports are deduplicated top-level class facts")
    void deduplicatesTopLevelClassReports() throws IOException {
        JsonNode manualDi = JSON.readTree(DATA.resolve("manual-di-composition-map.json").toFile());
        JsonNode hotspots = JSON.readTree(DATA.resolve("method-call-hotspot-map.json").toFile());

        assertNoDuplicateRows(manualDi.path("rows"), "className", "manual DI rows");
        assertNoDuplicateRows(hotspots.path("rows"), "className", "hotspot rows");
        for (JsonNode row : manualDi.path("rows")) {
            assertTrue(row.has("outboundNewSites"),
                    "manual DI rows must label outbound construction sites honestly: " + row);
            assertFalse(row.has("newSites"),
                    "manual DI rows must not expose ambiguous newSites field: " + row);
        }
    }

    @Test
    @DisplayName("lifecycle report classifies known Wave 5 ownership surfaces")
    void classifiesKnownWave5LifecycleSurfaces() throws IOException {
        JsonNode lifecycle = JSON.readTree(DATA.resolve("lifecycle-ownership-map.json").toFile());
        Map<String, JsonNode> byClass = rowsBy(lifecycle.path("rows"), "className");

        assertScope(byClass, "dev.talos.cli.repl.TalosBootstrap", "APPLICATION");
        assertScope(byClass, "dev.talos.core.Config", "APPLICATION");
        assertScope(byClass, "dev.talos.cli.repl.Context", "SESSION");
        assertScope(byClass, "dev.talos.runtime.TurnProcessor", "SESSION");
        assertScope(byClass, "dev.talos.core.llm.LlmClient", "SESSION");
        assertScope(byClass, "dev.talos.cli.modes.AssistantTurnExecutor", "TURN");
        assertScope(byClass, "dev.talos.runtime.task.TaskContract", "TURN");
        assertScope(byClass, "dev.talos.runtime.ToolCallLoop", "TOOL_LOOP");
        assertScope(byClass, "dev.talos.runtime.toolcall.LoopState", "TOOL_LOOP");
        assertScope(byClass, "dev.talos.runtime.trace.LocalTurnTraceCapture", "TRACE");

        long classified = streamRows(lifecycle.path("rows")).stream()
                .filter(row -> !"UNKNOWN".equals(row.path("scope").asText()))
                .count();
        assertTrue(classified >= 20,
                "lifecycle report must classify enough rows to support Wave 5 planning, classified=" + classified);
    }

    @Test
    @DisplayName("static state report filters compiler noise and keeps real ThreadLocal ownership")
    void filtersStaticStateNoise() throws IOException {
        JsonNode staticState = JSON.readTree(DATA.resolve("static-global-threadlocal-map.json").toFile());
        List<String> snippets = streamRows(staticState.path("rows")).stream()
                .map(row -> row.path("snippet").asText())
                .toList();

        assertTrue(snippets.stream().noneMatch(snippet -> snippet.contains("$VALUES")
                        || snippet.contains("$SwitchMap")),
                "static report must filter compiler synthetic enum/switch fields: " + snippets);
        assertTrue(snippets.stream().noneMatch(snippet -> snippet.contains("static final class")),
                "static report must not treat static nested class declarations as state: " + snippets);
        assertTrue(snippets.stream().noneMatch(snippet -> snippet.startsWith("import ")
                        || snippet.contains("static web diagnostics")
                        || snippet.contains("runtime-owned static web")
                        || snippet.contains("safe(String")
                        || snippet.contains("INSTANCE_ID_TIMESTAMP")),
                "static report must filter imports, methods, constants, local variables, and string-literal source noise: " + snippets);
        assertTrue(streamRows(staticState.path("rows")).stream()
                        .anyMatch(row -> row.path("kind").asText().equals("THREAD_LOCAL_FIELD")
                                && row.path("snippet").asText().contains("LocalTurnTraceCapture.HOLDER")),
                "static report must retain real ThreadLocal ownership evidence");
    }

    @Test
    @DisplayName("quality overlay joins hotspots to raw coverage and validates Qodana artifacts")
    void overlaysHotspotsWithRawQualityEvidence() throws IOException {
        JsonNode overlay = JSON.readTree(DATA.resolve("coverage-qodana-overlay.json").toFile());
        JsonNode hotspotOverlays = overlay.path("hotspotOverlays");
        assertTrue(hotspotOverlays.isArray() && hotspotOverlays.size() > 0,
                "quality overlay must contain class hotspot overlay rows");
        assertTrue(streamRows(hotspotOverlays).stream()
                        .anyMatch(row -> row.path("className").asText()
                                .equals("dev.talos.cli.modes.AssistantTurnExecutor")
                                && !row.path("coverageStatus").asText().isBlank()),
                "quality overlay must join hotspot classes to coverage evidence where available");

        JsonNode qodana = streamRows(overlay.path("evidenceRows")).stream()
                .filter(row -> row.path("input").asText().equals("qodana-summary"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing qodana-summary evidence row"));
        assertTrue(qodana.has("rawArtifactStatus"),
                "Qodana evidence row must validate raw artifact paths, not only summary JSON");
        assertTrue(
                Set.of("RAW_ARTIFACT_MISSING", "PRESENT")
                        .contains(qodana.path("rawArtifactStatus").asText()),
                "Qodana raw artifact status must come from a generated summary and be either present or explicitly missing");
    }

    @Test
    @DisplayName("jdeps report classifies incomplete classpath output as advisory partial evidence")
    void classifiesJdepsPartialClasspath() throws IOException {
        JsonNode jdeps = JSON.readTree(DATA.resolve("jdeps-cross-check.json").toFile());
        assertTrue(Set.of("COMPLETE", "PARTIAL_CLASSPATH", "FAILED", "UNAVAILABLE")
                        .contains(jdeps.path("status").asText()),
                "jdeps status must use the architecture intelligence evidence taxonomy: " + jdeps);
        boolean hasNotFound = streamRows(jdeps.path("lines")).stream()
                .anyMatch(line -> line.asText().contains("not found"));
        if (hasNotFound) {
            assertEquals("PARTIAL_CLASSPATH", jdeps.path("status").asText(),
                    "jdeps output containing 'not found' must be classified as partial classpath");
        } else {
            assertEquals("COMPLETE", jdeps.path("status").asText(),
                    "jdeps output without missing dependencies should be classified as complete");
        }
    }

    @Test
    @DisplayName("Wave 5 priority index is decomposed and explained")
    void explainsWave5PriorityIndex() throws IOException {
        JsonNode sequence = JSON.readTree(DATA.resolve("wave5-sequence-recommendations.json").toFile());
        assertTrue(sequence.has("scoreModel"), "Wave 5 sequence JSON must explain the score model");
        JsonNode scoreModel = sequence.path("scoreModel");
        assertEquals(5, scoreModel.path("callVolumeDivisor").asInt());
        assertEquals(8, scoreModel.path("lifecycleWeight").asInt());
        assertEquals(30, scoreModel.path("approvalToolWeight").asInt());
        assertEquals(25, scoreModel.path("tracePrivacyWeight").asInt());
        assertTrue(scoreModel.path("description").asText().toLowerCase().contains("unnormalized priority index"),
                "score model description must identify the value as an unnormalized priority index");
        String formula = scoreModel.path("formula").asText();
        assertTrue(formula.contains("priorityIndex ="), "score model formula must name priorityIndex");
        assertTrue(formula.contains("fanOut + fanIn"), "score model formula must include fan-out/fan-in");
        assertTrue(formula.contains("floor(callsFrom / 5)"), "score model formula must include callsFrom divisor");
        assertTrue(formula.contains("floor(callsTo / 5)"), "score model formula must include callsTo divisor");
        assertTrue(formula.contains("lifecyclePriority(scope) * 8"),
                "score model formula must include lifecycle component");
        assertTrue(formula.contains("30 if approval/tool owner"),
                "score model formula must include approval/tool component");
        assertTrue(formula.contains("25 if trace/privacy owner"),
                "score model formula must include trace/privacy component");

        JsonNode assistant = rowsBy(sequence.path("rows"), "candidate")
                .get("dev.talos.cli.modes.AssistantTurnExecutor");
        assertTrue(assistant != null, "missing AssistantTurnExecutor sequence row");
        for (JsonNode row : streamRows(sequence.path("rows"))) {
            assertFalse(row.has("score"), "Wave 5 rows must not expose ambiguous score field: " + row);
            JsonNode breakdown = row.path("scoreBreakdown");
            assertTrue(breakdown.isObject(), "Wave 5 rows must include scoreBreakdown: " + row);
            assertTrue(breakdown.has("hotspot"), "scoreBreakdown must include hotspot: " + row);
            assertTrue(breakdown.has("lifecycle"), "scoreBreakdown must include lifecycle: " + row);
            assertTrue(breakdown.has("approvalTool"), "scoreBreakdown must include approvalTool: " + row);
            assertTrue(breakdown.has("tracePrivacy"), "scoreBreakdown must include tracePrivacy: " + row);
            assertTrue(breakdown.has("total"), "scoreBreakdown must include total: " + row);
            int expectedTotal = breakdown.path("hotspot").asInt()
                    + breakdown.path("lifecycle").asInt()
                    + breakdown.path("approvalTool").asInt()
                    + breakdown.path("tracePrivacy").asInt();
            assertEquals(expectedTotal, breakdown.path("total").asInt(),
                    "scoreBreakdown total must equal component sum: " + row);
            assertEquals(expectedTotal, row.path("priorityIndex").asInt(),
                    "priorityIndex must equal scoreBreakdown total: " + row);
        }

        String main = Files.readString(ROOT.resolve("00-ARCHITECTURE-INTELLIGENCE-REPORT.md"),
                StandardCharsets.UTF_8).toLowerCase();
        String sequenceReport = Files.readString(ROOT.resolve("11-wave5-ticket-sequence.md"),
                StandardCharsets.UTF_8).toLowerCase();
        assertTrue(main.contains("unnormalized priority index"),
                "main report must explain that the Wave 5 value is an unnormalized priority index");
        assertTrue(sequenceReport.contains("unnormalized priority index"),
                "Wave 5 sequence report must explain that the value is an unnormalized priority index");
        assertTrue(sequenceReport.contains("| rank | candidate | hotspot | lifecycle | approval | trace/privacy | priority index |"),
                "Wave 5 sequence report must expose score component columns");
    }

    @Test
    @DisplayName("approval and privacy thematic maps include bounded source evidence hits")
    void thematicMapsIncludeEvidenceHits() throws IOException {
        JsonNode approval = JSON.readTree(DATA.resolve("approval-tool-execution-map.json").toFile());
        JsonNode privacy = JSON.readTree(DATA.resolve("trace-privacy-map.json").toFile());
        JsonNode approvalAssistant = rowsBy(approval.path("rows"), "owner")
                .get("dev.talos.cli.modes.AssistantTurnExecutor");
        JsonNode privacyAssistant = rowsBy(privacy.path("rows"), "owner")
                .get("dev.talos.cli.modes.AssistantTurnExecutor");

        assertEvidenceHits(approvalAssistant, "approval/tool AssistantTurnExecutor");
        assertEvidenceHits(privacyAssistant, "trace/privacy AssistantTurnExecutor");
    }

    private static List<JsonNode> streamRows(JsonNode rows) {
        List<JsonNode> result = new ArrayList<>();
        rows.forEach(result::add);
        return result;
    }

    private static String expectedTalosVersion() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("gradle.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        String version = properties.getProperty("talosVersion");
        assertTrue(version != null && !version.isBlank(), "gradle.properties must declare talosVersion");
        return version.trim();
    }

    private static Map<String, JsonNode> rowsBy(JsonNode rows, String key) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.path(key).asText(), row));
        return result;
    }

    private static void assertNoDuplicateRows(JsonNode rows, String key, String label) {
        Map<String, Long> counts = streamRows(rows).stream()
                .collect(Collectors.groupingBy(row -> row.path(key).asText(), LinkedHashMap::new, Collectors.counting()));
        List<String> duplicates = counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();
        assertTrue(duplicates.isEmpty(), label + " must not contain duplicate " + key + " rows: " + duplicates);
    }

    private static void assertScope(Map<String, JsonNode> byClass, String className, String expectedScope) {
        JsonNode row = byClass.get(className);
        assertTrue(row != null, "missing lifecycle row for " + className);
        assertEquals(expectedScope, row.path("scope").asText(), "wrong lifecycle scope for " + className);
        assertEquals("INFERRED_REVIEW", row.path("confidence").asText(),
                "review-classified lifecycle rows must not pretend to be runtime evidence for " + className);
    }

    private static void assertEvidenceHits(JsonNode row, String label) {
        assertTrue(row != null, "missing thematic row for " + label);
        JsonNode hits = row.path("evidenceHits");
        assertTrue(hits.isArray() && hits.size() > 0, label + " must include evidence hits: " + row);
        assertTrue(hits.size() <= 3, label + " evidence hits must be capped: " + row);
        for (JsonNode hit : hits) {
            assertFalse(hit.path("file").asText().isBlank(), label + " evidence hit must include file: " + hit);
            assertTrue(hit.path("line").asInt() > 0, label + " evidence hit must include line: " + hit);
            assertFalse(hit.path("signal").asText().isBlank(), label + " evidence hit must include signal: " + hit);
            assertFalse(hit.path("snippet").asText().isBlank(), label + " evidence hit must include snippet: " + hit);
            assertFalse(hit.path("snippet").asText().startsWith("import "),
                    label + " evidence hit should prefer behavioral source over imports: " + hit);
        }
    }
}
