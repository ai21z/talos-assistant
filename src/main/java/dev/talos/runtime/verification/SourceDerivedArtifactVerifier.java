package dev.talos.runtime.verification;

import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.runtime.task.TaskContract;

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

/** Verifies that generated artifacts claiming source-derived summaries are grounded in readable source evidence. */
final class SourceDerivedArtifactVerifier {

    private static final Pattern WORD_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{3,}");
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

    private SourceDerivedArtifactVerifier() {}

    static Result verify(TaskContract contract, Path root) {
        if (contract == null || root == null) return Result.notRequired();
        if (contract.sourceEvidenceTargets().isEmpty() || contract.expectedTargets().isEmpty()) {
            return Result.notRequired();
        }
        String request = contract.originalUserRequest() == null ? "" : contract.originalUserRequest();
        if (!request.toLowerCase(Locale.ROOT).contains("summariz")) return Result.notRequired();

        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String targetPath = firstPath(contract.expectedTargets());
        if (targetPath.isBlank()) return Result.notRequired();
        Path target = resolveWorkspaceFile(root, targetPath);
        if (target == null || !Files.isRegularFile(target)) {
            problems.add(targetPath + ": source-derived target is not a readable file after apply.");
            return new Result(true, facts, problems);
        }

        String targetContent;
        try {
            targetContent = Files.readString(target);
        } catch (Exception e) {
            problems.add(targetPath + ": source-derived target could not be read after apply (" + e.getMessage() + ")");
            return new Result(true, facts, problems);
        }
        if (targetContent.isBlank()) {
            problems.add(targetPath + ": source-derived target is empty after apply.");
            return new Result(true, facts, problems);
        }

        List<SourceEvidence> sourceEvidence = readSourceEvidence(root, contract.sourceEvidenceTargets(), problems);
        if (sourceEvidence.isEmpty()) {
            return new Result(true, facts, problems);
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
        return new Result(true, facts, problems);
    }

    record Result(boolean required, List<String> facts, List<String> problems) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        static Result notRequired() {
            return new Result(false, List.of(), List.of());
        }
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
