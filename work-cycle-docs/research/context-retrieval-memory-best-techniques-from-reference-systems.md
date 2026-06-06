# Context, Retrieval & Memory: Best Techniques From Reference Coding Agents

> **Status:** research analysis (discussion-only, no code changed)
> **Author:** evidence pass over `.claude/` reference resources
> **Scope:** how the strongest local/CLI agent harnesses actually handle context window
> management, codebase retrieval, memory, and prompt economics — and what that implies for Talos.

---

## Goal of this document

The earlier Talos retrieval review argued that Talos should evolve from a single Lucene/vector
RAG index toward a typed, routed, trust-labelled context architecture. That argument was sound
in the abstract, but it was grounded only in vendor blog posts (Anthropic Contextual Retrieval,
BGE-M3 / Qwen3 model cards) — **not** in how the best shipping agents are actually built.

This document fixes that. It is a **deep, evidence-based extraction of the BEST techniques** used
by four reputable agent codebases and two Manning books that ship in this repo under `.claude/`.
For every technique it records **what** they do and **how** they do it, with file/line or page
citations so the claims can be re-verified. The final section translates the findings into concrete
implications for Talos.

The single most important finding up front, because it contradicts the instinct to "buy a bigger
embedding model":

> **None of the four reference coding agents use vector/embedding RAG to find code.**
> They use *agentic structure + keyword search* (ripgrep / glob / read / BFS) and *hierarchical
> Markdown memory*. Where semantic search exists at all (OpenClaw, Hermes), it is applied to
> **memory notes**, never to a workspace code index. Both books independently rank keyword and
> structure-based search above vectors for code.

That is the headline. The rest is detail.

---

## Sources examined (the "top resources")

| # | Resource | Type | What it is |
|---|----------|------|-----------|
| R1 | `.claude/claude-code/` | Reverse-engineered source (TypeScript, ~1900 files) | Anthropic Claude Code, from the March 2026 source-map leak |
| R2 | `.claude/gemini-cli/` | Official OSS source (TypeScript monorepo) | Google Gemini CLI |
| R3 | `.claude/hermes-agent/` | OSS source (Python) | Hermes agent harness |
| R4 | `.claude/openclaw/` | OSS source (TypeScript monorepo, ~18k files) | OpenClaw ("the AI that actually does things") |
| B1 | `.claude/Build_an_AI_Agent_(From_Scratch)_v5_MEAP.pdf` | Manning MEAP book | Single-agent, context-engineering focused |
| B2 | `.claude/Build_a_Multi-Agent_System_(MEAP-Book).pdf` | Manning MEAP book | Multi-agent orchestration |
| A1 | `.claude/alex000kim-article (1).txt` | Article | Analysis of the Claude Code source leak |

PDF text was extracted with `pypdf` for searchability; page markers (`===PAGE n===`) and `.txt.clean`
line numbers are cited.

---

## Part 1 — The cross-system consensus (what everyone agrees on)

Seven patterns appear in **three or more** of the resources. These are the high-confidence
"best techniques."

### C1. Code is found by agentic structure + keyword search, not vector RAG

| System | How it finds code | Evidence |
|---|---|---|
| Claude Code | ripgrep-backed `Grep`, `Glob`, `Read`; open-ended search delegated to a sub-agent | R1 `src/tools/GrepTool/prompt.ts:7-17` ("A powerful search tool built on ripgrep"), `src/tools/GlobTool/GlobTool.ts:57-89`, `src/tools/AgentTool/built-in/exploreAgent.ts` ("EXCLUSIVELY to search and analyze existing code") |
| Gemini CLI | BFS filename search + `grep`/`glob`/`read_file`/ripgrep; **no embedding index** | R2 `packages/core/src/utils/bfsFileSearch.ts:31-201`, `packages/core/src/prompts/snippets.ts:231-248` |
| Hermes | SQLite FTS5 over session messages; lexical catalog search for skills; **no vector index** | R3 `hermes_state.py:254-307`, `tools/skills_hub.py:3193-3212` |
| OpenClaw | hybrid search exists but only for **memory**, not a repo code index | R4 `docs/concepts/memory-search.md:58-80` ("two retrieval paths in parallel… Vector… BM25") |

Both books back this explicitly:

- B1 (From Scratch), §5.1.2: *"Tools like Claude Code, Cursor, and Gemini CLI understand code in
  exactly this way. This is structure-based search."* (`...From_Scratch...txt.clean:4676-4677`)
- B1 §5.2.1 on keyword search: *"There's no method faster or more accurate than keyword search when
  searching for a function name like get_user_by_id, finding error code 404, or checking a specific
  configuration value."* (`:4748-4751`)
- B1 §5.2.2 on vectors: *"vector search isn't always the best choice. When exact word matching is
  needed… keyword search is more effective… hybrid search combining keyword and vector search is
  widely used in practice."* (`:4801-4805`)
- B1 §5.1.3: vectors/keyword search become necessary only when a file is too big for context or
  there are too many unsystematic documents (a company wiki), not for structured code repos
  (`:4693-4702`).

**Takeaway:** vector search is the *fallback for scale*, not the primary code-retrieval mechanism.
The primary mechanisms are (1) walk the structure, (2) exact keyword/BM25, (3) read the file.

### C2. Memory is hierarchical Markdown files, loaded by tier — not vectorized by default

| System | Memory model | Evidence |
|---|---|---|
| Claude Code | `CLAUDE.md` hierarchy: managed → user (`~/.claude`) → project → local; `@include` expansion; recommended max 40k chars | R1 `src/utils/claudemd.ts:1-26, 18-25, 91-93, 618-685` |
| Gemini CLI | `HierarchicalMemory{global, extension, project, userProjectMemory}`; upward git-root traversal; tiered injection (Tier 1 → system prompt, Tier 2 → first user msg) | R2 `packages/core/src/config/memory.ts:7-12`, `utils/memoryDiscovery.ts:317-510`, `config/config.ts:2553-2597` |
| OpenClaw | Plain Markdown, *"there is no hidden state"*: `MEMORY.md` + `memory/YYYY-MM-DD.md` + `DREAMS.md`; daily notes indexed for search, not injected every turn | R4 `docs/concepts/memory.md:9-27, 36-44` |
| Hermes | Persistent SQLite session store + FTS5; session chaining via `parent_session_id` | R3 `hermes_state.py:5-13, 190-241, 254-307` |

Precedence is explicit and deterministic. Gemini states the order in the prompt layer itself:
`<project_context>` > `<extension_context>` > `<global_context>` (R2 `prompts/snippets.ts:250-259`).

Semantic memory search, where present, is **hybrid and optional**: OpenClaw runs vector + BM25 (FTS5)
in parallel and merges, with `sqlite-vec` as an *optional* accelerator that falls back gracefully
(R4 `docs/concepts/memory-builtin.md:9-18,76-87`, `packages/memory-host-sdk/src/host/sqlite-vec.ts:30-76`).

### C3. Context window is managed by explicit compaction: protect the ends, summarize the middle, keep tool-call pairs, and a circuit breaker

This is the most universal engineering pattern, and the numbers are concrete:

| System | Strategy + thresholds | Evidence |
|---|---|---|
| Claude Code | autoCompact at `effectiveWindow − 13_000` buffer; manual at `−3_000`; **`MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3`** circuit breaker (resets on success) | R1 `src/services/compact/autoCompact.ts:62-70, 72-91, 257-349` |
| Gemini CLI | `ChatCompressionService`: compress when tokens ≥ `0.5 × tokenLimit`; **preserve last 30%**; tool outputs truncated first via "reverse token budget"; LLM summary + a verification "Probe" pass | R2 `packages/core/src/context/chatCompressionService.ts:37-53, 135-235, 268-328, 359-479` |
| Hermes | `trajectory_compressor`: **protect first turns + last N (4); compress middle only;** replace span with one `[CONTEXT SUMMARY]` message; `target_max_tokens=15250`, `summary_target_tokens=750` | R3 `trajectory_compressor.py:8-14, 90-92, 493-527, 759-825` |
| OpenClaw | auto-compact near limit or on overflow error; **keeps assistant tool-calls paired with their `toolResult`**; flushes memory to disk *before* compacting | R4 `docs/concepts/compaction.md:9-24, 17-19, 31-33` |

The `MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES = 3` breaker is independently corroborated by the leak
article: a single comment notes 1,279 sessions had 50+ consecutive failures, *"wasting ~250K API
calls/day globally"* — fixed by disabling compaction after 3 failures (A1 lines 64-68).

**Common sub-rules:** (a) never split a tool call from its result; (b) always keep a recent tail
verbatim; (c) only the *middle* is lossy; (d) verify the summary didn't drop facts (Gemini's Probe);
(e) fail safe — stop compacting rather than loop.

### C4. Prompt-cache economics are a first-class architectural constraint

This is the theme Talos most under-weights, and it is everywhere in the strongest system (Claude Code):

- System prompt is split into **memoized vs volatile** sections; the cache-busting escape hatch is
  literally named `DANGEROUS_uncachedSystemPromptSection` (R1 `src/constants/systemPromptSections.ts:17-38, 60-68`).
- **Sticky latches** prevent mode toggles from busting the cache (`promptCache1hEligible`,
  `afkModeHeaderLatched`, `fastModeHeaderLatched`, `thinkingClearLatched`) — comments warn mode
  headers can cause *"50–70K token cache churn"* (R1 `src/bootstrap/state.ts:202-255`).
- Cache breaks are *deliberately injected* via a `[CACHE_BREAKER: …]` marker only when needed
  (R1 `src/context.ts:22-34, 116-149`).
- The agent/tool list is moved into attachments specifically to keep the tool schema static and
  avoid cache busts (R1 `src/tools/AgentTool/prompt.ts:190-199`).

OpenClaw codifies the same doctrine as architecture rules: *"deterministic prompt cache ordering"*,
*"hot paths should carry prepared facts forward"*, *"Do not rediscover with broad loaders"*
(R4 `AGENTS.md:26-51`). The article confirms it drives the codebase: `promptCacheBreakDetection.ts`
tracks 14 cache-break vectors (A1 line 89).

**Takeaway:** context assembly order must be *stable and tiered* — static/cacheable content first,
volatile content last — or you pay (latency + tokens) on every turn.

### C5. Progressive disclosure: load a compact index first, expand on demand

Agents do **not** dump everything into context. They load a small catalog and pull detail when asked:

- Hermes skills: `skills_list()` (compact, at session start) → `skill_view(name)` (full, on demand)
  → `skill_view(name, file)` (reference file on demand) (R3 `website/docs/guides/work-with-skills.md:75-82`).
- OpenClaw memory: daily notes are *indexed* for `memory_search`/`memory_get`, **not injected every
  turn**; `MEMORY.md` injected at session start and *truncated* if over the bootstrap budget
  (R4 `docs/concepts/memory.md:36-51`); read budgets `DEFAULT_MEMORY_READ_LINES=120`,
  `DEFAULT_MEMORY_READ_MAX_CHARS=12_000` (R4 `packages/memory-host-sdk/src/host/read-file-shared.ts:3-4`).
- Gemini loads subdirectory memory **just-in-time** only under trusted roots
  (R2 `utils/memoryDiscovery.ts:512-648`).

The books give the *why*: B1 §1.5.3 "Bigger context is not always better" cites **Context Rot** and
the **"Lost in the Middle"** effect — *"we should not simply provide more information but rather
selectively provide only highly relevant information"* (`:540-557`).

### C6. Tool gating is allow / ask / deny, layered with trust scope and a classifier

| System | Model | Evidence |
|---|---|---|
| Claude Code | rules → allow/deny/ask; `dontAsk` turns ask→deny; auto-mode **classifier** with a safe-tool allowlist fast-path; 23 numbered bash security checks | R1 `src/utils/permissions/permissions.ts:122-231, 473-517, 658-760`; A1 line 87 |
| Gemini CLI | policy engine `ALLOW/DENY/ASK_USER`; modes `DEFAULT/AUTO_EDIT/YOLO/PLAN`; **trusted-folder** gating; shell redirection downgraded; MCP refuses to start unless trusted | R2 `policy/types.ts:10-14, 48-65`, `policy/policy-engine.ts:284-497`, `tools/mcp-client-manager.ts:575-590` |
| OpenClaw | `plugins.allow/deny/enabled`, **deny wins**; skills treated as **untrusted code**, critical scan findings block by default | R4 `docs/tools/plugin.md:153-200`, `docs/tools/skills.md:180-201` |

**Takeaway:** capability is governed by *policy + trust scope + (optionally) a classifier*, not by
a single boolean. Risky operations fail closed. Third-party code is untrusted until scanned/accepted.

### C7. Orchestration of sub-agents lives in the *prompt*, and workers are stateless

- Claude Code's multi-agent coordinator logic is *entirely in a system prompt*: *"Do not rubber-stamp
  weak work"*, *"Never hand off understanding to another worker"* (A1 line 91; R1
  `src/coordinator/coordinatorMode.ts:111-259`). Workers start with **zero context** and run in
  parallel; results are summarized up, not treated as conversation (R1 `src/tools/AgentTool/prompt.ts:202-287`).
- Background long-term consolidation is a *forked sub-agent* (`/dream` auto-dream), gated by
  time + session count + a lock (R1 `src/services/autoDream/autoDream.ts:54-233`).

Both books frame this as the **Isolate** strategy (B1 §1.5.4, `:580-606`) and as multi-agent
decomposition (B2 Ch9). B2's mental model: the agent *"checks the memory modules at the outset of
task execution"* and *"saves the results of every sub-step, tool call, and the final task result
into memory"* (B2 `:509-513`).

---

## Part 2 — The two books' organizing frameworks

These give a vocabulary that unifies the per-system findings.

### Framework F1 — The five context-engineering strategies (B1 §1.5.4, `:558-606`)

> *Context engineering can be broadly categorized into five strategies.*

1. **Generation** — use LLM output in context (plans, reflection). [B1 Ch7]
2. **Retrieval** — bring external info in (web, DB, file read, vector DB). [B1 Ch3/5/6]
3. **Write** — persist context out (long-term memory, scratchpad, files). [B1 Ch6/8]
4. **Reduce** — shrink context (summarize, delete, filter) → fights Context Rot. [B1 Ch6]
5. **Isolate** — separate tasks/tools (sandboxes, specialized agents). [B1 Ch8/9]

Memory (B1 Ch6) is explicitly the hub where Retrieval + Write + Reduce converge (`:607-609`).

### Framework F2 — The search taxonomy (B1 §5.2)

Four methods, each best for a different job (`:4703-4830`):

- **Structure-based** — explore the file/folder tree like a developer; best for code repos (`:4672-4677`).
- **Keyword (BM25/TF-IDF)** — exact identifiers, error codes, config keys; unbeatable for code symbols (`:4733-4752`).
- **Vector (embeddings + cosine/Euclidean)** — semantic/synonym recall in natural language (`:4766-4796`).
- **Graph** — entity/relationship traversal, multi-hop questions (`:4808-4830`).
- → **Hybrid** (keyword + vector) is "widely used in practice" (`:4801-4805`).

### Framework F3 — Three-layer memory (B1 Ch6 overview, `:4572-4574`)

1. **Conversation history management** during a task (the Reduce/compaction loop).
2. **Session handling** so different users/tasks keep separate history.
3. **Long-term memory** that survives across runs and feeds future tasks.

This maps cleanly onto what the real systems ship: (1) = C3 compaction, (2) = Hermes/OpenClaw session
stores, (3) = CLAUDE.md/MEMORY.md + dream/distillation.

---

## Part 3 — What this means for Talos (grounded translation)

Talos already verified state (from the code review preceding this doc):

- Pipeline `Bm25 → Knn → RrfFusion(60) → SourceBoost → Reranker(ScoreThreshold) → Dedup`
  (`src/main/java/dev/talos/core/rag/RagService.java:251-259`) — clean stateless stages.
- Rich Lucene metadata, structure-aware chunker, `cache.db` with `sessions`/`memory` tables,
  `SessionMemory` rolling buffer, private-mode RAG gating.
- **Gaps:** vectors default to `false` in code (`Config.java:262`) vs `true` in the shipped YAML;
  reranker is a heuristic, not a cross-encoder; **one uniform top-k for every task** (no routing);
  no symbol index; no contextual chunk prefixes; **no compaction circuit breaker**; no prompt-cache
  ordering discipline; no hierarchical Markdown project-memory equivalent.

Mapping the reference techniques onto Talos, in priority order:

1. **Adopt structure + keyword first; demote vectors to a recall signal (C1, F2).**
   Talos already has BM25 + KNN + RRF — keep it. But the reference systems prove the *highest-value*
   code retrieval is structure-based + exact symbol search. Talos's planned **symbol index** is the
   single biggest dev-assistant upgrade, and it is *more* important than any embedding-model swap.
   Vectors are the scale fallback (B1 §5.1.3), not the spine.

2. **Add a compaction loop with the reference rules (C3, F3-layer-1).**
   Talos has `SessionMemory` but no evidenced compaction discipline. Implement: preserve recent tail,
   summarize only the middle, **never split a tool call from its result**, verify the summary
   (Gemini's Probe), and a **`MAX_CONSECUTIVE_*_FAILURES` circuit breaker** (Claude Code's 3-strike
   rule prevented a 250K-call/day burn). This is local-trust-relevant: a bad summary that drops an
   approval or a verification result is a truthfulness failure.

3. **Introduce hierarchical Markdown project memory (C2, C5).**
   A `TALOS.md` / `.talos/rules.md` hierarchy (global < workspace < repo < dir), loaded by tier with
   deterministic precedence and a size budget + truncation — exactly Gemini/Claude/OpenClaw. Treat
   workspace-provided instructions as **untrusted until displayed/accepted** (C6). This is cheaper
   and more trustworthy than vectorizing memory, and aligns with Talos's "no hidden state" ethos
   (OpenClaw: *"there is no hidden state"*, R4 `docs/concepts/memory.md:9-11`).

4. **Make context assembly cache-stable and tiered (C4).**
   Order the prompt static→volatile, carry prepared facts forward instead of re-running broad loaders
   each turn (OpenClaw `AGENTS.md:26-51`). Talos already has `ContextLedger` and `TokenBudget`; add an
   explicit cacheable/volatile split. This is latency + cost scalability — directly answering the
   "easily and fast scalable" requirement — without touching the model.

5. **Route retrieval by task type (C1 + F1 Isolate).**
   Talos already classifies tasks (`TaskType`/`TaskContract`). Wire it: ASK → docs/source; EDIT →
   symbol/path + direct read + tests; DEBUG → errors/stack/recent changes; VERIFY → changed files +
   commands. One uniform top-k for all is the gap, and the wire is small.

6. **Progressive disclosure for any large context source (C5).**
   Inject a compact catalog (file map, memory index, skill list); expand on demand via tools. Honors
   Context Rot / Lost-in-the-Middle (B1 §1.5.3).

7. **Keep memory writes gated and roles non-theatrical (C7, F1).**
   If long-term memory is added, gate writes (importance/scope/TTL/provenance/privacy) and use
   *roles*, not autonomous background agents — consistent with Talos doctrine and with every
   reference system's warning against uncontrolled autonomy (and the article's KAIROS cautionary tale,
   A1 lines 70-80).

### What to explicitly NOT copy

- **Anti-distillation, undercover mode, native attestation DRM** (A1) — these are vendor-hostile,
  trust-eroding behaviours antithetical to Talos's local/visible/auditable vision.
- **A repo-wide *vector* code index as the primary retrieval path** — no reference coding agent does
  this; it is the wrong first investment.
- **Bigger/fancier embedding models before the engine is coherent** — model choice is the last 10%.

---

## Confidence and limits

- **High confidence** on C1–C7: each is corroborated by ≥3 independent resources with file/line or
  page citations.
- **Medium confidence** on exact numeric thresholds: they are quoted from the cited lines but versions
  drift; treat them as design references, not constants to copy.
- The two PDFs are MEAP (in-progress) editions; chapter numbering may change in final print.
- This is a *static* documentation/source read. No reference binary was executed; no Talos code was
  modified.

---

## Source quick-reference

| ID | Path |
|----|------|
| R1 | `.claude/claude-code/src/...` (GrepTool, GlobTool, AgentTool, coordinatorMode, autoCompact, permissions, claudemd, systemPromptSections, bootstrap/state, context) |
| R2 | `.claude/gemini-cli/packages/core/src/...` (memoryDiscovery, memoryContextManager, chatCompressionService, bfsFileSearch, policy, mcp-client, environmentContext, prompts/snippets) |
| R3 | `.claude/hermes-agent/` (trajectory_compressor.py, hermes_state.py, toolset_distributions.py, tools/skills_hub.py, providers/) |
| R4 | `.claude/openclaw/` (VISION.md, AGENTS.md, docs/concepts/{compaction,memory,memory-search,memory-builtin}.md, packages/memory-host-sdk/src/host/*) |
| B1 | `.claude/Build_an_AI_Agent_(From_Scratch)_v5_MEAP.pdf` — §1.5 context engineering, Ch5 search, Ch6 memory |
| B2 | `.claude/Build_a_Multi-Agent_System_(MEAP-Book).pdf` — Ch1 memory model, Ch7 memory, Ch9 multi-agent |
| A1 | `.claude/alex000kim-article (1).txt` — Claude Code source-leak analysis |
