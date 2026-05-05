# T144 - Negated Protected Path Evidence Obligation

Severity: high
Status: done

## Problem

The product workflow audit showed that a negated protected-path mention can be
treated as required evidence.

Prompt:

`Inspect README.md and src/app.js, then summarize the fixture purpose in two bullets. Do not read .env and do not edit files.`

Both models read only `README.md` and `src/app.js`, but the task contract still
included `.env` in `expectedTargets`, and the final outcome became
`BLOCKED_BY_POLICY`.

## Scope

- Adjust target extraction/evidence handling so negated path mentions such as
  "do not read .env" do not become required expected targets.
- Preserve protected-path blocking when the user actually asks to read a
  protected file.
- Preserve normal expected-target behavior for non-negated paths.

## Acceptance

- A prompt that says to inspect public files and not read `.env` can complete
  from the public file reads.
- `.env` is not included as required evidence when it appears only in a
  negated read instruction.
- A direct request to read `.env` still requires approval and protected-read
  handling.
- Tests cover negated protected path, direct protected read, and mixed public
  plus negated protected path prompts.

## Evidence

- `local/manual-testing/llama-cpp-product-workflow-audit-20260505-120139/`
- Qwen trace: `trc-1ddae252-d7dd-472f-a647-17c50f8f3e81`
- GPT-OSS trace: `trc-681d3891-a23e-4e57-8a18-cd62358a5621`

## Non-Goals

- No broad natural-language parser rewrite.
- No weakening protected-read approval.
- No prompt wording patch only.

## Result

- Added direct negated-read target extraction so prompts like "do not read .env"
  remove that path from expected evidence targets.
- Preserved direct protected-read target extraction for prompts that actually
  ask to read `.env`.
- Covered negated protected path, direct protected read, and mixed public plus
  negated protected targets in `TaskContractResolverTest`.
