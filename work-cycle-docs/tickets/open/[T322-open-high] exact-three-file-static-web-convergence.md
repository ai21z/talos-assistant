# T322 - Exact Three-File Static Web Convergence

Severity: High

Status: still-open - deterministic guards improved, but fresh live synthwave audit still missed `script.js`

Source: Five scenario big audit, 2026-05-19

## Problem

Talos is safe but not reliably convergent for a realistic frontend request that asks for exactly:

```text
index.html
style.css
script.js
```

The live audit showed:

- correct mutation classification,
- approval-gated file creation,
- three files created,
- false success blocked by verification,
- but static verifier applied irrelevant calculator/form requirements,
- repair target logic drifted to `styles.css` and `scripts.js`.

## Evidence

Local transcript:

```text
local/manual-testing/five-scenario-audit-20260519-221645/20260519-221913/five-web-synthwave-site.txt
```

Related existing tickets:

```text
T297 static-web-edit-reliability-before-beta
T316 static-site-artifact-completeness-verifier
T318 correction-prompts-repair-apply-mode
```

Update 2026-05-20:

- Follow-up classification already has deterministic coverage for transcript-style prompts:
  - `Great! now can you create that site?` inherits apply-capable file creation after a prior synthwave text guide.
  - `But you just changed the index and reduced it. You never put any style in the index` inherits an apply-capable correction contract after a prior site mutation.
- Static verifier already has coverage for styled-web failure when only HTML is written without CSS/inline style.
- Static verifier now distinguishes generic interactive/styled websites from calculator/form tasks. The verifier no longer requires form/input/result elements merely because the site prompt says `interactive`, `functional`, or `functioning`.
- Static verifier no longer treats explicit text-guide requests such as `create a txt file that talks about how to build a synthwave band's web page` as failed static-web artifacts.
- Static verifier now treats `style` plus `JavaScript interaction` follow-ups as web verification candidates even when the current prompt does not literally repeat `website`.
- `ExecutionOutcome` now records embedded static-verification failures from the tool loop as verification `FAILED` in outcome/trace evidence instead of `NOT_RUN`.
- Focused evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.interactiveStyledBandSiteDoesNotRequireCalculatorFormResultElements" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.textGuideAboutBuildingWebPageDoesNotTriggerStaticWebVerification" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.styleAndJavascriptInteractionFollowUpVerifiesMissingScriptReference" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest.embeddedStaticVerificationFailureInBlockedToolLoopIsRecordedInOutcomeAndTrace" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon
```

These focused checks passed on `v0.9.0-beta-dev` after the implementation slices.

Live mini-audit evidence:

```text
local/manual-testing/static-web-synthwave-live-20260520-1aa74c31-r3/artifacts/TRANSCRIPT.txt
local/manual-workspaces/static-web-synthwave-live-20260520-1aa74c31-r3/workspace
```

Result:

- The text-guide turn is no longer falsely failed by static-web coherence verification.
- The site creation turn still created only `index.html` and was classified `COMPLETED_UNVERIFIED`.
- The style/JavaScript follow-up created `style.css` but still missed `script.js`.
- Runtime now blocks the final turn with `Static verification failed - HTML references missing JavaScript file: script.js`.
- `/last trace` now records `Verification: FAILED` for that embedded static failure.
- Artifact canary scan over the r3 live-audit directories passed:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/static-web-synthwave-live-20260520-1aa74c31-r3,local/manual-workspaces/static-web-synthwave-live-20260520-1aa74c31-r3" --no-daemon
```

## Expected Behavior

For:

```text
Create the full synthwave frontend now with exactly index.html, style.css, and script.js.
```

Talos must:

- request approval before mutation,
- create or edit exactly those three files,
- not create `styles.css` or `scripts.js`,
- ensure `index.html` links `style.css` and `script.js`,
- distinguish static coherence checks from browser execution,
- not apply calculator/form-specific verifier requirements unless the task actually requests a calculator/form.

## Regression Tests

Add deterministic tests:

```text
createExactSynthwaveThreeFileSurface_usesIndexStyleScriptOnly       // covered by exact expected-target and preferred target tests; needs live rerun evidence
styledSiteDoesNotTriggerCalculatorResultRequirement                 // added as interactiveStyledBandSiteDoesNotRequireCalculatorFormResultElements
staticRepairPreservesRequestedStyleCssAndScriptJsNames              // covered by repair/follow-up target tests; needs live rerun evidence
plainSiteCorrectionInheritsApplyMode                                // covered by missingStylingCorrectionAfterSiteMutationInheritsApplyCapableContract
```

## Fix Direction

Separate verifier profiles more explicitly:

- styled landing page
- form/calculator
- selector repair
- generic static page

Repair target discovery must preserve explicit user target names over default plural conventions.

Current remaining work:

1. Improve the continuation/repair prompt or expected-target planning so a live model that creates `index.html` linking `script.js` is driven to create `script.js`, not only `style.css`.
2. Decide whether `Great! now can you create that site?` after a guide-writing turn should infer exact static-web target expectations (`index.html`, `style.css`, `script.js`) earlier, so the second turn cannot stop at `COMPLETED_UNVERIFIED` after only `index.html`.
3. Rerun the focused live synthwave audit from a fresh workspace with `/debug prompt on`, `/last trace`, and prompt-debug save after each natural prompt.
4. Keep the ticket open if the live model still writes only HTML/CSS, misses `script.js`, drifts to `styles.css`/`scripts.js`, or claims styling/functionality without files.
