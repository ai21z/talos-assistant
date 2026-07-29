package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.expectation.AppendLineExpectation;
import dev.talos.runtime.expectation.AppendSectionExpectation;
import dev.talos.runtime.expectation.AppendStatementExpectation;
import dev.talos.runtime.expectation.BulletListExpectation;
import dev.talos.runtime.expectation.ExpectationVerificationStatus;
import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.expectation.TaskExpectation;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;

import java.nio.charset.StandardCharsets;
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
        return verify(
                contract,
                RequestObligationCompiler.compile(contract),
                root,
                successfulMutations,
                recordExpectationTrace);
    }

    static Result verify(
            TaskContract contract,
            RequestObligation requestObligation,
            Path root,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            boolean recordExpectationTrace
    ) {
        RequestObligation compiled = RequestObligationCompiler.compile(contract);
        if (requestObligation == null || !requestObligation.equals(compiled)) {
            return failedObligationResult(
                    "Supplied request obligation differs from production compilation.");
        }
        if (compiled.derivationStatus() == RequestObligation.DerivationStatus.COMPLETE) {
            return verifyCompleteRequestObligation(
                    compiled,
                    root,
                    successfulMutations,
                    recordExpectationTrace);
        }

        List<TaskExpectation> expectations = TaskExpectationResolver.resolve(contract);
        if (expectations.isEmpty()) return Result.empty();

        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        boolean verifiedAny = false;
        boolean replacementRequired = false;
        boolean appendLineRequired = false;
        boolean appendSectionRequired = false;
        boolean appendStatementRequired = false;
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
            } else if (expectation instanceof AppendSectionExpectation appendSection) {
                verifiedAny = true;
                appendSectionRequired = true;
                addExpectationTarget(verifiedTargets, appendSection);
                verifyAppendSectionExpectation(
                        root,
                        appendSection,
                        successfulMutations,
                        facts,
                        problems,
                        recordExpectationTrace);
            } else if (expectation instanceof AppendStatementExpectation appendStatement) {
                verifiedAny = true;
                appendStatementRequired = true;
                addExpectationTarget(verifiedTargets, appendStatement);
                verifyAppendStatementExpectation(
                        root,
                        appendStatement,
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
                appendSectionRequired,
                appendStatementRequired,
                bulletCountRequired,
                coversAllSuccessfulMutations(verifiedTargets, successfulMutations),
                facts,
                problems);
    }

    private static Result verifyCompleteRequestObligation(
            RequestObligation obligation,
            Path root,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            boolean recordExpectationTrace
    ) {
        RequestObligation.OutputShape output = obligation.outputShape();
        AppendStatementExpectation expectation = new AppendStatementExpectation(
                output.target(),
                output.label(),
                output.statement(),
                "request-obligation-v" + obligation.schemaVersion());
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        TaskExpectationTargetReader.Result target = TaskExpectationTargetReader.read(
                root,
                output.target(),
                "request-obligation verification could not resolve target path.",
                "request-obligation target is not a readable file.",
                "request-obligation verification could not read target");
        if (target.hasProblem()) {
            problems.add(target.problem());
            if (recordExpectationTrace) {
                TaskExpectationTraceRecorder.recordAppendStatementExpectation(
                        expectation,
                        ExpectationVerificationStatus.FAILED,
                        "");
            }
            return completeAppendResult(
                    false,
                    Set.of(output.target()),
                    successfulMutations,
                    facts,
                    problems);
        }

        TaskExpectationMutationEvidenceVerifier.AppendStatementEvidence mutationEvidence =
                TaskExpectationMutationEvidenceVerifier.collectAppendStatementMutationEvidence(
                        target.pathHint(),
                        output.label(),
                        output.statement(),
                        target.content(),
                        successfulMutations,
                        facts,
                        problems);
        byte[] before = mutationEvidence.beforeContent().getBytes(StandardCharsets.UTF_8);
        byte[] after = target.content().getBytes(StandardCharsets.UTF_8);
        RequestObligationFinalStateVerifier.Result finalState =
                mutationEvidence.passed()
                        ? RequestObligationFinalStateVerifier.verify(
                                obligation,
                                new RequestObligationFinalStateVerifier.Evidence(
                                        java.util.Map.of(output.target(), before),
                                        java.util.Map.of(output.target(), after),
                                        Set.of(output.target())))
                        : new RequestObligationFinalStateVerifier.Result(
                                false,
                                List.of("STRUCTURED_MUTATION_EVIDENCE_UNAVAILABLE"));
        boolean passed = mutationEvidence.passed() && finalState.passed();
        String finalLine = RequestObligationFinalStateVerifier.finalLogicalLine(after);
        if (recordExpectationTrace) {
            TaskExpectationTraceRecorder.recordAppendStatementExpectation(
                    expectation,
                    passed
                            ? ExpectationVerificationStatus.PASSED
                            : ExpectationVerificationStatus.FAILED,
                    finalLine == null ? "" : finalLine);
        }
        if (passed) {
            facts.add(target.pathHint()
                    + ": appended statement matched requested final line.");
        } else {
            finalState.failedPostconditions().forEach(failure ->
                    problems.add(target.pathHint()
                            + ": request-obligation postcondition failed: " + failure + "."));
        }
        return completeAppendResult(
                passed,
                Set.of(output.target()),
                successfulMutations,
                facts,
                problems);
    }

    private static Result completeAppendResult(
            boolean passed,
            Set<String> verifiedTargets,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        boolean coversAllMutations = coversAllSuccessfulMutations(
                verifiedTargets,
                successfulMutations);
        if (passed && !coversAllMutations) {
            problems.add(
                    "Request-obligation evidence did not cover every successful mutation.");
        }
        return new Result(
                true,
                false,
                false,
                false,
                true,
                false,
                passed && coversAllMutations,
                facts,
                problems);
    }

    private static Result failedObligationResult(String problem) {
        return new Result(
                true,
                false,
                false,
                false,
                true,
                false,
                false,
                List.of(),
                List.of(problem));
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

    private static void verifyAppendSectionExpectation(
            Path root,
            AppendSectionExpectation expectation,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        TaskExpectationTargetReader.Result target = TaskExpectationTargetReader.read(
                root,
                expectation.targetPath(),
                "append section verification could not resolve target path.",
                "append section verification target is not a readable file.",
                "append section verification could not read target");
        String pathHint = target.pathHint();
        if (target.hasProblem()) {
            problems.add(target.problem());
            if (recordExpectationTrace) TaskExpectationTraceRecorder.recordAppendSectionExpectation(
                    expectation, ExpectationVerificationStatus.FAILED, "");
            return;
        }

        AppendSectionObservation observation = observeAppendedSection(
                target.content(), expectation.sectionName(), expectation.expectedSentence());
        boolean evidencePassed = TaskExpectationMutationEvidenceVerifier.verifyAppendSectionMutationEvidence(
                pathHint,
                expectation.sectionName(),
                expectation.expectedSentence(),
                successfulMutations,
                facts,
                problems);
        boolean matched = observation.matched() && evidencePassed;
        if (recordExpectationTrace) TaskExpectationTraceRecorder.recordAppendSectionExpectation(
                expectation,
                matched ? ExpectationVerificationStatus.PASSED : ExpectationVerificationStatus.FAILED,
                observation.observedBlock());
        if (!observation.headingFound()) {
            problems.add(pathHint + ": requested Markdown section heading was not found.");
        } else if (!observation.uniqueHeading()) {
            problems.add(pathHint + ": requested Markdown section heading was not unique.");
        } else if (!observation.sentenceFound()) {
            problems.add(pathHint + ": requested section sentence was not found under its heading.");
        } else if (!observation.exactOneSentenceSection()) {
            problems.add(pathHint + ": requested section did not contain exactly the requested sentence.");
        } else if (!observation.finalSection()) {
            problems.add(pathHint + ": requested section was not appended as the final document section.");
        } else if (evidencePassed) {
            facts.add(pathHint + ": requested appended section matched heading and sentence.");
        }
    }

    private static AppendSectionObservation observeAppendedSection(
            String content,
            String sectionName,
            String expectedSentence
    ) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        int headingIndex = -1;
        int headingCount = 0;
        for (int i = 0; i < lines.length; i++) {
            if (MarkdownHeadingParser.headingText(lines[i]).equals(sectionName)) {
                headingIndex = i;
                headingCount++;
            }
        }
        if (headingCount == 0) {
            return new AppendSectionObservation(false, false, false, false, false, "");
        }
        if (headingCount != 1) {
            return new AppendSectionObservation(true, false, false, false, false, "");
        }

        List<String> sectionLines = new ArrayList<>();
        int lastSectionContentIndex = -1;
        for (int i = headingIndex + 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (!MarkdownHeadingParser.headingText(line).isBlank()) break;
            if (!line.isBlank()) {
                sectionLines.add(line);
                lastSectionContentIndex = i;
            }
        }
        int lastDocumentContentIndex = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                lastDocumentContentIndex = i;
                break;
            }
        }
        boolean sentenceFound = sectionLines.stream().anyMatch(expectedSentence::equals);
        boolean exact = sectionLines.size() == 1 && sentenceFound;
        boolean finalSection = lastSectionContentIndex == lastDocumentContentIndex;
        String observed = sectionName + "\n" + String.join("\n", sectionLines);
        return new AppendSectionObservation(true, true, sentenceFound, exact, finalSection, observed);
    }

    private static void verifyAppendStatementExpectation(
            Path root,
            AppendStatementExpectation expectation,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems,
            boolean recordExpectationTrace
    ) {
        TaskExpectationTargetReader.Result target = TaskExpectationTargetReader.read(
                root,
                expectation.targetPath(),
                "append statement verification could not resolve target path.",
                "append statement verification target is not a readable file.",
                "append statement verification could not read target");
        String pathHint = target.pathHint();
        if (target.hasProblem()) {
            problems.add(target.problem());
            if (recordExpectationTrace) TaskExpectationTraceRecorder.recordAppendStatementExpectation(
                    expectation, ExpectationVerificationStatus.FAILED, "");
            return;
        }

        List<String> lines = logicalLines(target.content());
        String finalLine = lines.isEmpty() ? "" : lines.getLast();
        long matchingLines = lines.stream()
                .filter(line -> AppendStatementMatcher.matches(
                        line, expectation.label(), expectation.statement()))
                .count();
        boolean postStateMatched = matchingLines == 1
                && AppendStatementMatcher.matches(finalLine, expectation.label(), expectation.statement());
        boolean evidencePassed = postStateMatched
                && TaskExpectationMutationEvidenceVerifier.verifyAppendStatementMutationEvidence(
                        pathHint,
                        expectation.label(),
                        expectation.statement(),
                        successfulMutations,
                        facts,
                        problems);
        boolean matched = postStateMatched && evidencePassed;
        if (recordExpectationTrace) TaskExpectationTraceRecorder.recordAppendStatementExpectation(
                expectation,
                matched ? ExpectationVerificationStatus.PASSED : ExpectationVerificationStatus.FAILED,
                finalLine);
        if (matched) {
            facts.add(pathHint + ": appended statement matched requested final line.");
        } else if (matchingLines == 0) {
            problems.add(pathHint + ": requested appended statement was not found.");
        } else if (matchingLines > 1) {
            problems.add(pathHint + ": requested appended statement was not unique.");
        } else if (!AppendStatementMatcher.matches(
                finalLine, expectation.label(), expectation.statement())) {
            problems.add(pathHint + ": requested appended statement was not the final logical line.");
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
            boolean appendSectionRequired,
            boolean appendStatementRequired,
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
            return new Result(false, false, false, false, false, false, false, List.of(), List.of());
        }
    }

    private record AppendSectionObservation(
            boolean headingFound,
            boolean uniqueHeading,
            boolean sentenceFound,
            boolean exactOneSentenceSection,
            boolean finalSection,
            String observedBlock
    ) {
        boolean matched() {
            return headingFound && uniqueHeading && sentenceFound && exactOneSentenceSection && finalSection;
        }
    }
}
