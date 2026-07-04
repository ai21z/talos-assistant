# [T959-done-low] Desktop scroll vein can reveal section names without replacing the nav bar

Status: done
Priority: low

## Evidence Summary

- Source: owner design direction after site/docs review
- Date: 2026-07-04
- Branch under ticket: `v0.9.0-beta-dev`
- Talos version / commit: 0.10.8 /
  closing ticket commit
- Code under review:
  - `site/index.html`
  - `site/src/main.js`
  - `site/src/styles.css`
  - `site/test/e2e/site.spec.js`
- Verification status: implemented and verified locally

Owner direction:

```text
Keep the current nav bar. On desktop only, while scrolling, the vein can show
section names.
```

Expected behavior:

```text
The current nav remains the primary navigation. The scroll-reactive vein may
act as a desktop-only progress/section-label companion, appearing only after
scroll begins and never on mobile.
```

## Classification

Primary taxonomy bucket: `SITE_UX`

Secondary buckets:

- `ACCESSIBILITY`
- `VISUAL_POLISH`

Blocker level: future milestone

Why this level:

```text
This is a useful S-tier polish item, not a release-evidence blocker. It should
not delay T954/T955 or public artifact staging unless the site needs a visual
polish pass before launch.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The existing site already has a section map, sticky nav, and scroll-reactive
visual vein. The safe enhancement is progressive disclosure over the vein
using existing section anchors and active-section state, with desktop media
queries and no mobile layout impact.
```

Likely code/document areas:

- `site/index.html`
- `site/src/main.js`
- `site/src/styles.css`
- `site/test/site.test.js`
- `site/test/e2e/site.spec.js`

Why a one-off patch is insufficient:

```text
Adding labels visually without desktop/mobile, reduced-motion, and no-layout-
shift guards can recreate the header/content collision class the review
already flagged. This should be implemented as a small, tested enhancement.
```

## Goal

```text
Desktop users get an elegant scroll-progress companion that names the current
landing-page section without replacing or crowding the primary nav.
```

## Non-Goals

- No replacing the current navbar.
- No mobile vein labels.
- No scroll hijacking.
- No content hidden behind animation.
- No new framework.

## Implementation Notes

- Use existing section IDs and short labels:
  `Overview`, `Execution`, `Turn UI`, `Local Boundaries`, `Good Fits`, `Docs`.
- Desktop-only via CSS media query, likely `min-width >= 1024px` or the site's
  existing desktop breakpoint.
- Reveal only after scroll threshold or when a section observer has activated.
- Avoid layout shift by reserving or overlaying a fixed rail area.
- Respect `prefers-reduced-motion`.
- Add Playwright assertions for desktop visibility and mobile absence.

## Architecture Metadata

Capability:

- public site navigation/progress affordance

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
- Evidence obligation: visual/e2e tests prove desktop-only behavior
- Verification profile: site static tests plus Playwright e2e
- Repair profile: no runtime/model repair

Outcome and trace:

- Outcome/truth warnings: labels must match actual section anchors
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: small section-observer/state helper if needed
- Forbidden: nav redesign or broad animation rewrite

## Acceptance Criteria

- Existing navbar remains visible and unchanged as primary navigation.
- Section labels appear on the vein only on desktop and only after scroll.
- Labels do not appear on mobile viewport tests.
- Active label matches the current section anchor.
- Reduced-motion users are not blocked by animation.
- No header/content overlap regression.

## Tests / Evidence

Required deterministic regression:

- Static test: site structure contains label source data without duplicating
  unsupported route names.
- Browser test: Playwright desktop and mobile assertions.

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
- `npm test --prefix site` failed on missing `has-scrolled` rail state.
- `npm run test:e2e --prefix site -- --grep "desktop vein rail reveals"` failed before implementation.

Green:
- `npm test --prefix site`: 46 passed.
- `npm run build --prefix site`: Vite production build succeeded.
- `npm run test:e2e --prefix site -- --grep "desktop vein rail reveals"`: 1 passed after rebuild.
- `npm run test:e2e --prefix site`: 25 passed.
- `npm run test:deploy-surface --prefix site`: 1 passed.
- `git diff --check`: passed.
```

Visual evidence:

```text
local/site-visual/t959-vein-labels/desktop-scrolled-vein-labels.png
local/site-visual/t959-vein-labels/mobile-scrolled-no-vein-labels.png
```

Implementation:

```text
The existing vein rail keeps the same section anchors and navbar. On desktop
viewports, `setupVeinRail()` adds `has-scrolled` after native scroll passes
the threshold, causing short section labels to fade in from the rail. The
active section label is brighter. Mobile viewports still hide the whole rail
through the existing breakpoint, and reduced-motion users get the state
without transition animation.
```

## Known Risks

- Over-labeling can make the distinctive site feel cluttered. Keep labels short
  and treat the vein as secondary navigation/progress, not a second navbar.

## Known Follow-Ups

- If this lands, capture desktop/mobile screenshots before staging the site.
