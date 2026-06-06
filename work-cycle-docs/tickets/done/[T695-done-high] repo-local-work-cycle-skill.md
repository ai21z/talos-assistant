# T695 - Repo-Local Work-Cycle Skill

Status: done
Severity: high
Release gate: process discipline for all Talos development/audit work
Branch: v0.9.0-beta-dev
Created/updated: 2026-06-06
Owner: unassigned

## Problem

Talos work-cycle discipline was spread across `AGENTS.md`, work-test-cycle
runbooks, ticket READMEs, and conversation instructions. That made it possible
to perform a rigorous review and still leave the actionable state outside the
ticket track.

The concrete failure shape was a current-head open-ticket review recorded as a
report without also ensuring the project-local workflow itself forced ticket
track reconciliation before future work.

## Required Behavior

- A repo-local `SKILL.md` must be visible inside the project.
- Normal Talos repo work must load and follow that skill unless the user
  explicitly says the task is outside the Talos work-test cycle.
- The skill must make ticket-track discipline explicit:
  - create or update open tickets for active work;
  - move tickets to done only when acceptance evidence is satisfied;
  - keep deferred tickets open only when explicitly marked;
  - treat reports as evidence, not as substitutes for ticket state.
- `AGENTS.md` must point to the local skill so future workers do not have to
  rediscover it from conversation history.

## Implementation

Added:

- `work-cycle-docs/skills/talos-work-cycle/SKILL.md`

Updated:

- `AGENTS.md`

The skill encodes:

- mandatory start checks;
- ticket lifecycle checks;
- inner development loop versus candidate loop;
- audit evidence requirements;
- final-response checklist.

## Evidence

Current source evidence:

- `work-cycle-docs/skills/talos-work-cycle/SKILL.md` exists and has valid
  skill frontmatter.
- `AGENTS.md` now requires loading
  `work-cycle-docs/skills/talos-work-cycle/SKILL.md` for normal Talos repo work.
- This ticket records the process fix in `work-cycle-docs/tickets/done/`
  instead of leaving it only in conversation.

Verification:

```powershell
git diff --check
```

Result: passed.

## Acceptance Criteria

- Repo-local skill exists: satisfied.
- `AGENTS.md` points to it: satisfied.
- Ticket-track discipline is explicit in the skill: satisfied.
- This process change itself is represented in the ticket track: satisfied.

## Rollback / Migration Notes

If a future project-level skill loader is added, this skill can move to that
canonical location. Until then, keep the file in `work-cycle-docs/skills/` and
keep the `AGENTS.md` pointer.
