# T164 - Changed-Files Questions Must Use Runtime-Owned Mutation History

Status: open

Severity: medium

## Problem

When the user asks what files changed during the current audit/session, Talos currently treats the request as a generic read-only workspace explanation. That lets the model inspect arbitrary workspace evidence and guess, instead of answering from Talos-owned mutation history.

The result is unreliable:

- Qwen gave a cautious but unhelpful answer saying it could not know without previous versions.
- GPT-OSS falsely claimed `README.md` had been modified during the focused audit.

Talos already owns mutation events, approvals, checkpoints, and changed-file summaries. This class of question should not be delegated to model inference.

## Evidence

Focused managed llama.cpp re-audit:

- `local/manual-testing/t157-t160-focused-response-audit-20260506-093950/FINDINGS-T157-T160-T163-FOCUSED-RESPONSE-AUDIT.md`
- Qwen: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt`, around lines 1615-1667
- GPT-OSS: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt`, around lines 1697-1736

## Scope

- Detect direct changed-files questions such as:
  - "What files changed?"
  - "What files changed during this audit/session?"
  - "Which files did you modify?"
- Answer from runtime-owned mutation history or changed-files summary state, not model-authored workspace inference.
- If no files were mutated by Talos in the current session, say that directly.
- If there are changed files, list the runtime-owned paths and mutation status.
- Do not inspect protected files to answer this question.

## Acceptance

- Add tests where no mutation has occurred and a changed-files question returns a deterministic "no files changed by Talos" style answer.
- Add tests where one or more approved mutations occurred and the answer lists exactly those runtime-owned paths.
- The answer must not call read-only workspace tools just to infer changed files.
- The answer must not claim a file changed merely because its content contains audit markers.
- Existing `/last trace` and changed-files summary behavior remains intact.
- `.\gradlew.bat --no-daemon check installDist` passes.

## Non-Goals

- Do not implement general Git diff support.
- Do not infer external/user edits outside Talos mutation history.
- Do not change static verifier behavior.
