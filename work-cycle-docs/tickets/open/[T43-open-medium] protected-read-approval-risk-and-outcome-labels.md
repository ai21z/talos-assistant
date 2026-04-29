# [T43-open-medium] Ticket: Protected Read Approval Risk and Outcome Labels
Date: 2026-04-29
Priority: medium
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `docs/architecture/04-declarative-allow-ask-deny-permissions.md`
- `docs/architecture/03-local-turn-trace-model-v1.md`
- `work-cycle-docs/tickets/done/[T41-done-high] manual-prompt-evaluation-before-0.9.7-candidate.md`

## Why This Ticket Exists

T41 manual testing confirmed protected `.env` reads ask for approval and do not
leak content when denied. The behavior is safe, but the user-facing labels and
trace outcome are confusing.

Observed prompt:

```text
Read .env and tell me what it says.
```

Observed approval dialog:

```text
Action: read only operation: talos.read_file
Risk:   write
```

After denial, the final answer said it could not read the file, but the local
trace rendered:

```text
Outcome: COMPLETE (READ_ONLY_ANSWERED)
```

## Problem

Protected read approval is safe, but the risk label says `write`, and denied
read-only tool calls can render as completed read-only answers in the local
trace. That weakens trust in the trace and approval UX.

## Goal

Protected reads should show an accurate sensitive-read risk/category, and
approval-denied read turns should be classified as blocked/not completed rather
than complete.

## Scope

In scope:
- Approval dialog risk text for protected read tools.
- Turn outcome/trace classification for denied read-only tool calls.
- Tests covering protected-read denial.

Out of scope:
- Changing protected path defaults.
- Allowing protected reads without approval.
- Permission UI redesign.

## Proposed Work

- Review `ToolRiskLevel`, `PermissionDecision`, and approval rendering for
  read-only protected paths.
- Add or adjust an outcome classification for approval-denied read-only turns.
- Ensure trace and `/last trace` show blocked/denied instead of complete.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/policy/`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/repl/slash/ExplainLastTurnCommand.java`
- tests under `src/test/java/dev/talos/`

## Test / Verification Plan

- Unit test for protected read approval metadata.
- Turn/executor test for denied `read_file .env`.
- Manual installed Talos check with denied `.env` read.

## Acceptance Criteria

- Protected read approval no longer displays `Risk: write`.
- Denied protected read does not reveal file content.
- Trace/outcome does not report the turn as complete/read-only answered.
- Existing protected mutation denial still denies before approval.
