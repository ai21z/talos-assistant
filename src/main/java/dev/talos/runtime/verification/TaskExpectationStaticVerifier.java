package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.expectation.AppendLineExpectation;
import dev.talos.runtime.expectation.BulletListExpectation;
import dev.talos.runtime.expectation.ExpectationVerificationStatus;
import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.tools.ToolAliasPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Verifies deterministic post-apply expectations resolved from explicit task wording. */
final class TaskExpectationStaticVerifier {

    private TaskExpectationStaticVerifier() {}

    static Result verify(
            TaskContract contract,
            Path root,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            boolean recordExpectationTrace
    ) {
        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);
        if (expectations.isEmpty()) return Result.empty();

        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        boolean verifiedAny = false;
        boolean replacementRequired = false;
        boolean appendLineRequired = false;
        boolean bulletCountRequired = false;

        for (TaskExpectation expectation : expectations) {
            if (expectation instanceof LiteralContentExpectation literal) {
                verifiedAny = true;
                verifyLiteralContentExpectation(root, literal, facts, problems, recordExpectationTrace);
            } else if (expectation instanceof ReplacementExpectation replacement) {
                verifiedAny = true;
                replacementRequired = true;
                verifyReplacementExpectation(
                        root,
                        replacement,
                        successfulMutations,
                        facts,
                        problems,
                        recordExpectationTrace);
            } else if (expectation instanceof AppendLineExpectation appendLine) {
                verifiedAny = true;
                appendLineRequired = true;
                verifyAppendLineExpectation(
                        root,
                        appendLine,
                        successfulMutations,
                        facts,
                        problems,
                        recordExpectationTrace);
            } else if (expectation instanceof BulletListExpectation bullets) {
                verifiedAny = true;
                bulletCountRequired = true;
                verifyBulletListExpectation(root, bullets, facts, problems, recordExpectationTrace);
            }
        }

        return new Result(
                verifiedAny,
                replacementRequired,
                appendLineRequired,
                bulletCountRequired,
                facts,
                problems);
    }

    private static void verifyLiteralContentExpectation(
            Path root,
            LiteralContentExpectation expectation,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        TaskExpectationTargetReader.Result target = TaskExpectationTargetReader.read(
                root,
                expectation.targetPath(),
                "exact content verification could not resolve target path.",
                "exact content verification target is not a readable file.",
                "exact content verification could not read target");
        String pathHint = target.pathHint();
        if (target.hasProblem()) {
            problems.add(target.problem());
            if (recordExpectationTrace) TaskExpectationTraceRecorder.recordLiteralExpectation(
                    expectation,
                    ExpectationVerificationStatus.FAILED,
                    "");
            return;
        }

        String observed = target.content();
        boolean matched = observed.equals(expectation.expectedContent());
        ExpectationVerificationStatus status = matched
                ? ExpectationVerificationStatus.PASSED
                : ExpectationVerificationStatus.FAILED;
        if (recordExpectationTrace) {
            TaskExpectationTraceRecorder.recordLiteralExpectation(expectation, status, observed);
        }
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

    private static void verifyReplacementExpectation(
            Path root,
            ReplacementExpectation expectation,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        TaskExpectationTargetReader.Result target = TaskExpectationTargetReader.read(
                root,
                expectation.targetPath(),
                "replacement verification could not resolve target path.",
                "replacement verification target is not a readable file.",
                "replacement verification could not read target");
        String pathHint = target.pathHint();
        if (target.hasProblem()) {
            problems.add(target.problem());
            if (recordExpectationTrace) TaskExpectationTraceRecorder.recordReplacementExpectation(
                    expectation,
                    ExpectationVerificationStatus.FAILED,
                    false,
                    false);
            return;
        }

        String observed = target.content();
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
            TaskExpectationTraceRecorder.recordReplacementExpectation(
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

    private static void verifyAppendLineExpectation(
            Path root,
            AppendLineExpectation expectation,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        TaskExpectationTargetReader.Result target = TaskExpectationTargetReader.read(
                root,
                expectation.targetPath(),
                "appended line verification could not resolve target path.",
                "appended line verification target is not a readable file.",
                "appended line verification could not read target");
        String pathHint = target.pathHint();
        if (target.hasProblem()) {
            problems.add(target.problem());
            if (recordExpectationTrace) TaskExpectationTraceRecorder.recordAppendLineExpectation(
                    expectation,
                    ExpectationVerificationStatus.FAILED,
                    "");
            return;
        }

        String observed = target.content();
        List<String> lines = logicalLines(observed);
        String expectedLine = expectation.expectedLine();
        long matchingLines = lines.stream().filter(expectedLine::equals).count();
        String finalLine = lines.isEmpty() ? "" : lines.getLast();
        boolean postStateMatched = matchingLines == 1 && expectedLine.equals(finalLine);
        boolean appendOnlyEvidenceSatisfied = postStateMatched
                && verifyAppendLineMutationEvidence(pathHint, expectedLine, successfulMutations, facts, problems);
        boolean matched = postStateMatched && appendOnlyEvidenceSatisfied;
        if (recordExpectationTrace) {
            TaskExpectationTraceRecorder.recordAppendLineExpectation(
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
        TaskExpectationTargetReader.Result target = TaskExpectationTargetReader.read(
                root,
                expectation.targetPath(),
                "bullet count verification could not resolve target path.",
                "bullet count verification target is not a readable file.",
                "bullet count verification could not read target");
        String pathHint = target.pathHint();
        if (target.hasProblem()) {
            problems.add(target.problem());
            if (recordExpectationTrace) TaskExpectationTraceRecorder.recordBulletListExpectation(
                    expectation,
                    ExpectationVerificationStatus.FAILED,
                    0);
            return;
        }

        String observed = target.content();
        int observedCount = bulletLineCount(observed);
        int nonBulletLines = nonBlankNonBulletLineCount(observed);
        boolean matched = observedCount == expectation.expectedBulletCount() && nonBulletLines == 0;
        if (recordExpectationTrace) {
            TaskExpectationTraceRecorder.recordBulletListExpectation(
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

    private static int nonBlankNonBulletLineCount(String content) {
        if (content == null || content.isBlank()) return 0;
        int count = 0;
        for (String line : content.split("\\R")) {
            if (line.isBlank()) continue;
            if (!isBulletLine(line)) count++;
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
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    record Result(
            boolean verifiedAny,
            boolean replacementRequired,
            boolean appendLineRequired,
            boolean bulletCountRequired,
            List<String> facts,
            List<String> problems
    ) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        static Result empty() {
            return new Result(false, false, false, false, List.of(), List.of());
        }
    }
}
