# T322 - Exact Three-File Static Web Convergence

Severity: High

Status: implemented-awaiting-evidence - deterministic static-web target/follow-up/form-gating fixes exist; fresh live synthwave audit remains

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
- Focused evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.interactiveStyledBandSiteDoesNotRequireCalculatorFormResultElements" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon
```

Both passed on `v0.9.0-beta-dev` after the implementation slice.

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

1. Run a focused true terminal/live-model synthwave audit from a fresh workspace:
   - create the synthwave guide text file,
   - ask `Great! now can you create that site?`,
   - reject weak/read-only loop behavior,
   - require final `index.html`, `style.css`, and `script.js` or an honest partial failure.
2. Save `/last trace` and prompt-debug evidence for each natural prompt.
3. Inspect final workspace state and diff.
4. Keep the ticket open if the live model still loops, writes only HTML, drifts to `styles.css`/`scripts.js`, or claims styling/functionality without files.
