package dev.talos.runtime.policy;

import dev.talos.tools.PathArgumentCanonicalizer;
import dev.talos.tools.ToolCall;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes only narrowly scoped escaped aliases for protected current-turn
 * targets. This is deliberately not a fuzzy filename correction layer.
 */
public final class ProtectedPathAliasNormalizer {
    private ProtectedPathAliasNormalizer() {}

    private static final List<String> PATH_KEYS = List.of(
            "path", "file_path", "filepath", "file", "filename",
            "from", "to", "source", "source_path", "src",
            "destination", "destination_path", "dest", "target",
            "dir", "directory");

    public static PathArgumentCanonicalizer.ToolCallNormalization canonicalizeExpectedProtectedAliases(
            Path workspace,
            ToolCall call,
            Set<String> expectedTargets
    ) {
        return canonicalizeExpectedProtectedAliases(workspace, call, PATH_KEYS, expectedTargets);
    }

    public static PathArgumentCanonicalizer.ToolCallNormalization canonicalizeExpectedProtectedAliases(
            Path workspace,
            ToolCall call,
            List<String> pathKeys,
            Set<String> expectedTargets
    ) {
        if (workspace == null
                || call == null
                || call.parameters().isEmpty()
                || pathKeys == null
                || pathKeys.isEmpty()
                || expectedTargets == null
                || expectedTargets.isEmpty()) {
            return new PathArgumentCanonicalizer.ToolCallNormalization(call, List.of());
        }

        Set<String> protectedExpectedDotfiles = protectedExpectedDotfiles(workspace, expectedTargets);
        if (protectedExpectedDotfiles.isEmpty()) {
            return new PathArgumentCanonicalizer.ToolCallNormalization(call, List.of());
        }

        Map<String, String> updated = new LinkedHashMap<>(call.parameters());
        List<PathArgumentCanonicalizer.PathParameterChange> changes = new ArrayList<>();
        for (String key : pathKeys) {
            if (key == null || key.isBlank() || !updated.containsKey(key)) continue;
            String raw = updated.get(key);
            String alias = escapedSingleDotfileAlias(raw);
            if (alias.isBlank() || !protectedExpectedDotfiles.contains(alias)) continue;
            updated.put(key, alias);
            changes.add(new PathArgumentCanonicalizer.PathParameterChange(key, raw, alias));
        }

        if (changes.isEmpty()) {
            return new PathArgumentCanonicalizer.ToolCallNormalization(call, List.of());
        }
        return new PathArgumentCanonicalizer.ToolCallNormalization(new ToolCall(call.toolName(), updated), changes);
    }

    private static Set<String> protectedExpectedDotfiles(Path workspace, Set<String> expectedTargets) {
        Set<String> out = new LinkedHashSet<>();
        for (String target : expectedTargets) {
            String normalized = normalizeExpectedTarget(target);
            if (!isSingleDotfile(normalized)) continue;
            ResourceDecision decision = ProtectedPathPolicy.classify(workspace, normalized);
            if (decision.protectedPath()) {
                out.add(normalized);
            }
        }
        return out;
    }

    private static String escapedSingleDotfileAlias(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "";
        String raw = rawPath.strip();
        if (raw.length() < 3) return "";
        if (raw.charAt(0) != '\\') return "";
        if (raw.length() > 1 && (raw.charAt(1) == '\\' || raw.charAt(1) == '/')) return "";
        String candidate = raw.substring(1).replace('\\', '/');
        return isSingleDotfile(candidate) ? candidate : "";
    }

    private static boolean isSingleDotfile(String value) {
        if (value == null || value.isBlank()) return false;
        if (!value.startsWith(".")) return false;
        if (value.equals(".") || value.equals("..")) return false;
        if (value.contains("/") || value.contains("\\")) return false;
        return true;
    }

    private static String normalizeExpectedTarget(String raw) {
        if (raw == null) return "";
        String normalized = raw.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
