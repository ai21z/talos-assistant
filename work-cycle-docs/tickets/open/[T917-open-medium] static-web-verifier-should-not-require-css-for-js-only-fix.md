# [T917-open-medium] Static web verifier should not require CSS for JS-only fix

Status: open
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
- Verification status: live installed audit reproduced; deterministic regression not yet added

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

- `StaticTaskVerifier.verifyStaticWeb(...)` adds the exact failure when primary
  HTML/CSS/JS files are not all present:
  `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`.
- Existing verifier tests assert this message for some incomplete surfaces:
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

Make static verification target-aware:

1. If the task only targets JS and the HTML file references that JS, allow a
   JS/HTML coherence check without requiring CSS.
2. If CSS is absent and irrelevant to the requested target set, report a
   limitation rather than failing the turn.
3. Preserve the existing hard failure for tasks that require full HTML/CSS/JS
   coherence or where a missing primary file is actually relevant.

## Regression Test

- Fixture with `index.html` and `script.js`, no CSS primary file.
- Prompt/contract targets only `script.js`.
- Mutation changes a selector in `script.js`.
- Verifier should not emit "HTML, CSS, and JavaScript primary files were not
  all present" as a task failure solely because CSS is absent.
- Similar-file guard remains asserted: `scripts.js` is forbidden and unchanged.
