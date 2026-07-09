package dev.talos.cli.tune;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Surgical config edit for {@code talos tune} (T987).
 *
 * <p>Only {@code engines.llama_cpp.server_path}, {@code context},
 * {@code context_reason}, and {@code server_args} change. Every other line,
 * including model identity and custom sections, is preserved byte for byte,
 * so the previewed diff is exactly what lands on disk.
 */
public final class TuneConfigEditor {

    private static final Pattern PORT_LINE = Pattern.compile("^\\s*port:\\s*(\\d+)\\s*$");

    private TuneConfigEditor() {}

    public record Edit(String updatedYaml, String diff) {}

    public static Edit propose(String yaml, Path newServerPath, int context, String contextReason) {
        List<String> oldLines = yaml.lines().toList();
        List<String> newLines = new ArrayList<>(oldLines.size() + 2);

        boolean inLlamaBlock = false;
        int llamaIndent = -1;
        boolean sawContextReason = false;
        int contextLineIndexInNew = -1;
        String keyIndent = "    ";
        int skipListItemsDeeperThan = -1;

        for (String line : oldLines) {
            int indent = leadingSpaces(line);
            String trimmed = line.trim();

            if (skipListItemsDeeperThan >= 0) {
                if (!trimmed.isEmpty() && indent > skipListItemsDeeperThan) {
                    continue;
                }
                skipListItemsDeeperThan = -1;
            }

            if (!line.isBlank() && indent == 0) {
                inLlamaBlock = false;
            }
            if (trimmed.equals("llama_cpp:")) {
                inLlamaBlock = true;
                llamaIndent = indent;
                newLines.add(line);
                continue;
            }
            if (inLlamaBlock && !line.isBlank() && indent <= llamaIndent) {
                inLlamaBlock = false;
            }

            if (inLlamaBlock && !line.isBlank()) {
                keyIndent = " ".repeat(indent);
                if (trimmed.startsWith("server_path:")) {
                    newLines.add(keyIndent + "server_path: \"" + yamlPath(newServerPath) + "\"");
                    continue;
                }
                if (trimmed.startsWith("context_reason:")) {
                    sawContextReason = true;
                    newLines.add(keyIndent + "context_reason: \"" + contextReason + "\"");
                    continue;
                }
                if (trimmed.startsWith("context:")) {
                    newLines.add(keyIndent + "context: " + context);
                    contextLineIndexInNew = newLines.size() - 1;
                    continue;
                }
                if (trimmed.startsWith("server_args:")) {
                    newLines.add(keyIndent + "server_args: []");
                    skipListItemsDeeperThan = indent;
                    continue;
                }
            }
            newLines.add(line);
        }

        if (!sawContextReason && contextLineIndexInNew >= 0) {
            newLines.add(contextLineIndexInNew + 1,
                    keyIndent + "context_reason: \"" + contextReason + "\"");
        }

        String updated = String.join("\n", newLines) + (yaml.endsWith("\n") ? "\n" : "");
        return new Edit(updated, renderDiff(oldLines, newLines));
    }

    /** First {@code port:} inside the llama_cpp block, or the fallback. */
    public static int configuredPort(String yaml, int fallback) {
        boolean inLlamaBlock = false;
        int llamaIndent = -1;
        for (String line : yaml.lines().toList()) {
            int indent = leadingSpaces(line);
            String trimmed = line.trim();
            if (trimmed.equals("llama_cpp:")) {
                inLlamaBlock = true;
                llamaIndent = indent;
                continue;
            }
            if (inLlamaBlock && !line.isBlank() && indent <= llamaIndent) {
                inLlamaBlock = false;
            }
            if (inLlamaBlock) {
                Matcher matcher = PORT_LINE.matcher(line);
                if (matcher.matches()) {
                    try {
                        return Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException ignored) {
                        return fallback;
                    }
                }
            }
        }
        return fallback;
    }

    private static String renderDiff(List<String> oldLines, List<String> newLines) {
        Map<String, Integer> newCounts = counts(newLines);
        Map<String, Integer> oldCounts = counts(oldLines);
        StringBuilder diff = new StringBuilder();
        for (String line : oldLines) {
            int remaining = newCounts.getOrDefault(line, 0);
            if (remaining > 0) {
                newCounts.put(line, remaining - 1);
            } else {
                diff.append("- ").append(line).append('\n');
            }
        }
        for (String line : newLines) {
            int remaining = oldCounts.getOrDefault(line, 0);
            if (remaining > 0) {
                oldCounts.put(line, remaining - 1);
            } else {
                diff.append("+ ").append(line).append('\n');
            }
        }
        return diff.toString();
    }

    private static Map<String, Integer> counts(List<String> lines) {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : lines) {
            counts.merge(line, 1, Integer::sum);
        }
        return counts;
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static String yamlPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
