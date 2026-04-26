# [done] Ticket: Minimal Runtime TaskContract

Date: 2026-04-25
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `docs/new-architecture/talos-harness-plan.md`
- `docs/new-architecture/29-v1-scenario-pack.md`
Depends on / should follow:
- `work-cycle-docs/tickets/talos-minimal-execution-phase-policy.md`
- `work-cycle-docs/tickets/talos-execution-outcome-centralization.md`
- `work-cycle-docs/tickets/talos-static-task-verifier.md`

## Why This Ticket Exists

Talos now has:
- a minimal execution phase policy
- centralized execution outcome shaping
- a narrow static post-apply verifier
- deterministic scenario coverage for those slices

But the runtime still derives task intent directly from raw user text in several
places.

Examples:
- `AssistantTurnExecutor` decides initial phase from mutation wording
- `ExecutionOutcome` computes mutation-requested status from latest user text
- `TurnProcessor` allows or denies mutating tools from `MutationIntent`
- `StaticTaskVerifier` decides whether selector coherence matters from the raw
  user request

Those checks are individually useful, but they are not yet a first-class task
contract.

## Problem

Without a central contract, Talos cannot explain the shape of the current turn
as structured runtime state.

That keeps later architecture work patch-shaped:
- phase selection is inferred locally
- mutation permission is inferred locally
- verification need is inferred locally
- target expectations are inferred locally or not at all

The current system is safer than before, but it still cannot say:

```text
TaskContract:
  type: FILE_EDIT
  mutationAllowed: true
  verificationRequired: true
  expectedTargets: [index.html]
```

## Goal

Add a minimal deterministic `TaskContract` model and route the existing
contract-adjacent decisions through it.

This should make Talos more disciplined and measurable without introducing an
LLM classifier, planner, or workflow engine.

## Scope

### In scope

- deterministic `TaskContract`
- simple `TaskType`
- simple contract derivation from latest user request
- mutation permission derived through the contract
- initial phase selection derived through the contract
- verification-needed decision derived through the contract
- target extraction for obvious file path mentions such as `index.html`
- focused unit tests and one or more JSON scenario regressions

### Out of scope

- LLM-based task classification
- planner or multi-step workflow decomposition
- shell/browser/test-runner verification
- MCP server concerns
- user-facing CLI phase trace
- broad semantic task completion guarantees

## Proposed Work

### 1. Add a small task package

Likely package:

```text
src/main/java/dev/talos/runtime/task/
```

Likely classes:

```text
TaskType
TaskContract
TaskContractResolver
```

Initial task types:

```text
READ_ONLY_QA
WORKSPACE_EXPLAIN
DIAGNOSE_ONLY
FILE_EDIT
FILE_CREATE
VERIFY_ONLY
UNKNOWN
```

Initial fields:

```text
type
mutationRequested
mutationAllowed
verificationRequired
expectedTargets
forbiddenTargets
originalUserRequest
```

Use path strings first. Do not introduce a heavy workspace snapshot or path
identity model in this ticket.

### 2. Reuse existing deterministic logic

Do not replace `MutationIntent` with a new looser classifier.

Instead:
- keep `MutationIntent.looksExplicitMutationRequest(...)` as the narrow lexical
  primitive
- wrap it in `TaskContractResolver`
- classify task type and verification need conservatively
- extract obvious target names from the current user request

### 3. Integrate into current runtime seams

Likely integration points:

- `AssistantTurnExecutor.initializeExecutionPhaseForTurn(...)`
  - use contract mutation allowance to pick `APPLY` vs `INSPECT`

- `ExecutionOutcome.fromToolLoop(...)` / `fromNoTool(...)`
  - use contract for `mutationRequested`
  - use contract to decide whether post-apply verification is expected

- `TurnProcessor.executeTool(...)`
  - derive the contract from `TurnUserRequestCapture.get()`
  - allow mutating tools only when the contract says mutation is allowed

- `StaticTaskVerifier`
  - optionally accept the contract so expected target checks can use structured
    target hints instead of raw text only

### 4. Keep language honest

This ticket does not mean Talos understands broad user intent.

It means Talos has a deterministic contract for the common local workspace task
shapes it already handles.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/task/`
- `src/main/java/dev/talos/runtime/MutationIntent.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/test/java/dev/talos/runtime/task/`
- `src/test/java/dev/talos/runtime/TurnProcessorTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

### Unit tests

- explicit edit request becomes `FILE_EDIT`
- create/write request becomes `FILE_CREATE`
- read-only inspect request becomes `DIAGNOSE_ONLY` or `WORKSPACE_EXPLAIN`
- meta-question like `why didn't you call the edit tool?` stays read-only
- obvious target extraction finds `index.html`, `style.css`, etc.
- mutation guard denies mutating tool calls when contract disallows mutation
- mutation guard allows approval flow when contract allows mutation
- post-apply verification remains active for mutating contracts

### Scenario coverage

Add at least one JSON scenario proving:
- read-only contract blocks mutation before approval
- explicit edit contract allows the approval path and verifier path to run

Prefer extending existing phase/verifier scenario shapes instead of adding broad
new scenario volume.

### Manual installed Talos verification

After implementation:
- uninstall current Talos
- build `installDist`
- install Talos
- run the standard horror-synth prompt flow
- capture/review `local/manual-testing/test-output`
- confirm read-only inspection stays read-only
- confirm explicit edit still asks approval
- confirm denial still prevents writes
- confirm no raw tool JSON display regression

## Acceptance Criteria

- Talos has a deterministic `TaskContract` for current-turn local workspace
  tasks
- phase initialization uses the contract instead of direct raw mutation
  heuristics
- mutation guard uses the contract instead of directly interpreting the user
  request at the execution gate
- post-apply verification gating can use the contract
- no LLM classifier, planner, shell/browser tool, or new framework is added
- existing phase, approval, verifier, and streaming-display tests still pass

## Completion Notes

Implemented a first deterministic runtime contract slice:

- `TaskType`, `TaskContract`, and `TaskContractResolver`
- phase initialization now uses contract mutation allowance
- mutating tool execution now uses contract mutation allowance
- execution outcome shaping now uses the contract for mutation and verification
  expectations
- static verification can use expected target hints from the contract
- read-only meta-questions about edit tools remain non-mutating

This is intentionally not a full semantic task-contract system. It is the
minimal structured contract layer needed before broader task verification and
failure-policy work.

## Verification Evidence

Focused checks passed:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest"
./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"
./gradlew.bat test --tests "dev.talos.runtime.ApprovalGatedToolTest"
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorPhasePolicyTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Wider checks passed:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos manual verification was run against
`local/playground/horror-synth-site` after uninstall/install. The standard
read-only + denied edit prompt flow confirmed:

- clean session start
- read-only inspection stayed read-only
- explicit edit still reached approval
- denial prevented writes and stopped cleanly
- tracked playground files stayed unchanged
- no raw `talos.*` JSON tool object leaked to the transcript

Residual display polish remains separate debt: one live transcript showed
empty JSON-array punctuation after suppressed streamed tool JSON. That belongs
to the medium-priority streaming display hygiene follow-up, not this ticket.
