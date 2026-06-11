package dev.talos.core.util;

/**
 * Talos-emitted status-line ("chrome") prefixes shared between the emitters
 * and the conversation-history stripper (T767).
 *
 * <p>The runtime decorates assistant text with status lines — tool-usage
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

    /** Tool-call budget notice: {@code [Tool-call limit reached. ...]}. */
    public static final String TOOL_CALL_LIMIT_PREFIX = "[Tool-call limit reached";

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

    /** Historical stripper entry; no current emitter produces "Wrote ..." (T768). */
    public static final String WROTE_PREFIX = CHECK_PREFIX + "Wrote ";

    /** {@code "✓ " + FileWriteTool/MakeDirectoryTool} output ("Created ..."). */
    public static final String CREATED_PREFIX = CHECK_PREFIX + "Created ";

    /** Repeated-edit-failure cushion suggestion injected into tool errors. */
    public static final String EDIT_FAILURE_SUGGESTION_PREFIX = "Suggestion: edit_file has failed";

    private UiChrome() {
    }
}
