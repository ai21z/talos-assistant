# T184 - Runtime-Owned Static Import Answer Must Win In Live Post-Mutation Verify Turns

Severity: high

## Problem

The T179 isolated tests pass, but the focused clean Qwen/GPT-OSS audit showed that the live multi-turn path can still fail after an exact overwrite.

Sequence:

1. `index.html` starts as a static HTML page importing `script.js`.
2. `script.js` contains `.missing-button`.
3. `scripts.js` exists as a confusing sibling but is not imported.
4. User overwrites `index.html` with exactly `AFTER`.
5. User asks:

```text
Which exact file currently imports the BMI script, script.js or scripts.js? Verify from current files and answer only after inspection. Do not read protected files.
```

Correct runtime-owned answer:

```text
[Static web import check]

Neither `script.js` nor `scripts.js` is imported by `index.html`.

Current script imports found in `index.html`: none.
```

Observed Qwen behavior:
- Read only `scripts.js`.
- Returned model-authored false/misleading prose saying `scripts.js` contains the reference.
- Leaked the previous exact-write verification banner into the current answer.

Observed GPT-OSS behavior:
- Repeatedly read `index.html`, `script.js`, and `scripts.js`.
- Hit the tool-call/iteration limit.
- Did not produce the runtime-owned static import answer.

## Evidence

Audit:
`local/manual-testing/t179-t183-focused-truthfulness-audit-20260507-115245/FINDINGS-T179-T183-FOCUSED-TRUTHFULNESS-AUDIT.md`

Qwen:
- `local/manual-testing/t179-t183-focused-truthfulness-audit-20260507-115245/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1093-1126`
- `local/manual-testing/t179-t183-focused-truthfulness-audit-20260507-115245/TEST-OUTPUT-LLAMA-CPP-QWEN-14B.txt:1138-1156`

GPT-OSS:
- `local/manual-testing/t179-t183-focused-truthfulness-audit-20260507-115245/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1123-1147`
- `local/manual-testing/t179-t183-focused-truthfulness-audit-20260507-115245/TEST-OUTPUT-LLAMA-CPP-GPT-OSS-20B.txt:1153-1169`

Relevant code to inspect:
- `src/main/java/dev/talos/runtime/verification/StaticWebImportIntent.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`

Existing tests that are insufficient by themselves:
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java` around the script import grounding tests.

## Scope

- Add a live integration-style regression test for the exact audited sequence:
  - selector search,
  - exact overwrite of `index.html` to `AFTER`,
  - static import question with `script.js` vs `scripts.js`.
- Ensure the final answer is runtime-owned whenever `StaticWebImportIntent.matches(...)` is true and the workspace can be inspected.
- Ensure stale model-authored success/status prose from a prior mutation turn is suppressed for this static import answer path.
- Ensure a model reading the wrong candidate file (`scripts.js`) cannot produce the final answer if `index.html` says otherwise.
- Ensure a model hitting the tool-call limit on this static import question still receives the deterministic runtime-owned static import answer when workspace evidence is available.

## Acceptance

- Qwen-shaped scripted test: model reads only `scripts.js` and returns false/misleading prose. Final output contains `[Static web import check]`, says neither `script.js` nor `scripts.js` is imported by `index.html`, and does not contain the model-authored `scripts.js` conclusion.
- GPT-OSS-shaped scripted test: model repeatedly reads the candidate files until tool-call limit. Final output still contains the runtime-owned static import result when `index.html` is readable.
- Final output does not contain stale prior-turn `[Static verification: passed - Exact content verification passed.]` text for the static import turn.
- Trace/debug still records the original current user request and the actual tools used.
- Existing T179 and T183 tests continue to pass.

## Non-Goals

- Do not redesign the full read-only evidence system in this ticket.
- Do not change the static import wording unless the implementation proves the matcher is the actual gap.
- Do not start a full T61-style audit for this ticket alone; use the same focused Qwen/GPT-OSS audit after implementation.

## Completion Notes

Implemented on `v0.9.0-beta-dev`.

Root cause: the static import renderer could infer `index.html` in tiny fixtures, but in the full audit fixture it fell back through `obviousPrimaryFiles(...)`, which rejects workspaces above the small visible-file threshold. Candidate-only questions like `script.js or scripts.js` therefore returned no deterministic static import answer unless the user explicitly named `index.html`.

Fix:
- `StaticTaskVerifier.renderScriptImportInspection(...)` now reuses `StaticWebImportIntent.evidenceTargets(...)` so candidate-only script import questions can select inferred `index.html` before the tiny-workspace fallback.
- Added a direct verifier regression for the larger audit fixture shape.
- Added an executor regression where the model reads `script.js` and falsely claims `script.js` imports the BMI script; runtime-owned output now wins.

Verification:
- RED observed for `StaticTaskVerifierTest*scriptImportInspectionUsesInferredIndexHtmlInLargerAuditFixture`.
- Targeted static import and T185 tests passed.
- Full `gradlew test`, `gradlew build`, and `gradlew installDist` passed.
- Focused clean Qwen/GPT-OSS rerun passed:
  `local/manual-testing/t184-t185-focused-runtime-audit-20260507-140732/`
