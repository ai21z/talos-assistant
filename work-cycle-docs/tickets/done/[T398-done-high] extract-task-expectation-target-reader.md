# [T398-done-high] Extract Task Expectation Target Reader

Status: done
Priority: high
Date: 2026-05-24
Branch: `T398`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `f26777d7`
Predecessor: `T397`

## Scope

T398 implements the next verifier ownership slice after T397.

The task was not to split expectation verification by kind. The task was to
inspect the post-T397 `TaskExpectationStaticVerifier` shape and implement only
the next coherent ownership fix if source evidence proved it could preserve
existing behavior.

Source inspection showed four duplicated target-read blocks in
`TaskExpectationStaticVerifier`:

- exact literal content verification;
- replacement verification;
- append-line verification;
- bullet-list verification.

Each block normalized the target path, resolved it under the workspace root,
checked workspace containment/readability, read file content, and emitted
expectation-specific failure wording.

## What Changed

Added:

```text
src/main/java/dev/talos/runtime/verification/TaskExpectationTargetReader.java
```

`TaskExpectationTargetReader` now owns:

- target path normalization for expectation file reads;
- `root.resolve(...).normalize()` handling;
- `InvalidPathException` handling;
- workspace containment/readability checks;
- `Files.readString(...)`;
- preservation of caller-supplied expectation-specific failure wording.

Updated:

```text
src/main/java/dev/talos/runtime/verification/TaskExpectationStaticVerifier.java
```

`TaskExpectationStaticVerifier` now asks the target reader for the target
content and remains responsible for expectation postcondition logic:

- exact content equality;
- replacement old/new checks;
- preserve-rest replacement evidence;
- append-line post-state;
- append-only mutation evidence;
- bullet-list count/prose checks;
- facts, problems, and summary flags.

Updated:

```text
src/test/java/dev/talos/runtime/verification/TaskExpectationStaticVerifierTest.java
```

Added:

- an ownership test proving target file reads live in
  `TaskExpectationTargetReader`;
- a behavior-preservation test proving the four missing-target messages remain
  expectation-specific.

## Behavior Preservation

T398 preserves the existing failure messages:

| Expectation | Missing target wording |
|---|---|
| exact literal content | `missing.txt: exact content verification target is not a readable file.` |
| replacement | `missing.txt: replacement verification target is not a readable file.` |
| append-line | `missing.txt: appended line verification target is not a readable file.` |
| bullet-list | `missing.md: bullet count verification target is not a readable file.` |

T398 intentionally does not change:

- `TaskExpectationResolver`;
- expectation model records;
- `TaskExpectationTraceRecorder`;
- replacement preserve-rest proof;
- append-only mutation evidence proof;
- line-ending normalization for mutation evidence;
- `TaskVerificationOutcomeSelector`;
- any summary wording.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest.targetReadingIsOwnedByDedicatedReader" --no-daemon
```

The ownership test failed before implementation because
`TaskExpectationTargetReader.java` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon
```

The focused verifier test class passed after extraction.

## Focused Regression Coverage

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon
```

This passed after extraction and covers expectation behavior from the static
verifier facade, outcome selector, and resolver sides.

## Measurements

Measured after extraction:

| File | Lines |
|---|---:|
| `TaskExpectationStaticVerifier.java` | 521 |
| `TaskExpectationTargetReader.java` | 72 |
| `TaskExpectationTraceRecorder.java` | 98 |
| `TaskExpectationStaticVerifierTest.java` | 147 |

The point of T398 is ownership, not line count. The verifier no longer owns
target file I/O mechanics, while the target reader does not own expectation
semantics.

## Rejected Work

### Split by expectation kind

Rejected for T398.

Reason: literal, replacement, append-line, and bullet-list checks still share
result flags, mutation-evidence concerns, and summary selection. The target
reader extraction was the lower-risk prerequisite.

### Move mutation-evidence proof

Rejected for T398.

Reason: preserve-rest and append-only proof are false-success prevention logic.
They deserve their own red/green ticket if moved.

### Change failure wording

Rejected for T398.

Reason: these strings are user-visible verifier evidence. The reader accepts
caller-supplied wording so the extraction does not flatten distinct expectation
failures into a generic file-read error.

## Next Ticket

After T398 is merged and beta CI passes, inspect the post-T398 verifier shape
before choosing T399.

Likely next candidates:

1. a no-code decision ticket for mutation-evidence proof ownership, or
2. a narrow extraction of replacement/append-line text mutation proof
   primitives, but only with focused red/green pass/fail coverage.

Do not split all expectation kinds in one ticket.

## Acceptance Criteria

- `TaskExpectationStaticVerifier` no longer imports `Files` or
  `InvalidPathException`.
- `TaskExpectationTargetReader` owns expectation target file reads.
- Missing-target failure wording remains expectation-specific.
- Existing expectation trace behavior remains unchanged.
- Existing literal/replacement/append-line/bullet-list pass/fail behavior
  remains passing.
- No resolver, mutation-evidence, trace-recorder, or outcome-selector semantics
  change.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- RED ownership test: failed before implementation because
  `TaskExpectationTargetReader.java` did not exist.
- `.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon`:
  passed after extraction.
- `.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon`:
  passed.
- `git diff --check`: passed; line-ending warnings only.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; first run had 1 actionable task executed; final packet
  rerun had 1 actionable task up-to-date).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; first full
  run had 14 actionable tasks: 8 executed, 6 up-to-date; final packet rerun had
  14 actionable tasks: 2 executed, 12 up-to-date).
