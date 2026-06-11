# T763 - Stop-Word "by" Parsed As A Phantom Expected Mutation Target

Status: done - completed in wave 2; see completion evidence section
Severity: medium
Release gate: no (pre-existing trace-truth defect; harness scenario claims
still pass, so no packet gate flips)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: Claude

## Problem

All four approved workspace-operation lanes (copy/move/rename/delete) in the
Qwen synchronized approval bank end BLOCKED on a phantom remaining target
named "by": the operation is approved, executed, and checkpointed, then
`PENDING_ACTION_OBLIGATION_RAISED {targets=[by], kind=EXPECTED_TARGETS_REMAINING}`
is raised and breached, and the turn fail-closes. Evidence:
`local/manual-testing/current-0.10.3-release-packet-20260611-125134/artifacts/qwen/sync-approval/workspace-*-approved/traces/last-trace.txt`
(same shape in the 0.10.2 packet and the wave-1 stabilization run — 12
traces total, so pre-existing, not a wave-2 regression). The scenarios only
pass because the harness claims accept a BLOCKED trace.

Causal chain:

1. Qwen's first response carries no tool call, so the missing-mutation retry
   appends the workspace-operation retry frame
   (`MissingMutationRetry.mutationRetryInstruction`, WORKSPACE_OPERATION_REQUIRED
   branch): "... Call talos.copy_path. Do not emulate move, copy, rename, or
   mkdir by writing/editing file content. ..."
2. After the retried tool call executes,
   `ExpectedTargetProgressAccounting.remainingExpectedMutationTargets`
   re-resolves the task contract from the messages; the latest user message
   is now that retry frame.
3. `TaskContractResolver`'s `SINGLE_DIRECTORY_CREATION_TARGET` pattern matches
   "mkdir by" inside the retry frame and captures "by" as a directory-creation
   target. `looksLikeDirectoryTarget` excludes only
   `a, an, the, and, to, into`, so "by" survives into `expectedTargets`.
4. The executed copy/move/rename/delete satisfies the real path targets, the
   phantom "by" remains, the obligation breaches, the turn renders BLOCKED.

## Design

- Characterization first: pin the current (correct) extraction for the four
  SynchronizedApprovalAuditMain workspace-operation prompts
  ("Use talos.copy_path to copy source.md to source-copy.md. Perform only
  that workspace operation." and the move/rename/delete siblings), then pin
  the workspace-operation retry frame to extract only the real path targets.
- Fix in `TaskContractResolver`: a `BARE_PATH_STOP_WORDS` set (articles,
  conjunctions, and common prepositions — by, to, with, into, using, from,
  of, in, on, at, for, as, via, or, onto — plus the previously excluded
  a/an/the/and) consulted by `looksLikeDirectoryTarget` (covers the
  single-directory and batch-directory capture lanes) and by the batch
  copy/move/rename destination capture. Membership is whole-token, so any
  name with a file extension or path separator ("by/2026", "with.d",
  "using.txt") never equals a stop word and still extracts.
- No change to `MutationIntent` (its directory patterns classify, they do not
  capture targets) and no change to the retry-frame wording — the contract
  layer must tolerate instructional prose in re-parsed requests regardless.

## Behavioral delta (intended)

Re-resolved contracts over workspace-operation retry frames no longer carry a
phantom "by" expected target, so approved copy/move/rename/delete retry turns
can complete instead of fail-closing BLOCKED after a successful, approved,
checkpointed operation. Bare English function words can never become expected
mutation targets from any directory/batch capture; path-like names keep
extracting unchanged.

## Architecture Metadata

Capability: task-contract expected-target extraction
Operation(s): none (contract resolution; affects pending-action obligation
accounting downstream)
Owning package/class: `dev.talos.runtime.task.TaskContractResolver`
New or changed tools: none
Risk, approval, and protected paths: n/a (approval and protected-path
behavior unchanged; this only stops a false remaining-target block after an
approved operation)
Checkpoint behavior: unchanged
Evidence obligation: expected-target progress accounting now sees only
path-like targets
Verification profile: unchanged
Repair profile: unchanged
Outcome and trace: workspace-operation retry turns stop raising
EXPECTED_TARGETS_REMAINING on "by"; no event shapes change
Allowed refactor scope: stop-word set + the two extraction guards in
TaskContractResolver only

## Tests / Evidence

- `TaskContractResolverTest`:
  - pins for the four harness workspace-operation prompts (exact
    expectedTargets sets, mutation flags);
  - the workspace-operation retry frame yields only
    {source.md, source-copy.md} (fails pre-fix with phantom "by");
  - bare stop-words after mkdir/folder phrasing are never targets
    (by/with/using/to/into);
  - stop-word lookalikes with a separator or extension still extract
    ("mkdir by/2026", "mkdir with.d");
  - a natural batch prompt whose copy destination is a bare stop-word does
    not adopt it as a target.
- `gradlew test e2eTest --no-daemon` green.

## 2026-06-11 completion evidence

- Characterization run before the fix: the four scenario-prompt pins and the
  separator/extension preservation tests passed; the three regression tests
  (retry frame, bare stop-words after directory verbs, batch stop-word
  destination) failed with the phantom "by" — reproducing all 12 packet
  traces deterministically.
- Fix: `BARE_PATH_STOP_WORDS` in `TaskContractResolver`, consulted by
  `looksLikeDirectoryTarget` and the batch destination capture. The same
  guard also covers `CurrentTurnCapabilityFrame`'s "mkdir by manually
  writing or editing file contents" wording.
- `gradlew test e2eTest --no-daemon` green (BUILD SUCCESSFUL, 2m 26s).
- CHANGELOG Unreleased entry added under Fixed.
