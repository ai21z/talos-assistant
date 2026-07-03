# [T933-open-high] CI branch protection and release trigger contract

Status: open
Priority: high

## Evidence Summary

- Source: GitHub API and workflow inspection
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - GitHub API response for `repos/ai21z/talos-assistant/branches/main/protection`
    returned `Branch not protected` (HTTP 404).
  - `.github/workflows/beta-dev-ci.yml` runs CI on `main`,
    `v0.9.0-beta-dev`, `codex/**`, and `feature/**`.
  - There is no release/tag workflow trigger.

## Classification

Primary taxonomy bucket: `VERIFICATION`

Secondary buckets:

- `RELEASE_HYGIENE`
- `OUTCOME_TRUTH`

Blocker level: release blocker before public beta/tag

Why this level:

```text
A public trust-positioned repo should not be able to move `main` past red CI
accidentally. The tree currently has CI, but GitHub settings do not require it
for `main`.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Talos needs branch protection and CI trigger policy that match its branch model:
`v0.9.0-beta-dev` as the integration branch and `main` as public stable. Release
artifact workflows should be explicit and gated, not accidental side effects of
ordinary branch pushes.
```

Likely code/document areas:

- GitHub repository settings
- `.github/workflows/beta-dev-ci.yml`
- future `.github/workflows/release-candidate.yml`
- `docs/public-installation.md`
- `work-cycle-docs/work-test-cycle.md`

## Goal

```text
Make green CI structurally required for `main`, keep beta-dev checks visible,
and define release workflow triggers so artifact publication cannot bypass the
QA gate.
```

## Non-Goals

- No history rewrite.
- No forced merge strategy change unless explicitly approved.
- No public release/tag in this ticket.
- No branch deletion.

## Architecture Metadata

Capability:

- CI/release governance

Operation(s):

- configure repository settings
- adjust workflow triggers
- verify checks

Owning package/class:

- GitHub repo settings and workflow YAML

New or changed tools:

- possible release workflow

Risk, approval, and protected paths:

- Risk level: high for release governance
- Approval behavior: repository settings require owner action or authenticated
  GitHub API call
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: GitHub API evidence for branch protection and required
  checks
- Verification profile: push/PR CI observed green on relevant branch
- Repair profile: do not release while protection is absent

Outcome and trace:

- Outcome/truth warnings: do not claim `main` is protected without GitHub API
  evidence
- Trace/debug fields: not applicable

Refactor scope:

- Allowed: workflow trigger cleanup
- Forbidden: unrelated CI job rewrites

## Acceptance Criteria

- `main` has branch protection requiring the Talos CI checks that define public
  main health.
- The chosen beta-dev branch policy is documented: whether direct pushes are
  allowed or checks are required before merge.
- CI triggers match real branch conventions and do not keep dead patterns
  without reason.
- Release workflow trigger policy is documented and consistent with T929/T930.
- GitHub API verification is captured after settings change.

## Tests / Evidence

Required deterministic regression:

- Unit test: none unless workflow contract tests exist.
- Integration/executor test: CI workflow syntax and observed Actions run.
- JSON e2e scenario: not applicable.
- Trace assertion: not applicable.

Commands/evidence:

```powershell
gh api repos/ai21z/talos-assistant/branches/main/protection
gh run list --branch main --limit 5
git diff --check
```

## Known Risks

- Branch protection settings are outside the git tree; they can drift unless
  documented and periodically checked.
- Overly strict protection can block emergency fixes if required checks are
  flaky.

## Known Follow-Ups

- Add a small repo-health script that checks branch protection and latest CI.
