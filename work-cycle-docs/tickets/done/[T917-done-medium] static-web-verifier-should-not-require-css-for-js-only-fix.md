# [T917-done-medium] Static web verifier should not require CSS for JS-only fix

Status: done
Priority: medium

## Evidence Summary

- Source: installed-product GPT-OSS Agent-mode manual audit
- Date: 2026-06-29
- Talos version / repo HEAD at audit: 0.10.6 / `ab4b3706`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Model/backend: managed `llama.cpp` / `gpt-oss-20b`
- Isolated Talos home: `local/manual-testing/gptoss-agent-mode-deep-20260629-104800/home`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\gptoss-agent-mode-deep-20260629-104800\agent-workspace`
- Prompt-debug artifact copy: `local/manual-testing/gptoss-agent-mode-deep-20260629-104800/artifacts/prompt-debug/prompt-debug-20260629-110433.md`
- Provider body artifact copy: `local/manual-testing/gptoss-agent-mode-deep-20260629-104800/artifacts/prompt-debug/prompt-debug-20260629-110433.provider-body.json`
- Trace id: `trc-adf0337d-c155-4af2-99fd-407a9ca21e66`
- Approval choices: none on this turn; prior write session approval was reused
- Checkpoint id: `chk-148afac2-4481-499f-9c7e-96b953a68c54`
- Final disk state: `script.js` changed from `.missing-button` to `.cta-button`; `scripts.js` unchanged
- Verification status: live installed audit reproduced; deterministic regression added and passing

Redacted prompt sequence:

```text
/mode agent
Fix script.js by changing .missing-button to .cta-button. Do not edit scripts.js.
/last trace
```

Expected behavior:

```text
For a targeted JavaScript selector edit where the workspace has HTML and JS but
no CSS file, static verification should still be able to evaluate the requested
targeting/change or degrade to readback-only with an honest "no CSS file"
limitation. It should not fail the task solely because a CSS primary file is
absent when the user did not request CSS work.
```

Observed behavior:

```text
The mutation and targeting were correct:

- target roles: `script.js = MUST_MUTATE`, `scripts.js = FORBIDDEN`;
- tools: `talos.read_file -> script.js`, then `talos.edit_file -> script.js`;
- final disk state: `script.js` contains `.cta-button`;
- final disk state: `scripts.js` remains the trap file with `.trap-button`.

But the runtime marked the turn `FAILED`:

  Static verification failed - web coherence could not be checked because HTML,
  CSS, and JavaScript primary files were not all present.

This is an honest failure report rather than a false success, but it appears to
be an over-strict verifier false negative for a JS-only selector fix in a
workspace with no CSS primary file.
```

Code evidence:

- The static-web verifier path adds the exact failure when primary HTML/CSS/JS
  files are not all present:
  `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`.
- `StaticTaskVerifier.verifySmallWebWorkspace(...)` already has partial functional
  paths gated by `profile.targetSurface().allowsFunctionalPartial()`, so the
  fix should refine contract-sensitive partial verification rather than remove
  the full-surface verifier:
  `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`.
- `TargetScopeStaticVerifier.verify(...)` enforces expected, forbidden, and
  similar-target mutation scope before static-web coherence is interpreted:
  `src/main/java/dev/talos/runtime/verification/TargetScopeStaticVerifier.java`.
- Existing verifier tests assert this missing-surface failure family for some
  incomplete surfaces:
  `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`.
- Existing verifier tests also pin the `script.js` versus `scripts.js`
  similar-target traps, including wrong expected target and forbidden sibling
  mutation failures:
  `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`.
- The live trace proves the target-specific mutation guard and similar-file
  guard worked; the failure came after mutation, during static verification.

## Classification

Primary taxonomy bucket:

- `VERIFIER`

Secondary buckets:

- `OUTCOME_TRUTH`
- `STATIC_WEB`
- `UX`

Blocker level:

- candidate follow-up

Why this level:

No unsafe mutation occurred and Talos did not falsely claim success. The impact
is a false-negative completion result after a correctly targeted, approved edit.
This can erode trust in the verifier and make simple static-web fixes look
failed when they are actually complete for the requested scope.

## Recommended Fix

Make static verification target- and contract-aware:

1. If the task only targets JS and the HTML file references that JS, allow a
   JS/HTML coherence check without requiring CSS.
2. If CSS is absent and irrelevant to the requested target set, report a
   limitation rather than failing the turn.
3. Preserve the existing hard failure for tasks that require full HTML/CSS/JS
   coherence or where a missing primary file is actually relevant.
4. Preserve `TargetScopeStaticVerifier` behavior: writing `script.js` must still
   fail when the contract expected `scripts.js`, and forbidden sibling targets
   must still fail if mutated.

## Implementation Evidence

- Added a focused verifier regression:
  `StaticTaskVerifierTest.scriptOnlySelectorFixDoesNotRequireCssWhenHtmlImportsEditedScript`.
- `StaticTaskVerifier.verifySmallWebWorkspace(...)` now routes only narrow
  JavaScript selector edits through a JS/HTML partial verifier when:
  - all successful mutations are JavaScript files;
  - the task does not carry an interaction-claim obligation;
  - the contract does not expect non-JavaScript mutation targets; and
  - inspected HTML imports the mutated JavaScript file.
- The new path reuses `StaticWebPartialVerifier.verifyFunctionalWebWorkspace(...)`
  and `StaticWebSelectorAnalyzer.analyzeFunctional(...)`, filters CSS-only
  linkage/content findings as irrelevant to the JS-only contract, and records:
  `HTML/JavaScript selector coherence passed ...; CSS was not required for this
  JavaScript-only edit.`
- Existing full-surface, interaction-proof, and similar-target behavior remains
  pinned by the broader `dev.talos.runtime.verification.*` test package.

## Regression Test

- Fixture with `index.html` and `script.js`, no CSS primary file.
- Prompt/contract targets only `script.js`.
- Mutation changes a selector in `script.js`.
- Verifier should not emit "HTML, CSS, and JavaScript primary files were not
  all present" as a task failure solely because CSS is absent.
- Similar-file guard remains asserted: `scripts.js` is forbidden and unchanged.
- Negative guard: if the contract expects `scripts.js` but the mutation changes
  `script.js`, the existing similar-target failure still wins.

## Verification

- `.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.scriptOnlySelectorFixDoesNotRequireCssWhenHtmlImportsEditedScript"`:
  RED before implementation with the T917 failure text, GREEN after implementation.
- `.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.verification.TargetScopeStaticVerifierTest" --tests "dev.talos.runtime.verification.StaticWebSurfaceDetectorTest" --tests "dev.talos.runtime.verification.StaticWebSelectorAnalyzerTest"`:
  green.
- `.\gradlew.bat test --tests "dev.talos.runtime.verification.*"`: green.
- `.\gradlew.bat test --tests "dev.talos.docs.TicketHygieneTest" --tests "dev.talos.wiki.WikiLintStructuralTest"`:
  green.
- `.\gradlew.bat check --no-daemon`: green.
- `git diff --check`: green, with CRLF normalization warnings only.
