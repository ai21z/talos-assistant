# [T864-open-medium] Write-Layer Verification Fail-Closed

Status: open
Priority: medium

## Evidence Summary

- Source: static code review
- Date: 2026-06-23
- Talos version / commit: `0.10.5` / `723d4cd2`
- Branch: `v0.9.0-beta-dev`
- Model/backend: not applicable
- Workspace fixture: not applicable
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: no runtime diff; static source gap
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: not run

Redacted prompt sequence:

```text
Static source review asked whether write/edit read-back integrity failure is
treated as a failed mutation outcome, or only as later post-apply verification
evidence.
```

Expected behavior:

```text
If a write/edit tool cannot read back the approved bytes it attempted to write,
or reads back different bytes, the mutation should not be classified as a clean
success. The runtime should fail closed or downgrade the mutation outcome
before any success-shaped final outcome can dominate.
```

Observed behavior:

```text
`FileWriteTool` and `FileEditTool` call `ContentVerifier.verify(...)`, but when
`vr.ok()` is false they still return `ToolResult.ok(..., vr.status())`.
`ToolOutcomeFactory` copies `result.success()` into `ToolOutcome.success()`.
`MutationOutcome` classifies successful mutating outcomes using only
`ToolOutcome.success()` and does not consult `fileVerificationStatus()`.

`ContentVerifier` currently overloads `VerificationStatus.FAIL` for two
different facts:

- integrity failure: read-back I/O error or read-back byte mismatch, before any
  semantic validator runs;
- structural failure: byte-matched content that fails JSON/YAML/XML parsing.

The enum documentation only describes the structural case: filesystem mutation
succeeded but content is invalid. The integrity case violates the stronger
approved-bytes-equal-written-bytes invariant and needs a distinct typed status.

`MutationTargetReadbackVerifier` does later add a problem for non-acceptable
`fileVerificationStatus()`, but that path is gated by post-apply/static
verification. The write layer and mutation-outcome layer can still represent a
verification-failed write as successful.
```

Source evidence:

- `src/main/java/dev/talos/tools/impl/ContentVerifier.java`: read-back I/O
  failure and read-back mismatch return `VerificationStatus.FAIL` before
  semantic validation; malformed JSON/YAML/XML also returns
  `VerificationStatus.FAIL` after byte equality has already passed.
- `src/main/java/dev/talos/tools/VerificationStatus.java`: `FAIL` is
  documented as filesystem-success/content-invalid, not read-back integrity
  failure.
- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`: failed
  `ContentVerifier` result returns `ToolResult.ok(...)`.
- `src/main/java/dev/talos/tools/impl/FileEditTool.java`: failed
  `ContentVerifier` result returns `ToolResult.ok(...)`.
- `src/main/java/dev/talos/runtime/toolcall/ToolOutcomeFactory.java`:
  `success = result != null && result.success()`.
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`:
  success counters, mutation evidence, mutation-state accounting, failure
  accounting, and progress reporting key off `ToolResult.success()` before
  `ToolOutcomeFactory.executed(...)` builds the structured `ToolOutcome`.
- `src/main/java/dev/talos/runtime/failure/FailurePolicy.java`: repeated
  failures are bounded by same-path, same-tool, and no-progress thresholds.
- `src/main/java/dev/talos/runtime/repair/RepairAttemptBudget.java`: repair
  plans are bounded by per-turn, per-path, failed-mutation, and no-progress
  limits.
- `src/main/java/dev/talos/runtime/outcome/MutationOutcome.java`: successful,
  failed, and status classification are based on `ToolOutcome.success()` /
  denied state, not `fileVerificationStatus()`.
- `src/main/java/dev/talos/runtime/verification/MutationTargetReadbackVerifier.java`:
  later verification catches non-acceptable `fileVerificationStatus()`, so this
  is a layer-boundary gap rather than total absence of detection.

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `TOOL_RESULT`
- `FAILURE_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
The gap weakens the trust story around false success. It is lower than a P0
because the post-apply verifier can catch the same flag when static
verification runs, and true read-back divergence after `Files.writeString` is
rare. It still matters before strong public claims that Talos cannot claim a
failed file change as successful.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Patch the final answer wording only.
```

Architectural hypothesis:

```text
File-level verification status should participate in the structured tool and
mutation outcome contract, not only in later static verification wording.
Read-back integrity failure should have a distinct typed status and should be
fail-closed at the `ToolResult` layer so upstream failure accounting, mutation
evidence, loop progress, and downstream `ToolOutcome` construction agree.
Structural `FAIL` should remain a successful write with failed/negative
verification surfaced unless a separate product decision introduces a
single-mutation partial state. `WARN` remains warning-only unless separately
designed.
```

Likely code/document areas:

- `src/main/java/dev/talos/tools/impl/FileWriteTool.java`
- `src/main/java/dev/talos/tools/impl/FileEditTool.java`
- `src/main/java/dev/talos/tools/VerificationStatus.java`
- `src/main/java/dev/talos/tools/ToolResult.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolOutcomeFactory.java`
- `src/main/java/dev/talos/runtime/outcome/MutationOutcome.java`
- `src/main/java/dev/talos/runtime/failure/FailurePolicy.java`
- `src/main/java/dev/talos/runtime/repair/RepairAttemptBudget.java`
- `src/test/java/dev/talos/tools/impl/*`
- `src/test/java/dev/talos/runtime/ToolCallLoopTest.java`
- `src/test/java/dev/talos/runtime/toolcall/ToolOutcomeFactoryTest.java`
- `src/test/java/dev/talos/runtime/outcome/MutationOutcomeTest.java`
- `src/test/java/dev/talos/runtime/verification/*`

Why a one-off patch is insufficient:

```text
Changing only the write tools may lose verification metadata on failure unless
`ToolResult` supports failed results with file-verification status. Changing
only final-answer rendering leaves structured outcome evidence wrong. Changing
only `ToolOutcomeFactory` leaves upstream `ToolResult.success()` consumers
inconsistent: the loop may still record mutation evidence, count progress, skip
failure accounting, and emit warning-style progress for a write that the
structured outcome later calls failed. The fix must preserve the verification
flag and make tool result, loop accounting, outcome classification, and trace
evidence agree.
```

## Goal

```text
Ensure read-back integrity failure from write/edit verification cannot produce
a clean successful mutation outcome, while byte-matched structural validation
failure remains represented as a successful write with failed verification.
Add deterministic regression coverage for that boundary.
```

## Non-Goals

- No broad verifier rewrite.
- No weakening of readback-only `UNKNOWN` behavior.
- No automatic rollback or checkpoint restore in this ticket.
- No expansion of arbitrary semantic validation.
- No final-answer-only prompt patch.

## Implementation Notes

```text
Prefer the smallest structured change that keeps verification metadata visible.
Two likely options:

1. Add a distinct `VerificationStatus` value for read-back integrity failure,
   for example `INTEGRITY_FAIL` or `READBACK_MISMATCH`, and emit it from
   `ContentVerifier` on read-back I/O error and byte mismatch.
2. Add or extend a failed `ToolResult` factory so a failed result can still
   carry the file-verification status and user-visible verification summary.
3. Make `FileWriteTool` and `FileEditTool` return a failed `ToolResult` for the
   integrity-failure status, not an ok result with a warning string.
4. Keep `ToolOutcomeFactory.executed(...)` as a propagation/checkpoint layer:
   it should see `result.success() == false`, preserve the verification status,
   and avoid manufacturing a success/failure split.

Do not collapse `UNKNOWN`, structural `FAIL`, or `WARN` into mutation failure.
Structural `FAIL` and `WARN` should still be visible to static verification and
final-answer truthfulness, but they should not claim the approved bytes failed
to land.
```

`ContentVerifier` currently uses `FAIL` for both byte/read-back failure and
structural invalidity in supported text formats such as malformed JSON/YAML/XML.
The implementation should split those cases instead of treating every `FAIL` as
a failed mutation. The default trust-first recommendation is:

- read-back I/O failure or mismatch: failed mutation outcome;
- structural JSON/YAML/XML parse failure after byte equality: successful write,
  failed verification;
- HTML/CSS/JS `WARN`: successful write, warning surfaced.

## Architecture Metadata

Capability:

- Write/edit mutation verification and truthful outcome classification.

Operation(s):

- `write`
- `edit`
- `verify`

Owning package/class:

- `dev.talos.tools.VerificationStatus`
- `dev.talos.tools.ToolResult`
- `dev.talos.tools.impl.FileWriteTool`
- `dev.talos.tools.impl.FileEditTool`
- `dev.talos.runtime.toolcall.ToolCallExecutionStage`
- `dev.talos.runtime.toolcall.ToolOutcomeFactory`
- `dev.talos.runtime.outcome.MutationOutcome`

New or changed tools:

- No new tool names.
- Existing `talos.write_file` and `talos.edit_file` outcome semantics become
  fail-closed for read-back integrity failure.

Risk, approval, and protected paths:

- Risk level: mutation outcome truthfulness.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: file-level verification metadata must remain visible in
  tool outcome, trace, and task outcome.
- Verification profile: write/read-back plus content verifier.
- Repair profile: existing repair/reprompt behavior should see read-back
  integrity failure as failed, not cleanly succeeded. The implementation must
  verify this stays bounded and does not create an unbounded retry path.

Outcome and trace:

- Outcome/truth warnings: failed file verification must dominate clean success.
- Trace/debug fields: preserve file-verification status in outcome evidence.

Refactor scope:

- Allowed: small result/outcome contract adjustment.
- Forbidden: broad tool-loop rewrite or prompt-only solution.

## Acceptance Criteria

- `ContentVerifier` distinguishes read-back integrity failure from structural
  semantic failure with a typed status that survives tool-result handoff.
- Failed `ToolResult` can carry file-verification metadata, so integrity failure
  does not discard the verification status or summary.
- `talos.write_file` with read-back integrity failure cannot be counted as a
  clean successful mutation.
- `talos.edit_file` with read-back integrity failure cannot be counted as a
  clean successful mutation.
- `ToolCallExecutionStage` records integrity-failed writes as failed calls:
  no successful mutation evidence, no successful mutation summary, failure
  counts updated, and progress emitted as error/failure rather than warning.
- `MutationOutcome.from(...)` no longer returns `SUCCEEDED` when the only
  mutating outcome has an integrity-failed file verification status.
- The static verifier still records a problem for non-acceptable
  `fileVerificationStatus()`.
- Malformed JSON/YAML/XML that was byte-matched on disk remains a successful
  write with failed verification surfaced, not a failed write.
- `VerificationStatus.UNKNOWN` remains acceptable/readback-only where no
  semantic validator exists.
- `VerificationStatus.WARN` remains warning-only for mutation outcome
  classification.
- Final outcome wording cannot imply clean success after file-level verification
  failure.
- Integrity-failed write/edit behavior is bounded: it either stops truthfully or
  performs a bounded retry according to existing failure policy, never an
  unbounded loop.

## Tests / Evidence

Required deterministic regression:

- Unit test: `VerificationStatusTest` for the new integrity-failure status,
  label, and acceptable semantics.
- Unit test: `ContentVerifierTest` proving read-back I/O/mismatch emits the new
  integrity-failure status while malformed JSON/YAML/XML remains structural
  `FAIL`.
- Unit test: `MutationOutcomeTest` for an integrity-failed mutating
  `ToolOutcome` downgrading to failed or partial.
- Unit test: `ToolOutcomeFactoryTest` or equivalent proving a `ToolResult` with
  `success == false` and the integrity-failure status preserves the verification
  metadata and does not become clean success evidence.
- Unit test: `ToolResult` coverage for failed results carrying
  `VerificationStatus`.
- Tool test: write malformed supported structured content, such as invalid JSON,
  and verify the write remains successful while failed verification is surfaced.
- Tool test: equivalent edit-path coverage for `FileEditTool`.
- Verification test: keep `MutationTargetReadbackVerifier` problem reporting for
  non-acceptable file verification.
- Loop/failure-policy test: an integrity-failed write/edit is counted as failed,
  does not record successful mutation evidence, and does not enter an unbounded
  retry loop.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.VerificationStatusTest" --tests "dev.talos.tools.impl.*" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --tests "dev.talos.runtime.outcome.MutationOutcomeTest" --tests "dev.talos.runtime.verification.*" --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop first.
- Add a `CHANGELOG.md` `Unreleased` note when implementation lands.
- Do not use this ticket as proof that all false-success classes are solved;
  it covers file-level write/edit verification failure only.

## Known Risks

- Returning failed `ToolResult` without carrying verification metadata would
  hide useful evidence from traces and static verification.
- Treating structural `FAIL` or `WARN` as fatal without design intent may create
  inverse false-failure claims for writes where approved bytes landed.
- A test based on actual byte read-back mismatch may be hard to make
  deterministic because `ContentVerifier` reads immediately after local write;
  malformed JSON/YAML/XML can provide deterministic `FAIL` coverage.

## Known Follow-Ups

- Broader run-command verification and false-success coverage remains separate.
- Live audit should include at least one file-verification failure prompt once
  this ticket lands.

## Implementation Checkpoint - 2026-06-24

Status: implemented, awaiting review. Keep this ticket open until independent review/owner
review verifies the trust-surface behavior and closes it.

Implementation summary:

- Added `VerificationStatus.INTEGRITY_FAIL` for read-back I/O failure or
  byte mismatch, leaving structural JSON/YAML/XML `FAIL` for byte-matched
  content-invalid cases.
- Added failed `ToolResult` support for verification metadata.
- Added a package-private write/edit result mapper so `FileWriteTool` and
  `FileEditTool` return failed results for integrity failure, while preserving
  successful write semantics for structural `FAIL`, `WARN`, `PASS`, and
  `UNKNOWN`.
- Preserved verification metadata through `ToolOutcomeFactory` and failed
  tool-result formatting.
- Added regression coverage for verifier split, write/edit structural failure
  behavior, failed result metadata, outcome classification, mutation-state
  accounting, and readback verifier problem reporting.

Local verification:

```powershell
.\gradlew.bat test --tests "dev.talos.tools.VerificationStatusTest" --tests "dev.talos.tools.impl.*" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.runtime.toolcall.ToolOutcomeFactoryTest" --tests "dev.talos.runtime.toolcall.ToolResultFormatterTest" --tests "dev.talos.runtime.toolcall.ToolMutationStateAccountingTest" --tests "dev.talos.runtime.outcome.MutationOutcomeTest" --tests "dev.talos.runtime.verification.*" --no-daemon
```

Result: PASS.
