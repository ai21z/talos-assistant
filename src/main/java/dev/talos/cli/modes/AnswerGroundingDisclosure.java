package dev.talos.cli.modes;

import dev.talos.core.util.UiChrome;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.runtime.toolcall.ToolLoopResultSummaryFormatter;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Builds conservative read-set grounding chrome for workspace answer turns. */
final class AnswerGroundingDisclosure {
    private static final int CANDIDATE_CAP = 50;
    private static final int UNREAD_DISPLAY_LIMIT = 3;
    private static final Pattern FILE_TOKEN = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_./\\\\-])([A-Za-z0-9_.\\\\/-]+\\."
                    + "(?:html|htm|css|js|jsx|ts|tsx|java|py|md|txt|json|yaml|yml|xml|"
                    + "properties|gradle|kts|toml|ini|env|csv|pdf|doc|docx|xls|xlsx))"
                    + "(?=$|\\s|[`'\"),;:!?\\]])");

    private AnswerGroundingDisclosure() {
    }

    static String toolLoopSummary(
            ToolCallLoop.LoopResult loopResult,
            CurrentTurnPlan plan,
            Path workspace,
            List<ChatMessage> messages
    ) {
        return ToolLoopResultSummaryFormatter.format(
                loopResult,
                toolLoopDisclosure(loopResult, plan, workspace, messages));
    }

    static String zeroReadWorkspaceNote(CurrentTurnPlan plan) {
        if (!workspaceScoped(plan)) return null;
        return UiChrome.GROUNDING_NOTE_PREFIX + "answered without reading workspace files]";
    }

    static ToolLoopResultSummaryFormatter.GroundingDisclosure toolLoopDisclosure(
            ToolCallLoop.LoopResult loopResult,
            CurrentTurnPlan plan,
            Path workspace,
            List<ChatMessage> messages
    ) {
        if (!workspaceScoped(plan) || loopResult == null || hasMutation(loopResult)) {
            return ToolLoopResultSummaryFormatter.GroundingDisclosure.none();
        }
        Map<String, String> candidates = workspaceCandidateFiles(workspace, loopResult, messages, plan);
        if (candidates.isEmpty()) {
            return ToolLoopResultSummaryFormatter.GroundingDisclosure.none();
        }
        Set<String> read = readFilePathKeys(loopResult);
        List<String> unread = new ArrayList<>();
        int readCandidates = 0;
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            if (read.contains(candidate.getKey())) {
                readCandidates++;
            } else {
                unread.add(candidate.getValue());
            }
        }
        if (unread.isEmpty()) {
            return ToolLoopResultSummaryFormatter.GroundingDisclosure.none();
        }
        String note = readCandidates + " of " + candidates.size()
                + " workspace candidate files read, unread: " + boundedUnread(unread);
        return new ToolLoopResultSummaryFormatter.GroundingDisclosure(note);
    }

    private static boolean workspaceScoped(CurrentTurnPlan plan) {
        TaskContract contract = plan == null ? null : plan.taskContract();
        return contract != null
                && contract.type() == TaskType.WORKSPACE_EXPLAIN
                && contract.expectedTargets().isEmpty()
                && contract.sourceEvidenceTargets().isEmpty();
    }

    private static boolean hasMutation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult.mutatingToolSuccesses() > 0) return true;
        if (loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome != null && outcome.success() && outcome.mutating());
    }

    private static Map<String, String> workspaceCandidateFiles(
            Path workspace,
            ToolCallLoop.LoopResult loopResult,
            List<ChatMessage> messages,
            CurrentTurnPlan plan
    ) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        addTopLevelFiles(out, workspace);
        addEnumeratedFiles(out, loopResult, messages);
        addPromptNamedFiles(out, plan);
        return out;
    }

    private static void addTopLevelFiles(Map<String, String> out, Path workspace) {
        if (workspace == null || out.size() >= CANDIDATE_CAP) return;
        try (Stream<Path> stream = Files.list(workspace)) {
            stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> addCandidate(out, path.getFileName().toString()));
        } catch (IOException | RuntimeException ignored) {
            // Disclosure is best-effort UI chrome. Failure to enumerate must not block the turn.
        }
    }

    private static void addEnumeratedFiles(
            Map<String, String> out,
            ToolCallLoop.LoopResult loopResult,
            List<ChatMessage> messages
    ) {
        if (loopResult == null || loopResult.toolOutcomes() == null || out.size() >= CANDIDATE_CAP) return;
        List<String> bodies = successfulToolBodies(messages, "talos.list_dir");
        int bodyIndex = 0;
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (out.size() >= CANDIDATE_CAP) return;
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.list_dir".equals(outcome.canonicalToolName())) continue;
            if (bodyIndex >= bodies.size()) return;
            String body = bodies.get(bodyIndex++);
            String base = normalizePath(outcome.pathHint());
            for (String line : body.lines().toList()) {
                if (out.size() >= CANDIDATE_CAP) return;
                String entry = line == null ? "" : line.strip();
                if (entry.isBlank()
                        || entry.endsWith("/")
                        || entry.startsWith("[")
                        || entry.startsWith("...")
                        || "(empty directory)".equalsIgnoreCase(entry)) {
                    continue;
                }
                addCandidate(out, joinPath(base, entry));
            }
        }
    }

    private static List<String> successfulToolBodies(List<ChatMessage> messages, String canonicalToolName) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message == null || message.content() == null) continue;
            String content = message.content().strip();
            int prefixStart = content.indexOf("[tool_result:");
            if (prefixStart < 0) continue;
            int prefixEnd = content.indexOf(']', prefixStart);
            if (prefixEnd < 0) continue;
            String rawToolName = content.substring(prefixStart + "[tool_result:".length(), prefixEnd).strip();
            if (!canonicalToolName.equals(canonicalToolName(rawToolName))) continue;
            String body = content.substring(prefixEnd + 1).strip();
            int end = body.indexOf("[/tool_result]");
            if (end >= 0) {
                body = body.substring(0, end).strip();
            }
            if (body.contains("[error]")) continue;
            out.add(body);
        }
        return out;
    }

    private static void addPromptNamedFiles(Map<String, String> out, CurrentTurnPlan plan) {
        if (plan == null || plan.taskContract() == null || out.size() >= CANDIDATE_CAP) return;
        String request = plan.taskContract().originalUserRequest();
        if (request == null || request.isBlank()) return;
        var matcher = FILE_TOKEN.matcher(request);
        while (matcher.find() && out.size() < CANDIDATE_CAP) {
            addCandidate(out, matcher.group(1));
        }
    }

    private static Set<String> readFilePathKeys(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (ToolCallLoop.ToolOutcome outcome : loopResult.toolOutcomes()) {
            if (outcome == null || !outcome.success()) continue;
            if (!"talos.read_file".equals(outcome.canonicalToolName())) continue;
            String path = normalizePath(outcome.pathHint());
            if (!path.isBlank()) out.add(path.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }

    private static void addCandidate(Map<String, String> out, String path) {
        if (out.size() >= CANDIDATE_CAP) return;
        String normalized = normalizePath(path);
        if (normalized.isBlank() || hasHiddenSegment(normalized)) return;
        out.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
    }

    private static String joinPath(String base, String entry) {
        String normalizedEntry = normalizePath(entry);
        if (base == null || base.isBlank() || ".".equals(base)) return normalizedEntry;
        if (normalizedEntry.isBlank()) return base;
        return base + "/" + normalizedEntry;
    }

    private static boolean hasHiddenSegment(String path) {
        for (String segment : path.split("/+")) {
            if (segment.startsWith(".")) return true;
        }
        return false;
    }

    private static String boundedUnread(List<String> unread) {
        int shown = Math.min(UNREAD_DISPLAY_LIMIT, unread.size());
        String joined = String.join(", ", unread.subList(0, shown));
        int remaining = unread.size() - shown;
        if (remaining > 0) {
            joined += ", and " + remaining + " more";
        }
        return joined;
    }

    private static String normalizePath(String path) {
        return ToolCallSupport.normalizePath(path == null ? "" : path.strip());
    }

    private static String canonicalToolName(String toolName) {
        if (toolName == null) return "";
        String lower = toolName.strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith("talos.")) lower = lower.substring("talos.".length());
        return "talos." + lower;
    }
}
