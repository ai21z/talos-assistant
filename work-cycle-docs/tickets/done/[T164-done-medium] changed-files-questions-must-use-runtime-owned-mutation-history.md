# T164 - Changed-Files Questions Must Use Runtime-Owned Mutation History

Status: done

Severity: medium

Closed: 2026-05-06

## Problem

When the user asks what files changed during the current audit/session, Talos must not treat the request as a generic read-only workspace explanation. That lets the model inspect arbitrary workspace evidence and guess, instead of answering from Talos-owned mutation history.

The focused audit evidence showed:

- Qwen gave a cautious but unhelpful answer saying it could not know without previous versions.
- GPT-OSS falsely claimed `README.md` had been modified during the focused audit.

Talos already owns mutation events, approvals, checkpoints, and changed-file summaries. This class of question should not be delegated to model inference.

## Scope Completed

- Direct changed-files questions now use `ChangeSummaryContext` when runtime-owned changes exist.
- Direct changed-files questions with no runtime-owned mutations now return a deterministic no-change answer.
- Added detection for direct modify/change forms such as `Which files did you modify in this session?`.
- Kept broader status follow-ups on the existing verified-outcome path when they are not direct file-change questions.

## Acceptance

- Added tests where no mutation has occurred and a changed-files question returns a deterministic "No files were changed by Talos..." answer.
- Added tests proving model-authored changed-files claims and previous assistant prose are not used when the runtime ledger is empty.
- Added tests proving workspace markers are not inspected/inferred as changed files.
- Preserved the runtime-ledger path for approved mutations and asserted it does not include model hallucinated paths.
- Verified direct changed-files audit turns use no tools and no provider/model prompt debug capture.

## Verification

- `.\gradlew.bat --no-daemon test --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"` passed.
- `.\gradlew.bat --no-daemon check installDist` passed.
- Focused Qwen/GPT-OSS managed llama.cpp audit passed:
  - `local/manual-testing/t164-focused-response-audit-20260506-103528/FINDINGS-T164-FOCUSED-RESPONSE-AUDIT.md`
  - `local/manual-testing/t164-focused-response-audit-20260506-103528/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`
  - `local/manual-testing/t164-focused-response-audit-20260506-103528/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`

## Non-Goals

- No general Git diff support.
- No inference of external/user edits outside Talos mutation history.
- No static verifier behavior changes.
