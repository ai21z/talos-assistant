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
import java.util.Map;
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

    private static final String STATIC_WEB_COHERENCE_NOT_APPLICABLE =
            "Static web coherence was not checked because ";

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
        return verifyWithEvidence(
                workspace,
                TaskContractResolver.fromUserRequest(userRequest),
                loopResult,
                extraMutationSuccesses).compatibilityResult();
    }

    public static TaskVerificationResult verify(
            Path workspace,
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        return verifyWithEvidence(workspace, contract, loopResult, extraMutationSuccesses).compatibilityResult();
    }

    public static TaskVerificationEvidence verifyWithEvidence(
            Path workspace,
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        return verifyInternal(
                workspace,
                contract,
                loopResult,
                extraMutationSuccesses,
                true,
                StaticWebRenderVerifier.unavailableRunner());
    }

    static TaskVerificationEvidence verifyWithEvidence(
            Path workspace,
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses,
            StaticWebRenderVerifier.RenderRunner renderRunner
    ) {
        return verifyInternal(
                workspace,
                contract,
                loopResult,
                extraMutationSuccesses,
                true,
                renderRunner);
    }

    public static TaskVerificationResult verifyWithoutTraceEvents(
            Path workspace,
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses
    ) {
        return verifyInternal(
                workspace,
                contract,
                loopResult,
                extraMutationSuccesses,
                false,
                StaticWebRenderVerifier.unavailableRunner()).compatibilityResult();
    }

    private static TaskVerificationEvidence verifyInternal(
            Path workspace,
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraMutationSuccesses,
            boolean recordExpectationTrace,
            StaticWebRenderVerifier.RenderRunner renderRunner
    ) {
        if (loopResult == null) {
            return TaskVerificationEvidence.postApply(
                    TaskVerificationResult.notRun("No tool-loop result was available."),
                    VerificationReport.empty());
        }

        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        List<ToolCallLoop.ToolOutcome> successfulMutations = outcomes.stream()
                .filter(ToolCallLoop.ToolOutcome::mutating)
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
        int totalMutationSuccesses = successfulMutations.size() + Math.max(0, extraMutationSuccesses);
        if (totalMutationSuccesses <= 0) {
            return TaskVerificationEvidence.postApply(
                    TaskVerificationResult.notRun("No successful mutation was available to verify."),
                    VerificationReport.empty());
        }
        if (workspace == null) {
            return TaskVerificationEvidence.postApply(
                    TaskVerificationResult.unavailable(
                            "Workspace path was unavailable for post-apply verification.",
                            List.of(),
                            List.of("workspace path missing")),
                    VerificationReport.empty());
        }
        if (successfulMutations.isEmpty()) {
            return TaskVerificationEvidence.postApply(
                    TaskVerificationResult.unavailable(
                            "A mutation succeeded outside the structured tool-outcome path, so target files could not be verified.",
                            List.of(),
                            List.of("structured mutation targets unavailable")),
                    VerificationReport.empty());
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
        boolean webCoherenceRequired;

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
        TaskSpecificVerifierRegistry.Result taskSpecificVerification =
                TaskSpecificVerifierRegistry.verify(
                        root,
                        contract,
                        profile,
                        mutatedPaths,
                        facts,
                        problems,
                        loopResult.readFileBodies(),
                        renderRunner);
        webCoherenceRequired = taskSpecificVerification.webCoherenceRequired();
        SourceDerivedArtifactVerifier.Result sourceDerivedVerification =
                taskSpecificVerification.sourceDerivedVerification();
        VerificationReport claimReport = taskSpecificVerification.report();

        TaskVerificationResult compatibilityResult = TaskVerificationOutcomeSelector.select(
                facts,
                problems,
                mutatedPaths.size(),
                webCoherenceRequired,
                expectationVerification,
                exactEditVerification,
                sourceDerivedVerification,
                claimReport);
        compatibilityResult = withCommandVerificationUpgrade(compatibilityResult, outcomes);
        return TaskVerificationEvidence.postApply(compatibilityResult, claimReport);
    }

    /**
     * T792: a user-approved, successful, verification-class run_command
     * ordered after the last successful mutation upgrades a would-be
     * READBACK_ONLY verdict to PASSED. Additive only - FAILED is never
     * overridden, and ambiguous evidence changes nothing (fail closed).
     */
    private static TaskVerificationResult withCommandVerificationUpgrade(
            TaskVerificationResult result,
            List<ToolCallLoop.ToolOutcome> outcomes) {
        if (result == null || result.status() != TaskVerificationStatus.READBACK_ONLY) {
            return result;
        }
        return CommandVerificationEvidence.verificationProfilePassedAfterMutations(outcomes)
                .map(profile -> {
                    String summary = "Command verification passed: " + profile + " exited 0.";
                    List<String> upgradedFacts = new ArrayList<>(result.facts());
                    upgradedFacts.add(summary);
                    return TaskVerificationResult.passed(summary, upgradedFacts);
                })
                .orElse(result);
    }

    static void verifyPrimaryWebMutationCoverage(
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

    static VerificationReport verifySmallWebWorkspace(
            Path root,
            TaskContract contract,
            CapabilityProfile profile,
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems,
            Map<String, String> readFileBodies
    ) {
        return verifySmallWebWorkspace(
                root,
                contract,
                profile,
                mutatedPaths,
                facts,
                problems,
                readFileBodies,
                StaticWebRenderVerifier.unavailableRunner());
    }

    static VerificationReport verifySmallWebWorkspace(
            Path root,
            TaskContract contract,
            CapabilityProfile profile,
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems,
            Map<String, String> readFileBodies,
            StaticWebRenderVerifier.RenderRunner renderRunner
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
            VerificationReport jsOnlySelectorReport = verifyJavaScriptOnlySelectorWorkspace(
                    root,
                    contract,
                    profile,
                    primary,
                    mutatedPaths,
                    facts,
                    problems);
            if (jsOnlySelectorReport != null) return jsOnlySelectorReport;
            if (!primary.isEmpty()
                    && profile.targetSurface().allowsFunctionalPartial()
                    && hasSelectorInteractionClaim(contract)) {
                VerificationReport report = verifyFunctionalInteractionWorkspace(
                        root,
                        contract,
                        primary,
                        mutatedPaths,
                        facts,
                        problems);
                if (report.hasRequiredClaims()) return report;
            }
            if (!primary.isEmpty()
                    && profile.targetSurface().allowsFunctionalPartial()
                    && StaticWebCapabilityProfile.looksStyledWebTask(contract, mutatedPaths)) {
                StaticWebPartialVerifier.verifyStyledWebWorkspace(root, primary, facts, problems);
                if (!problems.isEmpty()) return VerificationReport.empty();
                facts.add("Styled web checks passed for " + String.join(", ", primary) + ".");
                return VerificationReport.empty();
            }
            if (!primary.isEmpty()
                    && profile.targetSurface().allowsFunctionalPartial()
                    && StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) {
                StaticWebPartialVerifier.verifyFunctionalWebWorkspace(root, contract, primary, facts, problems);
                if (!problems.isEmpty()) return VerificationReport.empty();
                facts.add("Self-contained functional web checks passed for "
                        + String.join(", ", primary) + ".");
                return VerificationReport.empty();
            }
            if (staticWebCoherenceNotApplicableIsSafe(profile, primary, mutatedPaths)) {
                facts.add(STATIC_WEB_COHERENCE_NOT_APPLICABLE
                        + "the workspace does not expose a small HTML/CSS/JS surface.");
                return VerificationReport.empty();
            }
            problems.add("web coherence could not be checked because the workspace does not expose a small HTML/CSS/JS surface.");
            return VerificationReport.empty();
        }
        if (!hasPrimaryWebSurface(primary)) {
            VerificationReport jsOnlySelectorReport = verifyJavaScriptOnlySelectorWorkspace(
                    root,
                    contract,
                    profile,
                    primary,
                    mutatedPaths,
                    facts,
                    problems);
            if (jsOnlySelectorReport != null) return jsOnlySelectorReport;
            if (profile.targetSurface().allowsFunctionalPartial()
                    && hasSelectorInteractionClaim(contract)) {
                VerificationReport report = verifyFunctionalInteractionWorkspace(
                        root,
                        contract,
                        primary,
                        mutatedPaths,
                        facts,
                        problems);
                if (report.hasRequiredClaims()) return report;
            }
            if (profile.targetSurface().allowsFunctionalPartial()
                    && StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) {
                StaticWebPartialVerifier.verifyFunctionalWebWorkspace(root, contract, primary, facts, problems);
                if (!problems.isEmpty()) return VerificationReport.empty();
                facts.add("Self-contained functional web checks passed for "
                        + String.join(", ", primary) + ".");
                return VerificationReport.empty();
            }
            if (staticWebCoherenceNotApplicableIsSafe(profile, primary, mutatedPaths)) {
                facts.add(STATIC_WEB_COHERENCE_NOT_APPLICABLE
                        + "HTML, CSS, and JavaScript primary files were not all present.");
                return VerificationReport.empty();
            }
            problems.add("web coherence could not be checked because HTML, CSS, and JavaScript primary files were not all present.");
            return VerificationReport.empty();
        }

        StaticWebSelectorAnalyzer.Facts selectors = StaticWebSelectorAnalyzer.analyze(
                root,
                primary,
                preferredWebTargetFiles(contract, mutatedPaths));
        if (selectors == null) {
            problems.add("web coherence could not be checked because primary web files could not be read.");
            return VerificationReport.empty();
        }

        List<String> staticWebProblems = new ArrayList<>();
        staticWebProblems.addAll(selectors.linkageProblems());
        staticWebProblems.addAll(selectors.contentProblems());
        staticWebProblems.addAll(StaticWebTailwindCoherenceVerifier.problems(
                root,
                contract,
                selectors,
                mutatedPaths));
        staticWebProblems.addAll(StaticWebFrontendFrameworkAssetVerifier.problems(
                root,
                contract,
                mutatedPaths));
        StaticWebContentPreservationVerifier.Result contentPreservation =
                StaticWebContentPreservationVerifier.verify(contract, selectors, readFileBodies);
        facts.addAll(contentPreservation.facts());
        staticWebProblems.addAll(contentPreservation.problems());
        staticWebProblems.addAll(selectors.selectorProblems());
        List<String> buttonBehaviorProblems = selectors.buttonResultBehaviorProblems(contract.originalUserRequest());
        staticWebProblems.addAll(buttonBehaviorProblems);
        VerificationReport interactionReport = StaticWebInteractionVerifier.verify(
                contract.originalUserRequest(),
                selectors);
        VerificationReport browserBehaviorReport = StaticWebBrowserBehaviorVerifier.verify(
                root,
                contract.originalUserRequest(),
                selectors);
        interactionReport = VerificationReport.merge(interactionReport, browserBehaviorReport);
        StaticWebRemoteAssetVerifier.Result remoteAssetVerification =
                StaticWebRemoteAssetVerifier.verify(contract, selectors);
        interactionReport = VerificationReport.merge(interactionReport, remoteAssetVerification.report());
        staticWebProblems.addAll(remoteAssetVerification.blockingProblems());
        VerificationReport renderReport = StaticWebRenderVerifier.verify(root, contract, selectors, renderRunner);
        interactionReport = VerificationReport.merge(interactionReport, renderReport);
        if (renderReport.verifierResults().stream()
                .anyMatch(result -> result.proofKind() == ProofKind.RENDER_COMPARISON
                        && result.verdict() == VerificationVerdict.FAILED)) {
            staticWebProblems.addAll(renderReport.problems());
        }
        if (!interactionReport.hasRequiredClaims()
                && StaticWebInteractionVerifier.looksLikeStaticVerificationRepairWithoutBinding(
                contract.originalUserRequest())) {
            interactionReport = StaticWebInteractionVerifier.unavailableRepairClaimContext();
        }
        interactionReport = withoutSupersededStaticRuntimeLimitation(interactionReport);
        facts.addAll(interactionReport.facts());
        facts.addAll(interactionReport.limitations());
        if (interactionReport.hasRequiredFailure()) {
            staticWebProblems.addAll(interactionReport.problems());
        }
        if (buttonBehaviorProblems.isEmpty()
                && StaticWebSelectorAnalyzer.expectsRunButtonResultClicked(contract.originalUserRequest())) {
            facts.add("Static button/result behavior passed for " + selectors.jsFile() + ".");
        }
        if (StaticWebCapabilityProfile.looksCalculatorOrFormTask(contract)) {
            List<String> formProblems = StaticWebStructureVerifier.calculatorFormProblems(
                    contract.originalUserRequest(), selectors.html());
            staticWebProblems.addAll(formProblems);
            if (formProblems.isEmpty()) {
                facts.add("Calculator/form static structure checks passed.");
            }
        }
        StaticWebProblemScope.Result scopedProblems = StaticWebProblemScope.classify(
                contract,
                profile,
                mutatedPaths,
                staticWebProblems);
        problems.addAll(scopedProblems.blockingProblems());
        facts.addAll(scopedProblems.contextualFacts());
        if (selectors.linkageProblems().isEmpty()
                && selectors.contentProblems().isEmpty()
                && selectors.selectorProblems().isEmpty()) {
            facts.add("HTML/CSS/JS selector coherence passed for "
                    + selectors.htmlFile() + ", " + selectors.cssFile() + ", and " + selectors.jsFile() + ".");
        }
        return interactionReport;
    }

    private static VerificationReport verifyJavaScriptOnlySelectorWorkspace(
            Path root,
            TaskContract contract,
            CapabilityProfile profile,
            List<String> primary,
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems
    ) {
        if (root == null
                || contract == null
                || profile == null
                || !profile.targetSurface().allowsFunctionalPartial()
                || primary == null
                || primary.isEmpty()
                || !looksJavaScriptOnlySelectorEdit(contract, mutatedPaths)
                || !htmlImportsMutatedJavaScript(root, primary, mutatedPaths)) {
            return null;
        }

        StaticWebPartialVerifier.verifyFunctionalWebWorkspace(root, contract, primary, facts, problems);
        if (!problems.isEmpty()) return VerificationReport.empty();

        StaticWebSelectorAnalyzer.Facts selectors = StaticWebSelectorAnalyzer.analyzeFunctional(
                root,
                primary,
                preferredWebTargetFiles(contract, mutatedPaths));
        if (selectors == null) {
            problems.add("functional web interaction could not be checked because HTML/JavaScript primary files could not be read.");
            return VerificationReport.empty();
        }
        if (!pathCollectionContains(mutatedPaths, selectors.jsFile())) {
            problems.add("HTML does not link JavaScript file: `"
                    + firstJavaScriptMutation(mutatedPaths) + "`");
            return VerificationReport.empty();
        }

        List<String> staticWebProblems = new ArrayList<>();
        staticWebProblems.addAll(functionalLinkageProblems(selectors));
        staticWebProblems.addAll(functionalContentProblems(selectors));
        staticWebProblems.addAll(selectors.selectorProblems());
        problems.addAll(staticWebProblems);
        if (staticWebProblems.isEmpty()) {
            facts.add("HTML/JavaScript selector coherence passed for "
                    + selectors.htmlFile() + " and " + selectors.jsFile()
                    + "; CSS was not required for this JavaScript-only edit.");
        }
        return VerificationReport.empty();
    }

    private static boolean looksJavaScriptOnlySelectorEdit(TaskContract contract, Set<String> mutatedPaths) {
        if (contract == null || !contract.mutationRequested() || hasSelectorInteractionClaim(contract)) return false;
        if (mutatedPaths == null
                || mutatedPaths.isEmpty()
                || mutatedPaths.stream().anyMatch(path -> !hasExtension(path, ".js"))) {
            return false;
        }
        if (!contract.expectedTargets().isEmpty()
                && contract.expectedTargets().stream().anyMatch(path -> !hasExtension(path, ".js"))) {
            return false;
        }
        String request = contract.originalUserRequest();
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        if (lower.contains("css")
                || lower.contains("stylesheet")
                || lower.contains("style.css")
                || lower.contains("styles.css")
                || lower.contains("styling")) {
            return false;
        }
        return lower.contains("selector") || hasNonFileStaticSelectorLiteral(request);
    }

    private static boolean hasNonFileStaticSelectorLiteral(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        Matcher matcher = STATIC_SELECTOR_LITERAL.matcher(userRequest);
        while (matcher.find()) {
            String selector = matcher.group(1);
            if (selector == null || selector.isBlank()) continue;
            String lower = selector.toLowerCase(Locale.ROOT);
            if (!Set.of(".js", ".css", ".html", ".htm", ".json", ".md", ".txt").contains(lower)) {
                return true;
            }
        }
        return false;
    }

    private static boolean htmlImportsMutatedJavaScript(
            Path root,
            List<String> primary,
            Collection<String> mutatedPaths
    ) {
        if (root == null || primary == null || primary.isEmpty() || mutatedPaths == null || mutatedPaths.isEmpty()) {
            return false;
        }
        Set<String> mutatedJavaScript = new LinkedHashSet<>();
        for (String path : mutatedPaths) {
            if (hasExtension(path, ".js")) {
                String name = basename(path);
                if (!name.isBlank()) mutatedJavaScript.add(name);
            }
        }
        if (mutatedJavaScript.isEmpty()) return false;

        for (String htmlFile : StaticWebSurfaceDetector.primaryHtmlTargets(primary)) {
            try {
                String html = Files.readString(root.resolve(htmlFile));
                for (String linked : StaticWebSelectorAnalyzer.linkedJavaScriptOccurrences(html)) {
                    if (pathCollectionContains(mutatedJavaScript, linked)) return true;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static List<String> functionalLinkageProblems(StaticWebSelectorAnalyzer.Facts selectors) {
        if (selectors == null) return List.of();
        return selectors.linkageProblems().stream()
                .filter(problem -> !problem.contains("CSS file"))
                .toList();
    }

    private static List<String> functionalContentProblems(StaticWebSelectorAnalyzer.Facts selectors) {
        if (selectors == null) return List.of();
        return selectors.contentProblems().stream()
                .filter(problem -> !problem.contains("CSS file appears to be placeholder content"))
                .toList();
    }

    private static boolean pathCollectionContains(Collection<String> paths, String candidate) {
        if (paths == null || paths.isEmpty() || candidate == null || candidate.isBlank()) return false;
        boolean caseInsensitive = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        String normalizedCandidate = normalizePath(candidate);
        for (String path : paths) {
            String normalizedPath = normalizePath(path);
            if (caseInsensitive ? normalizedPath.equalsIgnoreCase(normalizedCandidate)
                    : normalizedPath.equals(normalizedCandidate)) {
                return true;
            }
        }
        return false;
    }

    private static String firstJavaScriptMutation(Collection<String> mutatedPaths) {
        if (mutatedPaths == null || mutatedPaths.isEmpty()) return "";
        return mutatedPaths.stream()
                .filter(path -> hasExtension(path, ".js"))
                .findFirst()
                .orElse("");
    }

    private static boolean hasSelectorInteractionClaim(TaskContract contract) {
        return contract != null
                && StaticWebInteractionVerifier.detectBinding(contract.originalUserRequest()).isPresent();
    }

    private static VerificationReport verifyFunctionalInteractionWorkspace(
            Path root,
            TaskContract contract,
            List<String> primary,
            Set<String> mutatedPaths,
            List<String> facts,
            List<String> problems
    ) {
        StaticWebPartialVerifier.verifyFunctionalWebWorkspace(root, contract, primary, facts, problems);
        if (!problems.isEmpty()) return VerificationReport.empty();

        StaticWebSelectorAnalyzer.Facts selectors = StaticWebSelectorAnalyzer.analyzeFunctional(
                root,
                primary,
                preferredWebTargetFiles(contract, mutatedPaths));
        if (selectors == null) {
            problems.add("functional web interaction could not be checked because HTML/JavaScript primary files could not be read.");
            return VerificationReport.empty();
        }

        VerificationReport interactionReport = StaticWebInteractionVerifier.verify(
                contract.originalUserRequest(),
                selectors);
        VerificationReport browserBehaviorReport = StaticWebBrowserBehaviorVerifier.verify(
                root,
                contract.originalUserRequest(),
                selectors);
        interactionReport = VerificationReport.merge(interactionReport, browserBehaviorReport);
        StaticWebRemoteAssetVerifier.Result remoteAssetVerification =
                StaticWebRemoteAssetVerifier.verify(contract, selectors);
        interactionReport = VerificationReport.merge(interactionReport, remoteAssetVerification.report());
        problems.addAll(remoteAssetVerification.blockingProblems());
        if (!interactionReport.hasRequiredClaims()
                && StaticWebInteractionVerifier.looksLikeStaticVerificationRepairWithoutBinding(
                contract.originalUserRequest())) {
            interactionReport = StaticWebInteractionVerifier.unavailableRepairClaimContext();
        }
        interactionReport = withoutSupersededStaticRuntimeLimitation(interactionReport);
        facts.addAll(interactionReport.facts());
        facts.addAll(interactionReport.limitations());
        if (interactionReport.hasRequiredFailure()) {
            problems.addAll(interactionReport.problems());
        }
        if (interactionReport.requiredClaimsSatisfied()) {
            facts.add("Functional web interaction checks passed for " + selectors.htmlFile()
                    + " and " + selectors.jsFile() + ".");
        }
        return interactionReport;
    }

    private static VerificationReport withoutSupersededStaticRuntimeLimitation(VerificationReport report) {
        if (report == null
                || !report.authoritativeProofKinds().contains(ProofKind.BROWSER_BEHAVIOR.name())) {
            return report;
        }
        List<ClaimResult> claimResults = report.claimResults().stream()
                .map(StaticTaskVerifier::withoutSupersededStaticRuntimeLimitation)
                .toList();
        return new VerificationReport(
                claimResults,
                report.verifierResults(),
                report.facts(),
                report.problems(),
                withoutSupersededStaticRuntimeLimitations(report.limitations()));
    }

    private static ClaimResult withoutSupersededStaticRuntimeLimitation(ClaimResult result) {
        if (result == null) return null;
        return new ClaimResult(
                result.claim(),
                result.obligation(),
                result.verdict(),
                result.proofKind(),
                result.authority(),
                result.coverage(),
                result.facts(),
                result.problems(),
                withoutSupersededStaticRuntimeLimitations(result.limitations()));
    }

    private static List<String> withoutSupersededStaticRuntimeLimitations(List<String> limitations) {
        if (limitations == null || limitations.isEmpty()) return List.of();
        return limitations.stream()
                .filter(limit -> limit == null || !limit.contains("browser/runtime behavior was not executed"))
                .toList();
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

    public static String renderReadOnlyWebDiagnostics(
            Path workspace,
            Collection<String> readPaths,
            String userRequest
    ) {
        if (workspace == null || !Files.isDirectory(workspace)) return null;
        List<String> inspected = inspectedStaticWebReadFiles(workspace, readPaths);
        if (inspected.isEmpty()) return null;
        Path root = workspace.toAbsolutePath().normalize();
        if (!hasReadFileWithExtension(inspected, ".js", ".jsx", ".ts", ".tsx")) {
            return renderHtmlOnlyReadOnlyWebDiagnostics(root, inspected, userRequest);
        }
        StaticWebSelectorAnalyzer.Facts facts = StaticWebSelectorAnalyzer.analyzeFunctional(
                root,
                inspected,
                inspected);
        if (facts == null) return null;

        boolean cssInspected = !facts.cssFile().isBlank() && containsTarget(inspected, facts.cssFile());
        boolean jsInspected = !facts.jsFile().isBlank() && containsTarget(inspected, facts.jsFile());
        StringBuilder out = new StringBuilder();
        out.append("I inspected the current-turn static web files:\n\n");
        out.append("- HTML: `").append(facts.htmlFile()).append("`\n");
        if (cssInspected) {
            out.append("- CSS: `").append(facts.cssFile()).append("`\n");
        }
        if (jsInspected) {
            out.append("- JavaScript: `").append(facts.jsFile()).append("`\n");
        }
        out.append('\n');

        List<String> problems = readOnlyWebDiagnosticProblems(facts, cssInspected, jsInspected, userRequest);
        boolean planRequest = looksLikePlanRequest(userRequest);
        if (problems.isEmpty() && !planRequest) {
            return null;
        }
        if (problems.isEmpty()) {
            out.append("Static web diagnostics did not find obvious problems in the inspected evidence.");
        } else {
            out.append("Static web diagnostics found:\n");
            for (String problem : problems) {
                out.append("- ").append(problem).append('\n');
            }
        }
        if (planRequest) {
            appendReadOnlyWebPlan(out, facts, cssInspected, jsInspected, problems);
        }
        out.append("\nNo files were changed.");
        return out.toString().stripTrailing();
    }

    private static String renderHtmlOnlyReadOnlyWebDiagnostics(
            Path root,
            List<String> inspected,
            String userRequest
    ) {
        String htmlFile = inspected.stream()
                .filter(path -> hasExtension(path, ".html", ".htm"))
                .findFirst()
                .orElse("");
        if (htmlFile.isBlank()) return null;
        String html;
        try {
            html = Files.readString(root.resolve(htmlFile));
        } catch (Exception e) {
            return null;
        }

        StringBuilder out = new StringBuilder();
        out.append("I inspected the current-turn static web files:\n\n");
        out.append("- HTML: `").append(htmlFile).append("`\n\n");

        List<String> problems = new ArrayList<>();
        problems.addAll(StaticWebStructureVerifier.htmlStructureProblems(htmlFile, html));
        List<String> linkedScripts = StaticWebSelectorAnalyzer.linkedJavaScriptOccurrences(html);
        if (linkedScripts.isEmpty()) {
            problems.add(htmlFile + ": inspected HTML does not link a local JavaScript file.");
        } else {
            problems.add(htmlFile + ": linked JavaScript was not inspected in this turn: "
                    + formatBacktickList(linkedScripts) + ".");
        }

        out.append("Static web diagnostics found:\n");
        for (String problem : problems) {
            out.append("- ").append(problem).append('\n');
        }
        if (looksLikePlanRequest(userRequest)) {
            out.append("\nImplementation plan:\n");
            out.append("1. Switch to `/mode agent` before making changes.\n");
            out.append("2. Inspect the JavaScript file linked by `").append(htmlFile)
                    .append("` or add the intended script link if none exists.\n");
            out.append("3. Update the smallest HTML or JavaScript target needed for the button behavior.\n");
            out.append("4. Verify the button interaction after the edit.\n");
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

    private static List<String> inspectedStaticWebReadFiles(Path workspace, Collection<String> readPaths) {
        if (workspace == null || readPaths == null || readPaths.isEmpty()) return List.of();
        Path root = workspace.toAbsolutePath().normalize();
        List<String> out = new ArrayList<>();
        for (String path : readPaths) {
            String normalized = normalizePath(path);
            if (normalized.isBlank() || !StaticWebSurfaceDetector.isSmallWorkspaceWebFile(normalized)) continue;
            try {
                Path resolved = root.resolve(normalized).toAbsolutePath().normalize();
                if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) continue;
                String relative = root.relativize(resolved).toString().replace('\\', '/');
                if (relative.contains("/")) continue;
                if (!out.contains(relative)) out.add(relative);
            } catch (RuntimeException ignored) {
                // Ignore malformed read-path hints; trace evidence remains authoritative elsewhere.
            }
        }
        return List.copyOf(out);
    }

    private static List<String> readOnlyWebDiagnosticProblems(
            StaticWebSelectorAnalyzer.Facts facts,
            boolean cssInspected,
            boolean jsInspected,
            String userRequest
    ) {
        List<String> problems = new ArrayList<>();
        try {
            problems.addAll(StaticWebStructureVerifier.htmlStructureProblems(facts.htmlFile(), facts.html()));
        } catch (RuntimeException ignored) {
            problems.add(facts.htmlFile() + ": could not be checked for HTML structure problems.");
        }
        problems.addAll(filterReadOnlyLinkageProblems(facts.linkageProblems(), cssInspected, jsInspected));
        problems.addAll(filterReadOnlyContentProblems(facts.contentProblems(), facts, cssInspected, jsInspected));
        problems.addAll(filterReadOnlySelectorProblems(facts.selectorProblems(), cssInspected, jsInspected));
        if (jsInspected) {
            problems.addAll(facts.genericButtonResultDiagnosticProblems());
            problems.addAll(facts.buttonResultBehaviorProblems(userRequest));
        }
        return List.copyOf(problems);
    }

    private static List<String> filterReadOnlyLinkageProblems(
            List<String> problems,
            boolean cssInspected,
            boolean jsInspected
    ) {
        if (problems == null || problems.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String problem : problems) {
            String lower = problem == null ? "" : problem.toLowerCase(Locale.ROOT);
            if (lower.contains("css") && !cssInspected) continue;
            if (lower.contains("javascript") && !jsInspected) continue;
            out.add(problem);
        }
        return List.copyOf(out);
    }

    private static List<String> filterReadOnlyContentProblems(
            List<String> problems,
            StaticWebSelectorAnalyzer.Facts facts,
            boolean cssInspected,
            boolean jsInspected
    ) {
        if (problems == null || problems.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String problem : problems) {
            if (problem == null || problem.isBlank()) continue;
            String lower = problem.toLowerCase(Locale.ROOT);
            if (!cssInspected && lower.contains("css")) continue;
            if (!jsInspected && lower.contains("javascript")) continue;
            if (!cssInspected && !facts.cssFile().isBlank() && problem.startsWith(facts.cssFile() + ":")) continue;
            if (!jsInspected && !facts.jsFile().isBlank() && problem.startsWith(facts.jsFile() + ":")) continue;
            out.add(problem);
        }
        return List.copyOf(out);
    }

    private static List<String> filterReadOnlySelectorProblems(
            List<String> problems,
            boolean cssInspected,
            boolean jsInspected
    ) {
        if (problems == null || problems.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String problem : problems) {
            if (problem == null || problem.isBlank()) continue;
            if (!cssInspected && problem.startsWith("CSS ")) continue;
            if (!jsInspected && problem.startsWith("JavaScript ")) continue;
            out.add(problem);
        }
        return List.copyOf(out);
    }

    private static boolean containsTarget(Collection<String> targets, String target) {
        if (targets == null || target == null || target.isBlank()) return false;
        String normalizedTarget = normalizePath(target);
        for (String candidate : targets) {
            if (normalizePath(candidate).equals(normalizedTarget)) return true;
        }
        return false;
    }

    private static boolean hasReadFileWithExtension(Collection<String> targets, String... exts) {
        if (targets == null || targets.isEmpty()) return false;
        for (String target : targets) {
            if (hasExtension(target, exts)) return true;
        }
        return false;
    }

    private static boolean looksLikePlanRequest(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return lower.contains("plan")
                || lower.contains("fix")
                || lower.contains("repair")
                || lower.contains("how would")
                || lower.contains("how to");
    }

    private static void appendReadOnlyWebPlan(
            StringBuilder out,
            StaticWebSelectorAnalyzer.Facts facts,
            boolean cssInspected,
            boolean jsInspected,
            List<String> problems
    ) {
        out.append("\nImplementation plan:\n");
        if (problems == null || problems.isEmpty()) {
            out.append("1. Switch to `/mode agent` before making changes.\n");
            out.append("2. Reproduce the button behavior and inspect any additional asset involved.\n");
            out.append("3. Apply the smallest file change and verify the page behavior.\n");
            return;
        }
        out.append("1. Switch to `/mode agent` before making changes.\n");
        if (jsInspected) {
            out.append("2. Update `").append(facts.jsFile())
                    .append("` so its selectors and button handler match elements present in `")
                    .append(facts.htmlFile()).append("`.\n");
        } else {
            out.append("2. Inspect the JavaScript file linked from `").append(facts.htmlFile())
                    .append("`, then update the selector or handler causing the issue.\n");
        }
        if (!cssInspected && !facts.cssFile().isBlank()) {
            out.append("3. Inspect `").append(facts.cssFile())
                    .append("` before making any styling-specific change; it was not read in this turn.\n");
            out.append("4. Verify the button interaction after the edit.\n");
        } else {
            out.append("3. Verify the button interaction after the edit.\n");
        }
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

    static boolean addedStaticWebCoherenceNotApplicableFact(List<String> facts, int fromIndex) {
        if (facts == null || facts.isEmpty()) return false;
        int start = Math.max(0, fromIndex);
        for (int i = start; i < facts.size(); i++) {
            String fact = facts.get(i);
            if (fact != null && fact.startsWith(STATIC_WEB_COHERENCE_NOT_APPLICABLE)) {
                return true;
            }
        }
        return false;
    }

    private static boolean staticWebCoherenceNotApplicableIsSafe(
            CapabilityProfile profile,
            List<String> primary,
            Set<String> mutatedPaths
    ) {
        if (profile == null || !profile.targetSurface().allowsFunctionalPartial()) return false;
        if (primary != null && !primary.isEmpty()) return true;
        return singleHtmlMutation(mutatedPaths);
    }

    private static boolean singleHtmlMutation(Set<String> mutatedPaths) {
        if (mutatedPaths == null || mutatedPaths.size() != 1) return false;
        String only = mutatedPaths.iterator().next();
        return hasExtension(only, ".html", ".htm");
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
