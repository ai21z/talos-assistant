# T162 - Verified Multi-File Success Summaries Must List All Mutated Targets

Status: done

Severity: medium

## Problem

Runtime-owned success summaries can underreport changed files after verified multi-file operations.

In the T61-F audit, both models wrote `index.html`, `styles.css`, and `scripts.js`, and static verification passed for 3 mutated targets. The visible response sometimes listed only a subset of those changed files.

## Evidence

T61-F managed llama.cpp response-quality review:

- `local/manual-testing/llama-cpp-t61f-full-audit-20260506-075339/MODEL-RESPONSE-QUALITY-REVIEW.md`

Trace evidence:

- Qwen turn 18:
  - `talos.write_file(index.html)`
  - `talos.write_file(styles.css)`
  - `talos.write_file(scripts.js)`
  - verification passed for 3 mutated targets
  - visible answer listed only `index.html` and `styles.css`
- GPT-OSS turn 18:
  - same three writes
  - verification passed
  - visible answer did not list changed files
- GPT-OSS turn 19:
  - same three writes
  - visible answer listed only `scripts.js`

## Scope

- Make verified multi-file success summaries complete and runtime-owned.
- If Talos reports "passed for N mutated targets", visible output must list all N target paths or explicitly say all changed paths are listed elsewhere.
- Prefer concise output:
  - `Updated 3 files: index.html, styles.css, scripts.js`
  - optionally include line/byte details when available.
- Preserve failure-dominant output behavior.

## Acceptance

- Add tests for a three-file static web create where all three writes pass verification; final visible answer lists all three target paths.
- Add tests for partial success/failure where only successfully changed paths are listed and failure remains dominant.
- Final changed-files summary behavior from T153 remains intact.
- Existing exact-write success summaries still pass.
- `.\gradlew.bat --no-daemon check installDist` passes.

## Non-Goals

- Do not change static verification logic.
- Do not add verbose diffs to normal success output.
- Do not rely on model-authored success prose for changed-file lists.
