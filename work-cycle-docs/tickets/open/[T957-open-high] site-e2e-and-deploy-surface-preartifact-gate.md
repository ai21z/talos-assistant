# [T957-open-high] Site e2e and deploy-surface checks must gate pre-artifact decisions

Status: open
Priority: high

## Evidence Summary

- Source: local workflow/package inspection
- Date: 2026-07-04
- Branch under ticket: `v0.9.0-beta-dev`
- Talos version / commit: 0.10.8 /
  `9d7174ee9129c0a566d3a1656adf6d7894f54f5d`
- Code under review:
  - `.github/workflows/beta-dev-ci.yml`
  - `.github/workflows/release-staging.yml`
  - `.github/workflows/site-staging.yml`
  - `site/package.json`
- Verification status: confirmed by `rg`

Observed:

```text
site/package.json defines:
  test:deploy-surface = node --test test/deploy-surface.test.js
  test:e2e = playwright test

.github/workflows/release-staging.yml runs test:deploy-surface.
.github/workflows/site-staging.yml runs test:deploy-surface.
.github/workflows/beta-dev-ci.yml runs npm test and npm run build, but not
test:e2e or test:deploy-surface.
```

Expected behavior:

```text
Before public artifacts are staged or promoted, the site evidence path should
run the same checks that protect the deployed public surface: static site tests,
build, deploy-surface leak scan, and Playwright e2e where practical.
```

## Classification

Primary taxonomy bucket: `PUBLIC_ARTIFACT_GATE`

Secondary buckets:

- `SITE_QA`
- `CI_CONTRACT`
- `DEPLOYMENT_HYGIENE`

Blocker level: public-artifact blocker

Why this level:

```text
The site is the public front door for the release artifacts. A candidate can
currently pass normal beta-dev CI while skipping the deploy-surface leak scan
and browser-level site verification. That gap is acceptable for fast inner-loop
changes, but not for a public artifact decision.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
The repo has the right checks, but they are split between ordinary CI and
manual staging workflows. The release path needs a named pre-artifact gate that
cannot accidentally skip public-surface checks.
```

Likely code/document areas:

- `.github/workflows/beta-dev-ci.yml`
- `.github/workflows/release-staging.yml`
- `site/package.json`
- `site/playwright.config.js`
- `site/test/deploy-surface.test.js`

Why a one-off patch is insufficient:

```text
Running one manual Playwright pass does not create a repeatable release gate.
The workflow contract should name which site checks are required before staged
artifacts are accepted.
```

## Goal

```text
Public artifact staging cannot be treated as QA-complete unless site static
tests, build, deploy-surface leak scanning, and browser e2e checks have run on
the exact staged source.
```

## Non-Goals

- No production Cloudflare deployment.
- No GitHub Release or tag creation.
- No broad visual redesign.
- No moving all site e2e into every local inner-loop command if runtime cost is
  too high.

## Implementation Notes

- Decide whether Playwright e2e belongs in:
  - beta-dev push CI,
  - release-staging only,
  - or a dedicated pre-artifact workflow invoked before release-staging.
- Pin the decision in a workflow contract test.
- Preserve the existing `site/test/deploy-surface.test.js` leak guard against
  `codex`, `claude`, and `copilot` markers in `site/dist`.

## Architecture Metadata

Capability:

- public site QA gate

Operation(s):

- CI/build verification only

Owning files:

- GitHub Actions workflows
- site test scripts

New or changed tools:

- none expected

Risk, approval, and protected paths:

- Risk level: high for public release hygiene, none for workspace mutation
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: workflow logs prove required site checks ran
- Verification profile: workflow contract test plus local `npm` checks
- Repair profile: no runtime/model repair

Outcome and trace:

- Outcome/truth warnings: do not claim staged public surface has passed site
  QA unless all named checks ran
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: workflow/test-script wiring
- Forbidden: unrelated release workflow redesign

## Acceptance Criteria

- The pre-artifact path runs or requires:
  - `npm test --prefix site`
  - `npm run build --prefix site`
  - `npm run test:deploy-surface --prefix site`
  - `npm run test:e2e --prefix site` or a documented equivalent exclusion
- A workflow contract test fails if the required public-surface checks are
  removed from the chosen gate.
- The staging/deploy-surface leak guard remains active after `site/dist` is
  built.

## Tests / Evidence

Required deterministic regression:

- Workflow contract test, likely under `src/test/java/dev/talos/release/`
  or existing CI workflow tests.

Commands:

```powershell
npm test --prefix site
npm run test:e2e --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
.\gradlew.bat test --tests "dev.talos.release.*" --no-daemon
```

## Known Risks

- Playwright can be slower or more environment-sensitive than static tests.
  If it stays out of push CI, the public-artifact gate must still run it before
  any release decision.

## Known Follow-Ups

- Consider uploading Playwright screenshots/traces as staging evidence when
  the public release process becomes tag-driven.

