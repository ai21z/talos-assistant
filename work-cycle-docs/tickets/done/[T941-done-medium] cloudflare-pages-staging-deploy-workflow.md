# [T941-done-medium] Cloudflare Pages staging deploy workflow

Status: done
Priority: medium

## Evidence Summary

- Source: Follow-up after T940 and owner confirmation that `v0.9.0-beta-dev`
  was pushed and CI passed; hosted staging deploy evidence from GitHub Actions
- Date: 2026-07-03
- Talos version / commit: 0.10.8 /
  c53213a3b924b777d1620beeab3aed847caad644
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - Cloudflare Pages project `taloslocal` exists.
  - Project has `source: null`, so no Git auto-deploy is connected.
  - Initial project state had no deployment yet and only the
    `taloslocal.pages.dev` domain.
  - Cloudflare docs describe Direct Upload through Wrangler and preview
    deployments via `wrangler pages deploy <OUTPUT_DIRECTORY>
    --branch=<BRANCH_NAME>`.
  - GitHub Actions run `28679998449` deployed the `site-staging` preview from
    `v0.9.0-beta-dev` at commit `c53213a3b924b777d1620beeab3aed847caad644`.
  - Hosted preview `https://site-staging.taloslocal.pages.dev` returned HTTP
    200 with title `Talos | Local-first CLI workspace operator`.
  - Fetched preview HTML contained `Talos` and did not contain case-insensitive
    `codex`, `claude`, or `copilot`.

## Classification

Primary taxonomy bucket: `RELEASE_HYGIENE`

Secondary buckets:

- `PUBLIC_SITE`
- `DEPLOYMENT`
- `CI_CD`

Blocker level: pre-public-site staging control

Why this level:

```text
Talos needs a repeatable, reviewable staging deploy before binding the
production domain. A dashboard-only or auto-generated deployment would bypass
the same evidence discipline already used by release staging.
```

## Goal

```text
Add a manual GitHub Actions workflow that deploys the already-tested static
site to the Cloudflare Pages preview branch `site-staging` for project
`taloslocal`, without production domain binding or Git auto-deploy.
```

## Non-Goals

- No production `taloslocal.com` binding.
- No GitHub Release, tag, winget, or release artifact publication.
- No Cloudflare Git integration or automatic deploy-on-push.
- No broad site copy redesign.

## Architecture Metadata

Capability:

- public website staging deployment

Operation(s):

- checkout exact candidate SHA
- build static site
- scan deploy output
- deploy Cloudflare Pages preview

Owning package/class:

- `.github/workflows/site-staging.yml`
- `src/test/java/dev/talos/release/CiWorkflowContractTest.java`
- Cloudflare Pages project `taloslocal`

New or changed tools:

- new manual GitHub Actions workflow

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: manual `workflow_dispatch`; Cloudflare deployment requires
  explicit GitHub secret/variable
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: local workflow contract test, site build/leak-scan, and
  hosted workflow result when credentials are available
- Verification profile: `CiWorkflowContractTest`, site tests/build/leak scan,
  ticket hygiene
- Repair profile: fail before deployment if SHA/version/credential/output
  checks fail

Outcome and trace:

- Outcome/truth warnings: staging preview is not a public release and not a
  production-domain cutover
- Trace/debug fields: workflow run URL and Cloudflare preview URL

Refactor scope:

- Allowed: workflow YAML, workflow contract tests, ticket/changelog evidence
- Forbidden: production domain cutover, release asset publication, runtime Talos
  behavior changes

## Acceptance Criteria

- New workflow is manual-only (`workflow_dispatch`) and requires `target_sha`
  plus `version` inputs.
- Workflow checks out `inputs.target_sha` and verifies the checked-out SHA and
  `talosVersion`.
- Workflow builds the site from `site/` and runs `npm test`, `npm run build`,
  and `npm run test:deploy-surface`.
- Workflow deploys with Wrangler Direct Upload to Cloudflare Pages project
  `taloslocal`.
- Workflow deploys to branch `site-staging`, producing a preview URL under
  `site-staging.taloslocal.pages.dev`; it must not deploy the production branch.
- Workflow requires explicit Cloudflare credentials from GitHub
  `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID`.
- Workflow contains no generated assistant/tool resource names.
- Workflow does not bind `taloslocal.com` or create a GitHub Release/tag/public
  artifact.
- Focused contract test fails before the workflow exists and passes after it.

## Tests / Evidence

Required deterministic regression:

```powershell
.\gradlew.bat test --tests "dev.talos.release.CiWorkflowContractTest.siteStagingWorkflowDeploysOnlyNamedCloudflarePreview" --no-daemon
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
.\gradlew.bat test --tests "dev.talos.release.CiWorkflowContractTest" --tests "dev.talos.docs.TicketHygieneTest" --no-daemon
git diff --check
```

Required hosted evidence when credentials are present:

```powershell
gh workflow run site-staging.yml --repo ai21z/talos-assistant --ref v0.9.0-beta-dev -f target_sha=<sha> -f version=0.10.8
gh run watch <run-id> --repo ai21z/talos-assistant --exit-status
```

## Known Risks

- GitHub Actions cannot deploy until `CLOUDFLARE_API_TOKEN` and
  `CLOUDFLARE_ACCOUNT_ID` are configured in the repository. The workflow should
  fail early with a clear message if they are missing.
- A successful preview deploy creates a public preview hostname. This ticket
  intentionally keeps it under the non-production `site-staging` branch alias.

## Implementation Progress

- Added `.github/workflows/site-staging.yml`.
- Added workflow contract coverage in `CiWorkflowContractTest`.
- Configured repository variable `CLOUDFLARE_ACCOUNT_ID`.
- Owner configured repository secret `CLOUDFLARE_API_TOKEN`.
- Merged the workflow to `main` so GitHub Actions could register the manual
  workflow, then dispatched it against `v0.9.0-beta-dev`.

Red evidence:

```text
.\gradlew.bat test --tests "dev.talos.release.CiWorkflowContractTest.siteStagingWorkflowDeploysOnlyNamedCloudflarePreview" --no-daemon
```

failed with `NoSuchFileException` for `.github/workflows/site-staging.yml`,
proving the new contract was absent before implementation.

Local green verification:

```text
.\gradlew.bat test --tests "dev.talos.release.CiWorkflowContractTest.siteStagingWorkflowDeploysOnlyNamedCloudflarePreview" --no-daemon
.\gradlew.bat test --tests "dev.talos.release.CiWorkflowContractTest" --tests "dev.talos.docs.TicketHygieneTest" --no-daemon
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
git diff --check
```

All passed locally. `git diff --check` printed only Git line-ending
normalization warnings for touched files, not whitespace errors.

Hosted green verification:

```text
gh workflow run site-staging.yml --repo ai21z/talos-assistant --ref v0.9.0-beta-dev -f target_sha=c53213a3b924b777d1620beeab3aed847caad644 -f version=0.10.8
gh run watch 28679998449 --repo ai21z/talos-assistant --exit-status
```

Result:

```text
Run: https://github.com/ai21z/talos-assistant/actions/runs/28679998449
Conclusion: success
Head branch: v0.9.0-beta-dev
Head SHA: c53213a3b924b777d1620beeab3aed847caad644
Deployment alias URL: https://site-staging.taloslocal.pages.dev
Preview inspection: HTTP 200; no codex/claude/copilot strings in fetched HTML
```
