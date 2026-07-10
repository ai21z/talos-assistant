package dev.talos.qualification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class QualificationRequirementEvaluator {
    private static final Pattern LIST_ITEM = Pattern.compile(
            "(?m)^\\s*(?:[-*+] |\\d+[.)] )\\S.*$");

    private QualificationRequirementEvaluator() {}

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

        private static Map<String, byte[]> deepCopy(Map<String, byte[]> source) {
            if (source == null || source.isEmpty()) return Map.of();
            LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
            source.forEach((path, bytes) -> copy.put(path, bytes == null ? new byte[0] : bytes.clone()));
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
                    requirement.id(), evaluate(requirement, evidence)));
        }
        return List.copyOf(results);
    }

    private static boolean evaluate(
            QualificationProtocol.AtomicRequirement requirement,
            Evidence evidence) {
        return switch (requirement.type()) {
            case "ANSWER_CONTAINS" -> containsIgnoreCase(evidence.answer(), requirement.expected());
            case "ANSWER_NOT_CONTAINS" -> !evidence.answer().contains(requirement.expected());
            case "ANSWER_EQUALS" -> evidence.answer().strip().equals(requirement.expected());
            case "ANSWER_MAX_SENTENCES" -> sentenceCount(evidence.answer()) <= positiveInt(requirement);
            case "ANSWER_BULLET_COUNT", "ANSWER_ITEM_COUNT" ->
                    LIST_ITEM.matcher(evidence.answer()).results().count() == positiveInt(requirement);
            case "FILE_ABSENT" -> !evidence.afterFiles().containsKey(requirement.target());
            case "FILE_CONTAINS" -> fileText(evidence.afterFiles(), requirement.target())
                    .map(text -> text.contains(requirement.expected())).orElse(false);
            case "FILE_EQUALS" -> fileText(evidence.afterFiles(), requirement.target())
                    .map(text -> text.equals(requirement.expected())).orElse(false);
            case "FILE_UNCHANGED" -> unchanged(evidence, requirement.target());
            case "TRACE_CONTAINS" -> evidence.trace().contains(requirement.expected());
            default -> throw new IllegalArgumentException(
                    "Unsupported atomic requirement type: " + requirement.type());
        };
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
