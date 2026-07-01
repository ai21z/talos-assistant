package dev.talos.safety;

import java.util.List;
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
                    + "(?:AKIA|ASIA)[A-Z0-9]{16}|xox[baprs]-[A-Za-z0-9-]{12,}))\\b");

    private static final Pattern EYJ_ANCHORED_JWT = Pattern.compile(
            "\\b(eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b");

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
        return redacted;
    }

    public static boolean containsSecretShape(String text) {
        if (text == null || text.isBlank()) return false;
        return PEM_PRIVATE_KEY_BLOCK.matcher(text).find()
                || CONNECTION_STRING_WITH_USERINFO.matcher(text).find()
                || TOKEN_PREFIX.matcher(text).find()
                || EYJ_ANCHORED_JWT.matcher(text).find();
    }
}
