# [done] Ticket: Static Verifier Web-App Scope And Wording
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
Related tickets:
- `work-cycle-docs/tickets/done/talos-task-contract-build-mutation-intent.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-outcome.md`

## Why This Ticket Exists

The static verifier V1 correctly stayed narrow, but installed and JShell
evidence showed the CLI wording can overstate what was proven.

For a broken BMI calculator workspace, simulated successful writes to
`index.html`, `styles.css`, and `script.js` produced:

```text
PASSED - Post-apply static checks passed for 3 mutated target(s).
```

even though:

- HTML lacked the form and input IDs required by `script.js`
- `script.js` referenced IDs missing from HTML
- CSS class selectors could be missing from HTML
- the web app would not function

## Problem

`StaticTaskVerifier` runs generic target/readability/placeholder checks for
every successful mutation.

It only runs small-web selector/linkage checks when
`shouldCheckSelectorCoherence(...)` sees narrow selector/linkage language:

```text
selector, .cta-button, #cta-button, match, mismatch, align, linkage, wire, reference
```

Broad web-app generation prompts such as:

```text
Can you build a small BMI calculator website here with separate CSS and JavaScript files?
Can you make it?
```

do not trigger web coherence checks.

The verifier's internal scope is acceptable for V1, but the message
`Static verification: passed` reads too broadly to users.

## Goal

Prevent Talos from presenting narrow file-level/static checks as if broad
web-app functionality was verified.

For small HTML/CSS/JS workspaces and web creation/repair prompts, run stronger
static coherence checks or downgrade the verification wording/status.

## Scope

### In scope

- Broaden web-coherence trigger logic for web-app generation/repair task
  contracts.
- Verify common HTML/CSS/JS linkage facts:
  - HTML links expected CSS file
  - HTML links expected JS file
  - JS `getElementById` / `querySelector` references exist in HTML when safe
  - CSS class/ID selectors exist in HTML for small web workspaces
- Change final wording when only target/readback checks passed.
- Add tests using the broken BMI workspace shape.

### Out of scope

- Browser execution.
- Shell/test-runner verification.
- Full semantic correctness of BMI math or UX.
- Large website crawling.

## Proposed Work

1. Separate verification labels.

   Distinguish:

   ```text
   target/readback verification passed
   static web coherence passed
   static verification incomplete
   static verification failed
   ```

   Avoid a bare `Static verification: passed` when only mutated target files
   were readable.

2. Expand web-task detection.

   Use `TaskContract` and user request signals:

   - website
   - web app
   - page
   - HTML + CSS + JavaScript
   - separate styling/script files
   - functioning/functionality
   - calculator/site/app

3. Add small-web coherence checks.

   Reuse existing selector extraction where possible. Add ID extraction for:

   - `document.getElementById(...)`
   - `querySelector("#...")`
   - `querySelector(". ...")` where applicable

4. Keep failure language honest.

   If static facts do not prove the task, say so.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"
```

Required cases:

- broken BMI workspace with successful writes does not get broad `PASSED`
- valid HTML/CSS/JS linkage passes static web coherence
- `.cta-button` selector scenario remains covered
- CSS hex colors are still ignored as ID selectors
- non-web file edits keep narrow target/readback verification behavior

Installed verification:

- Run an approved disposable web-app apply in a temporary copy, or use scripted
  e2e first and only mutate a disposable playground copy manually.

## Acceptance Criteria

- Talos no longer implies functional web-app completion from readback-only
  checks.
- Small HTML/CSS/JS tasks get stronger static coherence verification.
- Final answer wording makes the verifier's scope clear.
- Existing selector verifier scenarios still pass.
