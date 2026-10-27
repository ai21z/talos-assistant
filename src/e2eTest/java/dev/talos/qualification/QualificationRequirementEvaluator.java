package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.util.TextReplacement;
import dev.talos.runtime.expectation.AppendSectionExpectation;
import dev.talos.runtime.expectation.AppendStatementExpectation;
import dev.talos.runtime.expectation.LiteralContentExpectation;
import dev.talos.runtime.expectation.ReplacementExpectation;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.verification.RequestObligationFinalStateVerifier;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class QualificationRequirementEvaluator {
    private static final Pattern LIST_ITEM = Pattern.compile(
            "(?m)^\\s*(?:[-*+] |\\d+[.)] )\\S.*$");

    private QualificationRequirementEvaluator() {}

    private enum Type {
        ANSWER_CONTAINS,
        ANSWER_NOT_CONTAINS,
        ANSWER_EQUALS,
        ANSWER_SCALAR_EQUALS,
        ANSWER_MAX_SENTENCES,
        ANSWER_BULLET_COUNT,
        ANSWER_ITEM_COUNT,
        FILE_ABSENT,
        FILE_CONTAINS,
        FILE_EQUALS,
        FILE_UNCHANGED,
        REQUEST_OBLIGATION,
        TRACE_CONTAINS
    }

    static boolean supports(String type) {
        try {
            Type.valueOf(type == null ? "" : type);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    record Evidence(
            String answer,
            String trace,
            Map<String, byte[]> beforeFiles,
            Map<String, byte[]> afterFiles) {
        Evidence {
            answer = answer == null ? "" : answer;
            trace = trace == null ? "" : trace;
            beforeFiles = deepCopy(beforeFiles);
            afterFiles = deepCopy(afterFiles);
        }

        @Override
        public Map<String, byte[]> beforeFiles() {
            return deepCopy(beforeFiles);
        }

        @Override
        public Map<String, byte[]> afterFiles() {
            return deepCopy(afterFiles);
        }

        private static Map<String, byte[]> deepCopy(Map<String, byte[]> source) {
            if (source == null || source.isEmpty()) return Map.of();
            LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
            source.forEach((path, bytes) -> {
                String canonical = RequestObligation.canonicalTarget(path);
                if (!canonical.equals(path)) {
                    throw new IllegalArgumentException(
                            "Qualification evidence target must already be canonical: " + path);
                }
                if (bytes == null) {
                    throw new IllegalArgumentException(
                            "Qualification evidence bytes are unavailable for: " + path);
                }
                if (copy.putIfAbsent(canonical, bytes.clone()) != null) {
                    throw new IllegalArgumentException(
                            "Qualification evidence target is duplicated: " + path);
                }
            });
            return Map.copyOf(copy);
        }
    }

    static List<QualificationRecords.RequirementResult> evaluate(
            QualificationProtocol.Scenario scenario,
            Evidence evidence) {
        if (scenario == null) throw new IllegalArgumentException("scenario is required");
        if (evidence == null) throw new IllegalArgumentException("evidence is required");
        List<QualificationRecords.RequirementResult> results = new ArrayList<>();
        for (QualificationProtocol.AtomicRequirement requirement : scenario.atomicRequirements()) {
            results.add(new QualificationRecords.RequirementResult(
                    requirement.id(), evaluate(scenario, requirement, evidence)));
        }
        return List.copyOf(results);
    }

    private static boolean evaluate(
            QualificationProtocol.Scenario scenario,
            QualificationProtocol.AtomicRequirement requirement,
            Evidence evidence) {
        Type type;
        try {
            type = Type.valueOf(requirement.type());
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalArgumentException(
                    "Unsupported atomic requirement type: " + requirement.type(), unsupported);
        }
        return switch (type) {
            case ANSWER_CONTAINS -> containsIgnoreCase(evidence.answer(), requirement.expected());
            case ANSWER_NOT_CONTAINS -> !evidence.answer().contains(requirement.expected());
            case ANSWER_EQUALS -> evidence.answer().strip().equals(requirement.expected());
            case ANSWER_SCALAR_EQUALS -> scalarEquals(evidence.answer(), requirement.expected());
            case ANSWER_MAX_SENTENCES -> sentenceCount(evidence.answer()) <= positiveInt(requirement);
            case ANSWER_BULLET_COUNT, ANSWER_ITEM_COUNT ->
                    LIST_ITEM.matcher(evidence.answer()).results().count() == positiveInt(requirement);
            case FILE_ABSENT -> !evidence.afterFiles().containsKey(requirement.target());
            case FILE_CONTAINS -> fileText(evidence.afterFiles(), requirement.target())
                    .map(text -> text.contains(requirement.expected())).orElse(false);
            case FILE_EQUALS -> fileText(evidence.afterFiles(), requirement.target())
                    .map(text -> text.equals(requirement.expected())).orElse(false);
            case FILE_UNCHANGED -> unchanged(evidence, requirement.target());
            case REQUEST_OBLIGATION -> requestObligationPassed(
                    scenario,
                    requirement,
                    evidence);
            case TRACE_CONTAINS -> evidence.trace().contains(requirement.expected());
        };
    }

    private static boolean requestObligationPassed(
            QualificationProtocol.Scenario scenario,
            QualificationProtocol.AtomicRequirement requirement,
            Evidence evidence
    ) {
        if (!"request".equals(requirement.target())
                || !"true".equals(requirement.expected())) {
            return false;
        }
        TaskContract contract = TaskContractResolver.fromUserRequest(scenario.prompt());
        RequestObligation obligation = RequestObligationCompiler.compile(contract);
        if (obligation.derivationStatus() != RequestObligation.DerivationStatus.COMPLETE) {
            return false;
        }
        Set<String> structuredTargets = structuredMutationTargets(
                evidence.trace(),
                obligation,
                evidence.afterFiles());
        try {
            return RequestObligationFinalStateVerifier.verify(
                    obligation,
                    new RequestObligationFinalStateVerifier.Evidence(
                            evidence.beforeFiles(),
                            evidence.afterFiles(),
                            structuredTargets))
                    .passed();
        } catch (IllegalArgumentException invalidEvidence) {
            return false;
        }
    }

    private static Set<String> structuredMutationTargets(
            String traceJson,
            RequestObligation obligation,
            Map<String, byte[]> afterFiles
    ) {
        if (traceJson == null || traceJson.isBlank() || obligation == null) return Set.of();
        try {
            ObjectNode root = QualificationJson.requiredObject(
                    QualificationJson.readTree(
                            traceJson.getBytes(StandardCharsets.UTF_8)),
                    "trace root");
            ArrayNode events = QualificationJson.requiredArray(
                    root.get("events"),
                    "trace events");
            JsonNode expectedObligation = QualificationJson.tree(
                    obligation.redactedProjection());
            JsonNode expectedExpectation = expectedExpectationData(obligation, afterFiles);
            JsonNode expectedVerification = QualificationJson.tree(Map.of(
                    "status", "PASSED",
                    "problemCount", 0,
                    "requiredClaimCount", 0,
                    "unsatisfiedRequiredClaimCount", 0,
                    "authoritativeProofKinds", List.of()));

            int obligationIndex = -1;
            int expectationIndex = -1;
            int verificationIndex = -1;
            for (int index = 0; index < events.size(); index++) {
                ObjectNode event = QualificationJson.requiredObject(
                        events.get(index),
                        "trace event");
                String type = QualificationJson.requiredText(
                        event.get("type"),
                        "trace event type");
                if ("REQUEST_OBLIGATION_COMPILED".equals(type)) {
                    if (obligationIndex >= 0
                            || !QualificationJson.requiredObject(
                                    event.get("data"),
                                    "request-obligation trace data")
                            .equals(expectedObligation)) {
                        return Set.of();
                    }
                    obligationIndex = index;
                } else if ("EXPECTATION_VERIFIED".equals(type)) {
                    if (expectationIndex >= 0
                            || !QualificationJson.requiredObject(
                                    event.get("data"),
                                    "expectation trace data")
                            .equals(expectedExpectation)) {
                        return Set.of();
                    }
                    expectationIndex = index;
                } else if ("VERIFICATION_COMPLETED".equals(type)) {
                    if (verificationIndex >= 0
                            || !QualificationJson.requiredObject(
                                    event.get("data"),
                                    "verification trace data")
                            .equals(expectedVerification)) {
                        return Set.of();
                    }
                    verificationIndex = index;
                }
            }
            return obligationIndex >= 0
                    && obligationIndex < expectationIndex
                    && expectationIndex < verificationIndex
                    ? Set.of(obligation.outputShape().target())
                    : Set.of();
        } catch (Exception invalidTrace) {
            return Set.of();
        }
    }

    private static JsonNode expectedExpectationData(
            RequestObligation obligation,
            Map<String, byte[]> afterFiles
    ) {
        RequestObligation.OutputShape output = obligation.outputShape();
        String sourcePattern = "request-obligation-v" + obligation.schemaVersion();
        return switch (obligation.actionKind()) {
            case APPEND_STATEMENT -> {
                AppendStatementExpectation expectation = new AppendStatementExpectation(
                        output.target(),
                        output.label(),
                        output.statement(),
                        sourcePattern);
                String finalLine = RequestObligationFinalStateVerifier.finalLogicalLine(
                        afterFiles.get(output.target()));
                if (finalLine == null) {
                    throw new IllegalArgumentException(
                            "Request-obligation target is not strict UTF-8 evidence");
                }
                yield expectedExpectationData(
                        expectation.kind(),
                        output.target(),
                        sourcePattern,
                        expectation.expectedHash(),
                        expectation.expectedBytes(),
                        expectation.expectedChars(),
                        1,
                        finalLine,
                        LiteralContentExpectation.byteCount(finalLine),
                        LiteralContentExpectation.charCount(finalLine),
                        finalLine.isBlank() ? 0 : 1);
            }
            case APPEND_SECTION -> {
                AppendSectionExpectation expectation = new AppendSectionExpectation(
                        output.target(),
                        output.label(),
                        output.statement(),
                        sourcePattern);
                String observed = expectation.expectedContent();
                yield expectedExpectationData(
                        expectation.kind(),
                        output.target(),
                        sourcePattern,
                        expectation.expectedHash(),
                        expectation.expectedBytes(),
                        expectation.expectedChars(),
                        2,
                        observed,
                        LiteralContentExpectation.byteCount(observed),
                        LiteralContentExpectation.charCount(observed),
                        2);
            }
            case REPLACE_TEXT_PRESERVE_REST -> {
                ReplacementExpectation expectation = new ReplacementExpectation(
                        output.target(),
                        output.label(),
                        output.statement(),
                        sourcePattern,
                        true);
                String content = fileText(afterFiles, output.target())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Request-obligation target is not UTF-8 evidence"));
                boolean oldPresent = TextReplacement.oldTextRemainsOutsideReplacement(
                        content,
                        expectation.oldText(),
                        expectation.newText());
                boolean newPresent = TextReplacement.containsNormalized(
                        content,
                        expectation.newText());
                String observed = "oldPresent:" + oldPresent
                        + ";newPresent:" + newPresent;
                yield expectedExpectationData(
                        expectation.kind(),
                        output.target(),
                        sourcePattern,
                        "old:" + expectation.oldHash()
                                + ";new:" + expectation.newHash(),
                        expectation.newBytes(),
                        expectation.newChars(),
                        0,
                        observed,
                        0,
                        0,
                        0);
            }
            case NONE -> throw new IllegalArgumentException(
                    "Complete request obligation cannot name no action");
        };
    }

    private static JsonNode expectedExpectationData(
            String kind,
            String target,
            String sourcePattern,
            String expectedHash,
            int expectedBytes,
            int expectedChars,
            int expectedLines,
            String observed,
            int observedBytes,
            int observedChars,
            int observedLines
    ) {
        return QualificationJson.tree(Map.ofEntries(
                Map.entry("kind", kind),
                Map.entry("status", "PASSED"),
                Map.entry("pathHint", RequestObligation.diagnosticPath(target)),
                Map.entry("sourcePattern", sourcePattern),
                Map.entry("expectedHash", expectedHash),
                Map.entry("expectedBytes", expectedBytes),
                Map.entry("expectedChars", expectedChars),
                Map.entry("expectedLines", expectedLines),
                Map.entry("observedHash", LiteralContentExpectation.hash(observed)),
                Map.entry("observedBytes", observedBytes),
                Map.entry("observedChars", observedChars),
                Map.entry("observedLines", observedLines)));
    }

    private static java.util.Optional<String> fileText(Map<String, byte[]> files, String target) {
        byte[] bytes = files.get(target);
        return bytes == null ? java.util.Optional.empty()
                : java.util.Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    private static boolean unchanged(Evidence evidence, String target) {
        byte[] before = evidence.beforeFiles().get(target);
        byte[] after = evidence.afterFiles().get(target);
        return before != null && after != null && Arrays.equals(before, after);
    }

    private static boolean containsIgnoreCase(String value, String expected) {
        return value.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private static boolean scalarEquals(String value, String expected) {
        return stripOneTerminalSentenceMark(value).equals(stripOneTerminalSentenceMark(expected));
    }

    private static String stripOneTerminalSentenceMark(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) return text;
        char last = text.charAt(text.length() - 1);
        if (last == '.' || last == '!' || last == '?') {
            return text.substring(0, text.length() - 1).stripTrailing();
        }
        return text;
    }

    private static int positiveInt(QualificationProtocol.AtomicRequirement requirement) {
        try {
            int value = Integer.parseInt(requirement.expected());
            if (value < 0) throw new NumberFormatException("negative");
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Requirement " + requirement.id() + " expects a non-negative integer", e);
        }
    }

    private static int sentenceCount(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) return 0;
        String[] parts = text.split("(?<=[.!?])(?:[\\\"')\\]]*)\\s+");
        int count = 0;
        for (String part : parts) if (!part.isBlank()) count++;
        return count;
    }
}
