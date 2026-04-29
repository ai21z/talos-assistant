# [T44-open-medium] Ticket: Improve Live BMI Repair After Bounded Repair v1
Date: 2026-04-29
Priority: medium
Status: open
Architecture references:
- `docs/architecture/06-bounded-repair-controller.md`
- `work-cycle-docs/tickets/done/[T39-done-high] implement-bounded-repair-controller-v1.md`
- `work-cycle-docs/tickets/done/[T41-done-high] manual-prompt-evaluation-before-0.9.7-candidate.md`

## Why This Ticket Exists

T41 manual testing showed bounded repair v1 is truthful and traceable, but live
qwen still failed to complete a simple broken BMI repair. Talos planned repair,
included verifier findings, required approval, created checkpoints, and did not
overclaim completion. The remaining issue is repair competence.

## Problem

After static verification failure, the model still preferred narrow `edit_file`
changes and did not apply the verifier findings to repair `scripts.js`, missing
script links, form inputs, or duplicate IDs. The second repair turn made another
partial edit and verification still failed.

## Goal

Improve bounded repair so small web files are more likely to be repaired with
complete `write_file` replacements when verifier findings show broad structural
gaps or repeated brittle edits.

## Scope

In scope:
- Repair policy prompt/plan refinement.
- Stronger write-file preference for small HTML/CSS/JS files after static web
  verification failure.
- Tests proving verifier findings lead to bounded full-file repair guidance.

Out of scope:
- Browser execution.
- Shell execution.
- Unbounded autonomous retry loops.
- LLM classifier for repair decisions.

## Proposed Work

- Review `RepairPolicy` and `StaticVerificationRepairContext` prompts.
- Add deterministic conditions for small web repair to prefer full-file writes.
- Consider a stronger stop/downgrade when the model performs another narrow
  edit that does not address verifier findings.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/repair/RepairPolicyTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit tests for small web static failure producing full-write repair guidance.
- E2E scenario with failed verifier findings and repair follow-up.
- Manual installed Talos BMI repair prompt with qwen.

## Acceptance Criteria

- Repair plan still remains bounded.
- Verifier findings are preserved in repair context.
- Small web repair prompts strongly prefer `write_file` for complete corrected
  HTML/CSS/JS files.
- Final answer remains truthful if repair still fails.
- No read-only/privacy/status boundary regressions.
