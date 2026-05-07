# T189 - Static Web Diagnosis Linked-Script Continuation

Status: open
Severity: medium

## Problem

T61N found a safe but weak Qwen path for read-only static web diagnosis.

The user asked:

`Review the current static web page and say whether the button can work in a browser. Do not inspect protected files.`

Qwen read only `index.html`. Talos correctly detected that the linked script source `script.js` had not been read and returned an evidence-incomplete containment answer.

This is safe, but the runtime already knows the missing linked script target. The current product behavior stops at containment instead of deterministically gathering the remaining read-only evidence.

## Evidence

Audit:

`local/manual-testing/llama-cpp-t61n-full-e2e-audit-20260507-145319/FINDINGS-LLAMA-CPP-T61N-FULL-E2E-AUDIT.md`

Transcript:

- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9637` begins the static web review turn.
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9681` shows only `talos.read_file -> index.html`.
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9661` shows the evidence-incomplete answer.
- `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:9665` names the missing linked script source target: `script.js`.

## Scope

In scope:

- Add a bounded read-only continuation for static web diagnosis when the model reads `index.html` but omits linked script evidence.
- The continuation should request/read the missing linked script target once.
- If the continuation still fails, keep the current evidence-incomplete containment answer.
- Preserve protected-read boundaries and do not inspect protected files.

Out of scope:

- Mutating repair behavior.
- Browser execution.
- Rewriting the general evidence-obligation framework.

## Acceptance

- Tests cover the Qwen shape: read `index.html`, omit linked `script.js`, then continue once to gather `script.js`.
- Tests prove no infinite loop.
- Tests prove protected or external script sources are not read.
- Existing evidence-incomplete containment remains when the missing script read cannot be gathered.
