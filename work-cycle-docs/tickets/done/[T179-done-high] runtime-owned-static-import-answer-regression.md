# [T179-high] Runtime-Owned Static Import Answer Regression

Status: done
Priority: high

## Evidence Summary

- Source: managed llama.cpp T61-K full E2E audit
- Date: 2026-05-07
- Branch: `v0.9.0-beta-dev`
- Commit under audit: `417ab98`
- Related completed ticket:
  - `work-cycle-docs/tickets/done/[T176-done-high] static-web-import-candidate-only-question-grounding.md`
- Findings report:
  - `local/manual-testing/llama-cpp-t61k-full-e2e-audit-20260507-071629/FINDINGS-LLAMA-CPP-T61K-FULL-E2E-AUDIT.md`

Observed prompt:

```text
Which exact file currently imports the BMI script, script.js or scripts.js? Verify from current files and answer only after inspection. Do not read protected files.
```

Observed behavior:

- Before the prompt, `index.html` had been overwritten to exactly `AFTER`.
- Qwen read `index.html`.
- Qwen answered that `index.html` imports `script.js`.
- The current file evidence makes that answer false.

Concrete evidence:

- Qwen prompt: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:25271`
- Qwen false answer: `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:25295`
- GPT-OSS correct answer: `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:24496`

Additional T61-L evidence:

- Source: managed llama.cpp T61-L full E2E audit
- Date: 2026-05-07
- Commit under audit: `d312393`
- Findings report:
  - `local/manual-testing/llama-cpp-t61l-full-e2e-audit-20260507-081444/FINDINGS-LLAMA-CPP-T61L-FULL-E2E-AUDIT.md`
- GPT-OSS prompt:
  `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:23611`
- GPT-OSS read `index.html` and `scripts.js`:
  `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:23653`
- GPT-OSS false answer:
  `TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:23635-23656`
- Qwen prompt:
  `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:23960`
- Qwen answer:
  `TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:23989-23990`
  was grounded enough, but still model-authored rather than runtime-owned.

The T61-L evidence shows this is not Qwen-only. The failure mode can appear in
GPT-OSS when the model reads `index.html` and then reads `scripts.js`, allowing
model-authored prose to override the deterministic HTML import fact.

## Problem

T176 improved candidate-only static import target selection, but the audited
path still allowed model-authored read-only prose to contradict current file
evidence.

For this class of question, the correct answer can be computed deterministically
from current HTML. Talos should not leave the final truth claim to the model.

## Goal

Candidate-only static import questions must produce a runtime-owned static
import answer from parsed current HTML evidence.

When `index.html` is exactly `AFTER`, the answer must say that neither
`script.js` nor `scripts.js` is currently imported.

## Scope

In scope:

- Re-check the T176 path against the audited prompt.
- Ensure candidate-only static import questions trigger deterministic static import rendering.
- Ensure the runtime-owned answer wins over model-authored prose.
- Preserve exact filename distinction between `script.js` and `scripts.js`.
- Preserve protected-read boundaries.

Out of scope:

- Browser execution.
- General JavaScript bundler analysis.
- Broad web crawler behavior.
- New model prompt wording unrelated to static import checks.

## Acceptance

- The audited prompt triggers a deterministic static import check.
- Expected/evidence target includes current `index.html` when present.
- Candidate answer choices preserve `script.js` and `scripts.js`.
- If `index.html` contains only `AFTER`, final output says neither candidate is imported.
- The final answer is runtime-owned and cannot be contradicted by model prose.
- Tests include a Qwen-like model response that falsely claims `script.js` is imported after reading `index.html`; the final user-visible answer must remain correct.
- Tests include a GPT-OSS-like response that reads `index.html`, then reads
  `scripts.js`, and falsely says `scripts.js` is the imported BMI script; the
  final user-visible answer must remain correct.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*StaticWebImport*" --tests "*candidateOnly*" --no-daemon
./gradlew.bat test --tests dev.talos.cli.modes.AssistantTurnExecutorTest --no-daemon
./gradlew.bat check --no-daemon
```

## Resolution

- Static import answer shaping now receives the stable `CurrentTurnPlan` and
  resolves the static-import intent from `originalUserRequest` before falling
  back to the mutable message list.
- Added a regression test for the GPT-OSS-like path where internal retry
  messages trail the original request and the model falsely claims
  `scripts.js` is imported after reading `index.html` and `scripts.js`.
- The runtime-owned static import answer now wins over model-authored prose for
  this class of read-only question.
