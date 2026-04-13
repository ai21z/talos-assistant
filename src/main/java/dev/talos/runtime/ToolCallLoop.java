package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.util.Sanitize;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Agentic tool-call loop: receives tool calls (native or text-parsed),
 * executes them via {@link TurnProcessor#executeTool}, feeds results back
 * as messages, and re-prompts the LLM until the response contains no more
 * tool calls (or the iteration limit is reached).
 *
 * <p><b>Architecture (native-first):</b>
 * <ul>
 *   <li><b>Native path (primary):</b> Structured
 *       {@link dev.talos.spi.types.ChatMessage.NativeToolCall NativeToolCall} objects
 *       from the engine — no text parsing needed.</li>
 *   <li><b>Text fallback (secondary):</b> Tool calls extracted from the LLM
 *       response text by {@link ToolCallParser} — supports JSON code fences
 *       (active format) and XML tags (compatibility).</li>
 * </ul>
 *
 * <p>This is the bridge between:
 * <ul>
 *   <li>{@link ToolCallParser} — extracts tool-call blocks from text (JSON code fences,
 *       XML tags, bare JSON)</li>
 *   <li>{@link TurnProcessor#executeTool} — sandbox-enforced, approval-gated execution</li>
 *   <li>The LLM chat endpoint — re-prompted with tool results via
 *       {@link dev.talos.core.llm.LlmClient#chatFull}</li>
 * </ul>
 *
 * <p>The loop is stateless and designed to be called from any Mode (Ask, Rag, etc.)
 * after the initial LLM response. It mutates the provided message list in-place,
 * appending assistant/tool-result messages for each iteration.
 *
 * <p>Safety:
 * <ul>
 *   <li>Max iterations prevent infinite loops (default: 10)</li>
 *   <li>Tool execution never throws — errors become tool-result messages</li>
 *   <li>Non-tool text from the LLM (reasoning/explanation) is preserved</li>
 *   <li>Missing paths on write/edit are NOT inferred — tool produces clear error</li>
 * </ul>
 */
public final class ToolCallLoop {

    private static final Logger LOG = LoggerFactory.getLogger(ToolCallLoop.class);

    /** Default maximum tool-call iterations per turn. */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    private final TurnProcessor turnProcessor;
    private final int maxIterations;
    private final ToolProgressSink progressSink;

    /**
     * Create a tool-call loop with a custom iteration limit and progress sink.
     *
     * @param turnProcessor provides tool execution with sandbox + approval gate
     * @param maxIterations maximum number of tool-call round-trips (must be ≥ 1)
     * @param progressSink  optional progress callback (may be null)
     */
    public ToolCallLoop(TurnProcessor turnProcessor, int maxIterations, ToolProgressSink progressSink) {
        this.turnProcessor = Objects.requireNonNull(turnProcessor, "turnProcessor");
        this.maxIterations = Math.max(1, maxIterations);
        this.progressSink = progressSink;
    }

    /**
     * Create a tool-call loop with a custom iteration limit.
     *
     * @param turnProcessor provides tool execution with sandbox + approval gate
     * @param maxIterations maximum number of tool-call round-trips (must be ≥ 1)
     */
    public ToolCallLoop(TurnProcessor turnProcessor, int maxIterations) {
        this(turnProcessor, maxIterations, null);
    }

    /** Create a tool-call loop with the default iteration limit. */
    public ToolCallLoop(TurnProcessor turnProcessor) {
        this(turnProcessor, DEFAULT_MAX_ITERATIONS, null);
    }

    /**
     * Result of the tool-call loop: the final LLM answer after all tool calls
     * have been resolved, plus metadata about the loop execution.
     *
     * @param finalAnswer  the LLM's final text (with tool_call blocks stripped)
     * @param iterations   number of tool-call round-trips executed (0 if no tools called)
     * @param toolsInvoked total number of individual tool calls across all iterations
     * @param toolNames    names of tools invoked (in call order, may contain duplicates)
     * @param messages     the full message list including all tool interactions
     */
    public record LoopResult(
            String finalAnswer,
            int iterations,
            int toolsInvoked,
            List<String> toolNames,
            List<ChatMessage> messages
    ) {
        /**
         * Returns a user-facing summary line, or null if no tools were invoked.
         * Example: {@code "[Used 2 tool(s): read_file, grep | 1 iteration]"}
         */
        public String summary() {
            if (toolsInvoked <= 0) return null;
            // Deduplicate tool names preserving first-seen order
            var unique = new java.util.LinkedHashSet<>(toolNames != null ? toolNames : List.of());
            String names = unique.isEmpty() ? "" : ": " + String.join(", ", unique);
            return "[Used " + toolsInvoked + " tool(s)" + names + " | "
                    + iterations + " iteration(s)]";
        }
    }

    /**
     * Run the tool-call loop on an initial LLM response (text-only, no native calls).
     *
     * <p>If the response contains tool-call blocks (JSON code fences, XML tags,
     * or bare JSON), they are extracted, executed, and the results are appended
     * to the message list. The LLM is then re-prompted with the updated messages.
     * This repeats until:
     * <ol>
     *   <li>The LLM responds without any tool calls, or</li>
     *   <li>The maximum iteration count is reached</li>
     * </ol>
     *
     * @param initialAnswer the first LLM response text (may contain text-format tool calls)
     * @param messages      the mutable message list (will be extended with assistant + tool messages)
     * @param workspace     the workspace root path (for sandbox-scoped tool execution)
     * @param ctx           runtime context (provides LLM client, sandbox, etc.)
     * @return loop result with the final answer and execution stats
     */
    public LoopResult run(String initialAnswer, List<ChatMessage> messages, Path workspace, Context ctx) {
        return run(initialAnswer, List.of(), messages, workspace, ctx);
    }

    /**
     * Run the tool-call loop with native tool calls from the LLM.
     *
     * <p>When {@code nativeToolCalls} is non-empty, the loop uses them directly
     * (no regex parsing needed). This is the <b>primary path</b> for modern models
     * that support Ollama's native tool calling API.
     *
     * <p>When {@code nativeToolCalls} is empty, falls back to parsing tool calls
     * from the text response via {@link ToolCallParser} (handles XML tags,
     * code-fenced JSON, and bare JSON formats).
     *
     * @param initialAnswer    the first LLM response text (prose; may contain text-format tool calls as fallback)
     * @param nativeToolCalls  native tool calls from the model (may be empty)
     * @param messages         the mutable message list (will be extended with assistant + tool messages)
     * @param workspace        the workspace root path (for sandbox-scoped tool execution)
     * @param ctx              runtime context (provides LLM client, sandbox, etc.)
     * @return loop result with the final answer and execution stats
     */
    public LoopResult run(String initialAnswer, List<NativeToolCall> nativeToolCalls,
                          List<ChatMessage> messages, Path workspace, Context ctx) {
        if (initialAnswer == null) initialAnswer = "";

        boolean hasNative = nativeToolCalls != null && !nativeToolCalls.isEmpty();
        boolean hasTextCalls = ToolCallParser.containsToolCalls(initialAnswer);

        if (!hasNative && !hasTextCalls) {
            // No tool calls of any kind — check for code-block fallback (warning only)
            if (CodeBlockToolExtractor.containsExtractableBlocks(initialAnswer)) {
                LOG.warn("Response contains code blocks with filename hints but no tool calls. "
                        + "File writes were NOT performed. The model should use tool_call format for file operations.");
            }
            return new LoopResult(initialAnswer, 0, 0, List.of(), messages);
        }

        // Lightweight session for tool execution context
        Session toolSession = new Session(workspace, ctx.cfg());

        String currentText = initialAnswer;
        List<NativeToolCall> currentNativeCalls = hasNative ? new ArrayList<>(nativeToolCalls) : List.of();
        int iterations = 0;
        int totalToolsInvoked = 0;
        List<String> toolNames = new ArrayList<>();

        while (iterations < maxIterations) {
            boolean useNativePath = !currentNativeCalls.isEmpty();
            boolean useTextPath = !useNativePath && ToolCallParser.containsToolCalls(currentText);

            if (!useNativePath && !useTextPath) break;

            iterations++;

            // 1. Parse/convert tool calls
            List<ToolCall> calls;
            if (useNativePath) {
                calls = convertNativeToolCalls(currentNativeCalls);
                LOG.debug("Tool-call loop iteration {}: {} native tool call(s)", iterations, calls.size());
            } else {
                calls = ToolCallParser.parse(currentText);
                LOG.debug("Tool-call loop iteration {}: {} text tool call(s)", iterations, calls.size());
            }

            if (calls.isEmpty()) break; // malformed — stop

            // 2. Append the assistant message with proper type
            if (useNativePath) {
                messages.add(ChatMessage.assistantWithToolCalls(currentText, currentNativeCalls));
            } else {
                messages.add(ChatMessage.assistant(currentText));
            }

            // 3. Execute each tool call and append results
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                ToolCall effective = repairMissingPath(call);
                totalToolsInvoked++;
                toolNames.add(effective.toolName());

                emitProgress(effective.toolName(), "executing", resolvePathHint(effective));
                LOG.debug("  Executing tool: {} (params: {})", effective.toolName(), effective.parameters());

                ToolResult result = turnProcessor.executeTool(toolSession, effective, ctx);
                emitToolResult(effective.toolName(), result);

                String resultText = formatToolResult(effective, result);

                // Use proper message type: native path → role="tool" with callId; fallback → role="user"
                if (useNativePath && i < currentNativeCalls.size()) {
                    String callId = currentNativeCalls.get(i).id();
                    messages.add(ChatMessage.toolResult(callId, resultText));
                } else {
                    messages.add(ChatMessage.user(resultText));
                }

                LOG.debug("  Tool {} → {}", effective.toolName(),
                        result.success() ? "success (" + truncateForLog(result.output()) + ")"
                                : "error: " + result.errorMessage());
            }

            // 4. Re-prompt the LLM with the updated conversation
            try {
                LlmClient.StreamResult repromptResult = ctx.llm().chatFull(messages);
                currentText = repromptResult.text();
                currentNativeCalls = repromptResult.hasToolCalls()
                        ? new ArrayList<>(repromptResult.toolCalls()) : List.of();

                if (currentText == null) currentText = "";
                if (currentText.isEmpty() && currentNativeCalls.isEmpty()) {
                    currentText = "(no answer from model after tool execution)";
                    break;
                }
            } catch (EngineException.ConnectionFailed cf) {
                LOG.warn("Ollama not reachable during tool-call loop iteration {}: {}", iterations, cf.getMessage());
                currentText = "[Ollama not reachable — tool loop aborted. " + cf.guidance() + "]";
                currentNativeCalls = List.of();
                break;
            } catch (EngineException.ModelNotFound mnf) {
                LOG.warn("Model not found during tool-call loop iteration {}: {}", iterations, mnf.model());
                currentText = "[Model '" + mnf.model() + "' not found — tool loop aborted. " + mnf.guidance() + "]";
                currentNativeCalls = List.of();
                break;
            } catch (EngineException.Transient tr) {
                LOG.warn("Transient error during tool-call loop iteration {}: {}", iterations, tr.getMessage());
                try {
                    Thread.sleep(400);
                    LlmClient.StreamResult retryResult = ctx.llm().chatFull(messages);
                    currentText = retryResult.text();
                    currentNativeCalls = retryResult.hasToolCalls()
                            ? new ArrayList<>(retryResult.toolCalls()) : List.of();
                    if (currentText == null) currentText = "";
                    if (currentText.isEmpty() && currentNativeCalls.isEmpty()) {
                        currentText = "(no answer from model after retry)";
                        break;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    currentText = "[Interrupted during tool-call loop]";
                    currentNativeCalls = List.of();
                    break;
                } catch (Exception retryEx) {
                    currentText = "[" + tr.guidance() + "]";
                    currentNativeCalls = List.of();
                    break;
                }
            } catch (EngineException ee) {
                LOG.warn("Engine error during tool-call loop iteration {}: {}", iterations, ee.getMessage());
                currentText = "[Engine error during tool loop: " + ee.getMessage() + "]";
                currentNativeCalls = List.of();
                break;
            } catch (Exception e) {
                LOG.warn("LLM call failed during tool-call loop iteration {}: {}", iterations, e.getMessage());
                currentText = "(error during follow-up LLM call: " + e.getMessage() + ")";
                currentNativeCalls = List.of();
                break;
            }
        }

        if (iterations >= maxIterations
                && (!currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(currentText))) {
            LOG.warn("Tool-call loop reached max iterations ({}). Stopping.", maxIterations);
            currentText = ToolCallParser.stripToolCalls(currentText)
                    + "\n\n[Tool-call limit reached. Some tool calls were not executed.]";
        }

        // Strip any remaining tool_call blocks from the final answer,
        // then apply SUS_HTML stripping to the prose (safe now that tool_call
        // blocks with their HTML-valued JSON params have been removed).
        String finalAnswer = Sanitize.stripSuspiciousHtml(
                ToolCallParser.stripToolCalls(currentText));

        LOG.debug("Tool-call loop complete: {} iterations, {} tools invoked", iterations, totalToolsInvoked);

        return new LoopResult(finalAnswer, iterations, totalToolsInvoked, List.copyOf(toolNames), messages);
    }

    // ── NativeToolCall → ToolCall conversion ─────────────────────────────

    /**
     * Convert native tool calls to the canonical {@link ToolCall} format.
     * All argument values are stringified (ToolCall uses {@code Map<String, String>}).
     */
    static List<ToolCall> convertNativeToolCalls(List<NativeToolCall> nativeCalls) {
        List<ToolCall> calls = new ArrayList<>(nativeCalls.size());
        for (NativeToolCall ntc : nativeCalls) {
            Map<String, String> params = new LinkedHashMap<>();
            if (ntc.arguments() != null) {
                for (var entry : ntc.arguments().entrySet()) {
                    params.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
            calls.add(new ToolCall(ntc.name(), params));
        }
        return calls;
    }

    /**
     * Format a tool result as a message for the LLM.
     * Uses a structured format that the model can easily parse.
     * Includes verification status when present.
     */
    static String formatToolResult(ToolCall call, ToolResult result) {
        var sb = new StringBuilder();
        sb.append("[tool_result: ").append(call.toolName()).append("]\n");
        if (result.success()) {
            String output = result.output();
            if (output == null || output.isBlank()) {
                sb.append("(empty result)");
            } else {
                // Cap tool output to prevent context window explosion
                if (output.length() > 32_000) {
                    sb.append(output, 0, 32_000);
                    sb.append("\n... (output truncated at 32K chars)");
                } else {
                    sb.append(output);
                }
            }
            // Surface structured verification status for write/edit tools
            if (result.verification() != null) {
                sb.append("\n[verification_status: ").append(result.verification().name()).append("]");
            }
        } else {
            sb.append("[error] ").append(result.errorMessage());
        }
        sb.append("\n[/tool_result]");
        return sb.toString();
    }

    /** Truncate a string for logging purposes. */
    private static String truncateForLog(String s) {
        if (s == null) return "null";
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    // ---- Progress events ----

    /** Safely emit a progress event to the sink (no-op if null). */
    private void emitProgress(String toolName, String action, String detail) {
        if (progressSink != null) {
            try {
                progressSink.onToolProgress(toolName, action, detail);
            } catch (Exception e) {
                LOG.debug("Progress sink error (ignored): {}", e.getMessage());
            }
        }
    }

    /** Emit progress for a completed tool result, surfacing verification warnings. */
    private void emitToolResult(String toolName, ToolResult result) {
        if (progressSink == null) return;
        if (!result.success()) {
            emitProgress(toolName, "error", result.errorMessage());
            return;
        }
        // Surface verification warnings as distinct progress events
        if (result.verification() != null && !result.verification().acceptable()) {
            // Extract summary from output (after "Warning: " if present)
            String detail = extractVerificationSummary(result.output());
            emitProgress(toolName, "warning", detail);
        }
    }

    /** Extract the verification summary from a tool result output string. */
    static String extractVerificationSummary(String output) {
        if (output == null) return null;
        int warnIdx = output.indexOf("Warning: ");
        if (warnIdx >= 0) {
            String after = output.substring(warnIdx + 9);
            // Trim trailing status tag if present
            int tagIdx = after.indexOf(". [verification:");
            return tagIdx >= 0 ? after.substring(0, tagIdx) : after;
        }
        return null;
    }

    /** Extract a path hint from a tool call for display purposes. */
    private static String resolvePathHint(ToolCall call) {
        for (String key : List.of("path", "file_path", "filepath", "file", "filename", "dir", "pattern")) {
            String v = call.param(key);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    // ---- Path safety for write/edit calls with missing path ----

    /** Tool names that require a 'path' parameter and frequently have it omitted by models. */
    private static final Set<String> PATH_REQUIRED_TOOLS = Set.of(
            "talos.write_file", "talos.edit_file"
    );

    /** All parameter name variants the tools accept for the file path. */
    private static final List<String> PATH_PARAM_KEYS = List.of(
            "path", "file_path", "filepath", "file", "filename"
    );

    /**
     * Check for missing 'path' on write/edit tool calls.
     *
     * <p><strong>For mutating tools (write_file, edit_file):</strong> a missing path
     * is returned as-is so the tool produces a clear error. Path inference was
     * previously used here but proved too dangerous — it silently wrote files to
     * guessed targets (e.g. inferring 'styles.css' when the model intended 'index.html').
     * The model must provide the path explicitly.
     *
     * <p><strong>For read-only tools:</strong> the call is returned unchanged
     * (those tools already produce safe errors for missing paths).
     */
    static ToolCall repairMissingPath(ToolCall call) {
        // Only check write/edit tools
        if (!PATH_REQUIRED_TOOLS.contains(call.toolName())) {
            return call;
        }

        // Check if path is already present (any alias)
        for (String key : PATH_PARAM_KEYS) {
            String v = call.param(key);
            if (v != null && !v.isBlank()) return call; // path is present, no repair needed
        }

        // Path is genuinely missing — do NOT infer for mutating tools.
        // Let the tool produce its own clear error message so the model can retry.
        LOG.warn("{} call is missing required 'path' parameter. "
                + "Returning call as-is so the tool produces an error. "
                + "The model must provide the target file path explicitly.", call.toolName());
        return call;
    }
}

