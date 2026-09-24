package dev.talos.runtime.verification;

import dev.talos.core.diff.DocumentTextDiff;
import dev.talos.runtime.TurnAuditCapture.DocumentComparison;

/** Display escaping is separate from the exact source and computation identities. */
public final class DocumentComparisonPresentation {
    public static final String VERSION = "document-comparison-display-v1";
    public record Display(String text, boolean complete) { }
    private DocumentComparisonPresentation() { }

    public static Display render(DocumentComparison observation, int maximum) {
        var pair = observation.pair();
        var result = observation.result();
        if (maximum < 512) return new Display("Comparison display unavailable.", false);
        var text = new StringBuilder("Ordered document comparison. ").append(DocumentTextDiff.VERSION).append('.');
        text.append("\nLeft ").append(quote(pair.leftIdentity().relativePath())).append(". Raw SHA-256 ")
                .append(pair.leftIdentity().rawSha256()).append(". Text SHA-256 ").append(pair.leftIdentity().textSha256());
        text.append("\nRight ").append(quote(pair.rightIdentity().relativePath())).append(". Raw SHA-256 ")
                .append(pair.rightIdentity().rawSha256()).append(". Text SHA-256 ").append(pair.rightIdentity().textSha256());
        text.append("\nRepresentations ").append(pair.leftIdentity().representation()).append(" and ")
                .append(pair.rightIdentity().representation()).append('.');
        text.append("\nComputation ").append(result.status()).append(". Raw equality ").append(result.rawEquality())
                .append(". Text equality ").append(result.textEquality()).append(". BOM and line-terminator sequence equality ").append(result.formatEquality()).append('.');
        text.append("\nCaptured snapshots are compared, not an atomic cross-file state. PDF and DOCX comparison covers extracted text only, not visual layout or tracked changes.");
        if (result.status() != DocumentTextDiff.Status.COMPLETE) return new Display(text.toString(), false);
        text.append("\nAdded lines ").append(result.addedLines()).append(". Removed lines ").append(result.removedLines())
                .append(". BOM changed ").append(result.bomChanged()).append('.');
        text.append("\nChanged lines are displayed as quoted literals with explicit terminators. Positions are one-based.");
        if (text.length() + 100 > maximum)
            return new Display("The comparison metadata exceeds the display bound. Full delivery was not established.", false);
        boolean complete = true;
        for (var hunk : result.hunks()) {
            var part = new StringBuilder("\nLeft line ").append(hunk.beforeStart() + 1)
                    .append(", right line ").append(hunk.afterStart() + 1).append('.');
            for (var line : hunk.removed()) part.append("\nRemoved ").append(quote(line.text())).append(" [").append(line.terminator()).append(']');
            for (var line : hunk.added()) part.append("\nAdded ").append(quote(line.text())).append(" [").append(line.terminator()).append(']');
            if (text.length() + part.length() + 100 > maximum) { complete = false; break; }
            text.append(part);
        }
        if (!complete) text.append("\nChanged lines were omitted by the display bound. Full delivery was not established.");
        return new Display(text.toString(), complete);
    }

    static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        value.codePoints().forEach(c -> {
            if (c == '\\' || c == '"') result.append('\\').appendCodePoint(c);
            else if (c < 32 || c == 127 || Character.getType(c) == Character.FORMAT
                    || c == '`' || c == '<' || c == '>') result.append(String.format(java.util.Locale.ROOT, "\\u%04x", c));
            else result.appendCodePoint(c);
        });
        return result.append('"').toString();
    }
}
