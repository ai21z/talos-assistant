---
name: talos-work-cycle
description: Use when working in the loqj-cli/Talos repo on tickets, code, audits, installed-product tests, release gates, project progress, or backlog review unless the user explicitly says the work is outside the Talos work-test cycle.
---

# Talos Work Cycle (registered entrypoint)

This is the discoverable entrypoint for the Talos work-test cycle. The authoritative full skill and the runbooks it references live in the repo and are the single source of truth:

- Canonical skill: `work-cycle-docs/skills/talos-work-cycle/SKILL.md`
- Project doctrine (highest authority): `AGENTS.md`

Read both for the current turn before acting. If this file and `AGENTS.md` conflict, `AGENTS.md` wins.

## Mandatory start
1. Read or re-check `AGENTS.md` and `work-cycle-docs/skills/talos-work-cycle/SKILL.md`.
2. Inspect `git status --short`, branch, HEAD, and `talosVersion`.
3. Identify the role: implementation engineer, static auditor, live transcript auditor, regression-test designer, ticket manager, or release reviewer.
4. Read the relevant runbooks before acting:
   - tickets: `work-cycle-docs/tickets/README.md` and `work-cycle-docs/tickets/open/README.md`
   - loops: `work-cycle-docs/work-test-cycle.md`, `work-cycle-docs/work-test-cycle-step-by-step.md`
   - audits: `work-cycle-docs/milestone-audit-workflow.md` or `work-cycle-docs/full-e2e-audit-workflow.md`
5. Inspect source, tests, traces, prompt-debug artifacts, and reports before making claims.

## Core rules
- Every confirmed failure, implementation batch, audit gate, or release blocker maps to a ticket under `work-cycle-docs/tickets/open/` or `done/`.
- Use TDD for behavior changes. Stay in the inner loop for active coding (focused tests, no per-edit version bump).
- Run the candidate loop only when a change set is ready to become versioned evidence: update `CHANGELOG.md` Unreleased, run `scripts/bump-patch.ps1`, build, run post-bump `.\gradlew.bat check --no-daemon`, then the packet evidence. Pre-bump `check` is a readiness signal, not candidate evidence.
- A fluent final answer is not proof. Judge from final workspace state, verifier output, traces, approvals, prompt-debug, provider bodies, and diffs.
- Do not say a ticket is complete because behavior looks better. Say it only when acceptance criteria and evidence support it.

For the full discipline (ticket lifecycle detail, audit discipline, final-response checklist), read the canonical skill file linked above.
