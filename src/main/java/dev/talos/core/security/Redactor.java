package dev.talos.core.security;

import dev.talos.core.CfgUtil;
import dev.talos.core.util.Sanitize;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
 *
 * Secret pattern convention: if a custom regex has 2+ capturing groups,
 * group 1 is treated as a label (preserved) and the rest is masked.
 */
public final class Redactor {

    private final boolean redactPaths;
    private final boolean redactIps;
    private final List<Pattern> secretPatterns;

    // Absolute *filesystem* paths (Windows & POSIX). Avoids matching dotted package names.
    // POSIX arm requires: (1) preceded by whitespace or start-of-line (truly absolute),
    // and (2) at least one internal '/' to avoid matching REPL commands like /help.
    private static final Pattern ABS_PATH = Pattern.compile(
            // Windows: C:\... or C:/...
            "(?i)\\b[A-Z]:[\\\\/](?:[^\\s\"'<>|]{1,200}[\\\\/])*[^\\s\"'<>|]{1,200}" +
                    // OR POSIX: /usr/bin/... (must start after whitespace/SOL, must have 2+ segments)
                    "|(?:(?<=\\s)|(?<=^))(/[^\\s\"'<>|/]{1,200}(?:/[^\\s\"'<>|]{1,200})+)"
    );

    // IPv4 with octet validation (0–255). Excludes loopback 127.x.x.x.
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?!127(?:\\.\\d{1,3}){3})" +
            "((?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?))\\b"
    );

    // IPv6: common forms (full, compressed, loopback-excluded).
    // Best-effort, not a full RFC 5952 validator.
    private static final Pattern IPV6 = Pattern.compile(
            "(?<![:\\w])(" +
            "(?:[0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}" +         // full
            "|(?:[0-9a-fA-F]{1,4}:){1,7}:" +                       // trailing ::
            "|(?:[0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}" +      // :: + 1 segment
            "|(?:[0-9a-fA-F]{1,4}:){1,5}(?::[0-9a-fA-F]{1,4}){1,2}" +
            "|::(?:[0-9a-fA-F]{1,4}:){0,4}[0-9a-fA-F]{1,4}" +     // ::prefix
            ")(?![:\\w])"
    );

    // Line terminator for preserving original line endings in redactBlock
    private static final Pattern LINE_TERM = Pattern.compile("\\R");

    // Safe stand-ins
    private static final String PATH_MASK = "[path]";
    private static final String IP_MASK   = "[ip]";
    private static final String SECRET_MASK = "[secret]";

    /** Default (safe) constructor with built-in rules. */
    public Redactor() {
        this(Map.of());
    }

    /** Config-driven constructor. */
    public Redactor(Map<String, Object> cfg) {
        Map<String,Object> root = cfg == null ? Map.of() : cfg;
        Map<String,Object> redact = CfgUtil.map(root.get("redact"));
        this.redactPaths = CfgUtil.boolAt(redact, "paths", true);
        this.redactIps   = CfgUtil.boolAt(redact, "ips",   true);

        List<String> regexes = new ArrayList<>();
        if (redact.get("secrets") instanceof List<?> xs) {
            for (Object o : xs) if (o != null) regexes.add(String.valueOf(o));
        }
        if (regexes.isEmpty()) {
            // Sensible defaults: tokens/keys/password-style assignments and well-known prefixes.
            regexes.add("(?i)\\b(api[_-]?key|token|secret|password|passwd|pwd|bearer)\\s*[:=]\\s*['\\\"]?([A-Za-z0-9._\\-+/=]{8,})");
            regexes.add("\\b(sk-[A-Za-z0-9]{16,})\\b");         // common vendor prefixes
            regexes.add("\\b(xox[baprs]-[A-Za-z0-9-]{12,})\\b");// Slack token shapes
            regexes.add("\\b(ghp_[A-Za-z0-9]{20,})\\b");        // GitHub PAT
            regexes.add("\\b([A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{20,})\\b"); // JWT-like (variable length)
        }
        List<Pattern> compiled = new ArrayList<>(regexes.size());
        for (String rx : regexes) {
            try {
                compiled.add(Pattern.compile(rx));
            } catch (Exception e) {
                System.err.println("[Redactor] Skipping invalid secret pattern: " + rx + " (" + e.getMessage() + ")");
            }
        }
        this.secretPatterns = List.copyOf(compiled);
    }

    public String redactLine(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s;

        // 1) strip obviously dangerous control sequences first
        out = Sanitize.stripAnsi(out);
        out = Sanitize.stripControls(out);

        // 2) secrets (label-aware: patterns with 2+ groups preserve group 1 as label)
        for (Pattern p : secretPatterns) {
            out = p.matcher(out).replaceAll(mr -> {
                if (mr.groupCount() >= 2 && mr.group(1) != null && mr.group(2) != null) {
                    return Matcher.quoteReplacement(mr.group(1)) + "=" + SECRET_MASK;
                }
                return SECRET_MASK;
            });
        }

        // 3) IPs (avoid loopback noise; mask everything else)
        if (redactIps) {
            out = IPV4.matcher(out).replaceAll(IP_MASK);
            out = IPV6.matcher(out).replaceAll(IP_MASK);
        }

        // 4) absolute filesystem paths
        if (redactPaths) {
            out = ABS_PATH.matcher(out).replaceAll(PATH_MASK);
        }

        return out;
    }

    public String redactBlock(String s) {
        if (s == null) return "";
        // Preserve original line terminators (\r\n, \r, \n)
        Matcher termMatcher = LINE_TERM.matcher(s);
        List<String> terminators = new ArrayList<>();
        while (termMatcher.find()) terminators.add(termMatcher.group());

        String[] lines = LINE_TERM.split(s, -1);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < lines.length; i++) {
            b.append(redactLine(lines[i]));
            if (i < terminators.size()) b.append(terminators.get(i));
        }
        return b.toString();
    }
}
