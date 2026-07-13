package dev.talos.runtime.verification;

import java.util.Locale;

/** Matches one explicit labeled statement without semantic inference. */
final class AppendStatementMatcher {

    private AppendStatementMatcher() {}

    static boolean matches(String observedLine, String label, String statement) {
        String observed = normalize(observedLine, true);
        String expectedLabel = normalize(label, false);
        String expectedStatement = normalize(statement, true);
        if (observed.isBlank() || expectedLabel.isBlank() || expectedStatement.isBlank()
                || !observed.startsWith(expectedLabel)) {
            return false;
        }
        if (observed.equals(expectedLabel + " " + expectedStatement)) {
            return true;
        }
        String suffix = observed.substring(expectedLabel.length()).strip();
        for (String separator : new String[]{":", "-", "says", "saying"}) {
            if (!suffix.startsWith(separator)) continue;
            String remainder = suffix.substring(separator.length()).strip();
            if (remainder.equals(expectedStatement)) return true;
        }
        return false;
    }

    private static String normalize(String value, boolean stripTerminator) {
        String normalized = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (stripTerminator && !normalized.isEmpty()) {
            char last = normalized.charAt(normalized.length() - 1);
            if (last == '.' || last == '!' || last == '?') {
                normalized = normalized.substring(0, normalized.length() - 1).stripTrailing();
            }
        }
        return normalized.toLowerCase(Locale.ROOT);
    }
}
