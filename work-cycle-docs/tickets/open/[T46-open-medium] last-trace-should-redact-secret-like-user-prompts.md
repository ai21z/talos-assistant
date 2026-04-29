# [T46-open-medium] Ticket: Last Trace Should Redact Secret-Like User Prompts
Date: 2026-04-29
Priority: medium
Status: open
Architecture references:
- `docs/architecture/03-local-turn-trace-model-v1.md`
- `docs/architecture/04-declarative-allow-ask-deny-permissions.md`
- `work-cycle-docs/tickets/done/[T33-done-high] implement-local-turn-trace-model-v1.md`
- `work-cycle-docs/tickets/done/[T41-done-high] manual-prompt-evaluation-before-0.9.7-candidate.md`

## Context

During the 0.9.7 candidate smoke, protected `.env` mutation was correctly denied
before approval and `.env` remained unchanged.

Prompt:

```text
Overwrite .env with SECRET=changed. Use talos.write_file.
```

The local trace section did not expose `.env` contents, but `/last trace`
displayed the raw user request in the human-readable `User Request` section.
That can echo secret-like values supplied by the user.

## Goal

`/last trace` should avoid displaying raw secret-like user prompt content by
default, especially when protected paths or `KEY=value`-style secret material
appears in the request.

## Non-Goals

- Do not remove user-request visibility entirely if a safe redacted summary is
  available.
- Do not change local trace full/debug opt-in behavior without an explicit
  design update.
- Do not weaken protected-path denial.

## Implementation Notes

- Review the `/last trace` rendering path and the local trace redaction policy.
- Reuse or extend existing redaction helpers instead of adding ad hoc string
  cleanup.
- Candidate redactions:
  - `SECRET=changed` -> `SECRET=[redacted]`
  - token-like values -> `[redacted]`
  - protected path payload previews -> hash/count metadata only

## Acceptance Criteria

- `/last trace` does not display raw `KEY=value` secret-like payloads from user
  prompts by default.
- Protected path mutation/read denials still show enough context to debug the
  policy decision.
- Explicit opt-in debug/full trace behavior remains clearly marked if full
  content is ever shown.
- Tests cover protected `.env` prompt rendering.

## Tests / Evidence

- Add unit coverage for `/last trace` rendering redaction.
- Add manual installed Talos check with a protected `.env` mutation denial.

## Work-Test Cycle Notes

Use the inner dev loop. This ticket is not part of the 0.9.7 candidate
closeout.

## Known Risks

- Over-redaction can make traces hard to debug. Preserve path and policy reason
  metadata while redacting only sensitive values.
