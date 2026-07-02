# [T919-done-medium] Public repo identity truth

Status: done
Priority: medium

## Evidence Summary

- Source: source review
- Date: 2026-07-02
- Talos version / commit: 0.10.6 / 7f75eb3070a6f32be890787decca0254e5972d4b
- Verification status: tracked files still point at `ai21z/talos-cli`; GitHub repo is `ai21z/talos-assistant`

Expected behavior:

```text
Public docs, installer defaults, site links, and packaging contract tests should
point at the current GitHub repository, while the CLI/package identity remains
talos / talos-cli / TalosProject.TalosCLI.
```

Observed behavior:

```text
Tracked public surfaces still contain github.com/ai21z/talos-cli and
ai21z/talos-cli in installer/site/docs/test contract locations.
```

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Blocker level: candidate follow-up

## Architectural Hypothesis

Architectural hypothesis:

```text
This is public identity drift, not a product rename. Update repository URLs and
tests without changing command names, package IDs, winget moniker, or install
copy that intentionally says talos-cli.
```

Likely code/document areas:

- `tools/install-talos.ps1`
- `site/`
- `src/test/java/dev/talos/release/PublicInstallPackagingContractTest.java`
- public docs that state the repository slug

## Goal

```text
The public repository identity is truthful and tested after the GitHub rename.
```

## Non-Goals

- No CLI/package/winget identity rename.
- No history rewrite or GitHub release creation.

## Architecture Metadata

Capability:

- public installation / repository discovery

Operation(s):

- documentation and packaging contract update

Owning package/class:

- release packaging docs/tests

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: not applicable
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: test contract and site tests
- Verification profile: focused release/site tests
- Repair profile: not applicable

Outcome and trace:

- Outcome/truth warnings: public install URLs must not 404 due stale slug
- Trace/debug fields: none

Refactor scope:

- Allowed: URL/copy correction only.
- Forbidden: command/package rename.

## Acceptance Criteria

- Tracked public GitHub URLs resolve to `ai21z/talos-assistant`.
- CLI/package/winget identity remains unchanged where intentional.
- Public install packaging contract and site tests are updated.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
npm test --prefix site
git diff --check
```

Observed evidence:

```text
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
BUILD SUCCESSFUL

npm test --prefix site
tests 33, pass 33, fail 0
```

Notes:

```text
npm run test:e2e --prefix site was attempted after updating the Playwright
assertion, but the run was contaminated by serving a different page
("Aris Zounarakis | Software Engineer in Barcelona") and failed before the
repo-link assertion could prove this ticket. The static site contract is the
scoped T919 acceptance gate.
```
