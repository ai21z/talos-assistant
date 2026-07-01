# [done] Ticket: Read-Only Web Diagnostics Static Grounding
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-source-of-truth.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/done/talos-static-verifier-web-app-scope-and-wording.md`

## Why This Ticket Exists

Installed Talos verification against a deliberately broken BMI workspace showed
that read-only troubleshooting can still produce an incorrect diagnosis even
after Talos reads the relevant local files.

Prompt:

```text
Inspect this BMI website and identify why it is not working. Do not edit files yet.
```

Observed answer:

```text
The issue with the BMI website is that the `script.js` file is missing a
closing script tag, which causes the JavaScript code to not be executed.
```

The workspace facts did not support that wording. The malformed tags were in
`index.html`:

```html
<button type="submit">Calculate BMI</button
<script src="script.js"></script
```

and `styles.css` also had a likely selector typo:

```css
calculator-container { max-width: 420px; margin: 2rem auto; }
```

## Problem

Static verification is currently strongest after successful mutations. For a
read-only diagnostic turn, Talos still leans too much on model synthesis over
tool output.

That leaves a trust gap:

- Talos can read the right files.
- The final answer can still misattribute the failure.
- The user receives a confident but incorrect diagnosis before any edit.

This is a runtime discipline issue, not just prompt polish. Read-only diagnosis
is part of Talos's safety surface.

## Goal

For small HTML/CSS/JS workspaces and explicit read-only troubleshooting prompts,
ground the final diagnostic answer in deterministic static workspace facts when
those facts are available.

## Scope

### In scope

- Detect read-only web diagnostic prompts such as:
  - `why is this website not working`
  - `inspect this BMI website`
  - `identify problems`
  - `do not edit yet`
- Reuse or expose static web checks for read-only diagnostics.
- Report malformed HTML tags, missing linked files, missing DOM IDs/selectors,
  and obvious CSS selector typos when detectable.
- Keep the turn read-only: no mutation, no approval.
- Add deterministic scenario coverage for the broken BMI shape.

### Out of scope

- Browser execution.
- Full semantic website testing.
- Shell/test-runner tools.
- Broad HTML parser dependency.
- Replacing normal model explanation for all read-only questions.

## Proposed Work

1. Add a read-only static diagnostic path for small web workspaces.
2. Reuse `StaticTaskVerifier` internals where appropriate, but avoid pretending
   a task was post-apply verified when no mutation occurred.
3. Feed the final answer through a deterministic grounding annotation or
   replacement when the model diagnosis contradicts local static facts.
4. Add an e2e scenario where the model misdiagnoses `script.js`, but workspace
   facts show malformed tags in `index.html`.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/e2eTest/resources/scenarios/`
- `src/e2eTest/java/dev/talos/harness/JsonScenarioPackTest.java`

## Test / Verification Plan

Focused:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest"
```

Manual:

- Run installed Talos in `local/manual-testing/qa-workspaces/broken-bmi-stale`.
- Ask the read-only diagnostic prompt.
- Confirm the final answer names `index.html` malformed tags and does not claim
  `script.js` itself is missing a closing script tag.
- Confirm no file changes and no approval prompt.

## Acceptance Criteria

- Read-only troubleshooting remains read-only.
- The broken BMI prompt is grounded in local static facts.
- Unsupported model diagnoses are corrected or clearly qualified.
- Existing selector-grounding scenarios still pass.

## Completion Notes

Implemented on `ticket/talos-read-only-web-diagnostics-static-grounding`.

Added a deterministic read-only web diagnostic renderer that reports static
workspace facts for small HTML/CSS/JS surfaces, including malformed closing
tags and likely bare CSS selectors that match HTML classes. The executor outcome
path now replaces unsupported model diagnostics for non-mutating web
troubleshooting prompts with those static facts.

Covered by:

```text
src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java
src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java
src/e2eTest/resources/scenarios/31-read-only-web-diagnostics-grounded.json
src/e2eTest/resources/fixtures/broken-bmi-site/
```

Verification run:

```powershell
./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest"
./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.readOnlyWebDiagnosticsAreGrounded"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
```

Installed Talos was rebuilt and manually run against
`local/manual-testing/qa-workspaces/broken-bmi-stale`. The final answer now
names the real `index.html` malformed closing tags and the CSS
`calculator-container` selector problem, and it says no files were changed.

Manual verification also exposed separate loop-efficiency debt: the model still
ran read-only tools to the 10-iteration cap before the deterministic answer was
shaped. That is captured as:

```text
work-cycle-docs/tickets/done/talos-read-only-web-diagnostic-loop-short-circuit.md
```
