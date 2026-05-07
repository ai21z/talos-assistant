# T188 - Runtime-Owned Static Button Diagnostics

Status: done
Severity: high

## Problem

T61N found that GPT-OSS inspected the current static web files and still produced a false success claim for a broken button page.

The user asked:

`Review the current static web page and say whether the button can work in a browser. Do not inspect protected files.`

The model read `index.html` and `script.js`. The current `script.js` contained a broken/no-op result handler:

```js
const button = document.querySelector('.cta-button');
const result = document.querySelector('#result');

if (button && result) {
  button.addEventListener('click', () => {
    result.textC;
  });
}
```

GPT-OSS still answered that the page would work and that clicking the button would replace `Waiting.` with `Audit action complete.`

## Evidence

Audit:

`local/manual-testing/llama-cpp-t61n-full-e2e-audit-20260507-145319/FINDINGS-LLAMA-CPP-T61N-FULL-E2E-AUDIT.md`

Transcript:

- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:9299` begins the static web review turn.
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:9323` contains the false success answer.
- `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:24386` shows the inspected `script.js` evidence containing `result.textC;`.

## Scope

In scope:

- Expand read-only web diagnostic intent so natural prompts like "review the static web page" and "whether the button can work in a browser" use runtime-owned diagnostics.
- Add static diagnostics for the common button/result fixture shape:
  - HTML has a button.
  - HTML has a visible result target such as `id="result"` or `class="result"`.
  - JavaScript references the button and result.
  - JavaScript does not assign visible result text through `textContent` or `innerText`.
- Suppress/replace model-authored "button works" success prose when runtime diagnostics find the problem.
- Preserve existing selector/linkage diagnostics and existing passing static verifier paths.

Out of scope:

- Browser automation.
- Full JavaScript execution or symbolic evaluation.
- Provider/tool-loop changes.
- The Qwen evidence-continuation gap, tracked separately.

## Acceptance

- Tests cover the T61N shape: `result.textC;` after a selector fix must be reported as a runtime-owned static web diagnostic problem.
- Tests prove the natural audit prompt matches the read-only web diagnostic intent.
- Tests prove model-authored success prose is replaced by runtime-owned diagnostic output.
- Tests prove a valid `result.textContent = 'Audit action complete.'` fixture does not produce the new problem.
- Existing static verifier and read-only web diagnostic tests still pass.

## Implementation

- Added read-only static web diagnostics for button/result handlers that reference `#result` but never assign visible text with `textContent` or `innerText`.
- Expanded web diagnostic intent for natural button-review wording from the audit.
- Restricted runtime-owned diagnostic replacement to turns that actually read both HTML and script evidence.
- Made retry-wrapped prompts expose the original `Task type` and `User request`, so runtime-owned diagnostics do not override plain workspace explanations after internal read-completeness retries.

## Verification

- `.\gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --tests dev.talos.runtime.verification.StaticTaskVerifierTest --no-daemon`
