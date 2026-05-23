package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.CapabilityProfileRegistry;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;

import java.nio.file.Files;
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

    public record WebDiagnostics(
            String htmlFile,
            String cssFile,
            String jsFile,
            List<String> problems
    ) {
        public WebDiagnostics {
            htmlFile = htmlFile == null ? "" : htmlFile;
            cssFile = cssFile == null ? "" : cssFile;
            jsFile = jsFile == null ? "" : jsFile;
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public boolean available() {
            return !htmlFile.isBlank() && !cssFile.isBlank() && !jsFile.isBlank();
        }

        public List<String> primaryFiles() {
            if (!available()) return List.of();
            return List.of(htmlFile, cssFile, jsFile);
        }
    }

    private static final int MAX_STATIC_SELECTOR_SEARCH_MATCHES = 50;

    private static final Pattern STATIC_SELECTOR_LITERAL = Pattern.compile(
            "(?<![A-Za-z0-9_-])([.#][A-Za-z_][A-Za-z0-9_-]*)(?![A-Za-z0-9_-])");
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
        return verifyInternal(workspace, contract, loopResult, extraMutationSuccesses, true);
    }

    public static TaskVerificationResult verifyWithoutTraceEvents(
            Path workspace,
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        return verifyInternal(workspace, contract, loopResult, extraMutationSuccesses, false);
    }

    private static TaskVerificationResult verifyInternal(
            Path workspace,
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses,
            boolean recordExpectationTrace
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
        Set<String> expectedTargetExemptions = new LinkedHashSet<>();
        MutationTargetReadbackVerifier.Result mutationReadback =
                MutationTargetReadbackVerifier.verify(root, successfulMutations);
        facts.addAll(mutationReadback.facts());
        problems.addAll(mutationReadback.problems());
        mutatedPaths.addAll(mutationReadback.mutationTargets());
        WorkspaceOperationStaticVerifier.Result workspaceOperationVerification =
                WorkspaceOperationStaticVerifier.verify(root, mutationReadback.workspaceOperationPlans());
        facts.addAll(workspaceOperationVerification.facts());
        problems.addAll(workspaceOperationVerification.problems());
        mutatedPaths.addAll(workspaceOperationVerification.mutationTargets());
        expectedTargetExemptions.addAll(workspaceOperationVerification.expectedTargetExemptions());

        CapabilityProfile profile = CapabilityProfileRegistry.select(contract, root, mutatedPaths);
        boolean webCoherenceRequired = profile.staticWeb();

        TargetScopeStaticVerifier.Result targetScopeVerification = TargetScopeStaticVerifier.verify(
                contract,
                root,
                profile,
                mutatedPaths,
                expectedTargetExemptions,
                workspaceOperationVerification.expectedTargetAliases());
        facts.addAll(targetScopeVerification.facts());
        problems.addAll(targetScopeVerification.problems());
        TaskExpectationStaticVerifier.Result expectationVerification = TaskExpectationStaticVerifier.verify(
                contract,
                root,
                successfulMutations,
                recordExpectationTrace);
        facts.addAll(expectationVerification.facts());
        problems.addAll(expectationVerification.problems());
        ExactEditReplacementVerifier.Result exactEditVerification =
                ExactEditReplacementVerifier.verify(root, successfulMutations);
        facts.addAll(exactEditVerification.facts());
        problems.addAll(exactEditVerification.problems());
        SourceDerivedArtifactVerifier.Result sourceDerivedVerification =
                SourceDerivedArtifactVerifier.verify(contract, root);
        facts.addAll(sourceDerivedVerification.facts());
        problems.addAll(sourceDerivedVerification.problems());

        if (webCoherenceRequired) {
            String profileFact = StaticWebCapabilityProfile.profileFact(profile);
            if (!profileFact.isBlank()) facts.add(profileFact);
        }
        if (StaticWebCapabilityProfile.requiresSeparateAssetMutations(profile)) {
            verifyPrimaryWebMutationCoverage(mutatedPaths, facts, problems);
        }
        if (webCoherenceRequired) {
            verifySmallWebWorkspace(root, contract, profile, mutatedPaths, facts, problems);
        }

        return TaskVerificationOutcomeSelector.select(
                facts,
                problems,
                mutatedPaths.size(),
                webCoherenceRequired,
                expectationVerification,
                exactEditVerification,
                sourceDerivedVerification);
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
            CapabilityProfile profile,
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems
    ) {
        List<String> primary = obviousPrimaryFiles(root);
        if (primary.isEmpty()) {
            primary = targetAwarePrimaryFiles(root, mutatedPaths);
            if (!primary.isEmpty()) {
                facts.add("Target-aware web surface selected from successful web mutation: "
                        + String.join(", ", primary) + ".");
            }
        }
        if (primary.size() < 3) {
            if (!primary.isEmpty()
                    && profile.targetSurface().allowsFunctionalPartial()
                    && StaticWebCapabilityProfile.looksStyledWebTask(contract, mutatedPaths)) {
                StaticWebPartialVerifier.verifyStyledWebWorkspace(root, primary, facts, problems);
                if (!problems.isEmpty()) return;
                facts.add("Styled web checks passed for " + String.join(", ", primary) + ".");
                return;
            }
            if (!primary.isEmpty()
                    && profile.targetSurface().allowsFunctionalPartial()
                    && StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) {
                StaticWebPartialVerifier.verifyFunctionalWebWorkspace(root, contract, primary, facts, problems);
                if (!problems.isEmpty()) return;
                facts.add("Self-contained functional web checks passed for "
                        + String.join(", ", primary) + ".");
                return;
            }
            problems.add("web coherence could not be checked because the workspace does not expose a small HTML/CSS/JS surface.");
            return;
        }
        if (!hasPrimaryWebSurface(primary)) {
            if (profile.targetSurface().allowsFunctionalPartial()
                    && StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) {
                StaticWebPartialVerifier.verifyFunctionalWebWorkspace(root, contract, primary, facts, problems);
                if (!problems.isEmpty()) return;
                facts.add("Self-contained functional web checks passed for "
                        + String.join(", ", primary) + ".");
                return;
            }
            problems.add("web coherence could not be checked because HTML, CSS, and JavaScript primary files were not all present.");
            return;
        }

        StaticWebSelectorAnalyzer.Facts selectors = StaticWebSelectorAnalyzer.analyze(
                root,
                primary,
                preferredWebTargetFiles(contract, mutatedPaths));
        if (selectors == null) {
            problems.add("web coherence could not be checked because primary web files could not be read.");
            return;
        }

        problems.addAll(selectors.linkageProblems());
        problems.addAll(selectors.contentProblems());
        problems.addAll(selectors.selectorProblems());
        List<String> buttonBehaviorProblems = selectors.buttonResultBehaviorProblems(contract.originalUserRequest());
        problems.addAll(buttonBehaviorProblems);
        if (buttonBehaviorProblems.isEmpty()
                && StaticWebSelectorAnalyzer.expectsRunButtonResultClicked(contract.originalUserRequest())) {
            facts.add("Static button/result behavior passed for " + selectors.jsFile() + ".");
        }
        if (StaticWebCapabilityProfile.looksCalculatorOrFormTask(contract)) {
            List<String> formProblems = StaticWebStructureVerifier.calculatorFormProblems(
                    contract.originalUserRequest(), selectors.html());
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
        return StaticWebSurfaceDetector.obviousPrimaryFiles(workspace);
    }

    private static List<String> targetAwarePrimaryFiles(Path workspace, Collection<String> targetHints) {
        return StaticWebSurfaceDetector.targetAwarePrimaryFiles(workspace, targetHints);
    }

    private static List<String> preferredWebTargetFiles(TaskContract contract, Collection<String> mutatedPaths) {
        return StaticWebSurfaceDetector.preferredWebTargetFiles(
                contract == null ? null : contract.expectedTargets(),
                mutatedPaths);
    }

    public static List<String> missingPrimaryReads(Path workspace, Collection<String> readPaths) {
        return StaticWebSurfaceDetector.missingPrimaryReads(workspace, readPaths);
    }

    public static String renderSelectorInspection(Path workspace, Collection<String> readPaths) {
        List<String> missing = missingPrimaryReads(workspace, readPaths);
        if (!missing.isEmpty()) return null;
        return renderSelectorInspection(workspace);
    }

    public static String renderSelectorInspection(Path workspace) {
        List<String> primary = obviousPrimaryFiles(workspace);
        if (!hasPrimaryWebSurface(primary)) return null;
        StaticWebSelectorAnalyzer.Facts facts =
                StaticWebSelectorAnalyzer.analyze(workspace.toAbsolutePath().normalize(), primary);
        return facts == null ? null : facts.renderInspection();
    }

    public static String renderTargetAwareSelectorInspection(Path workspace, Collection<String> targetHints) {
        if (workspace == null || !Files.isDirectory(workspace)) return null;
        List<String> primary = obviousPrimaryFiles(workspace);
        if (!hasPrimaryWebSurface(primary)) {
            primary = targetAwarePrimaryFiles(workspace, targetHints);
        }
        if (!hasPrimaryWebSurface(primary)) return null;
        StaticWebSelectorAnalyzer.Facts facts = StaticWebSelectorAnalyzer.analyze(
                workspace.toAbsolutePath().normalize(),
                primary,
                preferredWebTargetFiles(null, targetHints));
        return facts == null ? null : facts.renderInspection();
    }

    public static String renderStaticSelectorSearch(Path workspace, String userRequest) {
        if (workspace == null || !Files.isDirectory(workspace)) return null;
        String selector = requestedStaticSelectorLiteral(userRequest);
        if (selector.isBlank()) return null;

        Path root = workspace.toAbsolutePath().normalize();
        List<Path> visibleFiles;
        try {
            visibleFiles = StaticWebSurfaceDetector.visibleRegularFiles(root);
        } catch (Exception e) {
            return null;
        }
        if (visibleFiles.isEmpty()
                || visibleFiles.size() > StaticWebSurfaceDetector.MAX_TARGET_AWARE_WORKSPACE_VISIBLE_FILES) {
            return null;
        }

        List<String> matches = new ArrayList<>();
        search:
        for (Path file : visibleFiles.stream()
                .sorted((a, b) -> StaticWebSurfaceDetector.visibleFileName(a)
                        .compareToIgnoreCase(StaticWebSurfaceDetector.visibleFileName(b)))
                .toList()) {
            String name = StaticWebSurfaceDetector.visibleFileName(file).replace('\\', '/');
            if (!StaticWebSurfaceDetector.isSmallWorkspaceWebFile(name)) continue;
            int lineNumber = 0;
            try (var lines = Files.lines(file)) {
                var it = lines.iterator();
                while (it.hasNext()) {
                    String line = it.next();
                    lineNumber++;
                    if (!line.contains(selector)) continue;
                    matches.add(name + ":" + lineNumber + " | " + truncateSelectorSearchLine(line.strip()));
                    if (matches.size() >= MAX_STATIC_SELECTOR_SEARCH_MATCHES) break search;
                }
            } catch (Exception ignored) {
                // Search is best-effort over visible static-web text files only.
            }
        }
        if (matches.isEmpty()) return null;
        return ("[Static selector search]\n" + String.join("\n", matches)).stripTrailing();
    }

    private static String requestedStaticSelectorLiteral(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return "";
        Matcher matcher = STATIC_SELECTOR_LITERAL.matcher(userRequest);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String truncateSelectorSearchLine(String line) {
        if (line == null) return "";
        return line.length() <= 240 ? line : line.substring(0, 237) + "...";
    }

    public static String renderWebDiagnostics(Path workspace) {
        return renderWebDiagnostics(workspace, List.of());
    }

    public static String renderWebDiagnostics(Path workspace, Collection<String> targetHints) {
        WebDiagnostics diagnostics = currentWebDiagnostics(workspace, null, targetHints);
        if (!diagnostics.available()) return null;

        StringBuilder out = new StringBuilder();
        out.append("I inspected the primary web files:\n\n");
        out.append("- HTML: `").append(diagnostics.htmlFile()).append("`\n");
        out.append("- CSS: `").append(diagnostics.cssFile()).append("`\n");
        out.append("- JavaScript: `").append(diagnostics.jsFile()).append("`\n\n");

        if (diagnostics.problems().isEmpty()) {
            out.append("Static web diagnostics did not find obvious HTML/CSS/JavaScript linkage problems.");
        } else {
            out.append("Static web diagnostics found:\n");
            for (String problem : diagnostics.problems()) {
                out.append("- ").append(problem).append('\n');
            }
        }
        out.append("\nNo files were changed.");
        return out.toString().stripTrailing();
    }

    public static String renderScriptImportInspection(Path workspace, String userRequest) {
        if (workspace == null || !Files.isDirectory(workspace)) return null;
        if (!StaticWebImportIntent.matches(userRequest)) return null;
        Set<String> extractedTargets = TaskContractResolver.extractExpectedTargets(userRequest);
        List<String> candidateScripts = StaticWebImportIntent.scriptCandidates(extractedTargets);
        if (candidateScripts.isEmpty()) return null;

        List<String> htmlTargets = StaticWebImportIntent.htmlTargets(extractedTargets);
        if (htmlTargets.isEmpty()) {
            htmlTargets = StaticWebImportIntent.htmlTargets(
                    StaticWebImportIntent.evidenceTargets(userRequest, extractedTargets));
        }
        if (htmlTargets.isEmpty()
                && userRequest != null
                && userRequest.toLowerCase(Locale.ROOT).contains("index.html")) {
            htmlTargets = List.of("index.html");
        }
        if (htmlTargets.isEmpty()) {
            htmlTargets = primaryHtmlTargets(workspace);
        }
        if (htmlTargets.isEmpty()) return null;

        Path root = workspace.toAbsolutePath().normalize();
        String htmlTarget = firstReadableWorkspaceTarget(root, htmlTargets);
        if (htmlTarget.isBlank()) return null;

        String html;
        try {
            html = Files.readString(root.resolve(htmlTarget));
        } catch (Exception e) {
            return null;
        }

        List<String> linkedScripts = StaticWebSelectorAnalyzer.linkedJavaScriptOccurrences(html);
        List<String> importedCandidates = importedCandidateScripts(candidateScripts, linkedScripts);
        return renderScriptImportAnswer(htmlTarget, candidateScripts, importedCandidates, linkedScripts);
    }

    private static List<String> primaryHtmlTargets(Path workspace) {
        return StaticWebSurfaceDetector.primaryHtmlTargets(workspace);
    }

    public static WebDiagnostics currentWebDiagnostics(Path workspace, TaskContract contract) {
        return currentWebDiagnostics(workspace, contract, Set.of());
    }

    public static WebDiagnostics currentWebDiagnostics(
            Path workspace,
            TaskContract contract,
            Collection<String> targetHints
    ) {
        List<String> primary = obviousPrimaryFiles(workspace);
        if (!hasPrimaryWebSurface(primary)) {
            primary = targetAwarePrimaryFiles(workspace, targetHints);
        }
        if (!hasPrimaryWebSurface(primary)) {
            return new WebDiagnostics("", "", "", List.of(
                    "web coherence could not be checked because HTML, CSS, and JavaScript primary files were not all present."));
        }
        Path root = workspace.toAbsolutePath().normalize();
        StaticWebSelectorAnalyzer.Facts facts = StaticWebSelectorAnalyzer.analyze(
                root,
                primary,
                preferredWebTargetFiles(contract, targetHints));
        if (facts == null) {
            return new WebDiagnostics("", "", "", List.of(
                    "web coherence could not be checked because primary web files could not be read."));
        }

        List<String> problems = new ArrayList<>();
        try {
            String html = Files.readString(root.resolve(facts.htmlFile()));
            problems.addAll(StaticWebStructureVerifier.htmlStructureProblems(facts.htmlFile(), html));
        } catch (Exception e) {
            problems.add(facts.htmlFile() + ": could not be read for HTML structure checks.");
        }
        problems.addAll(facts.linkageProblems());
        problems.addAll(facts.contentProblems());
        problems.addAll(facts.selectorProblems());
        problems.addAll(facts.genericButtonResultDiagnosticProblems());
        if (contract != null) {
            problems.addAll(facts.buttonResultBehaviorProblems(contract.originalUserRequest()));
            if (StaticWebCapabilityProfile.looksCalculatorOrFormTask(contract)) {
                problems.addAll(StaticWebStructureVerifier.calculatorFormProblems(
                        contract.originalUserRequest(), facts.html()));
            }
        }
        return new WebDiagnostics(facts.htmlFile(), facts.cssFile(), facts.jsFile(), problems);
    }

    private static String firstReadableWorkspaceTarget(Path root, List<String> targets) {
        if (root == null || targets == null || targets.isEmpty()) return "";
        for (String target : targets) {
            String normalized = normalizePath(target);
            if (normalized.isBlank()) continue;
            try {
                Path resolved = root.resolve(normalized).toAbsolutePath().normalize();
                if (resolved.startsWith(root) && Files.isRegularFile(resolved)) {
                    return normalized;
                }
            } catch (RuntimeException ignored) {
                // Try the next candidate target.
            }
        }
        return "";
    }

    private static List<String> importedCandidateScripts(
            List<String> candidateScripts,
            List<String> linkedScripts
    ) {
        if (candidateScripts == null || candidateScripts.isEmpty()
                || linkedScripts == null || linkedScripts.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String candidate : candidateScripts) {
            String candidateName = basename(candidate);
            for (String linked : linkedScripts) {
                if (candidateName.equalsIgnoreCase(basename(linked))) {
                    out.add(candidate);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static String renderScriptImportAnswer(
            String htmlTarget,
            List<String> candidateScripts,
            List<String> importedCandidates,
            List<String> linkedScripts
    ) {
        StringBuilder out = new StringBuilder("[Static web import check]\n\n");
        if (importedCandidates.isEmpty()) {
            if (candidateScripts.size() == 2) {
                out.append("Neither `").append(candidateScripts.get(0)).append("` nor `")
                        .append(candidateScripts.get(1)).append("` is imported by `")
                        .append(htmlTarget).append("`.");
            } else {
                out.append("None of the candidate script files ")
                        .append(formatBacktickList(candidateScripts))
                        .append(" are imported by `")
                        .append(htmlTarget).append("`.");
            }
        } else if (importedCandidates.size() == 1) {
            out.append("`").append(htmlTarget).append("` imports `")
                    .append(importedCandidates.get(0)).append("`.");
        } else {
            out.append("`").append(htmlTarget).append("` imports ")
                    .append(formatBacktickList(importedCandidates)).append(".");
        }
        out.append("\n\nCurrent script imports found in `").append(htmlTarget).append("`: ");
        out.append(linkedScripts == null || linkedScripts.isEmpty()
                ? "none."
                : formatBacktickList(linkedScripts) + ".");
        return out.toString();
    }

    private static String formatBacktickList(List<String> values) {
        if (values == null || values.isEmpty()) return "none";
        return values.stream()
                .map(value -> "`" + value + "`")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static boolean hasPrimaryWebSurface(List<String> files) {
        return StaticWebSurfaceDetector.hasPrimaryWebSurface(files);
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

    private static String basename(String path) {
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

}
