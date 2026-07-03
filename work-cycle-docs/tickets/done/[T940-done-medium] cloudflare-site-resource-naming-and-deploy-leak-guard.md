# [T940-done-medium] Cloudflare site resource naming and deploy leak guard

Status: done
Priority: medium

## Evidence Summary

- Source: Owner request after purchasing `taloslocal.com` in Cloudflare
- Date: 2026-07-03
- Talos version / commit: 0.10.8 /
  60c523679e658d6e85e03ee201687bd0312b7e8b
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - Cloudflare API read-only check showed `taloslocal.com` is active.
  - Existing Pages project list did not include a Talos project before this
    ticket.
  - The deployable site surface did not contain `codex`, `claude`, or
    `copilot`, but no deploy-output gate existed to keep it that way.

## Classification

Primary taxonomy bucket: `RELEASE_HYGIENE`

Secondary buckets:

- `PUBLIC_SITE`
- `DEPLOYMENT`
- `TRUTHFULNESS`

Blocker level: pre-public-site guardrail

Why this level:

```text
This is not a runtime product bug. It is a public deployment hygiene control:
Cloudflare resources must be explicitly named as Talos resources, and the
deployed static output must not leak assistant/tool provenance names.
```

## Goal

```text
Create or verify the Cloudflare site resource with explicit `taloslocal`
naming, and add a deterministic site deploy-surface check that fails if
`site/dist` contains `codex`, `claude`, or `copilot`.
```

## Non-Goals

- No production custom-domain cutover.
- No GitHub Release, tag, winget, or public release artifact publication.
- No Git-connected Cloudflare auto-deploy.
- No broad public-site copy rewrite.

## Architecture Metadata

Capability:

- public website deployment hygiene

Operation(s):

- provision Cloudflare Pages project
- scan built static output before deployment

Owning package/class:

- Cloudflare Pages project `taloslocal`
- `site/test`
- `.github/workflows/release-staging.yml`

New or changed tools:

- new deploy-surface site test script

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: external Cloudflare write is explicitly owner-requested
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: Cloudflare API result, static test red/green evidence,
  and release-staging workflow contract
- Verification profile: `npm test`, `npm run build`, deploy-surface test,
  `TicketHygieneTest`
- Repair profile: fail before deployment if forbidden provenance markers appear
  in `site/dist`

Outcome and trace:

- Outcome/truth warnings: do not claim public production deployment or custom
  domain binding from this ticket
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: Cloudflare Pages resource creation, site package scripts, static site
  tests, release-staging site check, changelog/ticket evidence
- Forbidden: production deploy, domain cutover, broad release workflow rewrite,
  runtime Talos behavior changes

## Acceptance Criteria

- Cloudflare has a site resource named exactly `taloslocal`; no generated
  `codex`/assistant/tool-derived resource name is used.
- The Cloudflare project is not connected to Git auto-deploy in this ticket.
- The project does not bind `taloslocal.com` as a production custom domain in
  this ticket.
- `site/package.json` exposes a `test:deploy-surface` script.
- The deploy-surface test scans `site/dist` and fails on case-insensitive
  `codex`, `claude`, or `copilot`.
- Release staging runs the deploy-surface test after `npm run build --prefix
  site`.
- Site tests fail before the script/workflow exists and pass after the fix.

## Tests / Evidence

Required deterministic regression:

```powershell
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
.\gradlew.bat test --tests "dev.talos.docs.TicketHygieneTest" --no-daemon
git diff --check
```

Required hosted/resource evidence:

```text
Cloudflare Pages project name: taloslocal
Cloudflare Pages project source: null
Cloudflare Pages domains: taloslocal.pages.dev only
```

## Known Risks

- A Pages project creates a public `*.pages.dev` hostname even before production
  domain cutover. This ticket intentionally keeps the generated hostname
  product-owned (`taloslocal.pages.dev`) and does not deploy content.
- Internal repository history may still mention assistant/tool names. This
  ticket only gates deployable static website output.

## Resolution

Implemented on `v0.9.0-beta-dev` for the 0.10.8 public-site staging lane:

- Created a Cloudflare Pages project named exactly `taloslocal` via the
  Cloudflare API plugin.
- The project has `source: null`, so it is not connected to Git auto-deploy.
- The project domains list contains only `taloslocal.pages.dev`; no production
  `taloslocal.com` binding was added in this ticket.
- Added `site/test/deploy-surface.test.js`, which scans built text artifacts
  under `site/dist` and fails on case-insensitive `codex`, `claude`, or
  `copilot`.
- Added `npm run test:deploy-surface --prefix site` after `npm run build
  --prefix site` in release staging.
- Updated the site terminal copy from `v0.10.7` to the current `0.10.8`, because
  the existing site contract already failed on that stale version text during
  the red run.

Red evidence:

```text
npm test --prefix site
```

failed before implementation because `test:deploy-surface` was missing and
release staging did not scan `site/dist` after building the site. The same red
run also exposed stale `v0.10.7` terminal copy against `talosVersion=0.10.8`.

Green verification:

```text
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
.\gradlew.bat test --tests "dev.talos.docs.TicketHygieneTest" --no-daemon
git diff --check
```

All passed. `git diff --check` printed only Git line-ending normalization
warnings for touched site files, not whitespace errors.

Cloudflare read-back evidence:

```text
project.name=taloslocal
project.subdomain=taloslocal.pages.dev
project.domains=[taloslocal.pages.dev]
project.source=null
project.latest_deployment=null
workerScriptIds=[]
```
