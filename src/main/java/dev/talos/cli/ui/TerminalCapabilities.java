package dev.talos.cli.ui;

import java.nio.charset.Charset;
import java.util.Map;

/**
 * Terminal capability snapshot used by trusted CLI renderers.
 */
public record TerminalCapabilities(
        ColorPolicy colorPolicy,
        boolean interactive,
        boolean colorEnabled,
        boolean unicodeSafe,
        boolean dumbTerminal
) {
    public static TerminalCapabilities detectDefault() {
        // isatty, not System.console(): on JDK 22+ a console exists even for
        // redirected output, which would enable color/unicode on piped
        // transcripts that must stay byte-identical plain (T770).
        return detect(
                System.getenv(),
                InteractiveTty.stdoutIsTty(),
                System.getProperty("os.name", ""),
                Charset.defaultCharset(),
                null);
    }

    public static TerminalCapabilities detect(
            Map<String, String> env,
            boolean hasConsole,
            String osName,
            Charset charset,
            ColorPolicy requestedPolicy) {
        Map<String, String> safeEnv = env == null ? Map.of() : env;
        ColorPolicy policy = requestedPolicy == null
                ? ColorPolicy.fromEnvironment(safeEnv)
                : requestedPolicy;
        boolean dumb = isDumbTerminal(safeEnv);
        boolean color = detectColorSupport(safeEnv, hasConsole, dumb, policy);
        boolean unicode = detectUnicodeSupport(safeEnv, hasConsole, dumb, osName, charset);
        return new TerminalCapabilities(policy, hasConsole, color, unicode, dumb);
    }

    private static boolean detectColorSupport(
            Map<String, String> env,
            boolean hasConsole,
            boolean dumb,
            ColorPolicy policy) {
        if (dumb) return false;
        if (policy == ColorPolicy.NEVER) return false;
        if (policy == ColorPolicy.ALWAYS) return true;
        if (!hasConsole) return false;

        if (ColorPolicy.hasEnv(env, "WT_SESSION")) return true;
        if (ColorPolicy.hasEnv(env, "COLORTERM")) return true;
        if (ColorPolicy.hasEnv(env, "TERM_PROGRAM")) return true;

        String term = ColorPolicy.envValue(env, "TERM");
        if (term != null) {
            String lower = term.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("color") || lower.contains("xterm") || lower.contains("256")) {
                return true;
            }
        }

        return true;
    }

    private static boolean detectUnicodeSupport(
            Map<String, String> env,
            boolean hasConsole,
            boolean dumb,
            String osName,
            Charset charset) {
        if (dumb) return false;
        if (!hasConsole) return false;
        if (ColorPolicy.hasEnv(env, "WT_SESSION")) return true;
        if (ColorPolicy.hasEnv(env, "TERM_PROGRAM")) return true;

        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        if (!os.contains("win")) return true;

        try {
            Charset cs = charset == null ? Charset.defaultCharset() : charset;
            return "UTF-8".equalsIgnoreCase(cs.name());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isDumbTerminal(Map<String, String> env) {
        String term = ColorPolicy.envValue(env, "TERM");
        return term != null && "dumb".equalsIgnoreCase(term.trim());
    }
}
