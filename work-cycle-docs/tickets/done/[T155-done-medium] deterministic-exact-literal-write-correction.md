# T155 - Deterministic Exact Literal Write Correction

Status: done
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

Talos already captures the exact expected payload for complete-file literal writes, but the actual file content was still model-dependent. In the observed failure, Qwen wrote the correct visible text plus one trailing newline. Static verification caught the mismatch and failure dominance worked, but the file remained wrong.

## Implemented Fix

- Added `ExactLiteralWriteCallCorrector`.
- For unambiguous single-target complete-file exact writes, `talos.write_file` content is rewritten to the runtime-parsed exact payload before approval, checkpoint, and tool execution.
- The corrected payload is the one shown in approval details and the one written after approval.
- Denied writes still do not mutate files.
- Corrections are traceable through `EXACT_LITERAL_WRITE_CORRECTED`, with hashes and byte/line counts only, not raw payload text.
- Replaced the old mismatch-fails e2e scenario with a mismatch-is-corrected scenario.

## Scope Notes

- No broad memory/context feature.
- No fuzzy exact-write semantics.
- No hidden mutation outside the existing write approval/checkpoint policy.
- No correction for ambiguous multi-file prose requests.

## Verification

Passed:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.TurnProcessorTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.verification.StaticTaskVerifierTest
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.outcome.MutationOutcomeTest
.\gradlew.bat --no-daemon test --tests dev.talos.cli.modes.ExecutionOutcomeTest
.\gradlew.bat --no-daemon e2eTest --tests dev.talos.harness.JsonScenarioPackTest.literalFullFileWriteMismatchIsCorrected --tests dev.talos.harness.JsonScenarioPackTest.literalFullFileWriteMatchPassesVerification
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon e2eTest
git diff --check
.\gradlew.bat --no-daemon check installDist
```

## Manual Audit

Still recommended before a larger audit:

- Rerun exact README prompts with both Qwen and GPT-OSS.
- Confirm final bytes exactly match the runtime-captured expected payload.
