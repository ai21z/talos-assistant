# [T341-done-high] Beta-Dev CI Hard Gate

Status: done
Priority: high
Date: 2026-05-21
Branch: `T341`
Candidate version: `talosVersion=0.9.9`
Predecessor: `[T334-T340] architecture hygiene ratchet baseline and scanner`

## Evidence Summary

- Source: PR review gate after the architecture-ratchet packet was published.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on `T341`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- Raw transcript path: none.
- Trace path or `/last trace` summary: not applicable.
- File diff summary: added one minimal GitHub Actions workflow for the
  `v0.9.0-beta-dev` lane, corrected the public site install copy required by
  the existing release packaging contract, force-tracked the public installation
  document that the contract already reads, and fixed a Windows sandbox
  canonicalization false-denial found by the first Windows CI run.
- Approval choices: not applicable.
- Checkpoint id: not applicable.
- Verification status: focused release-contract and CI-exposed runtime tests
  passed locally; first GitHub check-run creation succeeded, then exposed
  pre-existing Linux unit-test failures and a Windows short-path sandbox
  false-denial, so the beta gate was corrected to Windows x64 and the concrete
  Windows failure was fixed. The final workflow also opts into GitHub's Node 24
  JavaScript-action runtime and explicit Windows 2025 + VS2026 image label to
  remove current GitHub Actions migration warnings.

## Problem

The architecture-ratchet PR had no repository-hosted CI signal:

- GitHub reported `0` check runs for the PR head commit.
- GitHub reported `0` check suites for the PR head commit.
- GitHub reported `0` workflow runs for the PR branch.
- `origin/v0.9.0-beta-dev` did not contain a workflow under
  `.github/workflows/`.

Local `check` had passed for the architecture packet, but the PR could not
satisfy the intended review-before-merge standard without a GitHub Actions hard
gate.

While verifying this ticket, the existing
`PublicInstallPackagingContractTest.docsAndSiteDescribeInstallBoundary` test
also exposed pre-existing site copy drift: `site/index.html` lacked the exact
future winget command, the `Windows x64` support boundary phrase, and the exact
`llama.cpp server or model weights` limitation phrase. T341 fixes that site
copy because the new CI gate must start green.

The first Windows check run then exposed two concrete repository issues:

- `docs/public-installation.md` existed locally but was hidden by local
  `.git/info/exclude`, so the remote checkout could not satisfy the existing
  packaging contract test.
- GitHub-hosted Windows temp workspaces used a short-name path segment such as
  `RUNNER~1`, while `Sandbox` canonicalized the workspace root through
  `toRealPath()`. Missing child paths under that workspace were compared in
  short-path form against the long real workspace root and were falsely denied
  as `path escapes workspace`.

## Goal

Add the smallest useful CI gate for beta-dev PRs: Windows x64, Java 21, and
`.\gradlew.bat check --no-daemon`.

## Non-Goals

- No SonarCloud setup.
- No Snyk setup.
- No Qodana Cloud setup.
- No branch protection change in this commit.
- No architecture-ratchet code changes.
- No cross-platform index/RAG refactor.
- No changelog edit; the `Unreleased` ledger is introduced by the separate
  architecture-ratchet packet.

## Implementation Summary

Added `.github/workflows/beta-dev-ci.yml`:

- runs on pull requests targeting `v0.9.0-beta-dev`;
- includes `ready_for_review` so a draft PR can be checked after CI lands;
- runs on pushes to `v0.9.0-beta-dev`;
- runs on `windows-2025-vs2026` because the public beta install support
  boundary is Windows x64, the repository work-test cycle is Windows-first, and
  GitHub is already migrating `windows-latest` to that image family;
- installs Java 21 with Temurin;
- uses the Gradle setup action;
- runs the hard gate as named Gradle steps:
  `test`, `e2eTest`, coverage/artifact canaries, and final `check`.

After the first successful Windows check emitted GitHub Actions migration
warnings, the workflow was moved to the explicit `windows-2025-vs2026` image and
sets `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24=true`. This tests the upcoming GitHub
runner defaults before they become implicit platform changes.

The first remote Linux run proved GitHub check creation, but failed in existing
unit tests around index/RAG path matching and policy behavior. That is real
cross-platform debt, but it is not the right scope for the beta-dev CI bootstrap.
T341 therefore gates the documented Windows x64 beta path first. A
failure-reporting step converts JUnit XML failures into GitHub annotations so
future Windows failures expose concrete test names and messages through the
public annotations API.

Updated `site/index.html` to keep the public install copy aligned with the
existing release packaging contract test:

- exact future command: `winget install --id TalosProject.TalosCLI -e`;
- public beta boundary: `Windows x64`;
- installer limitation: `llama.cpp server or model weights`.

Force-tracked `docs/public-installation.md` because the release packaging
contract already treats it as public release evidence.

Updated `Sandbox` missing-path canonicalization so a candidate under a real
workspace root is reconstructed from the nearest existing ancestor's real path
before the `startsWith(workspaceReal)` check. This preserves fail-closed
workspace-boundary behavior while avoiding false denial for Windows short-path
aliases on paths that do not exist yet.

## Architecture Metadata

Capability:

- CI evidence for beta-dev review gates.

Operation(s):

- Repository-hosted execution of the branch's Gradle `check` lifecycle.
- On the current beta-dev base this covers the existing build, unit test, E2E,
  coverage, and generated-artifact canary checks.
- When the T334-T340 architecture packet is evaluated against this workflow, its
  added release-ledger and architecture-boundary tasks are included because they
  are wired into that branch's `check` lifecycle.

Owning file:

- `.github/workflows/beta-dev-ci.yml`.
- Note: the repository ignores `.github/` by default, so the workflow file is
  intentionally force-added as the only `.github/workflows/` file in this
  ticket.

Risk, approval, and protected paths:

- Risk level: low runtime risk; medium workflow risk because CI failures now
  become visible review evidence.
- Approval behavior: not changed.
- Protected path behavior: strictness unchanged; path canonicalization for
  non-existing in-workspace children is corrected before boundary comparison.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: local `check` plus GitHub Actions run after push.
- Verification profile: `git diff --check`, local `check`, then GitHub check
  run on the `T341` branch.
- Repair profile: concrete CI failures only.

## Acceptance Criteria

- Branch and PR metadata use ticket-only identifiers, not agent names.
- A minimal beta-dev GitHub Actions workflow exists.
- The workflow runs the Gradle `check` hard gate on Windows x64 and Java 21,
  with named prerequisite steps for useful failure localization.
- The workflow opts into the current GitHub Actions Node 24 and Windows
  2025/VS2026 migration path instead of leaving migration warnings unresolved.
- The workflow triggers for PRs into `v0.9.0-beta-dev`.
- The workflow includes `ready_for_review` for draft-to-ready PR checks.
- Local `git diff --check` passes.
- Local `.\gradlew.bat check --no-daemon` passes.
- GitHub creates a pull-request check run for `T341`.

## Result

Local acceptance criteria satisfied. Initial remote check-run creation was
verified after push and PR creation; remote pass/fail evidence remains the PR
gate.

## Verification

Focused regression:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest.docsAndSiteDescribeInstallBoundary" --no-daemon
```

Result: passed.

Diff hygiene:

```powershell
git diff --check
```

Result: passed with the repository's existing LF-to-CRLF warning on
`site/index.html`.

Full local check:

```powershell
.\gradlew.bat check --no-daemon
```

Result: passed.

## Work-Test Cycle Notes

Infrastructure hardening loop. No version bump. No candidate packet. No live
audit.

## Known Follow-Ups

- After T341 lands in `v0.9.0-beta-dev`, mark the T334-T340 architecture PR
  ready for review to trigger its CI check.
- Configure branch protection manually after the first successful run if
  `Gradle check (Java 21)` should become a required status check.
- Restore or redesign advisory CodeQL, Qodana, Snyk, and Sonar workflows only in
  separate tickets because they involve security-event permissions, external
  services, or secrets.
