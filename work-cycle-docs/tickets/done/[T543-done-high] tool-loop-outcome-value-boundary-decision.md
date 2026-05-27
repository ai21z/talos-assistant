# [T543-done-high] Tool Loop Outcome Value Boundary Decision

Status: done
Priority: high
Date: 2026-05-27
Branch: `T543`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `dded0c72`
Predecessor: `T542`

## Scope

T543 inspects the remaining tool-loop outcome value surface after the
response/final-output lane was closed in T542.

This ticket intentionally makes no code changes.

## Source Evidence

Measured from fresh `origin/v0.9.0-beta-dev` at `dded0c72`.

Primary inspection commands:

```powershell
rg -n "record (LoopResult|ToolOutcome|MutationEvidence|MutationSummary|FileChange)|static (LoopResult|ToolOutcome|MutationEvidence|MutationSummary|FileChange)|class ToolOutcomeFactory|class ToolMutationEvidenceFactory" src/main/java/dev/talos/runtime src/test/java/dev/talos/runtime
rg -n "ToolCallLoop\.(LoopResult|ToolOutcome|MutationEvidence|MutationSummary|FileChange)" src/main/java src/test/java src/e2eTest/java
rg -n "mutationEvidence\(|exactEditReplacement|fullWriteReplacement|MutationEvidence" src/main/java src/test/java src/e2eTest/java
rg -n "MutationSummary|FileChange|record FileChange|new FileChange|ChangeSummaryContext" src/main/java src/test/java src/e2eTest/java
```

Current nested value surface in `ToolCallLoop.java`:

| Value | Source line | Current role |
|---|---:|---|
| `LoopResult` | 66 | Public result of `ToolCallLoop.run(...)`; consumed by CLI orchestration, runtime outcome renderers, runtime policy, static verification, E2E harnesses, and many tests. |
| `ToolOutcome` | 194 | Per-tool structured result; consumed by runtime outcome rendering, verification, evidence obligation policy, reprompt planning, trace/accounting, CLI retries, and tests. |
| `MutationEvidence` | 329 | Small mutation-proof value attached to `ToolOutcome`; produced by `ToolMutationEvidenceFactory` and consumed by exact-edit/task-expectation verification. |

Non-findings:

| Name | Result |
|---|---|
| `ToolCallLoop.MutationSummary` | No such nested type exists. Mutation summary state currently lives in `ToolMutationStateAccounting.Result`. |
| `ToolCallLoop.FileChange` | No such nested type exists. Runtime changed-file session memory uses `ChangeSummaryContext.FileChange`. |

Measured reference spread:

| Reference | Files |
|---|---:|
| `ToolCallLoop.LoopResult` | 44 |
| `ToolCallLoop.ToolOutcome` | 77 |
| `ToolCallLoop.MutationEvidence` | 9 |
| `ToolCallLoop.MutationSummary` | 0 |
| `ToolCallLoop.FileChange` | 0 |

Highest production-reference concentrations for the current nested values:

| File | Matches | Assessment |
|---|---:|---|
| `AssistantTurnExecutor.java` | 51 | CLI orchestration still consumes loop results and outcomes directly. Moving `LoopResult`/`ToolOutcome` would touch CLI/runtime integration. |
| `MutationFailureAnswerRenderer.java` | 38 | Runtime outcome rendering depends deeply on `ToolOutcome` semantics. |
| `EvidenceObligationVerifier.java` | 27 | Evidence policy consumes tool outcomes directly. |
| `MissingMutationRetry.java` | 21 | CLI retry behavior depends on outcome facts. |
| `ProtectedReadAnswerGuard.java` | 18 | Protected-read truthfulness guard consumes outcome facts. |
| `MutationOutcome.java` | 16 | Runtime task-outcome classification consumes outcome facts. |
| `StaticVerificationAnswerRenderer.java` | 13 | Verification answer rendering consumes outcome facts. |

Highest test-reference concentrations:

| File | Matches | Assessment |
|---|---:|---|
| `ExecutionOutcomeTest.java` | 130 | CLI final-answer outcome tests instantiate `LoopResult`/`ToolOutcome` heavily. A broad move would be mostly API churn. |
| `EvidenceObligationVerifierTest.java` | 26 | Policy tests depend on direct `ToolOutcome` construction. |
| `MutationOutcomeTest.java` | 8 | Runtime outcome tests consume `ToolOutcome` directly. |
| `StaticTaskVerifierTest.java` | 7 | Static verification uses mutation evidence and outcomes. |
| `MutationFailureAnswerRendererTest.java` | 7 | Runtime outcome wording tests consume `ToolOutcome`. |

Current supporting classes:

| Source | Lines | Role |
|---|---:|---|
| `ToolCallLoop.java` | 512 | Loop orchestration plus public nested result/value compatibility surface. |
| `ToolOutcomeFactory.java` | 92 | Builds `ToolCallLoop.ToolOutcome` instances inside the tool-call execution lane. |
| `ToolMutationEvidenceFactory.java` | 108 | Builds `ToolCallLoop.MutationEvidence` from tool-call parameters and prior read evidence. |
| `TaskOutcome.java` | 37 | Runtime outcome aggregate still stores `List<ToolCallLoop.ToolOutcome>`. |
| `MutationOutcome.java` | 107 | Runtime mutation-status classifier still stores `ToolOutcome` lists. |

Architecture baseline status:

```text
config/architecture-boundary-baseline.txt contains only comments.
```

So any implementation must preserve the zero-baseline ratchet.

## Decision

Do not move `LoopResult` yet.

Do not move `ToolOutcome` yet.

Do not invent a broad outcome-value rewrite.

The next implementation slice should be:

```text
[T544] Extract tool mutation evidence value
```

T544 should extract only `MutationEvidence` from `ToolCallLoop` into a
dedicated runtime-owned value type, then update the narrow producer and
verification consumers.

Recommended target ownership:

```text
dev.talos.runtime.toolcall.ToolMutationEvidence
```

Rationale:

- it is produced by `ToolMutationEvidenceFactory`;
- it is attached to `ToolOutcome` by `ToolOutcomeFactory`;
- it describes evidence captured during tool-call execution, not final-answer
  rendering;
- its main verification consumers can depend on a runtime tool-call evidence
  value without pulling value construction back into `ToolCallLoop`;
- the current consumer set is small enough for one focused implementation
  ticket.

T544 must preserve behavior and wording exactly. It should not rename final
answer wording, task-outcome warnings, mutation-status classification, trace
strings, or verifier messages.

## Why Not Move `LoopResult` Now

`LoopResult` is a public loop facade value, not a small internal detail.

It crosses CLI mode orchestration, E2E scenario harnesses, runtime outcome
renderers, runtime policy, static verification, and many tests. Moving it in
one ticket would either:

- create a compatibility wrapper with little design benefit; or
- force broad churn through CLI, runtime, E2E, and tests.

Neither is the correct next step.

The right future decision for `LoopResult` is likely an explicit compatibility
plan:

- keep `ToolCallLoop.LoopResult` as the public facade until beta stabilizes; or
- introduce a runtime outcome DTO and migrate users in a named compatibility
  packet.

That is not T544.

## Why Not Move `ToolOutcome` Now

`ToolOutcome` is more central than it looks. It carries:

- tool identity;
- path hint;
- success/failure/denial facts;
- mutation flag;
- user-visible summary/error facts;
- file verification status;
- error code;
- workspace operation plan;
- mutation evidence;
- failure-shape helpers used by recovery, summary, and outcome logic.

The current direct consumer spread is 77 files. A one-shot move would be broad
API churn and would risk mixing several separate ownership questions:

- execution-stage outcome construction;
- final-answer outcome rendering;
- protected-read containment;
- evidence-obligation policy;
- mutation recovery;
- static verification;
- CLI retry decisions;
- test fixtures.

`ToolOutcome` may eventually belong outside `ToolCallLoop`, but it needs a
dedicated compatibility decision after the smaller evidence value is extracted.

## Why `MutationEvidence` Is The Correct First Move

`MutationEvidence` is the only narrow value in the remaining nested surface:

- it has 9 direct file references, not 44 or 77;
- it is produced by one dedicated factory;
- it is consumed by two verification owners and focused tests;
- it has no CLI final-answer wording responsibility;
- it has no task-outcome dominance responsibility;
- it has no protected-read containment responsibility;
- it has no PR/trace rendering responsibility.

Extracting it reduces the false impression that `ToolCallLoop` owns mutation
proof semantics while preserving the current loop facade.

## T544 Implementation Shape

T544 should be a code ticket with TDD.

Expected steps:

1. Create fresh branch `T544` from `origin/v0.9.0-beta-dev`.
2. Add a RED ownership/compatibility test proving mutation evidence is no
   longer nested in `ToolCallLoop` and that the factory/verification path uses
   the extracted value.
3. Add `dev.talos.runtime.toolcall.ToolMutationEvidence`.
4. Change `ToolCallLoop.ToolOutcome` to hold `ToolMutationEvidence`.
5. Remove nested `ToolCallLoop.MutationEvidence`.
6. Update:
   - `ToolMutationEvidenceFactory`;
   - `ToolOutcomeFactory`;
   - `ToolCallExecutionStage`;
   - `ExactEditReplacementVerifier`;
   - `TaskExpectationMutationEvidenceVerifier`;
   - focused tests that construct mutation evidence directly.
7. Preserve all method names on the extracted value:
   - `none()`;
   - `exactEdit(...)`;
   - `fullWriteReplacement(...)`;
   - `exactEditReplacement()`;
   - `fullWriteReplacement()`;
   - `oldString()`;
   - `newString()`;
   - `kind()`.
8. Run focused tests first, then architecture validation and full `check`.

Focused tests should include at minimum:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon
```

Then:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Rejected Next Moves

### Move `LoopResult`

Rejected for T544.

Reason: too broad, public-facing, and heavily consumed.

### Move `ToolOutcome`

Rejected for T544.

Reason: too broad and semantically mixed. It needs its own compatibility
decision after the mutation-evidence value is extracted.

### Move `ChangeSummaryContext.FileChange`

Rejected.

Reason: it is not part of the `ToolCallLoop` nested value surface. It is owned
by runtime session change-summary memory.

### Extract `MutationSummary`

Rejected.

Reason: there is no `ToolCallLoop.MutationSummary` value. Existing mutation
summary bookkeeping is already owned by `ToolMutationStateAccounting.Result`.

### Create a generic `runtime.value` package

Rejected.

Reason: it would hide ownership instead of clarifying it. The first extracted
value has a concrete source and use: tool-call mutation evidence.

## Acceptance Criteria

- Inspect all remaining `ToolCallLoop` nested outcome values.
- Count reference spread before deciding.
- Distinguish real nested values from nonexistent or unrelated values.
- Decide whether implementation should proceed.
- Select one coherent next implementation ticket.
- Make no code changes.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```
