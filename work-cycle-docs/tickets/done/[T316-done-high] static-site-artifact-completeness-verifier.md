# T316 - Static Site Artifact Completeness Verifier

Status: done - styled HTML false-success blocking implemented; broader exact three-file generation/convergence remains T322
Severity: high
Release gate: yes for static website beta claims
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The transcript showed Talos accepting a static-site style request but producing only `index.html`:

```text
make the rest files please according to txt. I need a good modern synthwave style
```

Talos did not create `style.css`, did not link a stylesheet, and still reported only generic write/readback success because no task-specific verifier was applicable.

## Why It Matters

A local workspace operator must not treat a single readable file write as enough evidence for a multi-file website request. The current verifier is too weak for natural static-site artifact completeness.

## Expected Behavior

For site/page/webpage requests that mention styling, modern UI, CSS, or separate files, Talos should verify at least one of:

- the HTML contains meaningful inline styling, or
- the HTML links an existing stylesheet, and
- expected CSS artifact exists when the request implies a separate stylesheet.

If the output lacks styling, Talos should report the task incomplete or trigger repair.

## Proposed Tests

- `StaticTaskVerifierTest.styledWebpageRequestFailsWhenHtmlHasNoInlineOrLinkedStyle`
- `StaticTaskVerifierTest.styledWebpageRequestPassesWhenHtmlHasInlineStyle`
- `StaticTaskVerifierTest.transcriptStyleFollowUpFailsWhenOnlyHtmlWithoutStylingWasMutated`
- Future: `styleCorrectionFollowUpCreatesOrLinksCss`
- Future: `plainTextDocumentRequestDoesNotUseStaticSiteCompletenessVerifier`

## Evidence

Focused red/green:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --no-daemon
```

Broader focused suite:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.MutationIntentTest" --tests "dev.talos.runtime.task.TaskContractResolverTest" --tests "dev.talos.runtime.expectation.TaskExpectationResolverTest" --tests "dev.talos.runtime.ToolCallLoopTest" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.capability.CapabilityProfileRegistryTest" --no-daemon
```

Deterministic scenario pack:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest" --no-daemon
```

All passed on 2026-05-19.

## Non-Goals

- No browser automation requirement in this ticket.
- No visual quality scoring.
- No arbitrary asset generation.
