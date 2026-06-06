---
name: talos-work-cycle
description: Use when working in the loqj-cli/Talos repo on tickets, code, audits, installed-product tests, release gates, project progress, or backlog review unless the user explicitly says the work is outside the Talos work-test cycle.
---

# Talos Work Cycle

## Rule

Talos work is ticket-tracked, evidence-backed, and run through the project work-test cycle. A report alone is not enough when a ticket should be created, updated, moved, merged, or closed.

## Mandatory Start

For normal Talos repo work:

1. Read or re-check `AGENTS.md` and this skill for the current turn.
2. Run or inspect `git status --short`, branch, HEAD, and `talosVersion`.
3. Identify the role: implementation engineer, static code auditor, live transcript auditor, regression-test designer, ticket manager, or release/candidate reviewer.
4. Read the relevant local runbooks before acting:
   - ticket lifecycle: `work-cycle-docs/tickets/README.md` and `work-cycle-docs/tickets/open/README.md`
   - inner/candidate loop: `work-cycle-docs/work-test-cycle.md`
   - practical steps: `work-cycle-docs/work-test-cycle-step-by-step.md`
   - live audit: `work-cycle-docs/milestone-audit-workflow.md` or `work-cycle-docs/full-e2e-audit-workflow.md` when applicable
5. Inspect relevant architecture docs, source, tests, traces, prompt-debug artifacts, audit files, or reports before making claims.

## Ticket Track Discipline

- Every confirmed failure, implementation batch, audit gate, or release blocker must map to a ticket under `work-cycle-docs/tickets/open/` or `work-cycle-docs/tickets/done/`.
- Before starting implementation, create or update the relevant open ticket unless the user explicitly limits the task to analysis only.
- Before closing a ticket, verify its acceptance criteria from code, tests, audit evidence, and final state. Then rename `[Txxx-open-prio]` or `[Txxx-in-progress-prio]` to `[Txxx-done-prio]`, update body status, and move it to `done/`.
- Deferred tickets may remain in `open/` only when their body says `deferred-beyond-beta` or equivalent future-scope wording.
- If two tickets overlap, record the proposed merge in the ticket body or a report, but do not delete either unless the surviving ticket clearly covers all acceptance criteria.
- If a report finds missing ticket coverage, create or update ticket files. Do not leave the finding only in `reports/`.

## Implementation Loop

- Use TDD for feature/bug behavior changes: write a focused failing test, observe the failure, implement the smallest fix, then rerun focused tests.
- Stay in the inner loop for active coding: focused unit tests, targeted e2e only when relevant, no patch bump for every edit.
- Preserve unrelated work. Do not clean up broad architecture or generated artifacts unless required for the ticket.
- Before claiming done: review the diff, run relevant focused tests, run `git diff --check`, and state exactly what was and was not verified.

## Candidate Loop

Use the candidate loop only when the change set is ready to become versioned evidence:

1. Update `CHANGELOG.md` `Unreleased`.
2. Run `scripts/bump-patch.ps1`.
3. Build the artifact.
4. Run post-bump `.\gradlew.bat check --no-daemon`.
5. Run required E2E, coverage, quality summaries, and optional Qodana as the candidate packet demands.
6. Review evidence as belonging to that named version only.

Pre-bump `check` is a readiness signal, not candidate evidence.

## Audit Discipline

- Live audits need fresh roots, exact prompts, approvals, `/last trace`, `/prompt-debug last`, `/prompt-debug save`, provider bodies when relevant, logs, final files, diffs, and artifact canary scans.
- Approval-sensitive evidence must be synchronized/manual. Blind redirected approval input is exploratory only.
- Judge Talos from final workspace state, verifier output, traces, approvals, prompt-debug/provider-body evidence, and diffs. Treat final prose as least trusted.
- Every confirmed runtime-owned or policy-owned failure becomes a deterministic regression test or a ticket.

## Final Response Checklist

Report:

- ticket files created, updated, moved, or deliberately left unchanged;
- code/docs/reports changed;
- commands run and pass/fail;
- remaining blockers and exact next ticket move;
- confidence level and evidence source.

Do not say a ticket is complete because behavior looks better. Say it only when acceptance criteria and evidence support it.
