package dev.talos.runtime.trace;

import dev.talos.runtime.policy.ProtectedContentPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small deterministic redaction helpers for local trace v1. */
public final class TraceRedactor {
    private TraceRedactor() {}

    public static final String PROTECTED_READ_ANSWER_REDACTION =
            "[protected read answer redacted from history]";
    public static final String PRIVATE_DOCUMENT_ANSWER_REDACTION =
            "[private document answer redacted from history]";

    private static final Pattern SECRET_LIKE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_.-]*(?:secret|token|api[_-]?key|password|passwd|pwd|credential|credentials|private[_-]?key)[A-Za-z0-9_.-]*)\\b\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[^\\s,;]+)");
    private static final Pattern PROTECTED_PATH_REFERENCE = Pattern.compile(
            "(?i)(^|[\\s\"'`({\\[])"
                    + "(?:\\./)?(?:"
                    + "\\.env(?:\\b|\\.[A-Za-z0-9_.-]*\\b)"
                    + "|(?:secrets|tokens|credentials)[/\\\\][^\\s\"'`({\\[\\])}]+"
                    + "|[^\\s\"'`({\\[\\])}]*"
                    + "(?:secret|token|credential|password|private[_-]?key)"
                    + "[^\\s\"'`({\\[\\])}]*\\.[A-Za-z0-9]{1,8}\\b"
                    + "|id_rsa|id_ed25519"
                    + ")");

    static String hash(String value) {
        String safe = value == null ? "" : value;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(safe.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "sha256:unavailable";
        }
    }

    static int bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    static int lines(String value) {
        if (value == null || value.isEmpty()) return 0;
        return (int) value.chars().filter(ch -> ch == '\n').count() + 1;
    }

    static String pathHint(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.strip().replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (ProtectedContentPolicy.looksProtectedPathString(lower)) return "<protected-path>";
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    public static String redactSecretLikeAssignments(String text) {
        return ProtectedContentPolicy.sanitizeText(text);
    }

    public static boolean containsSecretLikeAssignment(String text) {
        return ProtectedContentPolicy.containsProtectedContentSignal(text);
    }

    public static String redactProtectedReadAnswerForPersistence(String userInput, String assistantText) {
        String redacted = redactSecretLikeAssignments(assistantText);
        if (redacted == null || redacted.isBlank()) return redacted;
        if (containsSecretLikeAssignment(assistantText)) return redacted;
        if (ProtectedContentPolicy.containsRawPrivateDocumentFactCanary(assistantText)) return redacted;
        if (redacted.contains(ProtectedContentPolicy.REDACTED_PRIVATE_DOCUMENT_CANARY)) return redacted;
        if (looksLikeProtectedReadRequest(userInput) && !isProtectedReadDenial(redacted)) {
            return PROTECTED_READ_ANSWER_REDACTION;
        }
        if (looksLikeDocumentExtractionRequest(userInput) && !isDocumentExtractionDenial(redacted)) {
            return PRIVATE_DOCUMENT_ANSWER_REDACTION;
        }
        return redacted;
    }

    public static boolean looksLikeProtectedReadRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (looksLikeProtectedReadProhibition(lower)) return false;
        if (!containsProtectedPathReference(text)) return false;
        return lower.contains("read")
                || lower.contains("show")
                || lower.contains("print")
                || lower.contains("tell me")
                || lower.contains("what")
                || lower.contains("value")
                || lower.contains("contents")
                || lower.contains("inside")
                || lower.contains("open ")
                || lower.contains("cat ");
    }

    private static boolean containsProtectedPathReference(String text) {
        if (text == null || text.isBlank()) return false;
        if (PROTECTED_PATH_REFERENCE.matcher(text).find()) return true;
        Matcher matcher = Pattern.compile("[^\\s\"'`({\\[\\])}]+").matcher(text);
        while (matcher.find()) {
            String token = trimPathTokenPunctuation(matcher.group());
            if (looksPathLike(token) && ProtectedContentPolicy.looksProtectedPathString(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksPathLike(String token) {
        if (token == null || token.isBlank()) return false;
        String normalized = token.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.startsWith(".")
                || normalized.contains("/")
                || normalized.contains(".")
                || normalized.startsWith("id_");
    }

    private static String trimPathTokenPunctuation(String token) {
        if (token == null) return "";
        String out = token.strip();
        while (!out.isEmpty()) {
            char last = out.charAt(out.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == ':' || last == '!' || last == '?') {
                out = out.substring(0, out.length() - 1);
            } else {
                break;
            }
        }
        return out;
    }

    public static boolean isProtectedReadDenial(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("protected content was not read")
                || lower.contains("approval denied")
                || lower.contains("permission was denied")
                || lower.contains("was not read")
                || lower.contains("did not read")
                || lower.contains("cannot read")
                || lower.contains("can't read");
    }

    public static boolean looksLikeDocumentExtractionRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (!containsExtractableDocumentReference(lower)) return false;
        return lower.contains("read")
                || lower.contains("show")
                || lower.contains("print")
                || lower.contains("tell me")
                || lower.contains("what")
                || lower.contains("summarize")
                || lower.contains("summary")
                || lower.contains("extract")
                || lower.contains("compare")
                || lower.contains("contents")
                || lower.contains("inside")
                || lower.contains("open ");
    }

    public static boolean isDocumentExtractionDenial(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("cannot extract")
                || lower.contains("can't extract")
                || lower.contains("extraction failed")
                || lower.contains("unsupported")
                || lower.contains("was withheld from model context")
                || lower.contains("withheld from model context")
                || lower.contains("local-display-only");
    }

    private static String trailingSentencePunctuation(String value) {
        if (value == null || value.length() < 2) return "";
        char last = value.charAt(value.length() - 1);
        if (last == '.' || last == '!' || last == '?') {
            return String.valueOf(last);
        }
        return "";
    }

    private static String normalizedSecretValue(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        if (normalized.length() >= 2) {
            char first = normalized.charAt(0);
            char last = normalized.charAt(normalized.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'') || (first == '`' && last == '`')) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
        }
        if (normalized.length() >= 2) {
            char last = normalized.charAt(normalized.length() - 1);
            if (last == '.' || last == '!' || last == '?') {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
        }
        return normalized;
    }

    private static boolean shouldRedactValueEcho(String value) {
        return value != null
                && value.length() >= 4
                && !value.equalsIgnoreCase("[redacted]");
    }

    private static boolean looksLikeProtectedReadProhibition(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains("do not read .env")
                || lower.contains("don't read .env")
                || lower.contains("do not inspect .env")
                || lower.contains("don't inspect .env")
                || lower.contains("without reading .env")
                || lower.contains("without inspecting .env");
    }

    private static boolean containsExtractableDocumentReference(String lower) {
        if (lower == null || lower.isBlank()) return false;
        return lower.contains(".pdf")
                || lower.contains(".docx")
                || lower.contains(".xlsx")
                || lower.contains(".xls")
                || lower.contains("pdf ")
                || lower.contains("word document")
                || lower.contains("word file")
                || lower.contains("excel workbook")
                || lower.contains("excel file")
                || lower.contains("spreadsheet");
    }
}
