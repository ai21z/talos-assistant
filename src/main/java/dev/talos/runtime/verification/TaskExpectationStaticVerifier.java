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
import dev.talos.tools.WorkspacePathIdentity;

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
                coversAllSuccessfulMutations(root, verifiedTargets, successfulMutations),
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
            recordCompleteObligationTrace(
                    obligation,
                    "",
                    false,
                    recordExpectationTrace);
            return completeObligationResult(
                    root,
                    obligation,
                    false,
                    null,
                    Set.of(output.target()),
                    successfulMutations,
                    facts,
                    problems);
        }

        boolean mutationEvidencePassed = false;
        String beforeContent = "";
        switch (obligation.actionKind()) {
            case APPEND_STATEMENT -> {
                TaskExpectationMutationEvidenceVerifier.AppendStatementEvidence evidence =
                        TaskExpectationMutationEvidenceVerifier.collectAppendStatementMutationEvidence(
                                root,
                                target.pathHint(),
                                output.label(),
                                output.statement(),
                                target.content(),
                                successfulMutations,
                                facts,
                                problems);
                mutationEvidencePassed = evidence.passed();
                beforeContent = evidence.beforeContent();
            }
            case APPEND_SECTION -> {
                TaskExpectationMutationEvidenceVerifier.FinalStateEvidence evidence =
                        TaskExpectationMutationEvidenceVerifier.collectAppendSectionFinalStateEvidence(
                                root,
                                target.pathHint(),
                                output.label(),
                                output.statement(),
                                target.content(),
                                successfulMutations,
                                facts,
                                problems);
                mutationEvidencePassed = evidence.passed();
                beforeContent = evidence.beforeContent();
            }
            case REPLACE_TEXT_PRESERVE_REST -> {
                ReplacementExpectation replacement = new ReplacementExpectation(
                        output.target(),
                        output.label(),
                        output.statement(),
                        "request-obligation-v" + obligation.schemaVersion(),
                        true);
                TaskExpectationMutationEvidenceVerifier.FinalStateEvidence evidence =
                        TaskExpectationMutationEvidenceVerifier.collectReplacementFinalStateEvidence(
                                root,
                                replacement,
                                target.pathHint(),
                                target.content(),
                                successfulMutations,
                                facts,
                                problems);
                mutationEvidencePassed = evidence.passed();
                beforeContent = evidence.beforeContent();
            }
            case NONE -> {
                mutationEvidencePassed = false;
                beforeContent = "";
                problems.add(target.pathHint()
                        + ": complete request obligation named no supported action.");
            }
        }

        byte[] before = beforeContent.getBytes(StandardCharsets.UTF_8);
        byte[] after = target.content().getBytes(StandardCharsets.UTF_8);
        RequestObligationFinalStateVerifier.Result finalState =
                mutationEvidencePassed
                        ? RequestObligationFinalStateVerifier.verify(
                                obligation,
                                new RequestObligationFinalStateVerifier.Evidence(
                                        java.util.Map.of(output.target(), before),
                                        java.util.Map.of(output.target(), after),
                                        Set.of(output.target())))
                        : new RequestObligationFinalStateVerifier.Result(
                                false,
                                List.of("STRUCTURED_MUTATION_EVIDENCE_UNAVAILABLE"));
        boolean passed = mutationEvidencePassed && finalState.passed();
        recordCompleteObligationTrace(
                obligation,
                target.content(),
                passed,
                recordExpectationTrace);
        if (passed) {
            facts.add(switch (obligation.actionKind()) {
                case APPEND_STATEMENT -> target.pathHint()
                        + ": appended statement matched requested final line.";
                case APPEND_SECTION -> target.pathHint()
                        + ": requested appended section matched heading and sentence.";
                case REPLACE_TEXT_PRESERVE_REST -> target.pathHint()
                        + ": replacement text observed and old text absent.";
                case NONE -> target.pathHint()
                        + ": unsupported request obligation unexpectedly passed.";
            });
        } else {
            addCompleteObligationProblems(
                    obligation,
                    target.pathHint(),
                    target.content(),
                    finalState.failedPostconditions(),
                    problems);
        }
        return completeObligationResult(
                root,
                obligation,
                passed,
                finalState,
                Set.of(output.target()),
                successfulMutations,
                facts,
                problems);
    }

    private static Result completeObligationResult(
            Path root,
            RequestObligation obligation,
            boolean passed,
            RequestObligationFinalStateVerifier.Result finalState,
            Set<String> verifiedTargets,
            List<ToolCallLoop.ToolOutcome> successfulMutations,
            List<String> facts,
            List<String> problems
    ) {
        boolean coversAllMutations = coversAllSuccessfulMutations(
                root,
                verifiedTargets,
                successfulMutations);
        if (passed && !coversAllMutations) {
            problems.add(
                    "Request-obligation evidence did not cover every successful mutation.");
        }
        RequestObligationVerificationReceipt receipt =
                RequestObligationVerificationReceipt.verified(
                        obligation,
                        finalState,
                        verifiedTargets,
                        successfulMutations,
                        root);
        RequestObligation.ActionKind actionKind = obligation.actionKind();
        return new Result(
                true,
                actionKind == RequestObligation.ActionKind.REPLACE_TEXT_PRESERVE_REST,
                false,
                actionKind == RequestObligation.ActionKind.APPEND_SECTION,
                actionKind == RequestObligation.ActionKind.APPEND_STATEMENT,
                false,
                passed && coversAllMutations,
                facts,
                problems,
                receipt);
    }

    private static void recordCompleteObligationTrace(
            RequestObligation obligation,
            String observed,
            boolean passed,
            boolean recordExpectationTrace
    ) {
        if (!recordExpectationTrace) return;
        RequestObligation.OutputShape output = obligation.outputShape();
        ExpectationVerificationStatus status = passed
                ? ExpectationVerificationStatus.PASSED
                : ExpectationVerificationStatus.FAILED;
        switch (obligation.actionKind()) {
            case APPEND_STATEMENT -> {
                String finalLine = RequestObligationFinalStateVerifier.finalLogicalLine(
                        observed.getBytes(StandardCharsets.UTF_8));
                TaskExpectationTraceRecorder.recordAppendStatementExpectation(
                        new AppendStatementExpectation(
                                output.target(),
                                output.label(),
                                output.statement(),
                                "request-obligation-v" + obligation.schemaVersion()),
                        status,
                        finalLine == null ? "" : finalLine);
            }
            case APPEND_SECTION -> {
                AppendSectionObservation observation = observeAppendedSection(
                        observed,
                        output.label(),
                        output.statement());
                TaskExpectationTraceRecorder.recordAppendSectionExpectation(
                        new AppendSectionExpectation(
                                output.target(),
                                output.label(),
                                output.statement(),
                                "request-obligation-v" + obligation.schemaVersion()),
                        status,
                        observation.observedBlock());
            }
            case REPLACE_TEXT_PRESERVE_REST ->
                    TaskExpectationTraceRecorder.recordReplacementExpectation(
                            new ReplacementExpectation(
                                    output.target(),
                                    output.label(),
                                    output.statement(),
                                    "request-obligation-v" + obligation.schemaVersion(),
                                    true),
                            status,
                            ReplacementTextPresence.oldTextRemainsOutsideReplacement(
                                    observed,
                                    output.label(),
                                    output.statement()),
                            ReplacementTextPresence.replacementTextObserved(
                                    observed,
                                    output.statement()));
            case NONE -> {
                // A COMPLETE obligation cannot carry NONE; constructor validation owns this.
            }
        }
    }

    private static void addCompleteObligationProblems(
            RequestObligation obligation,
            String pathHint,
            String observed,
            List<String> failedPostconditions,
            List<String> problems
    ) {
        RequestObligation.OutputShape output = obligation.outputShape();
        switch (obligation.actionKind()) {
            case APPEND_SECTION -> {
                AppendSectionObservation observation = observeAppendedSection(
                        observed,
                        output.label(),
                        output.statement());
                if (!observation.headingFound()) {
                    problems.add(pathHint
                            + ": requested Markdown section heading was not found.");
                } else if (!observation.uniqueHeading()) {
                    problems.add(pathHint
                            + ": requested Markdown section heading was not unique.");
                } else if (!observation.sentenceFound()) {
                    problems.add(pathHint
                            + ": requested section sentence was not found under its heading.");
                } else if (!observation.exactOneSentenceSection()) {
                    problems.add(pathHint
                            + ": requested section did not contain exactly the requested sentence.");
                } else if (!observation.finalSection()) {
                    problems.add(pathHint
                            + ": requested section was not appended as the final document section.");
                }
            }
            case REPLACE_TEXT_PRESERVE_REST -> {
                boolean oldPresent = ReplacementTextPresence.oldTextRemainsOutsideReplacement(
                        observed,
                        output.label(),
                        output.statement());
                boolean newPresent = ReplacementTextPresence.replacementTextObserved(
                        observed,
                        output.statement());
                if (!newPresent) {
                    problems.add(pathHint
                            + ": replacement new text was not observed after apply.");
                }
                if (oldPresent) {
                    problems.add(pathHint
                            + ": replacement old text remained after apply.");
                }
            }
            case APPEND_STATEMENT, NONE -> {
                // The typed postcondition details below are the precise diagnostic.
            }
        }
        failedPostconditions.forEach(failure -> {
            String detail = pathHint
                    + ": request-obligation postcondition failed: " + failure + ".";
            if (!problems.contains(detail)) problems.add(detail);
        });
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
            Path root,
            Set<String> verifiedTargets,
            List<ToolCallLoop.ToolOutcome> successfulMutations
    ) {
        if (verifiedTargets == null || verifiedTargets.isEmpty()
                || successfulMutations == null || successfulMutations.isEmpty()) {
            return false;
        }
        boolean mutatedAny = false;
        for (ToolCallLoop.ToolOutcome outcome : successfulMutations) {
            String target = normalizePath(outcome.pathHint());
            if (target.isBlank()) continue;
            mutatedAny = true;
            if (verifiedTargets.contains(target)) continue;
            boolean sameFile = verifiedTargets.stream()
                    .anyMatch(verified -> WorkspacePathIdentity.matches(root, verified, outcome.pathHint()));
            if (!sameFile) return false;
        }
        return mutatedAny;
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
                    root,
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
                        root,
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
                root,
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
                        root,
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
            List<String> problems,
            RequestObligationVerificationReceipt requestObligationReceipt
    ) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
            requestObligationReceipt = requestObligationReceipt == null
                    ? RequestObligationVerificationReceipt.none()
                    : requestObligationReceipt;
        }

        Result(
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
            this(
                    verifiedAny,
                    replacementRequired,
                    appendLineRequired,
                    appendSectionRequired,
                    appendStatementRequired,
                    bulletCountRequired,
                    coversAllSuccessfulMutations,
                    facts,
                    problems,
                    RequestObligationVerificationReceipt.none());
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
