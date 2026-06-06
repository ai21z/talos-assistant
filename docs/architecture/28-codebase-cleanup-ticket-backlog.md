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
- Rule: use the umbrella branch as the father integration branch for this cleanup stream
- Rule: each ticket should land as its own PR from a dedicated ticket branch back
  into `chore/codebase-cleanup-refactor`
- Rule: ticket branches may be cut directly from `v0.9.0-beta-dev` or from the
  umbrella branch, but each PR must contain only one ticket's changes
- Rule: the father branch is merged back into `v0.9.0-beta-dev` only after the
  intended cleanup ticket set is complete
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

1. `CCR-001` doc drift fix in `.github/assistant-instructions.md` `[done]`
2. `CCR-002` decouple failing tests from real engine resolution with the correct seam per test layer `[done]`
3. `CCR-003` `BuildInfo` exploded-classes version source `[done]`
4. `CCR-004` delete `FirstRunWizard` class only `[done]`
5. `CCR-005` decide `WebMode`: keep reserved or retire intentionally `[done]`
6. `CCR-006` migrate `TalosTool` from legacy no-context execution to context-aware execution `[done]`
7. `CCR-007` split `ModelEngine` into chat/embed interfaces `[done]`
8. `CCR-008` SPI package consolidation `[done]`
9. `CCR-009` split `OllamaEngine` `[done]`
10. `CCR-010` extract `ToolCallLoop` stages `[done]`
11. `CCR-011` decompose `LlmClient` `[done]`
12. `CCR-012.1` instrument and observe XML compatibility fallback usage `[done]`
13. `CCR-012.2` retire XML compatibility path if parity evidence justifies it
14. `CCR-013` naming cleanup pass (`cmds` / `commands` / `PromptClassifier`) `[done]`
15. `CCR-014` resolve ignored-architecture-doc ownership after cleanup renames `[done]`
16. `CCR-015` final terminology and stale-reference alignment after XML/naming cleanup `[done]`
17. `CCR-016` decide explicit approval and session default policy before harness work
18. `CCR-017` add focused unit coverage for extracted `core.llm` collaborators
19. `CCR-018` review XML telemetry gate and decide the next `CCR-012.2` action
20. `CCR-019` gate conversation-history prune on compaction success (data-loss fix)
21. `CCR-020` re-prompt on partial mutation failures (workspace-integrity fix)

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

### CCR-001 - Fix stale pre-Talos package references in project instructions

**Status**

- Done on `ticket/CCR-001-doc-drift-fix`
- Implementation commit: `53d5d61`
- Merge commit: `a46c49f`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

`.github/assistant-instructions.md` still describes package paths from the
pre-Talos codebase, while the active codebase is `dev.talos.*`. This creates
avoidable confusion for humans and AI assistants.

**Scope**

- Replace stale package references with `dev.talos.*`
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
- Implementation commit: `f5bd080`
- Merge commit: `4b4887f`
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
- Implementation commit: `c4fe974`
- Merge commit: `bc1d138`
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
- Implementation commit: `6c0766b`
- Merge commit: `f666d4f`
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
- Implementation commit: `2a72217`
- Merge commit: `6a87823`
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
- Implementation commit: `4a82635`
- Merge commit: `1004aa0`
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
- Implementation commit: `07b8e97`
- Merge commit: `46bafe3`
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
- Implementation commits: `cda83cb`, `44b5a06`
- Merge commit: `3c08a3b`
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
- Implementation commit: `62efbc0`
- Merge commit: `69ee985`
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
- Implementation commit: `7559b63`
- Merge commit: `b4d3563`
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

**Status**

- Done on `ticket/CCR-011-decompose-llmclient`
- Implementation commit: `3aadb89`
- Merge commit: `328c6f0`
- Merged into `chore/codebase-cleanup-refactor`

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

**Status**

- Done on `ticket/CCR-012-1-xml-fallback-observability`
- Implementation commit: `2869ed3`
- Merge commit: `6e8b8fd`
- Merged into `chore/codebase-cleanup-refactor`

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
- `docs/architecture/25-xml-retirement-review.md`

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
- `docs/architecture/25-xml-retirement-review.md`

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

**Status**

- Done on `ticket/CCR-013-naming-cleanup`
- Implementation commit: `cda605b`
- Merge commit: `dffc0db`
- Merged into `chore/codebase-cleanup-refactor`

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

### CCR-014 — Resolve ignored architecture-doc ownership after cleanup renames

**Status**

- Done on `ticket/CCR-014-doc-ownership-policy`
- Implementation commit: `1fcdc05`
- Merge commit: `dd904bb`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

`CCR-013` correctly renamed package/class references, but it also force-added
architecture docs that the repo currently ignores via `/docs` rules in both
`.gitignore` and `.git/info/exclude`. That creates an ownership/policy mismatch:
either these docs are intentionally part of the tracked cleanup backlog surface,
or they should remain ignored and be removed from the index again.

This should be handled as an explicit repo-hygiene decision, not left as an
accidental side effect of a mechanical rename ticket.

**Scope**

- Decide whether the tracked `docs/architecture/` architecture/planning
  set should be treated as intentional repo content or as local-only ignored docs
- If they should remain ignored:
  untrack the tracked `docs/architecture/` files from git while preserving
  local files, and define whether any exception (such as the active cleanup
  backlog) should remain tracked
- If they should become tracked:
  update repo-level ignore policy explicitly and document the new ownership
  expectation for architecture/planning docs
- Ensure the resulting state is internally consistent between git tracking and
  ignore rules

**Out of scope**

- Rewriting the architecture docs themselves
- Broad documentation restructuring
- Any production code changes

**Main files**

- `.gitignore`
- tracked `docs/architecture/*` files that are part of the ownership decision

**Risks**

- Medium: easy to produce a confusing half-state where docs are both tracked
  and ignored, or to accidentally drop a doc that the cleanup process now relies on

**Acceptance criteria**

- The ownership of the tracked `docs/architecture/` docs is explicit and consistent
- The repo no longer contains a repo-level mismatch between ignore policy and tracked architecture/planning docs
- No production code files change
- The cleanup branch’s documentation surface is easier to reason about than before

**Rollback plan**

- Revert the repo-hygiene decision commit

**Dependencies**

- After `CCR-013`

---

### CCR-015 — Final terminology and stale-reference alignment after XML/naming cleanup

**Status**

- Done on `ticket/CCR-015-stale-reference-alignment`
- Implementation commit: `38f4488`
- Merge commit: `b12c70f`
- Merged into `chore/codebase-cleanup-refactor`

**Why this exists**

The cleanup branch is structurally in much better shape, but a few comments,
javadocs, and high-signal instruction docs still describe the pre-cleanup
behavior. Those stale descriptions are small, but they undermine the value of
the refactor by teaching the wrong model to future readers.

Two concrete tracked-code examples were already identified, plus one ignored
local-only maintainer doc surface:

- `ToolCallParser.parse(...)` javadoc still says XML is checked first, while the
  implementation now checks JSON first and XML last
- `TalosBootstrap` still comments on suppressing `<tool_call>` XML only, even
  though the stream filter now also handles JSON-fence fallback semantics
- `.github/CARRY_OVER_PROMPT.md` still described `cli.cmds`, `cli.commands`,
  `PromptRouter`, `FirstRunWizard`, and XML-first tool-call flow, but that file
  is ignored/local-only and was reviewed separately rather than force-tracked

This should remain a narrow terminology/alignment pass only.

**Scope**

- Fix stale comments, javadocs, and high-signal instruction-doc references
  introduced or exposed by the cleanup work
- Restrict the pass to already-identified cleanup-touched files and directly
  adjacent stale references
- Align in-code descriptions with the current XML compatibility posture
- Align in-code descriptions with the `PromptClassifier` / `cli.launcher` /
  `cli.repl.slash` naming that now exists
- Review high-signal maintainer instructions for the same post-cleanup naming
  and XML-compatibility posture without changing ignored local-only files'
  tracking state
- Keep behavior unchanged

**Out of scope**

- XML compatibility deletion
- Runtime logic changes
- Additional refactors hidden behind comment edits

**Main files**

- `src/main/java/dev/talos/runtime/ToolCallParser.java`
- `src/main/java/dev/talos/runtime/ToolCallStreamFilter.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- local-only `.github/CARRY_OVER_PROMPT.md` review surface (not force-tracked)
- Any nearby file whose comments still mention removed names or pre-cleanup behavior

**Risks**

- Low: the main risk is missing a stale comment and thinking the pass is complete

**Acceptance criteria**

- No identified stale references remain in the touched cleanup surfaces
- Javadocs/comments/instruction docs describe the current implementation accurately
- No production behavior changes
- Focused affected tests still pass
- Ignored local-only maintainer docs are not force-tracked as a side effect of
  this ticket

**Rollback plan**

- Revert the terminology/alignment commit

**Dependencies**

- After `CCR-013`

---

### CCR-016 — Decide explicit approval and session default policy before harness work

**Status**

- In progress on `ticket/CCR-016-explicit-policy-defaults`
- Decision: keep `NoOpApprovalGate` / `NoOpSessionStore` as the named
  test/ad-hoc defaults, but remove silent null-to-NoOp substitution from
  the primary `Session` and `TurnProcessor` constructors. The shipped REPL
  wires `CliApprovalGate` and `JsonSessionStore` explicitly at the
  composition root (`TalosBootstrap`), so production does not rely on
  policy-by-null.
- Convenience constructors (2-/3-arg `Session`, 1-/2-/3-arg
  `TurnProcessor`) continue to pass explicit `NoOp*` values for tests and
  ad-hoc call sites — explicit wiring, not policy-by-null.
- `Context.Builder` now receives an explicit `.approvalGate(approvalGate)`
  from `TalosBootstrap`; its `build()` fallback to `NoOpApprovalGate` is
  retained as a documented, test-only default and no longer a production
  surface.
- Runtime tests updated to assert the strict-null contract
  (`SessionTest`, `SessionStoreTest`).

**Why this exists**

The cleanup stream intentionally left `NoOpApprovalGate` and
`NoOpSessionStore` policy untouched, but the current defaults are still a
meaningful architectural question before harness work starts.

Today the runtime silently falls back to approve-everything or
persist-nothing defaults in important seams:

- `TurnProcessor` defaults to `NoOpApprovalGate`
- `Context.Builder` defaults to `NoOpApprovalGate`
- `Session` defaults to `NoOpSessionStore`

At the same time, the main REPL composition root now wires a persistent
`JsonSessionStore` explicitly. That means the remaining ambiguity is not
"what the shipped REPL does today", but whether constructor- and builder-level
null fallbacks should remain an implicit policy surface before harness work.

That may be acceptable as a deliberate product policy, but it should not
remain an implicit behavior if the next stream is going to strengthen harness,
approval, or trust semantics.

**Scope**

- Make an explicit product decision about approval/session default policy
- Remove policy-by-null from the affected constructor/builder seams where that
  can be done without changing shipped behavior
- Wire the current intended defaults explicitly at the composition root where
  needed, and document that choice in code/docs
- Keep the ticket focused on decision + explicit wiring, not on broader
  approval/session UX

**Out of scope**

- Full approval UX redesign
- Any user-visible approval or persistence behavior change
- Swapping the composition-root defaults to a non-`NoOp*` implementation
- Harness phase model work
- Session persistence feature expansion

**Main files**

- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/cli/repl/Context.java`
- `src/main/java/dev/talos/runtime/Session.java`
- adjacent approval/session docs if the chosen policy needs documenting

**Risks**

- Medium: changing defaults can alter user-visible behavior if the decision is
  not made carefully

**Acceptance criteria**

- The approval/session default behavior is an explicit product decision
- The code no longer relies on ambiguous policy-by-null for these seams
- Shipped behavior remains unchanged after the ticket lands
- Relevant runtime tests still pass
- The result is easier to reason about before harness work begins

**Rollback plan**

- Revert the policy/defaults ticket

**Dependencies**

- After the cleanup stream merge

---

### CCR-017 — Add focused unit coverage for extracted `core.llm` collaborators

**Why this exists**

The cleanup stream extracted `LlmEngineResolver`, `RegistryLlmEngineResolver`,
`LlmCallBudget`, and `LlmRetryExecutor`, which improved structure but left the
new collaboration seams with thinner direct unit coverage than the rest of the
runtime.

That is not a correctness defect by itself, but these helpers sit in one of
the most central runtime packages and are likely to be exercised heavily by the
next harness stream.

**Scope**

- Add direct unit tests for:
  `LlmEngineResolver`, `RegistryLlmEngineResolver`, `LlmCallBudget`,
  and `LlmRetryExecutor`
- Keep the pass narrowly focused on collaborator behavior and edge cases
- Avoid changing `LlmClient` behavior unless a testability seam is strictly required

**Out of scope**

- Another `LlmClient` refactor
- Coverage-chasing outside the extracted collaborators
- Broad test-harness work

**Main files**

- `src/main/java/dev/talos/core/llm/LlmEngineResolver.java`
- `src/main/java/dev/talos/core/llm/RegistryLlmEngineResolver.java`
- `src/main/java/dev/talos/core/llm/LlmCallBudget.java`
- `src/main/java/dev/talos/core/llm/LlmRetryExecutor.java`
- new or updated tests under `src/test/java/dev/talos/core/llm/`

**Risks**

- Low: the main risk is adding shallow tests that restate the implementation
  without meaningfully protecting behavior

**Acceptance criteria**

- Each extracted collaborator has direct unit coverage for its main behavior
- Edge cases that matter for harness/runtime correctness are covered
- No production behavior changes are introduced by the test pass
- `core.llm` package coverage improves from the current cleanup baseline

**Rollback plan**

- Revert the test-only ticket

**Dependencies**

- After the cleanup stream merge

---

### CCR-018 — Review XML telemetry gate and decide the next `CCR-012.2` action

**Why this exists**

`CCR-012.1` added the XML compatibility telemetry and documented an
observation-window gate. `CCR-012.2` is still intentionally open, so the next
step should be an explicit review ticket rather than an assumption that XML can
or cannot be retired.

This keeps the decision evidence-based and prevents the compatibility path from
remaining in a permanent "we will decide later" state.

**Scope**

- Review the available XML compatibility telemetry after the next agreed
  observation window
- Decide one of two outcomes:
  advance to `CCR-012.2` retirement work, or record the next review gate/date
- Update the relevant retirement docs/backlog state to reflect that decision
- Keep this ticket strictly review-only; if retirement is justified, the output
  is "proceed with `CCR-012.2`", not the retirement implementation itself

**Out of scope**

- Implementing `CCR-012.2`
- Unconditional XML compatibility deletion
- Tool-call protocol redesign
- Broad prompt/runtime changes unrelated to the telemetry decision

**Main files**

- `docs/architecture/25-xml-retirement-review.md`
- `docs/architecture/28-codebase-cleanup-ticket-backlog.md`
- telemetry review surfaces such as `/status --verbose` output or other agreed
  local observation notes

**Risks**

- Medium: the main risk is making a deletion/no-deletion decision from
  insufficient observation evidence

**Acceptance criteria**

- The XML retirement gate is reviewed against the agreed observation criteria
- The repo records an explicit next action:
  either proceed to `CCR-012.2` or document the next review gate
- No unsupported assumption is made about XML retirement readiness

**Rollback plan**

- Revert the review-doc update if the decision was recorded incorrectly

**Dependencies**

- After the next XML telemetry observation window defined by `CCR-012.1`

---

### CCR-019 — Gate conversation-history prune on compaction success (data-loss fix)

**Status**

- Safety-core slice implemented in current tree as `T709a`; broader `T709`
  remains open for integrity/redaction/trace hardening.
- High-confidence bug confirmed from the manual-testing transcript
  (`manual-testing/test-output:53–55`): compaction LLM call failed but
  history was still pruned, losing turns.

**Why this exists**

`ConversationCompactor.compact(...)` returned the existing sketch on
failure — indistinguishable from a successful no-op. Callers could not
tell success from failure from the return value alone, so
`ConversationManager.maybeCompactWithBudget(...)` unconditionally called
`memory.pruneOldest(...)` after every compaction attempt. A failed
compaction therefore destroyed verbatim history without producing any
replacement summary.

This was observed during a manual test pass: `Model not found:
qwen3:8b` triggered an engine failure that immediately cascaded into a
compaction attempt which also failed, yet history was pruned anyway.

**Scope**

- Introduce an explicit success/failure signal in the compactor API
  (`CompactionResult { sketch, succeeded }`).
- Gate `memory.pruneOldest(...)` in `ConversationManager` on
  `succeeded == true` so failed compactions preserve all verbatim turns
  and the prior sketch.
- Keep the legacy `compact(...)` String-returning method as a thin
  wrapper so existing call sites and tests don't break, but forbid its
  use for gating destructive actions.
- Add a package-private functional seam
  (`ConversationManager.maybeCompactWith(BiFunction, int, double)`) so
  the failure-preservation contract can be unit-tested deterministically
  without mocking `LlmClient`.

**Out of scope**

- Compaction prompt tuning
- Compaction trigger thresholds or budget fractions
- Cross-turn memory persistence
- T709b work: tool/evidence-pair preservation, deterministic summary
  integrity/redaction checks, and trace/debug compaction reporting

**Main files**

- `src/main/java/dev/talos/core/context/ConversationCompactor.java`
- `src/main/java/dev/talos/core/context/ConversationManager.java`
- `src/test/java/dev/talos/core/context/ConversationCompactionTest.java`

**Risks**

- Low. The fix only adds a success gate; it does not change the happy
  path. On failure, behavior strictly improves (no silent data loss).

**Acceptance criteria**

- Failed compaction LLM call (thrown or blank output) returns
  `succeeded=false` from `tryCompact(...)`.
- `ConversationManager` does not call `memory.pruneOldest(...)` when
  `succeeded=false`.
- Sketch is preserved unchanged on failure.
- Unit tests cover: thrown LLM, blank output, empty turns, and
  successful compaction prune path.
- Three consecutive failures trip a session-local breaker until a successful
  compaction or `ConversationManager.clear()` resets it.
- Full test suite still green.

**Rollback plan**

- Revert the `tryCompact` seam and restore the previous unconditional
  prune. Not recommended — the previous behavior is the bug.

**Dependencies**

- None (post-`CCR-015`, independent of the other pre-harness follow-ups)

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

### Milestone E — Post-Cleanup Alignment

- `CCR-014`
- `CCR-015`

### Milestone F — Pre-Harness Follow-Ups

- `CCR-016`
- `CCR-017`
- `CCR-018`
- `CCR-019`

---

## 6. Copy-Paste Short Titles

If you need tracker-ready titles only:

- `CCR-001 Fix stale pre-Talos package references in project instructions`
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
- `CCR-013 Run final naming cleanup pass for CLI packages and PromptClassifier`
- `CCR-014 Resolve ignored architecture-doc ownership after cleanup renames`
- `CCR-015 Final terminology and stale-reference alignment after XML/naming cleanup`
- `CCR-016 Decide explicit approval and session default policy before harness work`
- `CCR-017 Add focused unit coverage for extracted core.llm collaborators`
- `CCR-018 Review XML telemetry gate and decide the next CCR-012.2 action`
- `CCR-019 Gate conversation-history prune on compaction success (data-loss fix)`

---

## 7. Post-Cleanup Verification (after `CCR-015`)

This section records the current state of the cleanup stream after the
completed tickets `CCR-001` through `CCR-015`. It is intentionally limited to
claims that are verifiable from the current tree, git history, harness docs,
and generated coverage/test artifacts.

### 7.1 Structural verification

- Zero leftover references remain in `src/main/java` for:
  `core.spi`, `core.engine.EngineRegistry`, `cli.cmds`, `cli.commands`,
  and `PromptRouter`
- `cli.launcher` contains 9 files (picocli entry/launcher commands)
- `cli.repl.slash` contains 29 files (REPL slash commands)
- `dev.talos.spi` now carries:
  `ChatModelEngine`, `EmbeddingEngine`, `ModelEngine`,
  `CorpusStore`, `Embeddings`, `EngineRegistry`, `ModelCatalog`,
  `ModelEngineProvider`, `EngineException`, and `types/*`
- `src/main/java/dev/talos/core/spi` and
  `src/main/java/dev/talos/core/engine` are gone
- `FirstRunWizard` is deleted; `TerminalFirstRun` now owns first-run behavior
- JavaFX dependencies remain in Gradle and were correctly not bundled into
  `CCR-004`
- `WebMode` remains a reserved stub and is aligned across:
  `WebMode.java`, `ModeController`, `/mode` help, and `README.md`
- XML compatibility is instrumented through `runtime/XmlCompatTelemetry.java`
  and remains correctly deferred to `CCR-012.2`

### 7.2 Harness seam status against source-of-truth

`docs/architecture/talos-harness-source-of-truth.md` identifies the
critical runtime seams for harness work as:

- `AssistantTurnExecutor`
- `ToolCallLoop`
- `TurnProcessor`
- `ConversationManager`
- `ToolRegistry` + `ToolDescriptor`
- `ContentVerifier`
- bootstrap wiring

Current status of those seams after cleanup:

| Seam | Current state | Verification |
|---|---|---|
| `AssistantTurnExecutor` | Preserved as a static utility | 923 LOC; unchanged in shape |
| `ToolCallLoop` | Decomposed into stage helpers | 180 LOC main class; `runtime/toolcall/` extracted |
| `TurnProcessor` | Preserved | 363 LOC |
| `ConversationManager` | Preserved | 295 LOC |
| `ToolRegistry` + `TalosTool` | Context-aware execution is primary | legacy no-context path removed |
| `ContentVerifier` | Preserved | 200 LOC |
| `TalosBootstrap` | Preserved as composition root | 406 LOC |

Evidence-backed conclusion:

- the cleanup preserved every seam named by the harness source-of-truth
- no named harness seam was deleted or made structurally unusable
- `ToolCallLoop` and `ToolRegistry` / `TalosTool` are in materially cleaner
  shape than before
- the cleanup did not attempt to start the harness stream itself; it only
  prepared or preserved the relevant seams

### 7.3 What cleanup intentionally did not do

These items remain outside the cleanup scope by design:

- `AssistantTurnExecutor` is still the largest file in the tree at 923 LOC;
  it was intentionally not reshaped in this stream
- `LlmClient` remains 778 LOC; collaborators were extracted, but the remaining
  bulk is still central runtime logic rather than a correctness defect
- `NoOpApprovalGate` and `NoOpSessionStore` remain silent defaults in:
  `TurnProcessor`, `Context`, and `Session`
- `CCR-012.2` remains explicitly gated by XML telemetry evidence
- the harness docs still point at branch `feature/native-tool-pipeline` and
  will need branch/ownership realignment before harness work resumes
- Gradle 8.14 deprecation warnings remain an infra/build concern, not a cleanup
  ticket concern

### 7.4 Coverage and test baseline

Current generated artifacts show:

- instruction coverage overall: `71.55%`
- instruction coverage for `dev.talos.core.llm`: `64.60%`
- instruction coverage for `dev.talos.engine.ollama`: `54.33%`
- current test result baseline:
  `2346 tests`, `0 failures`, `0 errors`, `2 skipped`

Interpretation:

- the cleanup did not introduce a test regression
- `core.llm` and `engine.ollama` remain the thinner coverage areas most likely
  to benefit from additional unit tests as harness work begins

### 7.5 Standards verdict

The cleanup stream meets the intended standard for this branch:

- parity-before-deletion gates were respected
- completed work landed as 15 ticket branches with 15 merge commits into the
  father branch
- no CI / workflow / quality-tooling reconfiguration was introduced on this stream
- no framework rewrite or DI framework was introduced
- no MCP server implementation was added
- source-of-truth harness seams remain available and usable

### 7.6 Recommended ordered follow-ups

Before starting the harness stream, the strongest next candidates are:

1. Resolve the approval-default policy question around `NoOpApprovalGate`
   and `NoOpSessionStore`
2. Add unit coverage for the extracted `core.llm` collaborators
   (`LlmEngineResolver`, `LlmCallBudget`, `LlmRetryExecutor`)
3. Review `CCR-012.2` after the next XML telemetry observation window and
   either retire the compatibility path or record the next review gate
