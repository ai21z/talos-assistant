package dev.talos.runtime.verification;

import dev.talos.runtime.obligation.RequestObligation;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure final-state evaluator for a compiled request obligation.
 *
 * <p>The caller supplies typed evidence that structured mutation verification
 * passed. This class never infers that fact from assistant prose.
 */
public final class RequestObligationFinalStateVerifier {

    private RequestObligationFinalStateVerifier() {}

    public record Evidence(
            Map<String, byte[]> beforeFiles,
            Map<String, byte[]> afterFiles,
            Set<String> structuredMutationTargets
    ) {
        public Evidence {
            beforeFiles = copyBytes(beforeFiles);
            afterFiles = copyBytes(afterFiles);
            structuredMutationTargets = canonicalTargets(structuredMutationTargets);
        }

        @Override
        public Map<String, byte[]> beforeFiles() {
            return copyBytes(beforeFiles);
        }

        @Override
        public Map<String, byte[]> afterFiles() {
            return copyBytes(afterFiles);
        }

        private static Map<String, byte[]> copyBytes(Map<String, byte[]> source) {
            if (source == null || source.isEmpty()) return Map.of();
            Map<String, byte[]> copy = new HashMap<>();
            source.forEach((path, bytes) -> {
                String canonical = RequestObligation.canonicalTarget(path);
                if (!canonical.equals(path)) {
                    throw new IllegalArgumentException(
                            "Evidence target must already be canonical: " + path);
                }
                if (bytes == null) {
                    throw new IllegalArgumentException(
                            "Evidence bytes are unavailable for target: " + path);
                }
                if (copy.putIfAbsent(canonical, bytes.clone()) != null) {
                    throw new IllegalArgumentException(
                            "Evidence contains an aliased target: " + path);
                }
            });
            return Map.copyOf(copy);
        }

        private static Set<String> canonicalTargets(Set<String> source) {
            if (source == null || source.isEmpty()) return Set.of();
            TreeSet<String> targets = new TreeSet<>();
            source.forEach(target -> {
                String canonical = RequestObligation.canonicalTarget(target);
                if (!canonical.equals(target) || !targets.add(canonical)) {
                    throw new IllegalArgumentException(
                            "Structured mutation target must be unique and canonical: " + target);
                }
            });
            return Set.copyOf(targets);
        }
    }

    public record Result(boolean passed, List<String> failedPostconditions) {
        public Result {
            failedPostconditions = failedPostconditions == null
                    ? List.of()
                    : List.copyOf(failedPostconditions);
        }
    }

    public static Result verify(RequestObligation obligation, Evidence evidence) {
        if (obligation == null
                || obligation.derivationStatus() != RequestObligation.DerivationStatus.COMPLETE
                || evidence == null) {
            return new Result(false, List.of("OBLIGATION_OR_EVIDENCE_UNAVAILABLE"));
        }
        List<String> failures = new ArrayList<>();
        Set<String> changed = changedTargets(evidence.beforeFiles, evidence.afterFiles);
        Set<String> requiredMutationTargets = Set.copyOf(obligation.requiredMutationTargets());

        if (obligation.mutationScope() == RequestObligation.MutationScope.ONLY_REQUIRED_TARGETS) {
            Set<String> outside = new TreeSet<>(changed);
            outside.removeAll(requiredMutationTargets);
            if (!outside.isEmpty()) failures.add("MUTATION_OUTSIDE_REQUIRED_TARGETS");
            if (!changed.containsAll(requiredMutationTargets)) {
                failures.add("REQUIRED_MUTATION_TARGET_UNCHANGED");
            }
        }
        for (String forbidden : obligation.forbiddenTargets()) {
            if (changed.contains(forbidden)) failures.add("FORBIDDEN_TARGET_CHANGED:" + forbidden);
        }

        RequestObligation.OutputShape output = obligation.outputShape();
        String outputBefore = utf8(evidence.beforeFiles.get(output.target()));
        String outputAfter = utf8(evidence.afterFiles.get(output.target()));
        if (outputBefore == null || outputAfter == null) {
            failures.add("OUTPUT_TARGET_NOT_UTF8_FILE");
            return new Result(false, List.copyOf(failures));
        }

        for (RequestObligation.PreservationConstraint constraint
                : obligation.preservationConstraints()) {
            String before = utf8(evidence.beforeFiles.get(constraint.target()));
            String after = utf8(evidence.afterFiles.get(constraint.target()));
            if (constraint.kind()
                    == RequestObligation.PreservationKind.NORMALIZED_PRIOR_CONTENT
                    && (before == null || after == null || !appendsOnlyRequestedStatement(
                             before,
                             after,
                             output.label(),
                             output.statement()))) {
                failures.add("NORMALIZED_PRIOR_CONTENT:" + constraint.target());
            }
        }
        for (RequestObligation.VerificationPostcondition postcondition
                : obligation.verificationPostconditions()) {
            String content = utf8(evidence.afterFiles.get(postcondition.target()));
            boolean passed = switch (postcondition.kind()) {
                case MATCHING_STATEMENT_PRESENT_ONCE ->
                        content != null
                                && matchingLineCount(
                                        content,
                                        output.label(),
                                        output.statement()) == 1;
                case MATCHING_STATEMENT_FINAL_LOGICAL_LINE ->
                        content != null
                                && matchingStatementIsFinalLine(
                                        content,
                                        output.label(),
                                        output.statement());
                case STRUCTURED_MUTATION_EVIDENCE ->
                        evidence.structuredMutationTargets.contains(postcondition.target());
            };
            if (!passed) failures.add(postcondition.kind().name() + ":" + postcondition.target());
        }
        return new Result(failures.isEmpty(), List.copyOf(failures));
    }

    static boolean appendsOnlyRequestedStatement(
            String oldString,
            String newString,
            String label,
            String statement
    ) {
        if (oldString == null || newString == null
                || label == null || label.isBlank()
                || statement == null || statement.isBlank()) {
            return false;
        }
        String oldNormalized = normalizeLineEndings(oldString);
        String newNormalized = normalizeLineEndings(newString);
        if (!newNormalized.startsWith(oldNormalized)) return false;
        List<String> nonBlank = newNormalized.substring(oldNormalized.length()).lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .toList();
        return nonBlank.size() == 1
                && AppendStatementMatcher.matches(nonBlank.getFirst(), label, statement);
    }

    private static long matchingLineCount(String content, String label, String statement) {
        return logicalLines(content).stream()
                .filter(line -> AppendStatementMatcher.matches(line, label, statement))
                .count();
    }

    private static boolean matchingStatementIsFinalLine(
            String content,
            String label,
            String statement
    ) {
        List<String> lines = logicalLines(content);
        return !lines.isEmpty()
                && AppendStatementMatcher.matches(lines.getLast(), label, statement);
    }

    private static List<String> logicalLines(String content) {
        if (content == null || content.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>(List.of(content.split("\\R", -1)));
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        return List.copyOf(lines);
    }

    public static String finalLogicalLine(byte[] utf8Content) {
        String content = utf8(utf8Content);
        if (content == null) return null;
        List<String> lines = logicalLines(content);
        return lines.isEmpty() ? "" : lines.getLast();
    }

    private static Set<String> changedTargets(
            Map<String, byte[]> before,
            Map<String, byte[]> after
    ) {
        Set<String> paths = new HashSet<>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());
        TreeSet<String> changed = new TreeSet<>();
        for (String path : paths) {
            if (!Arrays.equals(before.get(path), after.get(path))) changed.add(path);
        }
        return Set.copyOf(changed);
    }

    private static String utf8(byte[] bytes) {
        if (bytes == null) return null;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            return null;
        }
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

}
