package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agentic tool-call loop: parses tool calls from LLM responses, executes
 * them via {@link TurnProcessor#executeTool}, feeds results back as messages,
 * and re-prompts the LLM until the response contains no more tool calls
 * (or the iteration limit is reached).
 *
 * <p>This is the bridge between:
 * <ul>
 *   <li>{@link ToolCallParser} — extracts {@code <tool_call>} blocks from text</li>
 *   <li>{@link TurnProcessor#executeTool} — sandbox-enforced, approval-gated execution</li>
 *   <li>The LLM chat endpoint — re-prompted with tool results</li>
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
     * Run the tool-call loop on an initial LLM response.
     *
     * <p>If the response contains {@code <tool_call>} blocks, they are extracted,
     * executed, and the results are appended to the message list. The LLM is then
     * re-prompted with the updated messages. This repeats until:
     * <ol>
     *   <li>The LLM responds without any tool calls, or</li>
     *   <li>The maximum iteration count is reached</li>
     * </ol>
     *
     * @param initialAnswer the first LLM response text (may contain tool_call blocks)
     * @param messages      the mutable message list (will be extended with assistant + tool messages)
     * @param workspace     the workspace root path (for sandbox-scoped tool execution)
     * @param ctx           runtime context (provides LLM client, sandbox, etc.)
     * @return loop result with the final answer and execution stats
     */
    public LoopResult run(String initialAnswer, List<ChatMessage> messages, Path workspace, Context ctx) {
        if (initialAnswer == null) {
            return new LoopResult("", 0, 0, List.of(), messages);
        }

        if (!ToolCallParser.containsToolCalls(initialAnswer)) {
            // Safety-net: check for implicit file operations in code blocks with filename hints
            if (CodeBlockToolExtractor.containsExtractableBlocks(initialAnswer)) {
                return runCodeBlockFallback(initialAnswer, messages, workspace, ctx);
            }
            return new LoopResult(initialAnswer, 0, 0, List.of(), messages);
        }

        // Lightweight session for tool execution context
        Session toolSession = new Session(workspace, ctx.cfg());

        String currentAnswer = initialAnswer;
        int iterations = 0;
        int totalToolsInvoked = 0;
        List<String> toolNames = new ArrayList<>();

        while (iterations < maxIterations && ToolCallParser.containsToolCalls(currentAnswer)) {
            iterations++;

            // 1. Parse tool calls from the response
            List<ToolCall> calls = ToolCallParser.parse(currentAnswer);
            if (calls.isEmpty()) {
                // Pattern matched but JSON was malformed — stop looping
                break;
            }

            LOG.debug("Tool-call loop iteration {}: {} tool call(s) detected", iterations, calls.size());

            // 2. Append the assistant message (full response including tool_call blocks)
            messages.add(ChatMessage.assistant(currentAnswer));

            // 3. Execute each tool call and append results
            for (ToolCall call : calls) {
                // Repair missing 'path' for write/edit calls (model forgets it with long content)
                ToolCall effective = repairMissingPath(call, messages);
                totalToolsInvoked++;
                toolNames.add(effective.toolName());

                // Emit progress: executing
                emitProgress(effective.toolName(), "executing", resolvePathHint(effective));

                LOG.debug("  Executing tool: {} (params: {})", effective.toolName(), effective.parameters());

                ToolResult result = turnProcessor.executeTool(toolSession, effective, ctx);

                // Emit progress: completed or warning
                emitToolResult(effective.toolName(), result);

                // Format the tool result as a message the LLM can use
                String resultText = formatToolResult(effective, result);
                messages.add(ChatMessage.user(resultText));

                LOG.debug("  Tool {} → {}", effective.toolName(),
                        result.success() ? "success (" + truncateForLog(result.output()) + ")"
                                : "error: " + result.errorMessage());
            }

            // 4. Re-prompt the LLM with the updated conversation
            try {
                currentAnswer = ctx.llm().chat(messages);
                if (currentAnswer == null) {
                    currentAnswer = "(no answer from model after tool execution)";
                    break;
                }
            } catch (EngineException.ConnectionFailed cf) {
                LOG.warn("Ollama not reachable during tool-call loop iteration {}: {}", iterations, cf.getMessage());
                currentAnswer = "[Ollama not reachable — tool loop aborted. " + cf.guidance() + "]";
                break;
            } catch (EngineException.ModelNotFound mnf) {
                LOG.warn("Model not found during tool-call loop iteration {}: {}", iterations, mnf.model());
                currentAnswer = "[Model '" + mnf.model() + "' not found — tool loop aborted. " + mnf.guidance() + "]";
                break;
            } catch (EngineException.Transient tr) {
                LOG.warn("Transient error during tool-call loop iteration {}: {}", iterations, tr.getMessage());
                // One retry for transient errors in the tool loop
                try {
                    Thread.sleep(400);
                    currentAnswer = ctx.llm().chat(messages);
                    if (currentAnswer == null) {
                        currentAnswer = "(no answer from model after retry)";
                        break;
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    currentAnswer = "[Interrupted during tool-call loop]";
                    break;
                } catch (Exception retryEx) {
                    currentAnswer = "[" + tr.guidance() + "]";
                    break;
                }
            } catch (EngineException ee) {
                LOG.warn("Engine error during tool-call loop iteration {}: {}", iterations, ee.getMessage());
                currentAnswer = "[Engine error during tool loop: " + ee.getMessage() + "]";
                break;
            } catch (Exception e) {
                LOG.warn("LLM call failed during tool-call loop iteration {}: {}", iterations, e.getMessage());
                currentAnswer = "(error during follow-up LLM call: " + e.getMessage() + ")";
                break;
            }
        }

        if (iterations >= maxIterations && ToolCallParser.containsToolCalls(currentAnswer)) {
            LOG.warn("Tool-call loop reached max iterations ({}). Stopping.", maxIterations);
            currentAnswer = ToolCallParser.stripToolCalls(currentAnswer)
                    + "\n\n[Tool-call limit reached. Some tool calls were not executed.]";
        }

        // Strip any remaining tool_call blocks from the final answer
        String finalAnswer = ToolCallParser.stripToolCalls(currentAnswer);

        LOG.debug("Tool-call loop complete: {} iterations, {} tools invoked", iterations, totalToolsInvoked);

        return new LoopResult(finalAnswer, iterations, totalToolsInvoked, List.copyOf(toolNames), messages);
    }

    /**
     * Fallback: execute implicit write_file calls extracted from code blocks
     * with filename hints. Single-pass (no re-prompting) — the LLM already
     * produced the final answer, it just used code fences instead of
     * {@code <tool_call>} blocks.
     */
    private LoopResult runCodeBlockFallback(String answer, List<ChatMessage> messages,
                                            Path workspace, Context ctx) {
        List<ToolCall> calls = CodeBlockToolExtractor.extract(answer);
        if (calls.isEmpty()) {
            return new LoopResult(answer, 0, 0, List.of(), messages);
        }

        Session toolSession = new Session(workspace, ctx.cfg());
        List<String> toolNames = new ArrayList<>();
        int executed = 0;

        LOG.info("Detected {} implicit write_file call(s) from code blocks (safety-net extraction)", calls.size());

        for (ToolCall call : calls) {
            toolNames.add(call.toolName());
            emitProgress(call.toolName(), "executing", resolvePathHint(call));
            ToolResult result = turnProcessor.executeTool(toolSession, call, ctx);
            emitToolResult(call.toolName(), result);
            executed++;
            LOG.debug("  Code-block tool {} → {}", call.toolName(),
                    result.success() ? "success" : "error: " + result.errorMessage());
        }

        return new LoopResult(answer, 1, executed, List.copyOf(toolNames), messages);
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

    /**
     * Test-only accessor for {@link #repairMissingPath(ToolCall, List)}.
     * Package-private — used by {@code PathInferenceTest} in the same package.
     */
    static ToolCall testRepairMissingPath(ToolCall call, List<ChatMessage> messages) {
        return repairMissingPath(call, messages);
    }

    // ---- Path inference for write/edit calls with missing path ----

    /** Tool names that require a 'path' parameter and frequently have it omitted by models. */
    private static final Set<String> PATH_REQUIRED_TOOLS = Set.of(
            "talos.write_file", "talos.edit_file"
    );

    /** All parameter name variants the tools accept for the file path. */
    private static final List<String> PATH_PARAM_KEYS = List.of(
            "path", "file_path", "filepath", "file", "filename"
    );

    /**
     * Pattern to detect file path references in tool call parameter dumps.
     * Matches the path parameter from read_file calls in log-style messages.
     */
    private static final Pattern READ_FILE_PATH_PARAM = Pattern.compile(
            "talos\\.read_file\\s*\\(params:\\s*\\{path=([^,}]+)"
    );

    /** Common file extension pattern for extracting file names from user text. */
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile(
            "\\b([\\w./-]+\\.(?:html?|css|js|jsx|ts|tsx|json|ya?ml|xml|md|txt|java|py|rb|go|rs|c|cpp|h|sh|bat|ps1|sql|csv|toml|ini|cfg|conf|properties|gradle|kts))\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Pattern to match file path headers in RAG context snippets.
     * Matches both backtick-quoted and plain bracket styles:
     * <ul>
     *   <li>{@code [`index.html`]}</li>
     *   <li>{@code [`src/main.js#0`]}</li>
     *   <li>{@code [index.html]}</li>
     * </ul>
     * Strips optional chunk suffixes ({@code #0}, {@code #1}) from paths.
     */
    private static final Pattern RAG_SNIPPET_PATH = Pattern.compile(
            "\\[`?([\\w./-]+\\.(?:html?|css|js|jsx|ts|tsx|json|ya?ml|xml|md|txt|java|py|rb|go|rs|c|cpp|h|sh|bat|ps1|sql|csv|toml|ini|cfg|conf|properties|gradle|kts))(?:#\\d+)?`?\\]",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * If a write/edit tool call is missing the 'path' parameter, attempt to infer
     * it from conversation context. Returns the original call unchanged if:
     * <ul>
     *   <li>The tool doesn't need path repair</li>
     *   <li>The path is already present</li>
     *   <li>No path can be inferred from context</li>
     * </ul>
     *
     * <p>Inference sources (in priority order):
     * <ol>
     *   <li>Previous {@code talos.read_file} tool results in the conversation</li>
     *   <li>File name references in the user's most recent message</li>
     * </ol>
     */
    private static ToolCall repairMissingPath(ToolCall call, List<ChatMessage> messages) {
        // Only repair write/edit tools
        if (!PATH_REQUIRED_TOOLS.contains(call.toolName())) {
            return call;
        }

        // Check if path is already present (any alias)
        for (String key : PATH_PARAM_KEYS) {
            String v = call.param(key);
            if (v != null && !v.isBlank()) return call; // path is present, no repair needed
        }

        // Path is genuinely missing — try to infer it
        String inferred = inferPathFromContext(messages);
        if (inferred == null || inferred.isBlank()) {
            LOG.warn("write/edit tool call missing 'path' parameter and no path could be inferred from context");
            return call; // can't fix it, let the tool produce its error
        }

        // Build a repaired ToolCall with the inferred path injected
        Map<String, String> repairedParams = new HashMap<>(call.parameters());
        repairedParams.put("path", inferred);

        LOG.info("Repaired missing 'path' parameter for {}: inferred '{}' from conversation context",
                call.toolName(), inferred);

        return new ToolCall(call.toolName(), repairedParams);
    }

    /**
     * Scan conversation messages to find the most likely target file path.
     * Returns null if no path can be inferred.
     *
     * <p>Strategies (in priority order):
     * <ol>
     *   <li>Previous {@code talos.read_file} tool calls in current-turn messages</li>
     *   <li>File name references in the user's most recent question</li>
     *   <li>File path references in RAG context snippets ({@code [`path`]} headers)</li>
     *   <li>File name references in any message (history answers, prior questions)</li>
     * </ol>
     */
    private static String inferPathFromContext(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;

        // Strategy 1: Find the most recent read_file tool call in assistant messages
        // (works within the same turn — the tool_call XML is in the current conversation)
        String fromToolHistory = findLastReadFilePath(messages);
        if (fromToolHistory != null) return fromToolHistory;

        // Strategy 2: Find file name references in the user's most recent question
        String fromUserMessage = findFileNameInLastUserMessage(messages);
        if (fromUserMessage != null) return fromUserMessage;

        // Strategy 3: Find file path from RAG context snippets (e.g., [`index.html`] headers)
        String fromContext = findFileNameInRagContext(messages);
        if (fromContext != null) return fromContext;

        // Strategy 4: Broader scan — file name in ANY message (history answers, old questions)
        return findFileNameInAnyMessage(messages);
    }

    /**
     * Scan messages (newest first) for previous read_file tool calls and
     * extract the path that was read.
     */
    private static String findLastReadFilePath(List<ChatMessage> messages) {
        // Walk backwards — most recent messages first
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg == null || msg.content() == null) continue;
            String text = msg.content();

            // Check for tool_call JSON in assistant messages: {"name":"talos.read_file","parameters":{"path":"..."}}
            if ("assistant".equals(msg.role()) && text.contains("talos.read_file")) {
                String path = extractPathFromToolCallJson(text);
                if (path != null) return path;
            }
        }

        // Also try matching path from debug-style parameter dumps
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg == null || msg.content() == null) continue;
            Matcher m = READ_FILE_PATH_PARAM.matcher(msg.content());
            if (m.find()) return m.group(1).trim();
        }

        return null;
    }

    /**
     * Extract the 'path' value from a tool_call JSON block for talos.read_file.
     * Handles both XML-wrapped and raw JSON formats.
     */
    private static String extractPathFromToolCallJson(String text) {
        String toolName = "talos.read_file";
        // Look for JSON pattern: "name":"talos.read_file","parameters":{"path":"<value>"}
        int nameIdx = text.indexOf("\"name\":\"" + toolName + "\"");
        if (nameIdx < 0) {
            // Also try without quotes (some formats)
            nameIdx = text.indexOf("\"name\": \"" + toolName + "\"");
        }
        if (nameIdx < 0) return null;

        // Find "path" value after the name
        int pathIdx = text.indexOf("\"path\"", nameIdx);
        if (pathIdx < 0) return null;

        // Extract the value: skip to the colon, then the opening quote
        int colon = text.indexOf(':', pathIdx + 6);
        if (colon < 0) return null;
        int openQuote = text.indexOf('"', colon + 1);
        if (openQuote < 0) return null;
        int closeQuote = text.indexOf('"', openQuote + 1);
        if (closeQuote < 0) return null;

        String path = text.substring(openQuote + 1, closeQuote).trim();
        return path.isEmpty() ? null : path;
    }

    /**
     * Find a file name reference in the user's most recent message.
     */
    private static String findFileNameInLastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg == null || !"user".equals(msg.role())) continue;
            String text = msg.content();
            if (text == null || text.startsWith("[tool_result:")) continue; // skip tool results

            Matcher m = FILE_NAME_PATTERN.matcher(text);
            if (m.find()) return m.group(1);

            break; // only check the most recent actual user message
        }
        return null;
    }

    /**
     * Strategy 3: Find file path from RAG context snippet headers.
     *
     * <p>RAG context is injected as a user-role message with paths in bracket
     * headers: {@code [`index.html`]}. If the user says "update it", the RAG
     * context still names the file. We pick the most recent (closest to the
     * user question) file path found.
     */
    private static String findFileNameInRagContext(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg == null || !"user".equals(msg.role())) continue;
            String text = msg.content();
            if (text == null) continue;
            // Skip tool results
            if (text.startsWith("[tool_result:")) continue;
            // Look for RAG context marker
            if (!text.contains("retrieved context") && !text.contains("snippets")) continue;

            // Scan for snippet path headers (take the first/most prominent one)
            Matcher m = RAG_SNIPPET_PATH.matcher(text);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    /**
     * Strategy 4: Broader scan — find file name references in ANY message.
     *
     * <p>Walks backward through all messages (including history) looking for
     * file name references. This handles cross-turn scenarios where the user
     * said "read index.html" in Turn 1 and says "update it" in Turn 3 —
     * the file name appears in the Turn 1 user message in history.
     *
     * <p>Skips tool results to avoid false positives from file content.
     * Prefers user messages over assistant messages.
     */
    private static String findFileNameInAnyMessage(List<ChatMessage> messages) {
        // First pass: user messages only (more reliable)
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg == null || !"user".equals(msg.role())) continue;
            String text = msg.content();
            if (text == null) continue;
            // Skip tool results and RAG context blocks (already checked by strategy 3)
            if (text.startsWith("[tool_result:")) continue;
            if (text.length() > 500) continue; // skip large blocks (RAG context, file content)

            Matcher m = FILE_NAME_PATTERN.matcher(text);
            if (m.find()) return m.group(1);
        }
        // Second pass: assistant messages (history answers that mention file names)
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg == null || !"assistant".equals(msg.role())) continue;
            String text = msg.content();
            if (text == null) continue;
            // Only scan short messages (direct mentions, not full file content)
            if (text.length() > 1000) continue;

            Matcher m = FILE_NAME_PATTERN.matcher(text);
            if (m.find()) return m.group(1);
        }
        return null;
    }
}




