# T751 - Work-Cycle Doctrine Codification

Status: open
Severity: medium
Release gate: no (doctrine-integrity; prerequisite for T747's encoded sequence)
Branch: codex/wave1-stability-and-cycle
Created/updated: 2026-06-10
Owner: unassigned

## Problem

Three rules the project actually practices are unwritten or written wrongly,
and one template note contradicts the bump script. Since T747 encodes the
candidate-cut sequence into a script, the doctrine must be corrected FIRST so
the script implements written rules, not folklore.

## Evidence Analysis

- Ordering contradiction: AGENTS.md "Versioned Candidate Loop" lists
  "2. Bump the patch version. 3. Update CHANGELOG.md." — but
  `work-test-cycle.md` (~89-94) and the step-by-step runbook require
  changelog-notes-in-Unreleased BEFORE the bump, and `scripts/bump-patch.ps1`
  **hard-fails** on an empty Unreleased section (lines 56-62, verified). The
  script and runbooks agree; AGENTS.md (the highest authority) has it
  backwards — a drafting slip with authority status.
- Dirty-tree downgrade rule: enforced consistently in practice
  (`lane-labeled-two-model-prompt-bank-audit-20260520.md`: "evidence is valid
  for local stabilization, not a clean versioned candidate packet"; T280/T284
  precedent; the 0.10.1 packet treated it as binding) but absent from
  AGENTS.md and all five doctrine docs — practice is stricter than doctrine.
- Stale operator prompt: `full-e2e-audit-operator-prompt.md` hardcodes
  "Branch: v0.9.0-beta-dev. Do not merge to main." — beta-dev now carries
  0.10.x and audits run from feature branches; the prompt should reference
  "the current audit branch recorded in the packet" rather than a hardcoded
  name.
- Template contradiction: `templates/evaluation-finding-ticket-template.md`
  (~200-204) says "Do not update CHANGELOG.md unless this is candidate
  closeout" — but bump-patch.ps1 requires material Unreleased content at cut
  time, so per-ticket Unreleased accumulation is the only sequence that
  works; the wave already practices it.

## Architectural Hypothesis

n/a — documentation-only ticket.

## Architecture Metadata

Capability: work-cycle doctrine documents
Operation(s): n/a
Owning package/class: `AGENTS.md`, `work-cycle-docs/work-test-cycle.md`
(cross-reference only), `work-cycle-docs/full-e2e-audit-operator-prompt.md`,
`work-cycle-docs/tickets/templates/evaluation-finding-ticket-template.md`
New or changed tools: none
Risk, approval, and protected paths: n/a
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: n/a
Refactor scope: bounded doc edits; no prompt-bank or fixture changes

## Required Behavior

1. AGENTS.md Versioned Candidate Loop: reorder to changelog-notes-in-
   Unreleased → bump → (commit) → build → mandatory post-bump check →
   summaries; keep wording minimal.
2. AGENTS.md: add the dirty-tree evidence-downgrade rule to the audit/evidence
   doctrine ("a dirty working tree downgrades live evidence to local
   stabilization grade; clean committed candidates are required for release
   packets") — codifying existing precedent.
3. Operator prompt: replace the hardcoded branch line with packet-anchored
   branch language.
4. Ticket template: amend the CHANGELOG note to require a one-line
   `## [Unreleased]` entry per behavior-changing ticket; cite bump-patch.ps1's
   validation as the reason.
5. Mention `scripts/cut-candidate.ps1` (T747) as the recommended cut path in
   the candidate-loop section (one line; T747 lands the script).

## Non-Goals

- No restructuring of AGENTS.md sections; smallest coherent edits.
- No changes to the live prompt bank or fixture definitions.

## Tests

- Docs-only; `git diff --check` clean. Existing docs tests
  (ReadmePrivacyCopyTest etc.) unaffected — verify with focused docs test run.

## Acceptance Criteria

- The four edits landed; AGENTS.md no longer contradicts bump-patch.ps1.
- T747's script steps map 1:1 to the corrected AGENTS.md sequence.
- CHANGELOG `## [Unreleased]` gains a T751 entry.

## 2026-06-11 completion evidence

- AGENTS.md Versioned Candidate Loop reordered (Unreleased notes → bump →
  commit → build-from-committed-tree), dirty-tree downgrade rule and
  tooling-only SHA rule codified, scripted cut (`scripts/cut-candidate.ps1`)
  referenced as the preferred sequence with the manual fallback kept.
- `full-e2e-audit-operator-prompt.md` branch reference packet-anchored.
- Ticket template Work-Test Cycle Notes now require a per-ticket
  `## [Unreleased]` entry for behavior-changing tickets, citing
  bump-patch.ps1's empty-section hard-fail.
- Docs validation tests green (`dev.talos.docs.*`, `dev.talos.audit.*`).
