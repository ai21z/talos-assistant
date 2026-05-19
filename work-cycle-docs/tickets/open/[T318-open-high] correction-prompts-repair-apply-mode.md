# T318 - Correction Prompts Should Enter Apply Mode After Incomplete User-Observed Mutation

Status: fixed in working tree for narrow styling complaints; broader repair prompts remain open
Severity: high
Release gate: yes for iterative workspace editing
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The transcript included this correction after Talos wrote only `index.html`:

```text
But you just changed the index and reduced it. You never put any style in the index
```

Talos classified the turn as read-only, inspected `index.html` and missing `style.css`, then stopped by failure policy.

## Root Cause

Existing repair inheritance required the previous assistant response to contain an incomplete/static-verification failure marker. In this case the previous assistant reported generic write/readback success, so the user's correction complaint could not inherit the prior mutation contract.

## Fix Direction

Narrowly recognize styling/correction complaints after a prior mutation-allowed user turn and inherit that prior mutation contract.

## Tests

Added:

- `TaskContractResolverTest.missingStylingCorrectionAfterSiteMutationInheritsApplyCapableContract`
- `TaskContractResolverTest.readOnlyQuestionAboutTxtAfterSiteDiscussionStaysReadOnly`

Focused evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
```

Result: passed.

## Remaining Work

Broaden correction handling carefully only with new failing examples. Do not turn ordinary complaints, questions, or status checks into mutation-capable turns unless previous mutation context and correction language are both present.

