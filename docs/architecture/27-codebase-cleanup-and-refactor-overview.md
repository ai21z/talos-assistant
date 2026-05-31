# Codebase Cleanup & Refactor Overview (v0.9.0-beta-dev, 2026-04-19)

Read-only analysis. **No code changes are prescribed here**; this document
exists so cleanup work can be planned carefully and executed in small,
reversible PRs without affecting current behavior.

---

## 1. Scope and Guardrails

- Branch of record: `v0.9.0-beta-dev`. All cleanup PRs target this branch.
- **Parity before deletion.** Any removal must be preceded by a parity test
  (green) demonstrating that the removed surface has no live caller.
- **No infra/CI/quality-tooling changes** in cleanup PRs. Per
  `.github/assistant-instructions.md`, those belong on
  `feature/code-quality-stack` and are merged separately.
- **No broad package reshuffles** without explicit approval.
- **No framework rewrites** (LangChain4j, Spring AI, etc.).
- **No MCP server logic** until the retrieval seam is stable.
- Preserve behavior of the recently-landed priority-queue items (see §9.0).

### 1.1 What Talos actually is today (operational framing)

For the rest of this document, "Talos" means the concrete code on
`v0.9.0-beta-dev`, not the older retrieval-only headline:

- **Live runtime center is unified tool-driven assistance.**
  `cli/modes/AutoMode.java` is an explicit placeholder (its javadoc line 10–11
  says so); all real routing is in `ModeController#route`. That route sends
  deterministic file-ops (ls/dir/show/open) to `DevMode` and **everything
  else to `UnifiedAssistantMode`** — the tool-calling path.
- **`RagMode` is still a first-class explicit mode** (`/mode rag`), but it is
  no longer the default execution path. Retrieval is one tool among many
  inside the unified tool loop.
- **`AskMode`** remains for general chat **without pre-injected RAG context**.
  It is not a strict "non-tool" mode: it still builds a tool-aware system
  prompt and executes through `AssistantTurnExecutor`, so tool calls can still
  occur if the model emits them.
- **`WebMode`** is a reserved stub (see §5.2a).

This matters for §4, §7, and §9: the heaviest concerns are in the
tool-calling spine (`LlmClient` → `ToolCallLoop` → `AssistantTurnExecutor`
→ `ToolRegistry`), not in the retrieval pipeline.

---

## 2. Build & Test Health Snapshot

Measurement run: `gradlew clean build` on 2026-04-19, branch
`v0.9.0-beta-dev`.

| Metric              | Value              |
|---------------------|--------------------|
| Tests executed      | 2341               |
| Passed              | 2321               |
| Failed              | 18                 |
| Skipped             | 2                  |
| Build outcome       | FAILED (on tests)  |
| Compile warnings    | none blocking      |

### 2.1 Failure classification (all 18 are pre-existing, not new regressions)

| Group | Tests | Root cause | Real defect? |
|---|---|---|---|
| `LlmClientRetryTest` (5) | `placeholder_chat*`, `placeholder_messages_*` | Throws `EngineException$ModelNotFound: qwen3:8b` — the test environment has no Ollama model of that id; tests presume a placeholder-engine short-circuit that the current `LlmClient` no longer takes | **(b) test-environment coupling**. Fix = decouple these tests from real engine discovery. |
| `AssistantTurnExecutorTest` (7) | `returns_answer_and_marks_streamed`, `streamed_text_matches_returned_text`, `answer_sanitizer_is_applied`, `response_truncated_when_over_max_chars`, `retryTriggeredForDeflectionAfterToolUse`, `synthesisRetryFiresForRealTranscriptDeflection`, streaming-grounding-no-annotation | Same `qwen3:8b` not found error bleeds through into assertions about sanitizer/truncation/streamed-flag | **(b)**. Tests need a fake `ModelEngine` stub instead of hitting real engine resolution. |
| `StreamingModeTest` (2) + `ModeErrorMessageTest` (1) | `askMode_with_streamSink_*` | Same family — placeholder routing not exercised because real-engine resolution trips first | **(b)**. |
| `TalosBannerTest` (2) | `print_contains_version`, `printCompact_contains_brand_and_version` | Banner calls `BuildInfo.version()`, which reads `Implementation-Version` from the JAR manifest and **already falls back cleanly to `"unknown"`** when the manifest is absent (see `BuildInfo.manifestAttr`, lines 89–94). The failure is **not** a missing production fallback; it is that Gradle `:test` runs from exploded classes, so `version()` returns `"unknown"`, and the tests assert on the literal string `"0.9.0-beta"`. | **(b)**. Fix is a build/test ergonomics improvement: either teach `BuildInfo.version()` to consult a build-time resource when the manifest is absent, or adjust the banner tests to accept the fallback in exploded-class runs. Production behavior is unchanged. |
| `ConversationCompactionTest.compact_withTurns_returnsNewSketch` (1) | | Depends on a tokenizer/compactor path that indirectly hits engine resolution | **(b)**. |

**Conclusion:** 18/18 failures are **environment-coupling defects in the
tests**, not production defects. No production change needed to make the
build green — a narrow test-refactor PR that introduces a `FakeModelEngine`
fixture plus a manifest-less `BuildInfo` fallback would eliminate all 18.

### 2.2 Health verdict

- Production code: **healthy**. Compiles cleanly. No blocking warnings.
- Test suite: **fragile at the edges**. The placeholder-engine seam is not
  well isolated; a single resolver change cascades into ~15 tests.
- CI readiness: **blocked** by the 18 failures above until the test-fixture
  decoupling lands.

---

## 3. Structural Map

### 3.1 Top-level packages under `src/main/java/dev/talos`

| Package     | Files | LOC  | Role                                             | Alignment |
|-------------|------:|-----:|--------------------------------------------------|-----------|
| `api`       |  1    |  141 | Programmatic API seam (`TalosKnowledgeEngine`)   | OK        |
| `app`       |  3    |  336 | `Main`, bootstrap entry, deprecated JavaFX wizard | OK      |
| `cli`       | 68    | 6799 | picocli subcommands, REPL, slash commands, modes, UI | **See 3.2** |
| `core`      | 65    | 7883 | Config, LLM, retrieval, ingest, index, embed, cache, security | **See 3.3** |
| `engine`    |  3    |  678 | Ollama engine impl                               | OK        |
| `runtime`   | 27    | 3657 | Session, approval, turn processing, tool-call loop | **See 3.4** |
| `spi`       | 12    |  315 | `ModelEngine`, types                             | **Duplicate — see 3.5** |
| `tools`     | 21    | 2071 | Tool registry + concrete tools                   | OK        |

**Architecture drift flagged:** `.github/assistant-instructions.md` still names
pre-Talos package paths (lines 86-98). The actual tree is `dev.talos.*`.
Doc is stale - fix during the first cleanup PR (doc-only, zero risk).

### 3.2 `cli` sub-structure — the **`cmds` vs `commands` naming collision**

- `cli/cmds/` (9 files) — **picocli top-level subcommands** for the binary
  launcher (`talos run`, `talos rag-index`, `talos rag-ask`, `talos setup`,
  `talos net`, `talos status`, `talos version`, `talos diagnose`).
- `cli/commands/` (28 files) — **REPL slash-commands** (`/help`, `/quit`,
  `/mode`, `/status`, …) with their own `Command`, `CommandRegistry`,
  `CommandSpec`, `CommandGroup` abstractions.
- `cli/modes/` (11 files) — prompt-to-mode routing + mode implementations
  (`AskMode`, `AutoMode`, `DevMode`, `RagMode`, `UnifiedAssistantMode`,
  `WebMode`, plus `AssistantTurnExecutor`, `PromptClassifier`, `ModeController`,
  `WorkspaceSymbolChecker`, `BaseMode`).
- `cli/repl/` (14 files) — wiring (`TalosBootstrap`), the REPL runtime
  (`ReplRouter`), execution pipeline, session state, render engine.
- `cli/ui/` — banner and ANSI utilities.

**Finding (naming):** `cmds` vs `commands` vs `ModeController` vs
`ReplRouter` vs `PromptClassifier` is genuinely confusing. `PromptClassifier` is a
pure-function classifier (enum `Route`), while `ReplRouter` is the runtime
router. These live in different sub-packages but the name collision is a
recurring review cost. *Candidate rename* (low risk, doc-only + package move):

- `cli.cmds` → `cli.launcher` (picocli)
- `cli.commands` → `cli.repl.slash` (moved under repl, since that is their lifetime)
- `cli.modes.PromptRouter` → `cli.modes.PromptClassifier`

All three are mechanical IDE refactors; risk = compile-only. Defer until after
the 18-test fixture fix.

### 3.3 `core` sub-structure — **dual SPI / dual engine packages**

Observed:

- `dev.talos.spi/` — `ModelEngine`, `ModelCatalog`, `EngineException`,
  `ModelEngineProvider`, plus `spi/types/*` (ChatRequest, ChatMessage,
  TokenChunk, Capabilities, Health, EmbeddingResult).
- `dev.talos.core.spi/` — **only two files**: `CorpusStore`, `Embeddings`.
- `dev.talos.engine.ollama.OllamaEngine` — implements `dev.talos.spi.ModelEngine`.
- `dev.talos.core.engine.EngineRegistry` — lives in a *different* package than
  the only engine implementation.

**Finding (SOLID / DIP):** the SPI boundary is split across two packages
with no documented distinction. A reader cannot tell from the package name
whether a contract is a model contract or a storage contract. The two-file
`core.spi` package is a vestige (likely left over from an earlier
`corpus`/`embeddings` reshuffle).

**Candidate consolidation** (medium risk, touches imports across the tree):

- Move `CorpusStore`, `Embeddings` into `dev.talos.spi.corpus` and
  `dev.talos.spi.embed`.
- Move `EngineRegistry` into `dev.talos.spi` (it wires `ModelEngine`).
- Retire `dev.talos.core.spi` and `dev.talos.core.engine` packages.

Defer until after the more urgent god-class work because this is an
import-churn PR and should be the *only* change in its PR.

### 3.4 `runtime` sub-structure

27 files, 3657 LOC. Cohesive: session durability, approval, turn processing,
tool-call plumbing. The heaviest single file is `ToolCallLoop.java` (965
LOC) — see §4.

### 3.5 `api` and `app`

`dev.talos.api` has exactly one public type (`TalosKnowledgeEngine`, 141
LOC). It is the programmatic seam mandated by the architecture doc. Healthy.

`dev.talos.app` has `Main.java` and a **deprecated JavaFX wizard**
(`FirstRunWizard`, explicitly marked `@Deprecated(since = "0.9.0",
forRemoval = true)` at line 26; the only remaining reference is a javadoc
link from `TerminalFirstRun`). See §5.

---

## 4. God Classes & SRP Violations

### 4.1 Top-25 largest files (main-source)

| Rank | File | LOC | Responsibility count (≈) | Risk |
|---:|---|---:|---:|---|
| 1 | `core/llm/LlmClient.java` | 1018 | 6–8 | **High** |
| 2 | `runtime/ToolCallLoop.java` | 965 | 5–6 | **High** |
| 3 | `cli/modes/AssistantTurnExecutor.java` | 923 | 5 | **High** |
| 4 | `engine/ollama/OllamaEngine.java` | 554 | 4 | Medium |
| 5 | `core/index/LuceneStore.java` | 418 | 3 | Medium |
| 6 | `cli/repl/TalosBootstrap.java` | 405 | 4 | Medium |
| 7 | `cli/modes/PromptClassifier.java` | 397 | 2 | Low |
| 8 | `runtime/TurnProcessor.java` | 363 | 3 | Medium |
| 9 | `core/index/Indexer.java` | 353 | 2 | Low |
| 10 | `core/ingest/CodeBlockSplitter.java` | 343 | 1 | Low |
| 11 | `core/embed/EmbeddingsClient.java` | 332 | 2 | Low |
| 12 | `cli/repl/RenderEngine.java` | 327 | 2 | Low |
| 13 | `cli/modes/RagMode.java` | 321 | 2 | Low |
| 14 | `core/llm/SystemPromptBuilder.java` | 312 | 2 | Low |
| 15 | `runtime/ToolCallStreamFilter.java` | 302 | 2 | Medium (XML legacy) |
| 16 | `core/context/ConversationManager.java` | 295 | 2 | Low |
| 17 | `core/rag/RagService.java` | 282 | 2 | Low |
| 18 | `core/cache/CacheDb.java` | 256 | 2 | Low |
| 19 | `tools/ToolRegistry.java` | 238 | 3 | Medium |
| 20 | `cli/commands/BenchCommand.java` | 232 | 1 | Low |
| 21 | `runtime/JsonSessionStore.java` | 232 | 2 | Low |
| 22 | `core/Config.java` | 229 | 2 | Low |
| 23 | `runtime/ToolCallParser.java` | 225 | 2 | Medium (XML legacy) |
| 24 | `core/context/ContextPacker.java` | 204 | 1 | Low |
| 25 | `tools/impl/ContentVerifier.java` | 200 | 1 | Low |

### 4.2 `LlmClient` (1018 LOC) — **top-priority refactor target, high risk**

Mixed responsibilities observed:

- chat/chatStream/chatFull/chatStreamFull dispatch
- placeholder routing for tests
- wall-clock budget + idle watchdog + repetition breaker (`withWallClockBudget`)
- async cancellation plumbing (receives the future-cancel chain; the
  pending SPI-level async close item in the priority queue targets this file)
- retry logic (the `LlmClientRetryTest` surface)
- synchronous stream-close (added in priority-queue item #3)
- tracking sinks + repetition accounting
- exception taxonomy (`IdleStreamException`, `RepetitionException`)

**Why it is high risk:** every active priority-queue fix landed here in the
last week (repetition breaker, sync stream close, pending async SPI close).
Any extraction must wait until the SPI-level async close (item #6) lands and
stabilizes.

**Suggested extraction targets** (not now, post-#6):

- `core/llm/StreamWatchdog` — owns idle timing + repetition breaker + cancel
- `core/llm/LlmRetryPolicy` — isolates retry + backoff
- **Injectable engine-resolution seam** on `LlmClient`
  (most plausibly an injected `EngineRegistry`, factory, or equivalent
  collaborator, while retaining the existing `LlmClient(Config)` entry
  point). This is the real DIP fix that also unblocks the 16
  placeholder-routing tests — no separate "PlaceholderRouter" class is
  needed.

### 4.3 `ToolCallLoop` (965 LOC) — **medium risk**

Only 5 public methods (per grep), but the file is long because of embedded
state machines for:

- extracting tool calls from stream (delegates to `ToolCallParser`,
  `CodeBlockToolExtractor`, `ToolCallStreamFilter`)
- executing the tool with the registry
- handling approvals (via `ApprovalGate`)
- re-invoking the LLM with tool output
- budgets (`Limits`), bail-out conditions, recursion counters

**Extraction target:** `runtime/toolcall/` sub-package splitting the
extract → approve → execute → reinject phases into stages, mirroring how
`RagService.prepare()` uses the `RetrievalPipeline` stages. Low-to-medium
invasive; parity tests already exist (`ToolCallLoopTest*`).

### 4.4 `AssistantTurnExecutor` (923 LOC) — **high risk**

This file is the origin of 7 of the 18 test failures. Responsibilities:

- streaming execute path
- non-streaming execute path
- sanitization + truncation
- deflection detection + synthesis retry
- grounding-annotation injection (streaming + non-streaming)

**Shape note.** `AssistantTurnExecutor` is a **static utility class**:
`public final class` with `private AssistantTurnExecutor() {} // utility
class` (line 45). Its only collaborators come in through `Context ctx`,
and the LLM-call seam it actually uses is **`ctx.llm()` → `LlmClient`**.
It does not hold or look up a `ModelEngine` directly.

**Root cause for the fragile tests:** the tests drive the executor against
a real `LlmClient` that in turn hits real engine resolution. The correct
test seam is therefore either
(a) swap `ctx.llm()` for a scripted `LlmClient` fixture (the harness already
does this via `ExecutorScenarioRunner` — see the class javadoc), or
  (b) inject an **engine-resolution seam into `LlmClient`** (the layer
  below), so every caller of `LlmClient` — including the live
  `AssistantTurnExecutor` — resolves engines through an injectable
  collaborator rather than fixed internal discovery.

Changing `AssistantTurnExecutor`'s own constructor is **not** a correct
remedy: it has no public constructor, and adding one would also mean
removing the deliberate static-utility shape. See §9.2 for the corrected
backlog item.

### 4.5 `OllamaEngine` (554 LOC) — **medium risk, already in flight**

Pending SPI item #6 will change this file. After #6 lands, split into:

- `OllamaChatClient` (chat + streaming + cancel handle)
- `OllamaEmbedClient` (embeddings)
- `OllamaHealthProbe` (caps, health, tags)

Currently all three concerns share one 554-LOC file.

### 4.6 `TalosBootstrap` (405 LOC)

Wiring orchestration — not a god class in the SRP sense (single concern:
assemble the ReplRouter), but it does grow whenever a new component joins
the REPL. Keep as-is; do not extract a DI framework just for this.

### 4.7 `TurnProcessor` (363 LOC)

8 public methods. Touches approval + session persistence + template
placeholder guard + turn audit + streaming + memory update. This is the
second-highest SRP debt after `LlmClient`. Defer until `LlmClient` is split.

### 4.8 `ToolRegistry` (238 LOC)

9 public methods. Mixed concerns: registration, alias table (where the `ls`
alias was added in priority-queue item #2), separator normalization,
lookup, execution, context-aware vs legacy-no-context execution paths.
**Legacy no-context path** is explicitly marked as such in javadoc — see §5.

---

## 5. Dead / Legacy / Duplicate Code

### 5.1 Explicitly marked as deprecated

| Marker | File | Action |
|---|---|---|
| `@Deprecated(since = "0.9.0", forRemoval = true)` | `app/ui/FirstRunWizard.java` (JavaFX) | Only referenced from `TerminalFirstRun` javadoc. **Safe to delete** in a single-file PR once a parity check confirms the JavaFX dep is otherwise unused. |
| `"legacy, no context"` in javadoc | `tools/ToolRegistry.java:242`, `tools/TalosTool.java:11,25,29,35` | Default interface method wraps legacy. Convert all callers to context-aware, then delete the default. Moderate-risk (tests reference both). |
| `"DEPRECATED COMPATIBILITY ONLY"` (XML tool-call parsing) | `runtime/ToolCallStreamFilter.java` (lines 22, 51, 57, 64, 71, 156), `runtime/ToolCallParser.java` (lines 31, 79, 104, 133, 139), `core/util/Sanitize.java` (lines 24, 142) | XML parsing is retained *only* for models that emit XML from training habits. Per `docs/architecture/25-xml-retirement-review.md`, retirement is planned. **Needs a parity metric**: count of real transcripts where XML fallback fires. Defer deletion until that metric is zero for N releases. |
| `"legacy key"` | `core/embed/EmbeddingsFactory.java:29` (`ollama.embed`) | Old config key retained for backward compat. Add a one-release deprecation warning then remove in the next minor. |

### 5.2 Potentially dead — needs caller verification before removal

| Suspect | Evidence | Disposition |
|---|---|---|
| `runtime/CodeBlockToolExtractor.java` | Partially overlaps `ToolCallParser` + `ToolCallStreamFilter`. | **Investigate overlap**; may be foldable. |

### 5.2a Live but stubbed surfaces (not dead — be careful)

These are real code paths in production wiring. They are *not* dead-code
suspects. They are flagged here only so later work does not accidentally
treat them as dead.

| Surface | Evidence | Correct framing |
|---|---|---|
| `cli/modes/WebMode.java` | Registered in `ModeController.java:205`; advertised by `cli/commands/ModeCommand.java:25` (`"Available: auto, rag, chat, dev, ask, web"`); `README.md:204` states **"`web` mode is not implemented (placeholder only, returns 'reserved' message)"**. | **Live stub / reserved surface.** Not dead. Keep it, or productize it, but do not delete without a conscious decision to remove a documented mode. |
| `runtime/NoOpApprovalGate.java` | Active default in `TurnProcessor.java:43` (null-coalesce), `TurnProcessor.java:57` (secondary-constructor default), `Context.java:142` and `:163` (builder defaults). `ApprovalGate.java:7` javadoc: *"V1 uses `NoOpApprovalGate` which always approves."* | **Active compatibility/default implementation.** Not dead. May eventually be architecturally undesirable as a silent default (it always approves, which conflicts with the "distrust the model" posture), but that is a **policy** discussion, not a cleanup deletion. |
| `runtime/NoOpSessionStore.java` | Active default in `Session.java:41,45,54`. `SessionStore.java:7` javadoc: *"V1 uses `NoOpSessionStore` (ephemeral)."* | **Active compatibility/default implementation.** Not dead. Same framing as above: the fact that persistence defaults to a no-op is a policy question (silent data loss vs explicit opt-in), not a cleanup target. |

### 5.3 Confusing duplication (not dead, but worth consolidating)

- **Three routers**: `cli.modes.PromptClassifier` (classifier),
  `cli.modes.ModeController` (dispatcher), `cli.repl.ReplRouter` (REPL
  runtime). Not duplicates but readers constantly confuse them. Rename pass
  proposed in §3.2.
- **Two SPI packages** (`dev.talos.spi` and `dev.talos.core.spi`). See §3.3.
- **Two engine packages** (`dev.talos.engine.ollama` and
  `dev.talos.core.engine`). See §3.3.
- **Two command packages** (`cli.cmds` and `cli.commands`). See §3.2.

### 5.4 Abandoned assets hinted by `docs/architecture/25-xml-retirement-review.md`

Worth a follow-up sweep through `build/resources/main/prompts/` and any
`.xml` files lingering from the pre-JSON tool-call era. Out of scope for
this overview.

---

## 6. SOLID / Clean-Architecture Findings

### 6.1 Single Responsibility

Violations concentrated in §4: `LlmClient`, `ToolCallLoop`,
`AssistantTurnExecutor`, `OllamaEngine`, `TurnProcessor`, `ToolRegistry`.

### 6.2 Open/Closed

`ToolRegistry.ALIASES` is a hard-coded `Map.entry(...)` list (where `ls` was
just added). For a v0.9 CLI that is fine; for v1 it will want to load
aliases from config. Current shape doesn't prevent that — it just defers it.
Not a violation today.

### 6.3 Liskov Substitution

`FakeModelEngine`-like test stubs throw on methods they don't need (grep
`UnsupportedOperationException` across `src/test`). That is a test-code
smell, not a production LSP violation, and it should improve once the
engine-resolution seam described in §4.2 / §9.2 is in place.

### 6.4 Interface Segregation

- **`ModelEngine`** (`spi/ModelEngine.java`, 18 LOC): 7 methods —
  `id/caps/health/chat/chatStream/embed/close`. Reasonably narrow. **Caveat:** mixing chat and embed in one interface forces every engine to implement both. For Ollama that is free; for a future embedding-only or chat-only backend this will be a real ISP violation. **Pre-split now** into `ChatModelEngine` + `EmbeddingEngine` with a `ComposedEngine` for backends that do both. Low risk today (one implementor). High payoff later.
- **`TalosTool`** carries both legacy and context-aware `execute` methods —
  see §5.1. Narrow once the legacy method is deleted.

### 6.5 Dependency Inversion

- `LlmClient` resolves the target `ModelEngine` through internal
  `EngineRegistry` construction rather than an injected engine-resolution
  collaborator. This is the actual DIP gap that causes the 16
  placeholder-routing test failures in §2.1; `AssistantTurnExecutor`
  inherits the problem only because it sits on top of `LlmClient`.
- `AssistantTurnExecutor` itself is a static utility class (see §4.4) —
  it is not a DIP violation in its own right and should not be refactored
  to accept engine dependencies.
- `TalosBootstrap` directly `new`'s most components — acceptable at the
  composition root; do not introduce a DI framework.
- `OllamaEngine` is directly referenced by name in several places — OK while
  it is the only implementation, but the `EngineRegistry` relocation in §3.3
  will naturally pull these through the SPI instead.

### 6.6 Clean-architecture boundaries

- `core/*` does not import `cli/*` or `runtime/*` (spot-checked). Good.
- `runtime/*` imports `core/*` and `tools/*`. Good.
- `cli/*` depends on everything below it. Expected.
- The **architecture drift** to be fixed is documentation-only: the
  assistant-instructions file still talks about pre-Talos package names.

---

## 7. Design-Pattern Opportunities

Only patterns where the payoff clearly exceeds the churn are listed.

1. **Chain-of-Responsibility for `ToolCallLoop`** — mirrors the existing
   `RetrievalPipeline` stage pattern. Extracts a 965-LOC state machine into
   4–5 small stage classes. Medium invasive, high payoff for readability.
2. **Strategy for modes** — already present (`Mode` interface +
   `AskMode/RagMode/…`). No change needed; this is the one pattern the
   codebase already gets right.
3. **Facade for `TalosKnowledgeEngine`** (`dev.talos.api`) — already the
   intent, just under-used. As each subsystem consolidates, tighten the
   facade surface.
4. **Builder for `ChatRequest`** (`spi/types`) — fields are growing
   (budgets, stream options, cancel handles). A builder eliminates the
   constructor-parameter-creep that `LlmClient` is already exhibiting.
5. **Observer for turn events** — `SessionListener` and
   `MemoryUpdateListener` already exist. Consolidate into a single
   `TurnEventBus` instead of adding more listener interfaces ad-hoc.

Patterns deliberately **not** proposed: DI framework, event sourcing, CQRS,
hexagonal rewrite. None pass the "pay-off > churn" bar for Talos today.

---

## 8. Test Suite Hygiene

1. **Decouple tests from real engine resolution** (unblocks 16 of the 18
   failures). Two moves, either of which works: (a) have the existing
   `ExecutorScenarioRunner`-style harness supply a scripted `LlmClient`
   through `Context.llm()`; (b) add an injectable engine-resolution seam to
   `LlmClient`. See §9.2. Do **not** try to refactor `AssistantTurnExecutor`
   (it is a static utility — §4.4).
2. **Add an exploded-classes version source for `BuildInfo.version()`** so
   banner tests can resolve a real version outside a packaged JAR (unblocks
   2 of the 18). Production fallback is already in place — this is a
   test/build-ergonomics fix, not a production gap.
3. **Decouple tests from live Ollama.** No test under `src/test/java` should
   require a real `qwen3:8b` model to pass.
4. **Adopt the scenario-harness discipline** described in
   `docs/talos-source-pack-safe-local-alternative-2026-04-19.md` (v2)
   — specifically the OpenHands eval-harness *methodology* and the
   prompt-injection taxonomy — for regression coverage of the incidents
   logged in `test-output.txt` / `build-test-output.txt`.
5. **Test coverage gaps** (inferred from §4 file sizes vs test file names):
   - `OllamaEngine` has no direct HTTP-mock test; all coverage is
     transitive through `LlmClient`. A `MockWebServer`-style test is worth
     adding **after** the SPI-level async close (#6) lands.
   - `ToolCallLoop` has tests but they are coarse-grained. Stage extraction
     (see §7.1) would enable per-stage unit tests.

---

## 9. Prioritized Backlog (Safe Order)

Every PR below is atomic, reversible, and does **not** touch CI/quality
tooling. Each should be a single focused PR targeting `v0.9.0-beta-dev`.

### 9.0 Pre-existing priority-queue items (in flight / recently landed) — do not duplicate

| # | Title | Status |
|---|---|---|
| 1 | Status-gated replay of non-ok turns (`JsonTurnLogAppender` + `TalosBootstrap.replayTurnLog`) | **Landed** |
| 2 | `ls` alias in `ToolRegistry` | **Landed** |
| 3 | Synchronous stream close (`OllamaEngine` + `LlmClient`) | **Landed** |
| 4 | `RepetitionBreaker` + watchdog integration | **Landed** |
| 5 | JLine-safe stream sink (`TalosBootstrap`) | **Landed** |
| 6 | SPI-level async stream close (`ModelEngine` + `OllamaEngine` + `LlmClient` watchdog) | **In flight** — next up |

Finish #6 before starting any §9.1+ work.

### 9.1 Doc drift fix — zero production risk

- Files: `.github/assistant-instructions.md`
- Change: replace stale package references with `dev.talos.*`.
- Parity: doc-only.
- Rollback: revert.

### 9.2 Test seam: scripted `LlmClient` + injectable engine resolution in `LlmClient`

The seam is **below** `AssistantTurnExecutor`, not inside it.
`AssistantTurnExecutor` is a static utility that only talks to `ctx.llm()`;
the correct injection point is `LlmClient` itself.

Two independent moves, either of which unblocks the 16 placeholder tests:

- **9.2a — harness-style fixture (preferred first step, pure test change).**
  The executor's javadoc already calls out `ExecutorScenarioRunner` as a
  driver that supplies a scripted `LlmClient`. Extend that fixture so the
  `LlmClientRetryTest`, `AssistantTurnExecutorTest`, `StreamingModeTest`, and
  `ModeErrorMessageTest` families construct a `Context` whose `llm()` returns
  a scripted client. Zero production change.
- **9.2b — production-side injection (follow-up).** Give `LlmClient` an
  injectable engine-resolution collaborator — most plausibly an injected
  `EngineRegistry`, factory, or equivalent seam — while retaining the
  current `LlmClient(Config)` entry point for default behavior. This is the
  real DIP fix.

- Parity: all 2341 existing tests still pass; the 16 Assistant/Llm/Streaming
  tests flip to green.
- Rollback: revert; 9.2a is test-only, 9.2b adds a constructor overload and
  never changes the default call-site.

### 9.3 Test/build ergonomics: add an exploded-classes version source for `BuildInfo`

- `BuildInfo` already falls back cleanly to `"unknown"` (see §2.1 and
  `BuildInfo.java:89-94`), but `BuildInfo.version()` currently reads only the
  JAR manifest for version and does **not** consult
  `META-INF/talos-build.properties`. The work is therefore two-part:
  write a build-time version resource from Gradle, and teach
  `BuildInfo.version()` to consult it when manifest metadata is absent.
- Files: `build.gradle.kts` (add `processResources { expand(...) }` or a
  `Copy` task that writes the version resource), plus `BuildInfo.java`, plus
  a new resource template under `src/main/resources/`.
- Parity: `print_contains_version` and
  `printCompact_contains_brand_and_version` green.
- Rollback: revert.
- **Note:** `build.gradle.kts` edits can affect CI. If the resource-stamping
  step touches anything beyond local `processResources`, split into a
  standalone infrastructure PR per project rules.

### 9.4 Delete `FirstRunWizard` (JavaFX class-only PR)

- **Class-delete is safe.** `FirstRunWizard` is marked
  `@Deprecated(since = "0.9.0", forRemoval = true)` and is only referenced
  by a javadoc `{@link}` from `TerminalFirstRun` and a test comment in
  `TerminalFirstRunTest`. Nothing calls it.
- Files: remove `app/ui/FirstRunWizard.java`; adjust the javadoc link in
  `TerminalFirstRun` to plain text.
- Parity: `TerminalFirstRunTest` already asserts `Main` uses
  `TerminalFirstRun`.
- Rollback: revert.
- **Do NOT bundle the JavaFX dependency removal into this PR.** Removing
  JavaFX from `build.gradle.kts` is a **separate** decision that requires
  an independent sweep for any remaining JavaFX usages elsewhere in
  `src/main/java`. Make that a follow-up PR, kept off this branch if it
  ends up touching CI.

### 9.5 `WebMode` decision — productize or remove intentionally (not a dead-code delete)

- Files: `cli/modes/WebMode.java`, `cli/modes/ModeController.java:205`,
  `cli/commands/ModeCommand.java:25`, `README.md:204`.
- This is **not** a dead-code removal. `WebMode` is a documented reserved
  surface: registered in the `ModeController`, listed by the `/mode` slash
  command's help string, and explicitly called a placeholder in the README.
- Decision criterion: either
  (a) commit to implementing the mode and start a feature branch, or
  (b) retire the surface in a single PR that removes *all four* of its
  references simultaneously — the class, the registration, the help-string
  entry, and the README line — so no documentation advertises a mode that
  does not exist.
- Do **not** silently delete just the `.java` file.

### 9.6 `TalosTool` legacy-no-context removal (moderate risk)

- Migrate every `ToolRegistry` caller to context-aware execute.
- Remove the default no-context method after parity proof.

### 9.7 Split `ModelEngine` into `ChatModelEngine` + `EmbeddingEngine`

- Introduce new interfaces; have `ModelEngine` extend both (keeps
  back-compat). `OllamaEngine` implements `ModelEngine` unchanged. Downstream
  callers migrate one at a time. After a release, remove the composed
  interface.

### 9.8 SPI consolidation (the `core.spi` / `core.engine` retirement)

- Move `CorpusStore`, `Embeddings` to `dev.talos.spi.corpus` /
  `dev.talos.spi.embed`. Move `EngineRegistry` to `dev.talos.spi`. Retire
  `dev.talos.core.spi` and `dev.talos.core.engine` packages.
- Import-churn PR; should contain **no** logic changes.

### 9.9 `OllamaEngine` split (after #6 stabilizes)

- Extract `OllamaChatClient`, `OllamaEmbedClient`, `OllamaHealthProbe`.
- Parity: existing Ollama tests remain green.

### 9.10 `ToolCallLoop` stage extraction

- Introduce `runtime/toolcall/` stages mirroring `RetrievalPipeline`.
- Parity: every `ToolCallLoopTest*` remains green.

### 9.11 `LlmClient` decomposition (highest payoff, highest risk)

- Extract `StreamWatchdog` and `LlmRetryPolicy`; finalize the injectable
  engine-resolution seam started in §9.2b.
- **Only after** items #6, 9.2, 9.7 all land. Do not start earlier.

### 9.12 XML-parsing retirement

- Gate: `docs/architecture/25-xml-retirement-review.md` metric reaches
  zero for N releases.
- Delete the `DEPRECATED COMPATIBILITY ONLY` branches in
  `ToolCallStreamFilter`, `ToolCallParser`, `Sanitize`.

### 9.13 Rename pass (cosmetic, last)

- `cli.cmds` → `cli.launcher`
- `cli.commands` → `cli.repl.slash`
- `cli.modes.PromptRouter` → `cli.modes.PromptClassifier`
- Mechanical, run the IDE refactor, verify compile.

---

## 10. Explicit Non-Goals

Per `.github/assistant-instructions.md` and this review:

- No rewrite around LangChain4j, Spring AI, or any agent framework.
- No merging of broad long-term memory into Talos core without a scoped design.
- No MCP server implementation until the retrieval seam is stable.
- No broad package reshuffles beyond the targeted ones in §9.7–9.8.
- **No CI / quality-tooling (JaCoCo, Sonar, Qodana, CodeQL, Snyk, workflow
  files) changes on `v0.9.0-beta-dev` or `main`**. These belong on
  `feature/code-quality-stack`.
- No deletion of legacy XML parsing or legacy Tool methods without parity
  evidence collected over multiple releases.
- No introduction of a DI framework, event-sourcing layer, or hexagonal
  rewrite.
- No work on `LlmClient` decomposition until in-flight priority-queue
  item #6 has landed and been stable for at least one cycle.

---

## Appendix A — Data sources for this review

- `gradlew clean build` run on 2026-04-19 (2341 tests, 18 failures).
- `src/main/java/dev/talos/**` file listing + LOC counts (PowerShell
  `Get-ChildItem -Recurse` + `Measure-Object -Line`).
- grep sweeps for `@Deprecated`, `legacy`, `DEPRECATED`, `TODO remove`,
  `@link`, `new OllamaEngine(`, `new WebMode(`, `cli.cmds`, `cli.commands`,
  `UnifiedAssistantMode`, `DevMode`, `FirstRunWizard`, `ReplRouter`,
  `PromptClassifier`, `NoOpApprovalGate`, `NoOpSessionStore`.
- `build/test-results/test/*.xml` for per-test failure classification.
- Cross-reference against `.github/assistant-instructions.md`,
  `README.md`, and `docs/architecture/{21,23,24,25,26,talos-harness-*}.md`.

## Appendix B — Change log

- **2026-04-19 (rev 3)** — second maintainer review:
  1. Added §1.1 "What Talos actually is today" — the live runtime center is
     **unified tool-driven assistance** (`UnifiedAssistantMode`), not
     classic RAG. `AutoMode` is an explicit placeholder per its own
     javadoc; `RagMode` is still a first-class explicit mode but is no
     longer the default execution path.
  2. Rewrote §4.4 and §6.5 — `AssistantTurnExecutor` is a **static utility
     class** (`private AssistantTurnExecutor() {} // utility class` at line
     45). Earlier rev-1/rev-2 wording suggesting it should "accept a
     `ModelEngineProvider` in the constructor" was architecturally wrong.
     The correct seam is `LlmClient` (and/or a scripted `LlmClient` from
     the existing `ExecutorScenarioRunner` harness).
  3. Rewrote §9.2 accordingly: split into 9.2a (harness-only fixture, pure
     test change) and 9.2b (injectable engine-resolution seam inside
     `LlmClient`). Dropped the "`PlaceholderRouter`" extraction from §4.2
     and §9.11 — the DIP fix subsumes it.
  4. Tightened §2.1 and §9.3: `BuildInfo` already falls back cleanly to
     `"unknown"` (see `BuildInfo.java:89-94`); the banner-test failures
     come from tests asserting the literal `"0.9.0-beta"` string against
     that fallback, not from a missing production fallback. §9.3 is now
     framed correctly as a two-part test/build-ergonomics fix: add a
     build-time version resource and have `BuildInfo.version()` consult it
     outside packaged JAR runs.
  5. Rewrote §9.4 — class-delete of `FirstRunWizard` is a single-PR safe
     operation, but **removing JavaFX from `build.gradle.kts` is a
     separate decision** that requires its own sweep for remaining
     JavaFX usage and is kept off this PR.
- **2026-04-19 (rev 2)** — corrections from maintainer review:
  1. Removed the false claim that `dev.talos.app` contains a
     `Version.java` (no such file exists in the tree).
  2. Reclassified `NoOpApprovalGate` and `NoOpSessionStore` from
     "potentially dead — naming suggests test-only fallbacks" to
     "active compatibility/default implementations" (they are the
     null-coalesce defaults in `TurnProcessor`, `Context.Builder`, and
     `Session`; their javadoc explicitly names them as the V1 defaults).
  3. Strengthened the `WebMode` framing with the `ModeCommand.java:25`
     help-string and `README.md:204` placeholder references, and rewrote
     backlog §9.5 to reflect that any removal must also retire the
     `/mode` help entry and the README line atomically.
- **2026-04-19 (rev 1)** — initial draft.
