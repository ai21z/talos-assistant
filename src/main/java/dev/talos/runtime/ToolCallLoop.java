package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.util.Sanitize;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatMessage.NativeToolCall;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolProgressSink;
import dev.talos.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
     * Strict-measurement flag. When true, the loop disables the following
     * helpful-but-model-flattering cushions (harness-seam measurement only):
     * <ul>
     *   <li>B3 duplicate-failing-edit short-circuit + canned diagnostic</li>
     *   <li>Redundant read-only call suppression + "already gathered" nudge</li>
     *   <li>B2 read-before-write hint appended to tool results</li>
     *   <li>E1 error-message rewriting after repeated edit_file failure</li>
     * </ul>
     *
     * <p>Strict mode does <b>not</b> disable safety-critical behavior:
     * iteration cap, sandbox, approval gate, missing-path refusal, engine
     * exception handling, output truncation, and tool-call stripping all
     * remain active.
     *
     * <p>Default is {@code false} (cushioned, production-equivalent).
     */
    private final boolean strict;

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
        this.strict = false;
    }

    /**
     * Create a tool-call loop with an explicit strict-mode flag (harness use).
     *
     * @param turnProcessor provides tool execution with sandbox + approval gate
     * @param maxIterations maximum number of tool-call round-trips (must be ≥ 1)
     * @param progressSink  optional progress callback (may be null)
     * @param strict        if true, disable measurement cushions (see {@link #strict})
     */
    public ToolCallLoop(TurnProcessor turnProcessor, int maxIterations,
                        ToolProgressSink progressSink, boolean strict) {
        this.turnProcessor = Objects.requireNonNull(turnProcessor, "turnProcessor");
        this.maxIterations = Math.max(1, maxIterations);
        this.progressSink = progressSink;
        this.strict = strict;
    }

    /** @return true if this loop is running in strict-measurement mode. */
    public boolean isStrict() {
        return strict;
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
     * @param finalAnswer           the LLM's final text (with tool_call blocks stripped)
     * @param iterations            number of tool-call round-trips executed (0 if no tools called)
     * @param toolsInvoked          total number of individual tool calls across all iterations
     * @param toolNames             names of tools invoked (in call order, may contain duplicates)
     * @param messages              the full message list including all tool interactions
     * @param failedCalls           number of tool calls that returned errors
     * @param retriedCalls          number of tool calls with the same (tool, path, old_string) as a prior failed call
     * @param hitIterLimit          true if the loop was stopped by the max iteration cap
     * @param mutatingToolSuccesses number of successful mutating tool calls (write_file, edit_file)
     *                              executed in this turn. Used by the post-turn claim-vs-action
     *                              audit in {@code AssistantTurnExecutor}.
     * @param cushionFiresRedundantRead number of times the redundant read-only call suppression
     *                                  cushion fired (incremented per suppressed duplicate read).
     *                                  Always 0 in strict mode.
     * @param cushionFiresAliasRescue   number of times {@link dev.talos.tools.ToolRegistry} rescued
     *                                  a non-canonical tool name via prefix/alias/case normalization
     *                                  during this loop run. Always 0 in strict mode.
     * @param cushionFiresB3EditShortCircuit number of times the B3 duplicate-failing-edit
     *                                       short-circuit fired. Always 0 in strict mode.
     * @param cushionFiresE1Suggestion  number of times the E1 edit-failure error-message rewrite
     *                                  (suggests {@code write_file} after ≥2 failures on the same
     *                                  path) fired. Always 0 in strict mode.
     *
     * <p>N5: the four {@code cushionFires*} counters make strict-vs-normal deltas observable
     * from the harness without grepping logs. They count gate-site fires per loop run.
     */
    public record LoopResult(
            String finalAnswer,
            int iterations,
            int toolsInvoked,
            List<String> toolNames,
            List<ChatMessage> messages,
            int failedCalls,
            int retriedCalls,
            boolean hitIterLimit,
            int mutatingToolSuccesses,
            int cushionFiresRedundantRead,
            int cushionFiresAliasRescue,
            int cushionFiresB3EditShortCircuit,
            int cushionFiresE1Suggestion
    ) {
        /**
         * Returns a user-facing summary line, or null if no tools were invoked.
         * Example: {@code "[Used 2 tool(s): read_file, grep | 1 iteration]"}
         */
        public String summary() {
            if (toolsInvoked <= 0) return null;
            var unique = new java.util.LinkedHashSet<>(toolNames != null ? toolNames : List.of());
            String names = unique.isEmpty() ? "" : ": " + String.join(", ", unique);
            String base = "[Used " + toolsInvoked + " tool(s)" + names + " | " + iterations + " iteration(s)]";
            if (failedCalls > 0) {
                base += " [" + failedCalls + " failed]";
            }
            if (hitIterLimit) {
                base += " [iteration limit reached]";
            }
            return base;
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
                LOG.debug("Response contains code blocks with filename hints but no tool calls. "
                        + "File writes were NOT performed. The model should use tool_call format for file operations.");
            }
            return new LoopResult(initialAnswer, 0, 0, List.of(), messages, 0, 0, false, 0,
                    0, 0, 0, 0);
        }

        // Lightweight session for tool execution context
        Session toolSession = new Session(workspace, ctx.cfg());

        String currentText = initialAnswer;
        List<NativeToolCall> currentNativeCalls = hasNative ? new ArrayList<>(nativeToolCalls) : List.of();
        int iterations = 0;
        int totalToolsInvoked = 0;
        int failedCalls = 0;
        int retriedCalls = 0;
        int mutatingToolSuccesses = 0;
        // N5: cushion-fire counters (strict-mode runs keep these at 0 because
        // each gate site is already strict-gated — see comments at each site).
        int cushionFiresRedundantRead = 0;
        int cushionFiresB3EditShortCircuit = 0;
        int cushionFiresE1Suggestion = 0;
        // Snapshot alias-rescue counter on the registry so the post-loop delta
        // reflects only rescues that happened during this run.
        int aliasRescueBaseline = turnProcessor.toolRegistry().aliasRescueCount();
        List<String> toolNames = new ArrayList<>();

        // B3: track (toolName:path:old_string_hash) tuples that already FAILED in this run.
        // If the model retries the exact same failing call, short-circuit with a diagnostic.
        Set<String> failedCallSignatures = new HashSet<>();

        // E1: track edit_file failure count per file path. After 2 failures suggest write_file.
        Map<String, Integer> editFailuresByPath = new HashMap<>();

        // B2: track paths that were read in this loop execution (read-before-write enforcement).
        Set<String> pathsReadThisTurn = new HashSet<>();

        // Redundant info-gathering suppression: track successful read-only calls.
        // Key = "toolName:normalizedParams". Only suppressed when no mutation has happened since.
        Map<String, String> successfulReadCalls = new HashMap<>();
        boolean mutationSinceStart = false;

        // P0 — action-is-the-answer: collect one-line summaries of successful
        // mutating tool calls. When the model takes a visible action the user
        // asked for, the tool output IS the answer; we do not need to pay for
        // the model to narrate "I created the file" on a local 31B Q4 model.
        List<String> pendingMutationSummaries = new ArrayList<>();

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

            // Per-iteration counters (reset each iteration; used by P0 skip below).
            int mutationsThisIter = 0;
            List<String> mutationSummariesThisIter = new ArrayList<>();

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

                String pathHint = resolvePathHint(effective);
                emitProgress(effective.toolName(), "executing", pathHint);
                LOG.debug("  Executing tool: {} (params: {})", effective.toolName(), effective.parameters());

                // Fix 2: B3 duplicate-failure detection is scoped to edit_file only.
                // For other tools, distinct calls to the same path (e.g., two write_file
                // attempts with different content) must not be conflated.
                boolean isEditFile = "talos.edit_file".equals(effective.toolName());
                if (isEditFile && !strict) {
                    String callSig = buildCallSignature(effective);
                    if (failedCallSignatures.contains(callSig)) {
                        // Fix 3: short-circuited calls are NOT counted in toolsInvoked.
                        retriedCalls++;
                        failedCalls++;
                        cushionFiresB3EditShortCircuit++;
                        String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                                + "[error] This exact edit was already attempted and failed. "
                                + "Call talos.read_file to see the file's current state, "
                                + "then provide the exact raw content (without line-number prefixes) in old_string. "
                                + "Alternatively, use talos.write_file to replace the entire file content."
                                + "\n[/tool_result]";
                        if (useNativePath && i < currentNativeCalls.size()) {
                            messages.add(ChatMessage.toolResult(currentNativeCalls.get(i).id(), diagnostic));
                        } else {
                            messages.add(ChatMessage.user(diagnostic));
                        }
                        LOG.debug("  Skipped duplicate failing edit_file call for path: {}", pathHint);
                        continue;
                    }
                }

                // Redundant info-gathering suppression: if this is a read-only tool
                // with identical params and no mutation has happened since, inject a
                // diagnostic instead of re-executing.
                // Gated off in strict mode (measurement cushion).
                if (!strict && !mutationSinceStart && isReadOnlyTool(effective.toolName())) {
                    String readSig = buildReadCallSignature(effective);
                    String priorResult = successfulReadCalls.get(readSig);
                    if (priorResult != null) {
                        cushionFiresRedundantRead++;
                        String diagnostic = "[tool_result: " + effective.toolName() + "]\n"
                                + "You already gathered this information and the workspace has not changed since then. "
                                + "Answer the user's question now using the evidence you already have."
                                + "\n[/tool_result]";
                        if (useNativePath && i < currentNativeCalls.size()) {
                            messages.add(ChatMessage.toolResult(currentNativeCalls.get(i).id(), diagnostic));
                        } else {
                            messages.add(ChatMessage.user(diagnostic));
                        }
                        LOG.debug("  Suppressed redundant {} call (sig: {})", effective.toolName(), readSig);
                        continue;
                    }
                }

                // Fix 3: count only actually-executed calls.
                totalToolsInvoked++;
                toolNames.add(effective.toolName());

                // Fix 4: B2 read-before-write nudge — computed pre-execution, applied after.
                // Path is NOT marked as read until we confirm the read succeeded (below).
                // Gated off in strict mode (measurement cushion).
                String readBeforeWriteNudge = null;
                if (!strict && "talos.edit_file".equals(effective.toolName()) && pathHint != null) {
                    if (!pathsReadThisTurn.contains(normalizePath(pathHint))) {
                        readBeforeWriteNudge = "\nHint: You did not read this file before editing. "
                                + "Call talos.read_file first to see the current content, "
                                + "then retry the edit with the exact text.";
                    }
                }

                ToolResult result = turnProcessor.executeTool(toolSession, effective, ctx);
                emitToolResult(effective.toolName(), result);

                // Fix 4: mark path as read only after a successful read_file.
                if ("talos.read_file".equals(effective.toolName()) && pathHint != null && result.success()) {
                    pathsReadThisTurn.add(normalizePath(pathHint));
                }

                // Track successful read-only calls for redundancy suppression.
                if (result.success() && isReadOnlyTool(effective.toolName())) {
                    successfulReadCalls.put(buildReadCallSignature(effective), truncateForLog(result.output()));
                }

                // Track mutations so redundancy suppression is invalidated.
                if (isMutatingTool(effective.toolName()) && result.success()) {
                    mutationSinceStart = true;
                    mutatingToolSuccesses++;
                    mutationsThisIter++;
                    // P0: capture a one-line action summary. write_file / edit_file
                    // return strings like "Created index.html (79 lines, 2847 bytes).
                    // Verified: HTML structure OK. [verified...]" — take the first
                    // sentence and prepend a check mark so it reads as a status.
                    String summary = firstSentenceSummary(result.output());
                    if (!summary.isBlank()) {
                        mutationSummariesThisIter.add("✓ " + summary);
                        pendingMutationSummaries.add("✓ " + summary);
                    }
                    // Clear the read cache — workspace state changed.
                    successfulReadCalls.clear();
                }

                // Track failures for B3 (edit_file only) and E1.
                if (!result.success()) {
                    failedCalls++;

                    if (isEditFile) {
                        String callSig = buildCallSignature(effective);
                        failedCallSignatures.add(callSig);

                        // E1: track per-path edit_file failures; suggest write_file after 2nd failure
                        // Gated off in strict mode (measurement cushion — rewrites the raw
                        // tool error with extra guidance the model did not earn).
                        if (!strict && pathHint != null) {
                            int failCount = editFailuresByPath.merge(normalizePath(pathHint), 1, Integer::sum);
                            if (failCount >= 2) {
                                cushionFiresE1Suggestion++;
                                result = ToolResult.fail(ToolError.invalidParams(
                                        result.errorMessage()
                                        + "\nSuggestion: edit_file has failed on this file multiple times. "
                                        + "Consider using talos.write_file with the complete updated file content instead."));
                            }
                        }
                    }
                }

                String resultText = formatToolResult(effective, result);
                if (readBeforeWriteNudge != null) {
                    resultText = resultText + readBeforeWriteNudge;
                }

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

            // 4. Re-prompt the LLM with the updated conversation.
            //
            // P0 — action-is-the-answer short-circuit: if the model just
            // executed at least one successful mutating tool this iteration,
            // do NOT re-prompt. The tool output IS the answer. On local
            // 31B Q4 models the follow-up "okay, I created the file" can
            // cost 10–15 minutes of wall clock (observed: 14m32s in the
            // real transcript, producing empty text). We emit a
            // deterministic status line and exit the loop. If the user
            // wanted a longer explanation alongside the action, they can
            // ask a follow-up question; correctness doesn't depend on
            // model chatter here.
            //
            // Rationale is one-directional: we skip only after MUTATIONS.
            // Pure read-only batches (list_dir, read_file, grep) still
            // re-prompt because the user's question isn't answered by the
            // raw tool output — the model needs to synthesize the answer
            // from what it just read.
            if (mutationsThisIter > 0) {
                currentText = String.join("\n", mutationSummariesThisIter);
                currentNativeCalls = List.of();
                emitProgress("loop", "skip re-prompt after successful mutation", null);
                LOG.debug("P0: skipping re-prompt after {} successful mutation(s) this iteration",
                        mutationsThisIter);
                break;
            }

            // Point 2 — task anchor: inject a transient system-role reminder
            // of the user's current request right before the re-prompt. On
            // the native tool-call path the user message gets pushed several
            // turns back by tool_call + tool_result pairs; without this
            // anchor, local 8B models drift into generic "How can I help?"
            // deflections despite holding all the evidence. The anchor is
            // removed immediately after the call so it doesn't accumulate
            // or bloat future iterations.
            //
            // Point 4 — in-flight compaction: on iterations ≥ 3, replace
            // the bodies of older tool_result messages with one-line
            // summaries. The most recent 2 tool results stay verbatim so
            // the model still has the evidence it just gathered; older
            // ones become "[compacted: read_file(index.html) 22781 chars]".
            // This keeps long multi-read turns (Turns 6-8 in the real
            // transcript) from drowning the user's task in stale content.
            if (iterations >= 3) {
                compactOlderToolResultsInPlace(messages);
            }
            int anchorIndex = -1;
            String userTask = latestUserRequestIn(messages);
            if (userTask != null && !userTask.isBlank()) {
                String pinned = userTask.length() <= 500
                        ? userTask
                        : userTask.substring(0, 500) + "…";
                messages.add(ChatMessage.system(
                        "[Current task — stay focused on this] " + pinned));
                anchorIndex = messages.size() - 1;
            }
            try {
                // P1 — stream the re-prompt to the user. Previously this used
                // chatFull(messages) with no onChunk, which meant the user saw
                // an idle spinner while the model generated tokens silently for
                // multiple minutes. When a streamSink is available, route through
                // chatStreamFull so every token appears live in the TUI.
                java.util.function.Consumer<String> sink = ctx.streamSink();
                LlmClient.StreamResult repromptResult = sink != null
                        ? ctx.llm().chatStreamFull(messages, sink)
                        : ctx.llm().chatFull(messages);
                currentText = repromptResult.text();
                currentNativeCalls = repromptResult.hasToolCalls()
                        ? new ArrayList<>(repromptResult.toolCalls()) : List.of();

                if (currentText == null) currentText = "";
                if (currentText.isEmpty() && currentNativeCalls.isEmpty()) {
                    // No text, no more tools. If this turn already produced one
                    // or more successful mutations, the tool output stands as
                    // the answer — emit a deterministic summary instead of the
                    // misleading "(no answer from model after tool execution)".
                    if (!pendingMutationSummaries.isEmpty()) {
                        currentText = String.join("\n", pendingMutationSummaries);
                    } else {
                        currentText = "(no answer from model after tool execution)";
                    }
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
                    java.util.function.Consumer<String> sink = ctx.streamSink();
                    LlmClient.StreamResult retryResult = sink != null
                            ? ctx.llm().chatStreamFull(messages, sink)
                            : ctx.llm().chatFull(messages);
                    currentText = retryResult.text();
                    currentNativeCalls = retryResult.hasToolCalls()
                            ? new ArrayList<>(retryResult.toolCalls()) : List.of();
                    if (currentText == null) currentText = "";
                    if (currentText.isEmpty() && currentNativeCalls.isEmpty()) {
                        if (!pendingMutationSummaries.isEmpty()) {
                            currentText = String.join("\n", pendingMutationSummaries);
                        } else {
                            currentText = "(no answer from model after retry)";
                        }
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
            } finally {
                // Point 2: remove the transient task anchor so it doesn't
                // persist into the next iteration or the caller's history.
                if (anchorIndex >= 0 && anchorIndex < messages.size()) {
                    ChatMessage m = messages.get(anchorIndex);
                    if ("system".equals(m.role())
                            && m.content() != null
                            && m.content().startsWith("[Current task")) {
                        messages.remove(anchorIndex);
                    }
                }
            }
        }

        boolean hitIterLimit = iterations >= maxIterations
                && (!currentNativeCalls.isEmpty() || ToolCallParser.containsToolCalls(currentText));

        if (hitIterLimit) {
            LOG.warn("Tool-call loop reached max iterations ({}). Stopping.", maxIterations);
            currentText = ToolCallParser.stripToolCalls(currentText)
                    + "\n\n[Tool-call limit reached. Some tool calls were not executed.]";
        }

        // Strip any remaining tool_call blocks from the final answer,
        // then apply SUS_HTML stripping to the prose (safe now that tool_call
        // blocks with their HTML-valued JSON params have been removed).
        String finalAnswer = Sanitize.stripSuspiciousHtml(
                ToolCallParser.stripToolCalls(currentText));

        LOG.debug("Tool-call loop complete: {} iterations, {} tools invoked, {} failed",
                iterations, totalToolsInvoked, failedCalls);

        // N5: compute alias-rescue delta for this run. In strict mode the
        // registry's get() short-circuits before any rescue branch, so this
        // delta is guaranteed to be 0.
        int cushionFiresAliasRescue =
                turnProcessor.toolRegistry().aliasRescueCount() - aliasRescueBaseline;

        return new LoopResult(finalAnswer, iterations, totalToolsInvoked, List.copyOf(toolNames),
                messages, failedCalls, retriedCalls, hitIterLimit, mutatingToolSuccesses,
                cushionFiresRedundantRead, cushionFiresAliasRescue,
                cushionFiresB3EditShortCircuit, cushionFiresE1Suggestion);
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

    /**
     * Walks backwards through {@code messages} for the most recent user-role
     * message. On the native tool-call path, tool results use role="tool",
     * so this reliably returns the original user request. Package-private
     * copy — the loop deliberately does not depend on
     * {@code AssistantTurnExecutor} to avoid a reverse package edge.
     */
    static String latestUserRequestIn(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if ("user".equals(m.role())) {
                String c = m.content();
                return (c == null || c.isBlank()) ? null : c;
            }
        }
        return null;
    }

    /**
     * Point 4 — in-flight tool-result compaction.
     *
     * <p>Replace the bodies of older {@code role="tool"} messages with a
     * one-line summary so a long multi-iteration turn does not push the
     * user's task off the model's attention window. The most recent
     * {@link #KEEP_RECENT_TOOL_RESULTS} tool results are left verbatim so
     * the model retains the evidence it just gathered. Already-compacted
     * messages (detected by the {@code "[compacted:"} prefix) are left
     * untouched so this operation is idempotent across iterations.
     *
     * <p>Only runs on iteration 3 and later, so small turns incur zero
     * cost. Mutates {@code messages} in place.
     */
    static void compactOlderToolResultsInPlace(List<ChatMessage> messages) {
        if (messages == null || messages.size() < 4) return;
        // Find indices of every role="tool" message.
        List<Integer> toolResultIndices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("tool".equals(messages.get(i).role())) {
                toolResultIndices.add(i);
            }
        }
        int keepFrom = toolResultIndices.size() - KEEP_RECENT_TOOL_RESULTS;
        if (keepFrom <= 0) return; // not enough tool results to bother
        for (int k = 0; k < keepFrom; k++) {
            int idx = toolResultIndices.get(k);
            ChatMessage m = messages.get(idx);
            String content = m.content();
            if (content == null || content.isBlank()) continue;
            if (content.startsWith("[compacted:")) continue; // already done
            String summary = summarizeToolResult(content);
            messages.set(idx, ChatMessage.toolResult(m.toolCallId(), summary));
        }
    }

    /** Number of most-recent tool_result messages kept verbatim during compaction. */
    static final int KEEP_RECENT_TOOL_RESULTS = 2;

    /**
     * Summarize a tool_result body into a one-line marker. Preserves the
     * tool name from the {@code [tool_result: NAME]} header when present,
     * plus the original length, so the model can still see what it did
     * without the full content reappearing in every re-prompt.
     */
    static String summarizeToolResult(String body) {
        String tool = "unknown";
        // Parse the leading "[tool_result: talos.X]" header if present.
        if (body.startsWith("[tool_result:")) {
            int close = body.indexOf(']');
            if (close > "[tool_result:".length()) {
                tool = body.substring("[tool_result:".length(), close).trim();
            }
        }
        boolean isError = body.contains("[error]");
        int len = body.length();
        return "[compacted: " + tool + (isError ? " error" : " result")
                + ", " + len + " chars — full output elided to keep context focused]";
    }

    /**
     * Extract the first sentence from a tool output for the P0 "action-is-
     * the-answer" summary. Returns something like {@code "Created index.html
     * (79 lines, 2847 bytes)"} from a longer verified-write success message.
     *
     * <p>Rules:
     * <ul>
     *   <li>Trim leading/trailing whitespace.</li>
     *   <li>Cut at the first sentence terminator ({@code .}, {@code !}, {@code ?})
     *       followed by a space or end of line — so "Created index.html (79 lines,
     *       2847 bytes). Verified: …" becomes "Created index.html (79 lines, 2847 bytes)".</li>
     *   <li>If no terminator is found, take up to the first newline or 160 chars.</li>
     *   <li>Never return a trailing bracket fragment from verification markers
     *       (e.g., drop a trailing "[verified…" tail if present).</li>
     * </ul>
     */
    static String firstSentenceSummary(String output) {
        if (output == null) return "";
        String s = output.strip();
        if (s.isEmpty()) return "";
        // Drop leading "[tool_result: X]\n" header if the caller passed a pre-formatted body.
        if (s.startsWith("[tool_result:")) {
            int close = s.indexOf(']');
            if (close > 0 && close < s.length() - 1) {
                s = s.substring(close + 1).stripLeading();
            }
        }
        // Find first terminator followed by whitespace or newline.
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                if (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1))) {
                    cut = i + 1;
                    break;
                }
            } else if (c == '\n') {
                cut = i;
                break;
            }
        }
        String head = cut > 0 ? s.substring(0, cut).strip() : s;
        // Drop trailing "[verified…" or similar bracket annotations.
        int bracket = head.indexOf(" [");
        if (bracket > 0) head = head.substring(0, bracket).strip();
        // Drop the trailing sentence terminator so it reads as a label,
        // not a full sentence, when appended to a check-mark prefix.
        while (!head.isEmpty()) {
            char last = head.charAt(head.length() - 1);
            if (last == '.' || last == '!' || last == '?') {
                head = head.substring(0, head.length() - 1).stripTrailing();
            } else break;
        }
        // Hard cap for pathological inputs.
        if (head.length() > 160) head = head.substring(0, 157) + "…";
        return head;
    }

    // ---- Call-signature helpers (B3 repeated-failure detection) ----

    /**
     * Build a stable signature string for a tool call to detect repeated identical failures.
     * Format: "toolName:path:hashOf(old_string)". For non-edit tools, old_string is empty.
     */
    static String buildCallSignature(ToolCall call) {
        String path = resolvePathHint(call);
        String oldStr = call.param("old_string");
        if (oldStr == null) oldStr = call.param("oldString");
        int oldHash = oldStr != null ? oldStr.hashCode() : 0;
        return call.toolName() + ":" + (path != null ? path : "") + ":" + oldHash;
    }

    /**
     * Normalize a file path for tracking purposes (forward slashes, lower-cased on Windows).
     */
    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    /**
     * Canonicalize a path value for read-only redundancy signatures.
     *
     * <p>Collapses trivial path variants that produce identical results
     * for read-only tools: {@code "."}, {@code "./"}, {@code ""}, and
     * trailing-separator variants all map to the same canonical form.
     *
     * <p>This is intentionally narrow — only safe for read-only suppression,
     * not for write paths.
     */
    static String canonicalizeReadPath(String path) {
        if (path == null) return "";
        // Normalize separators first
        String p = path.replace('\\', '/');
        // Strip trailing slashes (but don't reduce "/" to "")
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        // Collapse empty and "." to the same canonical form
        if (p.isEmpty() || ".".equals(p)) {
            return ".";
        }
        // Strip leading "./" prefix for relative paths
        if (p.startsWith("./") && p.length() > 2) {
            p = p.substring(2);
        }
        return p;
    }

    // ---- Redundant info-gathering suppression helpers ────────────────────

    /** Read-only tools eligible for redundancy suppression. */
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "talos.read_file", "talos.list_dir", "talos.grep"
    );

    /** Mutating tools that invalidate the read cache. */
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "talos.write_file", "talos.edit_file"
    );

    static boolean isReadOnlyTool(String toolName) {
        return READ_ONLY_TOOLS.contains(toolName);
    }

    static boolean isMutatingTool(String toolName) {
        return MUTATING_TOOLS.contains(toolName);
    }

    /**
     * Build a signature for a read-only tool call: "toolName:sortedParams".
     * Uses {@link #canonicalizeReadPath} so trivial path variants like
     * {@code "."} and {@code "./"} produce the same signature.
     */
    static String buildReadCallSignature(ToolCall call) {
        var sb = new StringBuilder(call.toolName()).append(":");
        if (call.parameters() != null) {
            call.parameters().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(e.getKey()).append("=")
                            .append(canonicalizeReadPath(e.getValue())).append(";"));
        }
        return sb.toString();
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

