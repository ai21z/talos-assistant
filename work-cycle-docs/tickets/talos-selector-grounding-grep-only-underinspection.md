# [done] Ticket: Selector Grounding Must Handle Grep-Only Underinspection

Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/29-v1-scenario-pack.md`
- `work-cycle-docs/tickets/talos-post-edit-truthfulness-and-analysis.md`
- `work-cycle-docs/tickets/talos-streaming-no-tool-explicit-mutation-and-selector-grounding.md`

## Why This Ticket Exists

Installed CLI verification on 2026-04-26 produced a false read-only selector
answer:

```text
Based on the tool results, there are no mismatches between HTML classes/IDs and
the selectors used in CSS or JavaScript within your workspace.
```

The model had only run several `talos.grep` calls with bad patterns and had not
read `index.html`, `style.css`, or `script.js`.

## Problem

`AssistantTurnExecutor.overrideSelectorMismatchAnalysisIfNeeded(...)` delegates
to `StaticTaskVerifier.renderSelectorInspection(workspace, loopResult.readPaths())`.
That verifier currently returns `null` when the obvious primary web files were
not present in `readPaths`.

This protects against claiming the model inspected files it did not read, but it
also allows a worse outcome: a false "no mismatch" conclusion can escape when
the model under-inspected with grep-only tool calls.

## Goal

For explicit selector mismatch inspection requests in a small HTML/CSS/JS
workspace, Talos must not let unsupported grep-only "no mismatch" prose escape.
The final answer should be grounded by deterministic workspace facts or clearly
state that the primary files were not inspected.

## Scope

### In scope

- Fix the selector mismatch truth layer so grep-only underinspection does not
  bypass deterministic selector analysis.
- Add a regression where the tool loop ran only grep calls and the model claimed
  no mismatch.
- Preserve read-only behavior: no mutation, no approval.

### Out of scope

- General semantic verification beyond selector/linkage inspection.
- Browser execution.
- Shell/test-runner tools.
- Broad prompt rewrites.

## Proposed Work

Likely implementation direction:

- Add a deterministic selector-rendering path that reads the small workspace
  primary files directly from the runtime verifier, instead of requiring the
  model's `read_file` calls to have populated `loopResult.readPaths()`.
- Keep this limited to explicit selector mismatch requests and small web
  workspaces where `StaticTaskVerifier` can identify `index.html`, `style.css`,
  and `script.js`.
- Ensure the final answer is visibly grounded in those files and reports
  `.cta-button` as missing from HTML when CSS/JS reference it.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java`
- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/test/java/dev/talos/cli/modes/ExecutionOutcomeTest.java`
- `src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java`
- `src/e2eTest/resources/scenarios/`

## Test / Verification Plan

- Unit: selector mismatch request + grep-only loop result + unsupported
  "no mismatch" answer is replaced by deterministic selector facts.
- E2E scenario: JSON-backed selector grounding case where the scripted model
  does not read primary files before making the false claim.
- Full unit tests.
- Full e2e tests.
- Installed Talos manual verification in `local/playground/horror-synth-site`.

## Acceptance Criteria

- grep-only selector underinspection does not produce a final "no mismatch"
  answer when workspace facts show `.cta-button` is missing from HTML.
- deterministic selector grounding still ignores CSS hex colors as ID selectors.
- read-only inspection remains read-only.
- denied mutation still stops cleanly in the standard manual prompt sequence.

## Completion Notes

Implemented a narrow deterministic selector grounding path for explicit selector
mismatch inspection requests. `AssistantTurnExecutor` now uses
`StaticTaskVerifier.renderSelectorInspection(workspace)` for this truth layer,
so grep-only underinspection cannot bypass the workspace-fact override.

Verification completed:
- `./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest"`
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.selectorMismatchGrepOnlyUnderinspectionIsGrounded"`
- `./gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.selectorMismatchAnalysisIsGrounded"`
- `./gradlew.bat test`
- `./gradlew.bat e2eTest`
- `./gradlew.bat check`
- Installed Talos verification in `local/playground/horror-synth-site`

Manual installed run notes:
- first selector inspection turn now reports `.cta-button` missing from HTML
  even when the model under-inspects with grep/retrieve
- read-only inspection remained read-only
- playground files remained unchanged
- second mutation turn exposed a separate failure-discipline issue where invalid
  edit args still triggered missing-mutation retry; tracked separately in
  `talos-invalid-mutation-should-not-trigger-missing-mutation-retry.md`
