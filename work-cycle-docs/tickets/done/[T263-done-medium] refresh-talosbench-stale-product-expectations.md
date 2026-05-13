# T263 - Refresh TalosBench Stale Product Expectations
Date: 2026-05-13
Status: Done
Priority: Medium

## Why This Ticket Exists

The broader product audit reported three TalosBench failures for both Qwen and GPT-OSS:

- `capability-onboarding`
- `privacy-no-workspace`
- `failed-static-verification-truth`

Manual review showed these are stale benchmark expectations, not current product regressions.

## Evidence

- Qwen summary: `local/manual-testing/broader-product-audit-20260513-155858/talosbench-qwen/20260513-155935/summary.md`
- GPT-OSS summary: `local/manual-testing/broader-product-audit-20260513-155858/talosbench-gpt-oss/20260513-160137/summary.md`
- Qwen transcripts:
  - `capability-onboarding.txt`
  - `privacy-no-workspace.txt`
  - `failed-static-verification-truth.txt`
- GPT-OSS transcripts:
  - `capability-onboarding.txt`
  - `privacy-no-workspace.txt`
  - `failed-static-verification-truth.txt`

Observed current behavior:

- Capability/privacy answers say `apply approved file/workspace changes`, while the benchmark still requires `apply file changes only after approval`.
- `failed-static-verification-truth` is now a `VERIFY_ONLY` read-only status turn in `VERIFY` phase. It truthfully says the BMI page is not working / not fully functional and does not claim completion. The benchmark still expects old `INSPECT` phase and `ADVISORY_ONLY` outcome wording.

## Goal

Make TalosBench assert the current durable product contract instead of old wording.

## Scope

In scope:

- Update `tools/manual-eval/talosbench-cases.json` expectations for these three cases.
- Keep the cases strict about no workspace leakage, no tool calls for small talk, no mutation during verify-only status, and no false success.
- Validate the TalosBench schema/self-test.
- Rerun the focused cases against Qwen and GPT-OSS.

Out of scope:

- Runtime behavior changes.
- Broad TalosBench schema expansion.
- Reworking the full failed-static-verification case into a mutating apply/repair case.

## Acceptance

- `talosbench-cases.json` validates.
- TalosBench self-test passes.
- The three focused cases pass for Qwen and GPT-OSS.
- Current capability/privacy answers remain no-tool and do not leak fixture content.
- Current verify-only status answer remains read-only, evidence-based, and does not claim the broken page is working.

## Resolution

Updated `tools/manual-eval/talosbench-cases.json` to match the current product contract:

- `capability-onboarding` and `privacy-no-workspace` now require `apply approved file/workspace changes`.
- `failed-static-verification-truth` now expects `VERIFY` phase and `READ_ONLY_ANSWERED` outcome for a read-only status answer.
- The broken BMI status case still checks that the answer sees broken evidence (`empty`) and forbids false success wording such as `verified complete` and `fully working`.

## Verification

- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest`
- Focused Qwen/GPT-OSS TalosBench rerun:
  - `local/manual-testing/t263-talosbench-refresh-audit-20260513-162404/FINDINGS-T263-TALOSBENCH-REFRESH.md`
