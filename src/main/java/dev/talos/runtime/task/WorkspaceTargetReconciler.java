package dev.talos.runtime.task;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reconciles convention-derived static-web targets against current workspace
 * evidence without making the pure intent resolver filesystem-aware.
 */
public final class WorkspaceTargetReconciler {
    private static final Pattern HTML_LINK_HREF = Pattern.compile(
            "<link\\b[^>]*\\bhref\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_SCRIPT_SRC = Pattern.compile(
            "<script\\b[^>]*\\bsrc\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE);

    private WorkspaceTargetReconciler() {}

    public static TaskContract reconcile(TaskContract contract, Path workspace) {
        if (contract == null || workspace == null) {
            return contract;
        }
        if (contract.expectedTargets().isEmpty()) {
            return reconcileWorkspaceStaticWebSurface(contract, workspace);
        }
        Set<String> expected = new LinkedHashSet<>(contract.expectedTargets());
        boolean changed = false;
        changed |= reconcileLinkedPair(expected, contract, workspace, "script.js", "scripts.js");
        changed |= reconcileLinkedPair(expected, contract, workspace, "style.css", "styles.css");
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
                contract.classificationReason(),
                contract.staticWebRequirements());
    }

    private static TaskContract reconcileWorkspaceStaticWebSurface(TaskContract contract, Path workspace) {
        if (!shouldReconstructStaticWebTargets(contract, workspace)) {
            return contract;
        }
        Set<String> expected = workspaceStaticWebTargets(workspace);
        if (expected.isEmpty()) {
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
                appendClassificationReason(contract.classificationReason(),
                        "workspace-static-web-surface-targets"),
                contract.staticWebRequirements());
    }

    private static boolean shouldReconstructStaticWebTargets(TaskContract contract, Path workspace) {
        if (contract == null || workspace == null) return false;
        if (!contract.mutationAllowed() || !contract.verificationRequired()) return false;
        if (!contract.expectedTargets().isEmpty()) return false;
        if (!looksLikeStaticWebWorkspaceContinuation(contract.originalUserRequest())) return false;
        return Files.isRegularFile(workspace.resolve("index.html"));
    }

    private static boolean looksLikeStaticWebWorkspaceContinuation(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        boolean namesWebSurface = lower.contains("website")
                || lower.contains("web site")
                || lower.contains("webpage")
                || lower.contains("web page")
                || containsWholeWord(lower, "site")
                || lower.contains("frontend")
                || lower.contains("static web")
                || lower.contains("tailwind");
        if (!namesWebSurface) return false;
        return lower.contains("make")
                || lower.contains("polish")
                || lower.contains("polished")
                || lower.contains("complete")
                || lower.contains("better")
                || lower.contains("modern")
                || lower.contains("repair")
                || lower.contains("fix")
                || lower.contains("rewrite")
                || lower.contains("redesign")
                || lower.contains("verified")
                || lower.contains("unverified");
    }

    private static boolean containsWholeWord(String lower, String token) {
        if (lower == null || lower.isBlank() || token == null || token.isBlank()) return false;
        int start = 0;
        while (start < lower.length()) {
            int index = lower.indexOf(token, start);
            if (index < 0) return false;
            int before = index - 1;
            int after = index + token.length();
            boolean leftBoundary = before < 0 || !Character.isLetterOrDigit(lower.charAt(before));
            boolean rightBoundary = after >= lower.length() || !Character.isLetterOrDigit(lower.charAt(after));
            if (leftBoundary && rightBoundary) return true;
            start = after;
        }
        return false;
    }

    private static Set<String> workspaceStaticWebTargets(Path workspace) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(workspace.resolve("index.html"))) {
            return Set.of();
        }
        out.add("index.html");
        Set<String> linked = linkedLocalAssets(workspace);
        addLinkedAssetsByExtension(out, linked, ".css");
        addLinkedAssetsByExtension(out, linked, ".js");
        addExistingPairIfMissing(out, workspace, ".css", "style.css", "styles.css");
        addExistingPairIfMissing(out, workspace, ".js", "script.js", "scripts.js");
        return Set.copyOf(out);
    }

    private static void addLinkedAssetsByExtension(Set<String> out, Set<String> linked, String extension) {
        if (linked == null || linked.isEmpty()) return;
        List<String> sorted = new ArrayList<>(linked);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        for (String target : sorted) {
            if (target != null && target.toLowerCase(Locale.ROOT).endsWith(extension)) {
                out.add(target);
            }
        }
    }

    private static void addExistingPairIfMissing(
            Set<String> out,
            Path workspace,
            String extension,
            String conventional,
            String alternate
    ) {
        boolean alreadyHasExtension = out.stream()
                .anyMatch(target -> target.toLowerCase(Locale.ROOT).endsWith(extension));
        if (alreadyHasExtension) return;
        boolean conventionalExists = rootFileExists(workspace, conventional);
        boolean alternateExists = rootFileExists(workspace, alternate);
        if (conventionalExists && !alternateExists) {
            out.add(conventional);
        } else if (alternateExists && !conventionalExists) {
            out.add(alternate);
        }
    }

    private static String appendClassificationReason(String existing, String reason) {
        if (reason == null || reason.isBlank()) return existing == null ? "" : existing;
        if (existing == null || existing.isBlank()) return reason;
        if (existing.contains(reason)) return existing;
        return existing + "+" + reason;
    }

    private static boolean reconcileLinkedPair(
            Set<String> expected,
            TaskContract contract,
            Path workspace,
            String conventional,
            String observedAlternate
    ) {
        if (!containsTarget(expected, conventional) && !containsTarget(expected, observedAlternate)) {
            return false;
        }
        String linked = linkedPairTarget(workspace, conventional, observedAlternate);
        if (linked == null || linked.isBlank()) return false;
        String requestedOther = targetEquals(linked, conventional) ? observedAlternate : conventional;
        if (isForbidden(contract, linked)
                || explicitNewLinkedAssetRequest(contract, linked)
                || explicitNewLinkedAssetRequest(contract, requestedOther)
                || explicitStaticWebSurfaceReplacementRequest(contract, requestedOther)) {
            return false;
        }
        boolean hasOnlyLinked = containsTarget(expected, linked)
                && expected.stream()
                .filter(target -> targetEquals(target, conventional) || targetEquals(target, observedAlternate))
                .count() == 1;
        if (hasOnlyLinked) return false;
        removeTarget(expected, conventional);
        removeTarget(expected, observedAlternate);
        expected.add(linked);
        return true;
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
        String linked = linkedPairTarget(workspace, conventional, observedAlternate);
        if (targetEquals(linked, conventional)) {
            return false;
        }
        if (targetEquals(linked, observedAlternate) && !isForbidden(contract, observedAlternate)) {
            removeTarget(expected, conventional);
            expected.add(observedAlternate);
            return true;
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

    private static String linkedPairTarget(Path workspace, String conventional, String observedAlternate) {
        Set<String> linked = linkedLocalAssets(workspace);
        boolean conventionalLinked = containsTarget(linked, conventional);
        boolean alternateLinked = containsTarget(linked, observedAlternate);
        if (conventionalLinked && !alternateLinked) return conventional;
        if (alternateLinked && !conventionalLinked) return observedAlternate;
        return null;
    }

    private static Set<String> linkedLocalAssets(Path workspace) {
        try {
            Path index = workspace.resolve("index.html").normalize();
            if (!Files.isRegularFile(index)) return Set.of();
            String html = Files.readString(index);
            LinkedHashSet<String> out = new LinkedHashSet<>();
            collectLocalAssets(out, HTML_LINK_HREF.matcher(html));
            collectLocalAssets(out, HTML_SCRIPT_SRC.matcher(html));
            return Set.copyOf(out);
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static void collectLocalAssets(Set<String> out, Matcher matcher) {
        while (matcher.find()) {
            String value = matcher.group(2);
            String normalized = normalizeLinkedAsset(value);
            if (!normalized.isBlank()) {
                out.add(normalized);
            }
        }
    }

    private static String normalizeLinkedAsset(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.strip().replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("//")
                || lower.startsWith("data:")
                || lower.startsWith("#")) {
            return "";
        }
        int query = normalized.indexOf('?');
        if (query >= 0) normalized = normalized.substring(0, query);
        int hash = normalized.indexOf('#');
        if (hash >= 0) normalized = normalized.substring(0, hash);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.strip();
    }

    private static boolean explicitNewLinkedAssetRequest(TaskContract contract, String target) {
        if (contract == null || target == null || target.isBlank()) return false;
        String request = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(Locale.ROOT);
        String normalizedTarget = target.toLowerCase(Locale.ROOT);
        return request.contains(normalizedTarget)
                && (request.contains("create") || request.contains("new "))
                && (request.contains("link") || request.contains("href") || request.contains("src"));
    }

    private static boolean explicitStaticWebSurfaceReplacementRequest(TaskContract contract, String target) {
        if (contract == null || target == null || target.isBlank()) return false;
        String request = contract.originalUserRequest() == null
                ? ""
                : contract.originalUserRequest().toLowerCase(Locale.ROOT);
        String normalizedTarget = target.toLowerCase(Locale.ROOT);
        if (!request.contains(normalizedTarget) || !request.contains("index.html")) {
            return false;
        }
        return request.contains("create")
                || request.contains("overwrite")
                || request.contains("rewrite")
                || request.contains("replace")
                || request.contains("build")
                || request.contains("make ");
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
