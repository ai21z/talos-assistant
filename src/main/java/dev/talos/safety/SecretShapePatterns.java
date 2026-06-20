package dev.talos.safety;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort secret-shape detectors shared by safety sinks and audit redactors. */
public final class SecretShapePatterns {
    private SecretShapePatterns() {}

    private static final Pattern PEM_PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----[\\s\\S]*?-----END (?:[A-Z0-9 ]+ )?PRIVATE KEY-----");

    private static final Pattern CONNECTION_STRING_WITH_USERINFO = Pattern.compile(
            "(?i)\\b((?:jdbc:)?(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|amqps?|https?)://[^\\s:@/]+:[^\\s@/]+@[^\\s]+)");

    private static final Pattern TOKEN_PREFIX = Pattern.compile(
            "\\b((?:sk-[A-Za-z0-9]{16,}|sk-proj-[A-Za-z0-9_-]{16,}|sk-ant-[A-Za-z0-9_-]{16,}|"
                    + "(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,}|"
                    + "xox[baprs]-[A-Za-z0-9-]{12,}))\\b");

    private static final Pattern EYJ_ANCHORED_JWT = Pattern.compile(
            "\\b(eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b");

    private static final Pattern HIGH_ENTROPY_CANDIDATE = Pattern.compile(
            "(?<![A-Za-z0-9_+/-])([A-Za-z0-9_+/-]{32,}={0,2})(?![A-Za-z0-9_+=/-])");

    private static final Pattern HEX_ONLY = Pattern.compile("(?i)[a-f0-9]{32,64}");
    private static final Pattern UUID = Pattern.compile(
            "(?i)[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");

    public static List<String> redactorDefaultSecretRegexes() {
        return List.of(
                "(?i)\\b(api[_-]?key|token|secret|password|passwd|pwd|bearer)\\s*[:=]\\s*['\\\"]?([A-Za-z0-9._\\-+/=]{8,})",
                TOKEN_PREFIX.pattern(),
                CONNECTION_STRING_WITH_USERINFO.pattern(),
                EYJ_ANCHORED_JWT.pattern());
    }

    public static String redactSecretShapes(String text, String replacement) {
        if (text == null || text.isBlank()) return text;
        String redacted = PEM_PRIVATE_KEY_BLOCK.matcher(text).replaceAll(Matcher.quoteReplacement(replacement));
        redacted = CONNECTION_STRING_WITH_USERINFO.matcher(redacted).replaceAll(Matcher.quoteReplacement(replacement));
        redacted = TOKEN_PREFIX.matcher(redacted).replaceAll(Matcher.quoteReplacement(replacement));
        redacted = EYJ_ANCHORED_JWT.matcher(redacted).replaceAll(Matcher.quoteReplacement(replacement));
        redacted = redactHighEntropy(redacted, replacement);
        return redacted;
    }

    public static boolean containsSecretShape(String text) {
        if (text == null || text.isBlank()) return false;
        return PEM_PRIVATE_KEY_BLOCK.matcher(text).find()
                || CONNECTION_STRING_WITH_USERINFO.matcher(text).find()
                || TOKEN_PREFIX.matcher(text).find()
                || EYJ_ANCHORED_JWT.matcher(text).find()
                || containsHighEntropy(text);
    }

    private static String redactHighEntropy(String text, String replacement) {
        Matcher matcher = HIGH_ENTROPY_CANDIDATE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (looksHighEntropySecret(candidate)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean containsHighEntropy(String text) {
        Matcher matcher = HIGH_ENTROPY_CANDIDATE.matcher(text);
        while (matcher.find()) {
            if (looksHighEntropySecret(matcher.group(1))) return true;
        }
        return false;
    }

    private static boolean looksHighEntropySecret(String candidate) {
        if (candidate == null || candidate.length() < 32) return false;
        if (HEX_ONLY.matcher(candidate).matches()) return false;
        if (UUID.matcher(candidate).matches()) return false;
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            lower |= c >= 'a' && c <= 'z';
            upper |= c >= 'A' && c <= 'Z';
            digit |= c >= '0' && c <= '9';
        }
        if (!(lower && upper && digit)) return false;
        return shannonEntropy(candidate) >= 4.2;
    }

    private static double shannonEntropy(String value) {
        Set<Character> unique = new HashSet<>();
        for (int i = 0; i < value.length(); i++) {
            unique.add(value.charAt(i));
        }
        List<Character> symbols = new ArrayList<>(unique);
        double entropy = 0.0;
        for (char symbol : symbols) {
            int count = 0;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) == symbol) count++;
            }
            double p = (double) count / value.length();
            entropy -= p * (Math.log(p) / Math.log(2));
        }
        return entropy;
    }
}
