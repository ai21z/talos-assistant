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

T67 audit update, 2026-05-01:

- Summary:
  `local/manual-testing/t67-audit-20260501-143927/summary.md`
- Recovered session:
  `%USERPROFILE%/.talos/sessions/8d5e5c90b2f8140e09e5d7247d210c1cc1718331.turns.jsonl`
- Prompt:
  `Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js. It should calculate BMI from height and weight.`
- Turn 21 (`trc-31a74e56-b4f1-42e3-b781-32d97bac07b8`) classified
  `FILE_CREATE` but made no tool calls.
- Turn 22 (`trc-04fa73dc-d044-4498-9fc3-7fc8aec9d554`) wrote
  `index.html`, `styles.css`, and `scripts.js`, but verification reported
  `web coherence could not be checked because the workspace does not expose a
  small HTML/CSS/JS surface`.
- The final files were incoherent: `scripts.js` referenced `bmiForm`, `height`,
  and `weight`, while `index.html` did not define those elements.
- Follow-up repair prompts in turns 23-24 did not correct the artifact.

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

- `src/main/java/dev/talos/runtime/capability/StaticWebCapabilityProfile.java`
- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/e2eTest/resources/scenarios/`

Keep this as a guidance/static-verification refinement. Do not turn it into a
browser/runtime execution verifier.

T62 update, 2026-05-02:

- Static Web profile ownership now exists.
- T47 should refine `StaticWebCapabilityProfile` plus its verifier/repair
  adapters, not add broad BMI/web prompt text to generic turn-control code.
- Cross-file coherence acceptance should stay deterministic: HTML links the
  selected CSS/JS assets, JavaScript IDs exist in HTML, and CSS selectors match
  HTML structure where practical.

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
