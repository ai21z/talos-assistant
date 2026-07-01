package dev.talos.runtime.verification;

import dev.talos.runtime.capability.ArtifactOperation;
import dev.talos.runtime.capability.CapabilityProfile;
import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Verifies expected, forbidden, and only-target mutation scope. */
final class TargetScopeStaticVerifier {

    private TargetScopeStaticVerifier() {}

    static Result verify(
            TaskContract contract,
            Path root,
            CapabilityProfile profile,
            Set<String> mutatedPaths,
            Set<String> expectedTargetExemptions,
            Set<String> expectedTargetAliases
    ) {
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (contract == null
                || (contract.expectedTargets().isEmpty() && contract.forbiddenTargets().isEmpty())) {
            return new Result(facts, problems);
        }
        Set<String> normalizedMutations = new LinkedHashSet<>();
        for (String path : mutatedPaths == null ? Set.<String>of() : mutatedPaths) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) normalizedMutations.add(normalized);
        }
        Set<String> normalizedExemptions = new LinkedHashSet<>();
        for (String path : expectedTargetExemptions == null ? Set.<String>of() : expectedTargetExemptions) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) normalizedExemptions.add(normalized);
        }
        Set<String> normalizedAliases = new LinkedHashSet<>();
        for (String path : expectedTargetAliases == null ? Set.<String>of() : expectedTargetAliases) {
            String normalized = normalizePath(path);
            if (!normalized.isBlank()) normalizedAliases.add(normalized);
        }
        boolean caseInsensitive = expectedTargetMatchingIsCaseInsensitive();
        for (String target : contract.forbiddenTargets()) {
            String forbidden = normalizePath(target);
            if (forbidden.isBlank()) continue;
            boolean matched = normalizedMutations.stream()
                    .anyMatch(mutated -> expectedTargetMatches(forbidden, mutated, caseInsensitive));
            if (matched) {
                problems.add(forbidden + ": forbidden mutation target was changed.");
            }
        }
        String onlyTarget = singleTargetOnlyMutationTarget(contract);
        Set<String> satisfiedContextTargets = new LinkedHashSet<>();
        for (String target : contract.expectedTargets()) {
            String expected = normalizePath(target);
            if (expected.isBlank()) continue;
            boolean exempt = normalizedExemptions.stream()
                    .anyMatch(exemption -> expectedTargetMatches(expected, exemption, caseInsensitive));
            if (exempt) continue;
            boolean matched = normalizedMutations.stream()
                    .anyMatch(mutated -> expectedTargetMatches(expected, mutated, caseInsensitive))
                    || normalizedAliases.stream()
                    .anyMatch(alias -> expectedTargetMatches(expected, alias, caseInsensitive));
            if (!matched && staticWebRepairContextTargetSatisfied(profile, root, expected, normalizedMutations)) {
                satisfiedContextTargets.add(expected);
                continue;
            }
            if (!matched) {
                List<String> similarWrongTargets = similarWrongMutationTargets(
                        expected,
                        normalizedMutations,
                        caseInsensitive);
                String problem = expected + ": expected target was not successfully mutated.";
                if (!similarWrongTargets.isEmpty()) {
                    problem += " Changed similar target(s) "
                            + renderObserved(new LinkedHashSet<>(similarWrongTargets))
                            + " does not satisfy `" + expected + "`.";
                }
                problems.add(problem);
            }
        }
        if (!onlyTarget.isBlank()) {
            for (String mutated : normalizedMutations) {
                boolean matchesOnlyTarget = expectedTargetMatches(onlyTarget, mutated, caseInsensitive)
                        || normalizedAliases.stream()
                        .anyMatch(alias -> expectedTargetMatches(alias, mutated, caseInsensitive));
                if (!matchesOnlyTarget) {
                    problems.add(mutated + ": non-requested mutation target was changed under an only-target request.");
                }
            }
        }
        if (!contract.expectedTargets().isEmpty()
                && problems.isEmpty()) {
            if (satisfiedContextTargets.isEmpty()) {
                facts.add("Expected mutation target(s) were updated: "
                        + String.join(", ", contract.expectedTargets()) + ".");
            } else {
                facts.add("Expected mutation target(s) and static web context target(s) were satisfied: "
                        + String.join(", ", contract.expectedTargets()) + ".");
            }
        }
        return new Result(facts, problems);
    }

    record Result(
            List<String> facts,
            List<String> problems
    ) {
        Result {
            facts = facts == null ? List.of() : List.copyOf(facts);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    private static String singleTargetOnlyMutationTarget(TaskContract contract) {
        if (contract == null || contract.expectedTargets().size() != 1) return "";
        String target = firstPath(contract.expectedTargets());
        if (target.isBlank()) return "";
        return requestHasOnlyTargetLimiter(contract.originalUserRequest(), target) ? target : "";
    }

    private static String firstPath(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) return "";
        for (String path : paths) {
            if (path != null && !path.isBlank()) return normalizePath(path);
        }
        return "";
    }

    private static boolean requestHasOnlyTargetLimiter(String request, String target) {
        if (request == null || request.isBlank() || target == null || target.isBlank()) return false;
        String quoted = Pattern.quote(target);
        String targetBoundary = "`?" + quoted + "`?(?=$|\\s|[`'\"),;:!?\\]]|\\.(?:$|\\s))";
        String mutationVerb = "(?:change|edit|modify|update|fix|replace|write|create|append)";
        List<Pattern> patterns = List.of(
                Pattern.compile("(?is)\\bonly\\s+" + mutationVerb + "\\s+" + targetBoundary),
                Pattern.compile("(?is)\\b" + mutationVerb + "\\s+only\\s+" + targetBoundary),
                Pattern.compile("(?is)\\b" + mutationVerb + "\\b.{0,80}?" + targetBoundary + "\\s+only\\b"),
                Pattern.compile("(?is)\\bdo\\s+not\\s+(?:edit|change|modify|touch|write|create|save|mutate)\\s+"
                        + "(?:any\\s+)?other\\s+files?\\b"),
                Pattern.compile("(?is)\\b(?:don't|dont)\\s+"
                        + "(?:edit|change|modify|touch|write|create|save|mutate)\\s+"
                        + "(?:any\\s+)?other\\s+files?\\b"),
                Pattern.compile("(?is)\\bdo\\s+not\\s+modify\\s+anything\\s+else\\b"));
        for (Pattern pattern : patterns) {
            if (pattern.matcher(request).find()) return true;
        }
        return false;
    }

    private static boolean staticWebRepairContextTargetSatisfied(
            CapabilityProfile profile,
            Path root,
            String expected,
            Set<String> normalizedMutations
    ) {
        if (profile == null || !profile.staticWeb()) return false;
        if (profile.operation() != ArtifactOperation.REPAIR
                && profile.operation() != ArtifactOperation.EDIT) return false;
        if (StaticWebCapabilityProfile.requiresSeparateAssetMutations(profile)) return false;
        if (!StaticWebCapabilityProfile.isSmallWebFile(expected)) return false;
        if (normalizedMutations == null || normalizedMutations.stream()
                .noneMatch(StaticWebCapabilityProfile::isSmallWebFile)) return false;
        if (root == null) return false;
        Path target;
        try {
            target = root.resolve(expected).normalize();
        } catch (InvalidPathException e) {
            return false;
        }
        return target.startsWith(root) && Files.isRegularFile(target);
    }

    static boolean expectedTargetMatches(String expectedTarget, String mutatedPath, boolean caseInsensitive) {
        String expected = normalizePath(expectedTarget);
        String mutated = normalizePath(mutatedPath);
        if (expected.isBlank() || mutated.isBlank()) return false;
        if (caseInsensitive) {
            return expected.equalsIgnoreCase(mutated);
        }
        return expected.equals(mutated);
    }

    private static List<String> similarWrongMutationTargets(
            String expectedTarget,
            Set<String> mutatedPaths,
            boolean caseInsensitive
    ) {
        if (expectedTarget == null || mutatedPaths == null || mutatedPaths.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String mutated : mutatedPaths) {
            if (expectedTargetMatches(expectedTarget, mutated, caseInsensitive)) continue;
            if (looksLikeSingularPluralSibling(expectedTarget, mutated)) {
                out.add(mutated);
            }
        }
        return out.stream().sorted().toList();
    }

    private static boolean looksLikeSingularPluralSibling(String leftPath, String rightPath) {
        String left = normalizePath(leftPath).toLowerCase(Locale.ROOT);
        String right = normalizePath(rightPath).toLowerCase(Locale.ROOT);
        if (left.isBlank() || right.isBlank()) return false;

        int leftSlash = left.lastIndexOf('/');
        int rightSlash = right.lastIndexOf('/');
        String leftDir = leftSlash >= 0 ? left.substring(0, leftSlash + 1) : "";
        String rightDir = rightSlash >= 0 ? right.substring(0, rightSlash + 1) : "";
        if (!leftDir.equals(rightDir)) return false;

        String leftName = leftSlash >= 0 ? left.substring(leftSlash + 1) : left;
        String rightName = rightSlash >= 0 ? right.substring(rightSlash + 1) : right;
        int leftDot = leftName.lastIndexOf('.');
        int rightDot = rightName.lastIndexOf('.');
        if (leftDot <= 0 || rightDot <= 0) return false;
        String leftExt = leftName.substring(leftDot);
        String rightExt = rightName.substring(rightDot);
        if (!leftExt.equals(rightExt)) return false;

        String leftStem = leftName.substring(0, leftDot);
        String rightStem = rightName.substring(0, rightDot);
        return leftStem.equals(rightStem + "s") || rightStem.equals(leftStem + "s");
    }

    private static boolean expectedTargetMatchingIsCaseInsensitive() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String normalized = path.replace('\\', '/');
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("./") && normalized.length() > 2) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String renderObserved(Set<String> values) {
        if (values == null || values.isEmpty()) return "none";
        return values.stream().sorted().map(v -> "`" + v + "`").reduce((a, b) -> a + ", " + b).orElse("none");
    }
}
