package dev.talos.runtime.verification;

import dev.talos.core.extract.DocumentExtractionIntent;
import dev.talos.core.extract.DocumentExtractionProvenance;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.extract.DocumentExtractionWarning;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.CapabilityProfileRegistry;
import dev.talos.runtime.capability.DocumentExtractionCapabilityProfile;
import dev.talos.runtime.capability.VerifierProfile;
import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolError;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DocumentExtractionOutcomeVerifier {
    private static final Pattern STATUS_PATTERN = Pattern.compile("\\(status:\\s*([A-Z_]+)\\)");

    private DocumentExtractionOutcomeVerifier() {}

    public static TaskVerificationEvidence verifyWithEvidence(
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult
    ) {
        CapabilityProfile profile = CapabilityProfileRegistry.select(contract);
        if (profile.verifierProfile() != VerifierProfile.DOCUMENT_EXTRACTION) {
            return TaskVerificationEvidence.notRun("Document extraction verification was not applicable.");
        }
        if (loopResult == null || loopResult.toolOutcomes().isEmpty()) {
            return TaskVerificationEvidence.notRun("Document extraction verification had no tool outcomes.");
        }

        List<String> targets = DocumentExtractionCapabilityProfile.documentTargets(contract);
        List<VerifierResult> verifierResults = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        for (String target : targets) {
            ToolCallLoop.ToolOutcome outcome = latestReadOutcome(loopResult, target).orElse(null);
            if (outcome == null) continue;
            VerifierResult result = verifierResult(target, outcome);
            verifierResults.add(result);
            facts.addAll(result.facts());
            problems.addAll(result.problems());
            limitations.addAll(result.limitations());
        }
        if (verifierResults.isEmpty()) {
            return TaskVerificationEvidence.notRun("Document extraction verification found no matching read-file evidence.");
        }

        VerificationReport report = new VerificationReport(List.of(), verifierResults, facts, problems, limitations);
        return TaskVerificationEvidence.documentExtraction(
                compatibilityResult(contract, report),
                report);
    }

    private static TaskVerificationResult compatibilityResult(TaskContract contract, VerificationReport report) {
        List<VerifierResult> results = report.verifierResults();
        List<String> facts = report.facts();
        List<String> limitations = report.limitations();
        List<String> problems = report.problems();
        if (results.stream().anyMatch(result -> result.verdict() == VerificationVerdict.FAILED)) {
            List<String> details = problems.isEmpty() ? limitations : problems;
            return TaskVerificationResult.unavailable("Document extraction failed.", facts, details);
        }
        if (results.stream().anyMatch(DocumentExtractionOutcomeVerifier::isUnavailableOrUnsupported)) {
            List<String> details = problems.isEmpty() ? limitations : problems;
            return TaskVerificationResult.unavailable("Document extraction was unavailable or unsupported.", facts, details);
        }
        if (results.stream().anyMatch(result -> result.verdict() == VerificationVerdict.PARTIAL)) {
            return TaskVerificationResult.readbackOnly(
                    "Document extraction was partial; extracted text may be incomplete.",
                    merged(facts, limitations));
        }
        boolean allVerified = !results.isEmpty()
                && results.stream().allMatch(result -> result.verdict() == VerificationVerdict.VERIFIED);
        if (allVerified && DocumentExtractionCapabilityProfile.isExactTextExtractionTask(contract)) {
            return TaskVerificationResult.readbackOnly(
                    "Document parser extraction evidence verified extracted text only; final-answer exactness was not verified.",
                    merged(facts, limitations));
        }
        if (allVerified) {
            return TaskVerificationResult.readbackOnly(
                    "Document parser extraction evidence verified extracted text only; summary semantics were not verified.",
                    merged(facts, limitations));
        }
        return TaskVerificationResult.readbackOnly(
                "Document extraction evidence was gathered, but no verifying parser result was produced.",
                merged(facts, limitations));
    }

    private static boolean isUnavailableOrUnsupported(VerifierResult result) {
        return result.verdict() == VerificationVerdict.UNAVAILABLE
                || result.verdict() == VerificationVerdict.UNSUPPORTED
                || result.verdict() == VerificationVerdict.NOT_RUN;
    }

    private static VerifierResult verifierResult(String target, ToolCallLoop.ToolOutcome outcome) {
        DocumentExtractionStatus status = statusFromOutcome(target, outcome);
        DocumentExtractionResult extraction = syntheticExtraction(target, status);
        return DocumentExtractionVerificationMapper.toVerifierResult(target, extraction);
    }

    private static DocumentExtractionResult syntheticExtraction(String target, DocumentExtractionStatus status) {
        FileCapabilityPolicy.Capability capability = capabilityFor(target, status);
        return new DocumentExtractionResult(
                normalizePath(target),
                DocumentExtractionIntent.READ,
                capability,
                status,
                "",
                warningsFor(target, status),
                new DocumentExtractionProvenance(
                        normalizePath(target),
                        "read-file-tool-result",
                        "",
                        DocumentExtractionService.EXTRACTION_POLICY_VERSION),
                false);
    }

    private static FileCapabilityPolicy.Capability capabilityFor(String target, DocumentExtractionStatus status) {
        Optional<FileCapabilityPolicy.FormatInfo> info = formatInfo(target);
        if (info.isPresent()) return info.get().capability();
        return switch (status) {
            case SUCCESS, PARTIAL -> FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED;
            case OCR_REQUIRED, OCR_UNAVAILABLE -> FileCapabilityPolicy.Capability.OCR_REQUIRED_DISABLED;
            case DEFERRED_UNSUPPORTED -> FileCapabilityPolicy.Capability.DEFERRED_UNSUPPORTED;
            case UNSUPPORTED_ARCHIVE -> FileCapabilityPolicy.Capability.ARCHIVE_UNSUPPORTED;
            case UNSUPPORTED_BINARY -> FileCapabilityPolicy.Capability.UNKNOWN_BINARY_SKIP;
            default -> FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_DISABLED;
        };
    }

    private static List<DocumentExtractionWarning> warningsFor(String target, DocumentExtractionStatus status) {
        List<DocumentExtractionWarning> warnings = new ArrayList<>();
        String extension = extension(target);
        if ("pdf".equals(extension)) {
            warnings.add(new DocumentExtractionWarning(
                    "pdf-text-order",
                    "PDF text extraction may not match visual order or layout."));
        } else if ("docx".equals(extension)) {
            warnings.add(new DocumentExtractionWarning(
                    "docx-partial-structures",
                    "DOCX extraction is text-oriented; layout, comments, tracked changes, and embedded objects may be partial or omitted."));
        } else if ("xls".equals(extension) || "xlsx".equals(extension)) {
            warnings.add(new DocumentExtractionWarning(
                    extension + "-formula-policy",
                    extension.toUpperCase(Locale.ROOT)
                            + " extraction reports visible cells and cached display values; formulas are not recalculated."));
        } else if (isImageExtension(extension)) {
            warnings.add(new DocumentExtractionWarning(
                    "ocr-text-only",
                    "Image support is OCR text extraction only; Talos does not perform visual scene understanding."));
        }
        if (status == DocumentExtractionStatus.PARTIAL) {
            warnings.add(new DocumentExtractionWarning(
                    "extraction-partial",
                    "Document extraction was partial; extracted text may be truncated or incomplete."));
        }
        return List.copyOf(warnings);
    }

    private static DocumentExtractionStatus statusFromOutcome(String target, ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null) return DocumentExtractionStatus.NOT_ATTEMPTED;
        String statusSource = outcome.success() ? outcome.summary() : outcome.errorMessage();
        DocumentExtractionStatus parsed = parseStatus(statusSource).orElse(null);
        if (parsed != null) return parsed;
        if (!outcome.success() && ToolError.UNSUPPORTED_FORMAT.equals(outcome.errorCode())) {
            return defaultStatusFor(target);
        }
        return outcome.success() ? DocumentExtractionStatus.SUCCESS : DocumentExtractionStatus.FAILED;
    }

    private static Optional<DocumentExtractionStatus> parseStatus(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        Matcher matcher = STATUS_PATTERN.matcher(value);
        if (!matcher.find()) return Optional.empty();
        try {
            return Optional.of(DocumentExtractionStatus.valueOf(matcher.group(1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static DocumentExtractionStatus defaultStatusFor(String target) {
        return formatInfo(target)
                .map(FileCapabilityPolicy.FormatInfo::defaultOutcome)
                .map(outcome -> DocumentExtractionStatus.valueOf(outcome.name()))
                .orElse(DocumentExtractionStatus.UNSUPPORTED_BINARY);
    }

    private static Optional<ToolCallLoop.ToolOutcome> latestReadOutcome(
            ToolCallLoop.LoopResult loopResult,
            String target
    ) {
        String normalizedTarget = normalizePath(target);
        List<ToolCallLoop.ToolOutcome> outcomes = loopResult.toolOutcomes();
        for (int i = outcomes.size() - 1; i >= 0; i--) {
            ToolCallLoop.ToolOutcome outcome = outcomes.get(i);
            if (outcome == null) continue;
            if (!"talos.read_file".equals(canonicalToolName(outcome.toolName()))) continue;
            if (normalizePath(outcome.pathHint()).equals(normalizedTarget)) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }

    private static Optional<FileCapabilityPolicy.FormatInfo> formatInfo(String target) {
        try {
            return FileCapabilityPolicy.describe(Path.of(normalizePath(target)));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static String canonicalToolName(String toolName) {
        ToolAliasPolicy.Decision decision = ToolAliasPolicy.resolve(toolName);
        if (decision.accepted() && decision.canonicalToolName() != null && !decision.canonicalToolName().isBlank()) {
            return decision.canonicalToolName();
        }
        return toolName == null ? "" : toolName;
    }

    private static List<String> merged(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>();
        if (first != null) out.addAll(first);
        if (second != null) out.addAll(second);
        return List.copyOf(out);
    }

    private static boolean isImageExtension(String extension) {
        return switch (extension) {
            case "png", "jpg", "jpeg", "gif", "bmp", "webp", "tif", "tiff" -> true;
            default -> false;
        };
    }

    private static String extension(String path) {
        String normalized = normalizePath(path);
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/').strip();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("./") && normalized.length() > 2) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
