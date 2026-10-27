package dev.talos.runtime.verification;

/** Parses the text of an ATX Markdown heading for deterministic verification. */
final class MarkdownHeadingParser {

    private MarkdownHeadingParser() {}

    static String headingText(String line) {
        if (line == null) return "";
        String value = line.strip();
        int hashes = 0;
        while (hashes < value.length() && hashes < 6 && value.charAt(hashes) == '#') hashes++;
        if (hashes == 0 || hashes >= value.length() || !Character.isWhitespace(value.charAt(hashes))) {
            return "";
        }
        String heading = value.substring(hashes).strip();
        while (heading.endsWith("#")) {
            heading = heading.substring(0, heading.length() - 1).stripTrailing();
        }
        return heading;
    }
}
