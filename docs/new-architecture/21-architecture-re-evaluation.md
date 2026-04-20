# 21. Architecture Re-Evaluation — Codebases vs Documents

**Date:** 2026-04-06
**Baseline:** `v0.9.0-beta-dev` at commit `c052f9c` (802 tests, 155 production files)
**Purpose:** Re-evaluate `docs/new-architecture/` (00–20) against lessons from reference codebases (local coding assistant, OpenClaw/Open Interpreter, NemoClaw) and the current implemented state.

---

## 0. Why this document exists

The architecture documents (00–20) were written from first-principles reasoning and product vision. They are strong conceptually. However, they were authored **before** the reference codebases were deeply studied and **before** a large volume of implementation work landed on `v0.9.0-beta-dev`.

This document identifies where the codebases reveal patterns that the architecture docs either missed, understated, or got slightly wrong — and recommends concrete corrections.

---

## 1. What the codebase already proved (ahead of the docs)

The implementation on `v0.9.0-beta-dev` has already resolved many items the docs treat as future work:

| Architecture doc says... | Codebase already has... |
|---|---|
| "Source is the root input abstraction" (doc 14, step 3) | `SourceIdentity`, `SourceType`, `SourceFormat`, `MediaType`, `SourceClassifier` — fully implemented and flowing through ingestion |
| "Session, TurnProcessor, ApprovalGate needed" (bridge doc) | `dev.loqj.runtime` package: `Session`, `TurnProcessor`, `TurnResult`, `ApprovalGate`, `NoOpApprovalGate` — all shipped |
| "SessionMemory should move out of RagService" (bridge doc) | Done. `SessionMemory` lives in `cli.repl`, `RagService` is clean |
| "Retrieval pipeline abstraction is the keystone" (modernization plan) | `RetrievalPipeline` + 6 stages (BM25, KNN, RRF, SourceBoost, Reranker, Dedup) + `RetrievalTrace` — fully shipped |
| "ContextPacker unifying SnippetBuilder + PromptValidator" (modernization plan) | `ContextPacker`, `TokenBudget`, `ContextResult` — shipped with rich typed metadata. Legacy `SnippetBuilder` and `PromptValidator` still exist (per "preserve before deleting" rule) but the new path is live. |
| "Product identity should become Loqs" (doc 00–01) | Done. CLI banner, prompts, system text all say "Loqs" |
| "Smart routing, not retrieval-as-default" (architecture intent) | `PromptClassifier` with COMMAND/RETRIEVE/ASSIST routing, precision-first, retrieval-never-as-fallback |
| "Multi-turn structured conversation" (implied by assistant runtime) | `/api/chat` with role-tagged `ChatMessage` history, `SessionMemory` with structured turns |
| "Rich metadata from index through citations" (doc 14, step 3) | `ChunkMetadata` flows from `LuceneStore` through retrieval to `ContextPacker` to citations with line ranges |

**Assessment:** The codebase is significantly ahead of where docs 10–15 assumed it would be. The "Phase 1" from the modernization plan is **complete**. Several "Phase 2" items are partially done.

---

## 2. Where reference codebases contradict or correct the architecture docs

### 2A. The Loqs/LOQ-J split is overengineered for the current scale

**What the docs say (00, 01, 04):** Loqs is the assistant platform, LOQ-J is the knowledge engine. Two clear subsystems. Treat them as conceptually separate.

**What reference codebases show:** local coding assistant, Open Interpreter, and similar production CLI assistants are **single cohesive products**. There is no named internal knowledge subsystem. The retrieval/context pipeline is simply an implementation layer, not a branded subsystem.

**What the code actually looks like:** Everything lives in one repo, one Gradle project, one JAR. The "LOQ-J" branding adds cognitive overhead without actual module separation. The user never sees "LOQ-J" — they see "Loqs."

**Correction:** 
- **Keep the responsibility separation** (retrieval pipeline, context assembly, knowledge indexing are their own packages — this is correct).
- **Drop the "LOQ-J" branding.** It's an internal subsystem that doesn't need its own identity. Just call it "the knowledge engine" or "the retrieval layer" in docs. The packages (`dev.loqj.core.retrieval`, `dev.loqj.core.context`, etc.) already express the boundary without needing a product name.
- **Impact:** Docs 00, 01, 04, 07, 08, 09, 10, 12, 14, 19 all reference "LOQ-J" as if it's a separate entity. Simplify to "the knowledge/retrieval layer."

### 2B. The docs overweight "Tasks" and "Steps" as first-class runtime concepts

**What the docs say (02, 03, 07):** Tasks and Steps are core vocabulary. The runtime should decompose user goals into step-oriented workflows.

**What reference codebases show:** local coding assistant and similar tools use a much simpler model: **turn-based conversation with tool calls**. There is no "Task" abstraction. The user sends a message, the system responds (possibly calling tools in the process). Multi-step work emerges from the conversation, not from an explicit Task/Step planner.

**What the code actually looks like:** `TurnProcessor.process()` takes user input and produces a `TurnResult`. There's no `Task` or `Step` class. And this is *correct* for V1.

**Correction:**
- **Remove Task/Step from V1 vocabulary.** The turn-based model already works. 
- **Keep Task/Step as a future concept** for when multi-step autonomous workflows actually exist (e.g., "research this topic across 5 sources and produce a briefing" as a single command that runs multiple retrieval+generation cycles).
- **Impact:** Docs 02, 03, 07 should be updated. "Task" becomes "user turn/request." Steps are unnecessary until there's a planning/decomposition engine.

### 2C. The docs overweight "Action Mode" and "Research Mode" separation

**What the docs say (00, 02, 04, 07, 17):** Research mode (read-oriented) and Action mode (execution-oriented) should be explicitly separated as first-class runtime concepts with different risk profiles.

**What reference codebases show:** local coding assistant has one mode. Tool calls have individual permission/approval checks. There's no modal "research vs action" switch. The system naturally handles both read and write operations through tool-specific approval. Open Interpreter similarly runs in a single mode with per-action confirmation.

**What the code actually looks like:** The mode system (`AskMode`, `RagMode`, `DevMode`, etc.) is about retrieval strategy selection, not about research-vs-action. `PromptClassifier` routes by evidence need, not by action risk.

**Correction:**
- **Drop Research Mode / Action Mode as V1 architecture concepts.** They're not wrong in principle, but they're premature. 
- **The approval gate already handles the safety concern.** `ApprovalGate` can gate any sensitive tool call regardless of "mode."
- **Keep the distinction as a future design consideration** for when browser/email/calendar actions exist.
- **Impact:** Docs 00 §7, 02 §16, 04 §10, 07 §6, 17 §4C should be deprioritized.

### 2D. The docs underweight conversation/message management

**What the docs say:** Almost nothing about conversation management, context window management, message compaction, or multi-turn state.

**What reference codebases show:** This is one of the **most critical** engineering concerns:
- **local coding assistant** accumulates full message arrays, manages context windows, does message compaction/summarization when approaching limits, normalizes messages for API calls.
- **Open Interpreter** manages conversation state, handles system prompts separately, truncates history intelligently.
- This is a first-class runtime concern that the architecture docs completely skip.

**What the code actually has:**
- `SessionMemory` with a rolling window (`MAX_CHARS=64,000`, `MAX_TURNS=200`) and dual storage (flat text buffer + structured `ChatMessage` list)
- `/api/chat` with role-tagged `ChatMessage` history via `AskMode.buildMessages()`
- Basic session memory with proper turn pruning, but no compaction, no summarization of old context, no context-window-aware management

**Correction:**
- **Add conversation management as a first-class V1 architecture concern.** This is more important than Workspaces, Tasks, or Approval for V1 user value.
- Needed: context window tracking, intelligent history truncation, possible summarization of older turns, system prompt management as a separate concern from conversation history.
- **Impact:** This is a gap in ALL docs (00–20). Needs a new section or document.

### 2E. The docs underweight tool/capability execution patterns

**What the docs say (02, 08):** "Capability" is defined abstractly. "Actions" are future. Tools are mentioned in passing (`dev.loqj.tools` seam).

**What reference codebases show:** Tool use is the **primary mechanism** through which CLI assistants do useful work:
- local coding assistant's tool system: `Bash`, `Read`, `Write`, `Search`, `Grep`, etc. — each tool has a descriptor, input schema, execution logic, and result formatting.
- Tools are the bridge between the LLM and the workspace. Without tools, the assistant is just a chatbot.
- Tool results feed back into the conversation as structured messages.

**What the code has:** `dev.loqj.tools` package exists with `LoqjTool`, `AsyncLoqjTool`, `ToolCall`, `ToolDescriptor`, `ToolRegistry`, `ToolResult`, `ToolError` — but they're **not wired**. The architecture docs barely mention them.

**Correction:**
- **Elevate tool execution to a V1 architecture concept.** At minimum, V1 should wire:
  - File read tool (show file contents from workspace)
  - Grep/search tool (search workspace files)
  - Index search tool (the existing retrieval pipeline, exposed as a tool)
- This transforms Loqs from "chatbot that can search an index" to "assistant that can interact with a workspace."
- **Impact:** The tool seam that already exists in code should be reflected in the architecture. Doc 07 (runtime shape) and doc 08 (capability map) need tool execution as a concrete layer.

### 2F. The Workspace model is over-abstracted for V1 reality

**What the docs say (06):** Workspaces are "context boundaries" that group sources, knowledge, memory, tasks, permissions, policies. They're "not just a folder." Examples include "Shopping workspace," "Appointments workspace."

**What reference codebases show:** local coding assistant's "workspace" is literally `cwd` — the current working directory. That's it. Open Interpreter is the same. The workspace IS a directory. The value comes from what the system does within that directory, not from an elaborate workspace metadata model.

**What the code actually does:** `Session.workspace()` returns a `Path`. The indexer indexes that directory. Retrieval searches that index. That's the workspace.

**Correction:**
- **For V1, workspace = directory path.** That's sufficient and honest.
- **Do not build workspace metadata, workspace switching, workspace labels, cross-workspace search, or workspace policies in V1.**
- The current directory-as-workspace model is exactly what works in reference codebases.
- Future workspace enrichment (labels, policies, multi-source) can come later when there's a real use case.
- **Impact:** Doc 06 should be tagged as "future architecture." V1 workspace = the indexed directory path.

### 2G. The docs underweight the LLM interaction layer

**What the docs say:** Minimal. Doc 16 talks about model profiles at a high level.

**What reference codebases show:** The LLM interaction layer is one of the most complex parts:
- System prompt construction (varies by context, mode, available tools)
- Message formatting (role alternation, tool result injection)
- Streaming response handling
- Token counting and context window management
- Model-specific behavior (different models need different prompting)
- Error handling and retries
- Response parsing (extracting tool calls, handling malformed output)

**What the code has:** `OllamaEngine` with chat/generate/stream, stub engine providers for GPT4All and LlamaCpp (SPI extensibility intent), `LlmClient` with PLACEHOLDER/ENGINE modes and a structured `chat(List<ChatMessage>)` API already wired through `AskMode`, plus 4 system prompts in resources (`ask-system.txt`, `cli-system.txt`, `rag-system.txt`, `system.txt`). This is functional but underarchitected relative to its importance.

**Correction:**
- **Recognize the LLM interaction layer as a first-class architecture concern** equal in importance to retrieval.
- The current engine SPI + OllamaEngine is solid. But system prompt management, context window tracking, and response parsing need attention.
- **Impact:** Doc 07 and doc 16 should be updated to reflect the actual complexity here.

---

## 3. What the docs got RIGHT and should be preserved

These architectural stances are validated by both codebases and implementation:

1. **Local-first, privacy-first** — local coding assistant is cloud-based, but Open Interpreter and the Loqs direction are local-first. This is a genuine differentiator.

2. **CLI-first** — Every reference codebase proves that CLI-first is the right starting surface for a developer/power-user tool.

3. **Evidence-driven answers** — The retrieval pipeline, citations, provenance flow — this is genuinely strong and differentiating. Reference codebases that have RAG do it worse.

4. **Approval as a first-class concept** — local coding assistant's permission system validates this. The `ApprovalGate` seam is correct.

5. **Framework-neutral core** — Not adopting LangChain4j/Spring AI was the right call. The custom pipeline is cleaner and more controllable.

6. **Source model foundation** — `SourceIdentity`, `SourceType`, `SourceFormat`, `MediaType`, `SourceClassifier` — this is ahead of most reference codebases.

7. **Retrieval pipeline as composable stages** — The `RetrievalPipeline` with `RetrievalStage` + `StageOutput` + `RetrievalTrace` is production-quality architecture.

8. **"Don't build what you don't need yet"** (doc 13) — This principle is validated by every successful reference codebase.

---

## 4. Revised V1 priority stack

Based on the re-evaluation, here is the corrected V1 priority ordering:

### Priority 1 — What V1 must prove (revised)

1. **Workspace-scoped retrieval works** (already proven — 802 tests)
2. **Evidence-grounded answers with citations** (already proven)
3. **Smart routing avoids false retrieval** (already proven — PromptClassifier)
4. **Multi-turn conversation with context** (partially done — needs context window management)
5. **Tool execution for workspace interaction** (seam exists, needs wiring)

### Priority 2 — What V1 should improve next

6. **Conversation management** — context window tracking, history compaction, system prompt management
7. **Wire 2–3 basic tools** — file read, grep, retrieval-as-tool
8. **Improve chunking** — code-aware splitting (function boundaries for Java/Python)
9. **Better error UX** — model not found, embedding failure, index empty

### Priority 3 — Architecture clarification (no code, docs only)

10. **Simplify Loqs/LOQ-J language** — drop "LOQ-J" branding, use "retrieval layer"
11. **Remove Task/Step from V1 vocabulary** — the turn model is the runtime model
12. **Defer Research Mode/Action Mode** — approval gate is sufficient
13. **Defer workspace-as-context-boundary** — workspace = directory for V1

### Deprioritized (NOT V1)

- Workspace metadata/labels/switching
- Research mode vs Action mode as separate runtime paths
- Memory policies
- Browser/email/calendar actions
- Model profiles / hardware awareness
- Multi-surface (CLI + guided UI)
- Non-technical user onboarding

---

## 5. Specific document corrections needed

| Document | Correction | Severity |
|---|---|---|
| 00 — Executive Summary | Replace "LOQ-J" branding with "the retrieval/knowledge layer." Remove Task/Step prominence. | Medium |
| 01 — Product and Scope | Drop "LOQ-J" as a named subsystem. It's an implementation layer, not a product. | Medium |
| 02 — Core Vocabulary | Remove Task, Step, Action, Artifact from V1 vocabulary. Add "Turn", "Tool Call", "Conversation." | High |
| 03 — Use Cases | Rewrite around turn-based interaction, not task-oriented workflows. | Medium |
| 04 — System Boundaries | Simplify. The boundary is packages, not named subsystems. | Medium |
| 05 — Storage Responsibilities | Still accurate. The 4-role model (Raw, Structured, Knowledge Index, Cache) holds. | None |
| 06 — Workspace Model | Tag as "future architecture." V1: workspace = directory path. | High |
| 07 — Runtime Shape | Add conversation management layer. Add tool execution layer. Remove task/step orientation. | High |
| 08 — Capability Map | Add "tool execution" as a core capability. | Medium |
| 09 — Architecture Decisions | Update LOQ-J references. AD decisions mostly hold; minor language cleanup. | Low |
| 10 — Roadmap | Update for current state — most of "Phase 1" is done. | High |
| 11 — Open Questions | Many questions answered by implementation. Tag resolved items. | Medium |
| 12 — V1 Scope | Revise V1 must-wins to match revised priority stack above. | High |
| 13 — What Not to Build Yet | Still correct and validated by reference codebases. No changes needed. | None |
| 14 — Next Steps | Update — several "next steps" are already shipped. | High |
| 15 — Next Architectural Steps | Revise — conversation management and tool wiring are the actual next steps. | High |
| 16 — Local Runtime | Keep as future reference. Not V1. | Low |
| 17 — Data Protection | Keep as future reference. Approval gate covers V1. | Low |
| 18 — Accessibility | Keep as aspirational. Not V1. | Low |
| 19 — V1 Goal Statement | Update LOQ-J references. Core thesis holds. | Low |
| 20 — Reference Study | Update with deeper findings from codebases. | Medium |

---

## 6. What should happen next (implementation, not docs)

### Immediate (next feature branch)

1. **Wire the tool seam.** Connect `ToolRegistry` to `TurnProcessor`. Define 2–3 concrete tools:
   - `ReadFileTool` — read a workspace file by path
   - `SearchTool` — grep/search workspace files
   - `RetrieveTool` — expose the retrieval pipeline as a tool the LLM can call

2. **Context window management.** Track token usage across turns. Implement history truncation when approaching model context limits.

### Soon after

3. **System prompt management.** Consolidate system prompt construction. Different contexts (retrieval available vs not, tools available vs not) should produce different system prompts.

4. **Code-aware chunking.** Function/method boundary detection for Java/Python. This improves retrieval quality for the core coding use case.

### Do not start yet

5. Workspace metadata — wait for real multi-workspace use cases
6. Task/Step planner — wait for multi-step autonomous workflows
7. Research/Action mode — wait for browser/action tools
8. Memory policies — wait for V1 to prove basic value

---

## 7. Summary

The architecture docs (00–20) established a strong conceptual foundation. The codebase has already outpaced many of those plans. However, the docs over-invested in abstractions (Tasks, Steps, Workspaces-as-context-boundaries, Research/Action modes, LOQ-J branding) that reference codebases show are premature for V1.

The reference codebases point toward a simpler, more pragmatic V1:
- **Turn-based conversation** (not task decomposition)
- **Tool execution** (not capability bundles)
- **Directory-as-workspace** (not context boundaries)
- **Approval per action** (not modal research/action separation)
- **Context window management** (the docs' biggest gap)

The knowledge engine (retrieval pipeline, context packing, source model, citations) is genuinely strong and ahead of reference implementations. That advantage should be preserved and deepened, not diluted by premature platform abstractions.

**One-line summary:** The docs dreamed bigger than V1 needs; the codebases show that turn + tools + retrieval + approval is the V1 shape; the implementation is already close — wire the tools, manage the conversation, and V1 is real.




