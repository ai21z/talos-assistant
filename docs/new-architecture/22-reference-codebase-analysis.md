# 22. Reference Codebase Analysis — OpenClaw & NemoClaw vs TALOS

**Date:** 2026-04-09 (revised four times)  
**Baseline:** `v0.9.0-beta-dev` (1681 tests, 0 failures)  
**Previous baselines:** `2df38f4` (1653 tests), `879cfd0` (1572 tests), `7e63677` (802 tests), 1575 tests (pre-G14), 1623 tests (G14 first pass)  
**Purpose:** Extract actionable patterns from OpenClaw and NemoClaw, map them against TALOS's **current** state, and define remaining work.

---

## 0. Why this document exists

Document 21 re-evaluated the architecture docs (00–20) against reference codebases and identified five priorities: tool wiring, context window management, system prompt consolidation, code-aware chunking, and approval gate activation. This document went deeper — reading both OpenClaw and NemoClaw source code in detail — and produced concrete adoption decisions and implementation slices.

**This revision** updates the document against the current codebase, which has implemented all four originally proposed slices plus significant additional work. The gap analysis and slice plan are updated to reflect reality.

---

## 1. Patterns Worth Adopting from OpenClaw

### 1A. ContextEngine Lifecycle → **Adopted (adapted)**

OpenClaw's `ContextEngine` interface defines a pluggable lifecycle:

```
bootstrap() → ingest() → assemble() → compact() → afterTurn() → maintain() → dispose()
```

**TALOS mapping (updated):**

| OpenClaw | TALOS equivalent | Status |
|---|---|---|
| `assemble()` | `ContextPacker.pack()` + `RagService.prepare()` | ✅ Shipped |
| `compact()` | `ConversationCompactor` + `ConversationManager.maybeCompact()` | ✅ Shipped — auto-triggers after 6 turn pairs when history exceeds 25% budget |
| `afterTurn()` | `MemoryUpdateListener.onTurnComplete()` via `SessionListener` | ✅ Shipped — centralized in `TurnProcessor`, modes no longer own memory updates |
| `dispose()` | `Session.close()` (implements `AutoCloseable`) + `RunCmd` finally block | ✅ Shipped — fires close listeners, supports try-with-resources |
| `estimatedTokens` | `ContextResult.estimatedTokens()` + `ConversationManager.estimateTokens()` | ✅ Shipped |

**Original recommendation status:**

1. ~~Centralize afterTurn~~ → **Done.** `MemoryUpdateListener` registered with `TurnProcessor`. `AskMode` and `RagMode` no longer call `memory.update()` directly.
2. ~~Add ConversationManager~~ → **Done.** `dev.talos.core.context.ConversationManager` wraps `SessionMemory` + `TokenBudget`. Provides `buildHistory(availableTokens)`, `maybeCompact(LlmClient)`, and sketch-based compaction.
3. ~~Add Session.close()~~ → **Done.** `Session` implements `AutoCloseable` with close listeners. `RunCmd` calls `session.close()` in a finally block.

**Verdict:** This pattern is fully adopted. No further action needed.

### 1B. Security Audit Framework → **Defer (unchanged)**

OpenClaw's `audit.ts` (1441 lines) provides `SecurityAuditFinding` with `checkId/severity/title/detail/remediation` and a `SecurityAuditReport` with summary counts. TALOS already has:

- `Sandbox` — workspace-only path policy ✅
- `Redactor` — output redaction ✅
- `Audit` — JSONL audit logger ✅
- `ApprovalGate` — operation gating seam ✅
- `CliApprovalGate` — real stdin-based approval for WRITE/DESTRUCTIVE tools ✅ (new since original doc)

**Assessment:** Unchanged. A structured scan-and-report framework makes sense for platforms with plugins, dynamic code loading, and external channels. TALOS is a single JAR with no third-party code execution. The current primitives — now including a real approval gate — cover real threats.

**Recommendation:** Defer. Revisit when TALOS exposes MCP endpoints or runs third-party tools.

### 1C. Session Lifecycle Events → **Adopted**

**Original recommendation:** Add a `SessionListener` interface to `dev.talos.runtime`.

**Current state:**

```java
// dev.talos.runtime.SessionListener
public interface SessionListener {
    default void onTurnComplete(TurnResult result, String userInput) {}
    default void onSessionEnd() {}
}
```

Wired in `TurnProcessor`. `MemoryUpdateListener` is the primary implementation — handles memory recording and auto-compaction. `Session.close()` fires `onSessionEnd()` on registered close listeners.

**Verdict:** Fully adopted. Signature is slightly richer than originally proposed (includes `userInput` parameter). No further action needed.

---

## 2. Patterns Worth Adopting from NemoClaw

### 2A. SSRF Validation → **Irrelevant (unchanged)**

NemoClaw validates outbound URLs against private network CIDR ranges. TALOS is a local agent that talks to `localhost:11434` (Ollama). No user-controlled URL fetching exists.

**Verdict:** Not applicable. If `WebMode` fetches user-supplied URLs in the future, revisit.

### 2B. Snapshot/Restore → **Defer (seam exists)**

NemoClaw's snapshot/restore handles config migration with manifests and rollback. TALOS now has:

- `SessionStore` interface in `dev.talos.runtime` ✅
- `SessionData` record (sessionId, workspace, sketch, turnCount, createdAt) ✅
- `NoOpSessionStore` as V1 implementation ✅

**Verdict:** The seam exists. `SqliteSessionStore` at `~/.talos/sessions/` can be built when resume capability is needed. No further action now.

### 2C. Credential Isolation → **Partially relevant, no action needed (unchanged)**

NemoClaw scopes env vars to subprocesses and never persists secrets. TALOS:
- Reads `TALOS_OLLAMA_MODEL` from env ✅
- `Redactor` masks secrets in audit ✅
- Ollama is auth-free by default ✅
- No credential files exist

**Verdict:** No action now. When adding API-key-based backends, ensure keys come from env vars only, and `Redactor` covers the key formats.

### 2D. State Management → **Adopted (seam)**

**Original recommendation:** Add a `SessionStore` interface to `dev.talos.runtime`.

**Current state:** Implemented as described — `SessionStore` interface with `save/load/delete` contract, `SessionData` record, `NoOpSessionStore` for V1. `Session` carries a `SessionStore` reference. 11 tests cover the seam.

**Verdict:** Adopted. Future `SqliteSessionStore` can provide persistence without architectural changes.

---

## 3. Patterns to Explicitly REJECT (unchanged)

| Pattern | Source | Why reject for TALOS |
|---|---|---|
| Plugin/extension ecosystem | OpenClaw | Single JAR, no dynamic loading beyond SPI. Adds attack surface without V1 value. |
| MCP server mode | OpenClaw | Tool execution is internal-first (LLM calls tools via `ToolCallLoop`). External MCP exposure is post-V1. |
| Blueprint runner (plan/apply/rollback) | NemoClaw | Task/Step planning explicitly deferred per doc 21 §2B. Turn model is correct for V1. |
| Multi-workspace / context engine registry | OpenClaw | `Session.workspace()` = one `Path`. Workspace = directory. Per doc 21 §2F. |
| Complex message normalization | OpenClaw | One backend at a time (Ollama via SPI). `ChatMessage` is already canonical. No multi-provider translation needed. |
| Legacy compatibility proxy | OpenClaw | No external consumers of TALOS's context API. `ContextPacker` is internal. No backward-compat shim needed. |
| Channel/gateway/pairing | OpenClaw | TALOS is CLI-only, local-only. No network channels. |

---

## 4. Gap Analysis (updated 2026-04-08)

### Previously identified gaps — all resolved

| # | Gap | Original status | Current status |
|---|---|---|---|
| **G1** | Tools not wired | ❌ Missing | ✅ **Shipped.** `TurnProcessor.executeTool()` dispatches with sandbox + approval. 6 concrete tools: `ReadFileTool`, `FileWriteTool`, `FileEditTool`, `GrepTool`, `ListDirTool`, `RetrieveTool`. `ToolCallLoop` runs iterative tool-call rounds (max 10). |
| **G2** | Context window unmanaged | ❌ Missing | ✅ **Shipped.** `ConversationManager` provides `buildHistory(availableTokens)`. `ConversationCompactor` auto-summarizes old turns into a sketch. Token budget is coordinated: history tokens deducted from snippet budget. |
| **G3** | System prompts fragmented | ❌ Missing | ✅ **Shipped.** `SystemPromptBuilder` composes from `prompts/sections/` (identity + mode rules + tools + conversation). Both `AskMode` and `RagMode` use it. Old monolithic prompt files deleted. |
| **G4** | ApprovalGate is NoOp | ❌ Missing | ✅ **Shipped.** `CliApprovalGate` prompts user via stdin for WRITE/DESTRUCTIVE operations. `TurnProcessor` checks `riskLevel()` before execution. Denied operations return `ToolResult.fail()`. |
| **G5** | Tool execution not sandboxed | ❌ Missing | ✅ **Shipped.** `ToolContext` record carries `workspace + sandbox + config`. Every tool receives it at execution time. `Sandbox.allowedPath()` enforced in all file-touching tools. |
| **G6** | afterTurn not centralized | ⚠️ Partial | ✅ **Shipped.** `MemoryUpdateListener` + `SessionListener` pattern. Modes no longer own memory management. `TurnProcessor` fires post-turn hooks. |
| **G7** | No conversation compaction | ❌ Missing | ✅ **Shipped.** `ConversationCompactor` summarizes old turns via LLM. `ConversationManager.maybeCompact()` auto-triggers at 6 pair threshold when tokens exceed 25% budget. Sketch prepended to history. |
| **G8** | Tool contract lacks context | ❌ Missing | ✅ **Shipped.** `ToolContext` record with `workspace`, `sandbox`, `config`. `TalosTool.execute(ToolCall, ToolContext)` is the primary contract. |

### New gaps identified (post-implementation)

| # | Gap | Current state | Impact | Priority |
|---|---|---|---|---|
| **G9** | Conversation continuity — model forgets prior turns | `ConversationManager` and `SystemPromptBuilder.withHistory()` are wired, but the model still loses conversational thread on creative/multi-turn tasks (observed with Gemma 4) | Users experience broken multi-turn interaction for non-retrieval tasks (e.g., iterative ASCII art, refining a previous answer) | **High** — ✅ Addressed |
| **G10** | No structured task/execution model | Turn model is flat: one user prompt → one response (possibly with tool calls within the turn). No concept of multi-step task, subtask, partial completion, or resume. | Limits ability to handle "do X then Y then Z" requests or report incremental progress | **Medium** — not V1-blocking but shapes future agent capability |
| **G11** | `RagService` still owns session-irrelevant concerns | `RagService` holds `Config` and `Indexer` but creates new `LlmClient` and `LuceneStore` per call to `ask()`. No session binding. This is architecturally acceptable but means `RagService.ask()` is essentially a static utility. | Acceptable for V1. Potential lifecycle inefficiency if called many times per session. | **Low** — correct enough for now |
| **G12** | `Context` record surface area | 15-field record with 5 backward-compat constructors + fluent builder. Carries everything from config to stream sink. | Coupling magnet. Modes, commands, and tools all receive the full bag. Hard to test in isolation without building a nearly-complete Context. | **Medium** — worth narrowing interfaces in a future cleanup, but not blocking |
| **G13** | No `/undo` or operation rollback | Write tools (`FileWriteTool`, `FileEditTool`) modify files with no undo mechanism. `CliApprovalGate` prevents unintended writes, but approved writes are permanent. | Low risk for V1 (single-user local agent, files under git). Higher risk if agent autonomy increases. | **Low** — git is the safety net for V1 |
| **G14** | CLI doesn't feel natural — model blind to workspace | System prompt didn't include workspace path, AskMode prohibited tool use, tools-preamble biased toward NOT calling tools, routing missed common workspace terms, empty retrieval gave no guidance | Users experience "I can't see your files" responses, model outputs code blocks instead of using write_file, routing misses "check the directory" or "this site" | **High** — ✅ Addressed |

---

## 5. Implementation Slices — Status (updated 2026-04-08)

### Slice 1: Wire Tool Seam + First Tools → ✅ COMPLETE

**Branch:** `feature/tool-wiring` (merged)  
**Delivered:** LLM-invocable tools that read, write, edit files and search the workspace.

**Created (all shipped):**
- `dev.talos.tools.ToolContext` — record: `Path workspace`, `Sandbox sandbox`, `Config config`
- `dev.talos.tools.impl.ReadFileTool` — reads workspace file via Sandbox
- `dev.talos.tools.impl.FileWriteTool` — creates/overwrites files with approval
- `dev.talos.tools.impl.FileEditTool` — string replacement editing with approval
- `dev.talos.tools.impl.GrepTool` — text/regex search across workspace files
- `dev.talos.tools.impl.ListDirTool` — lists directory contents
- `dev.talos.tools.impl.RetrieveTool` — wraps `RagService.prepare()` as callable tool

**Modified (all shipped):**
- `TalosTool` — `execute(ToolCall, ToolContext)` as primary contract
- `ToolRegistry` — `execute(ToolCall, ToolContext)` overload
- `TurnProcessor` — full tool dispatch with sandbox + approval gate
- `ToolCallLoop` — iterative tool-call rounds with LLM re-prompting
- `ToolCallParser` — `<tool_call>` block extraction and stripping
- `Context` — carries `ToolRegistry`, `ToolCallLoop`, `streamSink`

---

### Slice 2: Conversation Manager + Context Window Tracking → ✅ COMPLETE

**Branch:** `feature/conversation-manager` (merged)  
**Delivered:** Long sessions don't overflow context windows. Memory update centralized.

**Created (all shipped):**
- `dev.talos.core.context.ConversationManager` — wraps SessionMemory + TokenBudget with `buildHistory()`, `maybeCompact()`, and sketch persistence
- `dev.talos.core.context.ConversationCompactor` — LLM-based turn summarization into a 2-4 sentence sketch
- `dev.talos.runtime.SessionListener` — interface with `onTurnComplete(TurnResult, String)` and `onSessionEnd()`
- `dev.talos.runtime.MemoryUpdateListener` — centralized memory recording + auto-compaction

**Modified (all shipped):**
- `TurnProcessor` — fires `SessionListener` after each turn
- `AskMode.buildMessages()` — uses `ConversationManager.buildHistory()` instead of raw turn dump
- `RagMode` — no longer calls `ctx.memory().update()` (moved to TurnProcessor)
- `Session` — `close()` method with `AutoCloseable`, close listeners
- `SessionMemory` — `pruneOldest(count)` for post-compaction cleanup

---

### Slice 3: System Prompt Consolidation + Tool Awareness → ✅ COMPLETE

**Branch:** `feature/prompt-consolidation` (merged via `feature/lifecycle-and-legacy-cleanup`)  
**Delivered:** Single composable system prompt builder, tool-aware, history-aware.

**Created (all shipped):**
- `dev.talos.core.llm.SystemPromptBuilder` — composes from: identity + mode rules (ask/rag) + tool descriptions + conversation continuity
- `src/main/resources/prompts/sections/` — composable sections: `identity.txt`, `ask-rules.txt`, `rag-rules.txt`, `tools-preamble.txt`, `conversation.txt`

**Modified (all shipped):**
- `AskMode` and `RagMode` — use `SystemPromptBuilder` instead of reading monolithic prompt files
- Old monolithic prompt files deleted: `system.txt`, `cli-system.txt`, `ask-system.txt`, `rag-system.txt`
- `RagService.buildSystemPrompt()` delegates to `SystemPromptBuilder.forRag()`

---

### Slice 4: ApprovalGate Activation for Tool Calls → ✅ COMPLETE

**Branch:** `feature/streaming-and-safety` (merged)  
**Delivered:** Real approval gate for write/destructive tool operations.

**Created (all shipped):**
- `dev.talos.runtime.CliApprovalGate` — prompts user via stdin for WRITE/DESTRUCTIVE operations, accepts y/yes
- `dev.talos.tools.ToolRiskLevel` — enum: `READ_ONLY`, `WRITE`, `DESTRUCTIVE` with `requiresApproval()`

**Modified (all shipped):**
- `TurnProcessor.executeTool()` — checks `riskLevel()` and calls `approvalGate.approve()` before execution
- `ToolDescriptor` — carries `riskLevel` field
- `TalosBootstrap` — wires `CliApprovalGate` as the default gate

---

## 6. Additional Work Shipped Beyond Original Slices

The following significant work was completed after the original four slices, driven by practical testing and architectural hardening:

| Feature | Key classes/changes | Impact |
|---|---|---|
| **Code-aware chunking** | `CodeBlockSplitter` (3 strategies: brace, indent, blank-line) integrated into `Chunker` | Chunks align on language boundaries (classes, methods, functions) instead of arbitrary positions |
| **SourceBoostStage** | New retrieval pipeline stage after RRF fusion | Biases toward production code, penalizes test/docs/config paths |
| **Assistant-first routing** | `PromptRouter` (515 lines) with COMMAND/RETRIEVE/ASSIST + workspace-aware PascalCase + sticky follow-up | Eliminates RAG-as-default-fallback. Natural conversation works without triggering retrieval. |
| **AssistantTurnExecutor** | Shared streaming/non-streaming/tool-loop/error-handling for AskMode and RagMode | Eliminates ~80 lines of duplicated turn execution per mode |
| **TalosBootstrap** | Composition root extracted from `ReplRouter` | `ReplRouter` is thin dispatch (110 lines). All construction/wiring in one auditable place. |
| **Error resilience** | `EngineException` hierarchy: `ConnectionFailed`, `ModelNotFound`, `Transient` | Typed errors with user-facing guidance. Tool-call loop handles transient retries. |
| **Dead code removal** | Legacy engine stubs (LlamaCpp, Gpt4All), `SnippetBuilder`, monolithic prompts deleted | 6 dead engine files + dead code removed. Net: -280 lines. |
| **SessionStore seam** | `SessionStore` interface, `SessionData` record, `NoOpSessionStore` | Future resume capability without architectural changes |
| **Streaming support** | `streamSink` consumer, `Result.Streamed`, `RenderEngine` spinner integration | Real-time token-by-token output to terminal |
| **Route diagnostics** | `/route` command, `PromptRouter.explainRoute()` | Developer observability into routing decisions |
| **IndexedWorkspaceSymbolChecker** | Lucene-backed symbol lookup with caching for PascalCase disambiguation | Workspace-aware routing: distinguishes code symbols from brand names |
| **G9: Conversation continuity** | `conversation.txt` strengthened (12 lines), `ConversationManager.buildHistoryForAssist()` (55% budget), `ConversationCompactor` sketch doubled to 2000 chars / 4-8 sentences, `SystemPromptBuilder` default fallback updated | AskMode gets 2.2× more history context. Sketch retains creative artifacts. Model explicitly instructed to work from last response. |
| **G14: Natural CLI feel** | `SystemPromptBuilder.withWorkspace(Path)`, `identity.txt` expanded (workspace awareness), `ask-rules.txt` rewritten (tool-friendly), `tools-preamble.txt` expanded (WHEN TO USE TOOLS + File Modification Protocol), `rag-rules.txt` expanded (file modification + tool fallback), `PromptRouter` expanded patterns (WORKSPACE_FRAME, ANCHORED_TECH_NOUN, isActionLike, WORKSPACE_PROXIMITY, isQuestionLike), `RagMode` empty-index guidance | Model knows its workspace path. AskMode can use tools proactively. Empty retrieval triggers tool guidance instead of "I can't see." Routing catches natural workspace terms (site, app, folder, directory, component, template, etc.), deictic references ("here", "workspace", "working on"), contractions ("what's"), and inspection verbs. `isQuestionLike` expanded with "do", "which", "tell me", contractions. 78 new tests total. |
| **G14.3: File-ops prompt hardening** | `tools-preamble.txt` restructured (write_file example, CRITICAL section elevated before tool list, 6 NEVER rules), `identity.txt` explicit file-creation capability, `ask-rules.txt` + `rag-rules.txt` write_file reinforcement, `SystemPromptBuilder` DEFAULT_TOOLS_PREAMBLE mirrored | Fixes Gemma 4 refusing to call `talos.write_file` and dumping code blocks instead. Concrete write_file example early in prompt. CRITICAL section with strong NEVER language. Repeated across identity + mode rules + tools preamble to counter attention decay in small LLMs. 8 new SystemPromptBuilder tests. |
| **G15: Slash command autocomplete** | `SlashCommandCompleter` (JLine Completer), `CommandGroup` extracted to own public file, `CommandSpec.groupDisplayName()`, `ReplRouter.getRegistry()`, `RunCmd` wired into `LineReaderBuilder` | Tab-completion for `/` slash commands. Typing `/` lists all commands, further typing filters by prefix. Aliases included. Groups and descriptions shown in completion menu. Case-insensitive. Non-slash input produces no completions (doesn't interfere with chat). 20 new SlashCommandCompleterTest tests. |
| **G16: Help layout redesign** | `CommandGroup` enum redesigned (SESSION, MODELS, KNOWLEDGE, SECURITY, DEBUG), `HelpCommand` rewritten (clean columns, group headers, footer hints), all 21 command summaries tightened to <30 chars, `CommandSpec` backward-compat default updated | Clean, scannable `/help` output. 5 logical groups with visual hierarchy (violet headers, blue usage, grey descriptions). 24-char aligned columns. Footer shows `/help <cmd>` hint + Tab autocomplete. Fixes 5 compilation errors from inconsistent enum values. 24 files changed, 0 test regressions. |

---

## 7. Summary (updated 2026-04-09)

### From OpenClaw — adopted:
- ✅ Centralized afterTurn lifecycle (`SessionListener` + `MemoryUpdateListener`)
- ✅ ConversationManager with token-aware history and auto-compaction
- ✅ Session close/dispose lifecycle (`AutoCloseable`)

### From NemoClaw — adopted:
- ✅ State management seam (`SessionStore` + `NoOpSessionStore`)
- ✅ Credential isolation discipline (env-vars only, `Redactor` covers)

### Rejected (unchanged):
Plugin ecosystem, MCP server, SSRF, blueprint runner, multi-workspace, channel/gateway, legacy compat proxy.

### Current project stats:
- **1681 tests**, 0 failures
- **6 LLM-invocable tools** with sandbox + approval gate
- **Composable system prompt** with tool awareness, workspace awareness, and conversation continuity
- **Auto-compacting conversation** with sketch-based memory (2000 char / 4-8 sentence sketches)
- **Mode-aware history budgets** — AskMode 55%, RagMode 25%
- **Assistant-first routing** with workspace-aware disambiguation and expanded vocabulary
- **Code-aware chunking** with 3 language strategies
- **Full streaming** with tool-call loop integration
- **Natural CLI feel** — model knows workspace path, proactively uses tools, handles empty retrieval gracefully
- **File-ops prompt hardening** — concrete write_file examples, CRITICAL section, attention-decay countermeasures for small LLMs
- **Slash command autocomplete** — JLine tab-completion for `/` commands with prefix filtering, groups, descriptions
- **Clean help layout** — 5 logical command groups, tight summaries, aligned columns, visual hierarchy

### Remaining priorities (next slices):

1. **G14 — Real-world validation.** File-ops prompt hardening is shipped. Needs manual testing with Gemma 4 on real workspaces to confirm the model uses write_file proactively and doesn't dump code blocks.

2. **G12 — Context narrowing.** `Context` is a 15-field dependency bag. Future refactoring could split it into narrower interfaces (`ModeDeps`, `ToolExecutionDeps`, `CommandDeps`). Not urgent but improves testability.

3. **G10 — Structured task model.** For post-V1 agent capabilities, a task/subtask/completion model would allow multi-step workflows. The current turn model is correct for V1 CLI use.

### What NOT to do next:
- Do not add MCP server mode — tool execution is internal-first and working
- Do not add plugin ecosystem — single JAR, no dynamic loading needed
- Do not add multi-workspace support — one `Session.workspace()` is correct
- Do not refactor `Context` into full DI framework — the builder pattern works
- Do not prematurely add structured task/planning model — turn model is adequate for V1
