package dev.talos.runtime.verification;

import java.util.List;

/** Analyzed file names are retained without an evidence freshness claim. */
public record StaticWebDiagnostic(
        Kind kind,
        String source,
        String target,
        List<String> operands,
        String message
) {
    public StaticWebDiagnostic {
        kind = kind == null ? Kind.OTHER : kind;
        source = source == null ? "" : source;
        target = target == null ? "" : target;
        operands = operands == null ? List.of() : List.copyOf(operands);
        message = message == null ? "" : message;
    }

    public static StaticWebDiagnostic other(String message) {
        return new StaticWebDiagnostic(Kind.OTHER, "", "", List.of(), message);
    }

    public enum Kind {
        HTML_DUPLICATE_ID,
        CSS_MISSING_CLASS,
        CSS_MISSING_ID,
        CSS_BARE_CLASS,
        JAVASCRIPT_MISSING_CLASS,
        JAVASCRIPT_MISSING_ID,
        OTHER;

        public boolean isCss() {
            return this == CSS_MISSING_CLASS || this == CSS_MISSING_ID || this == CSS_BARE_CLASS;
        }

        public boolean isJavaScript() {
            return this == JAVASCRIPT_MISSING_CLASS || this == JAVASCRIPT_MISSING_ID;
        }

        public boolean supportsAnswerPreservation() {
            return isJavaScript();
        }
    }
}
