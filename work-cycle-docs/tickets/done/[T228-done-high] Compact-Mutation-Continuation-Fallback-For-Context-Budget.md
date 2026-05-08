# T228 - Compact Mutation Continuation Fallback For Context Budget

Status: done
Severity: high
Source: post-T227 managed llama.cpp broad product audit

## Problem

Mutation-required tool-loop turns can still block on local context budget after the model spends several iterations reading files instead of mutating.

This is not a prompt-construction failure. The current turn frame has the expected targets and mutation obligation. The defect is that the continuation path can still try to carry too much loop context when it needs one more model call, then stops with a context-budget failure instead of attempting a compact mutation-only continuation.

## Evidence

Audit:
`local/manual-testing/llama-cpp-post-t227-broad-product-audit-20260508-093416`

GPT-OSS:
- Turn 25 created `index.html`, `styles.css`, and `scripts.js`; static verification passed.
  `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt` around lines 13521-13570.
- Turn 26 repeated the same static BMI create request. The model used only read tools:
  `index.html`, `styles.css`, `script.js`, and `index.html`.
  No file mutation happened.
  Lines 14268-14284.
- The continuation stopped with:
  `[Action obligation failed: retry could not fit in the context budget.]`
  estimated 5669 input tokens, budget 5635, context window 8192.
  Lines 14255-14262.
- Turn 27 review/fix had the same shape: read-only inspection only, then context-budget block.
  Lines 15019-15069.

Qwen did not reproduce this in the same audit. Qwen completed repeated BMI create and review/fix safely.

## Scope

Implement one bounded compact fallback for mutation-required tool-loop continuations when the normal continuation exceeds context budget.

In scope:
- Detect context-budget failure during a mutation-required continuation after read/read-like tool progress but no successful mutation.
- Build a compact current-turn continuation containing:
  - Talos compact mutation-continuation system instruction,
  - exact current user request,
  - task contract / expected targets,
  - latest successful readback snippets for relevant expected/static-web targets when available,
  - narrowed mutating tool surface (`talos.write_file`, `talos.edit_file`, or `talos.write_file` only for static full-rewrite repair),
  - provider required tool choice when supported.
- Execute at most one compact continuation attempt.
- If the compact attempt emits valid mutation tool calls, re-enter the normal tool loop and verification path.
- If the compact attempt also fails, keep deterministic failure-dominant output.
- Record trace/debug evidence that the compact continuation was attempted.

Out of scope:
- No new planner.
- No broad history/memory refactor.
- No change to protected read policy.
- No change to exact literal write fallback except where shared helper extraction is clearly needed.
- No larger T61-style audit until the focused fix passes.

## Acceptance

- Add a failing test reproducing the GPT-OSS shape:
  mutation-required static web request, repeated read-only tool calls, normal continuation context-budget failure, compact fallback emits a write tool, and final result is not a context-budget block.
- Add a failure-path test:
  if compact fallback also exceeds budget or returns no mutation, output remains failure-dominant and no success prose is allowed.
- Compact fallback request must contain the current user request and expected targets, including exact `scripts.js` spelling.
- Compact fallback request must not include protected file contents.
- Trace/debug records a compact mutation-continuation fallback event.
- Existing exact-write compact fallback tests still pass.
- Full `gradlew test` and `gradlew build installDist` pass.

## Audit Follow-Up

After implementation, run a focused two-model audit that repeats the BMI create/recreate/review/fix sequence with Qwen and GPT-OSS, with prompt debug and trace capture enabled.

## Verification

- `.\gradlew.bat test --no-daemon` passed on 2026-05-08.
- `.\gradlew.bat build installDist --no-daemon` passed on 2026-05-08.
- `git diff --check` passed on 2026-05-08.
