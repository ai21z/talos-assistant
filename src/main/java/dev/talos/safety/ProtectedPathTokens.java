package dev.talos.safety;

import java.util.List;
import java.util.Locale;

/** Pure protected-path token recognition for sink redaction. */
public final class ProtectedPathTokens {
    private ProtectedPathTokens() {}

    private static final List<String> PRIVATE_KEY_FILENAMES =
            List.of("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519");

    private static final List<String> PRIVATE_KEY_EXTENSIONS =
            List.of(".pem", ".key", ".p12", ".pfx");

    public static boolean looksProtectedPathToken(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return false;
        String normalized = stripWrappingQuotes(rawPath.strip())
                .replace('\\', '/')
                .toLowerCase(Locale.ROOT);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return !protectedKind(normalized).isBlank();
    }

    public static String protectedKind(String lowerRelative) {
        if (lowerRelative == null || lowerRelative.isBlank()) return "";
        List<String> segments = List.of(lowerRelative.split("/+"));

        if (segments.contains(".git") || segments.contains(".gnupg")) return "CONTROL";
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
            if (segment.contains("secret")
                    || segment.contains("token")
                    || segment.contains("credential")
                    || segment.contains("password")
                    || segment.contains("private_key")
                    || segment.contains("private-key")) {
                return "SECRET";
            }
        }
        for (int i = 0; i + 1 < segments.size(); i++) {
            if (".config".equals(segments.get(i)) && "gcloud".equals(segments.get(i + 1))) {
                return "SECRET";
            }
        }

        String filename = segments.isEmpty() ? lowerRelative : segments.get(segments.size() - 1);
        if (filename.contains("secret")
                || filename.contains("token")
                || filename.contains("credential")
                || filename.contains("password")
                || filename.contains("private_key")
                || filename.contains("private-key")) {
            return "SECRET";
        }
        for (String ext : PRIVATE_KEY_EXTENSIONS) {
            if (filename.endsWith(ext)) return "SECRET";
        }
        return "";
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
