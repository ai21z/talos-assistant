package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.AtFilePins;
import dev.talos.cli.repl.Context;
import dev.talos.cli.ui.TerminalWidths;
import dev.talos.core.CfgUtil;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.core.context.ContextMeter;
import dev.talos.runtime.Result;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntSupplier;

/**
 * /context — show context-window usage (T803).
 *
 * <p>Renders the {@link ContextMeter} read model: an honest, estimate-
 * labeled view of what occupies the window, both mode budgets, the
 * auto-compaction rule, the last compaction attempt, the prompt's
 * pinned bytes, and the engine-side context size — including the
 * silent divergence between {@code limits.llm_context_max_tokens} and
 * {@code engines.llama_cpp.context}, which this command surfaces but
 * does not reconcile.
 */
public final class ContextCommand implements Command {

    private static final int DEFAULT_WIDTH = 80;

    private final IntSupplier terminalWidth;
    private final boolean assistModeCompaction;

    /**
     * @param terminalWidth        live terminal width, null when scripted
     * @param assistModeCompaction the single bootstrap compaction-mode flag —
     *                             the same value feeds MemoryUpdateListener
     *                             and /compact, so the three can never drift
     */
    public ContextCommand(IntSupplier terminalWidth, boolean assistModeCompaction) {
        this.terminalWidth = terminalWidth;
        this.assistModeCompaction = assistModeCompaction;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec(
                "context",
                List.of(),
                "/context",
                "Show context-window usage and compaction state.",
                CommandGroup.SESSION);
    }

    @Override
    public Result execute(String args, Context ctx) {
        if (ctx == null || ctx.conversationManager() == null) {
            return new Result.Error("No conversation context is available in this process.", 200);
        }
        ContextMeter active = ctx.conversationManager().meter(assistModeCompaction);
        ContextMeter other = ctx.conversationManager().meter(!assistModeCompaction);
        int width = TerminalWidths.resolve(
                terminalWidth,
                terminalWidth != null ? System.getenv() : Map.of(),
                DEFAULT_WIDTH);

        StringBuilder sb = new StringBuilder();
        sb.append("Context window\n\n");
        sb.append("  History:     ~").append(num(active.historyTokensEstimate()))
                .append(" / ").append(num(active.historyBudgetTokens()))
                .append(" tokens (est.)  ")
                .append(meterBar(active.historyTokensEstimate(), active.historyBudgetTokens(), width))
                .append('\n');
        sb.append("  Max context: ").append(num(active.contextMaxTokens()))
                .append(" tokens (limits.llm_context_max_tokens)\n");
        sb.append("  Reserve:     ").append(percent(active.responseReserveFraction()))
                .append(" of the window held for the response, ")
                .append(num(active.overheadTokens())).append(" tokens structural overhead\n");
        sb.append("  Budgets:     ").append(budgets(active, other)).append('\n');
        sb.append("  Turns:       ").append(active.turnPairs())
                .append(active.turnPairs() == 1 ? " exchange" : " exchanges").append('\n');
        sb.append("  Sketch:      ").append(sketch(active)).append('\n');
        sb.append("  Pinned:      ").append(pinned()).append('\n');
        sb.append("  Compaction:  auto past ").append(active.compactionThresholdPairs())
                .append(" exchanges when history exceeds the budget\n");
        sb.append("  Last:        ").append(active.lastCompaction().renderCompact()).append('\n');
        engineRow(ctx, active).ifPresent(row -> sb.append("  Engine:      ").append(row).append('\n'));
        sb.append("\n  (token figures are estimates, chars/4)");
        return new Result.TrustedInfo(sb.toString());
    }

    // ── Rows ─────────────────────────────────────────────────────────────

    /** ASCII usage bar — PTY-safe, scaled to the live terminal width. */
    static String meterBar(int used, int budget, int terminalWidth) {
        int barWidth = Math.max(10, Math.min(40, terminalWidth - 50));
        int safeBudget = Math.max(1, budget);
        double ratio = Math.min(1.0, used / (double) safeBudget);
        int filled = (int) Math.round(ratio * barWidth);
        return "[" + "#".repeat(filled) + "-".repeat(barWidth - filled) + "] "
                + Math.round(ratio * 100) + "%";
    }

    private String budgets(ContextMeter active, ContextMeter other) {
        String activeName = assistModeCompaction ? "assist" : "rag";
        String otherName = assistModeCompaction ? "rag" : "assist";
        return activeName + " ~" + num(active.historyBudgetTokens()) + " tokens (active) - "
                + otherName + " ~" + num(other.historyBudgetTokens()) + " tokens";
    }

    private static String sketch(ContextMeter meter) {
        if (!meter.hasSketch()) return "none";
        return "~" + num(meter.sketchChars() / 4) + " tokens ("
                + num(meter.sketchChars()) + " chars of compacted history)";
    }

    /** T802 counter: what the last prompt actually pinned. */
    private static String pinned() {
        AtFilePins.Resolution last = AtFilePins.lastResolution();
        if (last.pins().isEmpty()) return "none this prompt";
        return last.pins().size() + (last.pins().size() == 1 ? " file, ~" : " files, ~")
                + num(last.pinnedChars() / 4) + " tokens ("
                + num(last.pinnedChars()) + " chars)";
    }

    /**
     * The engine-side context row. llama.cpp is sized independently by
     * {@code engines.llama_cpp.context} — when the two keys diverge, say
     * so and name the unsafe direction. Reconciliation is deliberately
     * out of scope (deferred); this row makes the divergence visible.
     */
    private Optional<String> engineRow(Context ctx, ContextMeter meter) {
        Optional<String> live = liveEngineRow(ctx);
        if (live.isPresent()) {
            return live;
        }

        String backend = EngineRuntimeConfig.from(ctx.cfg()).backend();
        if ("ollama".equals(backend)) {
            return Optional.of("context managed by Ollama");
        }
        if (!"llama_cpp".equals(backend)) {
            return Optional.empty();
        }
        return Optional.of(renderLlamaCppConfigRow(ctx, meter, "engines.llama_cpp.context"));
    }

    private Optional<String> liveEngineRow(Context ctx) {
        if (ctx == null || ctx.llm() == null) {
            return Optional.empty();
        }
        var diagnostics = ctx.llm().contextWindowDiagnostics();
        String activeModel = Objects.toString(diagnostics.model(), "").trim();
        int slash = activeModel.indexOf('/');
        if (slash <= 0 || slash == activeModel.length() - 1) {
            return Optional.empty();
        }
        String backend = activeModel.substring(0, slash);
        if ("ollama".equals(backend)) {
            return Optional.of(renderActiveOllamaRow(activeModel, diagnostics));
        }
        if ("llama_cpp".equals(backend)) {
            return Optional.of(renderActiveLlamaCppRow(ctx, diagnostics, activeModel));
        }
        return Optional.of(activeModel + " effective context "
                + num(diagnostics.effectiveWindowTokens())
                + " tokens (active model; limits.llm_context_max_tokens="
                + num(diagnostics.configuredWindowTokens()) + ")");
    }

    private static String renderActiveOllamaRow(
            String activeModel,
            dev.talos.core.llm.LlmClient.ContextWindowDiagnostics diagnostics) {
        StringBuilder row = new StringBuilder(activeModel)
                .append(" effective context ")
                .append(num(diagnostics.effectiveWindowTokens()))
                .append(" tokens (active model; limits.llm_context_max_tokens=")
                .append(num(diagnostics.configuredWindowTokens()));
        if (diagnostics.engineWindowTokens() > 0) {
            row.append("; engine-reported context=")
                    .append(num(diagnostics.engineWindowTokens()));
        } else {
            row.append("; engine context unavailable");
        }
        row.append(')');
        if (diagnostics.engineWindowTokens() > 0
                && diagnostics.effectiveWindowTokens() < diagnostics.configuredWindowTokens()) {
            row.append("\n               WARNING: runtime enforces the smaller active model window");
        }
        return row.toString();
    }

    private String renderActiveLlamaCppRow(
            Context ctx,
            dev.talos.core.llm.LlmClient.ContextWindowDiagnostics diagnostics,
            String activeModel) {
        int configured = diagnostics.configuredWindowTokens();
        int engine = diagnostics.engineWindowTokens() > 0
                ? diagnostics.engineWindowTokens()
                : configuredLlamaCppContext(ctx);
        StringBuilder row = new StringBuilder("llama.cpp context ")
                .append(num(engine))
                .append(" tokens (active model ")
                .append(activeModel)
                .append("; engines.llama_cpp.context)");
        if (engine < configured) {
            row.append("\n               WARNING: smaller than limits.llm_context_max_tokens - ")
                    .append("the budget assumes more room than the engine provides (overflow risk)");
        } else if (engine > configured) {
            row.append("\n               note: larger than limits.llm_context_max_tokens - ")
                    .append("safe, but the extra engine context goes unused");
        }
        return row.toString();
    }

    private static String renderLlamaCppConfigRow(Context ctx, ContextMeter meter, String sourceLabel) {
        long engineContext = configuredLlamaCppContext(ctx);
        StringBuilder row = new StringBuilder("llama.cpp context ").append(num((int) engineContext))
                .append(" tokens (").append(sourceLabel).append(")");
        if (engineContext < meter.contextMaxTokens()) {
            row.append("\n               WARNING: smaller than limits.llm_context_max_tokens - ")
                    .append("the budget assumes more room than the engine provides (overflow risk)");
        } else if (engineContext > meter.contextMaxTokens()) {
            row.append("\n               note: larger than limits.llm_context_max_tokens - ")
                    .append("safe, but the extra engine context goes unused");
        }
        return row.toString();
    }

    private static int configuredLlamaCppContext(Context ctx) {
        var engines = CfgUtil.map(ctx.cfg().data.get("engines"));
        var llamaCpp = CfgUtil.map(engines.get("llama_cpp"));
        return (int) CfgUtil.longAt(llamaCpp, "context", 8192L);
    }

    private static String num(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }
}
