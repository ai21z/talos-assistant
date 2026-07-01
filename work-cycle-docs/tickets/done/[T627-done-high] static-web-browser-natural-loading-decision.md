# [T627-done-high] Static-web browser natural loading decision

Status: done
Priority: high
Created: 2026-06-01
Branch: v0.9.0-beta-dev
Predecessor: T626

## Problem

T625 added an HtmlUnit browser behavior lane for simple static-web interaction
claims. T626 fixed the fallback so it only grants authoritative
`BROWSER_BEHAVIOR` when the output changes across the click boundary.

That closes the known false-credit bug, but the fallback still exists because
the natural HtmlUnit load-and-click path may fail to observe externally linked
script behavior reliably. The fallback is now causally honest, but it is still a
fallback: it executes linked workspace JavaScript in the loaded page context and
records a limitation.

The next architectural decision is whether to make natural script loading
deterministic enough that the fallback can be removed, or to keep HtmlUnit as the
cheap in-process lane and add a separate governed external-browser verifier lane
for stronger proof.

## Goal

Decide and specify the root-cause direction for static-web browser behavior
verification:

```text
Either retire the inline fallback by fixing deterministic natural external-script
loading, or introduce an external-browser lane that is unavailable by default and
cannot be mistaken for success when absent.
```

## Non-Goals

- Do not add another JavaScript heuristic.
- Do not broaden HtmlUnit into a general browser automation API.
- Do not claim visual/rendering/screenshot proof.
- Do not add internet browsing.
- Do not let an unavailable external browser lane satisfy required obligations.

## Option A: Deterministic Natural HtmlUnit Loading

Investigate whether the natural `client.getPage(...); click; observe` path can
reliably execute linked workspace scripts without the inline fallback.

Acceptance for choosing this option:

- Add a regression fixture that currently requires the fallback.
- Make the natural load path pass that fixture without inline script eval.
- Keep `WorkspaceOnlyWebConnection` sandboxing intact.
- Remove or disable the inline fallback after deterministic natural loading is
  proven.
- Keep `.textC;`, dead-handler, and load-time mutation regressions failing.

## Option B: External Browser Lane

Keep HtmlUnit as the cheap scoped lane, but add a separate browser profile later
for Playwright/Chrome-like proof.

Acceptance for choosing this option:

- The external-browser lane is `UNAVAILABLE` by default unless explicitly
  configured.
- `UNAVAILABLE` cannot be projected to `PASSED` and cannot mask a failed HtmlUnit
  result for the same required claim.
- It uses a governed command/browser surface, not an ad hoc shell escape.
- It records page path, trigger selector, output selector, runner identity, and
  redacted errors in trace/prompt-debug evidence.
- It remains separate from render/visual proof unless a visual oracle is added.

## Required Analysis

- Identify why HtmlUnit natural loading misses the relevant linked script cases.
- Compare maintenance and trust cost of fixing natural loading versus adding an
  external-browser lane.
- Confirm jar size / dependency impact remains contained to the existing HtmlUnit
  entry point if Option A is chosen.
- Confirm command-profile, approval, sandboxing, and trace requirements before
  any Option B implementation.

## Tests / Evidence

Minimum evidence for the decision ticket:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebBrowserBehaviorVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.*" --no-daemon
```

If code changes are made, also run:

```powershell
.\gradlew.bat check --no-daemon
```

## Expected Outcome

One of:

- A done ticket proving the fallback was removed because natural linked-script
  loading is deterministic, or
- a follow-up implementation ticket for an external-browser lane with
  unavailable-by-default semantics and explicit command/browser governance.

## Closeout - 2026-06-25 (main-merge backlog triage)

Closed as out of current beta / main-merge scope; static-web/browser natural loading is doctrine-bounded and deferred.

Closed by independent review as part of the v0.9.0-beta-dev -> main merge preparation (owner + Codex triage: close open tickets not on the current main-merge line). No deferred implementation is claimed; remaining work, if pursued, is re-opened as a new ticket for the relevant milestone.
