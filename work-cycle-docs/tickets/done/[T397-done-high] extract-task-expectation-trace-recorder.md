# [T397-done-high] Extract Task Expectation Trace Recorder

Status: done
Priority: high
Date: 2026-05-24
Branch: `T397`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `9a7fadd0`
Predecessor: `T396`

## Scope

T397 implements the first implementation slice selected by T396.

The goal is narrow: move redaction-safe expectation trace event formatting out
of `TaskExpectationStaticVerifier` into a dedicated package-private recorder.
T397 does not change task expectation resolution, postcondition verification,
mutation-evidence proof, summary selection, facts, problems, or user-facing
wording.

## What Changed

Added:

```text
src/main/java/dev/talos/runtime/verification/TaskExpectationTraceRecorder.java
```

This new package-private helper owns the existing trace formatting methods:

- `recordLiteralExpectation(...)`
- `recordReplacementExpectation(...)`
- `recordAppendLineExpectation(...)`
- `recordBulletListExpectation(...)`

It still delegates to the existing low-level trace sink:

```text
LocalTurnTraceCapture.recordExpectationVerified(...)
```

Updated:

```text
src/main/java/dev/talos/runtime/verification/TaskExpectationStaticVerifier.java
```

`TaskExpectationStaticVerifier` now delegates expectation trace recording to
`TaskExpectationTraceRecorder` and no longer imports
`LocalTurnTraceCapture`.

Updated:

```text
src/test/java/dev/talos/runtime/verification/TaskExpectationStaticVerifierTest.java
```

Added an ownership test proving:

- `TaskExpectationTraceRecorder.java` exists;
- `TaskExpectationStaticVerifier.java` does not directly reference
  `LocalTurnTraceCapture`;
- `TaskExpectationStaticVerifier.java` does not directly call
  `recordExpectationVerified`;
- the recorder owns all four expectation trace recording methods.

## Behavior Preservation

The moved recorder code preserves the existing trace fields:

- `kind`
- `status`
- `pathHint`
- `sourcePattern`
- `expectedHash`
- `expectedBytes`
- `expectedChars`
- `expectedLines`
- `observedHash`
- `observedBytes`
- `observedChars`
- `observedLines`

The event sink remains `LocalTurnTraceCapture.recordExpectationVerified(...)`.

T397 intentionally does not change:

- `TaskExpectationResolver`;
- expectation model records;
- literal exact-content verification;
- replacement verification;
- preserve-rest replacement proof;
- append-line verification;
- append-only mutation evidence proof;
- bullet-list verification;
- `TaskVerificationOutcomeSelector`;
- any summary wording.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest.traceRecordingIsOwnedByDedicatedRecorder" --no-daemon
```

The new ownership test failed before implementation because
`TaskExpectationTraceRecorder.java` did not exist.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.TaskExpectationStaticVerifierTest" --no-daemon
```

The focused verifier test class passed after the recorder extraction.

## Focused Regression Coverage

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.TaskVerificationOutcomeSelectorTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --no-daemon
```

This passed after the extraction and covers the existing static-verification
expectation behavior from the facade, outcome selector, and resolver sides.

## Measurements

Measured after extraction:

| File | Lines |
|---|---:|
| `TaskExpectationStaticVerifier.java` | 588 |
| `TaskExpectationTraceRecorder.java` | 98 |
| `TaskExpectationStaticVerifierTest.java` | 99 |

The point of T397 is not line-count reduction. The point is ownership:
trace formatting no longer lives inside the verifier that owns postcondition
logic.

## Rejected Work

### Split by expectation kind

Rejected for T397.

Reason: literal, replacement, append-line, and bullet-list verification still
share target reading, path normalization, summary flags, and evidence concerns.
Splitting by kind should happen only after this preparatory trace boundary is
stable.

### Move mutation-evidence proof

Rejected for T397.

Reason: preserve-rest and append-only proof are false-success prevention logic.
Moving them requires a separate red/green ticket with focused pass/fail
coverage.

### Adopt `ExpectationVerificationResult`

Rejected for T397.

Reason: that record is currently unused. Adopting it would change the internal
result pipeline and possibly summary precedence.

## Next Ticket

After T397 is merged and beta CI passes, inspect the post-extraction
`TaskExpectationStaticVerifier` shape before choosing T398.

The likely next implementation candidate is target file read/path-resolution
extraction, but only if inspection proves it can preserve all existing
expectation-specific failure wording.

## Acceptance Criteria

- `TaskExpectationStaticVerifier` no longer imports `LocalTurnTraceCapture`.
- Redacted expectation trace formatting is owned by
  `TaskExpectationTraceRecorder`.
- Existing expectation trace redaction behavior remains passing.
- Existing literal/replacement/append-line/bullet-list summary behavior remains
  passing.
- No resolver, mutation-evidence, or outcome-selector semantics change.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- RED ownership test: failed before implementation because
  `TaskExpectationTraceRecorder.java` did not exist.
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
