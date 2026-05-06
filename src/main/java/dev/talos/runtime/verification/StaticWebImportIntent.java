package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Recognizes narrow read-only questions about which script an HTML file imports. */
public final class StaticWebImportIntent {
    private StaticWebImportIntent() {}

    public static boolean matches(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        boolean asksQuestion = lower.contains("?")
                || lower.startsWith("which ")
                || lower.startsWith("what ")
                || lower.contains("which file")
                || lower.contains("what file")
                || lower.contains("does ");
        boolean staticWebSurface = lower.contains(".html")
                || lower.contains("html")
                || lower.contains("page")
                || lower.contains("web");
        boolean scriptSurface = lower.contains("script")
                || lower.contains(".js")
                || lower.contains("javascript");
        boolean importRelation = lower.contains("import")
                || lower.contains("link")
                || lower.contains("load")
                || lower.contains("include")
                || lower.contains("reference")
                || lower.contains("src");
        return asksQuestion && staticWebSurface && scriptSurface && importRelation;
    }

    public static Set<String> evidenceTargets(String userRequest, Collection<String> extractedTargets) {
        if (!matches(userRequest)) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>(htmlTargets(extractedTargets));
        if (out.isEmpty() && userRequest.toLowerCase(Locale.ROOT).contains("index.html")) {
            out.add("index.html");
        }
        return Set.copyOf(out);
    }

    public static List<String> htmlTargets(Collection<String> extractedTargets) {
        return targetsWithExtension(extractedTargets, ".html", ".htm");
    }

    public static List<String> scriptCandidates(Collection<String> extractedTargets) {
        List<String> out = targetsWithExtension(extractedTargets, ".js", ".jsx", ".ts", ".tsx");
        return out.stream().sorted().toList();
    }

    private static List<String> targetsWithExtension(Collection<String> targets, String... extensions) {
        if (targets == null || targets.isEmpty()) return List.of();
        ArrayList<String> out = new ArrayList<>();
        for (String target : targets) {
            String normalized = normalize(target);
            if (normalized.isBlank()) continue;
            String lower = normalized.toLowerCase(Locale.ROOT);
            for (String extension : extensions) {
                if (lower.endsWith(extension) && !out.contains(normalized)) {
                    out.add(normalized);
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static String normalize(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
