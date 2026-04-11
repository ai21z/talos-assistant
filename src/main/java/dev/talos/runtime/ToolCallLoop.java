package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    /**
     * Create a tool-call loop with a custom iteration limit.
     *
     * @param turnProcessor provides tool execution with sandbox + approval gate
     * @param maxIterations maximum number of tool-call round-trips (must be ≥ 1)
     */
    public ToolCallLoop(TurnProcessor turnProcessor, int maxIterations) {
        this.turnProcessor = Objects.requireNonNull(turnProcessor, "turnProcessor");
        this.maxIterations = Math.max(1, maxIterations);
    }

    /** Create a tool-call loop with the default iteration limit. */
    public ToolCallLoop(TurnProcessor turnProcessor) {
        this(turnProcessor, DEFAULT_MAX_ITERATIONS);
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
                totalToolsInvoked++;
                toolNames.add(call.toolName());
                LOG.debug("  Executing tool: {} (params: {})", call.toolName(), call.parameters());

                ToolResult result = turnProcessor.executeTool(toolSession, call, ctx);

                // Format the tool result as a message the LLM can use
                String resultText = formatToolResult(call, result);
                messages.add(ChatMessage.user(resultText));

                LOG.debug("  Tool {} → {}", call.toolName(),
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
            ToolResult result = turnProcessor.executeTool(toolSession, call, ctx);
            executed++;
            LOG.debug("  Code-block tool {} → {}", call.toolName(),
                    result.success() ? "success" : "error: " + result.errorMessage());
        }

        return new LoopResult(answer, 1, executed, List.copyOf(toolNames), messages);
    }

    /**
     * Format a tool result as a message for the LLM.
     * Uses a structured format that the model can easily parse.
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
}




