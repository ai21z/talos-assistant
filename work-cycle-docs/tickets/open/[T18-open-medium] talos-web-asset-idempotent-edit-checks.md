# [open] Ticket: Web Asset Edits Should Be Idempotent
Date: 2026-04-27
Priority: medium
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-minimal-failure-policy.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/open/[T16-open-high] talos-web-app-static-verifier-v0.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed Talos inserting duplicate stylesheet links by repeatedly
editing around the same anchor:

```html
<link rel="stylesheet" href="styles.css">
<link rel="stylesheet" href="styles.css">
<link rel="stylesheet" href="styles.css">
```

The repeated edit technically succeeded, but it made the file worse.

## Problem

After a successful edit, the same semantic anchor may still exist inside the
new content. A model can repeat the same edit and duplicate assets, scripts, or
DOM elements. The current runtime can report the edit as successful even though
the semantic result is not idempotent.

## Goal

Detect and prevent or downgrade obvious duplicate web-asset mutations.

## Scope

### In scope

- Detect duplicate identical stylesheet links.
- Detect duplicate identical script tags.
- Detect duplicate IDs in simple HTML files.
- Surface duplicate-web-asset problems in verification results.
- Consider loop-level detection for repeated successful edits to the same
  semantic anchor when practical.

### Out of scope

- Full DOM parser dependency.
- Browser validation.
- Blocking legitimate repeated CSS selectors.

## Proposed Work

1. Add duplicate asset checks to the web-app verifier.
2. Add tests around duplicate `<link href="styles.css">` and
   `<script src="scripts.js">`.
3. Consider whether `ToolCallExecutionStage` should flag repeated semantic
   insertions during the same turn.
4. Ensure final answer cannot call a task complete when duplicate assets remain.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallExecutionStage.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for duplicate stylesheet/script/id detection.
- E2E scenario where the model repeats a stylesheet insertion.
- Confirm duplicate detection appears in the final answer.

## Acceptance Criteria

- Duplicate identical stylesheet links fail web-app static verification.
- Duplicate identical script tags fail web-app static verification.
- Duplicate HTML IDs are flagged.
- The task is not marked complete while these duplicates remain.
