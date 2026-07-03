# [T934-done-medium] Public docs and site release truth

Status: done
Priority: medium

## Evidence Summary

- Source: release-readiness review and docs/site inspection
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - `README.md` has public install snippets and Ubuntu/WSL wizard content, but
    the front door still needs a clearer route to user docs.
  - `site/index.html` links `docs/user/*`, so the broad claim "user docs are
    unreachable" is false if applied to the site.
  - `site/index.html` still contains `v0.10.6` demo text while the branch is
    `0.10.7` with unreleased 0.10.8-bound work.
  - `docs/public-installation.md` states public Windows installers must be
    signed and GitHub Releases are the canonical artifact host.
  - Public install docs mix future winget, GitHub Release, source/developer
    setup, and setup wizard paths; this needs a final truth pass before any
    public artifact.

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Secondary buckets:

- `UNSUPPORTED_CAPABILITY`
- `RELEASE_HYGIENE`
- `UX`

Blocker level: candidate follow-up before public beta announcement

Why this level:

```text
Talos's codebase is trust-positioned. Public docs and site copy must not imply
live winget, live releases, Linux one-command install, signed artifacts, or a
specific version before those facts exist.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Release truth belongs in a small set of public front-door surfaces: README,
public installation docs, user docs, and site copy. Those surfaces should share
the same live/not-live state and version policy, with no stale demo versions.
```

Likely code/document areas:

- `README.md`
- `docs/public-installation.md`
- `docs/user/`
- `site/index.html`
- site tests, if present

## Goal

```text
Make the public-facing docs and site accurately describe what can be installed
today, what is planned, what requires source/developer setup, and what version
the examples represent.
```

## Non-Goals

- No release artifact publication.
- No winget submission.
- No broad marketing rewrite.
- No weakening README limitation language.
- No claiming native Linux package-manager packaging.
- No claiming live Ubuntu/WSL x64 tarball publication before GitHub Release
  assets exist.

## Architecture Metadata

Capability:

- public documentation and install truth

Operation(s):

- document
- validate links/version claims

Owning package/class:

- README/docs/site

New or changed tools:

- none unless adding docs/site truth tests

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: not applicable
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: docs/site claims cite actual artifact/release state
- Verification profile: docs tests/site tests/wiki lint
- Repair profile: stale public claim becomes a ticket or test

Outcome and trace:

- Outcome/truth warnings: public copy must distinguish planned from live
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: focused docs/site copy and tests
- Forbidden: full site redesign

## Acceptance Criteria

- README has a clear user-docs entry point.
- Site and README do not show stale released/demo version claims.
- Public install docs distinguish:
  - live source/developer setup;
  - Ubuntu/WSL wizard setup from source/developer installs;
  - planned Ubuntu/WSL x64 tarball lane after GitHub Release assets exist;
  - planned Windows winget;
  - GitHub Release prerequisites;
  - signed vs unsigned beta policy.
- Docs do not claim winget/release artifacts before they exist.
- Site tests or docs checks pin the highest-risk public install/status claims
  if a suitable test owner exists.

## Resolution

- `README.md` now exposes a direct user-docs entry point for
  `docs/user/index.md`, `docs/user/quickstart.md`, and
  `docs/public-installation.md`.
- `site/index.html` no longer renders stale `v0.10.6`; the terminal demo text
  follows the current `0.10.7` branch version.
- Landing and in-site docs now distinguish current source/developer setup from
  planned Windows x64 and Ubuntu/WSL x64 public artifact targets.
- Public copy states that release paths are not live until GitHub Release
  assets exist and continues to reject model/server bundling claims.
- User docs now describe Ubuntu/WSL x64 as a tarball target once assets exist,
  while keeping Linux source/developer setup as the current reliable path and
  avoiding native package-manager claims.
- Site regression coverage pins the current-version hero, the Ubuntu/WSL x64
  tarball target wording, the non-live GitHub Release boundary, and the README
  user-docs entry point.

## Tests / Evidence

Required deterministic regression:

- Unit test: site/docs truth test where practical.
- Integration/executor test: docs link check if available.
- JSON e2e scenario: not applicable.
- Trace assertion: not applicable.

Commands:

```powershell
npm test --prefix site
npm run build --prefix site
.\gradlew.bat test --tests "dev.talos.docs.*" --no-daemon
git diff --check
```

If site files change:

```powershell
.\gradlew.bat check --no-daemon
```

## Known Risks

- Marketing copy can drift faster than release state.
- Version snippets in static site HTML can become stale after every candidate
  cut unless pinned or generated.

## Known Follow-Ups

- Extend trust-claims/honesty checks to cover the site if not already covered.
