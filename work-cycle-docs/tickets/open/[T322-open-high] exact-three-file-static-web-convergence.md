# T322 - Exact Three-File Static Web Convergence

Severity: High

Status: still-open - exact three-file static web convergence remains a current blocker

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
createExactSynthwaveThreeFileSurface_usesIndexStyleScriptOnly
styledSiteDoesNotTriggerCalculatorResultRequirement
staticRepairPreservesRequestedStyleCssAndScriptJsNames
plainSiteCorrectionInheritsApplyMode
```

## Fix Direction

Separate verifier profiles more explicitly:

- styled landing page
- form/calculator
- selector repair
- generic static page

Repair target discovery must preserve explicit user target names over default plural conventions.
