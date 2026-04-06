package dev.talos.core.security;

import dev.talos.core.CfgUtil;
import dev.talos.core.util.Sanitize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Local-only redaction utilities used for console output & audit logs.
 * Goals:
 *  - Idempotent: re-running over redacted text keeps it stable.
 *  - Fast: single-pass-ish regexes, no catastrophic backtracking.
 *  - Conservative: avoid over-redacting normal prose/code.
 *
 * Config (all optional, defaults shown):
 *   redact.paths   : true
 *   redact.ips     : true
 *   redact.secrets : [ list of regex strings; see defaults below ]
 */
public final class Redactor {

    private final boolean redactPaths;
    private final boolean redactIps;
    private final List<Pattern> secretPatterns;

    // Absolute *filesystem* paths (Windows & POSIX). Avoids matching dotted package names.
    private static final Pattern ABS_PATH = Pattern.compile(
            // Windows: C:\... or C:/...
            "(?i)(?:\\b[A-Z]:[\\\\/](?:[^\\s\"'<>|]{1,200}[\\\\/])*[^\\s\"'<>|]{1,200})" +
                    // OR POSIX: /usr/... (avoid matching URLs by excluding : after scheme)
                    "|(?:\\B/(?:[^\\s\"'<>|]{1,200}/)*[^\\s\"'<>|]{1,200})"
    );

    private static final Pattern IPV4 = Pattern.compile("\\b(?!127(?:\\.\\d{1,3}){3})((?:\\d{1,3}\\.){3}\\d{1,3})\\b");

    // Safe stand-ins
    private static final String PATH_MASK = "[path]";
    private static final String IP_MASK   = "[ip]";
    private static final String SECRET_MASK = "[secret]";

    /** Default (safe) constructor with built-in rules. */
    public Redactor() {
        this(Map.of());
    }

    /** Config-driven constructor. */
    @SuppressWarnings("unchecked")
    public Redactor(Map<String, Object> cfg) {
        Map<String,Object> root = cfg == null ? Map.of() : cfg;
        Map<String,Object> redact = CfgUtil.map(root.get("redact"));
        this.redactPaths = redact == null || !redact.containsKey("paths") || Boolean.TRUE.equals(redact.get("paths"));
        this.redactIps   = redact == null || !redact.containsKey("ips")   || Boolean.TRUE.equals(redact.get("ips"));

        List<String> regexes = new ArrayList<>();
        if (redact != null && redact.get("secrets") instanceof List<?> xs) {
            for (Object o : xs) if (o != null) regexes.add(String.valueOf(o));
        }
        if (regexes.isEmpty()) {
            // Sensible defaults: tokens/keys/password-style assignments and well-known prefixes.
            regexes.add("(?i)\\b(api[_-]?key|token|secret|password|passwd|pwd|bearer)\\s*[:=]\\s*['\\\"]?([A-Za-z0-9._\\-+/=]{8,})");
            regexes.add("\\b(sk-[A-Za-z0-9]{16,})\\b");         // common vendor prefixes
            regexes.add("\\b(xox[baprs]-[A-Za-z0-9-]{12,})\\b");// Slack token shapes
            regexes.add("\\b(ghp_[A-Za-z0-9]{20,})\\b");        // GitHub PAT
            regexes.add("\\b([A-Za-z0-9]{24}\\.[A-Za-z0-9_\\-]{6}\\.[A-Za-z0-9_\\-]{27})\\b"); // JWT-like
        }
        this.secretPatterns = new ArrayList<>(regexes.size());
        for (String rx : regexes) {
            try { this.secretPatterns.add(Pattern.compile(rx)); } catch (Exception ignore) { /* skip bad rule */ }
        }
    }

    public String redactLine(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s;

        // 1) strip obviously dangerous control sequences first
        out = Sanitize.stripAnsi(out);
        out = Sanitize.stripControls(out);

        // 2) secrets (idempotent: replaced tokens don't re-match the patterns)
        for (Pattern p : secretPatterns) {
            out = p.matcher(out).replaceAll(SECRET_MASK);
        }

        // 3) IPs (avoid loopback noise; mask everything else)
        if (redactIps) {
            out = IPV4.matcher(out).replaceAll(IP_MASK);
        }

        // 4) absolute filesystem paths
        if (redactPaths) {
            out = ABS_PATH.matcher(out).replaceAll(PATH_MASK);
        }

        return out;
    }

    public String redactBlock(String s) {
        if (s == null) return "";
        String[] lines = s.split("\\R", -1);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) b.append('\n');
            b.append(redactLine(lines[i]));
        }
        return b.toString();
    }
}
