# [T544-done-high] Extract Tool Mutation Evidence Value

Status: done
Priority: high
Date: 2026-05-27
Branch: `T544`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `acfeb107`
Predecessor: `T543`

## Scope

T544 extracts the mutation-evidence value out of `ToolCallLoop` without moving
`LoopResult`, `ToolOutcome`, final-answer wording, outcome dominance,
protected-read containment, trace rendering, or verification behavior.

## Changes

- Added `dev.talos.runtime.toolcall.ToolMutationEvidence`.
- Removed nested `ToolCallLoop.MutationEvidence`.
- Updated `ToolCallLoop.ToolOutcome` to store `ToolMutationEvidence`.
- Updated narrow producer/consumer path:
  - `ToolMutationEvidenceFactory`;
  - `ToolOutcomeFactory`;
  - `ToolCallExecutionStage`;
  - `ExactEditReplacementVerifier`;
  - `TaskExpectationMutationEvidenceVerifier`;
  - focused mutation evidence and verifier tests.
- Added a RED/GREEN ownership test proving mutation evidence is now owned
  outside `ToolCallLoop`.

## TDD Evidence

RED command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest.mutationEvidenceValueIsOwnedOutsideToolCallLoop" --no-daemon
```

RED result:

```text
ToolMutationEvidenceFactoryTest > mutationEvidenceValueIsOwnedOutsideToolCallLoop() FAILED
AssertionFailedError at ToolMutationEvidenceFactoryTest.java:110
```

Failure reason: `ToolCallLoop.java` still contained nested
`record MutationEvidence`.

GREEN command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest.mutationEvidenceValueIsOwnedOutsideToolCallLoop" --no-daemon
```

GREEN result: passed.

Focused regression command:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest" --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon
```

Focused regression result: passed.

## Ownership Decision

`ToolMutationEvidence` belongs to `dev.talos.runtime.toolcall` for now.

Reason:

- it is captured from tool-call inputs and same-turn read evidence;
- it is produced by `ToolMutationEvidenceFactory`;
- it is attached to `ToolOutcome` by `ToolOutcomeFactory`;
- its verification consumers need the evidence facts, not ownership of evidence
  construction;
- moving it to `runtime.outcome` would confuse evidence capture with final
  answer rendering.

## Preserved Behavior

The extracted value preserves the previous API shape:

- `none()`;
- `exactEdit(...)`;
- `fullWriteReplacement(...)`;
- `exactEditReplacement()`;
- `fullWriteReplacement()`;
- `kind()`;
- `oldString()`;
- `newString()`.

No task outcome wording, verifier wording, trace wording, mutation-status
classification, protected-read handling, or final answer behavior changed.

## Rejected Scope

### Move `ToolOutcome`

Rejected.

Reason: `ToolOutcome` still has broad ownership and compatibility implications
across outcome rendering, evidence policy, verification, retry orchestration,
CLI modes, and tests.

### Move `LoopResult`

Rejected.

Reason: `LoopResult` remains the public `ToolCallLoop.run(...)` facade and is
too broad for this ticket.

### Introduce a generic outcome value package

Rejected.

Reason: the extracted value has concrete tool-call evidence ownership. A
generic package would make the architecture less precise.

## Verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest.mutationEvidenceValueIsOwnedOutsideToolCallLoop" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolMutationEvidenceFactoryTest" --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --tests "dev.talos.runtime.verification.ExactEditReplacementVerifierTest" --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next Move

After T544 merges, inspect the post-extraction outcome value shape before
starting another implementation. Do not assume `ToolOutcome` should move next
without a fresh compatibility and ownership inspection.
