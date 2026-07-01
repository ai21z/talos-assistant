# [T400-done-high] Close Task Expectation Verifier Lane

Status: done
Priority: high
Date: 2026-05-24
Branch: `T400`
Candidate version: `talosVersion=0.9.9`
Base branch: `origin/v0.9.0-beta-dev`
Parent head inspected: `816fcfd4`
Predecessor: `T399`

## Scope

T400 is a no-code inspection and decision ticket.

The task is to inspect the post-T399 shape of the task-expectation verifier
lane before choosing another implementation ticket. T400 intentionally does not
extract another class. The goal is to decide whether
`TaskExpectationStaticVerifier` still has a concrete ownership problem, or
whether further movement would be line-count chasing.

## Current Measurements

Measured from fresh `origin/v0.9.0-beta-dev` at `816fcfd4`:

| File | Lines | Current role |
|---|---:|---|
| `TaskExpectationStaticVerifier.java` | 330 | Expectation verification facade/orchestrator, per-kind observed post-state checks, facts/problems aggregation, and result flags. |
| `TaskExpectationTraceRecorder.java` | 98 | Redacted expectation trace event formatting and `LocalTurnTraceCapture` bridge. |
| `TaskExpectationTargetReader.java` | 72 | Workspace-contained expectation target path resolution, readability checks, and target file reads. |
| `TaskExpectationMutationEvidenceVerifier.java` | 208 | Replacement preserve-rest proof and append-only mutation-evidence proof. |
| `TaskExpectationStaticVerifierTest.java` | 169 | Focused ownership and redaction coverage for the expectation verifier lane. |
| `TaskExpectationResolver.java` | 398 | Converts `TaskContract` wording into deterministic expectation records. |
| `StaticTaskVerifier.java` | 621 | Public static verification facade/orchestrator and static-web diagnostic facade. |
| `ExecutionOutcome.java` | 1639 | CLI-facing end-of-turn outcome shaping and bridge over runtime `TaskOutcome`. |
| `TaskOutcome.java` | 37 | Runtime outcome aggregate for contract, completion, mutation, verification, warnings, and tool outcomes. |

## Source Evidence

The task-expectation verifier lane now has three extracted owners:

| Evidence | Meaning |
|---|---|
| `TaskExpectationStaticVerifier.java` calls `TaskExpectationTraceRecorder.record*Expectation(...)`. | Trace event formatting is no longer embedded in the verifier. |
| `TaskExpectationStaticVerifier.java` calls `TaskExpectationTargetReader.read(...)`. | Workspace path resolution and file reads are no longer embedded in the verifier. |
| `TaskExpectationStaticVerifier.java` calls `TaskExpectationMutationEvidenceVerifier.verifyReplacementPreservation(...)`. | Replacement preserve-rest evidence is no longer embedded in the verifier. |
| `TaskExpectationStaticVerifier.java` calls `TaskExpectationMutationEvidenceVerifier.verifyAppendLineMutationEvidence(...)`. | Append-only mutation evidence is no longer embedded in the verifier. |
| Task-expectation source search shows `ToolAliasPolicy` and `mutationEvidence()` only in `TaskExpectationMutationEvidenceVerifier`. | Mutation-evidence mechanics have one owner inside the task-expectation verifier lane. |
| Task-expectation source search shows `InvalidPathException`, `Files.isRegularFile`, and `Files.readString` only in `TaskExpectationTargetReader`. | Target-read mechanics have one owner inside the task-expectation verifier lane. |
| Task-expectation source search shows `LocalTurnTraceCapture` only in `TaskExpectationTraceRecorder`. | Expectation trace formatting has one owner inside the task-expectation verifier lane. |

These ownership claims are intentionally lane-scoped. Repository-wide searches
still find `LocalTurnTraceCapture`, `mutationEvidence()`,
`InvalidPathException`, `Files.isRegularFile`, and `Files.readString` in other
runtime, CLI, tool, and verifier classes. T400 does not claim those broader
mechanics have repository-wide single ownership.

`TaskExpectationStaticVerifier` still owns:

- resolving expectations from the `TaskContract`;
- dispatching literal, replacement, append-line, and bullet-list expectation
  records;
- literal exact-content comparison and user-facing mismatch wording;
- replacement observed post-state checks for old/new text;
- append-line observed post-state checks for uniqueness and EOF position;
- bullet-list line counting and non-bullet prose rejection;
- aggregate `Result` flags used by `TaskVerificationOutcomeSelector`.

That remaining ownership is coherent. It is expectation postcondition
semantics plus result aggregation.

## Decision

Close the `TaskExpectationStaticVerifier` extraction lane for now.

Do not extract another helper from `TaskExpectationStaticVerifier` just because
small private methods remain.

Do not split by expectation kind yet.

Do not move bullet-line counting yet.

Do not retrofit the unused `ExpectationVerificationResult` record yet.

Do not move expectation resolution out of `TaskExpectationStaticVerifier` in a
casual cleanup ticket.

The current file is not perfect, but it is no longer architecturally lying
about trace ownership, target I/O ownership, or mutation-evidence ownership.

## Why Not Continue Extracting Here

### Split by expectation kind

Rejected for now.

Reason: literal, replacement, append-line, and bullet-list checks still share
the same resolver input, target reader, trace recorder, aggregate result flags,
and summary-selection contract. Splitting them now would either duplicate
shared mechanics or force a larger result-model redesign.

### Extract bullet/list text-shape helpers

Rejected for now.

Reason: `bulletLineCount(...)`, `nonBlankNonBulletLineCount(...)`, and
`isBulletLine(...)` are small expectation-specific post-state helpers. Moving
them would mostly rename code without improving an ownership boundary.

### Move expectation resolution

Rejected for now.

Reason: `TaskExpectationStaticVerifier.verify(...)` is the package-local
facade consumed by `StaticTaskVerifier`. Taking resolved expectations as input
would affect call shape and ownership of expectation resolution. That is not
required for the current hygiene lane.

### Adopt `ExpectationVerificationResult`

Rejected for now.

Reason: source search shows `ExpectationVerificationResult` is currently
unused. Adopting it would be a semantic result-pipeline refactor, not a
behavior-preserving extraction.

## Next Lane

The next truthfulness ownership problem is not inside the task-expectation
verifier. It is the end-of-turn outcome shaping boundary.

Source inspection shows:

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java` is 1639 lines;
- `ExecutionOutcome` still owns CLI-facing answer shaping, verification
  annotation wording, warning construction, protected-read postconditions,
  command conclusions, no-tool mutation replacement, and runtime `TaskOutcome`
  assembly;
- `src/main/java/dev/talos/runtime/outcome/TaskOutcome.java` is only 37 lines
  and currently acts as a small aggregate rather than the primary owner of
  outcome truthfulness decisions.

The next correct ticket should be an inspection/decision ticket, not an
implementation extraction:

```text
[T401] ExecutionOutcome And TaskOutcome Boundary Decision
```

T401 should inspect:

- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`;
- `src/main/java/dev/talos/runtime/outcome/*.java`;
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`;
- `src/test/java/dev/talos/runtime/outcome/*.java`;
- current consumers in `AssistantTurnExecutor`;
- historical outcome/truthfulness tickets under `work-cycle-docs/tickets/done/`.

T401 should decide whether the next implementation should:

1. move warning construction into runtime outcome ownership;
2. move verification annotation assembly out of `ExecutionOutcome`;
3. move command-conclusion classification;
4. strengthen `TaskOutcome` as the central truth/result model; or
5. leave the boundary alone until a concrete failure or release gate demands
   movement.

Do not start T401 by extracting code. The current `ExecutionOutcome` surface is
large and user-visible; wording changes here can create failure-truth regressions.

## Acceptance Criteria

- The task-expectation verifier lane is explicitly closed.
- No code changes are made in T400.
- Current post-T399 ownership model is documented.
- Rejected extractions are documented.
- The next hygiene lane is identified as outcome truthfulness boundary
  inspection.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

- `git diff --check`: passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed
  (`BUILD SUCCESSFUL`; first run had 1 actionable task executed; review-fix
  rerun had 1 actionable task up-to-date).
- `.\gradlew.bat check --no-daemon`: passed (`BUILD SUCCESSFUL`; first full
  run had 14 actionable tasks: 13 executed, 1 up-to-date; review-fix rerun had
  14 actionable tasks: 2 executed, 12 up-to-date).
