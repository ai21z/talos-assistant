package dev.talos.safety;

import java.util.List;
import java.util.Locale;

/**
 * Pure protected-path token recognition — the SINGLE protected-path
 * classifier (T759). Sink redaction, permission policy, evidence gating,
 * answer guards, and repair planners all delegate here; do not grow local
 * copies of this vocabulary.
 *
 * <p>Secret-name matching uses equals-or-suffix on lowercase LETTER RUNS
 * (digits, {@code _}, {@code -}, {@code .} separate runs): a run that
 * equals or ends with a vocabulary stem is secret-bearing. Secret names
 * overwhelmingly end with the noun ({@code api_token}, {@code mysecrets},
 * {@code supersecret}); code-tooling names append derivational suffixes
 * ({@code tokenizer}, {@code secretary}, {@code passwordless}) where the
 * stem is a prefix. Pure word-equality would fail OPEN on
 * {@code mysecrets.txt}-class names — the suffix rule keeps them protected
 * while fixing the {@code tokenizer.java}-class false positives.
 */
public final class ProtectedPathTokens {
    private ProtectedPathTokens() {}

    private static final List<String> PRIVATE_KEY_FILENAMES =
            List.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");

    private static final List<String> PRIVATE_KEY_EXTENSIONS =
            List.of(".pem", ".key", ".p12", ".pfx");

    /**
     * Secret-name stems matched equals-or-suffix against letter runs.
     * Singulars and plurals are listed separately because "tokens" ends
     * with "token" anyway but "secrets"/"credentials" pluralize the run.
     * "key" alone is deliberately NOT a stem (monkey, keyboard);
     * private-key shapes are covered by "privatekey" plus the
     * (private, key) adjacent-run pair.
     */
    private static final List<String> SECRET_STEMS = List.of(
            "secret", "secrets",
            "token", "tokens",
            "credential", "credentials",
            "password", "passwords",
            "privatekey");

    public static boolean looksProtectedPathToken(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return false;
        String normalized = normalize(rawPath);
        return !protectedKind(normalized).isBlank();
    }

    /**
     * Fail-closed readback sensitivity for repair planners (T759): a path
     * that cannot be normalized is treated as sensitive, so compact repair
     * frames never inline content they cannot classify. Replaces four
     * identical planner-local copies.
     */
    public static boolean isSensitiveReadbackPath(String path) {
        if (path == null || path.isBlank()) return true;
        String normalized = normalize(path);
        if (normalized.isBlank()) return true;
        return !protectedKind(normalized).isBlank();
    }

    public static String protectedKind(String lowerRelative) {
        if (lowerRelative == null || lowerRelative.isBlank()) return "";
        List<String> segments = List.of(lowerRelative.split("/+"));

        // .talos is CONTROL since T788: it holds workspace-declared
        // verification profiles (.talos/profiles.yaml) and template commands
        // (.talos/commands/*.md) — content that influences what Talos
        // executes, so the model must not write it with an ordinary
        // write approval.
        if (segments.contains(".git") || segments.contains(".gnupg")
                || segments.contains(".talos")) return "CONTROL";
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (".github".equals(segments.get(i)) && "workflows".equals(segments.get(i + 1))) {
                return "CONTROL";
            }
        }

        for (String segment : segments) {
            if (segment.equals(".env") || segment.startsWith(".env.")) return "SECRET";
            if (segment.endsWith(".env")) return "SECRET";
            if (segment.equals("secrets") || segment.equals("tokens") || segment.equals("credentials")) return "SECRET";
            if (segment.equals("protected")) return "SECRET";
            if (segment.equals(".ssh") || segment.equals(".aws") || segment.equals(".azure")) return "SECRET";
            if (PRIVATE_KEY_FILENAMES.contains(segment)) return "SECRET";
            if (hasSecretWordRun(segment)) return "SECRET";
        }
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (".config".equals(segments.get(i)) && "gcloud".equals(segments.get(i + 1))) {
                return "SECRET";
            }
        }

        String filename = segments.isEmpty() ? lowerRelative : segments.get(segments.size() - 1);
        if (hasSecretWordRun(filename)) return "SECRET";
        for (String ext : PRIVATE_KEY_EXTENSIONS) {
            if (filename.endsWith(ext)) return "SECRET";
        }
        return "";
    }

    private static boolean hasSecretWordRun(String segment) {
        List<String> runs = letterRuns(segment);
        for (int i = 0; i < runs.size(); i++) {
            String run = runs.get(i);
            for (String stem : SECRET_STEMS) {
                if (run.endsWith(stem)) return true;
            }
            if ("private".equals(run) && i + 1 < runs.size() && "key".equals(runs.get(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> letterRuns(String segment) {
        List<String> runs = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c >= 'a' && c <= 'z') {
                current.append(c);
            } else if (!current.isEmpty()) {
                runs.add(current.toString());
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) runs.add(current.toString());
        return runs;
    }

    private static String normalize(String rawPath) {
        String normalized = stripWrappingQuotes(rawPath.strip())
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String stripWrappingQuotes(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '`' && last == '`')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
