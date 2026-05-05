# T155 - Deterministic Exact Literal Write Correction

Status: open
Priority: medium

## Evidence Summary

- Source: full llama.cpp T61-E audit
- Date: 2026-05-05
- Model/backend: managed llama.cpp with `qwen2.5-coder:14b`
- Findings report:
  - `local/manual-testing/llama-cpp-t61e-full-audit-20260505-235337/FINDINGS-LLAMA-CPP-T61E-FULL-AUDIT.md`
- Transcript:
  - `local/manual-testing/llama-cpp-t61e-full-audit-20260505-235337/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`

Observed:

- Line 5728 shows `ExactFileWrite` was injected for `README.md`.
- Line 6059 reports exact verification failure.
- Line 6065 reports expected 27 bytes/2 lines, observed 28 bytes/3 lines.
- Final `README.md` bytes show a trailing newline after `Line two`.

## Problem

Talos already captures the exact expected payload for complete-file literal writes, but the actual file content is still model-dependent. In the observed failure, Qwen wrote the correct visible text plus one trailing newline. Static verification caught the mismatch and failure dominance worked, but the file remained wrong.

## Goal

For explicit complete-file exact content requests, Talos should make the exact payload deterministic after approval. The model should not be the final authority for byte-exact content when the runtime already parsed the exact payload.

## Scope

In scope:

- Use the current-turn exact payload as the source of truth for complete-file exact writes.
- If the model writes a near-miss, perform one deterministic correction or one bounded exact-payload retry.
- Preserve approval and checkpoint behavior.
- Preserve failure-dominant output if deterministic correction is not allowed or fails.

Out of scope:

- No broad memory/context feature.
- No fuzzy exact-write semantics.
- No hidden mutation without the existing write approval/checkpoint policy.
- No correction for ambiguous multi-file prose requests.

## Acceptance Criteria

- The exact two-line README prompt writes exactly 27 bytes and 2 lines with no trailing newline.
- Denied exact writes still do not mutate the file.
- If a model writes visible text plus a trailing newline, Talos corrects it deterministically or fails with a typed exact-literal mismatch.
- Existing successful exact writes still produce concise verified success.
- Failure output contains no success/manual-save prose.

## Tests

Required tests:

- Exact complete-file two-line write with no trailing newline.
- Near-miss trailing-newline correction path.
- Denied approval path.
- Existing successful exact write regression.
- Failure-dominance path when deterministic correction cannot run.

Suggested verification commands:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.verification.StaticTaskVerifierTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.outcome.MutationOutcomeTest
.\gradlew.bat --no-daemon e2eTest --tests dev.talos.harness.JsonScenarioPackTest.exactFileWrite
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon e2eTest
.\gradlew.bat --no-daemon check
```

## Manual Audit

After implementation:

- Rerun exact README prompts with both Qwen and GPT-OSS.
- Confirm final bytes exactly match the runtime-captured expected payload.
