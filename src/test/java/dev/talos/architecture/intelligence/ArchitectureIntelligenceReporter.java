package dev.talos.architecture.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Report-only architecture intelligence generator for Wave 5 planning.
 *
 * <p>The generator intentionally lives in test sources beside the existing
 * architecture reports. It must not influence product runtime behavior.
 */
final class ArchitectureIntelligenceReporter {

    private static final String ROOT = "dev.talos";
    private static final String ROOT_PREFIX = ROOT + ".";
    private static final int TOP_N = 25;
    private static final int CALL_VOLUME_DIVISOR = 5;
    private static final int LIFECYCLE_WEIGHT = 8;
    private static final int APPROVAL_TOOL_WEIGHT = 30;
    private static final int TRACE_PRIVACY_WEIGHT = 25;
    private static final int THEMATIC_EVIDENCE_HIT_LIMIT = 3;

    private static final Path REPORT_ROOT = Path.of(
            "build", "reports", "talos", "architecture-intelligence", "current");
    private static final Path DATA_ROOT = REPORT_ROOT.resolve("data");

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private static final List<String> TOP_LEVEL_PACKAGES = List.of(
            "api", "app", "cli", "core", "engine", "runtime", "safety", "spi", "tools");

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

    private ArchitectureIntelligenceReporter() {
    }

    static void generate() throws IOException {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT);

        SourceIndex sourceIndex = SourceIndex.scan(Path.of("src", "main", "java"));
        GraphModel graph = GraphModel.from(classes);
        Toolchain toolchain = Toolchain.detect();
        JdepsResult jdeps = JdepsResult.runIfAvailable(toolchain);
        ManualDiModel manualDi = ManualDiModel.from(classes, sourceIndex);
        LifecycleModel lifecycle = LifecycleModel.from(classes, sourceIndex, graph);
        HotspotModel hotspots = HotspotModel.from(classes, graph);
        QualityOverlay quality = QualityOverlay.read(Path.of("build", "reports", "talos"), hotspots);
        StaticStateModel staticState = StaticStateModel.from(classes, sourceIndex);
        ThematicModel approvalTool = ThematicModel.approvalTool(sourceIndex, graph);
        ThematicModel tracePrivacy = ThematicModel.tracePrivacy(sourceIndex, graph);

        IntelligenceModel model = new IntelligenceModel(
                RunManifest.current(),
                graph,
                sourceIndex,
                quality,
                toolchain,
                jdeps,
                manualDi,
                lifecycle,
                hotspots,
                staticState,
                approvalTool,
                tracePrivacy);

        cleanCurrentReportDirectory();
        Files.createDirectories(REPORT_ROOT);
        Files.createDirectories(DATA_ROOT);

        writeJson("run-manifest.json", model.manifest().toJson());
        writeJson("dependency-cycle-map.json", model.graph().toJson());
        writeJson("manual-di-composition-map.json", model.manualDi().toJson());
        writeJson("lifecycle-ownership-map.json", model.lifecycle().toJson());
        writeJson("method-call-hotspot-map.json", model.hotspots().toJson());
        writeJson("static-global-threadlocal-map.json", model.staticState().toJson());
        writeJson("approval-tool-execution-map.json", model.approvalTool().toJson());
        writeJson("trace-privacy-map.json", model.tracePrivacy().toJson());
        writeJson("coverage-qodana-overlay.json", model.quality().toJson());
        writeJson("wave5-sequence-recommendations.json", Wave5Sequence.from(model).toJson());
        writeJson("jdeps-cross-check.json", model.jdeps().toJson());
        writeJson("toolchain-readiness.json", model.toolchain().toJson());

        writeMarkdown("00-ARCHITECTURE-INTELLIGENCE-REPORT.md", renderMainReport(model));
        writeMarkdown("01-run-manifest.md", renderRunManifest(model));
        writeMarkdown("02-wave5-roadmap-alignment.md", renderWave5Roadmap(model));
        writeMarkdown("03-package-boundary-and-cycle-map.md", renderPackageBoundary(model));
        writeMarkdown("04-manual-di-and-composition-map.md", renderManualDi(model));
        writeMarkdown("05-object-lifecycle-and-ownership-map.md", renderLifecycle(model));
        writeMarkdown("06-method-call-hotspot-map.md", renderHotspots(model));
        writeMarkdown("07-static-global-and-threadlocal-map.md", renderStaticState(model));
        writeMarkdown("08-tool-execution-and-approval-gate-map.md", renderThematic(
                "Tool Execution And Approval Gate Map", model.approvalTool()));
        writeMarkdown("09-trace-artifact-and-private-content-map.md", renderThematic(
                "Trace Artifact And Private Content Map", model.tracePrivacy()));
        writeMarkdown("10-coverage-qodana-hotspot-overlay.md", renderQualityOverlay(model));
        writeMarkdown("11-wave5-ticket-sequence.md", renderWave5Sequence(model));
        writeMarkdown("12-jdeps-cross-check.md", renderJdeps(model));
        writeMarkdown("13-toolchain-and-qodana-readiness.md", renderToolchain(model));

        assertRequiredFiles();
    }

    private static void writeMarkdown(String name, String markdown) throws IOException {
        Path file = REPORT_ROOT.resolve(name);
        Files.writeString(file, markdown, StandardCharsets.UTF_8);
    }

    private static void cleanCurrentReportDirectory() throws IOException {
        if (!Files.exists(REPORT_ROOT)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(REPORT_ROOT)) {
            List<Path> existing = paths
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : existing) {
                if (!path.equals(REPORT_ROOT)) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void writeJson(String name, Object payload) throws IOException {
        Path file = DATA_ROOT.resolve(name);
        JSON.writeValue(file.toFile(), payload);
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8) + "\n", StandardCharsets.UTF_8);
    }

    private static void assertRequiredFiles() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String name : MARKDOWN_REPORTS) {
            Path path = REPORT_ROOT.resolve(name);
            if (!Files.isRegularFile(path) || Files.size(path) == 0L) {
                missing.add(path.toString());
            }
        }
        for (String name : JSON_REPORTS) {
            Path path = DATA_ROOT.resolve(name);
            if (!Files.isRegularFile(path) || Files.size(path) == 0L) {
                missing.add(path.toString());
            } else {
                JSON.readTree(path.toFile());
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("Missing required architecture intelligence report files: " + missing);
        }
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private static String renderMainReport(IntelligenceModel model) {
        Wave5Sequence sequence = Wave5Sequence.from(model);
        StringBuilder sb = header("Talos Architecture Intelligence Report");
        sb.append("Report-only. Generated by `dev.talos.architecture.intelligence.ArchitectureIntelligenceReporter`. ")
                .append("This is architecture evidence for Wave 5 planning, not a refactor and not a release gate. ")
                .append("Content is deterministic: no wall-clock timestamp is written.\n\n");
        sb.append("## Identity\n\n");
        sb.append("- branch: `").append(model.manifest().branch()).append("`\n");
        sb.append("- commit: `").append(model.manifest().commit()).append("`\n");
        sb.append("- Talos version: `").append(model.manifest().talosVersion()).append("`\n");
        sb.append("- imported production classes: ").append(model.graph().importedClasses()).append("\n");
        sb.append("- source files scanned: ").append(model.sourceIndex().files().size()).append("\n\n");

        sb.append("## Executive Story\n\n");
        sb.append("Wave 5 should start from ownership risk, not from package names. The highest risk ")
                .append("areas are classes that combine broad fan-out, heavy method/constructor traffic, ")
                .append("approval/tool semantics, trace/privacy semantics, and ambiguous lifecycle scope. ")
                .append("This suite labels deterministic facts separately from reviewed inferences so a ")
                .append("future refactor can distinguish bytecode evidence from architectural judgment.\n\n");

        sb.append("## Highest Priority Wave 5 Candidates\n\n");
        appendScoreModelSection(sb, sequence.scoreModel());
        appendSequenceTable(sb, sequence.rows(), 10, false);

        sb.append("## Evidence Caveats\n\n");
        sb.append("- lifecycle scope labels are inferred unless marked `DETERMINISTIC_STATIC` or `OBSERVED_RUNTIME`\n");
        sb.append("- Qodana is read-only input; this report does not run or modify Qodana\n");
        sb.append("- jdeps is advisory and never overrides ArchUnit source-level evidence\n");
        sb.append("- CodeQL, JFR custom events, Error Prone, NullAway, and JSpecify remain follow-on work\n\n");

        sb.append("## Report Index\n\n");
        for (String report : MARKDOWN_REPORTS.subList(1, MARKDOWN_REPORTS.size())) {
            sb.append("- `").append(report).append("`\n");
        }
        return sb.toString();
    }

    private static String renderRunManifest(IntelligenceModel model) {
        StringBuilder sb = header("Run Manifest");
        sb.append("| Field | Value |\n|---|---|\n");
        sb.append("| Schema | `talos.architectureIntelligence.v1` |\n");
        sb.append("| Branch | `").append(model.manifest().branch()).append("` |\n");
        sb.append("| Commit | `").append(model.manifest().commit()).append("` |\n");
        sb.append("| Talos version | `").append(model.manifest().talosVersion()).append("` |\n");
        sb.append("| Imported production classes | ").append(model.graph().importedClasses()).append(" |\n");
        sb.append("| Source files scanned | ").append(model.sourceIndex().files().size()).append(" |\n");
        sb.append("| Output root | `").append(REPORT_ROOT).append("` |\n");
        sb.append("\n## Generated Reports\n\n");
        for (String report : MARKDOWN_REPORTS) {
            sb.append("- `").append(report).append("`\n");
        }
        sb.append("\n## Generated JSON\n\n");
        for (String report : JSON_REPORTS) {
            sb.append("- `data/").append(report).append("`\n");
        }
        return sb.toString();
    }

    private static String renderWave5Roadmap(IntelligenceModel model) {
        StringBuilder sb = header("Wave 5 Roadmap Alignment");
        sb.append("Wave 5 is controlled architecture surgery. The sequence below prioritizes ")
                .append("ownership clarity before package movement.\n\n");
        sb.append("## Discipline\n\n");
        sb.append("- map lifecycle scope before moving a class\n");
        sb.append("- move policy ownership before moving presentation adapters\n");
        sb.append("- preserve approval, trace, prompt-debug, and privacy invariants\n");
        sb.append("- convert confirmed findings into deterministic tests before broad refactors\n\n");
        sb.append("## Candidate Sequence\n\n");
        Wave5Sequence sequence = Wave5Sequence.from(model);
        appendScoreModelSection(sb, sequence.scoreModel());
        appendSequenceTable(sb, sequence.rows(), 15, false);
        return sb.toString();
    }

    private static String renderPackageBoundary(IntelligenceModel model) {
        StringBuilder sb = header("Package Boundary And Cycle Map");
        GraphModel graph = model.graph();
        sb.append("## Top-Level Package Edge Matrix\n\n");
        appendMatrix(sb, graph.packageEdgeCounts());
        sb.append("\n## Strongly Connected Components\n\n");
        if (graph.sccs().isEmpty()) {
            sb.append("No non-trivial top-level package SCCs detected.\n\n");
        } else {
            sb.append("| SCC | Size |\n|---|---:|\n");
            for (List<String> scc : graph.sccs()) {
                sb.append("| `").append(String.join("`, `", scc)).append("` | ").append(scc.size()).append(" |\n");
            }
            sb.append("\n");
        }
        sb.append("## High Fan-In / Fan-Out Classes\n\n");
        appendHotspotTable(sb, graph.classHotspots(), 20);
        return sb.toString();
    }

    private static String renderManualDi(IntelligenceModel model) {
        StringBuilder sb = header("Manual DI And Composition Map");
        sb.append("Talos has manual Java wiring, so composition evidence is visible in constructor ")
                .append("calls, `new` sites, app/CLI packages, and likely registry/factory classes.\n\n");
        appendRows(sb, model.manualDi().rows(), List.of(
                "Class", "Outbound new sites", "Constructor calls", "Role hint", "Confidence"));
        return sb.toString();
    }

    private static String renderLifecycle(IntelligenceModel model) {
        StringBuilder sb = header("Object Lifecycle And Ownership Map");
        sb.append("Lifecycle ownership is part of architecture evidence. Static construction facts ")
                .append("are deterministic; intended scope is marked as reviewed inference unless runtime ")
                .append("evidence exists.\n\n");
        appendRows(sb, model.lifecycle().rows(),
                List.of("Class", "Scope", "Confidence", "Constructor callers", "Close/dispose hints", "Reason"));
        return sb.toString();
    }

    private static String renderHotspots(IntelligenceModel model) {
        StringBuilder sb = header("Method Call Hotspot Map");
        sb.append("Counts are ArchUnit bytecode-level method and constructor call evidence within `dev.talos`.\n\n");
        appendRows(sb, model.hotspots().rows(),
                List.of("Class", "Fan-out", "Fan-in", "Calls from", "Calls to", "Constructor calls", "Risk"));
        return sb.toString();
    }

    private static String renderStaticState(IntelligenceModel model) {
        StringBuilder sb = header("Static Global And ThreadLocal Map");
        sb.append("Source-scan hints flag state that can cross lifecycle boundaries. These are review ")
                .append("targets, not automatic defects.\n\n");
        appendRows(sb, model.staticState().rows(), List.of("File", "Line", "Kind", "Snippet", "Confidence"));
        return sb.toString();
    }

    private static String renderThematic(String title, ThematicModel model) {
        StringBuilder sb = header(title);
        sb.append(model.description()).append("\n\n");
        appendRows(sb, model.rows(), List.of("Class/File", "Signals", "Fan-out", "Fan-in", "Evidence", "Confidence"));
        return sb.toString();
    }

    private static String renderQualityOverlay(IntelligenceModel model) {
        StringBuilder sb = header("Coverage Qodana Hotspot Overlay");
        sb.append("Quality summaries are consumed as read-only inputs. Missing or stale inputs are ")
                .append("reported honestly and never repaired by this suite.\n\n");
        sb.append("## Evidence Inputs\n\n");
        appendRows(sb, model.quality().evidenceRows(),
                List.of("Input", "Status", "Version", "Key facts", "Path", "Raw artifact"));
        sb.append("## Hotspot Overlay\n\n");
        appendRows(sb, model.quality().hotspotOverlays(),
                List.of("Class", "Hotspot score", "Coverage", "Instruction", "Branch", "Qodana", "Risk"));
        return sb.toString();
    }

    private static String renderWave5Sequence(IntelligenceModel model) {
        StringBuilder sb = header("Wave 5 Ticket Sequence");
        sb.append("The ordering favors ownership safety before broad package motion.\n\n");
        Wave5Sequence sequence = Wave5Sequence.from(model);
        appendScoreModelSection(sb, sequence.scoreModel());
        appendSequenceTable(sb, sequence.rows(), 30, true);
        return sb.toString();
    }

    private static String renderJdeps(IntelligenceModel model) {
        StringBuilder sb = header("Jdeps Cross-Check");
        JdepsResult result = model.jdeps();
        sb.append("- status: `").append(result.status()).append("`\n");
        sb.append("- command: `").append(result.command()).append("`\n\n");
        if (result.lines().isEmpty()) {
            sb.append("No jdeps output captured. This is advisory only.\n");
        } else {
            sb.append("```text\n");
            for (String line : result.lines()) {
                sb.append(line).append("\n");
            }
            sb.append("```\n");
        }
        return sb.toString();
    }

    private static String renderToolchain(IntelligenceModel model) {
        StringBuilder sb = header("Toolchain And Qodana Readiness");
        sb.append("This report detects readiness only. It does not run Qodana analysis and does not ")
                .append("change Qodana configuration or outputs.\n\n");
        appendRows(sb, model.toolchain().rows(), List.of("Tool", "Status", "Path/Detail", "Role"));
        sb.append("\n## Qodana Evidence Input\n\n");
        QualityOverlay quality = model.quality();
        for (QualityRow row : quality.evidenceRows()) {
            if (row.input().equals("qodana-summary")) {
                sb.append("- status: `").append(row.status()).append("`\n");
                sb.append("- facts: ").append(row.keyFacts()).append("\n");
                sb.append("- path: `").append(row.path()).append("`\n");
                sb.append("- raw artifact: `").append(row.rawArtifactStatus()).append("`");
            }
        }
        return sb.toString();
    }

    private static StringBuilder header(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        return sb;
    }

    private static void appendRows(StringBuilder sb, List<? extends TableRow> rows, List<String> columns) {
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("|").append("---|".repeat(columns.size())).append("\n");
        if (rows.isEmpty()) {
            sb.append("| ").append("(none) |".repeat(columns.size())).append("\n\n");
            return;
        }
        for (TableRow row : rows) {
            List<String> cells = row.cells();
            sb.append("| ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(md(cells.size() > i ? cells.get(i) : ""));
            }
            sb.append(" |\n");
        }
        sb.append("\n");
    }

    private static void appendMatrix(StringBuilder sb, Map<String, Map<String, Integer>> matrix) {
        sb.append("| from \\ to |");
        for (String to : TOP_LEVEL_PACKAGES) {
            sb.append(" ").append(to).append(" |");
        }
        sb.append("\n|---|").append("---:|".repeat(TOP_LEVEL_PACKAGES.size())).append("\n");
        for (String from : TOP_LEVEL_PACKAGES) {
            sb.append("| `").append(from).append("` |");
            Map<String, Integer> row = matrix.getOrDefault(from, Map.of());
            for (String to : TOP_LEVEL_PACKAGES) {
                int count = row.getOrDefault(to, 0);
                sb.append(from.equals(to) ? " - |" : " ").append(from.equals(to) ? "" : (count == 0 ? "." : count))
                        .append(from.equals(to) ? "" : " |");
            }
            sb.append("\n");
        }
    }

    private static void appendHotspotTable(StringBuilder sb, List<ClassHotspot> rows, int limit) {
        sb.append("| Class | Fan-out | Fan-in | Package |\n|---|---:|---:|---|\n");
        for (ClassHotspot row : rows.subList(0, Math.min(limit, rows.size()))) {
            sb.append("| `").append(shortName(row.className())).append("` | ")
                    .append(row.fanOut()).append(" | ")
                    .append(row.fanIn()).append(" | `")
                    .append(row.packageName()).append("` |\n");
        }
        sb.append("\n");
    }

    private static void appendScoreModelSection(StringBuilder sb, Wave5ScoreModel scoreModel) {
        sb.append("## Scoring Model\n\n");
        sb.append("The Wave 5 value is an unnormalized priority index, not a grade or percentage. ")
                .append("It is only for ordering refactor review candidates on this commit.\n\n");
        sb.append("```text\n");
        sb.append(scoreModel.formula()).append("\n");
        sb.append("```\n\n");
        sb.append("Lifecycle priority weights: ");
        sb.append(scoreModel.lifecyclePriorities().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", ")));
        sb.append(".\n\n");
    }

    private static void appendSequenceTable(StringBuilder sb, List<Wave5Row> rows, int limit, boolean includeComponents) {
        if (includeComponents) {
            sb.append("| Rank | Candidate | Hotspot | Lifecycle | Approval | Trace/privacy | Priority index | Why first | Confidence |\n")
                    .append("|---:|---|---:|---:|---:|---:|---:|---|---|\n");
        } else {
            sb.append("| Rank | Candidate | Priority index | Why first | Confidence |\n|---:|---|---:|---|---|\n");
        }
        int count = Math.min(limit, rows.size());
        for (int i = 0; i < count; i++) {
            Wave5Row row = rows.get(i);
            sb.append("| ").append(i + 1).append(" | `").append(shortName(row.candidate())).append("` | ");
            if (includeComponents) {
                Wave5ScoreBreakdown breakdown = row.scoreBreakdown();
                sb.append(breakdown.hotspot()).append(" | ")
                        .append(breakdown.lifecycle()).append(" | ")
                        .append(breakdown.approvalTool()).append(" | ")
                        .append(breakdown.tracePrivacy()).append(" | ");
            }
            sb.append(row.priorityIndex()).append(" | ").append(md(row.reason())).append(" | `")
                    .append(row.confidence()).append("` |\n");
        }
        sb.append("\n");
    }

    private static String md(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", "<br>");
    }

    private static String thematicEvidenceSummary(List<ThematicEvidenceHit> hits) {
        if (hits.isEmpty()) {
            return "";
        }
        return hits.stream()
                .map(hit -> code(hit.file() + ":" + hit.line()) + " " + code(hit.signal()) + " " + md(hit.snippet()))
                .collect(Collectors.joining("<br>"));
    }

    // ---------------------------------------------------------------------
    // Model construction
    // ---------------------------------------------------------------------

    private record IntelligenceModel(
            RunManifest manifest,
            GraphModel graph,
            SourceIndex sourceIndex,
            QualityOverlay quality,
            Toolchain toolchain,
            JdepsResult jdeps,
            ManualDiModel manualDi,
            LifecycleModel lifecycle,
            HotspotModel hotspots,
            StaticStateModel staticState,
            ThematicModel approvalTool,
            ThematicModel tracePrivacy) {
    }

    private enum LifecycleScope {
        APPLICATION, WORKSPACE, SESSION, TURN, TOOL_LOOP, TOOL_CALL, TRACE, TEMPORARY, UNKNOWN
    }

    private enum Confidence {
        DETERMINISTIC_STATIC, DETERMINISTIC_GENERATED, OBSERVED_RUNTIME, INFERRED_REVIEW, UNKNOWN
    }

    private interface TableRow {
        List<String> cells();
    }

    private record RunManifest(String schema, String branch, String commit, String talosVersion,
            List<String> reportPaths) {
        static RunManifest current() {
            return new RunManifest(
                    "talos.architectureIntelligence.v1",
                    commandOutput(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"), Duration.ofSeconds(5))
                            .firstLineOr("unknown"),
                    commandOutput(List.of("git", "rev-parse", "HEAD"), Duration.ofSeconds(5)).firstLineOr("unknown"),
                    readVersion(),
                    MARKDOWN_REPORTS);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "schema", schema,
                    "branch", branch,
                    "commit", commit,
                    "talosVersion", talosVersion,
                    "outputRoot", REPORT_ROOT.toString(),
                    "reportPaths", reportPaths,
                    "jsonPaths", JSON_REPORTS.stream().map(p -> "data/" + p).toList());
        }
    }

    private record SourceIndex(List<SourceFile> files, Map<String, SourceFile> byClass) {
        static SourceIndex scan(Path root) throws IOException {
            List<SourceFile> files = new ArrayList<>();
            if (!Files.isDirectory(root)) {
                return new SourceIndex(List.of(), Map.of());
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<Path> paths = walk
                        .filter(path -> path.toString().endsWith(".java"))
                        .sorted()
                        .toList();
                for (Path path : paths) {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    String fqn = inferFqn(content, path);
                    files.add(new SourceFile(path, fqn, content));
                }
            }
            Map<String, SourceFile> byClass = new TreeMap<>();
            for (SourceFile file : files) {
                if (file.fqn() != null) {
                    byClass.put(file.fqn(), file);
                }
            }
            return new SourceIndex(files, byClass);
        }

        List<SourceHit> find(Predicate<String> linePredicate, String kind, int limit) {
            List<SourceHit> hits = new ArrayList<>();
            for (SourceFile file : files) {
                String[] lines = file.content().split("\\R", -1);
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (linePredicate.test(line)) {
                        hits.add(new SourceHit(file.path().toString(), i + 1, kind, compact(line)));
                        if (hits.size() >= limit) {
                            return hits;
                        }
                    }
                }
            }
            return hits;
        }

        int countNewSites(String fqn) {
            SourceFile file = byClass.get(fqn);
            if (file == null) {
                return 0;
            }
            return countOccurrences(file.content(), "new ");
        }

        int countHints(String fqn, List<String> needles) {
            SourceFile file = byClass.get(fqn);
            if (file == null) {
                return 0;
            }
            String lower = file.content().toLowerCase(Locale.ROOT);
            int count = 0;
            for (String needle : needles) {
                count += countOccurrences(lower, needle.toLowerCase(Locale.ROOT));
            }
            return count;
        }
    }

    private record SourceFile(Path path, String fqn, String content) {
    }

    private record SourceHit(String file, int line, String kind, String snippet) {
    }

    private record GraphModel(int importedClasses, Map<String, String> packageOf,
            Map<String, Map<String, Integer>> packageEdgeCounts, List<List<String>> sccs,
            List<ClassHotspot> classHotspots, Map<String, Integer> fanOut, Map<String, Integer> fanIn) {
        static GraphModel from(JavaClasses classes) {
            Map<String, String> packageOf = new TreeMap<>();
            TreeSet<String> classEdges = new TreeSet<>();
            for (JavaClass jc : classes) {
                String origin = topLevelClass(jc.getName());
                packageOf.putIfAbsent(origin, jc.getPackageName());
                for (Dependency dep : jc.getDirectDependenciesFromSelf()) {
                    JavaClass target = dep.getTargetClass();
                    if (!isTalos(target.getPackageName())) {
                        continue;
                    }
                    String targetName = topLevelClass(target.getName());
                    packageOf.putIfAbsent(targetName, target.getPackageName());
                    if (!origin.equals(targetName)) {
                        classEdges.add(origin + "|" + targetName);
                    }
                }
            }

            Map<String, Integer> fanOut = new TreeMap<>();
            Map<String, Integer> fanIn = new TreeMap<>();
            Map<String, Map<String, Integer>> matrix = new TreeMap<>();
            Map<String, TreeSet<String>> packageGraph = new TreeMap<>();
            for (String edge : classEdges) {
                String[] parts = edge.split("\\|", 2);
                String from = parts[0];
                String to = parts[1];
                fanOut.merge(from, 1, Integer::sum);
                fanIn.merge(to, 1, Integer::sum);
                String fromPkg = topLevelPackage(packageOf.get(from));
                String toPkg = topLevelPackage(packageOf.get(to));
                if (fromPkg != null && toPkg != null && !fromPkg.equals(toPkg)) {
                    matrix.computeIfAbsent(fromPkg, ignored -> new TreeMap<>()).merge(toPkg, 1, Integer::sum);
                    packageGraph.computeIfAbsent(fromPkg, ignored -> new TreeSet<>()).add(toPkg);
                    packageGraph.computeIfAbsent(toPkg, ignored -> new TreeSet<>());
                }
            }

            List<ClassHotspot> hotspots = new ArrayList<>();
            TreeSet<String> names = new TreeSet<>(packageOf.keySet());
            for (String name : names) {
                hotspots.add(new ClassHotspot(name, packageOf.get(name),
                        fanOut.getOrDefault(name, 0), fanIn.getOrDefault(name, 0)));
            }
            hotspots.sort(Comparator
                    .comparingInt((ClassHotspot h) -> h.fanOut() + h.fanIn()).reversed()
                    .thenComparing(ClassHotspot::className));

            return new GraphModel(classes.size(), packageOf, matrix,
                    ArchitectureIntelligenceReporter.sccs(packageGraph), hotspots, fanOut, fanIn);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "importedClasses", importedClasses,
                    "packageEdgeCounts", packageEdgeCounts,
                    "stronglyConnectedComponents", sccs,
                    "classHotspots", classHotspots.stream().map(ClassHotspot::toJson).toList());
        }
    }

    private record ClassHotspot(String className, String packageName, int fanOut, int fanIn) {
        Map<String, Object> toJson() {
            return ordered(
                    "className", className,
                    "packageName", packageName,
                    "fanOut", fanOut,
                    "fanIn", fanIn,
                    "combined", fanOut + fanIn);
        }
    }

    private record ManualDiModel(List<ManualDiRow> rows) {
        static ManualDiModel from(JavaClasses classes, SourceIndex sourceIndex) {
            Map<String, ManualDiAccumulator> byClass = new TreeMap<>();
            for (JavaClass jc : classes) {
                String fqn = topLevelClass(jc.getName());
                ManualDiAccumulator accumulator = byClass.computeIfAbsent(fqn,
                        ignored -> new ManualDiAccumulator(fqn, topLevelPackageName(jc.getPackageName()),
                                sourceIndex.countNewSites(fqn)));
                accumulator.constructorCalls += talosConstructorCallsFrom(jc);
            }
            List<ManualDiRow> rows = new ArrayList<>();
            for (ManualDiAccumulator accumulator : byClass.values()) {
                String fqn = accumulator.className;
                String pkg = accumulator.packageName;
                int outboundNewSites = accumulator.outboundNewSites;
                int constructorCalls = accumulator.constructorCalls;
                boolean likelyRoot = pkg.startsWith("dev.talos.app")
                        || pkg.startsWith("dev.talos.cli")
                        || fqn.endsWith("App")
                        || fqn.endsWith("Main")
                        || fqn.endsWith("Launcher")
                        || fqn.endsWith("Factory")
                        || fqn.endsWith("Registry")
                        || fqn.endsWith("Provider");
                if (likelyRoot || outboundNewSites >= 3 || constructorCalls >= 5) {
                    rows.add(new ManualDiRow(fqn, outboundNewSites, constructorCalls, roleHint(fqn, pkg),
                            Confidence.INFERRED_REVIEW));
                }
            }
            rows = rows.stream()
                    .sorted(Comparator
                            .comparingInt((ManualDiRow r) -> r.outboundNewSites() + r.constructorCalls()).reversed()
                            .thenComparing(ManualDiRow::className))
                    .limit(40)
                    .toList();
            return new ManualDiModel(rows);
        }

        Map<String, Object> toJson() {
            return ordered("rows", rows.stream().map(ManualDiRow::toJson).toList());
        }
    }

    private static final class ManualDiAccumulator {
        private final String className;
        private final String packageName;
        private final int outboundNewSites;
        private int constructorCalls;

        private ManualDiAccumulator(String className, String packageName, int outboundNewSites) {
            this.className = className;
            this.packageName = packageName;
            this.outboundNewSites = outboundNewSites;
        }
    }

    private record ManualDiRow(String className, int outboundNewSites, int constructorCalls,
            String roleHint, Confidence confidence) implements TableRow {
        @Override
        public List<String> cells() {
            return List.of(code(shortName(className)), Integer.toString(outboundNewSites),
                    Integer.toString(constructorCalls), roleHint, code(confidence.name()));
        }

        Map<String, Object> toJson() {
            return ordered(
                    "className", className,
                    "outboundNewSites", outboundNewSites,
                    "constructorCalls", constructorCalls,
                    "roleHint", roleHint,
                    "confidence", confidence.name());
        }
    }

    private record LifecycleCatalogEntry(String className, LifecycleScope scope, String reason) {
    }

    private static final List<LifecycleCatalogEntry> LIFECYCLE_CATALOG = List.of(
            new LifecycleCatalogEntry("dev.talos.cli.repl.TalosBootstrap", LifecycleScope.APPLICATION,
                    "static CLI composition root; creates session/workspace services"),
            new LifecycleCatalogEntry("dev.talos.app.Main", LifecycleScope.APPLICATION,
                    "process entry point"),
            new LifecycleCatalogEntry("dev.talos.core.Config", LifecycleScope.APPLICATION,
                    "loaded configuration shared across runtime services"),
            new LifecycleCatalogEntry("dev.talos.core.CfgUtil", LifecycleScope.APPLICATION,
                    "configuration utility surface"),
            new LifecycleCatalogEntry("dev.talos.cli.repl.Context", LifecycleScope.SESSION,
                    "runtime dependency record carried by REPL modes and commands"),
            new LifecycleCatalogEntry("dev.talos.cli.repl.SessionState", LifecycleScope.SESSION,
                    "REPL session state"),
            new LifecycleCatalogEntry("dev.talos.runtime.Session", LifecycleScope.SESSION,
                    "conversation/session identifier and message state"),
            new LifecycleCatalogEntry("dev.talos.runtime.SessionMemory", LifecycleScope.SESSION,
                    "conversation memory retained across turns"),
            new LifecycleCatalogEntry("dev.talos.runtime.JsonSessionStore", LifecycleScope.SESSION,
                    "session persistence store rooted under Talos home"),
            new LifecycleCatalogEntry("dev.talos.core.context.ConversationManager", LifecycleScope.SESSION,
                    "session conversation compaction and token state"),
            new LifecycleCatalogEntry("dev.talos.core.llm.LlmClient", LifecycleScope.SESSION,
                    "session-owned local model client with close/cancel state"),
            new LifecycleCatalogEntry("dev.talos.runtime.TurnProcessor", LifecycleScope.SESSION,
                    "session-owned turn processor with listeners and policy collaborators"),
            new LifecycleCatalogEntry("dev.talos.cli.modes.AssistantTurnExecutor", LifecycleScope.TURN,
                    "static assistant-turn execution control surface"),
            new LifecycleCatalogEntry("dev.talos.runtime.task.TaskContract", LifecycleScope.TURN,
                    "current-turn task contract"),
            new LifecycleCatalogEntry("dev.talos.runtime.task.TaskContractResolver", LifecycleScope.TURN,
                    "current-turn contract resolver"),
            new LifecycleCatalogEntry("dev.talos.runtime.turn.CurrentTurnPlan", LifecycleScope.TURN,
                    "current-turn policy and capability frame"),
            new LifecycleCatalogEntry("dev.talos.runtime.phase.ExecutionPhaseState", LifecycleScope.TURN,
                    "mutable execution phase for the active turn"),
            new LifecycleCatalogEntry("dev.talos.runtime.ToolCallLoop", LifecycleScope.TOOL_LOOP,
                    "tool-call loop coordinator for a turn"),
            new LifecycleCatalogEntry("dev.talos.runtime.toolcall.LoopState", LifecycleScope.TOOL_LOOP,
                    "mutable state for one tool-call loop run"),
            new LifecycleCatalogEntry("dev.talos.runtime.toolcall.ToolCallExecutionStage", LifecycleScope.TOOL_CALL,
                    "per-call execution stage"),
            new LifecycleCatalogEntry("dev.talos.runtime.toolcall.ToolCallParseStage", LifecycleScope.TOOL_CALL,
                    "per-response tool-call parse stage"),
            new LifecycleCatalogEntry("dev.talos.runtime.toolcall.ToolCallRepromptStage", LifecycleScope.TOOL_CALL,
                    "tool-call reprompt stage"),
            new LifecycleCatalogEntry("dev.talos.runtime.toolcall.ToolCallSupport", LifecycleScope.TOOL_CALL,
                    "tool-call argument and result support"),
            new LifecycleCatalogEntry("dev.talos.runtime.toolcall.ToolSurfacePlanner", LifecycleScope.TOOL_CALL,
                    "current-turn tool surface planner"),
            new LifecycleCatalogEntry("dev.talos.runtime.toolcall.ToolResultModelContextHandoff", LifecycleScope.TOOL_CALL,
                    "tool-result handoff and privacy boundary"),
            new LifecycleCatalogEntry("dev.talos.runtime.trace.LocalTurnTraceCapture", LifecycleScope.TRACE,
                    "thread-local current-turn trace capture"),
            new LifecycleCatalogEntry("dev.talos.runtime.trace.LocalTurnTrace", LifecycleScope.TRACE,
                    "local trace artifact model"),
            new LifecycleCatalogEntry("dev.talos.runtime.trace.PromptAuditSnapshot", LifecycleScope.TRACE,
                    "prompt-debug audit snapshot"),
            new LifecycleCatalogEntry("dev.talos.cli.prompt.PromptDebugArtifactWriter", LifecycleScope.TRACE,
                    "prompt-debug artifact writer"),
            new LifecycleCatalogEntry("dev.talos.cli.prompt.PromptDebugInspector", LifecycleScope.TRACE,
                    "prompt-debug inspection surface"),
            new LifecycleCatalogEntry("dev.talos.cli.prompt.PromptDebugRedactor", LifecycleScope.TRACE,
                    "prompt-debug redaction boundary"),
            new LifecycleCatalogEntry("dev.talos.core.rag.RagService", LifecycleScope.WORKSPACE,
                    "workspace retrieval/index service"),
            new LifecycleCatalogEntry("dev.talos.core.index.LuceneStore", LifecycleScope.WORKSPACE,
                    "workspace index store with close ownership"),
            new LifecycleCatalogEntry("dev.talos.core.index.Indexer", LifecycleScope.WORKSPACE,
                    "workspace indexing coordinator"),
            new LifecycleCatalogEntry("dev.talos.runtime.checkpoint.CheckpointService", LifecycleScope.WORKSPACE,
                    "workspace checkpoint coordinator"),
            new LifecycleCatalogEntry("dev.talos.runtime.checkpoint.FileBundleCheckpointStore", LifecycleScope.WORKSPACE,
                    "workspace checkpoint store"));

    private record LifecycleModel(List<LifecycleRow> rows) {
        static LifecycleModel from(JavaClasses classes, SourceIndex sourceIndex, GraphModel graph) {
            Map<String, JavaClass> byTopClass = new TreeMap<>();
            for (JavaClass jc : classes) {
                byTopClass.putIfAbsent(topLevelClass(jc.getName()), jc);
            }
            Map<String, LifecycleCatalogEntry> catalog = new TreeMap<>();
            for (LifecycleCatalogEntry entry : LIFECYCLE_CATALOG) {
                catalog.put(entry.className(), entry);
            }
            List<LifecycleRow> rows = new ArrayList<>();
            for (Map.Entry<String, JavaClass> entry : byTopClass.entrySet()) {
                String fqn = entry.getKey();
                JavaClass jc = entry.getValue();
                int score = lifecycleScore(fqn, sourceIndex, graph);
                LifecycleCatalogEntry catalogEntry = catalog.get(fqn);
                if (score < 2 && catalogEntry == null) {
                    continue;
                }
                LifecycleScope scope = catalogEntry == null
                        ? inferScope(fqn, jc.getPackageName())
                        : catalogEntry.scope();
                Confidence confidence = scope == LifecycleScope.UNKNOWN ? Confidence.UNKNOWN : Confidence.INFERRED_REVIEW;
                int constructorCallers = jc.getConstructorCallsToSelf().size();
                int closeHints = sourceIndex.countHints(fqn, List.of("close(", "dispose(", "shutdown(", "try ("));
                rows.add(new LifecycleRow(fqn, scope, confidence, constructorCallers, closeHints,
                        lifecycleReason(fqn, scope, closeHints, constructorCallers,
                                catalogEntry == null ? "" : catalogEntry.reason())));
            }
            rows.sort(Comparator
                    .comparingInt((LifecycleRow r) -> isCatalogLifecycleClass(r.className()) ? 1 : 0).reversed()
                    .thenComparing(Comparator.comparingInt((LifecycleRow r) -> r.confidence() == Confidence.UNKNOWN ? 0 : 1).reversed())
                    .thenComparing(Comparator.comparingInt((LifecycleRow r) -> lifecyclePriority(r.scope())).reversed())
                    .thenComparing(Comparator.comparingInt((LifecycleRow r) -> r.constructorCallers() + r.closeDisposeHints()).reversed())
                    .thenComparing(LifecycleRow::className));
            return new LifecycleModel(rows.stream().limit(80).toList());
        }

        Map<String, Object> toJson() {
            return ordered("scopes", Stream.of(LifecycleScope.values()).map(Enum::name).toList(),
                    "confidenceLabels", Stream.of(Confidence.values()).map(Enum::name).toList(),
                    "rows", rows.stream().map(LifecycleRow::toJson).toList());
        }
    }

    private record LifecycleRow(String className, LifecycleScope scope, Confidence confidence,
            int constructorCallers, int closeDisposeHints, String reason) implements TableRow {
        @Override
        public List<String> cells() {
            return List.of(code(shortName(className)), code(scope.name()), code(confidence.name()),
                    Integer.toString(constructorCallers), Integer.toString(closeDisposeHints), reason);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "className", className,
                    "scope", scope.name(),
                    "confidence", confidence.name(),
                    "constructorCallers", constructorCallers,
                    "closeDisposeHints", closeDisposeHints,
                    "reason", reason);
        }
    }

    private record HotspotModel(List<HotspotRow> rows) {
        static HotspotModel from(JavaClasses classes, GraphModel graph) {
            Map<String, HotspotAccumulator> byClass = new TreeMap<>();
            for (JavaClass jc : classes) {
                String fqn = topLevelClass(jc.getName());
                HotspotAccumulator accumulator = byClass.computeIfAbsent(fqn, ignored -> new HotspotAccumulator(fqn));
                accumulator.callsFrom += talosCallsFrom(jc);
                accumulator.callsTo += talosCallsTo(jc);
                accumulator.constructorCalls += talosConstructorCallsFrom(jc);
            }
            List<HotspotRow> rows = new ArrayList<>();
            for (HotspotAccumulator accumulator : byClass.values()) {
                String fqn = accumulator.className;
                int callsFrom = accumulator.callsFrom;
                int callsTo = accumulator.callsTo;
                int ctorCalls = accumulator.constructorCalls;
                int fanOut = graph.fanOut().getOrDefault(fqn, 0);
                int fanIn = graph.fanIn().getOrDefault(fqn, 0);
                int score = fanOut + fanIn + callsFrom + callsTo + ctorCalls;
                if (score >= 20) {
                    rows.add(new HotspotRow(fqn, fanOut, fanIn, callsFrom, callsTo, ctorCalls,
                            hotspotRisk(fqn, fanOut, fanIn, callsFrom, callsTo)));
                }
            }
            rows.sort(Comparator
                    .comparingInt((HotspotRow r) -> r.fanOut() + r.fanIn() + r.callsFrom() + r.callsTo()).reversed()
                    .thenComparing(HotspotRow::className));
            return new HotspotModel(rows.stream().limit(50).toList());
        }

        Map<String, Object> toJson() {
            return ordered("rows", rows.stream().map(HotspotRow::toJson).toList());
        }
    }

    private static final class HotspotAccumulator {
        private final String className;
        private int callsFrom;
        private int callsTo;
        private int constructorCalls;

        private HotspotAccumulator(String className) {
            this.className = className;
        }
    }

    private record HotspotRow(String className, int fanOut, int fanIn, int callsFrom, int callsTo,
            int constructorCalls, String risk) implements TableRow {
        @Override
        public List<String> cells() {
            return List.of(code(shortName(className)), Integer.toString(fanOut), Integer.toString(fanIn),
                    Integer.toString(callsFrom), Integer.toString(callsTo), Integer.toString(constructorCalls), risk);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "className", className,
                    "fanOut", fanOut,
                    "fanIn", fanIn,
                    "callsFrom", callsFrom,
                    "callsTo", callsTo,
                    "constructorCalls", constructorCalls,
                    "risk", risk);
        }
    }

    private record StaticStateModel(List<StaticStateRow> rows) {
        static StaticStateModel from(JavaClasses classes, SourceIndex sourceIndex) {
            List<StaticStateRow> rows = new ArrayList<>();
            for (JavaClass jc : classes) {
                String fqn = topLevelClass(jc.getName());
                for (JavaField field : jc.getFields()) {
                    if (isReviewableStaticField(field)) {
                        rows.add(new StaticStateRow(sourcePath(sourceIndex, fqn), 0,
                                staticFieldKind(field),
                                shortName(fqn) + "." + field.getName(), Confidence.DETERMINISTIC_STATIC));
                    }
                }
            }
            for (SourceHit hit : sourceIndex.find(ArchitectureIntelligenceReporter::looksLikeStaticStateDeclaration,
                    "SOURCE_STATE_HINT", 200)) {
                if (!isReviewableStaticSourceLine(hit.snippet())) {
                    continue;
                }
                rows.add(new StaticStateRow(hit.file(), hit.line(), hit.kind(), hit.snippet(), Confidence.INFERRED_REVIEW));
            }
            rows = new ArrayList<>(rows.stream().distinct().toList());
            rows.sort(Comparator
                    .comparing(StaticStateRow::file)
                    .thenComparingInt(StaticStateRow::line)
                    .thenComparing(StaticStateRow::snippet));
            return new StaticStateModel(rows.stream().limit(120).toList());
        }

        Map<String, Object> toJson() {
            return ordered("rows", rows.stream().map(StaticStateRow::toJson).toList());
        }
    }

    private record StaticStateRow(String file, int line, String kind, String snippet,
            Confidence confidence) implements TableRow {
        @Override
        public List<String> cells() {
            return List.of(code(file), line == 0 ? "" : Integer.toString(line), kind, code(snippet), code(confidence.name()));
        }

        Map<String, Object> toJson() {
            return ordered(
                    "file", file,
                    "line", line,
                    "kind", kind,
                    "snippet", snippet,
                    "confidence", confidence.name());
        }
    }

    private record ThematicModel(String description, List<ThematicRow> rows) {
        static ThematicModel approvalTool(SourceIndex sourceIndex, GraphModel graph) {
            return thematic(
                    "Approval/tool execution ownership candidates from package names, class names, and source terms.",
                    sourceIndex, graph,
                    List.of("approval", "permission", "tool", "command", "checkpoint", "mutation", "allow", "deny"));
        }

        static ThematicModel tracePrivacy(SourceIndex sourceIndex, GraphModel graph) {
            return thematic(
                    "Trace/privacy ownership candidates from trace, prompt-debug, provider-body, protected-path, and private-content terms.",
                    sourceIndex, graph,
                    List.of("trace", "prompt-debug", "promptdebug", "provider", "private", "protected", "redact", "secret"));
        }

        Map<String, Object> toJson() {
            return ordered("description", description, "rows", rows.stream().map(ThematicRow::toJson).toList());
        }
    }

    private record ThematicRow(String owner, String signals, int fanOut, int fanIn,
            List<ThematicEvidenceHit> evidenceHits, Confidence confidence) implements TableRow {
        @Override
        public List<String> cells() {
            return List.of(code(shortName(owner)), signals, Integer.toString(fanOut), Integer.toString(fanIn),
                    thematicEvidenceSummary(evidenceHits), code(confidence.name()));
        }

        Map<String, Object> toJson() {
            return ordered(
                    "owner", owner,
                    "signals", signals,
                    "fanOut", fanOut,
                    "fanIn", fanIn,
                    "evidenceHits", evidenceHits.stream().map(ThematicEvidenceHit::toJson).toList(),
                    "confidence", confidence.name());
        }
    }

    private record ThematicEvidenceHit(String file, int line, String signal, String snippet) {
        Map<String, Object> toJson() {
            return ordered(
                    "file", file,
                    "line", line,
                    "signal", signal,
                    "snippet", snippet);
        }
    }

    private record QualityOverlay(List<QualityRow> evidenceRows, List<QualityHotspotRow> hotspotOverlays) {
        static QualityOverlay read(Path root, HotspotModel hotspots) {
            List<QualityRow> rows = new ArrayList<>();
            CoverageIndex coverage = CoverageIndex.read(List.of(
                    Path.of("build", "reports", "jacoco", "candidateTest", "candidateJacocoTestReport.xml"),
                    Path.of("build", "reports", "jacoco", "test", "jacocoTestReport.xml")));
            QodanaEvidence qodana = QodanaEvidence.read(root.resolve("qodana-summary.json"));
            rows.add(readSummary(root.resolve("version-summary.json"), "version-summary", RawArtifactStatus.NOT_APPLICABLE));
            rows.add(readSummary(root.resolve("coverage-summary.json"), "coverage-summary", coverage.status()));
            rows.add(readSummary(root.resolve("e2e-summary.json"), "e2e-summary", RawArtifactStatus.NOT_APPLICABLE));
            rows.add(readSummary(root.resolve("qodana-summary.json"), "qodana-summary", qodana.rawArtifactStatus()));

            List<QualityHotspotRow> overlays = new ArrayList<>();
            for (HotspotRow hotspot : hotspots.rows().subList(0, Math.min(25, hotspots.rows().size()))) {
                CoverageClass coverageClass = coverage.byClass().get(hotspot.className());
                String coverageStatus = coverageClass == null ? coverage.status().name() : "COVERAGE_PRESENT";
                overlays.add(new QualityHotspotRow(
                        hotspot.className(),
                        hotspot.fanOut() + hotspot.fanIn() + hotspot.callsFrom() + hotspot.callsTo(),
                        coverageStatus,
                        coverageClass == null ? "" : coverageClass.instructionPercent(),
                        coverageClass == null ? "" : coverageClass.branchPercent(),
                        qodana.statusForClass(hotspot.className()),
                        hotspot.risk()));
            }
            return new QualityOverlay(rows, overlays);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "evidenceRows", evidenceRows.stream().map(QualityRow::toJson).toList(),
                    "hotspotOverlays", hotspotOverlays.stream().map(QualityHotspotRow::toJson).toList());
        }
    }

    private record QualityRow(String input, String status, String version, String keyFacts,
            String path, RawArtifactStatus rawArtifactStatus) implements TableRow {
        @Override
        public List<String> cells() {
            return List.of(input, code(status), version, keyFacts, code(path), code(rawArtifactStatus.name()));
        }

        Map<String, Object> toJson() {
            return ordered(
                    "input", input,
                    "status", status,
                    "version", version,
                    "keyFacts", keyFacts,
                    "path", path,
                    "rawArtifactStatus", rawArtifactStatus.name());
        }
    }

    private record QualityHotspotRow(String className, int hotspotScore, String coverageStatus,
            String instructionCoverage, String branchCoverage, String qodanaStatus, String risk) implements TableRow {
        @Override
        public List<String> cells() {
            return List.of(code(shortName(className)), Integer.toString(hotspotScore), code(coverageStatus),
                    instructionCoverage, branchCoverage, qodanaStatus, risk);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "className", className,
                    "hotspotScore", hotspotScore,
                    "coverageStatus", coverageStatus,
                    "instructionCoverage", instructionCoverage,
                    "branchCoverage", branchCoverage,
                    "qodanaStatus", qodanaStatus,
                    "risk", risk);
        }
    }

    private enum RawArtifactStatus {
        PRESENT, RAW_ARTIFACT_MISSING, SUMMARY_MISSING, MALFORMED, NOT_APPLICABLE
    }

    private record CoverageClass(String className, String instructionPercent, String branchPercent) {
    }

    private record CoverageIndex(RawArtifactStatus status, Map<String, CoverageClass> byClass) {
        static CoverageIndex read(List<Path> candidates) {
            for (Path path : candidates) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                try {
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                    factory.setExpandEntityReferences(false);
                    Document document = factory.newDocumentBuilder().parse(path.toFile());
                    NodeList classNodes = document.getElementsByTagName("class");
                    Map<String, CoverageClass> rows = new TreeMap<>();
                    for (int i = 0; i < classNodes.getLength(); i++) {
                        Element clazz = (Element) classNodes.item(i);
                        String className = clazz.getAttribute("name").replace('/', '.');
                        String instruction = "";
                        String branch = "";
                        NodeList counters = clazz.getElementsByTagName("counter");
                        for (int j = 0; j < counters.getLength(); j++) {
                            Element counter = (Element) counters.item(j);
                            String type = counter.getAttribute("type");
                            String percent = percent(counter.getAttribute("covered"), counter.getAttribute("missed"));
                            if (type.equals("INSTRUCTION")) {
                                instruction = percent;
                            } else if (type.equals("BRANCH")) {
                                branch = percent;
                            }
                        }
                        rows.put(className, new CoverageClass(className, instruction, branch));
                    }
                    return new CoverageIndex(RawArtifactStatus.PRESENT, rows);
                } catch (Exception e) {
                    return new CoverageIndex(RawArtifactStatus.MALFORMED, Map.of());
                }
            }
            return new CoverageIndex(RawArtifactStatus.RAW_ARTIFACT_MISSING, Map.of());
        }
    }

    private record QodanaEvidence(RawArtifactStatus rawArtifactStatus, Map<String, Integer> issuesByClass) {
        static QodanaEvidence read(Path summaryPath) {
            if (!Files.isRegularFile(summaryPath)) {
                return new QodanaEvidence(RawArtifactStatus.SUMMARY_MISSING, Map.of());
            }
            try {
                JsonNode summary = JSON.readTree(summaryPath.toFile());
                String sarif = summary.path("sourcePaths").path("sarifFile").asText("");
                if (sarif.isBlank()) {
                    return new QodanaEvidence(RawArtifactStatus.RAW_ARTIFACT_MISSING, Map.of());
                }
                Path sarifPath = Path.of(sarif);
                if (!Files.isRegularFile(sarifPath)) {
                    return new QodanaEvidence(RawArtifactStatus.RAW_ARTIFACT_MISSING, Map.of());
                }
                return new QodanaEvidence(RawArtifactStatus.PRESENT, qodanaIssuesByClass(sarifPath));
            } catch (IOException e) {
                return new QodanaEvidence(RawArtifactStatus.MALFORMED, Map.of());
            }
        }

        String statusForClass(String className) {
            if (rawArtifactStatus != RawArtifactStatus.PRESENT) {
                return rawArtifactStatus.name();
            }
            return Integer.toString(issuesByClass.getOrDefault(className, 0));
        }
    }

    private record Toolchain(List<ToolRow> rows) {
        static Toolchain detect() {
            List<ToolRow> rows = new ArrayList<>();
            rows.add(pathTool("java", "required JDK/runtime"));
            rows.add(pathTool("gradlew.bat", "required Gradle wrapper"));
            rows.add(pathTool("jdeps", "advisory dependency cross-check"));
            rows.add(pathTool("jfr", "follow-on runtime lifecycle evidence"));
            rows.add(pathTool("jcmd", "follow-on runtime lifecycle capture"));
            rows.add(pathTool("docker", "Qodana container readiness only"));
            rows.add(pathTool("qodana", "Qodana CLI readiness only; scan is not run"));
            rows.add(pathTool("codeql", "follow-on static analysis; unavailable is acceptable"));
            rows.add(new ToolRow("ArchUnit", "AVAILABLE", "test dependency com.tngtech.archunit", "required fact engine"));
            rows.add(new ToolRow("Error Prone", "FOLLOW_ON", "not part of T807", "compiler policy follow-on"));
            rows.add(new ToolRow("NullAway", "FOLLOW_ON", "not part of T807", "nullness follow-on"));
            rows.add(new ToolRow("JSpecify", "FOLLOW_ON", "not part of T807", "type annotation follow-on"));
            rows.add(dockerImage());
            rows.sort(Comparator.comparing(ToolRow::tool));
            return new Toolchain(rows);
        }

        boolean available(String tool) {
            return rows.stream().anyMatch(row -> row.tool().equals(tool) && row.status().equals("AVAILABLE"));
        }

        Map<String, Object> toJson() {
            return ordered("rows", rows.stream().map(ToolRow::toJson).toList());
        }
    }

    private record ToolRow(String tool, String status, String detail, String role) implements TableRow {
        @Override
        public List<String> cells() {
            return List.of(tool, code(status), code(detail), role);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "tool", tool,
                    "status", status,
                    "detail", detail,
                    "role", role);
        }
    }

    private record JdepsResult(String status, String command, List<String> lines) {
        static JdepsResult runIfAvailable(Toolchain toolchain) {
            if (!toolchain.available("jdeps")) {
                return new JdepsResult("UNAVAILABLE", "jdeps --multi-release 21 -summary build/classes/java/main", List.of());
            }
            Path classes = Path.of("build", "classes", "java", "main");
            if (!Files.isDirectory(classes)) {
                return new JdepsResult("UNAVAILABLE", "jdeps --multi-release 21 -summary build/classes/java/main",
                        List.of("build/classes/java/main is not present"));
            }
            List<String> command = List.of("jdeps", "--multi-release", "21", "-summary", classes.toString());
            CommandResult result = commandOutput(command, Duration.ofSeconds(10));
            List<String> lines = result.lines().stream().limit(120).toList();
            String status;
            if (result.exitCode() != 0) {
                status = "FAILED";
            } else if (lines.stream().anyMatch(line -> line.contains("not found"))) {
                status = "PARTIAL_CLASSPATH";
            } else {
                status = "COMPLETE";
            }
            return new JdepsResult(status, "jdeps --multi-release 21 -summary build/classes/java/main", lines);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "status", status,
                    "command", command,
                    "lines", lines);
        }
    }

    private record Wave5Sequence(Wave5ScoreModel scoreModel, List<Wave5Row> rows) {
        static Wave5Sequence from(IntelligenceModel model) {
            Map<String, Wave5ScoreBreakdown> score = new TreeMap<>();
            Map<String, TreeSet<String>> reasons = new TreeMap<>();
            for (HotspotRow row : model.hotspots().rows()) {
                addHotspot(score, reasons, row.className(),
                        row.fanOut() + row.fanIn()
                                + row.callsFrom() / CALL_VOLUME_DIVISOR
                                + row.callsTo() / CALL_VOLUME_DIVISOR,
                        "method/class hotspot");
            }
            for (LifecycleRow row : model.lifecycle().rows()) {
                addLifecycle(score, reasons, row.className(), lifecyclePriority(row.scope()) * LIFECYCLE_WEIGHT,
                        "lifecycle " + row.scope());
            }
            for (ThematicRow row : model.approvalTool().rows()) {
                addApprovalTool(score, reasons, row.owner(), APPROVAL_TOOL_WEIGHT, "approval/tool ownership");
            }
            for (ThematicRow row : model.tracePrivacy().rows()) {
                addTracePrivacy(score, reasons, row.owner(), TRACE_PRIVACY_WEIGHT, "trace/privacy ownership");
            }
            List<Wave5Row> rows = new ArrayList<>();
            for (Map.Entry<String, Wave5ScoreBreakdown> entry : score.entrySet()) {
                Wave5ScoreBreakdown breakdown = entry.getValue();
                if (breakdown.total() < 20) {
                    continue;
                }
                String reason = String.join(", ", reasons.getOrDefault(entry.getKey(), new TreeSet<>()));
                rows.add(new Wave5Row(entry.getKey(), breakdown.total(), breakdown, reason, Confidence.INFERRED_REVIEW));
            }
            rows.sort(Comparator.comparingInt(Wave5Row::priorityIndex).reversed().thenComparing(Wave5Row::candidate));
            return new Wave5Sequence(Wave5ScoreModel.current(), rows.stream().limit(40).toList());
        }

        private static void addHotspot(Map<String, Wave5ScoreBreakdown> score, Map<String, TreeSet<String>> reasons,
                String candidate, int points, String reason) {
            Wave5ScoreBreakdown current = score.getOrDefault(candidate, Wave5ScoreBreakdown.empty());
            score.put(candidate, current.withHotspot(current.hotspot() + points));
            reasons.computeIfAbsent(candidate, ignored -> new TreeSet<>()).add(reason);
        }

        private static void addLifecycle(Map<String, Wave5ScoreBreakdown> score, Map<String, TreeSet<String>> reasons,
                String candidate, int points, String reason) {
            Wave5ScoreBreakdown current = score.getOrDefault(candidate, Wave5ScoreBreakdown.empty());
            score.put(candidate, current.withLifecycle(current.lifecycle() + points));
            reasons.computeIfAbsent(candidate, ignored -> new TreeSet<>()).add(reason);
        }

        private static void addApprovalTool(Map<String, Wave5ScoreBreakdown> score, Map<String, TreeSet<String>> reasons,
                String candidate, int points, String reason) {
            Wave5ScoreBreakdown current = score.getOrDefault(candidate, Wave5ScoreBreakdown.empty());
            score.put(candidate, current.withApprovalTool(current.approvalTool() + points));
            reasons.computeIfAbsent(candidate, ignored -> new TreeSet<>()).add(reason);
        }

        private static void addTracePrivacy(Map<String, Wave5ScoreBreakdown> score, Map<String, TreeSet<String>> reasons,
                String candidate, int points, String reason) {
            Wave5ScoreBreakdown current = score.getOrDefault(candidate, Wave5ScoreBreakdown.empty());
            score.put(candidate, current.withTracePrivacy(current.tracePrivacy() + points));
            reasons.computeIfAbsent(candidate, ignored -> new TreeSet<>()).add(reason);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "scoreModel", scoreModel.toJson(),
                    "rows", rows.stream().map(Wave5Row::toJson).toList());
        }
    }

    private record Wave5ScoreModel(String description, String formula, int callVolumeDivisor,
            int lifecycleWeight, int approvalToolWeight, int tracePrivacyWeight,
            Map<String, Integer> lifecyclePriorities) {
        static Wave5ScoreModel current() {
            Map<String, Integer> priorities = new LinkedHashMap<>();
            for (LifecycleScope scope : LifecycleScope.values()) {
                priorities.put(scope.name(), lifecyclePriority(scope));
            }
            String formula = "priorityIndex = fanOut + fanIn + floor(callsFrom / " + CALL_VOLUME_DIVISOR
                    + ") + floor(callsTo / " + CALL_VOLUME_DIVISOR + ")"
                    + " + lifecyclePriority(scope) * " + LIFECYCLE_WEIGHT
                    + " + " + APPROVAL_TOOL_WEIGHT + " if approval/tool owner"
                    + " + " + TRACE_PRIVACY_WEIGHT + " if trace/privacy owner";
            return new Wave5ScoreModel(
                    "Unnormalized priority index for ordering Wave 5 refactor review candidates on one commit.",
                    formula,
                    CALL_VOLUME_DIVISOR,
                    LIFECYCLE_WEIGHT,
                    APPROVAL_TOOL_WEIGHT,
                    TRACE_PRIVACY_WEIGHT,
                    priorities);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "description", description,
                    "formula", formula,
                    "callVolumeDivisor", callVolumeDivisor,
                    "lifecycleWeight", lifecycleWeight,
                    "approvalToolWeight", approvalToolWeight,
                    "tracePrivacyWeight", tracePrivacyWeight,
                    "lifecyclePriorities", lifecyclePriorities);
        }
    }

    private record Wave5ScoreBreakdown(int hotspot, int lifecycle, int approvalTool, int tracePrivacy) {
        static Wave5ScoreBreakdown empty() {
            return new Wave5ScoreBreakdown(0, 0, 0, 0);
        }

        int total() {
            return hotspot + lifecycle + approvalTool + tracePrivacy;
        }

        Wave5ScoreBreakdown withHotspot(int value) {
            return new Wave5ScoreBreakdown(value, lifecycle, approvalTool, tracePrivacy);
        }

        Wave5ScoreBreakdown withLifecycle(int value) {
            return new Wave5ScoreBreakdown(hotspot, value, approvalTool, tracePrivacy);
        }

        Wave5ScoreBreakdown withApprovalTool(int value) {
            return new Wave5ScoreBreakdown(hotspot, lifecycle, value, tracePrivacy);
        }

        Wave5ScoreBreakdown withTracePrivacy(int value) {
            return new Wave5ScoreBreakdown(hotspot, lifecycle, approvalTool, value);
        }

        Map<String, Object> toJson() {
            return ordered(
                    "hotspot", hotspot,
                    "lifecycle", lifecycle,
                    "approvalTool", approvalTool,
                    "tracePrivacy", tracePrivacy,
                    "total", total());
        }
    }

    private record Wave5Row(String candidate, int priorityIndex, Wave5ScoreBreakdown scoreBreakdown,
            String reason, Confidence confidence) {
        Map<String, Object> toJson() {
            return ordered(
                    "candidate", candidate,
                    "priorityIndex", priorityIndex,
                    "scoreBreakdown", scoreBreakdown.toJson(),
                    "reason", reason,
                    "confidence", confidence.name());
        }
    }

    // ---------------------------------------------------------------------
    // Extraction helpers
    // ---------------------------------------------------------------------

    private static ThematicModel thematic(String description, SourceIndex sourceIndex, GraphModel graph,
            List<String> needles) {
        Map<String, TreeSet<String>> signals = new TreeMap<>();
        Map<String, List<ThematicEvidenceHit>> evidenceHits = new TreeMap<>();
        for (SourceFile file : sourceIndex.files()) {
            String fqn = file.fqn();
            if (fqn == null) {
                continue;
            }
            String lowerName = fqn.toLowerCase(Locale.ROOT);
            String[] lines = file.content().split("\\R", -1);
            for (String needle : needles) {
                String lowerNeedle = needle.toLowerCase(Locale.ROOT);
                boolean matched = lowerName.contains(lowerNeedle);
                SourceHit hit = firstThematicHit(file.path().toString(), lines, needle, false)
                        .or(() -> firstThematicHit(file.path().toString(), lines, needle, true))
                        .orElse(null);
                if (hit != null) {
                    matched = true;
                    addThematicEvidence(evidenceHits, fqn, hit.file(), hit.line(), needle, hit.snippet());
                }
                if (matched) {
                    signals.computeIfAbsent(fqn, ignored -> new TreeSet<>()).add(needle);
                }
            }
        }
        List<ThematicRow> rows = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> entry : signals.entrySet()) {
            String owner = entry.getKey();
            int fanOut = graph.fanOut().getOrDefault(owner, 0);
            int fanIn = graph.fanIn().getOrDefault(owner, 0);
            int score = entry.getValue().size() * 10 + fanOut + fanIn;
            if (score >= 12) {
                rows.add(new ThematicRow(owner, String.join(", ", entry.getValue()), fanOut, fanIn,
                        List.copyOf(evidenceHits.getOrDefault(owner, List.of())), Confidence.INFERRED_REVIEW));
            }
        }
        rows.sort(Comparator
                .comparingInt((ThematicRow r) -> r.signals().length() + r.fanOut() + r.fanIn()).reversed()
                .thenComparing(ThematicRow::owner));
        return new ThematicModel(description, rows.stream().limit(40).toList());
    }

    private static Optional<SourceHit> firstThematicHit(String file, String[] lines, String signal,
            boolean allowImports) {
        String lowerSignal = signal.toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!allowImports && line.startsWith("import ")) {
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).contains(lowerSignal)) {
                return Optional.of(new SourceHit(file, i + 1, signal, compact(line)));
            }
        }
        return Optional.empty();
    }

    private static void addThematicEvidence(Map<String, List<ThematicEvidenceHit>> evidenceHits, String owner,
            String file, int line, String signal, String snippet) {
        List<ThematicEvidenceHit> hits = evidenceHits.computeIfAbsent(owner, ignored -> new ArrayList<>());
        if (hits.size() >= THEMATIC_EVIDENCE_HIT_LIMIT) {
            return;
        }
        ThematicEvidenceHit hit = new ThematicEvidenceHit(file, line, signal, snippet);
        if (!hits.contains(hit)) {
            hits.add(hit);
        }
    }

    private static int talosCallsFrom(JavaClass jc) {
        int calls = 0;
        for (JavaCall<?> call : jc.getMethodCallsFromSelf()) {
            if (isTalos(call.getTargetOwner().getPackageName())) {
                calls++;
            }
        }
        for (JavaCall<?> call : jc.getConstructorCallsFromSelf()) {
            if (isTalos(call.getTargetOwner().getPackageName())) {
                calls++;
            }
        }
        return calls;
    }

    private static int talosConstructorCallsFrom(JavaClass jc) {
        return (int) jc.getConstructorCallsFromSelf().stream()
                .filter(call -> isTalos(call.getTargetOwner().getPackageName()))
                .count();
    }

    private static int talosCallsTo(JavaClass jc) {
        int calls = 0;
        for (JavaAccess<?> access : jc.getAccessesToSelf()) {
            if (access instanceof JavaCall<?> call && isTalos(call.getOriginOwner().getPackageName())) {
                calls++;
            } else if (access instanceof JavaFieldAccess fieldAccess
                    && isTalos(fieldAccess.getOriginOwner().getPackageName())) {
                calls++;
            }
        }
        return calls;
    }

    private static int lifecycleScore(String fqn, SourceIndex sourceIndex, GraphModel graph) {
        String lower = fqn.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : List.of("session", "turn", "tool", "trace", "approval", "workspace", "client",
                "store", "registry", "context", "checkpoint", "executor", "runner", "manager")) {
            if (lower.contains(term)) {
                score += 2;
            }
        }
        score += sourceIndex.countHints(fqn, List.of("close(", "dispose(", "shutdown(", "ThreadLocal", "static "));
        score += Math.min(6, graph.fanIn().getOrDefault(fqn, 0) / 5);
        score += Math.min(6, graph.fanOut().getOrDefault(fqn, 0) / 5);
        return score;
    }

    private static LifecycleScope inferScope(String fqn, String pkg) {
        String lower = fqn.toLowerCase(Locale.ROOT);
        if (lower.contains("trace") || lower.contains("promptdebug") || lower.contains("promptaudit")) {
            return LifecycleScope.TRACE;
        }
        if (lower.contains("assistantturnexecutor")
                || lower.contains("taskcontract")
                || lower.contains("currentturn")
                || lower.contains("executionphasestate")) {
            return LifecycleScope.TURN;
        }
        if (lower.contains("toolcallloop") || lower.contains("toolloop")) {
            return LifecycleScope.TOOL_LOOP;
        }
        if (lower.contains("toolcall") || lower.contains("toolsurface") || lower.contains("toolresult")) {
            return LifecycleScope.TOOL_CALL;
        }
        if (lower.contains("turn") || lower.contains("currentturn") || lower.contains("outcome")) {
            return LifecycleScope.TURN;
        }
        if (lower.contains("session") || lower.contains("conversation") || lower.contains("llmclient")) {
            return LifecycleScope.SESSION;
        }
        if (lower.contains("workspace") || lower.contains("checkpoint")) {
            return LifecycleScope.WORKSPACE;
        }
        if (pkg.startsWith("dev.talos.app") || lower.endsWith("app") || lower.contains("registry")) {
            return LifecycleScope.APPLICATION;
        }
        if (lower.contains("request") || lower.contains("temporary")) {
            return LifecycleScope.TEMPORARY;
        }
        return LifecycleScope.UNKNOWN;
    }

    private static boolean isCatalogLifecycleClass(String fqn) {
        return LIFECYCLE_CATALOG.stream().anyMatch(entry -> entry.className().equals(fqn));
    }

    private static String lifecycleReason(String fqn, LifecycleScope scope, int closeHints, int constructorCallers,
            String reviewedReason) {
        List<String> reasons = new ArrayList<>();
        if (reviewedReason == null || reviewedReason.isBlank()) {
            reasons.add("name/package suggests " + scope.name().toLowerCase(Locale.ROOT) + " scope");
        } else {
            reasons.add(reviewedReason);
        }
        if (closeHints > 0) {
            reasons.add(closeHints + " close/dispose hints");
        }
        if (constructorCallers > 0) {
            reasons.add(constructorCallers + " constructor call sites");
        }
        return String.join("; ", reasons);
    }

    private static int lifecyclePriority(LifecycleScope scope) {
        return switch (scope) {
            case TRACE -> 9;
            case TOOL_CALL -> 8;
            case TOOL_LOOP -> 8;
            case TURN -> 7;
            case SESSION -> 6;
            case WORKSPACE -> 5;
            case APPLICATION -> 4;
            case TEMPORARY -> 3;
            case UNKNOWN -> 1;
        };
    }

    private static String roleHint(String fqn, String pkg) {
        String lower = fqn.toLowerCase(Locale.ROOT);
        if (pkg.startsWith("dev.talos.app")) {
            return "application composition root";
        }
        if (lower.contains("launcher") || lower.contains("cmd")) {
            return "CLI wiring/adapter";
        }
        if (lower.contains("factory") || lower.contains("provider")) {
            return "factory/provider";
        }
        if (lower.contains("registry")) {
            return "registry";
        }
        if (lower.contains("executor") || lower.contains("runner")) {
            return "execution coordinator";
        }
        return "manual wiring candidate";
    }

    private static String hotspotRisk(String fqn, int fanOut, int fanIn, int callsFrom, int callsTo) {
        if (fanOut >= 30 && fanIn >= 30) {
            return "high fan-in and fan-out; review ownership before moving";
        }
        if (fanOut >= 30 || callsFrom >= 120) {
            return "wide outgoing control surface; candidate for extraction plan";
        }
        if (fanIn >= 30 || callsTo >= 120) {
            return "shared hub; keep contract thin before moving";
        }
        if (fqn.contains("Approval") || fqn.contains("Trace") || fqn.contains("Tool")) {
            return "policy/evidence-adjacent; refactor with tests";
        }
        return "moderate hotspot";
    }

    private static QualityRow readSummary(Path path, String input, RawArtifactStatus rawArtifactStatus) {
        if (!Files.isRegularFile(path)) {
            return new QualityRow(input, "MISSING", "", "summary file missing", path.toString(),
                    RawArtifactStatus.SUMMARY_MISSING);
        }
        try {
            JsonNode json = JSON.readTree(path.toFile());
            String version = json.path("version").asText("");
            String status = json.path("summaryStatus").asText(json.path("status").asText("PRESENT"));
            String facts = switch (input) {
                case "qodana-summary" -> qodanaFacts(json);
                case "coverage-summary" -> coverageFacts(json);
                case "e2e-summary" -> e2eFacts(json);
                case "version-summary" -> versionFacts(json);
                default -> "present";
            };
            return new QualityRow(input, status, version, facts, path.toString(), rawArtifactStatus);
        } catch (IOException e) {
            return new QualityRow(input, "MALFORMED", "", e.getClass().getSimpleName(), path.toString(),
                    RawArtifactStatus.MALFORMED);
        }
    }

    private static String qodanaFacts(JsonNode json) {
        return "issues=" + json.path("totalIssues").asText("unknown")
                + ", critical=" + json.path("criticalIssues").asText("unknown")
                + ", revision=" + json.path("provenance").path("revisionStatus").asText("unknown")
                + ", branch=" + json.path("provenance").path("branchStatus").asText("unknown")
                + ", artifacts=" + json.path("requiredArtifacts").path("status").asText("unknown");
    }

    private static String coverageFacts(JsonNode json) {
        return "instruction=" + json.path("instructionCoverage").path("percent").asText("unknown")
                + ", branch=" + json.path("branchCoverage").path("percent").asText("unknown")
                + ", tests=" + json.path("tests").path("status").asText("unknown");
    }

    private static String e2eFacts(JsonNode json) {
        return "tests=" + json.path("testExecution").path("status").asText("unknown")
                + ", total=" + json.path("testExecution").path("total").asText("unknown")
                + ", json-scenarios=" + json.path("jsonScenarioCoverage").path("resourceTraceabilityStatus").asText("unknown");
    }

    private static String versionFacts(JsonNode json) {
        JsonNode artifact = json.path("artifacts").isArray() && json.path("artifacts").size() > 0
                ? json.path("artifacts").get(0)
                : JSON.createObjectNode();
        return "jarExists=" + artifact.path("exists").asText("unknown")
                + ", jarTask=" + json.path("jarTaskStateInCurrentInvocation").path("status").asText("unknown");
    }

    private static boolean isReviewableStaticField(JavaField field) {
        if (!field.getModifiers().contains(JavaModifier.STATIC)
                || !isTalos(field.getOwner().getPackageName())) {
            return false;
        }
        String name = field.getName();
        if (name.startsWith("$") || name.contains("$SwitchMap")) {
            return false;
        }
        String rawType = field.getRawType().getName();
        boolean isFinal = field.getModifiers().contains(JavaModifier.FINAL);
        if (rawType.contains("ThreadLocal") || rawType.contains("Atomic")) {
            return true;
        }
        if (name.equals("INSTANCE") || name.equals("HOLDER") || name.contains("CACHE") || name.contains("REGISTRY")) {
            return true;
        }
        if (rawType.equals(field.getOwner().getName()) && !name.equals("INSTANCE")) {
            return false;
        }
        if (isFinal && isConstantLikeStatic(rawType, name)) {
            return false;
        }
        return !isFinal;
    }

    private static String staticFieldKind(JavaField field) {
        String rawType = field.getRawType().getName();
        if (rawType.contains("ThreadLocal")) {
            return "THREAD_LOCAL_FIELD";
        }
        if (field.getModifiers().contains(JavaModifier.FINAL)) {
            return "STATIC_FINAL_STATE_FIELD";
        }
        return "STATIC_MUTABLE_FIELD";
    }

    private static boolean isConstantLikeStatic(String rawType, String name) {
        return rawType.equals("java.lang.String")
                || rawType.equals("java.util.regex.Pattern")
                || rawType.equals("org.slf4j.Logger")
                || rawType.equals("boolean")
                || rawType.equals("int")
                || rawType.equals("long")
                || rawType.equals("double")
                || rawType.equals("float")
                || rawType.equals("short")
                || rawType.equals("byte")
                || rawType.equals("char")
                || name.equals(name.toUpperCase(Locale.ROOT));
    }

    private static boolean isReviewableStaticSourceLine(String snippet) {
        String lower = snippet.toLowerCase(Locale.ROOT);
        if (snippet.contains("$VALUES") || snippet.contains("$SwitchMap")) {
            return false;
        }
        if (lower.contains(" static final class ")
                || lower.contains(" static final interface ")
                || lower.contains(" static final record ")
                || lower.contains(" static final enum ")) {
            return false;
        }
        if (lower.contains(" static ") && lower.contains("(") && !lower.contains("=")) {
            return false;
        }
        if (lower.contains("static final")
                && !lower.contains("threadlocal")
                && !lower.contains("atomicreference")
                && !lower.contains(" instance")
                && !lower.contains(" holder")
                && !lower.contains(" cache")
                && !lower.contains(" registry")) {
            return false;
        }
        return lower.contains("threadlocal")
                || lower.contains("atomicreference")
                || lower.contains(" static ")
                || lower.contains(" instance");
    }

    private static boolean looksLikeStaticStateDeclaration(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isBlank()
                || trimmed.startsWith("import ")
                || trimmed.startsWith("//")
                || trimmed.startsWith("*")
                || trimmed.startsWith("\"")) {
            return false;
        }
        if (!Pattern.compile("^(public|private|protected)?\\s*static\\b").matcher(trimmed).find()) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains(" static final class ")
                || lower.contains(" static final interface ")
                || lower.contains(" static final record ")
                || lower.contains(" static final enum ")) {
            return false;
        }
        int paren = trimmed.indexOf('(');
        int equals = trimmed.indexOf('=');
        if (paren >= 0 && (equals < 0 || paren < equals)) {
            return false;
        }
        return trimmed.contains("ThreadLocal")
                || trimmed.contains("AtomicReference")
                || trimmed.contains("AtomicBoolean")
                || trimmed.contains("AtomicInteger")
                || trimmed.contains("AtomicLong")
                || Pattern.compile("\\bINSTANCE\\b").matcher(trimmed).find()
                || trimmed.contains("HOLDER")
                || trimmed.contains("CACHE")
                || trimmed.contains("REGISTRY")
                || (!lower.contains("static final") && trimmed.contains("="));
    }

    private static String percent(String coveredText, String missedText) {
        try {
            int covered = Integer.parseInt(coveredText);
            int missed = Integer.parseInt(missedText);
            int total = covered + missed;
            if (total == 0) {
                return "";
            }
            return String.format(Locale.ROOT, "%.2f%%", covered * 100.0 / total);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static Map<String, Integer> qodanaIssuesByClass(Path sarifPath) throws IOException {
        JsonNode sarif = JSON.readTree(sarifPath.toFile());
        Map<String, Integer> counts = new TreeMap<>();
        for (JsonNode run : sarif.path("runs")) {
            for (JsonNode result : run.path("results")) {
                for (JsonNode location : result.path("locations")) {
                    String uri = location.path("physicalLocation").path("artifactLocation").path("uri").asText("");
                    String className = sourceUriToClassName(uri);
                    if (!className.isBlank()) {
                        counts.merge(className, 1, Integer::sum);
                    }
                }
            }
        }
        return counts;
    }

    private static String sourceUriToClassName(String uri) {
        if (uri == null || uri.isBlank()) {
            return "";
        }
        String normalized = uri.replace('\\', '/');
        int index = normalized.indexOf("src/main/java/");
        if (index < 0 || !normalized.endsWith(".java")) {
            return "";
        }
        String relative = normalized.substring(index + "src/main/java/".length(), normalized.length() - ".java".length());
        return relative.replace('/', '.');
    }

    private static ToolRow pathTool(String command, String role) {
        Optional<Path> path = findOnPath(command);
        if (path.isPresent()) {
            return new ToolRow(command, "AVAILABLE", path.get().toString(), role);
        }
        String status = command.equals("codeql") ? "FOLLOW_ON_UNAVAILABLE" : "UNAVAILABLE";
        return new ToolRow(command, status, "not found on PATH", role);
    }

    private static ToolRow dockerImage() {
        if (findOnPath("docker").isEmpty()) {
            return new ToolRow("jetbrains/qodana-jvm-community:2026.1", "UNAVAILABLE", "docker not found",
                    "Qodana image readiness only");
        }
        CommandResult result = commandOutput(List.of("docker", "image", "inspect",
                "jetbrains/qodana-jvm-community:2026.1", "--format", "{{.Id}}"), Duration.ofSeconds(5));
        if (result.exitCode() == 0 && !result.firstLineOr("").isBlank()) {
            return new ToolRow("jetbrains/qodana-jvm-community:2026.1", "AVAILABLE",
                    result.firstLineOr("present"), "Qodana image readiness only");
        }
        return new ToolRow("jetbrains/qodana-jvm-community:2026.1", "UNAVAILABLE",
                compact(String.join(" ", result.lines())), "Qodana image readiness only");
    }

    private static Optional<Path> findOnPath(String command) {
        Path direct = Path.of(command);
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct.toAbsolutePath().normalize());
        }
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return Optional.empty();
        }
        List<String> extensions = isWindows()
                ? List.of("", ".exe", ".bat", ".cmd")
                : List.of("");
        for (String dir : pathValue.split(Pattern.quote(System.getProperty("path.separator")))) {
            if (dir.isBlank()) {
                continue;
            }
            for (String ext : extensions) {
                Path candidate = Path.of(dir, command.endsWith(ext) ? command : command + ext);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate.toAbsolutePath().normalize());
                }
            }
        }
        if (command.equals("gradlew.bat") && Files.isRegularFile(Path.of("gradlew.bat"))) {
            return Optional.of(Path.of("gradlew.bat").toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    private static CommandResult commandOutput(List<String> command, Duration timeout) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, List.of("TIMEOUT after " + timeout.toSeconds() + "s"));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<String> lines = output.lines().map(String::trim).filter(s -> !s.isBlank()).toList();
            return new CommandResult(process.exitValue(), lines);
        } catch (IOException e) {
            return new CommandResult(-1, List.of(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, List.of("InterruptedException"));
        }
    }

    private record CommandResult(int exitCode, List<String> lines) {
        String firstLineOr(String fallback) {
            return lines.isEmpty() ? fallback : lines.get(0);
        }
    }

    // ---------------------------------------------------------------------
    // Generic helpers
    // ---------------------------------------------------------------------

    private static String readVersion() {
        Path properties = Path.of("gradle.properties");
        if (!Files.isRegularFile(properties)) {
            return "unknown";
        }
        try {
            for (String line : Files.readAllLines(properties, StandardCharsets.UTF_8)) {
                if (line.startsWith("talosVersion=")) {
                    return line.substring("talosVersion=".length()).trim();
                }
            }
        } catch (IOException ignored) {
            return "unknown";
        }
        return "unknown";
    }

    private static String inferFqn(String content, Path path) {
        String pkg = null;
        String type = null;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")) {
                pkg = trimmed.substring("package ".length()).replace(";", "").trim();
            }
            if (type == null) {
                type = firstType(trimmed);
            }
            if (pkg != null && type != null) {
                return pkg + "." + type;
            }
        }
        String file = path.getFileName().toString();
        return file.endsWith(".java") ? file.substring(0, file.length() - 5) : null;
    }

    private static String firstType(String line) {
        for (String marker : List.of(" class ", " interface ", " enum ", " record ")) {
            int idx = line.indexOf(marker);
            if (idx >= 0) {
                String rest = line.substring(idx + marker.length()).trim();
                int end = 0;
                while (end < rest.length() && Character.isJavaIdentifierPart(rest.charAt(end))) {
                    end++;
                }
                if (end > 0) {
                    return rest.substring(0, end);
                }
            }
        }
        return null;
    }

    private static String sourcePath(SourceIndex index, String fqn) {
        SourceFile file = index.byClass().get(fqn);
        return file == null ? shortName(fqn) : file.path().toString();
    }

    private static List<List<String>> sccs(Map<String, TreeSet<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};
        List<List<String>> result = new ArrayList<>();
        TreeSet<String> nodes = new TreeSet<>(graph.keySet());
        for (String node : nodes) {
            if (!index.containsKey(node)) {
                connect(node, graph, index, low, stack, onStack, counter, result);
            }
        }
        result.removeIf(scc -> scc.size() < 2);
        result.sort(Comparator.comparing(scc -> scc.get(0)));
        return result;
    }

    private static void connect(String node, Map<String, TreeSet<String>> graph, Map<String, Integer> index,
            Map<String, Integer> low, Deque<String> stack, Set<String> onStack, int[] counter,
            List<List<String>> result) {
        index.put(node, counter[0]);
        low.put(node, counter[0]);
        counter[0]++;
        stack.push(node);
        onStack.add(node);
        for (String next : graph.getOrDefault(node, new TreeSet<>())) {
            if (!index.containsKey(next)) {
                connect(next, graph, index, low, stack, onStack, counter, result);
                low.put(node, Math.min(low.get(node), low.get(next)));
            } else if (onStack.contains(next)) {
                low.put(node, Math.min(low.get(node), index.get(next)));
            }
        }
        if (low.get(node).equals(index.get(node))) {
            List<String> scc = new ArrayList<>();
            String current;
            do {
                current = stack.pop();
                onStack.remove(current);
                scc.add(current);
            } while (!current.equals(node));
            scc.sort(Comparator.naturalOrder());
            result.add(scc);
        }
    }

    private static boolean isTalos(String pkg) {
        return pkg != null && (pkg.equals(ROOT) || pkg.startsWith(ROOT_PREFIX));
    }

    private static String topLevelClass(String name) {
        String n = name;
        while (n.startsWith("[")) {
            n = n.substring(1);
        }
        if (n.startsWith("L") && n.endsWith(";")) {
            n = n.substring(1, n.length() - 1);
        }
        while (n.endsWith("[]")) {
            n = n.substring(0, n.length() - 2);
        }
        int dollar = n.indexOf('$');
        return dollar < 0 ? n : n.substring(0, dollar);
    }

    private static String topLevelPackage(String pkg) {
        if (!isTalos(pkg)) {
            return null;
        }
        if (pkg.equals(ROOT)) {
            return "(root)";
        }
        String rest = pkg.substring(ROOT_PREFIX.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static String topLevelPackageName(String pkg) {
        return pkg == null ? "" : pkg;
    }

    private static String shortName(String fqn) {
        return fqn != null && fqn.startsWith(ROOT_PREFIX) ? fqn.substring(ROOT_PREFIX.length()) : String.valueOf(fqn);
    }

    private static String code(String value) {
        return "`" + String.valueOf(value) + "`";
    }

    private static String compact(String line) {
        String compact = line.replace('\t', ' ').replaceAll("\\s+", " ").trim();
        return compact.length() > 160 ? compact.substring(0, 157) + "..." : compact;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }
}
