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

    public record Result(
            boolean passed,
            List<String> failedPostconditions,
            String obligationSha256
    ) {
        public Result {
            failedPostconditions = failedPostconditions == null
                    ? List.of()
                    : List.copyOf(failedPostconditions);
            obligationSha256 = obligationSha256 == null
                    ? ""
                    : obligationSha256.strip();
            if (!obligationSha256.isEmpty()
                    && !obligationSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "Request-obligation verification identity must be SHA-256");
            }
            if (passed && obligationSha256.isBlank()) {
                throw new IllegalArgumentException(
                        "A passed final-state result requires an obligation identity");
            }
        }

        /** Compatibility shape for an unavailable or not-evaluated result. */
        public Result(boolean passed, List<String> failedPostconditions) {
            this(passed, failedPostconditions, "");
        }

        public boolean passedFor(RequestObligation obligation) {
            return passed
                    && obligation != null
                    && obligation.derivationStatus()
                            == RequestObligation.DerivationStatus.COMPLETE
                    && obligation.canonicalSha256().equals(obligationSha256);
        }
    }

    public static Result verify(RequestObligation obligation, Evidence evidence) {
        if (obligation == null
                || obligation.derivationStatus() != RequestObligation.DerivationStatus.COMPLETE
                || evidence == null) {
            return new Result(false, List.of("OBLIGATION_OR_EVIDENCE_UNAVAILABLE"));
        }
        String obligationSha256 = obligation.canonicalSha256();
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
            return new Result(false, List.copyOf(failures), obligationSha256);
        }

        for (RequestObligation.PreservationConstraint constraint
                : obligation.preservationConstraints()) {
            String before = utf8(evidence.beforeFiles.get(constraint.target()));
            String after = utf8(evidence.afterFiles.get(constraint.target()));
            boolean passed = switch (constraint.kind()) {
                case NORMALIZED_PRIOR_CONTENT ->
                        before != null
                                && after != null
                                && switch (output.kind()) {
                                    case APPEND_LABELED_STATEMENT ->
                                            appendsOnlyRequestedStatement(
                                                    before,
                                                    after,
                                                    output.label(),
                                                    output.statement());
                                    case APPEND_MARKDOWN_SECTION ->
                                            appendsOnlyRequestedSection(
                                                    before,
                                                    after,
                                                    output.label(),
                                                    output.statement());
                                    case REPLACE_TEXT, NONE -> false;
                                };
                case UNCHANGED_CONTENT_AROUND_REPLACEMENT ->
                        before != null
                                && after != null
                                && output.kind() == RequestObligation.OutputKind.REPLACE_TEXT
                                && replacesOnlyRequestedText(
                                        before,
                                        after,
                                        output.label(),
                                        output.statement());
            };
            if (!passed) {
                failures.add(constraint.kind().name() + ":" + constraint.target());
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
                case MATCHING_SECTION_PRESENT_ONCE ->
                        content != null
                                && observeSection(
                                        content,
                                        output.label(),
                                        output.statement()).exactUniqueSection();
                case MATCHING_SECTION_FINAL_DOCUMENT_SECTION ->
                        content != null
                                && observeSection(
                                        content,
                                        output.label(),
                                        output.statement()).exactUniqueFinalSection();
                case REQUESTED_REPLACEMENT_EXACTLY_ONCE ->
                        outputBefore != null
                                && content != null
                                && replacesOnlyRequestedText(
                                        outputBefore,
                                        content,
                                        output.label(),
                                        output.statement());
                case OLD_TEXT_ABSENT ->
                        content != null
                                && !ReplacementTextPresence.oldTextRemainsOutsideReplacement(
                                        content,
                                        output.label(),
                                        output.statement());
                case NEW_TEXT_PRESENT ->
                        content != null
                                && ReplacementTextPresence.replacementTextObserved(
                                        content,
                                        output.statement());
                case STRUCTURED_MUTATION_EVIDENCE ->
                        evidence.structuredMutationTargets.contains(postcondition.target());
            };
            if (!passed) failures.add(postcondition.kind().name() + ":" + postcondition.target());
        }
        return new Result(
                failures.isEmpty(),
                List.copyOf(failures),
                obligationSha256);
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

    static boolean appendsOnlyRequestedSection(
            String oldString,
            String newString,
            String sectionName,
            String expectedSentence
    ) {
        if (oldString == null || newString == null
                || sectionName == null || sectionName.isBlank()
                || expectedSentence == null || expectedSentence.isBlank()) {
            return false;
        }
        String oldNormalized = normalizeLineEndings(oldString);
        String newNormalized = normalizeLineEndings(newString);
        if (!newNormalized.startsWith(oldNormalized)) return false;
        List<String> nonBlank = newNormalized.substring(oldNormalized.length()).lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .toList();
        return nonBlank.size() == 2
                && MarkdownHeadingParser.headingText(nonBlank.get(0)).equals(sectionName)
                && nonBlank.get(1).equals(expectedSentence);
    }

    static boolean replacesOnlyRequestedText(
            String oldString,
            String newString,
            String oldText,
            String newText
    ) {
        if (oldString == null || newString == null
                || oldText == null || oldText.isBlank()
                || newText == null || newText.isBlank()) {
            return false;
        }
        String oldNormalized = normalizeLineEndings(oldString);
        String newNormalized = normalizeLineEndings(newString);
        String requestedOld = normalizeLineEndings(oldText);
        String requestedNew = normalizeLineEndings(newText);
        if (countOccurrences(oldNormalized, requestedOld) != 1) return false;
        int replacementStart = oldNormalized.indexOf(requestedOld);
        String expected = oldNormalized.substring(0, replacementStart)
                + requestedNew
                + oldNormalized.substring(replacementStart + requestedOld.length());
        return expected.equals(newNormalized)
                || stripSingleTerminalNewline(expected)
                        .equals(stripSingleTerminalNewline(newNormalized));
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

    private static SectionObservation observeSection(
            String content,
            String sectionName,
            String expectedSentence
    ) {
        String normalized = normalizeLineEndings(content);
        String[] lines = normalized.split("\n", -1);
        int headingIndex = -1;
        int headingCount = 0;
        for (int index = 0; index < lines.length; index++) {
            if (MarkdownHeadingParser.headingText(lines[index]).equals(sectionName)) {
                headingIndex = index;
                headingCount++;
            }
        }
        if (headingCount != 1) {
            return new SectionObservation(headingCount, false, false);
        }
        List<String> sectionLines = new ArrayList<>();
        int lastSectionContentIndex = headingIndex;
        for (int index = headingIndex + 1; index < lines.length; index++) {
            String line = lines[index].strip();
            if (!MarkdownHeadingParser.headingText(line).isBlank()) break;
            if (!line.isBlank()) {
                sectionLines.add(line);
                lastSectionContentIndex = index;
            }
        }
        int lastDocumentContentIndex = -1;
        for (int index = lines.length - 1; index >= 0; index--) {
            if (!lines[index].isBlank()) {
                lastDocumentContentIndex = index;
                break;
            }
        }
        boolean exact = sectionLines.size() == 1
                && sectionLines.getFirst().equals(expectedSentence);
        return new SectionObservation(
                headingCount,
                exact,
                exact && lastSectionContentIndex == lastDocumentContentIndex);
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

    private static String stripSingleTerminalNewline(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || haystack.isEmpty()
                || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private record SectionObservation(
            int headingCount,
            boolean exactSection,
            boolean finalSection
    ) {
        boolean exactUniqueSection() {
            return headingCount == 1 && exactSection;
        }

        boolean exactUniqueFinalSection() {
            return exactUniqueSection() && finalSection;
        }
    }

}
