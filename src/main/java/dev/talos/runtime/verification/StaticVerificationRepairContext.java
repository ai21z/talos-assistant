package dev.talos.runtime.verification;

import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.toolcall.ToolCallSupport;
import dev.talos.spi.types.ChatMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Extracts a narrow repair checklist from the previous static verification
 * failure so the next repair turn can use verifier findings as first-class
 * context without adding a planner.
 */
public final class StaticVerificationRepairContext {

    private StaticVerificationRepairContext() {}

    public static Optional<String> instructionFor(
            List<ChatMessage> messages,
            TaskContract contract
    ) {
        if (messages == null || messages.isEmpty()) return Optional.empty();
        if (contract == null || !contract.mutationAllowed()) return Optional.empty();
        if (!looksLikeRepairContinuation(latestUserRequest(messages))) return Optional.empty();

        String previous = previousStaticVerificationFailure(messages);
        if (previous == null || previous.isBlank()) return Optional.empty();

        List<String> problems = extractProblemBullets(previous);
        String expectedTargets = expectedTargets(contract);
        StringBuilder out = new StringBuilder();
        out.append("[Static verification repair context]\n")
                .append("The previous mutation task ended incomplete after static verification. ")
                .append("Use the prior verifier findings as the repair checklist for this turn.\n\n")
                .append("Expected targets: ").append(expectedTargets).append("\n\n");

        if (problems.isEmpty()) {
            out.append("Previous static verification problem summary:\n")
                    .append("- ").append(firstStaticFailureLine(previous)).append("\n\n");
        } else {
            out.append("Previous static verification problems:\n");
            for (String problem : problems.subList(0, Math.min(8, problems.size()))) {
                out.append("- ").append(problem).append("\n");
            }
            if (problems.size() > 8) {
                out.append("- ... ").append(problems.size() - 8).append(" more\n");
            }
            out.append("\n");
        }

        out.append("For small HTML/CSS/JS files, prefer talos.write_file with complete corrected file content ")
                .append("when exact talos.edit_file old_string matching would be brittle. ")
                .append("Do not repeat an edit_file old_string that already failed. ")
                .append("After tool-backed changes, answer only from tool results and static verification.");
        return Optional.of(out.toString());
    }

    private static boolean looksLikeRepairContinuation(String userRequest) {
        if (userRequest == null || userRequest.isBlank()) return false;
        String lower = userRequest.toLowerCase(Locale.ROOT);
        return lower.contains("fix")
                || lower.contains("repair")
                || lower.contains("remaining")
                || lower.contains("try again")
                || lower.contains("try one more time")
                || lower.contains("complete")
                || lower.contains("finish")
                || lower.contains("make it work")
                || lower.contains("still does not work")
                || lower.contains("still doesn't work")
                || lower.contains("nothing changed")
                || lower.contains("nothing happened")
                || lower.contains("overwrite")
                || lower.contains("write_file");
    }

    private static String latestUserRequest(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"user".equals(message.role())) continue;
            String content = message.content();
            if (ToolCallSupport.isSyntheticToolResultContent(content)) continue;
            return content == null || content.isBlank() ? null : content;
        }
        return null;
    }

    private static String previousStaticVerificationFailure(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message == null || !"assistant".equals(message.role())) continue;
            String content = message.content();
            if (looksLikeStaticVerificationFailure(content)) {
                return content;
            }
        }
        return null;
    }

    private static boolean looksLikeStaticVerificationFailure(String value) {
        if (value == null || value.isBlank()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("static verification failed")
                || lower.contains("partial verification")
                || lower.contains("remaining static verification problems")
                || lower.contains("unresolved static verification problems")
                || lower.contains("task incomplete");
    }

    private static List<String> extractProblemBullets(String previous) {
        if (previous == null || previous.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        boolean inProblems = false;
        for (String rawLine : previous.split("\\R")) {
            String line = rawLine == null ? "" : rawLine.strip();
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("remaining static verification problems")
                    || lower.contains("unresolved static verification problems")) {
                inProblems = true;
                continue;
            }
            if (!inProblems) continue;
            if (line.isBlank()) {
                if (!out.isEmpty()) break;
                continue;
            }
            if (line.startsWith("-")) {
                String problem = line.substring(1).strip();
                if (!problem.isBlank()) {
                    out.add(singleLine(problem));
                }
                continue;
            }
            if (!out.isEmpty()) break;
        }
        return List.copyOf(out);
    }

    private static String expectedTargets(TaskContract contract) {
        if (contract == null || contract.expectedTargets().isEmpty()) {
            return "(not available from current task contract)";
        }
        return contract.expectedTargets().stream()
                .sorted(Comparator.naturalOrder())
                .reduce((left, right) -> left + ", " + right)
                .orElse("(not available from current task contract)");
    }

    private static String firstStaticFailureLine(String previous) {
        if (previous == null || previous.isBlank()) return "Static verification failed.";
        for (String rawLine : previous.split("\\R")) {
            String line = singleLine(rawLine);
            if (line.isBlank()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("static verification")
                    || lower.contains("task incomplete")
                    || lower.contains("not verified complete")) {
                return line;
            }
        }
        return "Static verification failed.";
    }

    private static String singleLine(String value) {
        if (value == null) return "";
        String line = value.replace('\n', ' ').replace('\r', ' ').strip();
        return line.length() <= 300 ? line : line.substring(0, 297) + "...";
    }
}
