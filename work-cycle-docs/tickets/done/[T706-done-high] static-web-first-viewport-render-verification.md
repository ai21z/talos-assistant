# T706 - Static-Web First-Viewport Render Verification

Status: done
Priority: high
Created: 2026-06-06
Completed: 2026-06-06
Scope: first implementation complete; real browser runner and release-grade live signal remain follow-ups

## Evidence Summary

- Source: user screenshot plus focused static-web audits.
- Date: 2026-06-06.
- Talos version / commit at review: `talosVersion=0.9.9`, `7adb03ca69bf94ba9482b657c326dd416bbb8088`.
- Branch: `v0.9.0-beta-dev`.
- Model/backend source: Qwen installed-product audit, managed llama.cpp.
- Raw audit family: `local/TalosTestOUTPUT/test02-12-*` and post-T705 `local/TalosTestOUTPUT/test02-13-post-t705-qwen-focused-20260606-173052`.
- Screenshot evidence: first viewport was mostly black/blank, with tiny `RetrocatsCostanza, Merri` text; useful content appeared only after scroll; DevTools showed failed remote placeholder image loading.

Expected behavior:

```text
Static-web verification must not claim first-viewport visual proof unless a render-capable lane actually loaded and inspected the page viewport.
When render evidence is available, the lane should catch a mostly blank first viewport, content pushed below the first viewport, missing/failed visual assets, console/page errors, and remote request failures.
When render evidence is unavailable, Talos should surface the limitation and avoid upgrading the task to visually verified.
```

Observed behavior:

```text
Current static checks can honestly fail source/content/framework issues, but Talos has no first-viewport render lane. A visually broken page can be evaluated only through source/selector/content heuristics plus manual user screenshots.
```

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `REPAIR_CONTROL`

Blocker level:

- candidate follow-up

Why this level:

```text
This is not a privacy or approval P0. It is a serious capability gap for static-web quality claims: Talos can verify source coherence and some behavior, but it cannot yet prove first-viewport visual usability. False visual success would be release-blocking; absence of the lane is a candidate follow-up as long as Talos reports the limitation honestly.
```

## Code Evidence

- `build.gradle.kts` currently includes HtmlUnit only for static-web browser behavior verification; no Playwright/Selenium/WebDriver render dependency is present.
- `StaticWebBrowserBehaviorVerifier` is intentionally scoped to click-caused DOM behavior and uses HtmlUnit with CSS disabled and image downloads disabled. It produces `ProofKind.BROWSER_BEHAVIOR`, not render proof.
- `ProofKind.RENDER_COMPARISON` already exists, so render proof should use a distinct proof kind rather than widening `BROWSER_BEHAVIOR`.
- `StaticTaskVerifier` integrates source/linkage/content/Tailwind/framework/interaction/browser-behavior/remote-asset verifiers, but no first-viewport render verifier exists.
- `StaticWebRemoteAssetVerifier` reports remote asset references as limitations or blocking problems depending on local/offline request language; it does not execute a visual render.

## External Evidence

- Playwright documentation shows screenshot capture through `page.screenshot(...)`, including full-page and element screenshots: https://playwright.dev/docs/screenshots
- Playwright Java `Page` documentation shows page-level browser interaction, screenshot use, console message events, and request events: https://playwright.dev/java/docs/api/class-page
- Playwright Java `Request.failure()` documents failed request evidence from `requestfailed` events: https://playwright.dev/java/docs/api/class-request

These sources support Playwright as the right technology candidate for true render evidence. They do not justify silently adding a heavyweight browser runtime without a governed dependency/install/runtime policy.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add another static regex that says dark hero bad.
```

Architectural hypothesis:

```text
Talos needs a separate static-web render verification lane with its own runner boundary, proof kind, trace output, unavailable path, and repair problems. Static heuristics may provide supplemental risk diagnostics, but they are not render proof.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/ProofKind.java`
- new `StaticWebRenderVerifier` under `src/main/java/dev/talos/runtime/verification/`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- new or focused render verifier tests using a fake `RenderRunner`
- optional future dependency decision in `build.gradle.kts`

Why a one-off patch is insufficient:

```text
The failure is not just one bad Retrocats page. It is an evidence-class gap. First-viewport visual usability, screenshot evidence, console errors, and network failures require a render-capable runner or an explicit unavailable limitation. Folding that into existing source checks or BROWSER_BEHAVIOR would blur proof semantics and create false confidence.
```

## Goal

```text
Introduce a governed first-viewport render-verification design that can produce RENDER_COMPARISON evidence when a render runner is available, and explicit UNAVAILABLE/limitation evidence when it is not. Do not claim visual proof from source-only checks.
```

## Completion Evidence

- Added `StaticWebRenderVerifier` with injectable render-runner records and default unavailable runner.
- Wired render verification into `StaticTaskVerifier` through the static-web verifier lane.
- Added deterministic fake-runner tests for verified, failed, unavailable, below-fold, failed-request, and pure-interaction non-render cases.
- Focused T706 tests passed.
- `.\gradlew.bat test --tests "dev.talos.runtime.verification.*" --no-daemon` passed.
- `.\gradlew.bat check --no-daemon` passed.

## Recommended Implementation Strategy

Stage 1 - deterministic product spine:

- Add `StaticWebRenderVerifier` with an injectable `RenderRunner`.
- Add records for render input/result, for example viewport size, visible brand/content facts, first-viewport blankness summary, console/page errors, failed requests, screenshot artifact path when available, problems, limitations.
- Integrate the verifier into `StaticTaskVerifier` for `STATIC_WEB` contracts that have visual/website presentation intent.
- Use `ProofKind.RENDER_COMPARISON`.
- Default to an unavailable runner unless a real render backend is configured. Unavailable render evidence must be trace-visible and must not verify visual claims.
- Add fake-runner deterministic tests before any real browser dependency.

Stage 2 - runner decision:

- Choose whether Talos should add a Playwright Java runner, an externally configured browser runner, or keep render proof manual/deferred.
- If Playwright is chosen, serve workspace files through a workspace-only local HTTP server or equivalent controlled route instead of relying on `file://` rendering.
- Block or record non-workspace network requests by default. If an explicit CDN allowance exists, the runner may report that visual proof depends on remote runtime assets; it must not silently fetch arbitrary remote assets as local proof.
- Capture first viewport at a fixed desktop size first, then add mobile viewport only in a later ticket if needed.

## Non-Goals

- No broad visual-quality LLM judge.
- No screenshot proof without a render runner.
- No widening `BROWSER_BEHAVIOR` beyond observed interaction behavior.
- No automatic internet fetch of arbitrary remote assets.
- No automatic rollback.
- No full aesthetic scoring in this ticket.
- No Playwright dependency unless the implementation step explicitly accepts the install/runtime complexity.

## Architecture Metadata

Capability:

- Static-web verification.

Operation(s):

- Verify only. No workspace mutation.

Owning package/class:

- `dev.talos.runtime.verification.StaticWebRenderVerifier` and `StaticTaskVerifier` integration.

New or changed tools:

- None in the Talos tool surface.
- Possible internal render runner, not a user-visible workspace tool.

Risk, approval, and protected paths:

- Risk level: medium-high if a real browser dependency is added; medium for fake-runner/static integration.
- Approval behavior: no user mutation approval because this is verification-only; no command/browser install without explicit implementation decision.
- Protected path behavior: only inspect static-web files already in the workspace verification scope; no protected reads.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none; verification-only.
- Evidence obligation: viewport render result, visible text/brand facts, console/page errors, failed request evidence, screenshot path or unavailable limitation.
- Verification profile: `STATIC_WEB`.
- Proof kind: `RENDER_COMPARISON`.
- Repair profile: render problems may feed static-web repair, but repair must target actual writable site files and preserve existing repair/approval policy.

Outcome and trace:

- Outcome/truth warnings: unavailable render evidence may appear as an unavailable `RENDER_COMPARISON` verifier result for traceability, but it must remain a limitation and must not be represented as verified render/visual proof.
- Trace/debug fields: render runner availability, viewport size, screenshot artifact path when present, blocked/failed requests, console/page errors, visible brand/content facts.

Refactor scope:

- Allowed: add a small verifier and runner interface; add deterministic tests; minimally wire into `StaticTaskVerifier`.
- Forbidden: broad rewrite of static-web verification, tool surface, approval policy, or HtmlUnit behavior verifier.

## Acceptance Criteria

- `StaticWebRenderVerifier` produces `RENDER_COMPARISON` evidence only through a render runner result, never from source-only heuristics.
- If render verification is unavailable, the report carries an explicit limitation and does not verify first-viewport/visual quality.
- A fixture with a mostly blank 100vh first viewport and tiny or below-fold brand/content fails render verification when the runner reports those facts.
- A fixture with visible first-viewport brand/content and no render errors passes render verification when the runner reports those facts.
- Failed remote asset requests are surfaced as render problems or limitations according to policy.
- Existing `BROWSER_BEHAVIOR` tests keep their current proof semantics.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: render verifier passes/fails from fake runner results.
- Unit test: unavailable runner produces limitation and no verified render claim.
- Integration verifier test: `StaticTaskVerifier` merges render problems into static-web problems without changing `BROWSER_BEHAVIOR`.
- Trace/debug assertion when practical: render runner availability and viewport result appear in verification report/trace data.

Manual/TalosBench rerun:

- Prompt family: Retrocats static-web creation and repair.
- Workspace fixture: deterministic static site with first-viewport blank hero and failed remote image; deterministic valid first viewport.
- Expected trace: `STATIC_WEB`, render verifier available/unavailable explicit, no false visual proof.
- Expected outcome: limitation/no visual proof surfaced when render evidence is absent; failed or unverified when available render evidence is bad; render-verified only when render evidence is present and good.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.verification.*" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```

If a real browser dependency is added, also run an installed-product smoke audit with a fresh isolated workspace and record browser install/runtime provenance.

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version for this ticket alone.
- Do not run a full live audit until T707 and the render verifier deterministic tests are green.
- If Playwright is chosen, create or update a dependency/runtime sub-ticket before merging that dependency.

## Known Risks

- Browser/runtime dependency size and install behavior may be too heavy for the beta default path.
- Remote CDN styling creates an evidence conflict: source verifier may accept CDN with limitation, but visual proof cannot be local/offline proof if the render runner does not fetch it.
- Pixel blankness alone is insufficient because dark pages are valid; render checks need visible text/brand boxes as well as pixel diagnostics.
- HtmlUnit is not enough for this ticket because current use disables CSS and images and does not provide screenshot evidence.

## Known Follow-Ups

- T707 should land before release-grade Retrocats live-audit conclusions, because repair convergence currently fails before the page reaches a stable final state.
- A separate dependency decision may be needed for Playwright Java or another governed render backend.
- Mobile viewport render checks should be a later ticket after desktop first-viewport proof works.
