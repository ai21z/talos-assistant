# [done] Ticket: Talos Auto Mutation Guard

Date: 2026-04-23
Branch: fix/ticket-talos-auto-mutation-guard
Status: done

## Problem

A read-only workspace question in auto/unified mode drifted into an unsolicited
`talos.edit_file` call. Approval correctly blocked the mutation, but the
text-tool-call loop appended synthetic tool results as `user` messages.

The duplicate-edit B3 diagnostic contains `replace the entire file content`.
Because `AssistantTurnExecutor.latestUserRequest(...)` walked backward to the
latest `role=user` message without skipping synthetic tool results,
`looksLikeMutationRequest(...)` matched `replace the` and fired the
missing-mutation retry even though the real user prompt was read-only.

The retry then emitted text JSON for `talos.write_file`. The retry path only
checked native `retry.hasToolCalls()`, so the text JSON was returned to the UI
instead of being executed or stripped.

## Fix Scope

- Make latest-user-request lookups ignore synthetic `[tool_result: ...]` user
  messages on the text fallback path.
- Add defense-in-depth so mutation intent detection ignores synthetic
  tool-result content directly.
- Make missing-mutation retry handle text-fallback tool calls the same way the
  normal executor path does.
- Strip text-fallback tool calls before returning retry text when no executable
  tool-call branch runs.

## Regression Shape

Read-only prompt -> text fallback tool loop -> denied edit -> duplicate edit
diagnostic containing `replace the` -> iteration cap -> mutation retry must not
fire from the synthetic diagnostic.
