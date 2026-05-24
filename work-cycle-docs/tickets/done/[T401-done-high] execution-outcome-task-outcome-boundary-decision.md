# [T401-done-high] ExecutionOutcome And TaskOutcome Boundary Decision

Status: done
Priority: high
Date: 2026-05-24
Branch: `T401`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `f13f8582`
Predecessor: `T400`

## Scope

T401 is a no-code inspection and decision ticket.

The task is to inspect the post-T400 end-of-turn outcome boundary before
choosing another implementation ticket. T401 intentionally does not extract
code from `ExecutionOutcome`. The goal is to decide which remaining
truthfulness/outcome responsibility has the clearest owner and the lowest risk
of changing final-answer wording.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `f13f8582`:

| File | Lines | Current role |
|---|---:|---|
| `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java` | 1639 | CLI-facing end-of-turn answer shaping, evidence checks, verifier invocation, status dominance bridge, warning construction, protected-read postconditions, command result replacement, and trace outcome emission. |
| `src/main/java/dev/talos/cli/modes/OutcomeDominancePolicy.java` | 235 | CLI-local precedence policy that maps primitive outcome facts to `ExecutionOutcome.CompletionStatus` and runtime `TaskCompletionStatus`. |
| `src/main/java/dev/talos/runtime/outcome/TaskOutcome.java` | 37 | Runtime aggregate for task contract, completion status, mutation outcome, verification result, truth warnings, and tool outcomes. |
| `src/main/java/dev/talos/runtime/outcome/MutationOutcome.java` | 107 | Runtime mutation status classifier over mutating tool outcomes. |
| `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java` | 3177 | Broad user-visible outcome regression suite covering denial, command, evidence, verification, protected-read, static-web, no-tool, and trace outcomes. |
| `src/test/java/dev/talos/cli/modes/OutcomeDominancePolicyTest.java` | 273 | Focused precedence tests for blocked, failed, partial, advisory, read-only, verified, and unverified states. |
| `src/test/java/dev/talos/runtime/outcome/MutationOutcomeTest.java` | 225 | Focused runtime mutation classification tests. |

## Source Evidence

The current boundary is a bridge, not a clean ownership model:

| Evidence | Meaning |
|---|---|
| `ExecutionOutcome.fromToolLoop(...)` shapes model text through multiple `AssistantTurnExecutor` helpers before constructing `TaskOutcome`. | CLI outcome rendering still owns answer correction and compatibility with existing executor helpers. |
| `ExecutionOutcome.fromToolLoop(...)` verifies evidence obligations through `EvidenceObligationVerifier` and can replace answer text with missing-evidence containment. | Evidence sufficiency still participates in final answer shaping, not only structured runtime outcome data. |
| `ExecutionOutcome.fromToolLoop(...)` invokes `StaticTaskVerifier.verify(...)`, maps `TaskVerificationStatus`, and prepends or replaces final answer text with static verification annotations. | Verification outcome rendering is still tightly coupled to user-facing wording. |
| `ExecutionOutcome.outcomeDecision(...)` delegates final status precedence to `OutcomeDominancePolicy`, but the policy accepts `ExecutionOutcome.VerificationStatus` and returns `ExecutionOutcome.CompletionStatus`. | Dominance is centralized, but still CLI-local and coupled to CLI status enums. |
| `ExecutionOutcome` builds `TaskOutcome` directly with `new TaskOutcome(...)`. | Runtime `TaskOutcome` is an aggregate target, not yet the primary owner of all outcome construction. |
| `ExecutionOutcome.toolLoopWarnings(...)` and `ExecutionOutcome.noToolWarnings(...)` create runtime `TruthWarning` values and runtime `TruthWarningType` values inside the CLI package. | Warning construction has a clear ownership mismatch: the values are runtime outcome concepts, but their construction still lives in CLI outcome rendering. |
| `ExecutionOutcome.recordLocalTraceOutcome(...)` records warning messages by iterating `taskOutcome.warnings()`. | Trace emission already consumes structured warnings after construction; it does not need warning construction to stay in CLI. |
| `TaskOutcome.java`, `TruthWarning.java`, and `TruthWarningType.java` live under `dev.talos.runtime.outcome`. | The target package for warning construction already exists and is lower than CLI. |
| `ExecutionOutcomeTest` mostly asserts warning presence through `TaskOutcome.hasWarning(...)`; only a small number of higher-level tests inspect warning message fragments indirectly through trace or task outcome output. | Moving warning construction with exact message preservation is testable without changing final answer wording. |
| `MutationOutcome` already owns mutation-status classification in runtime outcome. | Runtime outcome ownership has precedent: status facts can move below CLI when they are structured and not answer-rendering specific. |

## Decision

Do not start by moving final-answer rendering, verification annotations, or
dominance status selection.

The next implementation ticket should be:

```text
[T402] Extract task outcome warning builder
```

T402 should extract warning construction from `ExecutionOutcome` into runtime
outcome ownership while preserving exact warning types, messages, ordering, and
final-answer wording.

Proposed production class:

```text
src/main/java/dev/talos/runtime/outcome/TaskOutcomeWarningBuilder.java
```

The class should own only this responsibility:

```text
Given already-derived outcome facts, construct the ordered runtime
TruthWarning list for tool-loop and no-tool turns.
```

`ExecutionOutcome` should still derive the facts, shape answer text, invoke
static verification, choose dominance through `OutcomeDominancePolicy`, create
`TaskOutcome`, and record trace outcomes in T402.

## Why Warning Construction Is The Correct Next Slice

Warning construction is the clearest next ownership fix because:

- `TruthWarning` and `TruthWarningType` are runtime outcome types already.
- The current construction methods in `ExecutionOutcome` are pure mapping from
  facts to warning values.
- Moving them does not require moving final answer text or changing status
  dominance.
- The move can be covered by focused runtime outcome tests.
- `ExecutionOutcome` can keep all high-risk user-facing text paths unchanged.
- It shrinks the CLI outcome bridge without forcing a premature redesign of
  `TaskOutcome`.

This is real ownership work, not a line-count cleanup. The warning list is the
structured truth evidence later consumed by trace and tests.

## Rejected T402 Alternatives

### Move `OutcomeDominancePolicy` to runtime now

Rejected for T402.

Reason: `OutcomeDominancePolicy` currently accepts `ExecutionOutcome.VerificationStatus`
and returns `ExecutionOutcome.CompletionStatus`. Moving it cleanly would require
either moving CLI status enums into runtime or designing a new runtime decision
type. That is the correct direction eventually, but it is larger than the next
safe slice.

### Move verification annotation rendering

Rejected for T402.

Reason: `staticVerificationPassedAnnotation(...)`,
`readbackOnlyVerificationAnnotation(...)`,
`staticVerificationFailedReplacement(...)`,
`partialStaticVerificationFailedAnnotation(...)`, and
`staticVerificationUnavailableAnnotation(...)` directly shape final answer text.
Those strings are user-visible truthfulness gates. Moving them before the
structured outcome warning boundary is unnecessary risk.

### Extract command conclusion handling first

Rejected for T402.

Reason: command conclusion handling is a plausible later slice, but
`commandFailureReplacement(...)`, `commandSuccessReplacement(...)`, and
`commandRequiredButNotRunReplacement()` are final-answer rendering paths.
Extracting only the classifier would help less than moving warning
construction, and extracting the renderer would be higher risk.

### Strengthen `TaskOutcome` as the central result model now

Rejected for T402.

Reason: `TaskOutcome` should eventually become a stronger runtime truth model,
but doing that directly would mix status dominance, warning construction,
verification rendering, evidence containment, protected-read postconditions,
and trace output. That would be too broad for one ticket.

### Leave the boundary alone

Rejected.

Reason: the current boundary still makes CLI code construct runtime warning
values. The owner is obvious, the implementation surface is bounded, and the
move improves every future outcome ticket.

## T402 Implementation Boundary

T402 should:

- Create `TaskOutcomeWarningBuilder` under `dev.talos.runtime.outcome`.
- Move the exact warning type/message construction from:
  - `ExecutionOutcome.toolLoopWarnings(...)`;
  - `ExecutionOutcome.noToolWarnings(...)`.
- Use runtime-facing inputs. Prefer `TaskVerificationStatus` over
  `ExecutionOutcome.VerificationStatus` so the builder does not depend on CLI
  status enums.
- Preserve warning ordering exactly.
- Preserve warning messages exactly.
- Preserve all final answer wording exactly.
- Add focused runtime tests for the warning builder.
- Keep existing `ExecutionOutcomeTest` and `OutcomeDominancePolicyTest`
  behavior unchanged.

T402 should not:

- Move `OutcomeDominancePolicy`.
- Move `ExecutionOutcome.CompletionStatus` or `ExecutionOutcome.VerificationStatus`.
- Change `TaskOutcome` constructor shape unless the warning builder needs a
  minimal helper.
- Move command-result final answer rendering.
- Move protected-read postcondition handling.
- Move evidence obligation containment.
- Move static verification annotations.
- Change trace output wording or event names.

## T402 Focused Test Plan

Recommended focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.outcome.TaskOutcomeWarningBuilderTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest" --no-daemon
```

Required closeout gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```

## Future Lane Order After T402

Provisional order after T402:

1. Re-inspect whether command conclusion classification can move without
   moving command final-answer text.
2. Re-inspect whether `OutcomeDominancePolicy` can move after introducing a
   runtime decision/result type.
3. Re-inspect verification annotation rendering only after the structured
   outcome model is stronger.
4. Avoid broad `ExecutionOutcome` rewrites unless a concrete failure or release
   gate requires them.

This order is provisional. Each ticket must re-check source evidence before
implementation.

## Acceptance Criteria

- T401 changes no production runtime behavior.
- T401 records current `ExecutionOutcome` / `TaskOutcome` ownership evidence.
- T401 selects one next implementation slice.
- T401 rejects high-risk alternatives with concrete reasons.
- T401 does not commit generated artifacts or prompt-debug evidence.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; first run had 1 actionable task executed; final rerun
  had 1 actionable task up-to-date).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; 14
  actionable tasks: first full run had 13 executed and 1 up-to-date; final
  rerun had 2 executed and 12 up-to-date).
