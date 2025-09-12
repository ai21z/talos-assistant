package dev.loqj.cli.repl;

/** Classifies raw REPL input lines without side effects. */
public final class LineClassifier {
    public enum LineType { EMPTY, COMMAND, PROMPT }

    public record Classified(LineType type, String commandName, String argsText) {}

    /** Returns COMMAND if line starts with ":" at col 0; PROMPT otherwise; EMPTY if blank. */
    public Classified classify(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new Classified(LineType.EMPTY, "", "");
        }
        if (raw.startsWith(":")) {
            // grab token up to whitespace
            int i = 1;
            while (i < raw.length() && !Character.isWhitespace(raw.charAt(i))) i++;
            String name = raw.substring(1, i);
            String args = i < raw.length() ? raw.substring(i).trim() : "";
            return new Classified(LineType.COMMAND, name, args);
        }
        return new Classified(LineType.PROMPT, "", raw);
    }
}
