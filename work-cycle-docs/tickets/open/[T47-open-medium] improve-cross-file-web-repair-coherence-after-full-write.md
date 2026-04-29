# [T47-open-medium] Ticket: Improve Cross-File Web Repair Coherence After Full Write
Date: 2026-04-29
Priority: medium
Status: open
Architecture references:
- `docs/architecture/06-bounded-repair-controller.md`
- `work-cycle-docs/tickets/done/[T44-done-medium] improve-live-bmi-repair-after-bounded-repair-v1.md`

## Why This Ticket Exists

T44 improved bounded web repair behavior: after static verification failure,
Talos now plans complete `write_file` replacements for small HTML/CSS/JS repair
targets and continues the bounded repair instead of stopping after one planned
write.

The installed qwen manual check still ended with static verification failure
after the model rewrote all three files. The remaining issue was not tool
policy or boundedness; it was cross-file coherence:

- HTML still did not link `scripts.js`.
- JavaScript referenced IDs that were absent from HTML.
- Static verification correctly reported the task incomplete.

## Problem

The repair prompt tells the model to use complete file replacements, but it does
not yet strongly force the three rewritten files to agree with each other before
the model emits tool calls.

## Goal

Improve small web repair guidance so full-file replacement plans explicitly
require cross-file coherence:

- HTML links the CSS and JS files being written.
- HTML defines every ID used by JavaScript.
- JavaScript uses IDs that exist in HTML.
- CSS selectors correspond to HTML structure where practical.
- The final answer remains truthful if the model still fails.

## Non-Goals

- No browser execution.
- No shell execution.
- No unbounded repair loop.
- No LLM classifier.
- No bypass of approval, permission, checkpoint, or phase policy.

## Implementation Notes

Likely areas:

- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/e2eTest/resources/scenarios/`

Keep this as a guidance/static-verification refinement. Do not turn it into a
browser/runtime execution verifier.

## Acceptance Criteria

- Full-file web repair instructions explicitly require HTML/CSS/JS cross-file
  agreement.
- Deterministic scenarios cover a model rewriting all three files with ID/link
  mismatches and Talos reporting the exact remaining problems.
- A passing scenario proves coherent rewritten HTML/CSS/JS can verify.
- Manual qwen BMI repair is improved or remains truthfully bounded with exact
  static failures.

## Tests / Evidence

- Focused repair policy tests for cross-file coherence guidance.
- Static verifier tests for ID/link mismatch if coverage is missing.
- E2E scenario for incoherent full-file repair.
- Installed Talos manual prompt check with qwen.

## Work-Test Cycle Notes

Use the standard inner dev loop. This ticket is not a candidate/version bump by
itself.

## Known Risks

- Overly prescriptive prompt text may reduce model flexibility for non-BMI web
  tasks.
- Static checks must remain deterministic and not pretend to prove browser
  runtime behavior.
