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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        Set<String> verifiedTargets = new LinkedHashSet<>();

        for (TaskExpectation expectation : expectations) {
            if (expectation instanceof LiteralContentExpectation literal) {
                verifiedAny = true;
                addExpectationTarget(verifiedTargets, literal);
                verifyLiteralContentExpectation(root, literal, facts, problems, recordExpectationTrace);
            } else if (expectation instanceof ReplacementExpectation replacement) {
                verifiedAny = true;
                replacementRequired = true;
                addExpectationTarget(verifiedTargets, replacement);
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
                addExpectationTarget(verifiedTargets, appendLine);
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
                addExpectationTarget(verifiedTargets, bullets);
                verifyBulletListExpectation(root, bullets, facts, problems, recordExpectationTrace);
            }
        }

        return new Result(
                verifiedAny,
                replacementRequired,
                appendLineRequired,
                bulletCountRequired,
                coversAllSuccessfulMutations(verifiedTargets, successfulMutations),
                facts,
                problems);
    }

    private static void addExpectationTarget(Set<String> verifiedTargets, TaskExpectation expectation) {
        if (verifiedTargets == null || expectation == null) return;
        String target = normalizePath(expectation.targetPath());
        if (!target.isBlank()) verifiedTargets.add(target);
    }

    private static boolean coversAllSuccessfulMutations(
            Set<String> verifiedTargets,
            List<ToolCallLoop.ToolOutcome> successfulMutations
    ) {
        if (verifiedTargets == null || verifiedTargets.isEmpty()
                || successfulMutations == null || successfulMutations.isEmpty()) {
            return false;
        }
        Set<String> mutationTargets = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            String target = normalizePath(outcome.pathHint());
            if (!target.isBlank()) mutationTargets.add(target);
        }
        return !mutationTargets.isEmpty() && verifiedTargets.containsAll(mutationTargets);
    }

    private static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
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
        boolean oldPresent = ReplacementTextPresence.oldTextRemainsOutsideReplacement(
                observed,
                expectation.oldText(),
                expectation.newText());
        boolean newPresent = !expectation.newText().isEmpty()
                && ReplacementTextPresence.replacementTextObserved(observed, expectation.newText());
        boolean matched = !oldPresent && newPresent;
        if (matched && expectation.preserveRest()) {
            matched = TaskExpectationMutationEvidenceVerifier.verifyReplacementPreservation(
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
                && TaskExpectationMutationEvidenceVerifier.verifyAppendLineMutationEvidence(
                        pathHint,
                        expectedLine,
                        successfulMutations,
                        facts,
                        problems);
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

    record Result(
            boolean verifiedAny,
            boolean replacementRequired,
            boolean appendLineRequired,
            boolean bulletCountRequired,
            boolean coversAllSuccessfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        static Result empty() {
            return new Result(false, false, false, false, false, List.of(), List.of());
        }
    }
}
