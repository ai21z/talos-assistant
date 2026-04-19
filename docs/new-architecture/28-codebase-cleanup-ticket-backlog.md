# Codebase Cleanup Ticket Backlog

Branch plan for a dedicated cleanup/refactor stream off `v0.9.0-beta-dev`.

This document converts the analysis in
`27-codebase-cleanup-and-refactor-overview.md` into concrete tickets that can
be copied into IntelliJ Tasks, GitHub Issues, YouTrack, or a plain-text sprint
board.

The intent is not to do a large-batch refactor. The intent is to create
**small, reviewable, reversible tickets** that each preserve current behavior.

---

## 1. Branch Strategy

- Source branch: `v0.9.0-beta-dev`
- Umbrella branch: `chore/codebase-cleanup-refactor`
- Rule: use the umbrella branch only as a planning/integration branch if needed
- Rule: each ticket should land as its own PR from a dedicated ticket branch back
  into `v0.9.0-beta-dev`
- Rule: ticket branches may be cut directly from `v0.9.0-beta-dev` or from the
  umbrella branch, but each PR must contain only one ticket's changes
- Rule: do not combine unrelated cleanup items into one PR
- Rule: no CI / Qodana / JaCoCo / Sonar / workflow changes on this branch
- Rule: parity before deletion

Recommended branch creation commands:

```powershell
git checkout v0.9.0-beta-dev
git pull
git checkout -b chore/codebase-cleanup-refactor
```

Example ticket branch flow:

```powershell
git checkout v0.9.0-beta-dev
git pull
git checkout -b ticket/CCR-001-doc-drift-fix
```

---

## 2. Ticket Order

These tickets are ordered by safety and dependency.

1. `CCR-001` doc drift fix in `.github/assistant-instructions.md`
2. `CCR-002` decouple failing tests from real engine resolution with the correct seam per test layer `[done]`
3. `CCR-003` `BuildInfo` exploded-classes version source `[done]`
4. `CCR-004` delete `FirstRunWizard` class only `[done]`
5. `CCR-005` decide `WebMode`: keep reserved or retire intentionally `[done]`
6. `CCR-006` migrate `TalosTool` from legacy no-context execution to context-aware execution `[done]`
7. `CCR-007` split `ModelEngine` into chat/embed interfaces `[done]`
8. `CCR-008` SPI package consolidation `[done]`
9. `CCR-009` split `OllamaEngine` `[done]`
10. `CCR-010` extract `ToolCallLoop` stages `[done]`
11. `CCR-011` decompose `LlmClient`
12. `CCR-012.1` instrument and observe XML compatibility fallback usage
13. `CCR-012.2` retire XML compatibility path if parity evidence justifies it
14. `CCR-013` naming cleanup pass (`cmds` / `commands` / `PromptRouter`)

Do not start `CCR-009` onward until the in-flight async-close work is stable.

---

## 3. Ticket Template

Use this shape for each tracker ticket:

- Title
- Why this exists
- Scope
- Out of scope
- Main files
- Risks
- Acceptance criteria
- Rollback plan
- Dependencies

---

## 4. Tickets

### CCR-001 — Fix stale `dev.loqj.*` package references in project instructions

**Why this exists**

`.github/assistant-instructions.md` still describes package paths under
`dev.loqj.*`, while the codebase is `dev.talos.*`. This creates avoidable
confusion for humans and AI assistants.

**Scope**

- Replace stale `dev.loqj.*` package references with `dev.talos.*`
- Keep intent and project rules unchanged
- Restrict changes to documentation only

**Out of scope**

- Any production code
- Any package renames
- Any architecture rewrites

**Main files**

- `.github/assistant-instructions.md`

**Risks**

- Extremely low

**Acceptance criteria**

- All package examples in `.github/assistant-instructions.md` match the real repo
- No code files changed

**Rollback plan**

- Revert the doc commit

**Dependencies**

- None

---

### CCR-002 — Decouple failing tests from real engine resolution with the correct seam per test layer

**Status**

- Done on `ticket/CCR-002-test-engine-decoupling`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

The current failing tests are coupling themselves to live engine resolution and
to a real `qwen3:8b` environment. The first objective is to make those tests
deterministic without changing production behavior.

**Scope**

- Rework the failing mode/repl tests (`AssistantTurnExecutor`, streaming-mode,
  mode-error tests) to use scripted `LlmClient` fixtures through
  `Context.llm()`
- Treat direct `LlmClient` tests separately: fix them through a lower seam
  that still exercises real `LlmClient` behavior, not by replacing the class
  under test with a scripted client
- Prefer pure test-side changes first where possible

**Out of scope**

- Production refactor of `LlmClient`
- New runtime behavior
- CI changes

**Main files**

- `src/test/java/dev/talos/core/llm/LlmClientRetryTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/test/java/dev/talos/cli/modes/StreamingModeTest.java`
- `src/test/java/dev/talos/cli/modes/ModeErrorMessageTest.java`
- Any shared test fixture file added under `src/test/java`

**Risks**

- Medium: easy to accidentally weaken test realism if the fixture becomes too fake

**Acceptance criteria**

- The engine-coupled failures in:
  `LlmClientRetryTest`, `AssistantTurnExecutorTest`, `StreamingModeTest`,
  and `ModeErrorMessageTest` are resolved without requiring live Ollama
- Mode/repl tests use `Context.llm()` or an equivalent injected seam rather
  than accidental live engine resolution
- Direct `LlmClient` tests still exercise real `LlmClient` behavior
- No production files changed in the first pass unless a lower seam proves
  strictly necessary

**Rollback plan**

- Revert test-only commit

**Dependencies**

- None

---

### CCR-003 — Add exploded-classes version source for `BuildInfo.version()`

**Status**

- Done on `ticket/CCR-003-buildinfo-exploded-version`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

`BuildInfo.version()` currently relies on manifest metadata and correctly falls
back to `"unknown"` when running from exploded classes. That is safe in
production, but it breaks banner tests that assert a concrete version string.

**Scope**

- Add a build-time version resource generated during `processResources`
- Teach `BuildInfo.version()` to consult that resource when manifest metadata
  is absent
- Keep existing manifest behavior as first priority

**Out of scope**

- Build pipeline / CI restructuring
- Replacing manifest usage entirely
- Broader `BuildInfo` redesign

**Main files**

- `src/main/java/dev/talos/core/util/BuildInfo.java`
- `build.gradle.kts`
- new resource template under `src/main/resources/`
- `src/test/java/dev/talos/cli/ui/TalosBannerTest.java`

**Risks**

- Low to medium: build-resource logic can accidentally drift into CI/tooling

**Acceptance criteria**

- `TalosBannerTest` version assertions pass in test runs from exploded classes
- `BuildInfo.version()` resolves correctly in both packaged-JAR and exploded-class runs
- `BuildInfo.version()` still prefers manifest metadata when present
- No behavioral regression in startup/banner code

**Rollback plan**

- Revert commit

**Dependencies**

- None, but should ideally follow `CCR-002`

---

### CCR-004 — Delete deprecated `FirstRunWizard` class only

**Status**

- Done on `ticket/CCR-004-remove-first-run-wizard`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

`FirstRunWizard` is deprecated for removal and has no live runtime callers.
This is a low-risk cleanup if kept strictly to class deletion.

**Scope**

- Remove `app/ui/FirstRunWizard.java`
- Update any javadoc references that point to it

**Out of scope**

- Removing JavaFX dependencies from Gradle
- Any installer or setup redesign
- Any first-run UX changes

**Main files**

- `src/main/java/dev/talos/app/ui/FirstRunWizard.java`
- `src/main/java/dev/talos/app/ui/TerminalFirstRun.java`

**Risks**

- Low, if the ticket remains class-only

**Acceptance criteria**

- The class is deleted
- No runtime production code references it
- Existing first-run behavior still uses `TerminalFirstRun`

**Rollback plan**

- Restore the file

**Dependencies**

- None

---

### CCR-005 — Make an explicit `WebMode` product decision

**Status**

- Done on `ticket/CCR-005-webmode-decision`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

`WebMode` is not dead code. It is a reserved, documented surface. It should
either remain consciously reserved or be removed as a coordinated product
decision.

**Scope**

Choose one of two outcomes:

- Option A: keep `WebMode` as a reserved stub and tighten its docs/help text
- Option B: remove `WebMode` and all references to it in one atomic change

**Out of scope**

- Building real browser/web capability
- Partial deletion of only the `.java` file

**Main files**

- `src/main/java/dev/talos/cli/modes/WebMode.java`
- `src/main/java/dev/talos/cli/modes/ModeController.java`
- `src/main/java/dev/talos/cli/commands/ModeCommand.java`
- `README.md`

**Risks**

- Medium: easy to create doc/product inconsistency

**Acceptance criteria**

- No mismatch between code, `/mode` help, and README
- If removed, all references are retired together
- If kept, the reserved-stub framing is explicit and consistent

**Rollback plan**

- Revert the PR

**Dependencies**

- None

---

### CCR-006 — Migrate `TalosTool` contract from legacy no-context execution to context-aware execution

**Status**

- Done on `ticket/CCR-006-context-aware-talos-tool`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

The tool system still carries both legacy no-context execution and the newer
context-aware path. More importantly, the interface contract still treats the
legacy path as primary: `TalosTool.execute(ToolCall)` is the abstract method,
while `execute(ToolCall, ToolContext)` currently defaults to it. That contract
shape should be reversed only after parity is proven.

**Scope**

- Find all remaining callers of legacy `execute(call)` paths
- Migrate callers to context-aware execution where appropriate
- Update every concrete tool implementation so the context-aware method is the
  real primary implementation
- Only after implementation and caller parity is proven, change the interface
  contract and remove the legacy no-context path

**Out of scope**

- Tool redesign
- Approval policy changes
- New tool additions

**Main files**

- `src/main/java/dev/talos/tools/TalosTool.java`
- `src/main/java/dev/talos/tools/ToolRegistry.java`
- Any remaining call sites using legacy execution

**Risks**

- Medium to high: this is both a caller migration and an interface/implementation
  contract migration

**Acceptance criteria**

- No live production call site relies on the legacy no-context method
- Concrete tool implementations are context-aware first, not legacy-first
- No new regressions relative to the current baseline in relevant tool/runtime tests
- Legacy method removal happens only after parity evidence exists

**Rollback plan**

- Restore the legacy path

**Dependencies**

- None

---

### CCR-007 — Split `ModelEngine` into chat and embedding interfaces

**Status**

- Done on `ticket/CCR-007-split-modelengine-chat-embed`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

The current `ModelEngine` combines chat and embed responsibilities. That is
acceptable with one implementation, but it is a future ISP problem.

**Scope**

- Introduce `ChatModelEngine` and `EmbeddingEngine`
- Preserve backward compatibility by keeping `ModelEngine` as a composed type
  during the migration period
- Update the Ollama engine and adjacent code with minimal behavior change

**Out of scope**

- Changing engine behavior
- Provider discovery redesign
- New model backends

**Main files**

- `src/main/java/dev/talos/spi/ModelEngine.java`
- new SPI interface files
- `src/main/java/dev/talos/engine/ollama/OllamaEngine.java`
- any immediate callers that require typing updates

**Risks**

- Medium: import and type churn

**Acceptance criteria**

- Existing behavior unchanged
- The type split compiles cleanly
- No new regressions relative to the current baseline in relevant engine tests

**Rollback plan**

- Revert the interface split

**Dependencies**

- Prefer after `CCR-002`

---

### CCR-008 — Consolidate `core.spi` / `core.engine` into clearer SPI packages

**Status**

- Done on `ticket/CCR-008-spi-package-consolidation`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

The current SPI boundary is split awkwardly between `dev.talos.spi`,
`dev.talos.core.spi`, and `dev.talos.core.engine`.

**Scope**

- Move `CorpusStore` and `Embeddings` into clearer SPI-oriented packages
- Move `EngineRegistry` out of `core.engine` into the SPI area
- Keep this ticket as import/package churn only

**Out of scope**

- Logic changes
- Refactoring `LlmClient` behavior
- Tooling changes

**Main files**

- `src/main/java/dev/talos/core/spi/CorpusStore.java`
- `src/main/java/dev/talos/core/spi/Embeddings.java`
- `src/main/java/dev/talos/core/engine/EngineRegistry.java`
- all import call sites

**Risks**

- Medium: broad import churn

**Acceptance criteria**

- No logic changes in the PR
- Package layout is clearer and internally consistent
- No new regressions relative to the current baseline

**Rollback plan**

- Revert the package move

**Dependencies**

- Best after `CCR-007`

---

### CCR-009 — Split `OllamaEngine` into chat, embed, and health components

**Status**

- Done on `ticket/CCR-009-split-ollama-engine`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

`OllamaEngine` is carrying multiple concerns and is a good candidate for
internal extraction after the async-close changes settle.

**Scope**

- Extract chat/streaming logic into an `OllamaChatClient`
- Extract embedding logic into an `OllamaEmbedClient`
- Extract health/capability probing into an `OllamaHealthProbe`
- Preserve public behavior

**Out of scope**

- New backend support
- API redesign
- Changing request semantics

**Main files**

- `src/main/java/dev/talos/engine/ollama/OllamaEngine.java`
- new helper classes under `engine/ollama`

**Risks**

- Medium to high: streaming and cancel behavior is delicate

**Acceptance criteria**

- Existing Ollama behavior unchanged
- No new regressions relative to the current baseline in Ollama-related tests
- No regression in streaming close/cancel semantics

**Rollback plan**

- Revert extraction

**Dependencies**

- Must follow stabilization of the async-close work

---

### CCR-010 — Extract `ToolCallLoop` stages into a dedicated runtime subpackage

**Status**

- Done on `ticket/CCR-010-toolcallloop-stages`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

`ToolCallLoop` is one of the largest and most behavior-dense files in the
project. The code would benefit from stage-based decomposition similar to the
retrieval pipeline.

**Scope**

- Introduce `runtime/toolcall/` stage classes
- Split parsing, approval, execution, and reinjection responsibilities
- Preserve existing loop behavior and guardrails

**Out of scope**

- Prompt changes
- Tool behavior changes
- Approval policy changes

**Main files**

- `src/main/java/dev/talos/runtime/ToolCallLoop.java`
- new files under `src/main/java/dev/talos/runtime/toolcall/`

**Risks**

- High: this file encodes many subtle recovery heuristics

**Acceptance criteria**

- No new regressions relative to the current baseline in `ToolCallLoopTest*` suites
- No user-visible behavior regression
- Resulting code is structurally clearer than the original

**Rollback plan**

- Revert extraction

**Dependencies**

- Prefer after `CCR-009`

---

### CCR-011 — Decompose `LlmClient` into smaller collaborators

**Why this exists**

`LlmClient` is the highest-value structural cleanup target, but also the
highest-risk one. It should be addressed only after the lower-risk seams are
in place.

**Scope**

- Extract stream watchdog logic
- Extract retry/backoff logic
- Finalize the injectable engine-resolution seam
- Preserve placeholder/test behavior intentionally

**Out of scope**

- Transport rewrite
- Backend feature changes
- Changing high-level mode behavior

**Main files**

- `src/main/java/dev/talos/core/llm/LlmClient.java`
- new helper classes under `src/main/java/dev/talos/core/llm/`

**Risks**

- High: central runtime dependency with wide blast radius

**Acceptance criteria**

- Existing behavior unchanged
- No new regressions relative to the current baseline
- Responsibilities are materially clearer than before

**Rollback plan**

- Revert decomposition

**Dependencies**

- After `CCR-002`, `CCR-007`, and async-close stabilization

---

### CCR-012.1 — Instrument and observe XML compatibility fallback usage

**Why this exists**

The XML tool-call compatibility path is explicitly marked as deprecated legacy
behavior. Before any deletion decision, the project needs explicit evidence for
whether the fallback path is still used.

**Scope**

- Define the parity metric for real XML fallback usage
- Add the minimum instrumentation or observability needed to measure it
- Record the agreed observation window and success threshold for retirement

**Out of scope**

- Any XML compatibility deletion
- Tool-call protocol redesign

**Main files**

- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/core/util/Sanitize.java`
- `docs/new-architecture/25-xml-retirement-review.md`

**Risks**

- Medium: easy to collect the wrong metric or define an unusable retirement bar

**Acceptance criteria**

- There is an explicit, documented metric for XML fallback usage
- The observation window and retirement threshold are documented
- The repo has a concrete way to collect or review that signal

**Rollback plan**

- Revert instrumentation/docs change

**Dependencies**

- Last-stage cleanup only

---

### CCR-012.2 — Retire XML compatibility path if parity evidence justifies it

**Why this exists**

The XML compatibility path should be deleted only after `CCR-012.1` establishes
the metric and the agreed observation window shows that the fallback is no
longer needed.

**Scope**

- Review the metric collected in `CCR-012.1`
- Remove XML compatibility code only if the agreed retirement threshold is met
- Update docs/tests to reflect the deletion

**Out of scope**

- Removing XML compatibility without explicit evidence
- Tool-call protocol redesign
- Replacing the XML path with a new compatibility layer

**Main files**

- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/core/util/Sanitize.java`
- `docs/new-architecture/25-xml-retirement-review.md`

**Risks**

- High if the evidence is misread or the deletion happens too early

**Acceptance criteria**

- Deletion is backed by explicit parity evidence from `CCR-012.1`
- No remaining live XML-dependent path is broken
- No new regressions relative to the current baseline in relevant tool-call tests

**Rollback plan**

- Restore XML compatibility path

**Dependencies**

- After `CCR-012.1`

---

### CCR-013 — Final naming cleanup pass

**Why this exists**

Some naming collisions are not harmful to runtime behavior but impose ongoing
review and onboarding cost.

**Scope**

- Rename `cli.cmds` to a clearer package
- Rename `cli.commands` to a clearer package
- Rename `PromptRouter` to `PromptClassifier`
- Keep this a mechanical refactor only

**Out of scope**

- Behavior changes
- Logic refactors hidden inside rename commits

**Main files**

- `src/main/java/dev/talos/cli/cmds/`
- `src/main/java/dev/talos/cli/commands/`
- `src/main/java/dev/talos/cli/modes/PromptRouter.java`
- affected imports/tests/docs

**Risks**

- Medium: large rename diff can hide accidental changes

**Acceptance criteria**

- Mechanical rename only
- Project compiles
- No new regressions relative to the current baseline
- Names are clearer than before

**Rollback plan**

- Revert the rename commit

**Dependencies**

- Last

---

## 5. Suggested Milestones

### Milestone A — Safe prep

- `CCR-001`
- `CCR-002`
- `CCR-003`
- `CCR-004`

### Milestone B — Surface cleanup

- `CCR-005`
- `CCR-006`
- `CCR-007`
- `CCR-008`

### Milestone C — Internal decomposition

- `CCR-009`
- `CCR-010`
- `CCR-011`

### Milestone D — Late cleanup

- `CCR-012.1`
- `CCR-012.2`
- `CCR-013`

---

## 6. Copy-Paste Short Titles

If you need tracker-ready titles only:

- `CCR-001 Fix stale dev.loqj package references in project instructions`
- `CCR-002 Decouple failing tests from real engine resolution with the correct seam per test layer`
- `CCR-003 Add exploded-classes version source for BuildInfo`
- `CCR-004 Remove deprecated FirstRunWizard class`
- `CCR-005 Make explicit WebMode keep/remove product decision`
- `CCR-006 Migrate TalosTool from legacy no-context execution to context-aware execution`
- `CCR-007 Split ModelEngine into chat and embedding interfaces`
- `CCR-008 Consolidate SPI and engine package boundaries`
- `CCR-009 Split OllamaEngine into focused internal components`
- `CCR-010 Extract ToolCallLoop stage pipeline`
- `CCR-011 Decompose LlmClient into smaller collaborators`
- `CCR-012.1 Instrument and observe XML compatibility fallback usage`
- `CCR-012.2 Retire XML compatibility path if parity evidence justifies it`
- `CCR-013 Run final naming cleanup pass for CLI packages and PromptRouter`
