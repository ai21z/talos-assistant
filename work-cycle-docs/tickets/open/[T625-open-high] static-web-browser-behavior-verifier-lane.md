# [T625-open-high] Static-web browser behavior verifier lane

Status: open
Priority: high
Created: 2026-06-01
Branch: v0.9.0-beta-dev
Predecessor: T623

## Evidence Summary

- Source: T623 architecture discussion and static interaction guard closeout.
- Talos version / commit at creation: `talosVersion=0.9.9`, predecessor base `0404b392`.
- Model/backend: none; architecture follow-up only.
- Workspace fixture: static HTML/CSS/JS interaction fixtures from T623.
- Verification status: follow-up ticket only.

## Problem

T623 adds a conservative static interaction guard for simple selector-bound
click/update tasks. That blocks broken-but-syntactically-valid no-ops such as
`.textC;`, but it is still static evidence. It cannot prove runtime behavior,
DOM event timing, browser APIs, CSS visibility, script loading order, module
errors, async updates, or user-observable rendering.

For claims such as "clicking `#teaser-button` updates `#teaser-status`", the
strong proof is browser execution: open the page, click the trigger, observe the
output target, and assert the visible state.

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `TOOL_SURFACE`
- `UNSUPPORTED_CAPABILITY`

Blocker level:

- future milestone

Why this level:

```text
T623 prevents the immediate false verified claim. Runtime browser verification
is the next proof-strength lane, but it requires a governed command/browser
surface and should not be smuggled into static verification.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add more JavaScript regexes until it feels browser-like.
```

Architectural hypothesis:

```text
Browser behavior verification should be a separate verifier profile that
produces authoritative BROWSER_BEHAVIOR proof when a governed browser runner or
project-native Playwright test can execute the interaction.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/verification/`
- static-web capability/profile registry
- command profile and bounded process runner packages
- future browser/Playwright harness integration
- local trace and prompt-debug evidence packages

Why a one-off patch is insufficient:

```text
Runtime web behavior has different proof mechanics than static coherence. A
regex verifier cannot prove page load, event dispatch, async mutation, console
errors, or visual output.
```

## Goal

```text
Add a browser/runtime verifier lane for static-web interaction claims that can
produce BROWSER_BEHAVIOR authoritative evidence when the environment supports
safe execution, while honestly downgrading to static or unsupported evidence
when it does not.
```

## Non-Goals

- No unguided browser automation outside workspace-local static pages.
- No internet browsing.
- No arbitrary shell command execution.
- No LLM judgment as verifier authority.
- No visual-diff or screenshot oracle unless separately specified.

## Implementation Notes

- Prefer project-native tests first when a safe Playwright/Vitest/Jest lane is
  already configured and bounded.
- For simple static pages, use a governed local browser runner that loads the
  workspace page, clicks the requested trigger, and checks target text.
- Record console/page errors as verifier problems.
- Emit `ProofKind.BROWSER_BEHAVIOR` with `EvidenceAuthority.AUTHORITATIVE`
  only when the browser command actually ran and the assertion passed.
- If browser tooling is unavailable, return `UNAVAILABLE` with an honest
  limitation; do not infer behavior from static evidence.

## Architecture Metadata

Capability:

- Static-web runtime behavior verification.

Operation(s):

- verify
- optional bounded command/browser run

Owning package/class:

- `dev.talos.runtime.verification`
- future browser verifier profile implementation
- command profile/bounded process owners for runner execution

New or changed tools:

- None unless a separately approved browser or command verifier surface is added.

Risk, approval, and protected paths:

- Risk level: high if browser runner can escape workspace or run arbitrary code.
- Approval behavior: use existing command/browser approval policy once defined.
- Protected path behavior: browser input must stay in workspace-local static
  assets; no protected content indexing or upload.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: browser assertion output and command/browser logs.
- Verification profile: `BROWSER_BEHAVIOR`.
- Repair profile: future static-web repair continuation can use browser failures
  only after evidence is redacted and bounded.

Outcome and trace:

- Outcome/truth warnings: unavailable browser lane must not block satisfied
  static-only tasks unless browser behavior was required.
- Trace/debug fields: page path, trigger selector, output selector, assertion
  result, runner availability, redacted errors.

Refactor scope:

- Allowed: add verifier profile/registry entry and a small governed runner
  adapter.
- Forbidden: broad browser automation product claims, internet browsing, or
  unbounded shell fallback.

## Acceptance Criteria

- A valid click/update static-web task can be verified by actual browser
  execution when the runner is available.
- A no-op `.textC;` task fails or remains unverified under browser execution.
- Static interaction guard remains available as cheaper static evidence.
- Browser unavailable produces `UNAVAILABLE`, not `VERIFIED`.
- Browser evidence cannot be produced by LLM advisory text.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: browser verifier result maps to claim sufficiency only with
  `BROWSER_BEHAVIOR` + `AUTHORITATIVE`.
- Integration test: page click updates output text and passes.
- Integration test: page click with `.textC;` remains unverified or failed.
- Unavailable-runner test: reports `UNAVAILABLE` and final answer is honest.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Known Risks

- Browser execution can become a hidden shell escape if not owned by command
  policy.
- Visual semantics must not be claimed unless a renderer/visual oracle exists.

## Known Follow-Ups

- Render/visual verifier lane if screenshots become product scope.
- Project-native frontend test discovery and command-profile integration.
