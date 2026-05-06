# T169 - Changed-Files Summary Needs Per-Turn Verification State

Status: open

Severity: medium

Source audit:
- `local/manual-testing/llama-cpp-t61g-big-audit-20260506-172941/FINDINGS-LLAMA-CPP-T61G-BIG-AUDIT.md`

## Problem

T164 made changed-files answers runtime-owned, but the verification/status text
is still too coarse. Talos reports one aggregate verification status for a list
of changed files, which can overstate verification or carry stale failure state.

In the T61-G audit:

- Qwen listed multiple changed files, including a later `scripts.js` edit that
  was only `COMPLETED_UNVERIFIED`, but the summary said overall verification was
  `PASSED`.
- GPT-OSS repeatedly carried an old BMI static verification failure into later
  changed-files summaries, including after unrelated exact-write work.

## Evidence

- Qwen:
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:10623-10675`
    - `scripts.js` edit was `COMPLETED_UNVERIFIED`
  - `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:12799-12829`
    - changed-files summary reports aggregate `verified complete (PASSED)`
- GPT-OSS:
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:14245-14262`
    - changed-files summary reports stale unresolved BMI failure
  - `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:17746-17762`
    - final uncertainty prompt repeats the stale aggregate failure wording

## Scope

In scope:
- Render changed-files summaries from the runtime mutation ledger with per-entry
  verification state.
- Include path, mutation turn, tool type, outcome, and verifier result when
  available.
- Show unresolved failures separately with their originating turn.
- Avoid one global status line that implies every listed file shares the latest
  verifier result.

Out of scope:
- No Git diff support.
- No inference of user edits outside Talos mutation history.
- No browser or semantic verifier work.

## Acceptance

- Changed-files output does not say all changed files are verified if any listed
  file has only `COMPLETED_UNVERIFIED`.
- Stale unresolved failures remain visible but are attached to their originating
  task/turn, not presented as the status of every later changed-file question.
- Runtime-owned changed-files answers still use no model call and no workspace
  reads.
- Tests cover Qwen-style mixed verification and GPT-OSS-style stale failure.
- `.\gradlew.bat --no-daemon check installDist` passes.
