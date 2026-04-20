# 24 — TALOS v0.9.0-beta: Comprehensive Project Review

**Date:** 2025-04-11  
**Branch:** `v0.9.0-beta-dev`  
**Reviewer:** AI assistant (requested by maintainer)  
**Scope:** Code quality, testability, scalability, usability, architecture  

---

## 0. Executive Summary

TALOS is a **well-architected, privacy-first local AI CLI** at ~15K production
LOC, 109 test files (16K LOC), with a clean composition-root pattern, a
multi-stage retrieval pipeline, and thoughtful tool/safety infrastructure.

**What works well:** Retrieval pipeline design, prompt engineering layering,
sandbox/approval gate safety model, embedding profile abstraction, conversation
compaction, and the overall separation of concerns.

**What is holding it back:** UX gaps relative to top-tier CLIs (local coding assistant,
aider, Cursor). Tool invocation is unreliable due to XML-based tool calling
with small local models. No session persistence, no `/undo`, no streaming
progress for indexing, and the first-run experience requires JavaFX. Auto mode
routing is strong technically but the user doesn't see *why* decisions are made.

| Axis | Score | Comment |
|---|---|---|
| Architecture | **8.0/10** | Clean layers, right abstractions. Context record is a coupling magnet (known). |
| Code Quality | **7.5/10** | Consistent style, low duplication. RunCmd.Limits dup, 27 command files could consolidate. |
| Testability | **7.0/10** | Good retrieval/embedding coverage. Integration tests need real model or mock. JaCoCo bar at 20% is too low. |
| Scalability | **7.5/10** | Pipeline and profile systems extend well. Tool system is ready for MCP. Single-workspace limit is fine for V1. |
| **Usability (UX)** | **5.5/10** | The critical axis. Multiple pain points stacking. Detailed below. |

**Overall: 7.1/10** — strong foundation, UX is the gap between "engineering
project" and "product people reach for daily."

---

## 1. Architecture — 8.0/10

### Strengths

1. **Composition root** (`TalosBootstrap`, 193 lines) — single place where all
   services are constructed. No DI framework, no magic. Easy to trace.

2. **Retrieval pipeline** — stateless stages (`BM25 → KNN → RRF → SourceBoost
   → Rerank → Dedup`) with `RetrievalTrace` per-stage timing. Builder pattern.
   Adding/removing stages is a one-liner.

3. **Mode system** — `ModeController` with `PromptClassifier` (assistant-first,
   418 lines of battle-tested regex routing). The "false retrieval is worse than
   missed retrieval" principle is correct.

4. **Tool system** — `ToolRegistry` with fuzzy name resolution, `TalosTool`
   interface, `ToolContext` with workspace-scoped sandbox. `ApprovalGate`
   interface ready for real approval flow.

5. **Embedding profile abstraction** — `EmbeddingProfile` record with
   fingerprint-based cache isolation, query/document split. Ready for provider
   expansion without touching call sites.

6. **Prompt architecture** — composable sections (`identity.txt`,
   `tools-preamble.txt`, `rag-rules.txt`, `ask-rules.txt`, `conversation.txt`)
   via `SystemPromptBuilder`. Mode-specific prompt composition.

7. **Safety model** — `Sandbox` (symlink-aware, allow/deny lists),
   `ApprovalGate` (risk-level gating), tool result truncation (32K cap),
   content size guards on all file tools.

### Weaknesses

1. **Context record** (15 fields, 4 backward-compat constructors) is a known
   coupling magnet. Every mode, every command takes `Context`. The builder
   helps but the record is too wide.

2. **Package structure has some tension:**
   - `cli/commands/` has 27 files — many are tiny (10-30 lines). Could benefit
     from grouping or a declarative registration approach.
   - `cli/cmds/` vs `cli/commands/` — two command packages is confusing.
   - `runtime/` has 14 files that span tool execution, session management,
     and prompt tracing — could be split.

3. **DevMode** handles `ls`/`open`/`show` via regex pattern matching, while
   the same operations exist as tools (`talos.list_dir`, `talos.read_file`).
   This is duplicate capability with different code paths. The tool path is
   better (sandbox-enforced, model-invocable). DevMode should eventually
   delegate to tools.

4. **No error domain model** — exceptions propagate as raw Java exceptions.
   `EngineException` hierarchy is good for LLM errors, but there's no
   equivalent for indexing, parsing, or config errors. Error recovery is
   ad-hoc per call site.

### Recommendations

- **P2:** Introduce a `ContextScope` or split Context into mode-specific
  interfaces (e.g., `ToolCapableContext`, `RagContext`, `LlmContext`).
- **P3:** Consolidate `cli/cmds/` into `cli/commands/` (they already share
  purpose, the split is historical).
- **P3:** Deprecate DevMode's file operations in favor of tool invocations
  once tool reliability is solid.

---

## 2. Code Quality — 7.5/10

### Strengths

1. **Consistent Java 21 style** — records where appropriate, sealed types for
   Result, pattern matching, `List.copyOf` defensiveness.

2. **Small files** — median file is ~100-150 lines. Largest production files
   are `PromptClassifier` (418), `RagMode` (375), `RagService` (328),
   `EmbeddingsClient` (382). None are unmanageable.

3. **Low duplication** — `AssistantTurnExecutor` extracted shared LLM call
   logic. `ContextPacker` unified snippet assembly. `SystemPromptBuilder`
   unified prompt composition.

4. **Security-conscious** — `Sanitize.sanitizeForPrompt()` on all snippets,
   `Sanitize.sanitizeForOutput()` on responses, sandbox on all tool paths.

5. **Logging** — consistent SLF4J usage with `LOG.debug()` for tracing,
   `LOG.warn()` for degraded paths.

### Weaknesses

1. **`RunCmd.Limits`** (lines 176-209) duplicates `dev.talos.cli.repl.Limits`.
   Both are active. This should be a single class.

2. **`Config.data`** is `public Map<String,Object>` — raw map access scattered
   across ~20 call sites using `CfgUtil.map()` / `CfgUtil.longAt()`. No type
   safety. A typo in a config key silently returns defaults.

3. **FileWriteTool** and **FileEditTool** have identical parameter validation
   patterns (null check, blank check, resolve, sandbox check). This could be
   a shared method or base class.

4. **PromptClassifier** — 418 lines of regex patterns is maintainable but fragile
   for edge cases. No fuzzy/ML fallback. Adding new intent types means adding
   more regex patterns. The `:route` diagnostic is excellent mitigation.

5. **Magic numbers scattered:**
   - `RrfFusionStage(60)` — RRF k parameter
   - `COMPACTION_THRESHOLD_PAIRS = 6`
   - `HISTORY_BUDGET_FRACTION = 0.25`
   - `DEFAULT_MAX_ITERATIONS = 10` (tool loop)
   - These should be configurable or at least documented in config.yaml.

### Recommendations

- **P1:** Delete `RunCmd.Limits` and use `dev.talos.cli.repl.Limits` everywhere.
- **P2:** Introduce typed config accessors (e.g., `cfg.rag().topK()`) instead
  of raw map access. Even a simple facade over the map would prevent typo bugs.
- **P3:** Extract common tool parameter validation into a `ToolValidation`
  utility (resolve, sandbox check, size guard).

---

## 3. Testability — 7.0/10

### Strengths

1. **109 test files, 16,294 LOC** — test code slightly exceeds production code.
   That's a healthy ratio for a CLI with complex retrieval logic.

2. **Full test suite passes** (0 failures on `v0.9.0-beta-dev`).

3. **Excellent embedding/retrieval test coverage** — `EmbeddingProfileTest` (17),
   `EmbeddingsFactoryTest` (19), `PromptClassifierTest` (extensive), pipeline stage
   tests, `ContextPackerTest`, `ConversationManagerTest`.

4. **PromptClassifier has comprehensive routing tests** — the most critical routing
   logic is well-covered with explicit positive/negative cases.

5. **Tool tests exist** for all 6 tools (`FileWriteToolTest`, `FileEditToolTest`,
   `ReadFileToolTest`, `ListDirToolTest`, `GrepToolTest`, `RetrieveToolTest`).

### Weaknesses

1. **JaCoCo minimum is 20%** — this is a "don't regress catastrophically" gate,
   not a quality gate. For a product aiming at top-tier CLI standards, 50-60%
   would be appropriate as a floor.

2. **No integration tests with a real or mocked LLM.** The tool-call loop
   (`ToolCallLoop`), conversation compaction, and multi-turn flows are tested
   in isolation but never end-to-end. Tool invocation reliability — the #1 UX
   problem — has no automated regression test.

3. **No retrieval quality regression tests.** When you change the pipeline
   (add a stage, tune RRF k, adjust reranker threshold), there's no test that
   asserts "query X should return file Y in top-3." This is table-stakes for
   a retrieval system.

4. **`RagService` is hard to unit test** — it opens real Lucene indexes and
   creates real embedding clients. The `buildDefaultPipeline` is package-private
   for testing, which is good, but `prepare()` itself has no seam for injecting
   a mock store.

5. **Test naming is inconsistent** — some use `@Nested` inner classes (good),
   some use flat method names, some mix.

### Recommendations

- **P1:** Create a retrieval quality test suite: 5-10 golden queries against a
  small fixture corpus, asserting expected files appear in top-K.
- **P1:** Raise JaCoCo floor to 40% now, 50% after next test pass.
- **P2:** Add a `ToolCallLoop` integration test using a mock LLM that returns
  tool_call XML, verifying the loop executes tools and feeds results back.
- **P3:** Introduce an `LlmClient` interface (currently it's a concrete class)
  to enable mock-based testing of the entire turn pipeline.

---

## 4. Scalability — 7.5/10

### Strengths

1. **Pipeline is designed for extension** — `RetrievalStage` interface, builder
   pattern, stages are stateless. Adding a hypothetical `SemanticBoostStage` or
   `CrossEncoderStage` is trivial.

2. **Embedding profile system** is ready for multiple providers without touching
   call sites. Config resolution, fingerprint-based cache, fail-fast guard.

3. **Tool system** — `TalosTool` interface + `ToolRegistry` with fuzzy resolution
   + `ToolDescriptor` schema + `ToolRiskLevel` gating. Adding a new tool is:
   implement interface, register in `TalosBootstrap`, done.

4. **MCP-ready seam** — `ToolDescriptor` already has JSON schema, risk level,
   and descriptors. The `api/` package (`LoqjKnowledgeEngine`) provides a
   programmatic entry point separate from CLI.

5. **Single-workspace-at-a-time** is correct for V1. The architecture doesn't
   preclude multi-workspace (each workspace gets its own index directory).

### Weaknesses

1. **Config is a HashMap** — adding new config sections means more raw map
   access. As the system grows (more providers, more tool types, more modes),
   this becomes a maintenance burden.

2. **No plugin/extension point for tools** — tools are hard-coded in
   `TalosBootstrap`. For MCP integration, you'll need a dynamic registration
   mechanism (e.g., tool directory scanning, MCP server discovery).

3. **Conversation history is in-memory only** — if the process dies, all
   context is lost. For a "private assistant" product, session persistence is
   a scaling requirement (not just UX).

4. **No batch/pipeline mode** — can't do `talos < queries.txt` or
   `echo "explain this" | talos --workspace ./project`. The REPL is the only
   interaction model (RunCmd has `--ask` for single-shot, but no stdin pipe
   support).

### Recommendations

- **P2:** Add session persistence (SQLite or flat JSON file per workspace).
  This is both a scalability and UX item.
- **P3:** Plan the MCP tool registration API — even if not implementing now,
  design the `ToolProvider` SPI so third-party tools can be loaded.
- **P3:** Add `--pipe` or detect stdin isatty for non-interactive batch mode.

---

## 5. Usability (UX) — 5.5/10

**This is the critical section.** The engineering is solid but the user
experience has multiple stacking pain points that, individually seem minor,
but collectively make TALOS feel like a development tool rather than a product.

### Comparison baseline: top-tier AI CLIs

| Feature | local coding assistant | aider | Cursor Agent | TALOS |
|---|---|---|---|---|
| Workspace awareness on start | ✅ auto-indexes | ✅ auto-reads repo map | ✅ auto-indexes | ⚠️ requires `/reindex` or `/mode rag` first |
| Tool invocation reliability | ✅ native tool calling | ✅ unified diff format | ✅ native | ❌ XML-based, model-dependent |
| File creation/editing | ✅ always works | ✅ always works | ✅ always works | ⚠️ works ~70% of the time |
| Session persistence | ✅ per-project sessions | ✅ git-based | ✅ workspace sessions | ❌ lost on exit |
| Undo/rollback | ✅ git-based undo | ✅ git-based undo | ✅ undo | ❌ none |
| Streaming responses | ✅ token-by-token | ✅ token-by-token | ✅ token-by-token | ✅ streaming works |
| Progress indicators | ✅ clear stages | ⚠️ basic | ✅ clear | ⚠️ spinner only |
| Error messages | ✅ actionable | ✅ clear | ✅ clear | ⚠️ raw exceptions sometimes leak |
| Approval flow for writes | ✅ y/n per operation | ✅ y/n per edit | ✅ accept/reject | ❌ NoOpApprovalGate (auto-approves everything) |
| Cost/token visibility | ✅ shows tokens/cost | ✅ shows tokens | ✅ shows in UI | ❌ not visible to user |
| Multi-file editing | ✅ coordinated | ✅ coordinated | ✅ coordinated | ❌ single file at a time |

### UX Pain Points (ranked by severity)

#### P0 — Tool Invocation Unreliability

**The #1 blocker.** When a user says "create settings.json with {…}", TALOS
should call `talos.write_file`. Instead, ~30% of the time, the model outputs
a code block and says "I have created the file" without actually calling the
tool.

**Root cause:** Tool calling via XML `<tool_call>` blocks is fragile with
small local models (Gemma 4, Qwen). These models weren't trained on this
specific XML format. The prompt engineering in `tools-preamble.txt` is
aggressive and correct, but the model doesn't always comply.

**What top-tier CLIs do differently:**
- local coding assistant uses model provider's native tool-calling API (structured JSON, not
  in-band XML). The model was trained on this format.
- aider uses a "unified diff" format that's simpler for models to produce.
- Both avoid asking the model to produce structured XML inside free text.

**Possible mitigations (in priority order):**
1. **Switch to Ollama's native tool/function-calling API** if the model supports
   it. Ollama supports `tools` parameter in `/api/chat`. This is structured
   JSON, not in-band XML, and models are increasingly trained on it.
2. **Post-hoc tool extraction** — if the model outputs a code block with a
   filename header (` ```json // settings.json`), detect this and auto-convert
   to a `write_file` call. This is a safety net, not a primary path.
3. **Retry with stronger nudge** — if first response has no tool call but
   the prompt was a file operation, re-prompt with "You need to use
   talos.write_file to actually create the file. Call it now."
4. **Model selection guidance** — document which models work best with the
   tool-call format. Test and rank Qwen3, Gemma 4, Llama 3.1, Mistral.

#### P1 — No Workspace Awareness on First Launch

When a user opens TALOS in a workspace directory, the first question ("what am
I working on?") gets routed to AskMode (no retrieval) because:
1. Default mode is "auto"
2. PromptClassifier classifies it as ASSIST (no strong workspace signal)
3. Even if routed to RAG, the index doesn't exist yet

**What top-tier CLIs do:** Auto-scan on startup. Build a lightweight file tree
or repo map. The model knows the workspace before the first question.

**Fix:** On REPL start, inject a lightweight workspace manifest into the
system prompt (file tree, top-level README snippet, package structure).
This doesn't require indexing — just a directory walk. The model then *knows*
the workspace from turn 1.

#### P1 — No Session Persistence

Every session starts cold. Previous conversations, user preferences, learned
context — all gone. For a "private assistant for sensitive data," this is a
significant product gap.

**What top-tier CLIs do:** local coding assistant persists sessions per project. aider
uses git history as implicit context. Cursor persists in workspace settings.

**Fix:** Persist `SessionMemory` + compaction sketch to
`~/.talos/sessions/<workspace-hash>.json` on exit. Restore on start.
Add `/session save|load|clear` commands.

#### P1 — NoOpApprovalGate (Auto-Approves Everything)

All file writes are auto-approved. A misrouted tool call can overwrite
production files without confirmation. This is a trust violation for a
"privacy-first, under my control" product.

**What top-tier CLIs do:** local coding assistant shows a diff preview and asks y/n.
aider shows the unified diff. Both require explicit approval for writes.

**Fix:** Implement `ConsoleApprovalGate`:
- For `WRITE` risk: show file path + operation, ask `[y/n]`
- For `DESTRUCTIVE` risk: show file path + content preview, require explicit
  `yes`
- Add `--auto-approve` flag for scripted usage

#### P2 — No Undo/Rollback

If a tool writes the wrong content to a file, the user has no way to revert
except manual file editing or git. This compounds the trust problem from P1.

**What top-tier CLIs do:** Git-based undo. Both local coding assistant and aider
auto-commit before changes and offer `/undo`.

**Fix:** Before any write/edit tool execution:
1. Check if workspace has git
2. If yes, stash or auto-commit with `[talos] pre-edit checkpoint`
3. Add `/undo` command that reverts last talos-tagged commit

#### P2 — No Token/Cost Visibility

Users have no idea how much context is consumed, how many tokens the response
used, or whether they're hitting model limits.

**What shows today:** Turn timing and retrieval trace (`:route` and audit mode).
What's missing: token counts per turn, total session tokens, budget utilization
percentage.

**Fix:** After each turn, optionally show:
`[Turn 3 | 1.2s | 847 tokens in / 312 out | budget: 42% used]`
Controlled by a `/verbose` toggle or config flag.

#### P2 — First-Run Experience Requires JavaFX

`FirstRunWizard` uses JavaFX for a GUI wizard. This means:
- Heavy dependency (JavaFX runtime, platform-specific jars)
- Breaks on headless systems (WSL, SSH, Docker)
- Contradicts the CLI-first identity

**What top-tier CLIs do:** Interactive terminal prompts. `external assistant` uses
inquirer-style prompts. `aider` auto-detects and prompts in terminal.

**Fix:** Replace `FirstRunWizard` with a terminal-based first-run flow:
1. Detect Ollama → prompt to install if missing
2. Detect model → prompt to pull if missing
3. Write config → confirm and proceed
4. Remove JavaFX dependency

#### P2 — Indexing Has No Progress Feedback

`/reindex` blocks with a spinner but no indication of progress. On a large
workspace, the user sees a spinner for 30+ seconds with no idea what's
happening.

**Fix:** Emit progress callbacks from `Indexer.reindex()`:
`[Indexing] Scanning... 142 files found`  
`[Indexing] Parsing... 89/142`  
`[Indexing] Embedding... 89/142 (requires Ollama)`  
`[Indexing] Done. 89 chunks indexed in 12.3s`

#### P3 — Auto-Mode Routing is Invisible

PromptClassifier makes sophisticated decisions (COMMAND vs RETRIEVE vs ASSIST),
but the user never sees why. When routing goes wrong, the user doesn't know
why and can't debug it without `:route <query>`.

**Fix:** In auto mode, show a subtle routing indicator:
`[auto → rag] Searching workspace...` or `[auto → ask]`
One line, dimmed, before the response. Disappears with `--quiet`.

#### P3 — No Inline Slash-Command Suggestions

Tab completion works (via `SlashCommandCompleter`), but there's no inline
suggestion as the user types. Modern CLIs show ghost text or dropdown.

**Fix:** This is a JLine enhancement — add a `AutoSuggestion` widget that
shows the most likely completion in dimmed text. Low effort with JLine 3.26.

#### P3 — 27 Command Files in `cli/commands/`

Each command is a separate file. Many are 20-40 lines. The cognitive overhead
of navigating 27 files for simple commands is high.

**Not necessarily a user-facing issue** — but it affects developer velocity
and makes the command system feel over-engineered for its current scope.

---

## 6. Specific File-Level Issues

| File | Issue | Priority |
|---|---|---|
| `RunCmd.java:176-209` | Duplicate `Limits` struct — identical to `cli/repl/Limits` | P1 |
| `Config.data` | Public `Map<String,Object>` with raw access everywhere | P2 |
| `FirstRunWizard.java` | JavaFX dependency for CLI product | P2 |
| `DevMode.java` | Duplicates tool capabilities (`ls`, `open`) | P3 |
| `NoOpApprovalGate.java` | Auto-approves all writes in production | P1 |
| `ToolCallLoop.java:161` | Re-prompt uses non-streaming `ctx.llm().chat(messages)` | P3 |
| `RagMode.java:204-209` | Empty retrieval message is injected as user-role message | Minor |

---

## 7. What's Working Well (Don't Touch)

These are strengths that should be preserved:

1. **Retrieval pipeline** — the stage-based design with traces is excellent.
   Keep it stateless and composable.

2. **Prompt section architecture** — `SystemPromptBuilder` with pluggable
   sections is the right pattern. Keep prompts as external resources.

3. **Sandbox + approval gate design** — the interface is right, even if the
   current implementation is NoOp. Don't compromise this safety model.

4. **Conversation compaction** — the sketch-based compaction with
   `ConversationCompactor` is a clever solution for local models with limited
   context windows. Keep it.

5. **Embedding profile abstraction** — frozen and correct. Don't touch until
   V1 or a specific need.

6. **ToolRegistry fuzzy resolution** — alias mapping + prefix stripping is
   exactly right for handling model hallucination of tool names.

7. **RenderEngine** — the sanitize→redact→print pipeline with spinner is
   solid. The violet left-border styling is a nice brand touch.

---

## 8. Prioritized Action Plan

### Wave 1 — Trust & Reliability (pre-V1 must-haves)

| # | Item | Effort | Impact |
|---|---|---|---|
| 1 | Implement `ConsoleApprovalGate` (y/n for writes) | S | Critical |
| 2 | Investigate Ollama native tool-calling API | M | Critical |
| 3 | Delete `RunCmd.Limits` duplicate | XS | Hygiene |
| 4 | Inject workspace manifest into system prompt on REPL start | S | High |
| 5 | Retrieval quality golden test suite (5-10 queries) | M | High |
| 6 | Raise JaCoCo floor to 40% | XS | Hygiene |

### Wave 2 — Product Polish (V1 release quality)

| # | Item | Effort | Impact |
|---|---|---|---|
| 7 | Session persistence (save/load per workspace) | M | High |
| 8 | Replace FirstRunWizard with terminal flow | M | Medium |
| 9 | Indexing progress feedback | S | Medium |
| 10 | Token/cost visibility per turn | S | Medium |
| 11 | Auto-mode routing indicator | XS | Medium |
| 12 | Git-based `/undo` for file operations | M | High |

### Wave 3 — Developer Experience

| # | Item | Effort | Impact |
|---|---|---|---|
| 13 | Typed config accessors | M | Medium |
| 14 | Consolidate `cli/cmds/` into `cli/commands/` | S | Low |
| 15 | Tool parameter validation utility | S | Low |
| 16 | Deprecate DevMode file ops (delegate to tools) | S | Low |
| 17 | Post-hoc tool extraction from code blocks | M | Medium |

---

## 9. Final Assessment

TALOS has an **engineering foundation that is ready for a quality product**.
The retrieval pipeline, the prompt architecture, the safety model, and the
tool system are all designed with the right abstractions. The embedding
profile work shows architectural maturity.

**The gap is in the last mile of UX.** Tool invocation reliability, session
persistence, approval flow, and workspace awareness on startup are all
solvable problems that, when fixed, would put TALOS in the same conversation
as local coding assistant and aider for local-first use cases.

The biggest strategic decision ahead is **tool-calling approach**: staying with
in-band XML vs switching to Ollama's native tool-calling API. This is the
single highest-leverage change for UX improvement.

---

*This review is grounded in actual code reading of 40+ production files,
full test suite execution, and comparison against publicly documented
behavior of local coding assistant, aider, and Cursor.*

