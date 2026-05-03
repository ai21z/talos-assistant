# T97 - Current-Turn Expected-Target Steering For Exact And Web Writes

Status: Open
Priority: High
Branch: v0.9.0-beta-dev
Source: T93-T95 clean two-model audit follow-up

## Evidence Summary

- Source: post-batch clean two-model audit
- Date: 2026-05-03
- Models:
  - Qwen: `ollama/qwen2.5-coder:14b`
  - GPT-OSS: `ollama/gpt-oss:20b`
- Audit root: `local/manual-testing/t93-t95-clean-audit-20260503-034242`
- Findings:
  `local/manual-testing/t93-t95-clean-audit-20260503-034242/FINDINGS-T93-T95-CLEAN-TWO-MODEL.md`

Observed evidence:

- Qwen received an `[ExactFileWrite]` current-turn frame for
  `Overwrite index.html with exactly AFTER`, but wrote a full HTML wrapper
  containing `AFTER` instead of the exact five-byte file.
  - `TEST-OUTPUT-QWEN-14B.txt:1448-1449`
  - `TEST-OUTPUT-QWEN-14B.txt:1462-1468`
  - `TEST-OUTPUT-QWEN-14B.txt:1476-1488`
- GPT-OSS previously passed the BMI `scripts.js` path in the baseline clean
  audit, but in the T93-T95 audit repeatedly wrote `script.js` when the
  current expected target was `scripts.js`.
  - Current failure: `TEST-OUTPUT-GPT-OSS-20B.txt:1755-1768`
  - Repeated failures: `TEST-OUTPUT-GPT-OSS-20B.txt:1863-1879`,
    `TEST-OUTPUT-GPT-OSS-20B.txt:1975-2019`
  - Previous pass:
    `local/manual-testing/qwen-gptoss-clean-audit-20260503-021152/TEST-OUTPUT-GPT-OSS-20B.txt:1776`,
    `:1848`, `:1878`, `:1957`

## Classification

Primary taxonomy bucket: `CURRENT_TURN_FRAME`

Secondary buckets:

- `VERIFICATION`
- `REPAIR_CONTROL`
- `MODEL_COMPETENCE`

Blocker level: release-gate follow-up before a full T61-style audit

Why this level:

Runtime containment is correct, but the focused audit still has model failures
on two milestone-gate behaviors: exact complete-file writes and explicit
multi-file web targets. A full T61-style audit would be noisy until current-turn
target steering is stronger or the team explicitly accepts this model weakness.

## Goal

Make current-turn targets and exact-write obligations harder for routine audit
models to ignore before the first mutation attempt.

## Scope

- Extend current-turn capability framing for explicit expected target sets, not
  only exact literal expectations.
- For exact complete-file writes, make the model instruction unmistakable that
  the entire file content must be the literal payload only, with no wrapper,
  formatting, markdown, or inferred context.
- For multi-file web creates/repairs, name the expected target set in the
  current-turn frame before the first write attempt, including near-miss-prone
  targets such as `scripts.js`.
- Consider a narrow deterministic retry or correction path after exact literal
  mismatch if framing alone is insufficient.
- Preserve T93 failure-dominant output when exact or expected-target
  verification still fails.

## Non-Goals

- No broad memory system.
- No deterministic static web app generator.
- No acceptance of wrong-target mutation as completion.
- No full T61-style audit inside this ticket.

## Acceptance Criteria

- Tests prove current-turn frames for multi-target file mutations include the
  exact expected target set.
- Tests prove exact complete-file write framing says the payload must be the
  whole file and must not be wrapped or reformatted.
- Tests cover a near-miss web target set where `scripts.js` is expected while
  `script.js` exists or appears in history.
- Exact literal mismatch remains failure-dominant.
- Wrong-target web mutation remains failed and lists unresolved expected
  targets.
- Existing verified success paths for GPT-OSS-style correct `scripts.js` writes
  still pass.

## Suggested Verification

```powershell
./gradlew.bat test --tests "dev.talos.runtime.policy.CurrentTurnCapabilityFrameTest" --no-daemon
./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```

After implementation, rerun:

```text
local/manual-testing/t93-t95-clean-audit-20260503-034242/PROMPTS-CLEAN-TWO-MODEL.md
```

with fresh audit/workspace directories and the Qwen/GPT-OSS model pair.
