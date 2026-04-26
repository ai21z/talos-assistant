package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.TurnRecord;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /explain-last-turn - render the latest structured turn audit for this workspace.
 */
public final class ExplainLastTurnCommand implements Command {
    private static final int PREVIEW_LIMIT = 240;

    private final Path workspace;
    private final SessionStore store;
    private final String sessionId;

    public ExplainLastTurnCommand(Path workspace, SessionStore store) {
        this.workspace = workspace == null ? Path.of(".") : workspace;
        this.store = store;
        this.sessionId = JsonSessionStore.sessionIdFor(this.workspace);
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec(
                "explain-last-turn",
                List.of("explain"),
                "/explain-last-turn",
                "Explain the latest turn from structured audit data.",
                CommandGroup.DEBUG);
    }

    @Override
    public Result execute(String args, Context ctx) {
        if (args != null && !args.isBlank()) {
            return new Result.Error("Usage: /explain-last-turn", 200);
        }
        if (store == null) {
            return new Result.Info("No session store is available in this process.");
        }

        List<TurnRecord> turns = store.loadTurns(sessionId);
        if (turns == null || turns.isEmpty()) {
            return new Result.Info("No completed turn has been recorded for this workspace yet.");
        }

        TurnRecord latest = turns.stream()
                .max(Comparator.comparingInt(TurnRecord::turnNumber))
                .orElse(null);
        if (latest == null) {
            return new Result.Info("No completed turn has been recorded for this workspace yet.");
        }
        return new Result.TrustedInfo(render(latest));
    }

    static String render(TurnRecord turn) {
        StringBuilder sb = new StringBuilder();
        sb.append("Last Turn\n\n");
        sb.append("  Turn:      ").append(turn.turnNumber()).append('\n');
        sb.append("  Status:    ").append(blankDefault(turn.status(), "unknown")).append('\n');
        sb.append("  Outcome:   ").append(inferOutcome(turn)).append('\n');
        sb.append("  Duration:  ").append(turn.durationMs()).append("ms\n");
        sb.append("  Approvals: required=").append(turn.approvalsRequired())
                .append(" granted=").append(turn.approvalsGranted())
                .append(" denied=").append(turn.approvalsDenied())
                .append("\n");

        if (turn.retrievalTraceSummary() != null && !turn.retrievalTraceSummary().isBlank()) {
            sb.append("  Retrieval: ").append(turn.retrievalTraceSummary()).append('\n');
        }

        sb.append("\nUser Request\n");
        sb.append("  ").append(preview(turn.userInput())).append("\n");

        sb.append("\nTools\n");
        if (turn.toolCalls().isEmpty()) {
            sb.append("  none\n");
        } else {
            for (TurnRecord.ToolCallSummary call : turn.toolCalls()) {
                sb.append("  - ").append(blankDefault(call.name(), "(unknown tool)"));
                if (call.pathHint() != null && !call.pathHint().isBlank()) {
                    sb.append(" -> ").append(call.pathHint());
                }
                sb.append(call.success() ? " [ok]" : " [failed]").append('\n');
            }
        }

        if (turn.assistantText() != null && !turn.assistantText().isBlank()) {
            sb.append("\nAssistant Preview\n");
            sb.append("  ").append(preview(turn.assistantText())).append('\n');
        }

        return sb.toString();
    }

    static String inferOutcome(TurnRecord turn) {
        if (turn == null) return "UNKNOWN";
        String status = turn.status() == null ? "" : turn.status().toLowerCase(Locale.ROOT);
        if ("error".equals(status)) return "ERROR";
        if ("aborted".equals(status)) return "ABORTED";
        if ("info".equals(status)) return "INFO_ONLY";
        if ("stream".equals(status)) return "STREAM_EVENT";
        if (turn.approvalsDenied() > 0) return "BLOCKED_BY_APPROVAL";

        long mutatingSuccesses = turn.toolCalls().stream()
                .filter(call -> isMutatingTool(call.name()))
                .filter(TurnRecord.ToolCallSummary::success)
                .count();
        long mutatingFailures = turn.toolCalls().stream()
                .filter(call -> isMutatingTool(call.name()))
                .filter(call -> !call.success())
                .count();
        long failures = turn.toolCalls().stream()
                .filter(call -> !call.success())
                .count();

        if (mutatingSuccesses > 0 && failures > 0) return "PARTIAL_MUTATION";
        if (mutatingSuccesses > 0) return "MUTATION_APPLIED";
        if (mutatingFailures > 0) return "FAILED_OR_BLOCKED_MUTATION";
        if (!turn.toolCalls().isEmpty()) return "INSPECTION_RECORDED";
        if ("ok".equals(status)) return "NO_TOOL_RESPONSE";
        return "UNKNOWN";
    }

    static boolean isMutatingTool(String name) {
        if (name == null) return false;
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("write_file")
                || normalized.equals("edit_file")
                || normalized.endsWith(".write_file")
                || normalized.endsWith(".edit_file");
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) return "(blank)";
        String oneLine = text.replace('\r', ' ').replace('\n', ' ').strip();
        if (oneLine.length() <= PREVIEW_LIMIT) return oneLine;
        return oneLine.substring(0, PREVIEW_LIMIT - 3) + "...";
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
