package dev.talos.cli.ui;

import dev.talos.core.util.Sanitize;
import org.jline.utils.WCWidth;

import java.util.function.IntSupplier;
import java.util.List;

/** Plain command text is wrapped by terminal cells, independently of model answers. */
public final class CommandTextLayout {
    private CommandTextLayout() {}

    public static int width(IntSupplier source) {
        if (source != null) {
            int measured = source.getAsInt();
            if (measured > 0) return Math.min(120, measured);
        }
        return 96;
    }

    public static String entry(String label, String description, int width) {
        String name = clean(label).replace('\n', ' ');
        String detail = clean(description);
        if (width >= 76 && cells(name) <= 24) {
            String prefix = "  " + name + " ".repeat(26 - cells(name));
            String body = wrap(" ".repeat(28) + detail.replace("\n", "\n" + " ".repeat(28)), width);
            return prefix + body.substring(28);
        }
        return wrap("  " + name, width) + "\n" + wrap("    " + detail.replace("\n", "\n    "), width);
    }

    public static String wrap(String text, int width) {
        int limit = Math.max(2, width);
        String[] lines = clean(text).split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            String line = lines[i];
            if (cells(line) <= limit) {
                out.append(line);
                continue;
            }
            int spaces = 0;
            while (spaces < line.length() && line.charAt(spaces) == ' ') spaces++;
            String indent = " ".repeat(Math.min(spaces, limit / 2));
            int start = spaces;
            while (start < line.length()) {
                int available = limit - indent.length();
                int end = start;
                int used = 0;
                int lastSpace = -1;
                while (end < line.length()) {
                    int cp = line.codePointAt(end);
                    int size = Math.max(0, WCWidth.wcwidth(cp));
                    if (used + size > available) break;
                    if (cp == ' ') lastSpace = end;
                    used += size;
                    end += Character.charCount(cp);
                }
                if (end == line.length()) {
                    out.append(indent).append(line, start, end);
                    break;
                }
                int cut = lastSpace > start ? lastSpace : end;
                if (cut == start) cut += Character.charCount(line.codePointAt(start));
                out.append(indent).append(line, start, cut).append('\n');
                start = cut;
                while (start < line.length() && Character.isWhitespace(line.codePointAt(start))) {
                    start += Character.charCount(line.codePointAt(start));
                }
            }
        }
        return out.toString();
    }

    public static String table(List<String> columns, List<List<String>> rows, int width) {
        int count = Math.max(columns.size(), rows.stream().mapToInt(List::size).max().orElse(0));
        if (count == 0 || rows.isEmpty()) return "No rows.";
        int[] sizes = new int[count];
        boolean multiline = false;
        for (int i = 0; i < count; i++) {
            String label = i < columns.size() ? clean(columns.get(i)) : "Value " + (i + 1);
            sizes[i] = cells(label);
            multiline |= label.contains("\n");
            for (var row : rows) {
                if (i < row.size()) {
                    String value = clean(row.get(i));
                    sizes[i] = Math.max(sizes[i], cells(value));
                    multiline |= value.contains("\n");
                }
            }
        }
        boolean grid = !multiline && java.util.Arrays.stream(sizes).sum() + 2 * (count - 1) <= width;
        StringBuilder out = new StringBuilder();
        if (grid) {
            for (int r = -1; r < rows.size(); r++) {
                for (int c = 0; c < count; c++) {
                    String value = r < 0 ? (c < columns.size() ? clean(columns.get(c)) : "Value " + (c + 1))
                            : (c < rows.get(r).size() ? clean(rows.get(r).get(c)) : "");
                    out.append(value);
                    if (c + 1 < count) out.append(" ".repeat(sizes[c] - cells(value) + 2));
                }
                out.append('\n');
            }
        } else {
            for (var row : rows) {
                for (int c = 0; c < count; c++) {
                    String label = c < columns.size() ? clean(columns.get(c)) : "Value " + (c + 1);
                    out.append(entry(label + ":", c < row.size() ? row.get(c) : "", width)).append('\n');
                }
                out.append('\n');
            }
        }
        return out.toString().stripTrailing();
    }

    private static String clean(String text) {
        return Sanitize.stripControl(text == null ? "" : text.replace("\r\n", "\n")
                .replace('\r', '\n')).replace("\t", "    ");
    }

    private static int cells(String text) {
        return text.codePoints().map(cp -> Math.max(0, WCWidth.wcwidth(cp))).sum();
    }
}
