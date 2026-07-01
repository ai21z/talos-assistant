package dev.talos.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Report-only architecture discovery pass.
 *
 * <p>This is intentionally NOT a hard guard. It imports the production
 * {@code dev.talos} bytecode through ArchUnit's Core API and writes a
 * deterministic Markdown report to
 * {@code build/reports/talos/architecture/architecture-discovery-report.md}
 * describing package structure, dependency hotspots, the runtime-control spine,
 * layer-boundary candidates, and candidate top-level package cycles.
 *
 * <p>The test passes unless report generation itself fails. Discovered findings
 * never fail the build; they are evidence for manual review before any of them
 * is promoted into a hard {@code LayeredArchitectureTest} rule.
 *
 * <p>The report is timestamp-free, matching this project's deterministic
 * summary convention (see the build script summary helpers).
 */
@DisplayName("Architecture discovery report (report-only)")
class ArchitectureDiscoveryReportTest {

    private static final String ROOT = "dev.talos";
    private static final String ROOT_PREFIX = "dev.talos.";

    private static final Path REPORT_FILE = Path.of(
            "build", "reports", "talos", "architecture", "architecture-discovery-report.md");

    private static final List<String> TOP_LEVEL = List.of(
            "api", "app", "cli", "core", "engine", "runtime", "safety", "spi", "tools");

    /** Hubs called out by the discovery brief, with their actual packages. */
    private static final List<String> NAMED_HUBS = List.of(
            "dev.talos.cli.modes.AssistantTurnExecutor",
            "dev.talos.cli.modes.ExecutionOutcome",
            "dev.talos.core.context.ConversationManager",
            "dev.talos.runtime.ToolCallLoop",
            "dev.talos.runtime.policy.EvidenceObligationVerifier",
            "dev.talos.runtime.task.TaskContractResolver",
            "dev.talos.runtime.toolcall.ToolCallRepromptStage",
            "dev.talos.runtime.toolcall.ToolSurfacePlanner",
            "dev.talos.runtime.turn.CurrentTurnPlan");

    /** Runtime-control spine classes (section 4). */
    private static final List<String> SPINE = List.of(
            "dev.talos.runtime.task.TaskContractResolver",
            "dev.talos.runtime.turn.CurrentTurnPlan",
            "dev.talos.runtime.toolcall.ToolSurfacePlanner",
            "dev.talos.runtime.ToolCallLoop",
            "dev.talos.runtime.policy.EvidenceObligationPolicy",
            "dev.talos.runtime.policy.EvidenceObligationVerifier",
            "dev.talos.runtime.verification.StaticTaskVerifier",
            "dev.talos.cli.modes.ExecutionOutcome",
            "dev.talos.runtime.trace.LocalTurnTraceCapture");

    @Test
    @DisplayName("generates a deterministic architecture discovery report and never fails on findings")
    void generatesArchitectureDiscoveryReport() throws IOException {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT);

        Model model = buildModel(classes);
        String markdown = renderReport(model);

        Files.createDirectories(REPORT_FILE.getParent());
        Files.writeString(REPORT_FILE, markdown, StandardCharsets.UTF_8);

        assertTrue(Files.size(REPORT_FILE) > 0, "discovery report must not be empty");
    }

    // ---------------------------------------------------------------------
    // Model construction
    // ---------------------------------------------------------------------

    /** Aggregated, deterministic dependency model collapsed to top-level classes. */
    private static final class Model {
        int importedClasses;
        int methodCount;
        final Map<String, String> fullPackageOf = new HashMap<>();
        final TreeSet<String> classEdges = new TreeSet<>(); // "A|B" top-level-class edges within dev.talos
        final Map<String, Integer> fanOut = new HashMap<>();
        final Map<String, Integer> fanIn = new HashMap<>();
        final Map<String, TreeSet<String>> outAdj = new HashMap<>();
        final Map<String, TreeSet<String>> inAdj = new HashMap<>();
        final Map<String, Map<String, Integer>> pkgEdgeCounts = new TreeMap<>(); // topPkg -> topPkg -> count
    }

    private static Model buildModel(JavaClasses classes) {
        Model m = new Model();
        for (JavaClass jc : classes) {
            if (jc.getName().contains("$")) {
                // inner classes are folded into their enclosing top-level class
            }
            m.methodCount += jc.getMethods().size();
            String originKey = topLevelClass(jc.getName());
            m.fullPackageOf.putIfAbsent(originKey, jc.getPackageName());

            for (Dependency d : jc.getDirectDependenciesFromSelf()) {
                JavaClass target = d.getTargetClass();
                String targetPkg = target.getPackageName();
                if (!isTalos(targetPkg)) {
                    continue;
                }
                String targetKey = topLevelClass(stripArray(target.getName()));
                m.fullPackageOf.putIfAbsent(targetKey, targetPkg);
                if (!targetKey.equals(originKey)) {
                    m.classEdges.add(originKey + "|" + targetKey);
                }
            }
        }
        m.importedClasses = classes.size();

        for (String edge : m.classEdges) {
            int bar = edge.indexOf('|');
            String a = edge.substring(0, bar);
            String b = edge.substring(bar + 1);
            m.fanOut.merge(a, 1, Integer::sum);
            m.fanIn.merge(b, 1, Integer::sum);
            m.outAdj.computeIfAbsent(a, k -> new TreeSet<>()).add(b);
            m.inAdj.computeIfAbsent(b, k -> new TreeSet<>()).add(a);

            String pa = topLevelPackage(m.fullPackageOf.get(a));
            String pb = topLevelPackage(m.fullPackageOf.get(b));
            if (pa != null && pb != null && !pa.equals(pb)) {
                m.pkgEdgeCounts
                        .computeIfAbsent(pa, k -> new TreeMap<>())
                        .merge(pb, 1, Integer::sum);
            }
        }
        return m;
    }

    // ---------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------

    private static String renderReport(Model m) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Talos Architecture Discovery Report\n\n");
        sb.append("Report-only. Generated by `dev.talos.architecture.ArchitectureDiscoveryReportTest`. ")
                .append("Findings here never fail the build. Content is deterministic (no timestamps); ")
                .append("identity is collapsed to top-level classes (inner classes folded into their enclosing class), ")
                .append("and only dependencies whose target resides in `dev.talos` are counted.\n\n");

        renderSummary(sb, m);
        renderHotspots(sb, m);
        renderPackageMap(sb, m);
        renderSpine(sb, m);
        renderBoundaryCandidates(sb, m);
        renderCycles(sb, m);
        renderRecommendations(sb, m);
        return sb.toString();
    }

    private static void renderSummary(StringBuilder sb, Model m) {
        Map<String, Integer> perPkg = new TreeMap<>();
        Set<String> countedClasses = new HashSet<>();
        for (Map.Entry<String, String> e : m.fullPackageOf.entrySet()) {
            String top = topLevelPackage(e.getValue());
            if (top == null) {
                continue;
            }
            if (countedClasses.add(e.getKey())) {
                perPkg.merge(top, 1, Integer::sum);
            }
        }

        sb.append("## 1. Summary\n\n");
        sb.append("- Imported production classes (incl. inner): **").append(m.importedClasses).append("**\n");
        sb.append("- Distinct top-level classes referenced: **").append(m.fullPackageOf.size()).append("**\n");
        sb.append("- Declared methods (sum over imported classes): **").append(m.methodCount).append("**\n");
        sb.append("- Cross-class `dev.talos` dependency edges (deduped, top-level): **")
                .append(m.classEdges.size()).append("**\n\n");

        sb.append("Top-level package class counts:\n\n");
        sb.append("| Package | Top-level classes |\n|---|---:|\n");
        for (String p : TOP_LEVEL) {
            sb.append("| `dev.talos.").append(p).append("` | ").append(perPkg.getOrDefault(p, 0)).append(" |\n");
        }
        sb.append("\n");
    }

    private static void renderHotspots(StringBuilder sb, Model m) {
        sb.append("## 2. Dependency hotspots\n\n");
        Set<String> hubKeys = new HashSet<>(NAMED_HUBS);

        sb.append("### Top 15 by fan-out (outgoing `dev.talos` dependencies)\n\n");
        sb.append("| Rank | Class | Fan-out | Named hub |\n|---:|---|---:|:--:|\n");
        appendRanked(sb, m.fanOut, 15, hubKeys);
        sb.append("\n");

        sb.append("### Top 15 by fan-in (incoming `dev.talos` dependencies)\n\n");
        sb.append("| Rank | Class | Fan-in | Named hub |\n|---:|---|---:|:--:|\n");
        appendRanked(sb, m.fanIn, 15, hubKeys);
        sb.append("\n");

        sb.append("### Named hubs (from the discovery brief)\n\n");
        sb.append("| Class | Fan-out | Fan-in |\n|---|---:|---:|\n");
        for (String hub : NAMED_HUBS) {
            sb.append("| `").append(shortName(hub)).append("` | ")
                    .append(m.fanOut.getOrDefault(hub, 0)).append(" | ")
                    .append(m.fanIn.getOrDefault(hub, 0)).append(" |\n");
        }
        sb.append("\n");
    }

    private static void appendRanked(StringBuilder sb, Map<String, Integer> counts, int limit, Set<String> hubKeys) {
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        int rank = 1;
        for (Map.Entry<String, Integer> e : ranked) {
            if (rank > limit) {
                break;
            }
            sb.append("| ").append(rank).append(" | `").append(shortName(e.getKey())).append("` | ")
                    .append(e.getValue()).append(" | ").append(hubKeys.contains(e.getKey()) ? "yes" : "")
                    .append(" |\n");
            rank++;
        }
    }

    private static void renderPackageMap(StringBuilder sb, Model m) {
        sb.append("## 3. Package dependency map\n\n");
        sb.append("Counts are distinct top-level class edges from row package to column package.\n\n");
        sb.append("| from \\ to |");
        for (String p : TOP_LEVEL) {
            sb.append(" ").append(p).append(" |");
        }
        sb.append("\n|---|");
        for (int i = 0; i < TOP_LEVEL.size(); i++) {
            sb.append("---:|");
        }
        sb.append("\n");
        for (String from : TOP_LEVEL) {
            sb.append("| `").append(from).append("` |");
            Map<String, Integer> row = m.pkgEdgeCounts.getOrDefault(from, Map.of());
            for (String to : TOP_LEVEL) {
                if (from.equals(to)) {
                    sb.append(" - |");
                } else {
                    int c = row.getOrDefault(to, 0);
                    sb.append(" ").append(c == 0 ? "." : Integer.toString(c)).append(" |");
                }
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private static void renderSpine(StringBuilder sb, Model m) {
        sb.append("## 4. Runtime-control spine\n\n");
        for (String cls : SPINE) {
            String key = cls;
            boolean present = m.fullPackageOf.containsKey(key);
            sb.append("### `").append(shortName(cls)).append("`\n\n");
            if (!present) {
                sb.append("- not present in imported classes\n\n");
                continue;
            }
            sb.append("- package: `").append(m.fullPackageOf.get(key)).append("`\n");
            sb.append("- fan-out: ").append(m.fanOut.getOrDefault(key, 0))
                    .append(", fan-in: ").append(m.fanIn.getOrDefault(key, 0)).append("\n");
            sb.append("- callees (top-level, up to 10): ")
                    .append(sample(m.outAdj.get(key), 10)).append("\n");
            sb.append("- callers (top-level, up to 10): ")
                    .append(sample(m.inAdj.get(key), 10)).append("\n\n");
        }
    }

    private static void renderBoundaryCandidates(StringBuilder sb, Model m) {
        sb.append("## 5. Layer-boundary candidates (report-only)\n\n");
        List<Boundary> boundaries = List.of(
                new Boundary("runtime.policy -> cli",
                        p -> p.startsWith("dev.talos.runtime.policy"), p -> p.startsWith("dev.talos.cli")),
                new Boundary("runtime.verification -> cli",
                        p -> p.startsWith("dev.talos.runtime.verification"), p -> p.startsWith("dev.talos.cli")),
                new Boundary("runtime.toolcall -> cli.repl",
                        p -> p.startsWith("dev.talos.runtime.toolcall"), p -> p.startsWith("dev.talos.cli.repl")),
                new Boundary("tools -> cli",
                        p -> p.startsWith("dev.talos.tools"), p -> p.startsWith("dev.talos.cli")),
                new Boundary("core -> cli",
                        p -> p.startsWith("dev.talos.core"), p -> p.startsWith("dev.talos.cli")),
                new Boundary("spi -> cli/core/runtime/tools",
                        p -> p.startsWith("dev.talos.spi"),
                        p -> p.startsWith("dev.talos.cli") || p.startsWith("dev.talos.core")
                                || p.startsWith("dev.talos.runtime") || p.startsWith("dev.talos.tools")),
                new Boundary("safety -> cli/app",
                        p -> p.startsWith("dev.talos.safety"),
                        p -> p.startsWith("dev.talos.cli") || p.startsWith("dev.talos.app")));

        sb.append("| Candidate boundary | Edges | Examples |\n|---|---:|---|\n");
        for (Boundary b : boundaries) {
            List<String> hits = edgesMatching(m, b.src, b.tgt);
            String examples = hits.isEmpty()
                    ? "(none)"
                    : String.join("<br>", hits.subList(0, Math.min(5, hits.size())));
            sb.append("| ").append(b.name).append(" | ").append(hits.size()).append(" | ")
                    .append(examples).append(" |\n");
        }
        sb.append("\n");
    }

    private static void renderCycles(StringBuilder sb, Model m) {
        sb.append("## 6. Candidate cycles / slices\n\n");
        sb.append("Top-level package granularity (`dev.talos.*`). Intra-package subslice cycles are folded ")
                .append("into a single node here and are flagged for human review separately.\n\n");

        Map<String, Set<String>> graph = new TreeMap<>();
        for (String from : TOP_LEVEL) {
            Map<String, Integer> row = m.pkgEdgeCounts.getOrDefault(from, Map.of());
            Set<String> targets = new TreeSet<>();
            for (String to : TOP_LEVEL) {
                if (!from.equals(to) && row.getOrDefault(to, 0) > 0) {
                    targets.add(to);
                }
            }
            graph.put(from, targets);
        }

        List<String> mutual = new ArrayList<>();
        for (String a : TOP_LEVEL) {
            for (String b : graph.getOrDefault(a, Set.of())) {
                if (a.compareTo(b) < 0 && graph.getOrDefault(b, Set.of()).contains(a)) {
                    mutual.add("`" + a + "` <-> `" + b + "`");
                }
            }
        }

        List<List<String>> sccs = stronglyConnectedComponents(graph);
        List<List<String>> nonTrivial = new ArrayList<>();
        for (List<String> scc : sccs) {
            if (scc.size() > 1) {
                nonTrivial.add(scc);
            }
        }

        sb.append("- Mutual 2-package edges: ")
                .append(mutual.isEmpty() ? "none detected" : String.join(", ", mutual)).append("\n");
        sb.append("- Non-trivial strongly connected components: ");
        if (nonTrivial.isEmpty()) {
            sb.append("none detected\n");
        } else {
            List<String> rendered = new ArrayList<>();
            for (List<String> scc : nonTrivial) {
                rendered.add("{" + String.join(", ", scc) + "}");
            }
            sb.append(String.join("; ", rendered)).append("\n");
        }
        sb.append("\n");
    }

    private static void renderRecommendations(StringBuilder sb, Model m) {
        sb.append("## 7. Recommendations\n\n");

        List<String> cleanBoundaries = new ArrayList<>();
        List<String> dirtyBoundaries = new ArrayList<>();
        record Probe(String name, Predicate<String> src, Predicate<String> tgt) {
        }
        List<Probe> probes = List.of(
                new Probe("runtime.policy -> cli",
                        p -> p.startsWith("dev.talos.runtime.policy"), p -> p.startsWith("dev.talos.cli")),
                new Probe("runtime.verification -> cli",
                        p -> p.startsWith("dev.talos.runtime.verification"), p -> p.startsWith("dev.talos.cli")),
                new Probe("runtime.toolcall -> cli.repl",
                        p -> p.startsWith("dev.talos.runtime.toolcall"), p -> p.startsWith("dev.talos.cli.repl")),
                new Probe("tools -> cli",
                        p -> p.startsWith("dev.talos.tools"), p -> p.startsWith("dev.talos.cli")),
                new Probe("core -> cli",
                        p -> p.startsWith("dev.talos.core"), p -> p.startsWith("dev.talos.cli")),
                new Probe("spi -> cli/core/runtime/tools",
                        p -> p.startsWith("dev.talos.spi"),
                        p -> p.startsWith("dev.talos.cli") || p.startsWith("dev.talos.core")
                                || p.startsWith("dev.talos.runtime") || p.startsWith("dev.talos.tools")),
                new Probe("safety -> cli/app",
                        p -> p.startsWith("dev.talos.safety"),
                        p -> p.startsWith("dev.talos.cli") || p.startsWith("dev.talos.app")));
        for (Probe p : probes) {
            int n = edgesMatching(m, p.src(), p.tgt()).size();
            if (n == 0) {
                cleanBoundaries.add(p.name());
            } else {
                dirtyBoundaries.add(p.name() + " (" + n + " edges)");
            }
        }

        sb.append("### Hard-guard candidates (currently clean - promote deliberately, do not auto-merge)\n\n");
        if (cleanBoundaries.isEmpty()) {
            sb.append("- none currently clean\n");
        } else {
            for (String c : cleanBoundaries) {
                sb.append("- ").append(c).append(" - 0 edges today; would extend the existing 6-rule ratchet\n");
            }
        }
        sb.append("\n### Report-only candidates (nonzero today - keep observing, review before guarding)\n\n");
        if (dirtyBoundaries.isEmpty()) {
            sb.append("- none\n");
        } else {
            for (String c : dirtyBoundaries) {
                sb.append("- ").append(c).append("\n");
            }
        }
        sb.append("\n### No-action observations\n\n");
        sb.append("- `api` and `app` remain unconstrained by design (seam + composition root).\n");
        sb.append("- High fan-in on shared model/record types is expected and not inherently a defect.\n");
        sb.append("\n### Needs human review\n\n");
        sb.append("- The highest fan-out classes in section 2 (likely orchestration hubs) - confirm they are ")
                .append("intended coordinators, not accidental god-classes.\n");
        sb.append("- Any non-trivial SCC or mutual package edge in section 6.\n");
        sb.append("- Intra-`runtime` subpackage coupling (policy/toolcall/turn/verification/trace) is invisible ")
                .append("at top-level granularity and should be reviewed with a finer slice pass before guarding.\n");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private record Boundary(String name, Predicate<String> src, Predicate<String> tgt) {
    }

    private static List<String> edgesMatching(Model m, Predicate<String> srcPkg, Predicate<String> tgtPkg) {
        List<String> out = new ArrayList<>();
        for (String edge : m.classEdges) {
            int bar = edge.indexOf('|');
            String a = edge.substring(0, bar);
            String b = edge.substring(bar + 1);
            String pa = m.fullPackageOf.get(a);
            String pb = m.fullPackageOf.get(b);
            if (pa != null && pb != null && srcPkg.test(pa) && tgtPkg.test(pb)) {
                out.add("`" + shortName(a) + "` -> `" + shortName(b) + "`");
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static String sample(TreeSet<String> set, int limit) {
        if (set == null || set.isEmpty()) {
            return "(none)";
        }
        List<String> shorts = new ArrayList<>();
        for (String s : set) {
            shorts.add("`" + shortName(s) + "`");
            if (shorts.size() >= limit) {
                break;
            }
        }
        String suffix = set.size() > limit ? " (+" + (set.size() - limit) + " more)" : "";
        return String.join(", ", shorts) + suffix;
    }

    /** Tarjan strongly connected components, deterministic ordering. */
    private static List<List<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};
        List<List<String>> result = new ArrayList<>();
        List<String> nodes = new ArrayList<>(graph.keySet());
        nodes.sort(Comparator.naturalOrder());
        Map<String, Integer> state = new LinkedHashMap<>();
        for (String n : nodes) {
            if (!index.containsKey(n)) {
                strongConnect(n, graph, index, low, stack, onStack, counter, result, state);
            }
        }
        result.sort(Comparator.comparing(scc -> scc.get(0)));
        return result;
    }

    private static void strongConnect(String v, Map<String, Set<String>> graph, Map<String, Integer> index,
            Map<String, Integer> low, Deque<String> stack, Set<String> onStack, int[] counter,
            List<List<String>> result, Map<String, Integer> state) {
        // Iterative Tarjan to avoid recursion depth concerns; small graph but kept robust.
        Deque<String> callStack = new ArrayDeque<>();
        Deque<Integer> iterStack = new ArrayDeque<>();
        callStack.push(v);
        iterStack.push(0);
        List<List<String>> localNeighbors = new ArrayList<>();
        while (!callStack.isEmpty()) {
            String node = callStack.peek();
            int i = iterStack.pop();
            if (i == 0) {
                index.put(node, counter[0]);
                low.put(node, counter[0]);
                counter[0]++;
                stack.push(node);
                onStack.add(node);
            }
            List<String> neighbors = new ArrayList<>(graph.getOrDefault(node, Set.of()));
            neighbors.sort(Comparator.naturalOrder());
            boolean recursed = false;
            while (i < neighbors.size()) {
                String w = neighbors.get(i);
                i++;
                if (!index.containsKey(w)) {
                    iterStack.push(i);
                    callStack.push(w);
                    iterStack.push(0);
                    recursed = true;
                    break;
                } else if (onStack.contains(w)) {
                    low.put(node, Math.min(low.get(node), index.get(w)));
                }
            }
            if (recursed) {
                continue;
            }
            // finished node
            if (low.get(node).equals(index.get(node))) {
                List<String> scc = new ArrayList<>();
                String w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    scc.add(w);
                } while (!w.equals(node));
                scc.sort(Comparator.naturalOrder());
                result.add(scc);
            }
            callStack.pop();
            if (!callStack.isEmpty()) {
                String parent = callStack.peek();
                low.put(parent, Math.min(low.get(parent), low.get(node)));
            }
        }
    }

    private static boolean isTalos(String pkg) {
        return pkg != null && (pkg.equals(ROOT) || pkg.startsWith(ROOT_PREFIX));
    }

    private static String stripArray(String name) {
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
        return n;
    }

    private static String topLevelClass(String name) {
        String n = stripArray(name);
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

    private static String shortName(String fqcn) {
        if (fqcn.startsWith(ROOT_PREFIX)) {
            return fqcn.substring(ROOT_PREFIX.length());
        }
        return fqcn;
    }
}
