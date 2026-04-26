# [done] Ticket: Minimal Runtime TaskOutcome

Date: 2026-04-25
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/new-architecture/talos-harness-plan.md`
- `docs/new-architecture/29-v1-scenario-pack.md`

Depends on / follows:
- `work-cycle-docs/tickets/talos-execution-outcome-centralization.md`
- `work-cycle-docs/tickets/talos-minimal-execution-phase-policy.md`
- `work-cycle-docs/tickets/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/talos-minimal-task-contract.md`

## Why This Ticket Exists

Talos now has the core pieces that make a real outcome model possible:

- deterministic `TaskContract`
- minimal `ExecutionPhase` policy
- structured tool outcomes from `ToolCallLoop`
- static post-apply verification
- centralized answer shaping through `ExecutionOutcome`

But `ExecutionOutcome` is still a bridge object inside `dev.talos.cli.modes`.
It stores booleans such as `deniedMutation`, `partialMutation`,
`falseMutationClaim`, and `inspectUnderCompleted`, while the actual truth
rules still live as helper methods on `AssistantTurnExecutor`.

That is better than scattered final-answer patches, but it is not yet the
architecture described in `new-work.md`:

```text
TaskOutcome:
  contract
  tool outcomes
  mutation outcome
  verification outcome
  completion status
  warnings
```

## Problem

Talos can classify and shape outcomes, but the current state is still partly
patch-shaped:

- final-answer truth reasons are represented as separate booleans
- mutation result state is inferred repeatedly from `ToolOutcome`
- verification detail is reduced to a local enum instead of carried as a result
- warnings are not first-class values
- future failure policy would have to inspect several local flags and helpers

The next failure/reset policy should depend on structured outcome state, not on
another round of string annotations or executor-local conditionals.

## Goal

Introduce a minimal `TaskOutcome` model that centralizes the current outcome
facts without changing product behavior.

This should be mostly a structural refactor plus regression tests. The first
slice should preserve existing final-answer text and scenario behavior while
making the outcome internally explainable.

## Scope

### In scope

- Add a small runtime outcome package.
- Represent completion status as a first-class runtime concept.
- Represent mutation outcome as structured state.
- Represent truth/grounding/verification warnings as first-class values.
- Carry `TaskContract`, tool outcomes, and `TaskVerificationResult` together.
- Let `ExecutionOutcome` become a CLI-facing adapter over `TaskOutcome`, or
  gradually replace it if the change stays small.
- Add focused tests proving the structured status for denied, partial, failed
  verification, passed verification, advisory no-tool, and blocked no-tool
  turns.

### Out of scope

- Broad final-answer rewrite.
- New planner or semantic task verifier.
- Shell/browser/test-runner execution.
- MCP server logic.
- CLI phase/outcome trace display.
- Failure/reset policy implementation. This ticket prepares for it.

## Proposed Work

### 1. Add minimal outcome types

Likely package:

```text
src/main/java/dev/talos/runtime/outcome/
```

Likely classes:

```text
TaskOutcome
TaskCompletionStatus
MutationOutcome
MutationOutcomeStatus
TruthWarning
TruthWarningType
```

Keep the model small. Avoid a broad event system.

### 2. Build from current facts

Use existing sources:

- `TaskContract`
- `ToolCallLoop.LoopResult`
- `ToolCallLoop.ToolOutcome`
- `TaskVerificationResult`
- current executor truth checks

Do not parse human prose to recover structured facts.

### 3. Keep `ExecutionOutcome` as adapter if useful

`ExecutionOutcome` may remain package-private in `cli.modes`, but it should
wrap or expose a `TaskOutcome` rather than duplicating the central state.

Target direction:

```text
ExecutionOutcome.fromToolLoop(...)
  -> build TaskOutcome
  -> render current answer annotations from TaskOutcome
```

The final user-visible text should stay stable unless the existing text is
incorrect.

### 4. Make warnings inspectable

Current booleans should become warning entries where appropriate:

- denied mutation
- partial mutation
- false mutation claim
- inspect-under-completion
- selector grounded override
- streaming no-tool mutation replacement
- streaming no-tool ungrounded answer
- static verification failed/incomplete

This gives the next failure-policy ticket a single place to reason about
completion and risk.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/outcome/`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/outcome/`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/resources/scenarios/` only if a behavior edge needs coverage

## Test / Verification Plan

Focused unit tests:

- denied mutation -> `BLOCKED`, mutation status `DENIED`, warning present
- partial mutation -> `PARTIAL`, mutation status `PARTIAL`, success/failure
  paths preserved
- failed static verification -> `FAILED`, verification result carried
- passed static verification -> `COMPLETED_VERIFIED` or equivalent complete
  status with passed verification carried
- streaming no-tool mutation narrative -> blocked warning
- streaming no-tool fabricated evidence answer -> advisory/ungrounded warning

Regression checks:

```powershell
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat test --tests "dev.talos.runtime.outcome.*"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Then widen:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Manual installed Talos verification is required before marking done:

- uninstall current Talos
- build `installDist`
- install Talos
- clear the verified horror-synth session
- run the standard prompt sequence against `local/playground/horror-synth-site`
- review `local/manual-testing/test-output`
- confirm read-only, approval denial, final-answer truthfulness, and no raw
  tool JSON regressions

## Acceptance Criteria

- current outcome facts are represented by a structured `TaskOutcome`
- `TaskOutcome` carries the `TaskContract`
- mutation status is structured, not only inferred from booleans
- verification result is carried structurally
- warnings are first-class and inspectable
- existing scenario behavior remains stable
- no new framework dependency or runtime capability is added
- installed CLI manual verification is reviewed before marking done

## Completion Notes

Implemented the first structured runtime outcome slice:

- `TaskOutcome`
- `TaskCompletionStatus`
- `MutationOutcome`
- `MutationOutcomeStatus`
- `TruthWarning`
- `TruthWarningType`

`ExecutionOutcome` now carries a `TaskOutcome` while preserving the existing
CLI-facing answer text and status adapter. The structured outcome carries the
resolved `TaskContract`, mutation status/details, static verification result,
truth warnings, and per-tool outcomes.

This is intentionally not a failure-policy implementation and not a broad final
answer rewrite. It prepares the runtime for the next failure/reset discipline
slice.

## Verification Evidence

Focused checks passed:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.outcome.MutationOutcomeTest"
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Wide checks passed:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos manual verification was run after uninstall/install against
`local/playground/horror-synth-site`. The standard prompt flow confirmed:

- clean session start
- read-only inspection stayed read-only
- selector grounding corrected unsupported model prose
- explicit edit reached approval
- denial prevented writes and stopped cleanly
- tracked playground files stayed unchanged
- no raw `"name"` / `"arguments"` tool-call JSON object appeared in the
  transcript

Residual display debt remains separate: the live stream still showed an empty
```json fence before the tool loop entered execution. That is protocol-display
polish, not a TaskOutcome behavior failure.
