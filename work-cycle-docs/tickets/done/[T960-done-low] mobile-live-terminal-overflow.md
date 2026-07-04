# [T960-done-low] Mobile live terminal must not force the hero visual wider than the viewport

Status: done
Priority: low

## Evidence Summary

- Source: owner mobile screenshot review
- Date: 2026-07-04
- Branch under ticket: `v0.9.0-beta-dev`
- Talos version / commit: 0.10.8 / `9524e15a9e36d5fcc20d06a6da4fbb8841c79a99`
- Code under review:
  - `site/src/main.js`
  - `site/src/styles.css`
  - `site/test/e2e/site.spec.js`
  - `site/test/site.test.js`
- Verification status: implemented and verified locally

Observed defect:

```text
After the live hero terminal reaches the answer lane on mobile widths, the
terminal visual expands past the visible mobile viewport. The most visible
line is `Local-first CLI workspace operator. Java 21 sources`.
```

Measured reproduction against served `site/dist` at `http://127.0.0.1:41741/`:

```text
width=320: live-terminal right=437.45, width=425.45
width=375: live-terminal right=437.45, width=425.45
width=390: live-terminal right=437.45, width=425.45
```

Root cause:

```text
The live terminal uses a `<pre class="terminal-screen">` with `white-space: pre`
and long single-line lane strings. The longest answer/rule lines become the
terminal's intrinsic width, which inflates the hero visual column. The page
level overflow guard misses this because the hero clips overflow.
```

Secondary observed effects:

```text
The inflated hero visual also pulls the hero inscription rule, terminal bar,
terminal footer, and caption to the same clipped width on 320-390px screens.
```

## Classification

Primary taxonomy bucket: `SITE_UX`

Secondary buckets:

- `MOBILE_LAYOUT`
- `VISUAL_POLISH`

Blocker level: public-site polish before artifact publication

Why this level:

```text
This is not a runtime trust bug, but it is a visible mobile defect in the
public front door immediately before public artifact publication.
```

## Goal

```text
The live hero terminal should complete its answer animation on 320/375/390px
mobile viewports without any terminal, footer, caption, or hero visual element
extending past the viewport.
```

## Non-Goals

- No redesign of the hero.
- No removing the live terminal.
- No replacing the current navbar.
- No fake install or capability copy changes.
- No broad site animation rewrite.

## Implementation Notes

- Use strict TDD: add a Playwright regression that waits for the live terminal
  to reach `/last trace` before checking widths.
- The existing mobile test currently checks the hero before the terminal has
  completed; extend it so dynamic content is measured after the answer lane
  lands.
- The likely safe fix is both:
  - shorten the live answer copy to `Local-first CLI workspace operator.`, and
  - allow terminal screens to wrap on mobile instead of using unbounded
    `white-space: pre` in the narrow layout.
- Keep desktop terminal grammar intact unless shortening the mock divider is
  required to avoid mobile min-content inflation.
- Verify 320, 375, and 390px viewports.

## Architecture Metadata

Capability:

- public site mobile hero rendering

Operation(s):

- static site UI only

Owning package/class:

- site frontend

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: low
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: Playwright viewport assertions plus screenshots
- Verification profile: site static tests and Playwright e2e
- Repair profile: no runtime/model repair

Outcome and trace:

- Outcome/truth warnings: terminal mock copy must remain honest and grounded in
  the real Talos turn grammar
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: small terminal copy/CSS adjustments and targeted tests
- Forbidden: hero redesign or unrelated site cleanup

## Acceptance Criteria

- At 320, 375, and 390px after the live terminal reaches `/last trace`,
  `.live-terminal`, `.terminal`, `.terminal-screen`, `.terminal-foot`, and
  `.banner-caption` stay within the viewport.
- The terminal screen has no hidden horizontal scroll requirement on mobile.
- The answer copy remains true and concise.
- Existing desktop and mobile site e2e tests stay green.
- Visual screenshots confirm no clipped terminal on mobile.

## Tests / Evidence

Required deterministic regression:

- Browser test: wait for live terminal completion, then assert mobile widths and
  terminal screen scroll width on 320/375/390px.

Commands:

```powershell
npm test --prefix site
npm run build --prefix site
npm run test:e2e --prefix site
npm run test:deploy-surface --prefix site
git diff --check
```

Actual evidence from implementation:

```text
Red-first:
- `npm run build --prefix site`
- `npm run test:e2e --prefix site -- --grep "mobile live terminal fits"`
- Failed at 320/375/390px because `.hero-inscription` and the live terminal
  cluster measured right edge 437.45px on mobile viewports.

Green:
- `npm run build --prefix site`: passed.
- `npm run test:e2e --prefix site -- --grep "mobile live terminal fits"`:
  3 passed.
- `npm test --prefix site`: 46 passed.
- `npm run test:e2e --prefix site`: 28 passed.
- `npm run test:deploy-surface --prefix site`: 1 passed.
- `git diff --check`: passed.
```

Visual evidence:

```text
local/site-visual/t960-mobile-terminal-post/width-320.png
local/site-visual/t960-mobile-terminal-post/width-375.png
local/site-visual/t960-mobile-terminal-post/width-390.png
```

Post-fix measured result:

```text
width=320: live-terminal right=308, terminal-screen overflow=0
width=375: live-terminal right=363, terminal-screen overflow=0
width=390: live-terminal right=378, terminal-screen overflow=0
```

Implementation:

```text
The live terminal answer copy was shortened while preserving the truth claim.
Under the existing mobile breakpoint, terminal screens now use pre-wrap and
anywhere wrapping with hidden horizontal overflow, and the terminal/footer
cluster is width-capped to its container. The accessible terminal summary was
updated to match the visible copy.
```

## Known Risks

- Wrapping all terminal output can make terminal examples less literal. Keep the
  wrapping scoped to mobile if desktop terminal rhythm would degrade.
