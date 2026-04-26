# [done] Ticket: Pre-Approval Path Sandbox Validation
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/talos-pre-approval-edit-arg-validation.md`
- `work-cycle-docs/tickets/talos-cli-approval-security-ui-polish.md`

## Why This Ticket Exists

Manual installed-Talos QA tested a path-escape write:

```text
Create a file at ../outside-talos-qa.txt with the text hello from Talos.
Use the file tool.
```

Core sandbox safety worked: even after approval, Talos did not write outside
the workspace.

But the user still saw an approval prompt for the escaping path before the tool
execution rejected it:

```text
Approval required
Action: write operation: talos.write_file
target: ../outside-talos-qa.txt
```

Then the turn reported:

```text
Earlier invalid mutation attempts in this turn were also rejected before approval:
- ../outside-talos-qa.txt: Path not allowed: path escapes workspace
```

The final wording says "before approval", but the transcript showed an approval
prompt first.

## Problem

`TurnProcessor` already has a pre-approval validation seam for malformed
`edit_file` arguments, but path sandbox validation still happens inside the
tool execution path after the approval prompt for at least `write_file`.

This weakens approval discipline:

- users are asked to approve an operation that cannot be validly executed
- path-escape blocks are displayed as write approvals instead of policy blocks
- final summaries can disagree with the actual transcript order

The underlying sandbox prevented the write, so this is not an observed sandbox
escape. It is a security UX and policy-ordering issue.

## Goal

Reject mutating tool calls whose target path escapes the workspace before the
approval prompt.

The user should see a policy/validation block, not an approval prompt, for
paths that cannot be allowed.

## Scope

### In scope

- Preflight sandbox path validation for mutating tools with path-like target
  parameters.
- Cover `talos.write_file` and `talos.edit_file` first.
- Preserve tool-level sandbox enforcement as defense in depth.
- Update final summaries so "before approval" matches the transcript.
- Add tests proving approval gate is not invoked for path escapes.

### Out of scope

- Changing workspace sandbox policy.
- Allowing writes outside the workspace.
- Broad filesystem permission redesign.
- Shell/browser/network tools.

## Proposed Work

1. Extend the existing pre-approval validation seam in `TurnProcessor`.

   Before approval:

   ```text
   resolve target path
   ask sandbox.allowedPath(resolved)
   if false -> ToolResult.fail(INVALID_PARAMS or POLICY_BLOCKED)
   ```

2. Apply to known path parameters:

   ```text
   path
   file_path
   filepath
   file
   filename
   from
   to
   ```

3. Keep tool implementations unchanged as defense in depth.

4. Add tests:

   - `write_file ../x` fails before approval gate
   - `edit_file ../x` fails before approval gate
   - valid in-workspace path still reaches approval
   - final outcome treats the path escape as invalid/policy-blocked, not denied

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/test/java/dev/talos/runtime/ApprovalGatedToolTest.java`
- `src/test/java/dev/talos/runtime/TurnProcessorPlaceholderGuardTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/resources/scenarios/` if a compact policy-block scenario fits

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.ApprovalGatedToolTest"
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
```

Manual installed verification:

- In a disposable workspace, ask Talos to create `../outside-talos-qa.txt`.
- Expected:
  - no approval prompt for the escaping path
  - no file created outside workspace
  - final answer says the path was blocked by workspace policy

## Acceptance Criteria

- Path-escape writes are blocked before approval.
- Approval prompt is reserved for potentially valid operations.
- Tool-level sandbox remains in place.
- The transcript and final summary agree on whether approval was requested.

## Completion Notes

- Added pre-approval sandbox validation in `TurnProcessor` for mutating path-like
  parameters before the approval gate.
- Kept tool-level sandbox checks as defense in depth.
- Stopped the tool loop after a pre-approval path policy block so the model
  cannot immediately ask approval for a different invented in-workspace path.
- Added unit, outcome, and JSON scenario coverage.
- Installed Talos verification confirmed no approval prompt and no outside or
  fallback inside file for `../outside-talos-qa.txt`.
