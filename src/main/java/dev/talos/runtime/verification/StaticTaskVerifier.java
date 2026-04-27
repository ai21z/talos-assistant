package dev.talos.runtime.verification;

import dev.talos.runtime.TemplatePlaceholderGuard;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.VerificationStatus;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intent-light post-apply verifier for local static workspace facts.
 *
 * <p>This is deliberately narrower than the future TaskContract verifier. It
 * verifies observable post-mutation facts the current runtime already knows:
 * successful mutating targets, file-level verification metadata, placeholder
 * debris, and selector coherence for small HTML/CSS/JS workspaces when the
 * user asked for selector/linkage repair.
 */
public final class StaticTaskVerifier {

    private StaticTaskVerifier() {}

    private static final Set<String> SMALL_WORKSPACE_WEB_EXTS = Set.of(
            ".html", ".htm", ".css", ".js", ".ts", ".jsx", ".tsx"
    );

    private static final Pattern HTML_CLASS_ATTR = Pattern.compile(
            "\\bclass\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_ID_ATTR = Pattern.compile(
            "\\bid\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_LINK_HREF = Pattern.compile(
            "<link\\b[^>]*\\bhref\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_SCRIPT_SRC = Pattern.compile(
            "<script\\b[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_CLASS_SELECTOR = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern CSS_ID_SELECTOR = Pattern.compile("#([A-Za-z_][A-Za-z0-9_-]*)");
    private static final Pattern CSS_SELECTOR_PRELUDE = Pattern.compile("(?s)([^{}]+)\\{");
    private static final Pattern JS_QUERY_SELECTOR = Pattern.compile(
            "querySelector(?:All)?\\s*\\(\\s*['\"]([#.][A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");
    private static final Pattern JS_GET_BY_ID = Pattern.compile(
            "getElementById\\s*\\(\\s*['\"]([A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");
    private static final Pattern JS_GET_BY_CLASS = Pattern.compile(
            "getElementsByClassName\\s*\\(\\s*['\"]([A-Za-z_][A-Za-z0-9_-]*)['\"]\\s*\\)");
    private static final String[] HTML_STRUCTURAL_TAGS = {
            "html", "head", "body", "div", "span", "section", "article",
            "nav", "header", "footer", "main", "aside", "form", "button",
            "select", "textarea", "script", "style", "svg"
    };

    public static TaskVerificationResult verify(
            Path workspace,
            String userRequest,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        return verify(
                workspace,
                TaskContractResolver.fromUserRequest(userRequest),
                loopResult,
                extraMutationSuccesses);
    }

    public static TaskVerificationResult verify(
            Path workspace,
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        if (loopResult == null) {
            return TaskVerificationResult.notRun("No tool-loop result was available.");
        }

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        List<ToolCallLoop.ToolOutcome> successfulMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
        int totalMutationSuccesses = successfulMutations.size() + Math.max(0, extraMutationSuccesses);
        if (totalMutationSuccesses <= 0) {
            return TaskVerificationResult.notRun("No successful mutation was available to verify.");
        }
        if (workspace == null) {
            return TaskVerificationResult.unavailable(
                    "Workspace path was unavailable for post-apply verification.",
                    List.of(),
                    List.of("workspace path missing"));
        }
        if (successfulMutations.isEmpty()) {
            return TaskVerificationResult.unavailable(
                    "A mutation succeeded outside the structured tool-outcome path, so target files could not be verified.",
                    List.of(),
                    List.of("structured mutation targets unavailable"));
        }

        Path root = workspace.toAbsolutePath().normalize();
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> mutatedPaths = new LinkedHashSet<>();

        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            String pathHint = normalizePath(outcome.pathHint());
            if (pathHint.isBlank()) {
                problems.add(outcome.toolName() + " succeeded but did not expose a target path.");
                continue;
            }
            mutatedPaths.add(pathHint);
            verifyMutationTarget(root, pathHint, outcome.fileVerificationStatus(), facts, problems);
        }

        verifyExpectedTargets(contract, mutatedPaths, facts, problems);

        boolean webCoherenceRequired = shouldCheckWebCoherence(contract, root, mutatedPaths);
        if (shouldRequireSeparateWebAssetMutations(contract)) {
            verifyPrimaryWebMutationCoverage(mutatedPaths, facts, problems);
        }
        if (webCoherenceRequired) {
            verifySmallWebWorkspace(root, contract, facts, problems);
        }

        if (!problems.isEmpty()) {
            return TaskVerificationResult.failed(firstProblemSummary(problems), facts, problems);
        }
        if (webCoherenceRequired) {
            return TaskVerificationResult.passed(
                    "Static web coherence checks passed for " + mutatedPaths.size() + " mutated target(s).",
                    facts);
        }
        return TaskVerificationResult.readbackOnly(
                "Target/readback checks passed for " + mutatedPaths.size()
                        + " mutated target(s); no task-specific static verifier was applicable.",
                facts);
    }

    private static void verifyExpectedTargets(
            TaskContract contract,
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems
    ) {
        if (contract == null || contract.expectedTargets().isEmpty()) return;
        Set<String> normalizedMutations = new LinkedHashSet<>();
        for (String path : mutatedPaths) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) normalizedMutations.add(normalized);
        }
        boolean caseInsensitive = expectedTargetMatchingIsCaseInsensitive();
        for (String target : contract.expectedTargets()) {
            String expected = normalizePath(target);
            if (expected.isBlank()) continue;
            boolean matched = normalizedMutations.stream()
                    .anyMatch(mutated -> expectedTargetMatches(expected, mutated, caseInsensitive));
            if (!matched) {
                problems.add(expected + ": expected target was not successfully mutated.");
            }
        }
        if (problems.stream().noneMatch(p -> p.contains("expected target was not successfully mutated"))) {
            facts.add("Expected mutation target(s) were updated: "
                    + String.join(", ", contract.expectedTargets()) + ".");
        }
    }

    private static void verifyMutationTarget(
            Path root,
            String pathHint,
            VerificationStatus fileVerificationStatus,
            List<String> facts,
            List<String> problems
    ) {
        Path target;
        try {
            target = root.resolve(pathHint).normalize();
        } catch (InvalidPathException e) {
            problems.add(pathHint + ": target path is invalid (" + e.getMessage() + ")");
            return;
        }
        if (!target.startsWith(root)) {
            problems.add(pathHint + ": target path resolves outside the workspace.");
            return;
        }
        if (!Files.isRegularFile(target)) {
            problems.add(pathHint + ": mutated target is not a readable file after apply.");
            return;
        }
        String content;
        try {
            content = Files.readString(target);
        } catch (Exception e) {
            problems.add(pathHint + ": mutated target could not be read after apply (" + e.getMessage() + ")");
            return;
        }
        if (content.isBlank()) {
            problems.add(pathHint + ": mutated target is empty after apply.");
            return;
        }
        if (TemplatePlaceholderGuard.looksLikeTemplatePlaceholder(content)) {
            problems.add(pathHint + ": mutated target contains only a template placeholder.");
            return;
        }
        if (fileVerificationStatus != null && !fileVerificationStatus.acceptable()) {
            problems.add(pathHint + ": file-level verification reported " + fileVerificationStatus.label() + ".");
            return;
        }
        facts.add(pathHint + ": mutated target exists and is readable.");
    }

    private static void verifyPrimaryWebMutationCoverage(
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems
    ) {
        boolean mutatedHtml = mutatedPaths.stream().anyMatch(path -> hasExtension(path, ".html", ".htm"));
        boolean mutatedCss = mutatedPaths.stream().anyMatch(path -> hasExtension(path, ".css"));
        boolean mutatedJs = mutatedPaths.stream().anyMatch(path -> hasExtension(path, ".js"));
        if (!mutatedHtml) {
            problems.add("Expected web-app build to successfully mutate an HTML file.");
        }
        if (!mutatedCss) {
            problems.add("Expected web-app build to successfully mutate a CSS file.");
        }
        if (!mutatedJs) {
            problems.add("Expected web-app build to successfully mutate a JavaScript file.");
        }
        if (mutatedHtml && mutatedCss && mutatedJs) {
            facts.add("Expected HTML, CSS, and JavaScript targets were updated.");
        }
    }

    private static void verifySmallWebWorkspace(
            Path root,
            TaskContract contract,
            List<String> facts,
            List<String> problems
    ) {
        List<String> primary = obviousPrimaryFiles(root);
        if (primary.size() < 3) {
            problems.add("web coherence could not be checked because the workspace does not expose a small HTML/CSS/JS surface.");
            return;
        }
        if (!hasPrimaryWebSurface(primary)) {
            problems.add("web coherence could not be checked because HTML, CSS, and JavaScript primary files were not all present.");
            return;
        }

        SelectorFacts selectors = selectorFacts(root, primary);
        if (selectors == null) {
            problems.add("web coherence could not be checked because primary web files could not be read.");
            return;
        }

        problems.addAll(selectors.linkageProblems());
        problems.addAll(selectors.contentProblems());
        problems.addAll(selectors.selectorProblems());
        if (looksCalculatorOrFormTask(contract)) {
            List<String> formProblems = selectors.calculatorFormProblems(contract.originalUserRequest());
            problems.addAll(formProblems);
            if (formProblems.isEmpty()) {
                facts.add("Calculator/form static structure checks passed.");
            }
        }
        if (selectors.linkageProblems().isEmpty()
                && selectors.contentProblems().isEmpty()
                && selectors.selectorProblems().isEmpty()) {
            facts.add("HTML/CSS/JS selector coherence passed for "
                    + selectors.htmlFile() + ", " + selectors.cssFile() + ", and " + selectors.jsFile() + ".");
        }
    }

    public static List<String> obviousPrimaryFiles(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) return List.of();
        try {
            List<Path> files = new ArrayList<>();
            try (var stream = Files.list(workspace)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            }
            if (files.isEmpty() || files.size() > 5) return List.of();
            List<String> out = new ArrayList<>();
            for (Path file : files) {
                String name = file.getFileName() == null ? "" : file.getFileName().toString();
                if (name.isBlank() || name.startsWith(".")) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                int dot = lower.lastIndexOf('.');
                String ext = dot >= 0 ? lower.substring(dot) : "";
                if (!SMALL_WORKSPACE_WEB_EXTS.contains(ext)) return List.of();
                out.add(name.replace('\\', '/'));
            }
            return out.size() >= 2 ? out.stream().sorted().toList() : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    public static List<String> missingPrimaryReads(Path workspace, Collection<String> readPaths) {
        List<String> primary = obviousPrimaryFiles(workspace);
        if (primary.isEmpty()) return List.of();
        Set<String> read = new LinkedHashSet<>();
        if (readPaths != null) {
            for (String p : readPaths) {
                if (p == null || p.isBlank()) continue;
                String normalized = p.replace('\\', '/');
                int slash = normalized.lastIndexOf('/');
                read.add(slash >= 0 ? normalized.substring(slash + 1) : normalized);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String file : primary) {
            if (!read.contains(file)) missing.add(file);
        }
        return List.copyOf(missing);
    }

    public static String renderSelectorInspection(Path workspace, Collection<String> readPaths) {
        List<String> missing = missingPrimaryReads(workspace, readPaths);
        if (!missing.isEmpty()) return null;
        return renderSelectorInspection(workspace);
    }

    public static String renderSelectorInspection(Path workspace) {
        List<String> primary = obviousPrimaryFiles(workspace);
        if (!hasPrimaryWebSurface(primary)) return null;
        SelectorFacts facts = selectorFacts(workspace.toAbsolutePath().normalize(), primary);
        return facts == null ? null : facts.renderInspection();
    }

    public static String renderWebDiagnostics(Path workspace) {
        List<String> primary = obviousPrimaryFiles(workspace);
        if (!hasPrimaryWebSurface(primary)) return null;
        Path root = workspace.toAbsolutePath().normalize();
        SelectorFacts facts = selectorFacts(root, primary);
        if (facts == null) return null;

        List<String> problems = new ArrayList<>();
        try {
            String html = Files.readString(root.resolve(facts.htmlFile()));
            problems.addAll(htmlStructureProblems(facts.htmlFile(), html));
        } catch (Exception e) {
            problems.add(facts.htmlFile() + ": could not be read for HTML structure checks.");
        }
        problems.addAll(facts.linkageProblems());
        problems.addAll(facts.selectorProblems());

        StringBuilder out = new StringBuilder();
        out.append("I inspected the primary web files:\n\n");
        out.append("- HTML: `").append(facts.htmlFile()).append("`\n");
        out.append("- CSS: `").append(facts.cssFile()).append("`\n");
        out.append("- JavaScript: `").append(facts.jsFile()).append("`\n\n");

        if (problems.isEmpty()) {
            out.append("Static web diagnostics did not find obvious HTML/CSS/JavaScript linkage problems.");
        } else {
            out.append("Static web diagnostics found:\n");
            for (String problem : problems) {
                out.append("- ").append(problem).append('\n');
            }
        }
        out.append("\nNo files were changed.");
        return out.toString().stripTrailing();
    }

    private static boolean shouldCheckSelectorCoherence(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        if (lower.contains("selector") || lower.contains(".cta-button") || lower.contains("#cta-button")) {
            return true;
        }
        boolean namesWebParts = lower.contains("html")
                && (lower.contains("css") || lower.contains("stylesheet"))
                && (lower.contains("javascript") || lower.contains("script.js") || lower.contains("js"));
        boolean asksAlignment = lower.contains("match")
                || lower.contains("mismatch")
                || lower.contains("align")
                || lower.contains("linkage")
                || lower.contains("wire")
                || lower.contains("reference");
        return namesWebParts && asksAlignment;
    }

    private static boolean shouldCheckWebCoherence(
            TaskContract contract,
            Path root,
            Set<String> mutatedPaths
    ) {
        if (contract == null) return false;
        String request = contract.originalUserRequest();
        if (shouldCheckSelectorCoherence(request) || looksBroadWebTask(contract)) return true;
        return looksGenericMutationFollowUp(request) && mutatesSmallWebSurface(root, mutatedPaths);
    }

    private static boolean looksBroadWebTask(TaskContract contract) {
        if (contract == null) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        boolean mutatingTask = contract.mutationRequested();
        boolean mentionsWebSurface = lower.contains("website")
                || lower.contains("web app")
                || lower.contains("webpage")
                || lower.contains("web page")
                || lower.contains("index.html")
                || lower.contains(".html")
                || lower.contains(" html")
                || lower.startsWith("html")
                || lower.contains(" site")
                || lower.contains(" page");
        boolean mentionsStyle = lower.contains("css")
                || lower.contains(".css")
                || lower.contains("stylesheet")
                || lower.contains("style.css")
                || lower.contains("styles.css")
                || lower.contains("styling");
        boolean mentionsScript = lower.contains("javascript")
                || lower.contains(".js")
                || lower.contains("script.js")
                || lower.contains("scripts.js")
                || lower.contains("scripting")
                || lower.contains(" js ")
                || lower.endsWith(" js")
                || lower.contains("script file");
        boolean asksFunctional = lower.contains("functioning")
                || lower.contains("functional")
                || lower.contains("working")
                || lower.contains("interactive")
                || lower.contains("calculator")
                || lower.contains("form");
        return mutatingTask && mentionsWebSurface
                && ((mentionsStyle && mentionsScript) || asksFunctional);
    }

    private static boolean looksCalculatorOrFormTask(TaskContract contract) {
        if (!looksBroadWebTask(contract)) return false;
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("calculator")
                || lower.contains("bmi")
                || lower.contains("form")
                || lower.contains("input")
                || lower.contains("interactive")
                || lower.contains("functioning")
                || lower.contains("functional");
    }

    private static boolean shouldRequireSeparateWebAssetMutations(TaskContract contract) {
        if (!looksBroadWebTask(contract)) return false;
        String lower = contract.originalUserRequest().toLowerCase(Locale.ROOT);
        boolean createLike = contract.type() == TaskType.FILE_CREATE
                || lower.contains("build")
                || lower.contains("create")
                || lower.contains("generate")
                || lower.contains("scaffold")
                || lower.contains("set up")
                || lower.contains("setup");
        boolean separateAssets = (lower.contains("separate") || lower.contains("different files"))
                && (lower.contains("css") || lower.contains("styling"))
                && (lower.contains("javascript") || lower.contains("script") || lower.contains("scripting"));
        return createLike && separateAssets;
    }

    private static boolean looksGenericMutationFollowUp(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT).strip();
        return lower.equals("can you make it?")
                || lower.equals("make it")
                || lower.equals("make it please")
                || lower.equals("do it")
                || lower.equals("do it please")
                || lower.equals("make the edits please")
                || lower.equals("make the changes please")
                || lower.equals("apply it")
                || lower.equals("apply the changes")
                || lower.equals("fix it")
                || lower.equals("edit it");
    }

    private static boolean mutatesSmallWebSurface(Path root, Set<String> mutatedPaths) {
        if (root == null || mutatedPaths == null || mutatedPaths.isEmpty()) return false;
        if (mutatedPaths.stream().noneMatch(path -> hasExtension(path, ".html", ".htm", ".css", ".js"))) {
            return false;
        }
        return hasPrimaryWebSurface(obviousPrimaryFiles(root));
    }

    private static boolean hasPrimaryWebSurface(List<String> files) {
        return pickPrimary(files, ".html", ".htm") != null
                && pickPrimary(files, ".css") != null
                && pickPrimary(files, ".js") != null;
    }

    private static SelectorFacts selectorFacts(Path root, List<String> primaryFiles) {
        try {
            String htmlFile = pickPrimary(primaryFiles, ".html", ".htm");
            if (htmlFile == null) return null;
            String html = Files.readString(root.resolve(htmlFile));
            Set<String> htmlClasses = extractMatches(html, HTML_CLASS_ATTR, true);
            List<String> htmlIdOccurrences = extractMatchOccurrences(html, HTML_ID_ATTR, false);
            Set<String> htmlIds = new LinkedHashSet<>(htmlIdOccurrences);
            List<String> linkedCssOccurrences = extractLinkedAssetOccurrences(html, HTML_LINK_HREF, ".css");
            List<String> linkedJsOccurrences = extractLinkedAssetOccurrences(html, HTML_SCRIPT_SRC, ".js");
            Set<String> linkedCssFiles = new LinkedHashSet<>(linkedCssOccurrences);
            Set<String> linkedJsFiles = new LinkedHashSet<>(linkedJsOccurrences);
            String cssFile = pickLinkedOrPrimary(primaryFiles, linkedCssFiles, ".css");
            String jsFile = pickLinkedOrPrimary(primaryFiles, linkedJsFiles, ".js");
            if (cssFile == null || jsFile == null) return null;
            String css = Files.readString(root.resolve(cssFile));
            String js = Files.readString(root.resolve(jsFile));
            return new SelectorFacts(
                    htmlFile,
                    cssFile,
                    jsFile,
                    htmlClasses,
                    htmlIds,
                    htmlIdOccurrences,
                    extractCssSelectors(css, CSS_CLASS_SELECTOR),
                    extractCssSelectors(css, CSS_ID_SELECTOR),
                    extractBareClassSelectors(css, htmlClasses),
                    extractJsClasses(js),
                    extractJsIds(js),
                    linkedCssFiles,
                    linkedJsFiles,
                    linkedCssOccurrences,
                    linkedJsOccurrences,
                    html,
                    css,
                    js,
                    existingFileNames(root));
        } catch (Exception e) {
            return null;
        }
    }

    private record SelectorFacts(
            String htmlFile,
            String cssFile,
            String jsFile,
            Set<String> htmlClasses,
            Set<String> htmlIds,
            List<String> htmlIdOccurrences,
            Set<String> cssClasses,
            Set<String> cssIds,
            Set<String> cssBareClassSelectors,
            Set<String> jsClasses,
            Set<String> jsIds,
            Set<String> linkedCssFiles,
            Set<String> linkedJsFiles,
            List<String> linkedCssOccurrences,
            List<String> linkedJsOccurrences,
            String html,
            String css,
            String js,
            Set<String> existingFileNames
    ) {
        List<String> contentProblems() {
            List<String> out = new ArrayList<>();
            if (looksLikeNearPlaceholder(html, "html")) {
                out.add(htmlFile + ": HTML file appears to be placeholder content.");
            }
            if (looksLikeNearPlaceholder(css, "css")) {
                out.add(cssFile + ": CSS file appears to be placeholder content.");
            }
            if (looksLikeNearPlaceholder(js, "javascript")) {
                out.add(jsFile + ": JavaScript file appears to be placeholder content.");
            }
            return out;
        }

        List<String> selectorProblems() {
            List<String> out = new ArrayList<>();
            for (String id : duplicateValues(htmlIdOccurrences)) {
                out.add("HTML defines duplicate IDs: `#" + id + "`");
            }
            Set<String> cssMissingClasses = new LinkedHashSet<>(cssClasses);
            cssMissingClasses.removeAll(htmlClasses);
            Set<String> jsMissingClasses = new LinkedHashSet<>(jsClasses);
            jsMissingClasses.removeAll(htmlClasses);
            Set<String> cssMissingIds = new LinkedHashSet<>(cssIds);
            cssMissingIds.removeAll(htmlIds);
            Set<String> jsMissingIds = new LinkedHashSet<>(jsIds);
            jsMissingIds.removeAll(htmlIds);

            if (!cssMissingClasses.isEmpty()) {
                out.add("CSS references missing class selectors: " + renderSelectors(cssMissingClasses, "."));
            }
            if (!cssMissingIds.isEmpty()) {
                out.add("CSS references missing ID selectors: " + renderSelectors(cssMissingIds, "#"));
            }
            if (!cssBareClassSelectors.isEmpty()) {
                out.add("CSS likely uses bare element selectors where HTML defines classes: "
                        + renderBareClassSelectorHints(cssBareClassSelectors));
            }
            if (!jsMissingClasses.isEmpty()) {
                out.add("JavaScript references missing class selectors: " + renderSelectors(jsMissingClasses, "."));
            }
            if (!jsMissingIds.isEmpty()) {
                out.add("JavaScript references missing IDs: " + renderSelectors(jsMissingIds, "#"));
            }
            return out;
        }

        List<String> linkageProblems() {
            List<String> out = new ArrayList<>();
            for (String css : duplicateValues(linkedCssOccurrences)) {
                out.add("HTML links CSS file more than once: `" + css + "`");
            }
            for (String js : duplicateValues(linkedJsOccurrences)) {
                out.add("HTML links JavaScript file more than once: `" + js + "`");
            }
            if (!linkedCssFiles.contains(cssFile)) {
                out.add("HTML does not link CSS file: `" + cssFile + "`");
            }
            if (!linkedJsFiles.contains(jsFile)) {
                out.add("HTML does not link JavaScript file: `" + jsFile + "`");
            }
            for (String css : linkedCssFiles) {
                if (!existingFileNames.contains(css)) {
                    out.add("HTML references missing CSS file: `" + css + "`");
                }
            }
            for (String js : linkedJsFiles) {
                if (!existingFileNames.contains(js)) {
                    out.add("HTML references missing JavaScript file: `" + js + "`");
                }
            }
            return out;
        }

        List<String> calculatorFormProblems(String request) {
            String lowerHtml = html == null ? "" : html.toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if (!containsTag(lowerHtml, "form") && !containsTag(lowerHtml, "input")) {
                out.add("Calculator/form task is missing a form or input container.");
            }
            if (shouldExpectWeightHeightControls(request)) {
                if (!hasInputFor(lowerHtml, "weight")) {
                    out.add("Calculator/form task is missing a weight input.");
                }
                if (!hasInputFor(lowerHtml, "height")) {
                    out.add("Calculator/form task is missing a height input.");
                }
            }
            if (!containsTag(lowerHtml, "button") && !lowerHtml.contains("type=\"submit\"")
                    && !lowerHtml.contains("type='submit'")) {
                out.add("Calculator/form task is missing a submit/calculate button.");
            }
            if (!hasResultOutput(lowerHtml)) {
                out.add("Calculator/form task is missing a result output element.");
            }
            return out;
        }

        String renderInspection() {
            StringBuilder out = new StringBuilder();
            out.append("I checked the selectors against the actual workspace files:\n\n");
            out.append("- HTML: `").append(htmlFile).append("`\n");
            out.append("- CSS: `").append(cssFile).append("`\n");
            out.append("- JavaScript: `").append(jsFile).append("`\n\n");

            out.append("Observed in HTML:\n");
            out.append("- Classes: ").append(renderObserved(htmlClasses)).append('\n');
            out.append("- IDs: ").append(renderObserved(htmlIds)).append("\n\n");

            List<String> mismatches = new ArrayList<>();
            mismatches.addAll(linkageProblems());
            mismatches.addAll(selectorProblems());
            if (mismatches.isEmpty()) {
                out.append("Conclusion: I did not find selector mismatches in these files.");
            } else {
                out.append("Mismatches found:\n");
                for (String mismatch : mismatches) {
                    out.append("- ").append(mismatch).append('\n');
                }
            }
            return out.toString().stripTrailing();
        }
    }

    private static List<String> htmlStructureProblems(String htmlFile, String html) {
        if (html == null || html.isBlank()) {
            return List.of(htmlFile + ": HTML file is empty.");
        }
        String lower = html.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        Set<String> malformedClosings = malformedClosingTags(lower);
        for (String tag : malformedClosings) {
            out.add(htmlFile + ": malformed closing tag `</" + tag + ">` is missing `>`.");
        }
        for (String tag : HTML_STRUCTURAL_TAGS) {
            int opens = countCompleteTag(lower, "<" + tag, tag.length() + 1);
            int closes = countCompleteTag(lower, "</" + tag, tag.length() + 2);
            if (opens > closes && !malformedClosings.contains(tag)) {
                out.add(htmlFile + ": unclosed `<" + tag + ">` tag (" + (opens - closes)
                        + " open without close).");
            }
        }
        return out;
    }

    private static Set<String> malformedClosingTags(String lowerHtml) {
        Set<String> out = new LinkedHashSet<>();
        if (lowerHtml == null || lowerHtml.isBlank()) return out;
        int idx = lowerHtml.indexOf("</");
        while (idx >= 0) {
            int nameStart = idx + 2;
            int pos = nameStart;
            while (pos < lowerHtml.length()) {
                char c = lowerHtml.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '-' || c == ':') {
                    pos++;
                } else {
                    break;
                }
            }
            if (pos > nameStart) {
                String tag = lowerHtml.substring(nameStart, pos);
                int after = pos;
                while (after < lowerHtml.length() && Character.isWhitespace(lowerHtml.charAt(after))) {
                    after++;
                }
                if (after >= lowerHtml.length() || lowerHtml.charAt(after) != '>') {
                    out.add(tag);
                }
            }
            idx = lowerHtml.indexOf("</", Math.max(idx + 2, pos));
        }
        return out;
    }

    private static int countCompleteTag(String lowerHtml, String tagStart, int afterTagOffset) {
        int count = 0;
        int idx = 0;
        while ((idx = lowerHtml.indexOf(tagStart, idx)) >= 0) {
            int after = idx + afterTagOffset;
            if (after >= lowerHtml.length()) break;
            char delimiter = lowerHtml.charAt(after);
            if (delimiter == '>' || delimiter == '/' || Character.isWhitespace(delimiter)) {
                int closeBracket = lowerHtml.indexOf('>', after);
                int nextTag = lowerHtml.indexOf('<', after);
                if (closeBracket >= 0 && (nextTag < 0 || closeBracket < nextTag)) {
                    count++;
                }
            }
            idx = after;
        }
        return count;
    }

    private static Set<String> extractMatches(String text, Pattern pattern, boolean splitOnWhitespace) {
        return new LinkedHashSet<>(extractMatchOccurrences(text, pattern, splitOnWhitespace));
    }

    private static List<String> extractMatchOccurrences(String text, Pattern pattern, boolean splitOnWhitespace) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value == null || value.isBlank()) continue;
            if (splitOnWhitespace) {
                for (String token : value.trim().split("\\s+")) {
                    if (!token.isBlank()) out.add(token);
                }
            } else {
                out.add(value.trim());
            }
        }
        return out;
    }

    private static Set<String> extractCssSelectors(String css, Pattern selectorPattern) {
        Set<String> out = new LinkedHashSet<>();
        if (css == null || css.isBlank()) return out;
        Matcher preludeMatcher = CSS_SELECTOR_PRELUDE.matcher(css);
        while (preludeMatcher.find()) {
            String prelude = preludeMatcher.group(1);
            if (prelude == null || prelude.isBlank()) continue;
            Matcher selectorMatcher = selectorPattern.matcher(prelude);
            while (selectorMatcher.find()) {
                String value = selectorMatcher.group(1);
                if (value != null && !value.isBlank()) out.add(value.trim());
            }
        }
        return out;
    }

    private static Set<String> extractBareClassSelectors(String css, Set<String> htmlClasses) {
        Set<String> out = new LinkedHashSet<>();
        if (css == null || css.isBlank() || htmlClasses == null || htmlClasses.isEmpty()) return out;
        Matcher preludeMatcher = CSS_SELECTOR_PRELUDE.matcher(css);
        while (preludeMatcher.find()) {
            String prelude = preludeMatcher.group(1);
            if (prelude == null || prelude.isBlank()) continue;
            for (String selector : prelude.split(",")) {
                String trimmed = selector.strip();
                if (htmlClasses.contains(trimmed)) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    private static boolean shouldExpectWeightHeightControls(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("bmi")
                || lower.contains("weight")
                || lower.contains("height");
    }

    private static boolean containsTag(String lowerHtml, String tag) {
        return lowerHtml != null && lowerHtml.contains("<" + tag);
    }

    private static boolean hasInputFor(String lowerHtml, String name) {
        if (lowerHtml == null || lowerHtml.isBlank()) return false;
        Pattern pattern = Pattern.compile("<input\\b[^>]*(id|name|placeholder|aria-label)\\s*=\\s*(['\"])[^'\"]*"
                + Pattern.quote(name.toLowerCase(Locale.ROOT))
                + "[^'\"]*\\2", Pattern.CASE_INSENSITIVE);
        return pattern.matcher(lowerHtml).find();
    }

    private static boolean hasResultOutput(String lowerHtml) {
        if (lowerHtml == null || lowerHtml.isBlank()) return false;
        return lowerHtml.contains("<output")
                || lowerHtml.contains("id=\"result\"")
                || lowerHtml.contains("id='result'")
                || lowerHtml.contains("class=\"result\"")
                || lowerHtml.contains("class='result'");
    }

    private static boolean looksLikeNearPlaceholder(String content, String kind) {
        if (content == null) return false;
        String trimmed = content.strip();
        if (trimmed.isEmpty()) return true;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        String commentless = lower
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)^\\s*//.*$", " ")
                .strip();
        if (commentless.isBlank()) return true;
        String normalized = lower.replaceAll("\\s+", " ");
        return normalized.contains("your " + kind + " logic here")
                || normalized.contains("your " + kind + " code here")
                || normalized.contains(kind + " logic here")
                || normalized.contains(kind + " code here")
                || normalized.contains("add " + kind + " here");
    }

    private static Set<String> extractJsClasses(String js) {
        Set<String> out = new LinkedHashSet<>();
        if (js == null || js.isBlank()) return out;
        Matcher qs = JS_QUERY_SELECTOR.matcher(js);
        while (qs.find()) {
            String selector = qs.group(1);
            if (selector != null && selector.startsWith(".")) out.add(selector.substring(1));
        }
        Matcher gcn = JS_GET_BY_CLASS.matcher(js);
        while (gcn.find()) {
            String cls = gcn.group(1);
            if (cls != null && !cls.isBlank()) out.add(cls);
        }
        return out;
    }

    private static Set<String> extractJsIds(String js) {
        Set<String> out = new LinkedHashSet<>();
        if (js == null || js.isBlank()) return out;
        Matcher qs = JS_QUERY_SELECTOR.matcher(js);
        while (qs.find()) {
            String selector = qs.group(1);
            if (selector != null && selector.startsWith("#")) out.add(selector.substring(1));
        }
        Matcher gid = JS_GET_BY_ID.matcher(js);
        while (gid.find()) {
            String id = gid.group(1);
            if (id != null && !id.isBlank()) out.add(id);
        }
        return out;
    }

    private static List<String> extractLinkedAssetOccurrences(String html, Pattern pattern, String extension) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isBlank()) return out;
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String value = matcher.group(2);
            if (value == null || value.isBlank()) continue;
            String normalized = value.replace('\\', '/').strip();
            int query = normalized.indexOf('?');
            if (query >= 0) normalized = normalized.substring(0, query);
            int hash = normalized.indexOf('#');
            if (hash >= 0) normalized = normalized.substring(0, hash);
            if (!normalized.toLowerCase(Locale.ROOT).endsWith(extension)) continue;
            int slash = normalized.lastIndexOf('/');
            out.add(slash >= 0 ? normalized.substring(slash + 1) : normalized);
        }
        return out;
    }

    private static Set<String> duplicateValues(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        if (values == null) return duplicates;
        for (String value : values) {
            if (!seen.add(value)) duplicates.add(value);
        }
        return duplicates;
    }

    private static Set<String> existingFileNames(Path root) {
        Set<String> out = new LinkedHashSet<>();
        try (var stream = Files.list(root)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName() == null ? "" : path.getFileName().toString())
                    .filter(name -> !name.isBlank())
                    .forEach(out::add);
        } catch (Exception ignored) {
            // Linkage verification will fail elsewhere if primary files cannot be read.
        }
        return out;
    }

    private static String pickPrimary(List<String> files, String... exts) {
        for (String file : files) {
            String lower = file.toLowerCase(Locale.ROOT);
            for (String ext : exts) {
                if (lower.endsWith(ext)) return file;
            }
        }
        return null;
    }

    private static String pickLinkedOrPrimary(List<String> files, Set<String> linkedFiles, String ext) {
        if (files == null || files.isEmpty()) return null;
        if (linkedFiles != null) {
            for (String linked : linkedFiles) {
                for (String file : files) {
                    if (file.equals(linked) && hasExtension(file, ext)) return file;
                }
            }
        }
        return pickPrimary(files, ext);
    }

    private static boolean hasExtension(String path, String... exts) {
        if (path == null || exts == null) return false;
        String lower = normalizePath(path).toLowerCase(Locale.ROOT);
        for (String ext : exts) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("./") && normalized.length() > 2) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    static boolean expectedTargetMatches(String expectedTarget, String mutatedPath, boolean caseInsensitive) {
        String expected = normalizePath(expectedTarget);
        String mutated = normalizePath(mutatedPath);
        if (expected.isBlank() || mutated.isBlank()) return false;
        if (caseInsensitive) {
            return expected.equalsIgnoreCase(mutated);
        }
        return expected.equals(mutated);
    }

    private static boolean expectedTargetMatchingIsCaseInsensitive() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String firstProblemSummary(List<String> problems) {
        if (problems == null || problems.isEmpty()) return "Static verification failed.";
        String summary = String.join("; ", problems.subList(0, Math.min(3, problems.size())));
        if (summary.length() > 220) summary = summary.substring(0, 217) + "...";
        return summary;
    }

    private static String renderObserved(Set<String> values) {
        if (values == null || values.isEmpty()) return "none";
        return values.stream().sorted().map(v -> "`" + v + "`").reduce((a, b) -> a + ", " + b).orElse("none");
    }

    private static String renderSelectors(Set<String> values, String prefix) {
        return values.stream().sorted().map(v -> "`" + prefix + v + "`")
                .reduce((a, b) -> a + ", " + b).orElse("none");
    }

    private static String renderBareClassSelectorHints(Set<String> values) {
        return values.stream()
                .sorted()
                .map(v -> "`" + v + "` should probably be `." + v + "`")
                .reduce((a, b) -> a + ", " + b)
                .orElse("none");
    }
}
