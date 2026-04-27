# [open] Ticket: Readback Passed Must Not Mean Task Verified
Date: 2026-04-27
Priority: high
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-outcome.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/done/talos-static-verifier-web-app-scope-and-wording.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed Talos saying:

```text
Static verification: passed - Target/readback checks passed for 1 mutated
target(s); no task-specific static verifier was applicable.
```

But the mutated file was a placeholder `scripts.js`, or only one file was
updated for a multi-file BMI calculator task. The filesystem write/readback
passed; the task did not.

## Problem

The current wording lets a user interpret "Static verification: passed" as
"the requested task is complete." That is false when no task-specific verifier
ran or when the verifier only checked that a target file exists and is readable.

This undermines the central truthfulness goal of `TaskOutcome`.

## Goal

Separate file-level mutation verification from task-completion verification in
both internal outcome status and user-visible wording.

## Scope

### In scope

- Change wording for readback-only verification.
- Introduce or use outcome status that distinguishes:
  - file/readback passed,
  - task-specific verification passed,
  - task-specific verification failed,
  - task completion not verified.
- Prevent "Static verification: passed" wording when no task-specific verifier
  was applicable.
- Add tests for final answer text.

### Out of scope

- Implementing every task-specific verifier.
- Browser execution.
- Runtime JS execution.

## Proposed Work

1. Update `TaskVerificationResult` and/or `ExecutionOutcome` rendering so
   readback-only success is worded as:

   ```text
   File write/readback passed. No task-specific verifier was applicable, so
   task completion was not verified.
   ```

2. Reserve "task verified" or "static verification passed" language for cases
   where task-specific checks actually ran.
3. Ensure partial mutations remain clearly partial.
4. Add assertions in unit/E2E tests against misleading wording.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/TaskVerificationResult.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`

## Test / Verification Plan

- Focused verification rendering tests.
- E2E scenario where a valid file write has no task verifier.
- E2E scenario where a task-specific verifier fails.
- Confirm final answers do not overclaim completion.

## Acceptance Criteria

- Readback-only success does not say "Static verification: passed".
- The final answer clearly says task completion was not verified.
- Task-specific verifier success can still report verification passed.
- Existing partial/failure truth checks remain intact.
