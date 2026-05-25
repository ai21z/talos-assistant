package dev.talos.runtime.toolcall;

import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

final class SourceDerivedEvidenceGuard {
    record SourceReadback(String path, String readback) {}
    record RequiredSourceEvidenceDiagnostic(String message, List<String> missingSourceTargets) {}

    private SourceDerivedEvidenceGuard() {}

    static RequiredSourceEvidenceDiagnostic requiredSourceEvidenceDiagnostic(
            LoopState state,
            TaskContract contract,
            ToolCall call,
            String pathHint
    ) {
        if (!isSourceDerivedContentMutation(call)) return null;
        List<String> missingSourceTargets = missingSourceEvidenceTargets(state, contract);
        if (missingSourceTargets.isEmpty()) return null;
        return new RequiredSourceEvidenceDiagnostic(
                sourceEvidenceRequiredDiagnostic(pathHint, missingSourceTargets),
                missingSourceTargets);
    }

    static String exactEvidenceCoverageDiagnostic(
            LoopState state,
            TaskContract contract,
            ToolCall call,
            String pathHint
    ) {
        if (state == null || contract == null || call == null) return null;
        if (contract.sourceEvidenceTargets().isEmpty()) return null;
        if (!exactEvidenceRequested(contract.originalUserRequest())) return null;
        String content = sourceDerivedCandidateContent(call);
        if (content == null || content.isBlank()) return null;

        List<SourceReadback> sourceReadbacks = sourceReadbacks(state, contract);
        if (sourceReadbacks.isEmpty()) return null;

        List<String> missing = new ArrayList<>();
        for (SourceReadback sourceReadback : sourceReadbacks) {
            String snippet = evidenceSnippet(sourceReadback.readback());
            if (snippet.isBlank()) continue;
            if (!content.contains(snippet)) {
                missing.add(sourceReadback.path() + " -> `" + snippet + "`");
            }
        }
        if (missing.isEmpty()) return null;

        StringJoiner joiner = new StringJoiner("; ");
        missing.forEach(joiner::add);
        String target = pathHint == null || pathHint.isBlank()
                ? "the derived output"
                : ToolCallSupport.normalizePath(pathHint);
        return "Source-derived write blocked before approval: " + target
                + " does not include required exact evidence phrase(s) from source file(s): "
                + joiner
                + ". Copy one exact phrase from each listed source readback before writing.";
    }

    static ToolCall repairedExactEvidenceWrite(
            LoopState state,
            TaskContract contract,
            ToolCall call,
            String pathHint
    ) {
        if (exactEvidenceCoverageDiagnostic(state, contract, call, pathHint) == null) return null;
        String canonical = ToolAliasPolicy.localCanonicalName(call.toolName());
        if (!"write_file".equals(canonical)) return null;
        String target = ToolCallSupport.normalizePath(pathHint);
        if (target.isBlank() || !expectedTargetContains(contract, target)) return null;
        List<SourceReadback> sourceReadbacks = sourceReadbacks(state, contract);
        if (sourceReadbacks.isEmpty()) return null;
        String content = deterministicEvidenceSummary(target, sourceReadbacks);
        if (content.isBlank()) return null;
        return new ToolCall("talos.write_file", Map.of(
                "path", target,
                "content", content));
    }

    static List<SourceReadback> sourceReadbacks(LoopState state, TaskContract contract) {
        if (state == null || contract == null || contract.sourceEvidenceTargets().isEmpty()) {
            return List.of();
        }
        List<SourceReadback> out = new ArrayList<>();
        for (String source : contract.sourceEvidenceTargets()) {
            String target = ToolCallSupport.normalizePath(source);
            if (target.isBlank() || isSensitiveReadbackPath(target)) continue;
            String readback = latestSuccessfulReadbackForPath(state, target);
            if (readback == null || readback.isBlank()) continue;
            out.add(new SourceReadback(target, readback));
        }
        return out;
    }

    private static List<String> missingSourceEvidenceTargets(LoopState state, TaskContract contract) {
        if (state == null || contract == null || contract.sourceEvidenceTargets().isEmpty()) {
            return List.of();
        }
        Set<String> readPaths = new LinkedHashSet<>();
        readPaths.addAll(TurnSourceEvidenceCapture.readPaths());
        for (String readPath : state.pathsReadThisTurn) {
            String normalized = evidencePathKey(readPath);
            if (!normalized.isBlank()) {
                readPaths.add(normalized);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String sourceTarget : contract.sourceEvidenceTargets()) {
            String normalized = evidencePathKey(sourceTarget);
            if (normalized.isBlank()) continue;
            if (!readPaths.contains(normalized)) {
                missing.add(sourceTarget);
            }
        }
        return List.copyOf(missing);
    }

    static String deterministicEvidenceSummary(
            String target,
            List<SourceReadback> sourceReadbacks
    ) {
        if (sourceReadbacks == null || sourceReadbacks.isEmpty()) return "";
        String title = titleForTarget(target);
        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append("\n\n");
        for (SourceReadback sourceReadback : sourceReadbacks) {
            String snippet = evidenceSnippet(sourceReadback.readback());
            if (snippet.isBlank()) continue;
            out.append("- ").append(sourceReadback.path()).append(": ").append(snippet).append('\n');
        }
        return out.toString();
    }

    static String evidenceSnippet(String readback) {
        if (readback == null || readback.isBlank()) return "";
        List<String> candidates = new ArrayList<>();
        for (String rawLine : readback.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.strip();
            if (line.isBlank()) continue;
            line = line.replaceFirst("^\\d+\\s*\\|\\s*", "").strip();
            if (line.isBlank()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("extracted document text from")
                    || lower.startsWith("warning:")
                    || lower.startsWith("extractor:")
                    || lower.startsWith("sheet:")
                    || lower.startsWith("status:")
                    || lower.equals("---")) {
                continue;
            }
            if (line.length() < 8) continue;
            candidates.add(line);
        }
        for (String candidate : candidates) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.contains("canonical") || lower.contains("marker")) {
                return truncateEvidenceSnippet(candidate);
            }
        }
        if (!candidates.isEmpty()) {
            return truncateEvidenceSnippet(candidates.getFirst());
        }
        return truncateEvidenceSnippet(readback.strip());
    }

    private static boolean expectedTargetContains(TaskContract contract, String target) {
        if (contract == null || target == null || target.isBlank()) return false;
        Set<String> expected = new LinkedHashSet<>();
        for (String expectedTarget : contract.expectedTargets()) {
            String normalized = ToolCallSupport.normalizePath(expectedTarget).toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) expected.add(normalized);
        }
        return expected.contains(target.toLowerCase(Locale.ROOT));
    }

    private static String titleForTarget(String target) {
        String normalized = target == null ? "" : ToolCallSupport.normalizePath(target);
        String filename = normalized;
        int slash = filename.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < filename.length()) {
            filename = filename.substring(slash + 1);
        }
        int dot = filename.lastIndexOf('.');
        if (dot > 0) {
            filename = filename.substring(0, dot);
        }
        String cleaned = filename.replace('-', ' ').replace('_', ' ').strip();
        if (cleaned.isBlank()) return "Source Evidence Summary";
        StringBuilder title = new StringBuilder(cleaned.length());
        for (String part : cleaned.split("\\s+")) {
            if (part.isBlank()) continue;
            if (title.length() > 0) title.append(' ');
            title.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) title.append(part.substring(1));
        }
        return title.toString();
    }

    private static String sourceDerivedCandidateContent(ToolCall call) {
        String canonical = ToolAliasPolicy.localCanonicalName(call.toolName());
        if ("write_file".equals(canonical)) {
            return call.param("content");
        }
        if ("edit_file".equals(canonical)) {
            return call.param("new_string");
        }
        return null;
    }

    private static boolean isSourceDerivedContentMutation(ToolCall call) {
        if (call == null) return false;
        String canonical = ToolAliasPolicy.localCanonicalName(call.toolName());
        return "write_file".equals(canonical) || "edit_file".equals(canonical);
    }

    private static String sourceEvidenceRequiredDiagnostic(String pathHint, List<String> missingSourceTargets) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the derived artifact"
                : "`" + pathHint + "`";
        String sources = missingSourceTargets == null || missingSourceTargets.isEmpty()
                ? "(unknown)"
                : String.join(", ", missingSourceTargets);
        return "Source-derived artifact write blocked before approval: the current task requires reading "
                + "source target(s) " + sources + " before writing " + target + ". "
                + "Call talos.read_file for the source target(s) first, then retry the write. "
                + "No approval was requested and no file was changed.";
    }

    private static boolean exactEvidenceRequested(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("exact evidence")
                || lower.contains("evidence phrase")
                || lower.contains("source coverage")
                || lower.contains("audit source");
    }

    private static String latestSuccessfulReadbackForPath(LoopState state, String normalizedPath) {
        if (state == null || normalizedPath == null || normalizedPath.isBlank()) {
            return null;
        }
        String target = ToolCallSupport.canonicalizeReadPath(normalizedPath)
                .toLowerCase(Locale.ROOT);
        String fullBody = latestSuccessfulReadbackForPath(state.successfulReadCallBodies, target);
        if (fullBody != null) return fullBody;
        return latestSuccessfulReadbackForPath(state.successfulReadCalls, target);
    }

    private static String latestSuccessfulReadbackForPath(java.util.Map<String, String> readbacksBySignature,
                                                          String target) {
        if (readbacksBySignature == null || readbacksBySignature.isEmpty()
                || target == null || target.isBlank()) {
            return null;
        }
        for (var entry : readbacksBySignature.entrySet()) {
            String signature = entry.getKey() == null
                    ? ""
                    : entry.getKey().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (signature.startsWith("talos.read_file:")
                    && signature.contains("path=" + target + ";")) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String evidencePathKey(String pathHint) {
        String normalized = ToolCallSupport.normalizePath(pathHint == null ? "" : pathHint).strip();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isSensitiveReadbackPath(String path) {
        if (path == null || path.isBlank()) return true;
        String normalized = ToolCallSupport.normalizePath(path).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return true;
        for (String segment : normalized.split("/")) {
            if (segment.equals(".env") || segment.startsWith(".env.")) return true;
            if (segment.equals(".git") || segment.equals(".ssh") || segment.equals(".gnupg")) return true;
        }
        return normalized.contains("id_rsa")
                || normalized.contains("credentials")
                || normalized.contains("secret");
    }

    private static String truncateEvidenceSnippet(String value) {
        if (value == null) return "";
        String normalized = value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        if (normalized.length() <= 180) return normalized;
        return normalized.substring(0, 180).strip() + "...";
    }
}
