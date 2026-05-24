# [T399-done-high] Extract Task Expectation Mutation Evidence Verifier

Status: done
Priority: high
Date: 2026-05-24
Branch: `T399`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `d9ab9434`
Predecessor: `T398`

## Scope

T399 implements the next coherent ownership fix after T398.

The task was not to split `TaskExpectationStaticVerifier` by expectation kind.
The task was to inspect the post-T398 verifier shape and move only the
remaining ownership unit that source evidence proved was coherent.

Source inspection showed that `TaskExpectationStaticVerifier` still owned two
mutation-evidence proof mechanisms:

- preserve-rest replacement proof for replacement expectations;
- append-only evidence proof for append-line expectations.

Those checks are not target file reading, trace recording, resolver logic,
outcome selection, or final summary wording. They are mutation evidence
interpretation.

## What Changed

Added:

```text
src/main/java/dev/talos/runtime/verification/TaskExpectationMutationEvidenceVerifier.java
```

`TaskExpectationMutationEvidenceVerifier` now owns:

- canonical mutation tool name checks through `ToolAliasPolicy`;
- `ToolCallLoop.MutationEvidence` inspection;
- preserve-rest replacement proof;
- append-only proof;
- line-ending normalization for mutation-evidence comparison;
- exact-edit and full-write evidence wording.

Updated:

```text
src/main/java/dev/talos/runtime/verification/TaskExpectationStaticVerifier.java
```

`TaskExpectationStaticVerifier` now keeps:

- expectation dispatch;
- target reading delegation;
- observed post-state checks;
- trace recording delegation;
- facts/problems aggregation;
- result flags used by `TaskVerificationOutcomeSelector`.

It no longer imports `ToolAliasPolicy` or directly reads
`ToolCallLoop.MutationEvidence`.

Updated:

```text
src/test/java/dev/talos/runtime/verification/TaskExpectationStaticVerifierTest.java
```

Added an ownership test proving mutation-evidence proof lives in
`TaskExpectationMutationEvidenceVerifier`.

## Behavior Preservation

T399 intentionally preserves the existing verifier wording, including:

- `replacement preservation had no mutation evidence.`
- `talos.edit_file cannot prove preserve-rest replacement without exact edit evidence.`
- `replacement preservation exact edit changed content beyond the requested text.`
- `exact edit evidence preserved content beyond requested replacement.`
- `talos.write_file cannot prove preserve-rest replacement without complete same-turn read evidence.`
- `replacement preservation changed content beyond the requested text.`
- `replacement preservation matched prior content.`
- `mutation tool cannot prove preserve-rest replacement.`
- `replacement preservation had no matching mutation evidence.`
- `full-file write did not preserve prior content before appended line.`
- `talos.write_file cannot prove append-only preservation for an append-line request; use exact talos.edit_file append evidence.`
- `exact edit did not preserve prior content before appended line.`
- `exact edit evidence preserved prior content before appended line.`
- `full-write evidence preserved prior content before appended line.`

T399 does not change:

- `TaskExpectationResolver`;
- expectation model records;
- target path/read behavior;
- trace recording;
- replacement post-state old/new checks;
- append-line post-state EOF checks;
- bullet-list verification;
- `TaskVerificationOutcomeSelector`;
- static-web diagnostics;
- final summary wording.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest.mutationEvidenceProofIsOwnedByDedicatedVerifier" --no-daemon
```

The ownership test failed before implementation because
`TaskExpectationMutationEvidenceVerifier.java` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest.mutationEvidenceProofIsOwnedByDedicatedVerifier" --no-daemon
```

The ownership test passed after extraction.

## Focused Regression Coverage

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon
```

This passed after extraction.

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon
```

This passed after extraction and covers replacement preservation, append-only
evidence, expectation outcome selection, and resolver interactions.

## Measurements

Measured after extraction:

| File | Lines |
|---|---:|
| `TaskExpectationStaticVerifier.java` | 330 |
| `TaskExpectationMutationEvidenceVerifier.java` | 208 |
| `TaskExpectationTargetReader.java` | 72 |
| `TaskExpectationTraceRecorder.java` | 98 |
| `TaskExpectationStaticVerifierTest.java` | 169 |

The point of T399 is ownership, not raw line count. Mutation-evidence proof now
has one owner, while the expectation verifier remains the orchestrator for
expectation post-state semantics.

## Rejected Work

### Split by expectation kind

Rejected for T399.

Reason: literal, replacement, append-line, and bullet-list expectation kinds
still share result aggregation and outcome-selector flags. Splitting them now
would be a larger design move than this ticket needs.

### Move observed post-state checks

Rejected for T399.

Reason: checks such as replacement `oldPresent/newPresent`, appended EOF line
matching, and bullet-list counting are expectation semantics, not mutation
evidence mechanics.

### Change wording

Rejected for T399.

Reason: verifier problem/fact strings are evidence surfaced to users and tests.
This ticket is a behavior-preserving extraction.

## Next Ticket

After T399 is merged and beta CI passes, inspect the post-T399 verifier shape
before choosing T400.

Likely next candidates:

1. close the task-expectation verifier lane if the remaining class is mostly
   expectation orchestration; or
2. inspect whether bullet/list text-shape checks deserve a narrow helper.

Do not split all expectation kinds in one ticket.

## Acceptance Criteria

- `TaskExpectationStaticVerifier` no longer imports `ToolAliasPolicy`.
- `TaskExpectationStaticVerifier` no longer calls `mutationEvidence()`.
- `TaskExpectationMutationEvidenceVerifier` owns replacement preserve-rest proof.
- `TaskExpectationMutationEvidenceVerifier` owns append-only mutation proof.
- Existing replacement/append pass/fail behavior remains passing.
- Existing fact/problem wording remains unchanged.
- No resolver, trace recorder, target reader, outcome selector, or summary
  semantics change.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- RED ownership test: failed before implementation because
  `TaskExpectationMutationEvidenceVerifier.java` did not exist.
- `.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest.mutationEvidenceProofIsOwnedByDedicatedVerifier" --no-daemon`:
  passed after extraction.
- `.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon`:
  passed.
- `.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon`:
  passed.
- `git diff --check`: passed; line-ending warnings only.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; first run had 1 actionable task executed; final packet
  rerun had 1 actionable task up-to-date).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; first full
  run had 14 actionable tasks: 8 executed, 6 up-to-date; final packet rerun had
  14 actionable tasks: 2 executed, 12 up-to-date).
