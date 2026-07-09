package dev.talos.cli.tune;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Surgical config edit for {@code talos tune} (T987).
 *
 * <p>Only {@code engines.llama_cpp.server_path}, {@code context},
 * {@code context_reason}, and {@code server_args} change. Keys the proposal
 * needs that are missing from the block (legacy configs written before the
 * context selector existed) are inserted after {@code port:}. Every other
 * line is preserved, so the previewed diff is exactly what lands on disk,
 * and {@link #appliesProposal} verifies the semantic outcome after parsing
 * the edited YAML rather than trusting the line editor.
 */
public final class TuneConfigEditor {

    private static final Pattern PORT_LINE = Pattern.compile("^\\s*port:\\s*(\\d+)\\s*$");

    private TuneConfigEditor() {}

    public record Edit(String updatedYaml, String diff) {}

    public static Edit propose(String yaml, Path newServerPath, int context, String contextReason) {
        List<String> oldLines = yaml.lines().toList();
        List<String> newLines = new ArrayList<>(oldLines.size() + 3);

        boolean inLlamaBlock = false;
        int llamaIndent = -1;
        int llamaHeaderIdx = -1;
        boolean sawContext = false;
        boolean sawContextReason = false;
        boolean sawServerArgs = false;
        int contextIdx = -1;
        int portIdx = -1;
        int serverPathIdx = -1;
        String keyIndent = null;
        int skipListItemsDeeperThan = -1;
        int pendingBlanksInSkip = 0;

        for (String line : oldLines) {
            int indent = leadingSpaces(line);
            String trimmed = line.trim();

            if (skipListItemsDeeperThan >= 0) {
                if (trimmed.isEmpty()) {
                    // Blank lines inside the replaced list are held back: they
                    // are dropped if more list items follow, kept as section
                    // spacing if the list ends here.
                    pendingBlanksInSkip++;
                    continue;
                }
                if (indent > skipListItemsDeeperThan) {
                    pendingBlanksInSkip = 0;
                    continue;
                }
                for (int i = 0; i < pendingBlanksInSkip; i++) {
                    newLines.add("");
                }
                pendingBlanksInSkip = 0;
                skipListItemsDeeperThan = -1;
            }

            if (!line.isBlank() && indent == 0) {
                inLlamaBlock = false;
            }
            if (trimmed.equals("llama_cpp:")) {
                inLlamaBlock = true;
                llamaIndent = indent;
                newLines.add(line);
                llamaHeaderIdx = newLines.size() - 1;
                continue;
            }
            if (inLlamaBlock && !line.isBlank() && indent <= llamaIndent) {
                inLlamaBlock = false;
            }

            if (inLlamaBlock && !line.isBlank()) {
                keyIndent = " ".repeat(indent);
                if (trimmed.startsWith("server_path:")) {
                    newLines.add(keyIndent + "server_path: \"" + yamlPath(newServerPath) + "\"");
                    serverPathIdx = newLines.size() - 1;
                    continue;
                }
                if (trimmed.startsWith("context_reason:")) {
                    sawContextReason = true;
                    newLines.add(keyIndent + "context_reason: \"" + contextReason + "\"");
                    continue;
                }
                if (trimmed.startsWith("context:")) {
                    sawContext = true;
                    newLines.add(keyIndent + "context: " + context);
                    contextIdx = newLines.size() - 1;
                    continue;
                }
                if (trimmed.startsWith("port:")) {
                    newLines.add(line);
                    portIdx = newLines.size() - 1;
                    continue;
                }
                if (trimmed.startsWith("server_args:")) {
                    sawServerArgs = true;
                    newLines.add(keyIndent + "server_args: []");
                    skipListItemsDeeperThan = indent;
                    continue;
                }
            }
            newLines.add(line);
        }
        for (int i = 0; i < pendingBlanksInSkip; i++) {
            newLines.add("");
        }

        String insertIndent = keyIndent != null
                ? keyIndent
                : " ".repeat(llamaIndent >= 0 ? llamaIndent + 2 : 4);
        if (!sawContext && llamaHeaderIdx >= 0) {
            int anchor = portIdx >= 0 ? portIdx : (serverPathIdx >= 0 ? serverPathIdx : llamaHeaderIdx);
            contextIdx = anchor + 1;
            newLines.add(contextIdx, insertIndent + "context: " + context);
        }
        int insertCursor = contextIdx;
        if (!sawContextReason && contextIdx >= 0) {
            insertCursor = contextIdx + 1;
            newLines.add(insertCursor, insertIndent + "context_reason: \"" + contextReason + "\"");
        }
        if (!sawServerArgs && insertCursor >= 0) {
            newLines.add(insertCursor + 1, insertIndent + "server_args: []");
        }

        String updated = String.join("\n", newLines) + (yaml.endsWith("\n") ? "\n" : "");
        return new Edit(updated, renderDiff(oldLines, newLines));
    }

    /**
     * Semantic check that the proposal actually landed: parses the YAML and
     * compares the exact proposed server_path, context, context_reason, and
     * empty server_args. Used both to validate an edit before it is written
     * and to keep the "already matches" claim honest.
     */
    public static boolean appliesProposal(String yaml, Path serverPath, int context, String contextReason) {
        Map<?, ?> block = llamaBlock(yaml);
        if (block == null) {
            return false;
        }
        if (!yamlPath(serverPath).equals(Objects.toString(block.get("server_path"), ""))) {
            return false;
        }
        if (!(block.get("context") instanceof Number configured) || configured.intValue() != context) {
            return false;
        }
        if (!Objects.toString(contextReason, "").equals(Objects.toString(block.get("context_reason"), ""))) {
            return false;
        }
        return block.get("server_args") instanceof List<?> args && args.isEmpty();
    }

    /**
     * Empty when tune can safely edit this config; otherwise the reason it
     * cannot. Checked before any install offer or write.
     */
    public static String editableReason(String yaml) {
        Map<?, ?> block = llamaBlock(yaml);
        if (block == null) {
            return "the config has no engines.llama_cpp block";
        }
        String mode = Objects.toString(block.get("mode"), "managed")
                .trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if ("connect_only".equals(mode)) {
            return "engines.llama_cpp.mode is connect-only and tune manages only the managed lane";
        }
        if (!block.containsKey("server_path")) {
            return "engines.llama_cpp has no server_path key";
        }
        return "";
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

    private static Map<?, ?> llamaBlock(String yaml) {
        try {
            Object parsed = new ObjectMapper(new YAMLFactory()).readValue(yaml, Object.class);
            if (parsed instanceof Map<?, ?> root
                    && root.get("engines") instanceof Map<?, ?> engines
                    && engines.get("llama_cpp") instanceof Map<?, ?> block) {
                return block;
            }
        } catch (Exception ignored) {
            // unparseable configs are not editable
        }
        return null;
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
