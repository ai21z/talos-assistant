package dev.talos.runtime.verification;

import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.runtime.TemplatePlaceholderGuard;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.capability.ArtifactOperation;
import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.CapabilityProfileRegistry;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.expectation.AppendLineExpectation;
import dev.talos.runtime.expectation.BulletListExpectation;
import dev.talos.runtime.expectation.ExpectationVerificationStatus;
import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
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

    private static final Pattern HTML_INLINE_SCRIPT = Pattern.compile(
            "(?is)<script\\b(?![^>]*\\bsrc\\s*=)[^>]*>(.*?)</script>");
    private static final Pattern HTML_INLINE_STYLE = Pattern.compile(
            "(?is)<style\\b[^>]*>(.*?)</style>");
    private static final Pattern STATIC_SELECTOR_LITERAL = Pattern.compile(
            "(?<![A-Za-z0-9_-])([.#][A-Za-z_][A-Za-z0-9_-]*)(?![A-Za-z0-9_-])");
    private static final Pattern WORD_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{3,}");
    private static final String[] HTML_STRUCTURAL_TAGS = {
            "html", "head", "body", "div", "span", "section", "article",
            "nav", "header", "footer", "main", "aside", "form", "button",
            "select", "textarea", "script", "style", "svg"
    };
    private static final Set<String> SOURCE_DERIVED_STOP_WORDS = Set.of(
            "about", "after", "also", "avoid", "before", "bullet", "bullets",
            "called", "clear", "concise", "content", "contents", "create",
            "depend", "depends", "document", "file", "from", "into", "keep",
            "line", "long", "mention", "notes", "point", "points", "private",
            "read", "record", "records", "says", "secret", "secrets", "short",
            "source", "summarize", "summary", "target", "text", "that", "their",
            "them", "this", "under", "with", "write");
    private static final Set<String> SOURCE_DERIVED_ALLOWED_OUTPUT_TERMS = Set.of(
            "based", "brief", "client", "coverage", "data", "derived", "document",
            "evidence", "exact", "extracted", "file", "includes", "notes", "output",
            "phrase", "phrases", "report", "source", "sources", "spreadsheet",
            "summary", "workbook");

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
        List<WorkspaceOperationPlan> workspaceOperationPlans = new ArrayList<>();

        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            WorkspaceOperationPlan workspaceOperationPlan = outcome.workspaceOperationPlan();
            if (workspaceOperationPlan != null && !workspaceOperationPlan.pathEffects().isEmpty()) {
                workspaceOperationPlans.add(workspaceOperationPlan);
                continue;
            }
            String pathHint = normalizePath(outcome.pathHint());
            if (pathHint.isBlank()) {
                problems.add(outcome.toolName() + " succeeded but did not expose a target path.");
                continue;
            }
            mutatedPaths.add(pathHint);
            verifyMutationTarget(root, pathHint, outcome.fileVerificationStatus(), facts, problems);
        }
        WorkspaceOperationStaticVerifier.Result workspaceOperationVerification =
                WorkspaceOperationStaticVerifier.verify(root, workspaceOperationPlans);
        facts.addAll(workspaceOperationVerification.facts());
        problems.addAll(workspaceOperationVerification.problems());
        mutatedPaths.addAll(workspaceOperationVerification.mutationTargets());
        expectedTargetExemptions.addAll(workspaceOperationVerification.expectedTargetExemptions());

        CapabilityProfile profile = CapabilityProfileRegistry.select(contract, root, mutatedPaths);
        boolean webCoherenceRequired = profile.staticWeb();

        verifyExpectedTargets(contract, root, profile, mutatedPaths, expectedTargetExemptions,
                workspaceOperationVerification.expectedTargetAliases(), facts, problems);
        boolean expectationRequired = verifyTaskExpectations(
                contract,
                root,
                successfulMutations,
                facts,
                problems,
                recordExpectationTrace);
        boolean bulletCountExpectationRequired = hasBulletCountExpectation(contract);
        boolean appendLineExpectationRequired = hasAppendLineExpectation(contract);
        boolean replacementExpectationRequired = hasReplacementExpectation(contract);
        boolean exactEditEvidenceRequired = verifyExactEditEvidence(successfulMutations, root, facts, problems);
        boolean exactEditEvidenceCoversAllMutations =
                exactEditEvidenceRequired && allSuccessfulMutationsHaveExactEditEvidence(successfulMutations);
        boolean sourceDerivedRequired = verifySourceDerivedArtifact(contract, root, facts, problems);

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

        if (!problems.isEmpty()) {
            return TaskVerificationResult.failed(
                    sourceDerivedRequired && !webCoherenceRequired
                            ? "Source-derived artifact verification failed."
                    : exactEditEvidenceRequired && problems.stream().anyMatch(StaticTaskVerifier::isExactEditProblem)
                            ? "Exact edit replacement verification failed."
                    : replacementExpectationRequired && problems.stream().anyMatch(StaticTaskVerifier::isReplacementProblem)
                            ? "Replacement verification failed."
                    : appendLineExpectationRequired && problems.stream().anyMatch(StaticTaskVerifier::isAppendLineProblem)
                            ? "Append line verification failed."
                    : bulletCountExpectationRequired && problems.stream().anyMatch(StaticTaskVerifier::isBulletCountProblem)
                            ? "Bullet count verification failed."
                    : expectationRequired && problems.stream().anyMatch(StaticTaskVerifier::isExactContentProblem)
                            ? "Exact content verification failed."
                            : firstProblemSummary(problems),
                    facts,
                    problems);
        }
        if (expectationRequired && !webCoherenceRequired) {
            if (replacementExpectationRequired) {
                return TaskVerificationResult.passed(
                        "Replacement verification passed.",
                        facts);
            }
            if (appendLineExpectationRequired) {
                return TaskVerificationResult.passed(
                        "Append line verification passed.",
                        facts);
            }
            if (bulletCountExpectationRequired) {
                return TaskVerificationResult.passed(
                        "Bullet count verification passed.",
                        facts);
            }
            return TaskVerificationResult.passed(
                    "Exact content verification passed.",
                    facts);
        }
        if (exactEditEvidenceCoversAllMutations && !webCoherenceRequired) {
            return TaskVerificationResult.passed(
                    "Exact edit replacement verification passed.",
                    facts);
        }
        if (sourceDerivedRequired && !webCoherenceRequired) {
            return TaskVerificationResult.passed(
                    "Source-derived artifact verification passed.",
                    facts);
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

    private static boolean verifyTaskExpectations(
            TaskContract contract,
            Path root,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);
        if (expectations.isEmpty()) return false;
        boolean verifiedAny = false;
        for (TaskExpectation expectation : expectations) {
            if (expectation instanceof LiteralContentExpectation literal) {
                verifiedAny = true;
                verifyLiteralContentExpectation(root, literal, facts, problems, recordExpectationTrace);
            } else if (expectation instanceof ReplacementExpectation replacement) {
                verifiedAny = true;
                verifyReplacementExpectation(
                        root,
                        replacement,
                        successfulMutations,
                        facts,
                        problems,
                        recordExpectationTrace);
            } else if (expectation instanceof AppendLineExpectation appendLine) {
                verifiedAny = true;
                verifyAppendLineExpectation(
                        root,
                        appendLine,
                        successfulMutations,
                        facts,
                        problems,
                        recordExpectationTrace);
            } else if (expectation instanceof BulletListExpectation bullets) {
                verifiedAny = true;
                verifyBulletListExpectation(root, bullets, facts, problems, recordExpectationTrace);
            }
        }
        return verifiedAny;
    }

    private static boolean hasBulletCountExpectation(TaskContract contract) {
        return TaskExpectationResolver.resolve(contract).stream()
                .anyMatch(BulletListExpectation.class::isInstance);
    }

    private static boolean hasAppendLineExpectation(TaskContract contract) {
        return TaskExpectationResolver.resolve(contract).stream()
                .anyMatch(AppendLineExpectation.class::isInstance);
    }

    private static boolean hasReplacementExpectation(TaskContract contract) {
        return TaskExpectationResolver.resolve(contract).stream()
                .anyMatch(ReplacementExpectation.class::isInstance);
    }

    private static boolean verifySourceDerivedArtifact(
            TaskContract contract,
            Path root,
            List<String> facts,
            List<String> problems
    ) {
        if (contract == null || root == null) return false;
        if (contract.sourceEvidenceTargets().isEmpty() || contract.expectedTargets().isEmpty()) return false;
        String request = contract.originalUserRequest() == null ? "" : contract.originalUserRequest();
        if (!request.toLowerCase(Locale.ROOT).contains("summariz")) return false;

        String targetPath = firstPath(contract.expectedTargets());
        if (targetPath.isBlank()) return false;
        Path target = resolveWorkspaceFile(root, targetPath);
        if (target == null || !Files.isRegularFile(target)) {
            problems.add(targetPath + ": source-derived target is not a readable file after apply.");
            return true;
        }

        String targetContent;
        try {
            targetContent = Files.readString(target);
        } catch (Exception e) {
            problems.add(targetPath + ": source-derived target could not be read after apply (" + e.getMessage() + ")");
            return true;
        }
        if (targetContent.isBlank()) {
            problems.add(targetPath + ": source-derived target is empty after apply.");
            return true;
        }

        List<SourceEvidence> sourceEvidence = readSourceEvidence(root, contract.sourceEvidenceTargets(), problems);
        if (sourceEvidence.isEmpty()) {
            return true;
        }

        Set<String> requestTerms = distinctiveTerms(request);
        Set<String> targetTerms = distinctiveTerms(targetContent);
        Set<String> aggregateSourceTerms = new LinkedHashSet<>();
        int problemsBeforeDerivedChecks = problems.size();

        if (looksLikeInstructionEcho(targetContent, request, contract.sourceEvidenceTargets())) {
            problems.add(targetPath + ": target content appears to repeat the request instead of summarizing source evidence.");
        }
        for (SourceEvidence source : sourceEvidence) {
            Set<String> sourceTerms = distinctiveTerms(source.content());
            aggregateSourceTerms.addAll(sourceTerms);
            sourceTerms.removeAll(requestTerms);
            if (!sourceTerms.isEmpty() && sourceTerms.stream().noneMatch(targetTerms::contains)) {
                problems.add(source.path()
                        + ": source-derived summary does not include distinctive evidence from this readable source.");
            }
        }
        List<String> unsupportedTerms = unsupportedSourceDerivedTerms(
                targetTerms,
                requestTerms,
                aggregateSourceTerms);
        if (unsupportedTerms.size() >= 8) {
            problems.add(targetPath
                    + ": source-derived summary includes unsupported distinctive terms not found in source evidence: "
                    + String.join(", ", unsupportedTerms.stream().limit(12).toList()) + ".");
        }
        if (bulletLimitRequested(request) && bulletLineCount(targetContent) > 8) {
            problems.add(targetPath + ": source-derived summary exceeds the requested bullet limit.");
        }
        if (problems.size() == problemsBeforeDerivedChecks) {
            facts.add(targetPath + ": source-derived artifact includes evidence from "
                    + String.join(", ", contract.sourceEvidenceTargets()) + ".");
        }
        return true;
    }

    private record SourceEvidence(String path, String content) {}

    private static List<String> unsupportedSourceDerivedTerms(
            Set<String> targetTerms,
            Set<String> requestTerms,
            Set<String> sourceTerms
    ) {
        if (targetTerms == null || targetTerms.isEmpty()) return List.of();
        LinkedHashSet<String> unsupported = new LinkedHashSet<>(targetTerms);
        if (requestTerms != null) unsupported.removeAll(requestTerms);
        if (sourceTerms != null) unsupported.removeAll(sourceTerms);
        unsupported.removeAll(SOURCE_DERIVED_ALLOWED_OUTPUT_TERMS);
        return unsupported.stream().sorted().toList();
    }

    private static String firstPath(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) return "";
        for (String path : paths) {
            if (path != null && !path.isBlank()) return normalizePath(path);
        }
        return "";
    }

    private static Path resolveWorkspaceFile(Path root, String path) {
        try {
            Path resolved = root.resolve(normalizePath(path)).normalize();
            return resolved.startsWith(root) ? resolved : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static List<SourceEvidence> readSourceEvidence(
            Path root,
            Collection<String> sourceTargets,
            List<String> problems
    ) {
        List<SourceEvidence> out = new ArrayList<>();
        Config extractionConfig = new Config(null);
        DocumentExtractionService extractionService = new DocumentExtractionService(extractionConfig);
        for (String sourceTarget : sourceTargets) {
            if (sourceTarget == null || sourceTarget.isBlank()) continue;
            String normalized = normalizePath(sourceTarget);
            Path source = resolveWorkspaceFile(root, normalized);
            if (source == null || !Files.isRegularFile(source)) {
                problems.add(normalized + ": source evidence file is not readable for derived artifact verification.");
                continue;
            }
            SourceEvidence extracted = extractedSourceEvidence(
                    root, normalized, source, extractionConfig, extractionService, problems);
            if (extracted != null) {
                out.add(extracted);
                continue;
            }
            try {
                out.add(new SourceEvidence(normalized, Files.readString(source)));
            } catch (Exception e) {
                problems.add(normalized + ": source evidence file could not be read for derived artifact verification ("
                        + e.getMessage() + ")");
            }
        }
        return out;
    }

    private static SourceEvidence extractedSourceEvidence(
            Path root,
            String normalized,
            Path source,
            Config extractionConfig,
            DocumentExtractionService extractionService,
            List<String> problems
    ) {
        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(source, extractionConfig).orElse(null);
        if (info == null || info.capability() != FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED) {
            return null;
        }

        DocumentExtractionResult result = extractionService.extract(DocumentExtractionRequest.read(source, root));
        if ((result.status() == DocumentExtractionStatus.SUCCESS || result.status() == DocumentExtractionStatus.PARTIAL)
                && !result.safeText().isBlank()) {
            return new SourceEvidence(normalized, result.safeText());
        }

        problems.add(normalized + ": source evidence document could not be extracted for derived artifact verification"
                + " (status=" + result.status() + ").");
        return new SourceEvidence(normalized, "");
    }

    private static boolean looksLikeInstructionEcho(
            String targetContent,
            String request,
            Collection<String> sourceTargets
    ) {
        String target = normalizedLowerText(targetContent);
        String req = normalizedLowerText(request);
        if (target.isBlank()) return false;
        if (!target.contains("summarize")) return false;
        for (String sourceTarget : sourceTargets == null ? List.<String>of() : sourceTargets) {
            String source = normalizedLowerText(sourceTarget);
            if (!source.isBlank() && target.contains(source)) return true;
            String base = basename(sourceTarget).toLowerCase(Locale.ROOT);
            if (!base.isBlank() && target.contains(base)) return true;
        }
        return !req.isBlank() && req.contains(target);
    }

    private static String normalizedLowerText(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replace('\\', '/')
                .replaceAll("[^a-z0-9_./-]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static Set<String> distinctiveTerms(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = WORD_TOKEN.matcher(value.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (SOURCE_DERIVED_STOP_WORDS.contains(token)) continue;
            if (token.matches("\\d+")) continue;
            terms.add(token);
        }
        return terms;
    }

    private static boolean bulletLimitRequested(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("under 8 bullet") || lower.contains("under eight bullet");
    }

    private static int bulletLineCount(String content) {
        if (content == null || content.isBlank()) return 0;
        int count = 0;
        for (String line : content.split("\\R")) {
            if (isBulletLine(line)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isBulletLine(String line) {
        String trimmed = line == null ? "" : line.stripLeading();
        return trimmed.startsWith("- ")
                || trimmed.startsWith("* ")
                || trimmed.matches("\\d+[.)]\\s+.*");
    }

    private static int nonBlankNonBulletLineCount(String content) {
        if (content == null || content.isBlank()) return 0;
        int count = 0;
        for (String line : content.split("\\R")) {
            if (line.isBlank()) continue;
            if (!isBulletLine(line)) count++;
        }
        return count;
    }

    private static boolean isExactContentProblem(String problem) {
        return problem != null
                && (problem.contains("exact content mismatch")
                || problem.contains("exact content verification"));
    }

    private static boolean isAppendLineProblem(String problem) {
        return problem != null
                && (problem.contains("appended line")
                || problem.contains("append-only preservation"));
    }

    private static boolean isReplacementProblem(String problem) {
        return problem != null && problem.contains("replacement ");
    }

    private static boolean isExactEditProblem(String problem) {
        return problem != null && problem.contains("exact edit replacement");
    }

    private static boolean isBulletCountProblem(String problem) {
        return problem != null && (problem.contains("bullet count") || problem.contains("bullet list"));
    }

    private static boolean verifyExactEditEvidence(
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path root,
            List<String> facts,
            List<String> problems
    ) {
        if (outcomes == null || outcomes.isEmpty()) return false;
        boolean verifiedAny = false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null
                    || !outcome.success()
                    || !"edit_file".equals(ToolAliasPolicy.localCanonicalName(outcome.toolName()))
                    || outcome.mutationEvidence() == null
                    || !outcome.mutationEvidence().exactEditReplacement()) {
                continue;
            }
            verifiedAny = true;
            String pathHint = normalizePath(outcome.pathHint());
            Path target = resolveWorkspaceFile(root, pathHint);
            if (target == null || !Files.isRegularFile(target)) {
                problems.add(pathHint + ": exact edit replacement target is not readable after apply.");
                continue;
            }
            String content;
            try {
                content = Files.readString(target);
            } catch (Exception e) {
                problems.add(pathHint + ": exact edit replacement target could not be read after apply ("
                        + e.getMessage() + ")");
                continue;
            }

            ToolCallLoop.MutationEvidence evidence = outcome.mutationEvidence();
            String oldString = evidence.oldString();
            String newString = evidence.newString();
            if (!newString.isEmpty() && !content.contains(newString)) {
                problems.add(pathHint + ": exact edit replacement text was not observed after apply.");
                continue;
            }
            if (!oldString.isEmpty()
                    && (newString.isEmpty() || !newString.contains(oldString))
                    && content.contains(oldString)) {
                problems.add(pathHint + ": exact edit replacement old text remained after apply.");
                continue;
            }
            facts.add(pathHint + ": exact edit replacement observed in post-apply file.");
        }
        return verifiedAny;
    }

    private static boolean allSuccessfulMutationsHaveExactEditEvidence(List<ToolCallLoop.ToolOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null || !outcome.success() || !outcome.mutating()) continue;
            if (!hasExactEditEvidence(outcome)) return false;
        }
        return true;
    }

    private static boolean hasExactEditEvidence(ToolCallLoop.ToolOutcome outcome) {
        return outcome != null
                && "edit_file".equals(ToolAliasPolicy.localCanonicalName(outcome.toolName()))
                && outcome.mutationEvidence() != null
                && outcome.mutationEvidence().exactEditReplacement();
    }

    private static void verifyLiteralContentExpectation(
            Path root,
            LiteralContentExpectation expectation,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        String pathHint = normalizePath(expectation.targetPath());
        Path target;
        try {
            target = root.resolve(pathHint).normalize();
        } catch (InvalidPathException e) {
            problems.add(pathHint + ": exact content verification could not resolve target path.");
            if (recordExpectationTrace) recordLiteralExpectation(expectation, ExpectationVerificationStatus.FAILED, "");
            return;
        }
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            problems.add(pathHint + ": exact content verification target is not a readable file.");
            if (recordExpectationTrace) recordLiteralExpectation(expectation, ExpectationVerificationStatus.FAILED, "");
            return;
        }
        String observed;
        try {
            observed = Files.readString(target);
        } catch (Exception e) {
            problems.add(pathHint + ": exact content verification could not read target (" + e.getMessage() + ")");
            if (recordExpectationTrace) recordLiteralExpectation(expectation, ExpectationVerificationStatus.FAILED, "");
            return;
        }

        boolean matched = observed.equals(expectation.expectedContent());
        ExpectationVerificationStatus status = matched
                ? ExpectationVerificationStatus.PASSED
                : ExpectationVerificationStatus.FAILED;
        if (recordExpectationTrace) recordLiteralExpectation(expectation, status, observed);
        if (matched) {
            facts.add(pathHint + ": literal content matched requested exact content.");
        } else {
            problems.add(pathHint + ": exact content mismatch (expected "
                    + expectation.expectedChars() + " chars/" + expectation.expectedBytes()
                    + " bytes/" + expectation.expectedLines() + " lines, observed "
                    + LiteralContentExpectation.charCount(observed) + " chars/"
                    + LiteralContentExpectation.byteCount(observed) + " bytes/"
                    + LiteralContentExpectation.lineCount(observed) + " lines).");
        }
    }

    private static void recordLiteralExpectation(
            LiteralContentExpectation expectation,
            ExpectationVerificationStatus status,
            String observedContent
    ) {
        LocalTurnTraceCapture.recordExpectationVerified(
                expectation.kind(),
                status == null ? "" : status.name(),
                expectation.targetPath(),
                expectation.sourcePattern(),
                expectation.expectedHash(),
                expectation.expectedBytes(),
                expectation.expectedChars(),
                expectation.expectedLines(),
                LiteralContentExpectation.hash(observedContent),
                LiteralContentExpectation.byteCount(observedContent),
                LiteralContentExpectation.charCount(observedContent),
                LiteralContentExpectation.lineCount(observedContent));
    }

    private static void verifyReplacementExpectation(
            Path root,
            ReplacementExpectation expectation,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        String pathHint = normalizePath(expectation.targetPath());
        Path target;
        try {
            target = root.resolve(pathHint).normalize();
        } catch (InvalidPathException e) {
            problems.add(pathHint + ": replacement verification could not resolve target path.");
            if (recordExpectationTrace) recordReplacementExpectation(
                    expectation,
                    ExpectationVerificationStatus.FAILED,
                    false,
                    false);
            return;
        }
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            problems.add(pathHint + ": replacement verification target is not a readable file.");
            if (recordExpectationTrace) recordReplacementExpectation(
                    expectation,
                    ExpectationVerificationStatus.FAILED,
                    false,
                    false);
            return;
        }

        String observed;
        try {
            observed = Files.readString(target);
        } catch (Exception e) {
            problems.add(pathHint + ": replacement verification could not read target (" + e.getMessage() + ")");
            if (recordExpectationTrace) recordReplacementExpectation(
                    expectation,
                    ExpectationVerificationStatus.FAILED,
                    false,
                    false);
            return;
        }

        boolean oldPresent = !expectation.oldText().isEmpty() && observed.contains(expectation.oldText());
        boolean newPresent = !expectation.newText().isEmpty() && observed.contains(expectation.newText());
        boolean matched = !oldPresent && newPresent;
        if (matched && expectation.preserveRest()) {
            matched = verifyReplacementPreservation(
                    expectation,
                    pathHint,
                    successfulMutations,
                    facts,
                    problems);
        }
        if (recordExpectationTrace) {
            recordReplacementExpectation(
                    expectation,
                    matched ? ExpectationVerificationStatus.PASSED : ExpectationVerificationStatus.FAILED,
                    oldPresent,
                    newPresent);
        }
        if (matched) {
            facts.add(pathHint + ": replacement text observed and old text absent.");
        } else {
            if (!newPresent) {
                problems.add(pathHint + ": replacement new text was not observed after apply.");
            }
            if (oldPresent) {
                problems.add(pathHint + ": replacement old text remained after apply.");
            }
        }
    }

    private static boolean verifyReplacementPreservation(
            ReplacementExpectation expectation,
            String pathHint,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        if (successfulMutations == null || successfulMutations.isEmpty()) {
            problems.add(pathHint + ": replacement preservation had no mutation evidence.");
            return false;
        }
        boolean sawRelevantMutation = false;
        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            if (outcome == null
                    || !outcome.success()
                    || !normalizePath(outcome.pathHint()).equals(pathHint)) {
                continue;
            }
            sawRelevantMutation = true;
            String canonicalTool = ToolAliasPolicy.localCanonicalName(outcome.toolName());
            ToolCallLoop.MutationEvidence evidence = outcome.mutationEvidence();
            if ("edit_file".equals(canonicalTool)) {
                if (evidence == null || !evidence.exactEditReplacement()) {
                    problems.add(pathHint + ": talos.edit_file cannot prove preserve-rest replacement "
                            + "without exact edit evidence.");
                    return false;
                }
                if (!replacementOnlyChangesRequestedText(
                        evidence.oldString(),
                        evidence.newString(),
                        expectation.oldText(),
                        expectation.newText())) {
                    problems.add(pathHint
                            + ": replacement preservation exact edit changed content beyond the requested text.");
                    return false;
                }
                facts.add(pathHint + ": exact edit evidence preserved content beyond requested replacement.");
                continue;
            }
            if ("write_file".equals(canonicalTool)) {
                if (evidence == null || !evidence.fullWriteReplacement()) {
                    problems.add(pathHint + ": talos.write_file cannot prove preserve-rest replacement "
                            + "without complete same-turn read evidence.");
                    return false;
                }
                if (!replacementOnlyChangesRequestedText(
                        evidence.oldString(),
                        evidence.newString(),
                        expectation.oldText(),
                        expectation.newText())) {
                    problems.add(pathHint + ": replacement preservation changed content beyond the requested text.");
                    return false;
                }
                facts.add(pathHint + ": replacement preservation matched prior content.");
                continue;
            }
            problems.add(pathHint + ": mutation tool cannot prove preserve-rest replacement.");
            return false;
        }
        if (!sawRelevantMutation) {
            problems.add(pathHint + ": replacement preservation had no matching mutation evidence.");
            return false;
        }
        return true;
    }

    private static boolean replacementOnlyChangesRequestedText(
            String previousContent,
            String newContent,
            String oldText,
            String newText
    ) {
        if (previousContent == null || newContent == null
                || oldText == null || oldText.isBlank()
                || newText == null || newText.isBlank()) {
            return false;
        }
        String previousNormalized = normalizeLineEndings(previousContent);
        String newNormalized = normalizeLineEndings(newContent);
        String oldNormalized = normalizeLineEndings(oldText);
        String replacementNormalized = normalizeLineEndings(newText);
        if (countOccurrences(previousNormalized, oldNormalized) != 1) {
            return false;
        }
        String expected = previousNormalized.replace(oldNormalized, replacementNormalized);
        return expected.equals(newNormalized)
                || stripSingleTerminalNewline(expected).equals(stripSingleTerminalNewline(newNormalized));
    }

    private static String stripSingleTerminalNewline(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    private static void recordReplacementExpectation(
            ReplacementExpectation expectation,
            ExpectationVerificationStatus status,
            boolean oldPresent,
            boolean newPresent
    ) {
        String observedState = "oldPresent:" + oldPresent + ";newPresent:" + newPresent;
        LocalTurnTraceCapture.recordExpectationVerified(
                expectation == null ? "TEXT_REPLACEMENT" : expectation.kind(),
                status == null ? "" : status.name(),
                expectation == null ? "" : expectation.targetPath(),
                expectation == null ? "" : expectation.sourcePattern(),
                expectation == null ? "" : "old:" + expectation.oldHash() + ";new:" + expectation.newHash(),
                expectation == null ? 0 : expectation.newBytes(),
                expectation == null ? 0 : expectation.newChars(),
                0,
                LiteralContentExpectation.hash(observedState),
                0,
                0,
                0);
    }

    private static void verifyAppendLineExpectation(
            Path root,
            AppendLineExpectation expectation,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        String pathHint = normalizePath(expectation.targetPath());
        Path target;
        try {
            target = root.resolve(pathHint).normalize();
        } catch (InvalidPathException e) {
            problems.add(pathHint + ": appended line verification could not resolve target path.");
            if (recordExpectationTrace) recordAppendLineExpectation(expectation, ExpectationVerificationStatus.FAILED, "");
            return;
        }
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            problems.add(pathHint + ": appended line verification target is not a readable file.");
            if (recordExpectationTrace) recordAppendLineExpectation(expectation, ExpectationVerificationStatus.FAILED, "");
            return;
        }

        String observed;
        try {
            observed = Files.readString(target);
        } catch (Exception e) {
            problems.add(pathHint + ": appended line verification could not read target (" + e.getMessage() + ")");
            if (recordExpectationTrace) recordAppendLineExpectation(expectation, ExpectationVerificationStatus.FAILED, "");
            return;
        }

        List<String> lines = logicalLines(observed);
        String expectedLine = expectation.expectedLine();
        long matchingLines = lines.stream().filter(expectedLine::equals).count();
        String finalLine = lines.isEmpty() ? "" : lines.getLast();
        boolean postStateMatched = matchingLines == 1 && expectedLine.equals(finalLine);
        boolean appendOnlyEvidenceSatisfied = postStateMatched
                && verifyAppendLineMutationEvidence(pathHint, expectedLine, successfulMutations, facts, problems);
        boolean matched = postStateMatched && appendOnlyEvidenceSatisfied;
        if (recordExpectationTrace) {
            recordAppendLineExpectation(
                    expectation,
                    matched ? ExpectationVerificationStatus.PASSED : ExpectationVerificationStatus.FAILED,
                    finalLine);
        }
        if (matched) {
            facts.add(pathHint + ": appended line matched requested EOF line.");
        } else if (matchingLines == 0) {
            problems.add(pathHint + ": appended line missing.");
        } else if (matchingLines > 1) {
            problems.add(pathHint + ": appended line count mismatch (expected 1, observed "
                    + matchingLines + ").");
        } else if (!expectedLine.equals(finalLine)) {
            problems.add(pathHint + ": appended line was not the final logical line.");
        }
    }

    private static boolean verifyAppendLineMutationEvidence(
            String pathHint,
            String expectedLine,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        if (successfulMutations == null || successfulMutations.isEmpty()) return true;
        boolean sawRelevantExactEdit = false;
        boolean sawRelevantFullWrite = false;
        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            if (outcome != null
                    && outcome.success()
                    && "write_file".equals(ToolAliasPolicy.localCanonicalName(outcome.toolName()))
                    && normalizePath(outcome.pathHint()).equals(pathHint)) {
                if (outcome.mutationEvidence() != null
                        && outcome.mutationEvidence().fullWriteReplacement()) {
                    sawRelevantFullWrite = true;
                    ToolCallLoop.MutationEvidence evidence = outcome.mutationEvidence();
                    if (!exactEditAppendsOnlyRequestedLine(evidence.oldString(), evidence.newString(), expectedLine)) {
                        problems.add(pathHint
                                + ": full-file write did not preserve prior content before appended line.");
                        return false;
                    }
                    continue;
                }
                problems.add(pathHint
                        + ": talos.write_file cannot prove append-only preservation for an append-line request; "
                        + "use exact talos.edit_file append evidence.");
                return false;
            }
            if (outcome == null
                    || !outcome.success()
                    || !"edit_file".equals(ToolAliasPolicy.localCanonicalName(outcome.toolName()))
                    || !normalizePath(outcome.pathHint()).equals(pathHint)
                    || outcome.mutationEvidence() == null
                    || !outcome.mutationEvidence().exactEditReplacement()) {
                continue;
            }
            sawRelevantExactEdit = true;
            ToolCallLoop.MutationEvidence evidence = outcome.mutationEvidence();
            if (!exactEditAppendsOnlyRequestedLine(evidence.oldString(), evidence.newString(), expectedLine)) {
                problems.add(pathHint + ": exact edit did not preserve prior content before appended line.");
                return false;
            }
        }
        if (sawRelevantExactEdit) {
            facts.add(pathHint + ": exact edit evidence preserved prior content before appended line.");
        }
        if (sawRelevantFullWrite) {
            facts.add(pathHint + ": full-write evidence preserved prior content before appended line.");
        }
        return true;
    }

    private static boolean exactEditAppendsOnlyRequestedLine(
            String oldString,
            String newString,
            String expectedLine
    ) {
        if (oldString == null || newString == null || expectedLine == null || expectedLine.isEmpty()) {
            return false;
        }
        String oldNormalized = normalizeLineEndings(oldString);
        String newNormalized = normalizeLineEndings(newString);
        String expectedNormalized = normalizeLineEndings(expectedLine);
        if (!newNormalized.startsWith(oldNormalized)) {
            return false;
        }
        String suffix = newNormalized.substring(oldNormalized.length());
        return suffix.equals(expectedNormalized)
                || suffix.equals(expectedNormalized + "\n")
                || suffix.equals("\n" + expectedNormalized)
                || suffix.equals("\n" + expectedNormalized + "\n");
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty() || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static void recordAppendLineExpectation(
            AppendLineExpectation expectation,
            ExpectationVerificationStatus status,
            String observedFinalLine
    ) {
        String observed = observedFinalLine == null ? "" : observedFinalLine;
        LocalTurnTraceCapture.recordExpectationVerified(
                expectation == null ? "APPEND_LINE" : expectation.kind(),
                status == null ? "" : status.name(),
                expectation == null ? "" : expectation.targetPath(),
                expectation == null ? "" : expectation.sourcePattern(),
                expectation == null ? "" : expectation.expectedHash(),
                expectation == null ? 0 : expectation.expectedBytes(),
                expectation == null ? 0 : expectation.expectedChars(),
                1,
                LiteralContentExpectation.hash(observed),
                LiteralContentExpectation.byteCount(observed),
                LiteralContentExpectation.charCount(observed),
                observed.isBlank() ? 0 : 1);
    }

    private static List<String> logicalLines(String content) {
        if (content == null || content.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(List.of(content.split("\\R", -1)));
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        return List.copyOf(lines);
    }

    private static void verifyBulletListExpectation(
            Path root,
            BulletListExpectation expectation,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        String pathHint = normalizePath(expectation.targetPath());
        Path target;
        try {
            target = root.resolve(pathHint).normalize();
        } catch (InvalidPathException e) {
            problems.add(pathHint + ": bullet count verification could not resolve target path.");
            if (recordExpectationTrace) recordBulletListExpectation(expectation, ExpectationVerificationStatus.FAILED, 0);
            return;
        }
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            problems.add(pathHint + ": bullet count verification target is not a readable file.");
            if (recordExpectationTrace) recordBulletListExpectation(expectation, ExpectationVerificationStatus.FAILED, 0);
            return;
        }

        String observed;
        try {
            observed = Files.readString(target);
        } catch (Exception e) {
            problems.add(pathHint + ": bullet count verification could not read target (" + e.getMessage() + ")");
            if (recordExpectationTrace) recordBulletListExpectation(expectation, ExpectationVerificationStatus.FAILED, 0);
            return;
        }

        int observedCount = bulletLineCount(observed);
        int nonBulletLines = nonBlankNonBulletLineCount(observed);
        boolean matched = observedCount == expectation.expectedBulletCount() && nonBulletLines == 0;
        if (recordExpectationTrace) {
            recordBulletListExpectation(
                    expectation,
                    matched ? ExpectationVerificationStatus.PASSED : ExpectationVerificationStatus.FAILED,
                    observedCount);
        }
        if (matched) {
            facts.add(pathHint + ": bullet count matched requested " + expectation.expectedBulletCount() + ".");
        } else if (observedCount != expectation.expectedBulletCount()) {
            problems.add(pathHint + ": bullet count mismatch (expected "
                    + expectation.expectedBulletCount() + ", observed " + observedCount + ").");
        } else {
            problems.add(pathHint + ": bullet list contains non-bullet content.");
        }
    }

    private static void recordBulletListExpectation(
            BulletListExpectation expectation,
            ExpectationVerificationStatus status,
            int observedCount
    ) {
        int expectedCount = expectation == null ? 0 : expectation.expectedBulletCount();
        LocalTurnTraceCapture.recordExpectationVerified(
                expectation == null ? "BULLET_LIST_COUNT" : expectation.kind(),
                status == null ? "" : status.name(),
                expectation == null ? "" : expectation.targetPath(),
                expectation == null ? "" : expectation.sourcePattern(),
                "count:" + expectedCount,
                0,
                0,
                expectedCount,
                "count:" + Math.max(0, observedCount),
                0,
                0,
                Math.max(0, observedCount));
    }

    private static void verifyExpectedTargets(
            TaskContract contract,
            Path root,
            CapabilityProfile profile,
            Set<String> mutatedPaths,
            Set<String> expectedTargetExemptions,
            Set<String> expectedTargetAliases,
            List<String> facts,
            List<String> problems
    ) {
        if (contract == null
                || (contract.expectedTargets().isEmpty() && contract.forbiddenTargets().isEmpty())) {
            return;
        }
        Set<String> normalizedMutations = new LinkedHashSet<>();
        for (String path : mutatedPaths) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) normalizedMutations.add(normalized);
        }
        Set<String> normalizedExemptions = new LinkedHashSet<>();
        for (String path : expectedTargetExemptions == null ? Set.<String>of() : expectedTargetExemptions) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) normalizedExemptions.add(normalized);
        }
        Set<String> normalizedAliases = new LinkedHashSet<>();
        for (String path : expectedTargetAliases == null ? Set.<String>of() : expectedTargetAliases) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) normalizedAliases.add(normalized);
        }
        boolean caseInsensitive = expectedTargetMatchingIsCaseInsensitive();
        int problemsBeforeTargetVerification = problems.size();
        for (String target : contract.forbiddenTargets()) {
            String forbidden = normalizePath(target);
            if (forbidden.isBlank()) continue;
            boolean matched = normalizedMutations.stream()
                    .anyMatch(mutated -> expectedTargetMatches(forbidden, mutated, caseInsensitive));
            if (matched) {
                problems.add(forbidden + ": forbidden mutation target was changed.");
            }
        }
        String onlyTarget = singleTargetOnlyMutationTarget(contract);
        Set<String> satisfiedContextTargets = new LinkedHashSet<>();
        for (String target : contract.expectedTargets()) {
            String expected = normalizePath(target);
            if (expected.isBlank()) continue;
            boolean exempt = normalizedExemptions.stream()
                    .anyMatch(exemption -> expectedTargetMatches(expected, exemption, caseInsensitive));
            if (exempt) continue;
            boolean matched = normalizedMutations.stream()
                    .anyMatch(mutated -> expectedTargetMatches(expected, mutated, caseInsensitive))
                    || normalizedAliases.stream()
                    .anyMatch(alias -> expectedTargetMatches(expected, alias, caseInsensitive));
            if (!matched && staticWebRepairContextTargetSatisfied(profile, root, expected, normalizedMutations)) {
                satisfiedContextTargets.add(expected);
                continue;
            }
            if (!matched) {
                List<String> similarWrongTargets = similarWrongMutationTargets(
                        expected,
                        normalizedMutations,
                        caseInsensitive);
                String problem = expected + ": expected target was not successfully mutated.";
                if (!similarWrongTargets.isEmpty()) {
                    problem += " Changed similar target(s) "
                            + renderObserved(new LinkedHashSet<>(similarWrongTargets))
                            + " does not satisfy `" + expected + "`.";
                }
                problems.add(problem);
            }
        }
        if (!onlyTarget.isBlank()) {
            for (String mutated : normalizedMutations) {
                boolean matchesOnlyTarget = expectedTargetMatches(onlyTarget, mutated, caseInsensitive)
                        || normalizedAliases.stream()
                        .anyMatch(alias -> expectedTargetMatches(alias, mutated, caseInsensitive));
                if (!matchesOnlyTarget) {
                    problems.add(mutated + ": non-requested mutation target was changed under an only-target request.");
                }
            }
        }
        if (!contract.expectedTargets().isEmpty()
                && problems.size() == problemsBeforeTargetVerification
                && problems.stream().noneMatch(p -> p.contains("expected target was not successfully mutated"))) {
            if (satisfiedContextTargets.isEmpty()) {
                facts.add("Expected mutation target(s) were updated: "
                        + String.join(", ", contract.expectedTargets()) + ".");
            } else {
                facts.add("Expected mutation target(s) and static web context target(s) were satisfied: "
                        + String.join(", ", contract.expectedTargets()) + ".");
            }
        }
    }

    private static String singleTargetOnlyMutationTarget(TaskContract contract) {
        if (contract == null || contract.expectedTargets().size() != 1) return "";
        String target = firstPath(contract.expectedTargets());
        if (target.isBlank()) return "";
        return requestHasOnlyTargetLimiter(contract.originalUserRequest(), target) ? target : "";
    }

    private static boolean requestHasOnlyTargetLimiter(String request, String target) {
        if (request == null || request.isBlank() || target == null || target.isBlank()) return false;
        String quoted = Pattern.quote(target);
        String targetBoundary = "`?" + quoted + "`?(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))";
        String mutationVerb = "(?:change|edit|modify|update|fix|replace|write|create|append)";
        List<Pattern> patterns = List.of(
                Pattern.compile("(?is)\\bonly\\s+" + mutationVerb + "\\s+" + targetBoundary),
                Pattern.compile("(?is)\\b" + mutationVerb + "\\s+only\\s+" + targetBoundary),
                Pattern.compile("(?is)\\b" + mutationVerb + "\\b.{0,80}?" + targetBoundary + "\\s+only\\b"),
                Pattern.compile("(?is)\\bdo\\s+not\\s+(?:edit|change|modify|touch|write|create|save|mutate)\\s+"
                        + "(?:any\\s+)?other\\s+files?\\b"),
                Pattern.compile("(?is)\\b(?:don't|dont)\\s+"
                        + "(?:edit|change|modify|touch|write|create|save|mutate)\\s+"
                        + "(?:any\\s+)?other\\s+files?\\b"),
                Pattern.compile("(?is)\\bdo\\s+not\\s+modify\\s+anything\\s+else\\b"));
        for (Pattern pattern : patterns) {
            if (pattern.matcher(request).find()) return true;
        }
        return false;
    }

    private static boolean staticWebRepairContextTargetSatisfied(
            CapabilityProfile profile,
            Path root,
            String expected,
            Set<String> normalizedMutations
    ) {
        if (profile == null || !profile.staticWeb()) return false;
        if (profile.operation() != ArtifactOperation.REPAIR
                && profile.operation() != ArtifactOperation.EDIT) return false;
        if (StaticWebCapabilityProfile.requiresSeparateAssetMutations(profile)) return false;
        if (!StaticWebCapabilityProfile.isSmallWebFile(expected)) return false;
        if (normalizedMutations == null || normalizedMutations.stream()
                .noneMatch(StaticWebCapabilityProfile::isSmallWebFile)) return false;
        if (root == null) return false;
        Path target;
        try {
            target = root.resolve(expected).normalize();
        } catch (InvalidPathException e) {
            return false;
        }
        return target.startsWith(root) && Files.isRegularFile(target);
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
                verifyPartialStyledWebWorkspace(root, primary, facts, problems);
                if (!problems.isEmpty()) return;
                facts.add("Styled web checks passed for " + String.join(", ", primary) + ".");
                return;
            }
            if (!primary.isEmpty()
                    && profile.targetSurface().allowsFunctionalPartial()
                    && StaticWebCapabilityProfile.looksFunctionalWebTask(contract)) {
                verifyPartialFunctionalWebWorkspace(root, contract, primary, facts, problems);
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
                verifyPartialFunctionalWebWorkspace(root, contract, primary, facts, problems);
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
            List<String> formProblems = calculatorFormProblems(contract.originalUserRequest(), selectors.html());
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
            problems.addAll(htmlStructureProblems(facts.htmlFile(), html));
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
                problems.addAll(calculatorFormProblems(contract.originalUserRequest(), facts.html()));
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

    private static void verifyPartialStyledWebWorkspace(
            Path root,
            List<String> primaryFiles,
            List<String> facts,
            List<String> problems
    ) {
        if (root == null || primaryFiles == null || primaryFiles.isEmpty()) return;
        String htmlFile = StaticWebSelectorAnalyzer.pickPrimary(primaryFiles, ".html", ".htm");
        if (htmlFile == null) {
            problems.add("Styled web task is missing a primary HTML file.");
            return;
        }

        String html;
        try {
            html = Files.readString(root.resolve(htmlFile));
        } catch (Exception e) {
            problems.add(htmlFile + ": could not be read for styled web verification.");
            return;
        }

        problems.addAll(htmlStructureProblems(htmlFile, html));

        String cssFile = StaticWebSelectorAnalyzer.pickPrimary(primaryFiles, ".css");
        List<String> linkedCssOccurrences = StaticWebSelectorAnalyzer.linkedCssOccurrences(html);
        Set<String> linkedCssFiles = new LinkedHashSet<>(linkedCssOccurrences);
        Set<String> existingFileNames = StaticWebSelectorAnalyzer.existingFileNames(root);
        boolean hasInlineStyle = hasNonBlankInlineStyle(html);
        if (linkedCssFiles.isEmpty()) {
            if (cssFile != null) {
                problems.add("HTML does not link CSS file: `" + cssFile + "`");
            } else if (!hasInlineStyle) {
                problems.add("Styled web task is missing CSS styling: no stylesheet link, CSS file, or inline <style> was found.");
            }
        }
        for (String linked : linkedCssFiles) {
            if (!existingFileNames.contains(linked)) {
                problems.add("HTML references missing CSS file: `" + linked + "`");
            }
        }
        if (hasInlineStyle) {
            facts.add(htmlFile + ": inline CSS styling is present.");
        } else if (!linkedCssFiles.isEmpty()) {
            facts.add(htmlFile + ": linked CSS stylesheet is present.");
        }
    }

    private static void verifyPartialFunctionalWebWorkspace(
            Path root,
            TaskContract contract,
            List<String> primaryFiles,
            List<String> facts,
            List<String> problems
    ) {
        if (root == null || primaryFiles == null || primaryFiles.isEmpty()) return;
        String htmlFile = StaticWebSelectorAnalyzer.pickPrimary(primaryFiles, ".html", ".htm");
        if (htmlFile == null) {
            problems.add("Functional web task is missing a primary HTML file.");
            return;
        }

        String html;
        try {
            html = Files.readString(root.resolve(htmlFile));
        } catch (Exception e) {
            problems.add(htmlFile + ": could not be read for functional web verification.");
            return;
        }

        String jsFile = StaticWebSelectorAnalyzer.pickPrimary(primaryFiles, ".js");
        List<String> linkedJsOccurrences = StaticWebSelectorAnalyzer.linkedJavaScriptOccurrences(html);
        Set<String> linkedJsFiles = new LinkedHashSet<>(linkedJsOccurrences);
        Set<String> existingFileNames = StaticWebSelectorAnalyzer.existingFileNames(root);
        boolean hasInlineScript = hasNonBlankInlineScript(html);
        if (jsFile == null && linkedJsFiles.isEmpty() && !hasInlineScript) {
            problems.add("Functional web task is missing JavaScript behavior: no JavaScript file or inline script was found.");
            problems.add("HTML does not link a JavaScript file for functional behavior.");
        }
        for (String linked : linkedJsFiles) {
            if (!existingFileNames.contains(linked)) {
                problems.add("HTML references missing JavaScript file: `" + linked + "`");
            }
        }

        List<String> htmlIdOccurrences = StaticWebSelectorAnalyzer.htmlIdOccurrences(html);
        for (String id : StaticWebSelectorAnalyzer.duplicateValues(htmlIdOccurrences)) {
            problems.add("HTML defines duplicate IDs: `#" + id + "`");
        }
        if (StaticWebCapabilityProfile.looksCalculatorOrFormTask(contract)) {
            List<String> formProblems = calculatorFormProblems(contract.originalUserRequest(), html);
            problems.addAll(formProblems);
            if (formProblems.isEmpty()) {
                facts.add("Calculator/form static structure checks passed.");
            }
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

    private static boolean shouldExpectWeightHeightControls(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("bmi")
                || lower.contains("weight")
                || lower.contains("height");
    }

    private static boolean hasNonBlankInlineScript(String html) {
        if (html == null || html.isBlank()) return false;
        Matcher matcher = HTML_INLINE_SCRIPT.matcher(html);
        while (matcher.find()) {
            String content = matcher.group(1);
            if (content != null && !content.strip().isBlank()) return true;
        }
        return false;
    }

    private static boolean hasNonBlankInlineStyle(String html) {
        if (html == null || html.isBlank()) return false;
        Matcher matcher = HTML_INLINE_STYLE.matcher(html);
        while (matcher.find()) {
            String content = matcher.group(1);
            if (content != null && !content.strip().isBlank()) return true;
        }
        return false;
    }

    private static List<String> calculatorFormProblems(String request, String html) {
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

    static boolean expectedTargetMatches(String expectedTarget, String mutatedPath, boolean caseInsensitive) {
        String expected = normalizePath(expectedTarget);
        String mutated = normalizePath(mutatedPath);
        if (expected.isBlank() || mutated.isBlank()) return false;
        if (caseInsensitive) {
            return expected.equalsIgnoreCase(mutated);
        }
        return expected.equals(mutated);
    }

    private static List<String> similarWrongMutationTargets(
            String expectedTarget,
            Set<String> mutatedPaths,
            boolean caseInsensitive
    ) {
        if (expectedTarget == null || mutatedPaths == null || mutatedPaths.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String mutated : mutatedPaths) {
            if (expectedTargetMatches(expectedTarget, mutated, caseInsensitive)) continue;
            if (looksLikeSingularPluralSibling(expectedTarget, mutated)) {
                out.add(mutated);
            }
        }
        return out.stream().sorted().toList();
    }

    private static boolean looksLikeSingularPluralSibling(String leftPath, String rightPath) {
        String left = normalizePath(leftPath).toLowerCase(Locale.ROOT);
        String right = normalizePath(rightPath).toLowerCase(Locale.ROOT);
        if (left.isBlank() || right.isBlank()) return false;

        int leftSlash = left.lastIndexOf('/');
        int rightSlash = right.lastIndexOf('/');
        String leftDir = leftSlash >= 0 ? left.substring(0, leftSlash + 1) : "";
        String rightDir = rightSlash >= 0 ? right.substring(0, rightSlash + 1) : "";
        if (!leftDir.equals(rightDir)) return false;

        String leftName = leftSlash >= 0 ? left.substring(leftSlash + 1) : left;
        String rightName = rightSlash >= 0 ? right.substring(rightSlash + 1) : right;
        int leftDot = leftName.lastIndexOf('.');
        int rightDot = rightName.lastIndexOf('.');
        if (leftDot <= 0 || rightDot <= 0) return false;
        String leftExt = leftName.substring(leftDot);
        String rightExt = rightName.substring(rightDot);
        if (!leftExt.equals(rightExt)) return false;

        String leftStem = leftName.substring(0, leftDot);
        String rightStem = rightName.substring(0, rightDot);
        return leftStem.equals(rightStem + "s") || rightStem.equals(leftStem + "s");
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

}
