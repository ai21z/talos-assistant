# [T958-open-medium] Docs navigation must distinguish visible docs from intentional secondary docs

Status: open
Priority: medium

## Evidence Summary

- Source: local docs/nav inventory
- Date: 2026-07-04
- Branch under ticket: `v0.9.0-beta-dev`
- Talos version / commit: 0.10.8 /
  `9d7174ee9129c0a566d3a1656adf6d7894f54f5d`
- Code under review:
  - `docs/user/**/*.md`
  - `site/docs.html`
  - `site/src/docs.js`
  - `site/test/site.test.js`
- Verification status: confirmed by filesystem and test inspection

Observed:

```text
docs/user slugs: 20
sidebar nav slugs: 14
missing from sidebar: index and five model-profiles/* docs
```

Existing tests already assert:

- every public user doc exists and has an H1,
- every internal Markdown link resolves,
- `beta-best-practices` and `retrieval-and-vectors` are visible in the docs
  sidebar and landing.

Remaining ambiguity:

```text
The nested model-profile docs are bundled and routable, but not sidebar-visible.
That may be correct. The gap is that the intent is not encoded as a clear
contract: future docs can become invisible accidentally unless they happen to
be covered by the current hardcoded list.
```

## Classification

Primary taxonomy bucket: `PUBLIC_DOCS_TRUTH`

Secondary buckets:

- `SITE_NAVIGATION`
- `DOCS_INFORMATION_ARCHITECTURE`

Blocker level: candidate follow-up

Why this level:

```text
This is not currently false documentation. It is a coverage-contract weakness:
the docs site should make it mechanically clear which pages are first-class
navigation entries and which are intentionally secondary/deep-linked pages.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The docs site has a good single-source Markdown import model, but navigation
intent lives partly in HTML, partly in `docs.js`, and partly in tests. A small
explicit allowlist/manifest for sidebar-visible and secondary docs would make
coverage drift harder.
```

Likely code/document areas:

- `site/docs.html`
- `site/src/docs.js`
- `site/test/site.test.js`

Why a one-off patch is insufficient:

```text
Adding one missing link does not define the policy. The useful invariant is
that every bundled doc is either visible in primary docs navigation, visible on
the landing, or intentionally secondary with a reason.
```

## Goal

```text
Every `docs/user/**/*.md` source has explicit public navigation intent.
```

## Non-Goals

- No redesign of the docs page.
- No requirement that model-profile docs appear in the main sidebar if that
  would clutter the user path.
- No changing source-backed docs import behavior.

## Implementation Notes

- Add a small docs navigation contract test that classifies all doc slugs as:
  - landing/sidebar visible,
  - docs overview (`index`),
  - intentional secondary/deep-link pages such as `model-profiles/*`.
- Prefer one manifest or helper in tests rather than duplicated regex lists.
- If model-profile docs remain secondary, verify they are linked from
  `model-setup.md` and routable by hash route.

## Architecture Metadata

Capability:

- public docs navigation

Operation(s):

- static docs routing/navigation

Owning package/class:

- site docs shell and static contract tests

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium for docs usefulness, none for workspace mutation
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: static tests prove navigation intent for each doc
- Verification profile: site static tests
- Repair profile: no runtime/model repair

Outcome and trace:

- Outcome/truth warnings: avoid invisible public docs unless intentionally
  secondary
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: test helper or small manifest
- Forbidden: broad docs IA redesign

## Acceptance Criteria

- Every `docs/user/**/*.md` slug is covered by a navigation-intent test.
- `beta-best-practices` and `retrieval-and-vectors` remain visible in the
  docs navigation.
- `model-profiles/*` docs are either sidebar-linked or explicitly classified
  as secondary/deep-linked and linked from model setup.
- Unknown doc routes still render the existing not-found page.

## Tests / Evidence

Required deterministic regression:

- `site/test/site.test.js`

Commands:

```powershell
npm test --prefix site
```

## Known Risks

- Overloading the sidebar with all model-profile pages may reduce usability.
  The test should enforce intent, not force clutter.

## Known Follow-Ups

- If model-profile pages become a central onboarding surface, revisit whether
  they deserve a grouped sidebar section.

