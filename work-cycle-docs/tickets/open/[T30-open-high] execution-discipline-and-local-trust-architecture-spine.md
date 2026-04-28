# [T30-open-high] Ticket: Execution Discipline And Local Trust Architecture Spine
Date: 2026-04-28
Priority: high
Status: open
Architecture references:
- `docs/architecture/01-execution-discipline-and-local-trust.md`
- `work-cycle-docs/tickets/new-work.md`

## Context

After 0.9.6, Trust and Policy Boundary Stabilization is closed. Talos now has
TaskContract, phase policy, approval gates, compact trace, static verification,
and deterministic scenario coverage. Older architecture notes still contain
valuable doctrine, but some statements about missing TaskContract or missing
phase machinery are stale.

## Goal

Maintain the canonical post-0.9.6 architecture spine for Execution Discipline
and Local Trust Infrastructure.

## Non-Goals

- Do not implement runtime behavior.
- Do not start policy extraction.
- Do not change versioning or changelog files.
- Do not use this ticket to introduce shell, browser, MCP, or multi-agent work.

## Implementation Notes

- Keep `docs/architecture/01-execution-discipline-and-local-trust.md` as the
  source of truth for this milestone.
- Keep `work-cycle-docs/tickets/new-work.md` as historical context with a clear
  stale-context note.
- Add or maintain a small README pointer if helpful.

## Acceptance Criteria

- `docs/architecture/01-execution-discipline-and-local-trust.md` exists.
- `work-cycle-docs/tickets/new-work.md` states that post-0.9.6 TaskContract and
  phase machinery already exist.
- README links to the architecture doc if appropriate.
- No runtime behavior changes are included.
- `./gradlew.bat test --no-daemon` passes.

## Tests / Evidence

Run:

```powershell
./gradlew.bat test --no-daemon
```

Review:

```powershell
git diff -- docs/architecture work-cycle-docs/tickets/new-work.md README.md
```

## Work-Test Cycle Notes

Use the inner dev loop. This is a docs and roadmap ticket only.

## Known Risks

- Overwriting historical doctrine would lose useful context. Add correction
  notes instead of deleting the old vision.
