package dev.talos.runtime.verification;

import dev.talos.core.util.TextReplacement;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.WorkspacePathIdentity;

import java.nio.file.Path;
import java.util.List;

/** Verifies mutation evidence needed by task expectations without owning expectation post-state checks. */
final class TaskExpectationMutationEvidenceVerifier {

    private TaskExpectationMutationEvidenceVerifier() {}

    static boolean verifyReplacementPreservation(
            Path root,
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
                    || !sameTarget(root, pathHint, outcome.pathHint())) {
                continue;
            }
            sawRelevantMutation = true;
            String canonicalTool = ToolAliasPolicy.localCanonicalName(outcome.toolName());
            ToolMutationEvidence evidence = outcome.mutationEvidence();
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

    static FinalStateEvidence collectReplacementFinalStateEvidence(
            Path root,
            ReplacementExpectation expectation,
            String pathHint,
            String finalContent,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        if (successfulMutations == null || successfulMutations.isEmpty()) {
            problems.add(pathHint + ": replacement preservation had no mutation evidence.");
            return FinalStateEvidence.failed();
        }
        List<ToolCallLoop.ToolOutcome> relevant = successfulMutations.stream()
                .filter(outcome -> outcome != null
                        && outcome.success()
                        && sameTarget(root, pathHint, outcome.pathHint()))
                .toList();
        if (relevant.size() != 1) {
            problems.add(pathHint
                    + ": preserve-rest replacement requires exactly one successful matching mutation.");
            return FinalStateEvidence.failed();
        }

        ToolCallLoop.ToolOutcome outcome = relevant.getFirst();
        String canonicalTool = ToolAliasPolicy.localCanonicalName(outcome.toolName());
        ToolMutationEvidence evidence = outcome.mutationEvidence();
        String beforeContent;
        if ("edit_file".equals(canonicalTool)) {
            if (evidence == null || !evidence.exactEditReplacement()) {
                problems.add(pathHint + ": talos.edit_file cannot prove preserve-rest replacement "
                        + "without exact edit evidence.");
                return FinalStateEvidence.failed();
            }
            if (!replacementOnlyChangesRequestedText(
                    evidence.oldString(),
                    evidence.newString(),
                    expectation.oldText(),
                    expectation.newText())) {
                problems.add(pathHint
                        + ": replacement preservation exact edit changed content beyond the requested text.");
                return FinalStateEvidence.failed();
            }
            beforeContent = reconstructExactEditBefore(
                    finalContent,
                    evidence.oldString(),
                    evidence.newString());
            if (beforeContent == null) {
                problems.add(pathHint
                        + ": exact edit evidence could not reconstruct the prior file.");
                return FinalStateEvidence.failed();
            }
            facts.add(pathHint
                    + ": exact edit evidence preserved content beyond requested replacement.");
        } else if ("write_file".equals(canonicalTool)) {
            if (evidence == null || !evidence.fullWriteReplacement()) {
                problems.add(pathHint + ": talos.write_file cannot prove preserve-rest replacement "
                        + "without complete same-turn read evidence.");
                return FinalStateEvidence.failed();
            }
            if (!replacementOnlyChangesRequestedText(
                    evidence.oldString(),
                    evidence.newString(),
                    expectation.oldText(),
                    expectation.newText())) {
                problems.add(pathHint
                        + ": replacement preservation changed content beyond the requested text.");
                return FinalStateEvidence.failed();
            }
            if (!sameNormalizedContent(evidence.newString(), finalContent)) {
                problems.add(pathHint
                        + ": replacement mutation evidence did not match final readback.");
                return FinalStateEvidence.failed();
            }
            beforeContent = evidence.oldString();
            facts.add(pathHint + ": replacement preservation matched prior content.");
        } else {
            problems.add(pathHint + ": mutation tool cannot prove preserve-rest replacement.");
            return FinalStateEvidence.failed();
        }
        return new FinalStateEvidence(true, beforeContent);
    }

    static boolean verifyAppendLineMutationEvidence(
            Path root,
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
                    && sameTarget(root, pathHint, outcome.pathHint())) {
                if (outcome.mutationEvidence() != null
                        && outcome.mutationEvidence().fullWriteReplacement()) {
                    sawRelevantFullWrite = true;
                    ToolMutationEvidence evidence = outcome.mutationEvidence();
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
                    || !sameTarget(root, pathHint, outcome.pathHint())
                    || outcome.mutationEvidence() == null
                    || !outcome.mutationEvidence().exactEditReplacement()) {
                continue;
            }
            sawRelevantExactEdit = true;
            ToolMutationEvidence evidence = outcome.mutationEvidence();
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

    static boolean verifyAppendSectionMutationEvidence(
            Path root,
            String pathHint,
            String sectionName,
            String expectedSentence,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        if (successfulMutations == null || successfulMutations.isEmpty()) {
            problems.add(pathHint + ": append-section verification had no mutation evidence.");
            return false;
        }
        boolean sawRelevantMutation = false;
        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            if (outcome == null || !outcome.success()
                    || !sameTarget(root, pathHint, outcome.pathHint())) {
                continue;
            }
            sawRelevantMutation = true;
            String canonicalTool = ToolAliasPolicy.localCanonicalName(outcome.toolName());
            ToolMutationEvidence evidence = outcome.mutationEvidence();
            if ("write_file".equals(canonicalTool)) {
                if (evidence == null || !evidence.fullWriteReplacement()) {
                    problems.add(pathHint + ": talos.write_file cannot prove append-section preservation "
                            + "without complete same-turn read evidence.");
                    return false;
                }
            } else if ("edit_file".equals(canonicalTool)) {
                if (evidence == null || !evidence.exactEditReplacement()) {
                    problems.add(pathHint + ": talos.edit_file cannot prove append-section preservation "
                            + "without exact edit evidence.");
                    return false;
                }
            } else {
                problems.add(pathHint + ": mutation tool cannot prove append-section preservation.");
                return false;
            }
            if (!appendsOnlyRequestedSection(
                    evidence.oldString(), evidence.newString(), sectionName, expectedSentence)) {
                problems.add(pathHint + ": append-section mutation did not preserve prior content "
                        + "before the requested section.");
                return false;
            }
        }
        if (!sawRelevantMutation) {
            problems.add(pathHint + ": append-section verification had no matching mutation evidence.");
            return false;
        }
        facts.add(pathHint + ": append-section mutation evidence preserved prior content.");
        return true;
    }

    static FinalStateEvidence collectAppendSectionFinalStateEvidence(
            Path root,
            String pathHint,
            String sectionName,
            String expectedSentence,
            String finalContent,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        if (successfulMutations == null || successfulMutations.isEmpty()) {
            problems.add(pathHint + ": append-section verification had no mutation evidence.");
            return FinalStateEvidence.failed();
        }
        List<ToolCallLoop.ToolOutcome> relevant = successfulMutations.stream()
                .filter(outcome -> outcome != null
                        && outcome.success()
                        && sameTarget(root, pathHint, outcome.pathHint()))
                .toList();
        if (relevant.size() != 1) {
            problems.add(pathHint
                    + ": append-section verification requires exactly one successful matching mutation.");
            return FinalStateEvidence.failed();
        }

        ToolCallLoop.ToolOutcome outcome = relevant.getFirst();
        String canonicalTool = ToolAliasPolicy.localCanonicalName(outcome.toolName());
        ToolMutationEvidence evidence = outcome.mutationEvidence();
        String beforeContent;
        if ("write_file".equals(canonicalTool)) {
            if (evidence == null || !evidence.fullWriteReplacement()) {
                problems.add(pathHint + ": talos.write_file cannot prove append-section preservation "
                        + "without complete same-turn read evidence.");
                return FinalStateEvidence.failed();
            }
            beforeContent = evidence.oldString();
        } else if ("edit_file".equals(canonicalTool)) {
            if (evidence == null || !evidence.exactEditReplacement()) {
                problems.add(pathHint + ": talos.edit_file cannot prove append-section preservation "
                        + "without exact edit evidence.");
                return FinalStateEvidence.failed();
            }
            beforeContent = reconstructExactEditBefore(
                    finalContent,
                    evidence.oldString(),
                    evidence.newString());
            if (beforeContent == null) {
                problems.add(pathHint
                        + ": exact edit evidence could not reconstruct the prior file.");
                return FinalStateEvidence.failed();
            }
        } else {
            problems.add(pathHint + ": mutation tool cannot prove append-section preservation.");
            return FinalStateEvidence.failed();
        }
        if (!appendsOnlyRequestedSection(
                evidence.oldString(),
                evidence.newString(),
                sectionName,
                expectedSentence)) {
            problems.add(pathHint + ": append-section mutation did not preserve prior content "
                    + "before the requested section.");
            return FinalStateEvidence.failed();
        }
        if (!sameNormalizedContent(evidence.newString(), finalContent)
                && "write_file".equals(canonicalTool)) {
            problems.add(pathHint
                    + ": append-section mutation evidence did not match final readback.");
            return FinalStateEvidence.failed();
        }
        facts.add(pathHint + ": append-section mutation evidence preserved prior content.");
        return new FinalStateEvidence(true, beforeContent);
    }

    static boolean verifyAppendStatementMutationEvidence(
            Path root,
            String pathHint,
            String label,
            String statement,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        return collectAppendStatementMutationEvidence(
                root,
                pathHint,
                label,
                statement,
                null,
                successfulMutations,
                facts,
                problems).passed();
    }

    static AppendStatementEvidence collectAppendStatementMutationEvidence(
            Path root,
            String pathHint,
            String label,
            String statement,
            String finalContent,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        if (successfulMutations == null || successfulMutations.isEmpty()) {
            problems.add(pathHint + ": append-statement verification had no mutation evidence.");
            return AppendStatementEvidence.failed();
        }
        boolean sawRelevantMutation = false;
        String beforeContent = null;
        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            if (outcome == null || !outcome.success()
                    || !sameTarget(root, pathHint, outcome.pathHint())) {
                continue;
            }
            sawRelevantMutation = true;
            String canonicalTool = ToolAliasPolicy.localCanonicalName(outcome.toolName());
            ToolMutationEvidence evidence = outcome.mutationEvidence();
            if ("write_file".equals(canonicalTool)) {
                if (evidence == null || !evidence.fullWriteReplacement()) {
                    problems.add(pathHint + ": talos.write_file cannot prove append-statement preservation "
                            + "without complete same-turn read evidence.");
                    return AppendStatementEvidence.failed();
                }
                if (beforeContent == null) beforeContent = evidence.oldString();
            } else if ("edit_file".equals(canonicalTool)) {
                if (evidence == null || !evidence.exactEditReplacement()) {
                    problems.add(pathHint + ": talos.edit_file cannot prove append-statement preservation "
                            + "without exact edit evidence.");
                    return AppendStatementEvidence.failed();
                }
                if (beforeContent == null) {
                    beforeContent = finalContent == null
                            ? evidence.oldString()
                            : reconstructExactEditBefore(
                                    finalContent,
                                    evidence.oldString(),
                                    evidence.newString());
                    if (beforeContent == null) {
                        problems.add(pathHint
                                + ": exact edit evidence could not reconstruct the prior file.");
                        return AppendStatementEvidence.failed();
                    }
                }
            } else {
                problems.add(pathHint + ": mutation tool cannot prove append-statement preservation.");
                return AppendStatementEvidence.failed();
            }
            if (!appendsOnlyRequestedStatement(
                    evidence.oldString(), evidence.newString(), label, statement)) {
                problems.add(pathHint + ": append-statement mutation did not preserve prior content "
                        + "before the requested statement.");
                return AppendStatementEvidence.failed();
            }
        }
        if (!sawRelevantMutation) {
            problems.add(pathHint + ": append-statement verification had no matching mutation evidence.");
            return AppendStatementEvidence.failed();
        }
        facts.add(pathHint + ": append-statement mutation evidence preserved prior content.");
        return new AppendStatementEvidence(true, beforeContent == null ? "" : beforeContent);
    }

    private static String reconstructExactEditBefore(
            String finalContent,
            String oldString,
            String newString
    ) {
        if (finalContent == null
                || oldString == null
                || newString == null
                || newString.isEmpty()) {
            return null;
        }
        TextReplacement.Match reverse = TextReplacement.findUniqueMatch(
                finalContent,
                newString,
                oldString);
        if (reverse.count() != 1) return null;
        return finalContent.substring(0, reverse.startRaw())
                + reverse.replacement()
                + finalContent.substring(reverse.endRaw());
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

    private static boolean appendsOnlyRequestedSection(
            String oldString,
            String newString,
            String sectionName,
            String expectedSentence
    ) {
        return RequestObligationFinalStateVerifier.appendsOnlyRequestedSection(
                oldString,
                newString,
                sectionName,
                expectedSentence);
    }

    private static boolean appendsOnlyRequestedStatement(
            String oldString,
            String newString,
            String label,
            String statement
    ) {
        return RequestObligationFinalStateVerifier.appendsOnlyRequestedStatement(
                oldString,
                newString,
                label,
                statement);
    }

    private static String normalizeLineEndings(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String stripSingleTerminalNewline(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean sameNormalizedContent(String expected, String observed) {
        String expectedNormalized = normalizeLineEndings(expected);
        String observedNormalized = normalizeLineEndings(observed);
        return expectedNormalized.equals(observedNormalized)
                || stripSingleTerminalNewline(expectedNormalized)
                        .equals(stripSingleTerminalNewline(observedNormalized));
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

    /** The mutation names the target as text, or names the same workspace file under another spelling. */
    private static boolean sameTarget(Path root, String pathHint, String mutatedPath) {
        return normalizePath(mutatedPath).equals(pathHint)
                || WorkspacePathIdentity.matches(root, pathHint, mutatedPath);
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    record AppendStatementEvidence(boolean passed, String beforeContent) {
        AppendStatementEvidence {
            beforeContent = beforeContent == null ? "" : beforeContent;
        }

        static AppendStatementEvidence failed() {
            return new AppendStatementEvidence(false, "");
        }
    }

    record FinalStateEvidence(boolean passed, String beforeContent) {
        FinalStateEvidence {
            beforeContent = beforeContent == null ? "" : beforeContent;
        }

        static FinalStateEvidence failed() {
            return new FinalStateEvidence(false, "");
        }
    }
}
