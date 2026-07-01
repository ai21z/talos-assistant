# [done] Ticket: Talos Manual QA Constitution
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/work-test-cycle-step-by-step.md`
- `.external assistant/openclaw/qa/scenarios/index.md`
- `.external assistant/openclaw/docs/concepts/qa-e2e-automation.md`

## Why This Ticket Exists

`local/prompts/talos-manual-qa-suite.md` is useful, but it is still mostly an
incident-driven prompt list. Manual QA now needs a stable constitution: what to
test, why it matters, how to judge results, and how each finding becomes a
ticket or deterministic scenario.

## Problem

Current manual QA has several weaknesses:

- cases are not organized by user persona, mode, tool surface, and risk level
- expected outputs are not consistently phrased as pass/fail rubrics
- there is no severity taxonomy for findings
- there is no explicit mapping from manual finding to ticket to E2E scenario
- mode coverage is incomplete
- debug capture commands are not standardized

This makes regressions easy to notice but harder to compare across candidates.

## Goal

Create a living manual QA constitution that turns subjective Talos sessions into
reviewable evidence and scenario seeds.

## Scope

### In scope

- Define personas:

  ```text
  non-developer document user
  beginner website owner
  developer in a repo
  cautious user denying writes
  returning user with session history
  ```

- Define a mode/tool matrix for `auto`, `rag`, `ask`, `dev`, `chat`, and any
  modes we later keep or remove.
- Define required debug capture:

  ```text
  /debug trace
  /status --verbose
  /tools
  /prompt last
  /last trace
  ```

- Define review questions per turn:

  ```text
  What did Talos think the intent was?
  What system prompt and task contract did it receive?
  Which tools were exposed?
  Which tools were actually used?
  Did the answer rely on observed evidence or inference?
  Did it preserve natural conversation?
  Did it remain honest after partial failure?
  ```

- Define severity:

  ```text
  high: safety/trust/data loss/false completion/tool misuse
  medium: natural-flow failure, needless friction, weak recovery
  low: wording/help/debug-output polish
  ```

### Out of scope

- Implementing every scenario.
- Adding new runtime frameworks.
- Copying OpenClaw product direction.

## Proposed Work

1. Replace or extend `local/prompts/talos-manual-qa-suite.md` with a
   constitution section before the prompt cases.
2. Add stable scenario IDs and coverage tags, borrowing OpenClaw's idea of
   behavior-shaped coverage IDs without copying its multi-agent/channel product
   shape.
3. Add a "manual finding intake" template:

   - transcript path
   - workspace path
   - prompt
   - expected behavior
   - observed behavior
   - severity
   - source files likely involved
   - whether an E2E scenario should be added

4. Add review rules for when a manual prompt graduates into deterministic E2E.

## Likely Files / Areas

- `local/prompts/talos-manual-qa-suite.md`
- `local/manual-testing/qa-runs/`
- `work-cycle-docs/tickets/open/`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

No code test is required for the document itself. Verification is a dry run:

1. Run one manual QA session using the constitution.
2. Confirm the transcript includes required debug artifacts.
3. Confirm every finding maps to either:
   - an existing ticket
   - a new ticket
   - a "no issue" note with rationale

## Acceptance Criteria

- Manual QA has a stable written rubric.
- New prompts can be added without losing the purpose of older cases.
- Findings are consistently categorized by priority.
- Every high-priority manual failure has a ticket and an E2E scenario plan.
- The document explicitly distinguishes user-like testing from machine-like
  protocol probing.

## Resolution Notes

`local/prompts/talos-manual-qa-suite.md` now includes the manual QA
constitution: personas, debug frame, per-turn review questions, severity
taxonomy, finding intake template, promotion rule, stable `QA-###` case IDs,
coverage tags, and a dev-mode natural-list case.

Verification:

```powershell
rg "QA-[0-9]{3}|Severity Taxonomy|Finding Intake Template|Promotion Rule" local/prompts/talos-manual-qa-suite.md
```
