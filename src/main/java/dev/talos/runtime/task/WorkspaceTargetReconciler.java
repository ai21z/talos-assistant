package dev.talos.runtime.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Reconciles convention-derived static-web targets against current workspace
 * evidence without making the pure intent resolver filesystem-aware.
 */
public final class WorkspaceTargetReconciler {
    private WorkspaceTargetReconciler() {}

    public static TaskContract reconcile(TaskContract contract, Path workspace) {
        if (contract == null || workspace == null || contract.expectedTargets().isEmpty()) {
            return contract;
        }
        Set<String> expected = new LinkedHashSet<>(contract.expectedTargets());
        boolean changed = false;
        changed |= reconcilePair(expected, contract, workspace, "script.js", "scripts.js");
        changed |= reconcilePair(expected, contract, workspace, "style.css", "styles.css");
        if (!changed) {
            return contract;
        }
        return new TaskContract(
                contract.type(),
                contract.mutationRequested(),
                contract.mutationAllowed(),
                contract.verificationRequired(),
                expected,
                contract.sourceEvidenceTargets(),
                contract.forbiddenTargets(),
                contract.originalUserRequest(),
                contract.classificationReason());
    }

    private static boolean reconcilePair(
            Set<String> expected,
            TaskContract contract,
            Path workspace,
            String conventional,
            String observedAlternate
    ) {
        if (!containsTarget(expected, conventional)) {
            return false;
        }
        String request = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(Locale.ROOT);
        if (request.contains(conventional.toLowerCase(Locale.ROOT))) {
            return false;
        }

        boolean conventionalExists = rootFileExists(workspace, conventional);
        boolean alternateExists = rootFileExists(workspace, observedAlternate);
        if (conventionalExists && alternateExists) {
            removeTarget(expected, conventional);
            return true;
        }
        if (!conventionalExists && alternateExists && !isForbidden(contract, observedAlternate)) {
            removeTarget(expected, conventional);
            expected.add(observedAlternate);
            return true;
        }
        return false;
    }

    private static boolean rootFileExists(Path workspace, String filename) {
        try {
            return Files.isRegularFile(workspace.resolve(filename));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean containsTarget(Set<String> targets, String expected) {
        if (targets == null || targets.isEmpty()) return false;
        for (String target : targets) {
            if (targetEquals(target, expected)) return true;
        }
        return false;
    }

    private static void removeTarget(Set<String> targets, String expected) {
        if (targets == null || targets.isEmpty()) return;
        targets.removeIf(target -> targetEquals(target, expected));
    }

    private static boolean isForbidden(TaskContract contract, String target) {
        if (contract == null || contract.forbiddenTargets().isEmpty()) return false;
        return containsTarget(contract.forbiddenTargets(), target);
    }

    private static boolean targetEquals(String actual, String expected) {
        return normalize(actual).equals(normalize(expected));
    }

    private static String normalize(String target) {
        if (target == null) return "";
        String normalized = target.strip()
                .replace('\\', '/')
                .replaceAll("^[`'\"(\\[]+", "")
                .replaceAll("[`'\"),.;:!?\\]]+$", "");
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
