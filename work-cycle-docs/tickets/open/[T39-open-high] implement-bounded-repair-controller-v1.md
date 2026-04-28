# [T39-open-high] Ticket: Implement Bounded Repair Controller V1
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- T38 bounded repair controller design ticket

## Context

Current repair behavior includes static verification context and loop stop
policies, but repair is not yet owned by a dedicated policy/controller. A v1
repair controller should reduce blind retry loops while keeping final answers
truthful.

## Goal

Implement bounded repair strategy using existing `StaticVerificationRepairContext`
and `ToolCallLoop` seams.

## Non-Goals

- Do not add shell/browser execution.
- Do not add multi-agent repair.
- Do not bypass approval, permission, checkpoint, or phase policies.
- Do not claim runtime/browser validation from static checks.

## Implementation Notes

- Avoid blind retry loops.
- A failed static verification can produce one bounded repair plan.
- Repeated failures stop cleanly.
- Verifier findings should be passed into repair.
- Final answer must remain truthful.
- Prefer small policy/controller classes over adding more branching to
  `AssistantTurnExecutor`.

## Acceptance Criteria

- No blind retry loops.
- Failed static verification can produce one bounded repair plan.
- Repeated failures stop cleanly.
- Successful repair is verified before being reported complete.
- Failed repair reports remaining issues precisely.
- Final answer remains truthful.
- Tests cover successful repair, failed repair, and no-progress stop.
- Manual Talos check covers a broken small web app repair flow.

## Tests / Evidence

Run focused repair/controller tests first, then:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

Manual installed Talos verification is required.

## Work-Test Cycle Notes

Use the inner dev loop while implementing. This is runtime-sensitive and should
not begin until T38 is complete.

## Known Risks

- Repair controller work can become large. Keep v1 bounded to post-static
  verification failure and invalid edit/no-progress loops.
- Repair after verification failure still depends on model quality; the harness
  must preserve truthful partial/failed outcomes.
