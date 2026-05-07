# T206 - Conditional No-Change Summary Must Separate Tool Reads From Runtime Verification

Status: done
Severity: medium

## Problem

The focused T205 audit showed a truthfulness/trace wording flaw in conditional review/fix no-change output.

In the Qwen run, the model read `index.html` and stale sibling `script.js`, while runtime static verification validated the current web target set and reported no blocker for `index.html`, `styles.css`, and `scripts.js`.

The final runtime-owned answer said:

```text
Talos inspected the current workspace files ...
Checked files: index.html, styles.css, scripts.js.
```

That is too imprecise. It can imply the model inspected files it did not actually read. The runtime did validate those files, but that is a different evidence source.

## Evidence

Focused audit:

`local/manual-testing/llama-cpp-t205-focused-re-audit-20260507-211437/FINDINGS-LLAMA-CPP-T205-FOCUSED-RE-AUDIT.md`

Code path:

- `src/main/java/dev/talos/runtime/policy/ConditionalReviewFixPolicy.java`
- `ConditionalReviewFixPolicy.noChangeAnswerIfCurrentWorkspacePasses(...)`
- `ConditionalReviewFixPolicy.deterministicNoChangeAnswer(...)`

## Scope

- Make the deterministic conditional no-change answer distinguish:
  - tool-read files from this turn,
  - runtime verifier checked files.
- Do not claim the model inspected files it did not read.
- Preserve the existing safety behavior:
  - only emit no-change when inspection-only evidence exists,
  - no mutation tool succeeded,
  - current static web diagnostics pass,
  - model answer does not claim a concrete repair is needed.
- Keep the answer concise and CLI-friendly.

## Non-Goals

- Do not change static verification logic.
- Do not change task classification.
- Do not change model prompting.
- Do not require all verifier primary files to be read by the model before no-change containment can apply.

## Acceptance

- Tests cover a conditional review/fix turn where tool reads include stale `script.js` while runtime verifier checks current `scripts.js`.
- Final no-change output must include runtime verifier checked files.
- Final no-change output must include tool-read files from the turn.
- Final no-change output must not say "Talos inspected the current workspace files" when that wording conflates model/tool inspection with runtime verification.
- Existing conditional review/fix no-change and blocker tests continue to pass.
- Targeted tests and full `test` / `build installDist` pass before closure.

## Resolution Notes

Implemented in `ConditionalReviewFixPolicy`.

The deterministic no-change answer now names two separate evidence sources:

- runtime static verification checked files,
- tool-read files from the current turn.

It no longer says "Talos inspected the current workspace files".

Focused re-audit:

`local/manual-testing/llama-cpp-t207-focused-re-audit-20260507-214216/FINDINGS-LLAMA-CPP-T207-FOCUSED-RE-AUDIT.md`

Result:

- Qwen emitted `Runtime verification checked files: index.html, styles.css, scripts.js.` and `Tool-read files this turn: index.html.`
- GPT-OSS emitted `Runtime verification checked files: index.html, styles.css, scripts.js.` and `Tool-read files this turn: scripts.js, styles.css, index.html.`
- The old ambiguous wording did not appear.
