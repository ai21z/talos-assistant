package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.SessionStore;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.trace.LocalTurnTrace;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * /explain-last-turn - render the latest structured turn audit for this workspace.
 */
public final class ExplainLastTurnCommand implements Command {
    private static final int PREVIEW_LIMIT = 240;

    private final Path workspace;
    private final SessionStore store;
    private final String sessionId;
    private final java.time.Instant activeSessionStartedAt;

    public ExplainLastTurnCommand(Path workspace, SessionStore store) {
        this(workspace, store, null);
    }

    public ExplainLastTurnCommand(
            Path workspace,
            SessionStore store,
            java.time.Instant activeSessionStartedAt
    ) {
        this.workspace = workspace == null ? Path.of(".") : workspace;
        this.store = store;
        this.sessionId = JsonSessionStore.sessionIdFor(this.workspace);
        this.activeSessionStartedAt = activeSessionStartedAt;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec(
                "explain-last-turn",
                List.of("explain", "last"),
                "/last [summary|tools|sources|trace|--verbose]",
                "Inspect the latest turn from structured audit data.",
                CommandGroup.DEBUG);
    }

    @Override
    public Result execute(String args, Context ctx) {
        String view = normalizeView(args);
        if (!isSupportedView(view)) return new Result.Error("Usage: /last [summary|tools|sources|trace]", 200);
        if (store == null) {
            return new Result.Info("No session store is available in this process.");
        }

        List<TurnRecord> turns = store.loadTurns(sessionId);
        if (turns == null || turns.isEmpty()) {
            return new Result.Info("No completed turn has been recorded for this workspace yet.");
        }

        List<TurnRecord> activeTurns = filterActiveTurns(turns);
        if (activeTurns.isEmpty() && activeSessionStartedAt != null && !turns.isEmpty()) {
            return new Result.Info(
                    "No completed turn has been recorded in this active process yet. "
                    + "Saved turn history exists for this workspace, but it was not loaded.");
        }

        TurnRecord latest = activeTurns.stream()
                .max(Comparator.comparing(TurnRecord::timestamp)
                        .thenComparingInt(TurnRecord::turnNumber))
                .orElse(null);
        if (latest == null) {
            return new Result.Info("No completed turn has been recorded for this workspace yet.");
        }
        return new Result.TrustedInfo(renderView(latest, view, store, sessionId));
    }

    private List<TurnRecord> filterActiveTurns(List<TurnRecord> turns) {
        if (turns == null || turns.isEmpty()) return List.of();
        if (activeSessionStartedAt == null) return turns;
        return turns.stream()
                .filter(turn -> turn.timestamp() != null)
                .filter(turn -> !turn.timestamp().isBefore(activeSessionStartedAt))
                .toList();
    }

    private static String renderView(TurnRecord latest, String view, SessionStore store, String sessionId) {
        return switch (view) {
            case "tools" -> renderTools(latest);
            case "sources" -> renderSources(latest);
            case "trace" -> renderTrace(latest, loadLocalTrace(store, sessionId, latest));
            default -> render(latest);
        };
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
            if (!call.success() && call.reason() != null && !call.reason().isBlank()) {
                sb.append("      reason: ").append(call.reason()).append('\n');
            }
        }
        }

        if (turn.assistantText() != null && !turn.assistantText().isBlank()) {
            sb.append("\nAssistant Preview\n");
            sb.append("  ").append(preview(turn.assistantText())).append('\n');
        }

        return sb.toString();
    }

    static String renderTools(TurnRecord turn) {
        StringBuilder sb = new StringBuilder();
        sb.append("Last Turn Tools\n\n");
        if (turn.toolCalls().isEmpty()) {
            sb.append("  none\n");
            return sb.toString();
        }
        int index = 1;
        for (TurnRecord.ToolCallSummary call : turn.toolCalls()) {
            sb.append("  ").append(index++).append(". ")
                    .append(blankDefault(call.name(), "(unknown tool)"));
            if (call.pathHint() != null && !call.pathHint().isBlank()) {
                sb.append(" -> ").append(call.pathHint());
            }
            sb.append(call.success() ? " [ok]" : " [failed]").append('\n');
            if (!call.success() && call.reason() != null && !call.reason().isBlank()) {
                sb.append("      reason: ").append(call.reason()).append('\n');
            }
        }
        return sb.toString();
    }

    static String renderSources(TurnRecord turn) {
        StringBuilder sb = new StringBuilder();
        sb.append("Last Turn Sources\n\n");
        if (turn.retrievalTraceSummary() != null && !turn.retrievalTraceSummary().isBlank()) {
            sb.append("  Retrieval: ").append(turn.retrievalTraceSummary()).append('\n');
        } else {
            sb.append("  Retrieval: none recorded\n");
        }

        Set<String> paths = new LinkedHashSet<>();
        for (TurnRecord.ToolCallSummary call : turn.toolCalls()) {
            if (call.pathHint() != null && !call.pathHint().isBlank()) {
                paths.add(call.pathHint());
            }
        }

        sb.append("\n  Tool path hints\n");
        if (paths.isEmpty()) {
            sb.append("  none\n");
        } else {
            for (String path : paths) {
                sb.append("  - ").append(path).append('\n');
            }
        }
        return sb.toString();
    }

    static String renderTrace(TurnRecord turn) {
        return renderTrace(turn, Optional.empty());
    }

    static String renderTrace(TurnRecord turn, Optional<LocalTurnTrace> localTrace) {
        StringBuilder sb = new StringBuilder();
        sb.append(render(turn));
        sb.append("\nTrace Detail\n");
        appendPolicyTrace(sb, turn.policyTrace());
        sb.append("  Retrieval: ").append(blankDefault(turn.retrievalTraceSummary(), "none recorded")).append('\n');
        sb.append("  Tool calls: ").append(turn.toolCalls().size()).append('\n');
        sb.append("  Status tag: ").append(blankDefault(turn.status(), "unknown")).append('\n');
        localTrace.ifPresent(trace -> appendLocalTrace(sb, trace));
        return sb.toString();
    }

    private static Optional<LocalTurnTrace> loadLocalTrace(SessionStore store, String sessionId, TurnRecord turn) {
        if (store == null || sessionId == null || sessionId.isBlank() || turn == null || turn.traceId().isBlank()) {
            return Optional.empty();
        }
        return store.loadTrace(sessionId, turn.traceId());
    }

    private static void appendLocalTrace(StringBuilder sb, LocalTurnTrace trace) {
        sb.append("\nLocal Trace\n");
        sb.append("  Local trace: ").append(trace.traceId()).append('\n');
        sb.append("  Schema: ").append(trace.schemaVersion()).append('\n');
        sb.append("  Redaction: ").append(trace.redaction().mode()).append('\n');
        if (trace.taskContract() != null && !trace.taskContract().type().isBlank()) {
            sb.append("  Task contract: ").append(trace.taskContract().type())
                    .append(" mutationAllowed=").append(trace.taskContract().mutationAllowed())
                    .append(" verificationRequired=").append(trace.taskContract().verificationRequired())
                    .append('\n');
        }
        if (trace.toolSurface() != null) {
            sb.append("  Visible tools: ").append(listOrNone(trace.toolSurface().nativeTools())).append('\n');
        }
        sb.append("  Events: ").append(trace.events().size()).append('\n');
        if (trace.verification() != null && !trace.verification().status().isBlank()) {
            sb.append("  Verification: ").append(trace.verification().status());
            if (!trace.verification().summary().isBlank()) {
                sb.append(" - ").append(trace.verification().summary());
            }
            sb.append('\n');
            for (String problem : trace.verification().problems()) {
                sb.append("    - ").append(problem).append('\n');
            }
        }
        if (trace.outcome() != null && !trace.outcome().status().isBlank()) {
            sb.append("  Outcome: ").append(trace.outcome().status());
            if (!trace.outcome().classification().isBlank()) {
                sb.append(" (").append(trace.outcome().classification()).append(')');
            }
            sb.append('\n');
        }
    }

    private static void appendPolicyTrace(StringBuilder sb, dev.talos.runtime.TurnPolicyTrace trace) {
        if (trace == null || !trace.hasPolicyData()) {
            sb.append("  Policy: none recorded\n");
            return;
        }
        sb.append("  Contract: ").append(trace.taskType())
                .append(" mutationAllowed=").append(trace.mutationAllowed())
                .append(" verificationRequired=").append(trace.verificationRequired())
                .append('\n');
        if (!trace.expectedTargets().isEmpty()) {
            sb.append("  Expected targets: ").append(String.join(", ", trace.expectedTargets())).append('\n');
        }
        if (!trace.forbiddenTargets().isEmpty()) {
            sb.append("  Forbidden targets: ").append(String.join(", ", trace.forbiddenTargets())).append('\n');
        }
        sb.append("  Phase: initial=").append(trace.initialPhase())
                .append(" final=").append(trace.finalPhase())
                .append('\n');
        sb.append("  Native tools: ").append(listOrNone(trace.nativeTools())).append('\n');
        sb.append("  Prompt tools: ").append(listOrNone(trace.promptTools())).append('\n');
        sb.append("  Blocked: ").append(listOrNone(trace.blocks())).append('\n');
    }

    private static String listOrNone(List<String> values) {
        return values == null || values.isEmpty() ? "none" : String.join(", ", values);
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

    private static String normalizeView(String args) {
        String view = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        while (view.startsWith("/")) view = view.substring(1);
        if ("--verbose".equals(view) || "-v".equals(view) || "verbose".equals(view)) {
            return "trace";
        }
        return view.isBlank() ? "summary" : view;
    }

    private static boolean isSupportedView(String view) {
        return "summary".equals(view) || "tools".equals(view) || "sources".equals(view) || "trace".equals(view);
    }
}
