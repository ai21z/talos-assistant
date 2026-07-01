package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.cli.ui.AnsiColor;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRiskLevel;

import java.util.Comparator;
import java.util.List;

/**
 * Lists all registered tools available for LLM invocation.
 *
 * <p>These tools are called by the AI, not typed by the user. The user
 * triggers them through natural language ("read src/Main.java", "create
 * a hello.py file", "search for TODO in the project").
 *
 * <p>Displays tool name, risk level, description, and accepted parameters.
 */
public final class ToolsCommand implements Command {

    /** Word-wrap width for tool descriptions; conservative so the renderer never re-wraps a line. */
    private static final int WRAP_WIDTH = 70;

    /** Hanging indent for a tool's description and params lines. */
    private static final String DESC_INDENT = "      ";

    @Override
    public CommandSpec spec() {
        return new CommandSpec("tools", List.of("t"), "/tools", "List registered tools.", CommandGroup.DEBUG);
    }

    @Override
    public Result execute(String args, Context ctx) {
        var descriptors = ctx.toolRegistry().descriptors();
        if (descriptors.isEmpty()) {
            return new Result.Info("No tools registered.");
        }

        // Sort alphabetically for consistent output
        var sorted = descriptors.stream()
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();

        var sb = new StringBuilder();
        sb.append('\n');

        // ── header ─────────────────────────────────────────────────────
        sb.append("  ")
          .append(AnsiColor.violet("Tools"))
          .append(AnsiColor.grey(" (" + sorted.size() + ")"))
          .append('\n');
        sb.append("  ")
          .append(AnsiColor.dim("The AI calls these automatically when you ask."))
          .append('\n');
        sb.append("  ")
          .append(AnsiColor.dim("Just describe what you need in plain language."))
          .append('\n');
        sb.append('\n');

        // ── tool list ──────────────────────────────────────────────────
        // Each tool is its own block: a short "name  badge" line, then the full
        // description word-wrapped at a fixed width with a hanging indent, then the
        // params. No line overflows the content width, so the answer renderer never
        // re-wraps and mangles the layout (T882).
        for (ToolDescriptor d : sorted) {
            String name = stripPrefix(d.name());

            sb.append("  ")
              .append(AnsiColor.blue(name))
              .append("  ")
              .append(badge(d.riskLevel()))
              .append('\n');

            for (String line : wrap(d.description(), WRAP_WIDTH)) {
                sb.append(DESC_INDENT).append(AnsiColor.grey(line)).append('\n');
            }

            String params = extractParams(d.parametersSchema());
            if (params != null) {
                sb.append(DESC_INDENT).append(AnsiColor.dim(params)).append('\n');
            }

            sb.append('\n');
        }

        // ── footer ─────────────────────────────────────────────────────
        sb.append('\n');
        sb.append("  ")
          .append(AnsiColor.dim("Write-tools require approval before execution."))
          .append('\n');

        // ── examples ───────────────────────────────────────────────────
        sb.append('\n');
        sb.append("  ").append(AnsiColor.grey("Examples:")).append('\n');
        sb.append("    ").append(AnsiColor.dim("\"read src/Main.java\"")).append('\n');
        sb.append("    ").append(AnsiColor.dim("\"create a hello.py with a Flask server\"")).append('\n');
        sb.append("    ").append(AnsiColor.dim("\"search for TODO comments\"")).append('\n');

        return new Result.Ok(sb.toString());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Word-wrap text into lines no longer than {@code width}, breaking on spaces. */
    static java.util.List<String> wrap(String text, int width) {
        var lines = new java.util.ArrayList<String>();
        if (text == null || text.isBlank()) return lines;
        var line = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (line.length() == 0) {
                line.append(word);
            } else if (line.length() + 1 + word.length() <= width) {
                line.append(' ').append(word);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /** Strip "talos." prefix for cleaner display. */
    private static String stripPrefix(String name) {
        return name.startsWith("talos.") ? name.substring(6) : name;
    }

    /** Risk level badge: colored tag before description. */
    private static String badge(ToolRiskLevel risk) {
        if (risk == null || risk == ToolRiskLevel.READ_ONLY) {
            return AnsiColor.green("read");
        }
        if (risk == ToolRiskLevel.WRITE) {
            return AnsiColor.yellow("write");
        }
        return AnsiColor.red("destructive");
    }

    /**
     * Extract a compact parameter summary from the JSON schema.
     * Returns something like "path, max_lines?, offset?" or null.
     */
    static String extractParams(String schema) {
        if (schema == null || schema.isBlank()) return null;

        // Quick extraction: find "properties":{...} keys and "required":[...]
        var props = new java.util.ArrayList<String>();
        var required = new java.util.HashSet<String>();

        // Extract required list
        int reqIdx = schema.indexOf("\"required\"");
        if (reqIdx >= 0) {
            int arrStart = schema.indexOf('[', reqIdx);
            int arrEnd = schema.indexOf(']', arrStart);
            if (arrStart >= 0 && arrEnd >= 0) {
                String arr = schema.substring(arrStart + 1, arrEnd);
                for (String part : arr.split(",")) {
                    String key = part.trim().replace("\"", "");
                    if (!key.isBlank()) required.add(key);
                }
            }
        }

        // Extract property names
        int propIdx = schema.indexOf("\"properties\"");
        if (propIdx >= 0) {
            int braceStart = schema.indexOf('{', propIdx + 12);
            if (braceStart >= 0) {
                // Walk through looking for top-level keys
                int depth = 0;
                int i = braceStart;
                while (i < schema.length()) {
                    char c = schema.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') { depth--; if (depth == 0) break; }
                    else if (c == '"' && depth == 1) {
                        int keyEnd = schema.indexOf('"', i + 1);
                        if (keyEnd > i) {
                            String key = schema.substring(i + 1, keyEnd);
                            if (!key.equals("type") && !key.equals("description")) {
                                props.add(key);
                            }
                        }
                        i = keyEnd;
                    }
                    i++;
                }
            }
        }

        if (props.isEmpty()) return null;

        var sb = new StringBuilder();
        for (int i = 0; i < props.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(props.get(i));
            if (!required.contains(props.get(i))) {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}

