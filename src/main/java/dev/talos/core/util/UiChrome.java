package dev.talos.core.util;

/**
 * Talos-emitted status-line ("chrome") prefixes shared between the emitters
 * and the conversation-history stripper (T767).
 *
 * <p>The runtime decorates assistant text with status lines - tool-usage
 * summaries, mutation confirmations, abort/engine-error sentinels. Those
 * lines must never reach conversation history: code-tuned local models
 * memorize the format after one exposure and start emitting fake status
 * lines as prose, convincingly claiming work that never happened (the
 * documented BUG #1 confidence-trick failure mode, observed on
 * qwen2.5-coder:14b). {@code MemoryUpdateListener.stripUiChromeForHistory}
 * strips them by whole-line prefix before persisting.
 *
 * <p>Emitters and stripper previously each retyped these prefixes; a
 * rewording on either side silently broke stripping. Both sides now compose
 * from this class, so they cannot drift. This class lives in {@code core.util}
 * because the emitters span four layers (core, runtime, cli, tools-adjacent)
 * and core is the only package they may all reference under the architecture
 * boundary rules.
 *
 * <p>Prefixes are deliberately narrow whole-line anchors; message tails stay
 * free to change at the emitter. {@code UiChromeContractTest} round-trips
 * every emitter shape through the stripper.
 */
public final class UiChrome {

    /** Tool-loop telemetry summary: {@code [Used N tool(s): ... | M iteration(s)]}. */
    public static final String TOOL_SUMMARY_OPEN = "[Used ";

    /** Second anchor for the tool summary (guards against bare "[Used" prose). */
    public static final String TOOL_SUMMARY_MARKER = "tool(s)";

    /** Read-set suffix inside the tool summary line. */
    public static final String TOOL_SUMMARY_READ_PREFIX = " | read: ";

    /** Workspace grounding note line for no-tool workspace answers. */
    public static final String GROUNDING_NOTE_PREFIX = "[Grounding: ";

    /** Tool-call budget notice: {@code [Tool-call limit reached. ...]}. */
    public static final String TOOL_CALL_LIMIT_PREFIX = "[Tool-call limit reached";

    /** Provider output budget notice: {@code [Model output limit reached: ...]}. */
    public static final String OUTPUT_LIMIT_PREFIX = "[Model output limit reached";

    /** LLM wall-clock/idle/repetition/interrupt abort sentinel. */
    public static final String TURN_ABORTED_PREFIX = "[turn aborted";

    /** Iteration-limit marker appended to the tool summary. */
    public static final String ITERATION_LIMIT_PREFIX = "[iteration limit";

    /** Engine failure wrapper: {@code [Engine error...]}. */
    public static final String ENGINE_ERROR_PREFIX = "[Engine error";

    /** Model-not-found wrapper opening: {@code [Model '<id>' not found...]}. */
    public static final String MODEL_NOT_FOUND_OPEN = "[Model '";

    /** Second anchor of the model-not-found wrapper. */
    public static final String MODEL_NOT_FOUND_MARKER = "' not found";

    /** Mutation-success confirmation prefix composed with tool output. */
    public static final String CHECK_PREFIX = "✓ ";

    /** {@code "✓ " + FileEditTool} output ("Edited <path>: ..."). */
    public static final String EDITED_PREFIX = CHECK_PREFIX + "Edited ";

    /**
     * Defensive stripper entry with no current emitter (T768): no tool
     * produces "Wrote ...", but a line shaped {@code ✓ Wrote ...} can only
     * be chrome or a model imitating chrome - neither belongs in history.
     */
    public static final String WROTE_PREFIX = CHECK_PREFIX + "Wrote ";

    /** {@code "✓ " + FileWriteTool/MakeDirectoryTool} output ("Created ..."). */
    public static final String CREATED_PREFIX = CHECK_PREFIX + "Created ";

    /** {@code "✓ " + FileWriteTool} overwrite output ("Updated <path> (...)"), T768. */
    public static final String UPDATED_PREFIX = CHECK_PREFIX + "Updated ";

    /** Repeated-edit-failure cushion suggestion injected into tool errors. */
    public static final String EDIT_FAILURE_SUGGESTION_PREFIX = "Suggestion: edit_file has failed";

    /**
     * Auto-compaction notice (T805): {@code [context compacted: N older
     * exchanges summarized · M kept verbatim]}. Render-side only - the
     * emitter is RenderEngine, after turn stats - but stripped
     * defensively like all chrome: a model imitating the line must not
     * seed history with fake compaction claims.
     */
    public static final String CONTEXT_COMPACTED_PREFIX = "[context compacted";

    private UiChrome() {
    }

    /**
     * True when {@code text} carries the {@link #TURN_ABORTED_PREFIX}
     * sentinel anywhere as a line of its own. The marker is emitted either
     * as the whole result text (watchdog aborts) or appended after partial
     * output on its own line (transport loss after visible output), so a
     * prefix check on the full text misses the partial-output shape and
     * lets a confabulated partial pass as a normal answer.
     *
     * <p>Detection stays line-anchored (trimmed line startsWith) so prose
     * that mentions the marker mid-sentence never fires. A line that
     * genuinely starts with the marker text - for example quoted from a
     * log - fires deliberately: erring toward "aborted" refuses to persist
     * possibly-unfinished output, which is the trust-safe direction.
     */
    public static boolean containsTurnAbortMarker(String text) {
        return !turnAbortMarkerLine(text).isEmpty();
    }

    /** The first line-anchored abort-marker line in {@code text}, or "". */
    public static String turnAbortMarkerLine(String text) {
        if (text == null || text.isEmpty()) return "";
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (stripped.startsWith(TURN_ABORTED_PREFIX)) return stripped;
        }
        return "";
    }
}
