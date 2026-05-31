# [T626-done-high] Static-web browser fallback causality

Status: done
Priority: high
Created: 2026-06-01
Closed: 2026-06-01
Branch: v0.9.0-beta-dev
Predecessor: T625

## Problem

T625 added an HtmlUnit browser behavior lane for simple static-web click/update
claims. The natural load-and-click path observes click causation directly and is
not the problem.

The fallback path exists only for HtmlUnit external-script linkage flakiness. If
the loaded page click does not change the requested output, the verifier executes
the linked workspace JavaScript in the already-loaded page context, dispatches a
click, and compares the output text against the value from before that bundled
eval+click sequence.

That can over-credit load-time mutation as click behavior. A script that changes
`#teaser-status` at top level and has a dead/no-op `#teaser-button` handler can
make the fallback observe a text delta and emit authoritative
`BROWSER_BEHAVIOR`, even though the click did nothing.

## Goal

Keep the fallback scoped, but make it causally honest:

```text
Authoritative BROWSER_BEHAVIOR requires a visible output change across the click
boundary, not merely during linked-script eval.
```

## Non-Goals

- Do not change the natural load-and-click path.
- Do not replace HtmlUnit with an external browser.
- Do not add Playwright or a shell/browser runner.
- Do not broaden static-web product claims.

## Acceptance Criteria

- Dead handler plus load-time/top-level mutation must not verify.
- Working handler with no load-time mutation still verifies.
- Load-time/top-level mutation plus a click handler that changes the output
  further must verify.
- The fallback captures:
  - output before inline script eval,
  - output after inline script eval and before fallback click,
  - output after fallback click.
- The fallback returns `VERIFIED` only when the output changes across the click
  boundary.
- If inline eval changes the output but the click does not, return `FAILED`,
  with a problem explaining that the linked script changed the output before the
  fallback click but the click did not change it.
- Keep workspace URL sandboxing, URL redaction, script-error handling, and
  `UNAVAILABLE` failure modes unchanged.

## Tests / Evidence

Required RED tests:

- `fallbackLoadTimeMutationWithoutClickChangeFailsBrowserBehaviorProof`
- `fallbackVerifiesWhenInlineEvalMutatesAndClickChangesOutputFurther`

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebBrowserBehaviorVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.*" --no-daemon
.\gradlew.bat check --no-daemon
```

Completed evidence:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.StaticWebBrowserBehaviorVerifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.verification.*" --no-daemon
.\gradlew.bat check --no-daemon
```

Implementation result:

- Added regression coverage for the exact fallback over-credit shape: linked
  JavaScript changes `#teaser-status` at top level while the click handler is a
  no-op.
- Added regression coverage for the opposite case: fallback inline eval mutates
  the output, then a click handler changes it further, which remains valid
  `BROWSER_BEHAVIOR`.
- Tightened fallback causality by comparing output after inline script eval
  against output after the fallback click.
- Fallback now returns `FAILED` when linked script eval changes the output before
  the click but clicking the trigger does not change it.
- Natural load-and-click behavior remains unchanged.

## Follow-Up

T627 should record or remove the root cause: the fallback exists because the
HtmlUnit lane may fail to observe externally linked script behavior reliably.
The cleaner long-term fix is deterministic natural script loading or an
external-browser lane that makes this fallback unnecessary.
