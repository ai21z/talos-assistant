# T168 - Static Web Diagnosis Must Enforce Linked Source Read Coverage

Status: open

Severity: medium

Source audit:
- `local/manual-testing/llama-cpp-t61g-big-audit-20260506-172941/FINDINGS-LLAMA-CPP-T61G-BIG-AUDIT.md`

## Problem

Talos can mark a static web diagnosis complete even when the model has not read
the linked JavaScript needed to answer the question.

In the T61-G audit, Qwen was asked whether the current static web page button
would work in a browser. The prompt carried `STATIC_WEB_DIAGNOSIS_REQUIRED`, but
Qwen read only `index.html`, then answered conditionally that `script.js` still
needed inspection. Talos recorded the turn as complete.

GPT-OSS handled the same prompt correctly by reading both `index.html` and
`script.js`.

## Evidence

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:7820-7838`
  - static web diagnosis obligation is injected
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:7841-7850`
  - Qwen reads only `index.html` and says `script.js` still needs inspection
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:7856-7880`
  - trace records `READ_ONLY_ANSWERED` and `COMPLETE` with one tool call
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:8147-8175`
  - GPT-OSS reads both `index.html` and `script.js`

## Scope

In scope:
- For small static web diagnosis turns, derive linked source targets from
  `index.html` when possible.
- Require read coverage for linked scripts before marking a concrete browser
  behavior answer complete.
- If coverage is missing, render an advisory/incomplete answer instead of a
  complete diagnostic.
- Keep the turn read-only.

Out of scope:
- Do not add browser automation.
- Do not require full semantic JavaScript execution.
- Do not block all web diagnosis on every possible linked asset.

## Acceptance

- A model that reads only `index.html` and says linked JS still needs inspection
  is not recorded as a complete static web diagnosis.
- A model that reads `index.html` plus the linked script can complete the
  diagnosis.
- Tests cover the Qwen audit shape and the GPT-OSS passing shape.
- Existing read-only web diagnostic grounding tests still pass.
- `.\gradlew.bat --no-daemon check installDist` passes.
