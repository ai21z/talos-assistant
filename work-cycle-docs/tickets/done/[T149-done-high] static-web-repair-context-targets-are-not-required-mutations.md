# T149 - Static Web Repair Context Targets Are Not Required Mutations

Status: done
Priority: high

## Evidence Summary

- Source: focused managed llama.cpp product workflow re-audit
- Date: 2026-05-05
- Talos version / commit: `v0.9.8` / `c3de157`
- Model/backend: `llama_cpp/gpt-oss-20b`
- Workspace fixture: `local/manual-workspaces/llama-cpp-product-workflow-reaudit-20260505-170318/llama-cpp-product-workflow-gpt-oss-20b-workspace`
- Raw transcript path: `local/manual-testing/llama-cpp-product-workflow-reaudit-20260505-170318/TEST-OUTPUT-LLAMA-CPP-PRODUCT-WORKFLOW-GPT-OSS-20B.txt`
- Verification status: partial verification failure

Redacted prompt sequence:

```text
Fix the static web button fixture. The existing index.html loads script.js; the
button with id run-button should set #result to Clicked. Keep filenames
index.html, styles.css, and script.js. Do not create scripts.js.
```

Expected behavior:

```text
If the existing HTML and CSS are already coherent and the only broken behavior
is in script.js, editing script.js should satisfy the static web repair. The
verifier should inspect the final HTML/CSS/JS surface, not require every
mentioned context filename to be mutated.
```

Observed behavior:

```text
GPT-OSS edited script.js correctly. Final workspace state had index.html loading
script.js and script.js using #run-button and #result correctly. Static
verification still failed because styles.css and index.html were expected
targets that were not mutated, and because the profile required separate HTML
and CSS mutation.
```

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `CURRENT_TURN_FRAME`

Blocker level:

- candidate follow-up

Why this level:

```text
The runtime safely contained the outcome, but it falsely reported a correct
repair as partial. That blocks confidence in static web repair audits and keeps
users in unnecessary retry loops.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model to edit index.html and styles.css too.
```

Architectural hypothesis:

```text
Expected target extraction and static web verification currently treat all
mentioned static web files as required mutation targets. For repair tasks,
mentioned files can be context or naming constraints. Verification ownership
should stay deterministic: final web coherence plus at least one relevant web
mutation should satisfy repair when unchanged context files are already valid.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/capability/StaticWebCapabilityProfile.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`

Why a one-off patch is insufficient:

```text
This pattern recurs whenever users say "keep filenames index.html, styles.css,
script.js" while only one file needs repair. The verifier needs target-role
semantics for repair, not prompt-specific wording.
```

## Goal

```text
For static web repair tasks, expected web filenames that are final-state context
must not be forced to mutate when static coherence passes and at least one
relevant web file changed.
```

## Non-Goals

- No new model prompt wording.
- No browser execution or JS runtime simulation.
- No weakening exact complete-file write verification.
- No broad task-classifier rewrite.

## Implementation Notes

```text
Prefer a narrow verifier/profile change. Static web create/scaffold tasks can
still require separate HTML/CSS/JS mutations. Static web repair tasks should
allow context web targets to remain unchanged if final coherence checks pass.
```

## Architecture Metadata

Capability:

- Static web repair verification.

Operation(s):

- `talos.edit_file`
- `talos.write_file`
- static verification

Owning package/class:

- `dev.talos.runtime.verification.StaticTaskVerifier`
- `dev.talos.runtime.capability.StaticWebCapabilityProfile`

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: medium; verifier truth classification.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: unchanged.
- Verification profile: static web repair.
- Repair profile: unchanged.

Outcome and trace:

- Outcome/truth warnings should stop reporting false static verification failure for this case.
- Trace/debug fields unchanged except verification status/facts.

Refactor scope:

- Allowed: small helper extraction inside static web verification/profile code.
- Forbidden: broad verifier rewrite or LLM classifier.

## Acceptance Criteria

- Static web repair with expected targets `index.html`, `styles.css`, and `script.js` passes when only `script.js` is mutated and final HTML/CSS/JS coherence is correct.
- Static web create/scaffold tasks that explicitly require separate HTML/CSS/JS files still require appropriate separate assets.
- Wrong similar filenames such as `scripts.js` do not satisfy `script.js`.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `StaticTaskVerifierTest` for script-only button repair with context targets.
- Integration/executor test: only if outcome shaping still reports partial after verifier passes.
- JSON e2e scenario: not required for first closeout.
- Trace assertion: not required for first closeout.

Manual/TalosBench rerun:

- Prompt family: focused static web repair from the product workflow audit.
- Workspace fixture: index/styles/script fixture.
- Expected outcome: no false `index.html` / `styles.css` expected-target failure.

Commands:

```powershell
.\gradlew.bat --no-daemon test --tests dev.talos.runtime.verification.StaticTaskVerifierTest
.\gradlew.bat --no-daemon check
```

## Work-Test Cycle Notes

- Convert the audit failure into deterministic verifier regression first.
- Close only after focused static web re-audit confirms the false partial is gone.

## Known Risks

- Relaxing target mutation too broadly could hide missed file rewrites in create/scaffold tasks.

## Known Follow-Ups

- T150 covers loop/outcome behavior after workspace operation postconditions are already satisfied.

## Result

- Static web repair tasks now treat unchanged web files as final-state context
  when the task is repair/edit, the files exist, at least one web target was
  mutated, and final static web coherence passes.
- Static web create/scaffold tasks still require separate HTML/CSS/JS mutations
  when the profile asks for a separate asset surface.
- Added a deterministic regression for the product-audit shape where
  `index.html`, `styles.css`, and `script.js` are all named but only
  `script.js` needs mutation.
- Focused write-file re-audit confirmed no false `index.html` / `styles.css`
  expected-target failures and no false HTML/CSS mutation coverage failures.
