package dev.loqj.core.security;

/**
 * No-op redactor for PR-1. Returns input unchanged.
 * We’ll replace with the real redaction rules in a later PR.
 */
public final class Redactor {
    public Redactor() {}

    public String redactLine(String s) {
        return s == null ? "" : s;
    }

    public String redactBlock(String s) {
        if (s == null) return "";
        String[] lines = s.split("\\R", -1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) b.append('\n');
            b.append(redactLine(lines[i]));
        }
        return b.toString();
    }
}
