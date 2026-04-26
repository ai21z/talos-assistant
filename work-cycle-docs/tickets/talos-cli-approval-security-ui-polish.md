# [done] Ticket: CLI Approval/Security UI Polish
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- docs/new-architecture/30-cli-ui-output-architecture-audit.md
- work-cycle-docs/tickets/new-work.md

## Why This Ticket Exists
Approval prompts are safety-critical UI. They should clearly show the action,
risk, details, and available choices without weakening existing approval
policy.

## Problem
The prompt was safe but sparse, and it used Unicode markers that were not ideal
for dumb/non-interactive terminal transcripts.

## Goal
Keep approval behavior unchanged while making the prompt clearer and ASCII-safe.

## Scope
In scope:
- Approval prompt text.
- Action, inferred risk, details, choices.
- ASCII-safe warning/detail markers.

Out of scope:
- Approval policy changes.
- New risk model in the tool descriptor.
- Auto-approval behavior changes.

## Proposed Work
- Update `CliApprovalGate` rendering.
- Keep `Allow?` prompt compatibility.
- Make approval detail markers ASCII-safe.

## Likely Files / Areas
- `src/main/java/dev/talos/runtime/CliApprovalGate.java`
- `src/main/java/dev/talos/runtime/TurnProcessor.java`
- `src/test/java/dev/talos/runtime/CliApprovalGateTest.java`

## Test / Verification Plan
- Focused approval gate and runtime approval tests.
- Full `test`.
- Full `e2eTest`.
- Installed CLI denial run in `local/playground/horror-synth-site`.

## Acceptance Criteria
- Approval prompt shows action, risk, details, and choices.
- Denial still prevents writes.
- Existing approval responses still work.
- Installed transcript has no replacement characters.
