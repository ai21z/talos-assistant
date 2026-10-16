package dev.talos.core.security;

import dev.talos.core.CfgUtil;
import dev.talos.core.util.Sanitize;
import dev.talos.core.util.Hash;
import dev.talos.safety.SecretShapePatterns;

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
    private final String policyFingerprint;

    // Absolute *filesystem* paths (Windows & POSIX). Avoids matching dotted package names.
    // POSIX arm requires: (1) preceded by whitespace or start-of-line (truly absolute),
    // and (2) at least one internal '/' to avoid matching REPL commands like /help.
    private static final Pattern ABS_PATH = Pattern.compile(
            // Windows: C:\... or C:/...
            "(?i)\\b[A-Z]:[\\\\/](?:[^\\s\"'<>|]{1,200}[\\\\/])*[^\\s\"'<>|]{1,200}" +
                    // OR POSIX: /usr/bin/... (must start after whitespace/SOL, must have 2+ segments)
                    "|(?:(?<=\\s)|(?<=^))(/[^\\s\"'<>|/]{1,200}(?:/[^\\s\"'<>|]{1,200})+)"
    );

    // IPv4 with octet validation (0-255). Excludes loopback 127.x.x.x.
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

        List<String> regexes = new ArrayList<>(SecretShapePatterns.redactorDefaultSecretRegexes());
        if (redact.get("secrets") instanceof List<?> xs) {
            for (Object o : xs) if (o != null) regexes.add(String.valueOf(o));
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
        StringBuilder policy = new StringBuilder("terminal-redaction-v1\n")
                .append(redactPaths).append('\n').append(redactIps).append('\n');
        for (Pattern pattern : secretPatterns) {
            policy.append(pattern.flags()).append(':').append(pattern.pattern().length()).append(':')
                    .append(pattern.pattern()).append('\n');
        }
        this.policyFingerprint = Hash.sha256Hex(policy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The effective immutable policy is identified without exposing configured pattern text. */
    public String policyFingerprint() { return policyFingerprint; }

    public String redactLine(String s) {
        if (s == null || s.isEmpty()) return "";
        return redactLine(s, null);
    }

    private record LocationRange(int start, int end) {}
    private record Projection(String text, LocationRange location) {}

    private String redactLine(String s, LocationRange location) {
        if (s.isEmpty()) return "";
        // A control transform can join tokens or move the field. Its exception is revoked.
        String cleaned = Sanitize.stripControls(Sanitize.stripAnsi(s));
        Projection projected = new Projection(cleaned, cleaned.equals(s) ? location : null);

        // Secrets are assessed over the complete line, including either side of the field.
        for (Pattern p : secretPatterns) {
            projected = replace(projected, p, mr -> {
                if (mr.groupCount() >= 2 && mr.group(1) != null && mr.group(2) != null) {
                    return mr.group(1) + "=" + SECRET_MASK;
                }
                return SECRET_MASK;
            });
        }

        if (redactIps) {
            projected = replace(projected, IPV4, ignored -> IP_MASK);
            projected = replace(projected, IPV6, ignored -> IP_MASK);
        }

        if (redactPaths) {
            LocationRange retained = projected.location();
            projected = replace(projected, ABS_PATH, match -> retained != null
                    && match.start() >= retained.start() && match.end() <= retained.end()
                    ? match.group() : PATH_MASK);
        }
        return projected.text();
    }

    /** Every edit before a field is tracked. Any stronger masking overlapping it revokes it. */
    private static Projection replace(Projection source, Pattern pattern,
                                      java.util.function.Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(source.text());
        StringBuilder result = new StringBuilder(source.text().length());
        LocationRange range = source.location();
        int shift = 0;
        int cursor = 0;
        while (matcher.find()) {
            String value = replacement.apply(matcher);
            result.append(source.text(), cursor, matcher.start()).append(value);
            if (range != null && !value.equals(matcher.group())) {
                if (matcher.end() <= range.start()) {
                    shift += value.length() - (matcher.end() - matcher.start());
                } else if (matcher.start() < range.end()) {
                    range = null;
                }
            }
            cursor = matcher.end();
        }
        result.append(source.text(), cursor, source.text().length());
        return new Projection(result.toString(), range == null ? null
                : new LocationRange(range.start() + shift, range.end() + shift));
    }

    public String redactBlock(String s) {
        return redactBlockWithValidatedLocation(s, -1, -1, "");
    }

    /**
     * Low-level projection for an independently admitted exact runtime location field.
     * This method does not admit publication. The caller must establish runtime ownership.
     * Secret, IP and control handling retain the ordinary block's per-line semantics.
     */
    public String redactBlockWithValidatedLocation(String s, int start, int end, String location) {
        if (s == null) return "";
        boolean bound = location != null && start >= 0 && end > start && end <= s.length()
                && end - start == location.length() && s.regionMatches(start, location, 0, location.length())
                && !LINE_TERM.matcher(location).find();
        // Preserve original line terminators (\r\n, \r, \n)
        Matcher termMatcher = LINE_TERM.matcher(s);
        List<String> terminators = new ArrayList<>();
        while (termMatcher.find()) terminators.add(termMatcher.group());

        String[] lines = LINE_TERM.split(s, -1);
        StringBuilder b = new StringBuilder(s.length());
        int offset = 0;
        for (int i = 0; i < lines.length; i++) {
            LocationRange range = bound && start >= offset && end <= offset + lines[i].length()
                    ? new LocationRange(start - offset, end - offset) : null;
            b.append(redactLine(lines[i], range));
            offset += lines[i].length();
            if (i < terminators.size()) {
                b.append(terminators.get(i));
                offset += terminators.get(i).length();
            }
        }
        return b.toString();
    }
}
