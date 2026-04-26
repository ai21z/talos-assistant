# [done] Ticket: Mutation Intent Guard For Read-Only Turns

Date: 2026-04-23
Branch context: fix/ticket-talos-auto-mutation-guard
Status: done

## Problem

Talos in `auto` / unified mode can still execute the first step of an
unsolicited mutation path on a read-only prompt.

Observed transcript shape:

1. User asks a read-only question:
   `hey can you tell me what is in this workspace?`
2. Model correctly calls `talos.list_dir`.
3. Model may correctly call `talos.read_file` on obvious files.
4. Model then drifts into an unsolicited `talos.edit_file` or `talos.write_file`
   even though the user never requested a change.
5. Approval gate blocks the write if the user says `n`.

The recent fix closed the second-half failure:
- no false missing-mutation retry from synthetic tool-result text
- no raw JSON leak from retry text-fallback

But the first-half failure still exists:
- the runtime still allows the initial mutating tool call to reach approval
  even when the turn is clearly observational.

## Root Cause

This is both model drift and runtime policy weakness.

- Model side:
  local coder models can opportunistically "improve" content after inspection.
- Runtime side:
  unified mode exposes mutating tools and currently has no turn-level guard
  requiring explicit user mutation intent before mutating tools may run.

Today, Talos protects the workspace at the approval boundary, but not at the
intent boundary.

## Why This Matters

Approval is necessary but insufficient here.

Without an intent guard:
- Talos still startles the user with unsolicited edit/write approvals
- read-only questions can look unsafe or untrustworthy
- prompt tuning remains the only thing discouraging mutation drift
- behavior stays model-sensitive and unstable across local models

## Desired Behavior

For a clearly read-only turn:
- read/list/grep/retrieve may execute as needed
- `edit_file` / `write_file` must be rejected before approval
- the model should receive a precise error indicating that the user did not ask
  for a modification on this turn
- the final answer should remain read-only and grounded

For an explicitly mutating turn:
- current edit/write behavior should continue unchanged

## Proposed Solution

Add a runtime mutation-intent gate for mutating tools.

### Option A: enforce in `TurnProcessor.executeTool(...)`

Before approval for mutating tools:
- inspect the original user request captured for the turn
- determine whether the request contains explicit mutation intent
- if not, reject `talos.write_file` / `talos.edit_file` with a targeted error

This is the strongest option because it protects all call sites.

### Option B: enforce in tool-call execution stage

Before calling `turnProcessor.executeTool(...)` for mutating tools:
- inspect the original user request in loop state / capture
- short-circuit mutating calls when the turn is observational

This is workable, but weaker than guarding in `TurnProcessor`.

## Recommendation

Prefer Option A in `TurnProcessor.executeTool(...)`.

Reason:
- central enforcement point
- applies equally to native and text tool-call paths
- easier to reason about than scattered mode-level prompt rules
- aligns with other runtime safety controls already centralized there

## Open Design Questions

1. What should count as mutation intent?
   - reuse `looksLikeMutationRequest(...)`
   - or create a stricter/shared runtime predicate

2. Should the guard use only the original user prompt?
   - recommended: yes
   - do not infer mutation intent from assistant/tool messages

3. Should approval-denied turns permit a later explicit mutation?
   - only if the user explicitly asks again in a new turn

4. Should there be a special-case escape hatch for explicit commands?
   - probably yes for slash/debug/internal harness paths only

## Risks

- false negatives if the mutation-intent detector is too narrow
- false positives if vague phrasing is treated as edit permission
- duplicate logic if CLI/mode layer and runtime layer each build their own
  interpretation

## Test Plan

### Unit / integration

- read-only prompt + attempted `edit_file` -> mutating tool rejected
- read-only prompt + attempted `write_file` -> mutating tool rejected
- explicit edit prompt + `edit_file` -> allowed path proceeds to approval
- explicit create prompt + `write_file` -> allowed path proceeds to approval

### E2E

Add a dedicated scenario for:
- read-only workspace question
- scripted responses: `list_dir` -> `read_file` -> unsolicited `edit_file`
- expected result:
  - no file mutation
  - no approval prompt
  - final answer remains descriptive

## Acceptance Criteria

- Talos no longer asks approval for unsolicited mutations on clearly read-only turns
- explicit mutation requests still work
- behavior is stable for both native and text tool-call paths
- an E2E scenario covers the exact regression shape
