# [T920-done-medium] Public metadata legal docs truth

Status: done
Priority: medium

## Evidence Summary

- Source: source review
- Date: 2026-07-02
- Talos version / commit: 0.10.6 / 7f75eb3070a6f32be890787decca0254e5972d4b
- Verification status: public metadata files contain stale LOQ-J/template/GitLab copy

Expected behavior:

```text
Public-facing repository metadata should describe Talos, GitHub, current local
developer commands, and known dependency notices accurately.
```

Observed behavior:

```text
NOTICE contains a placeholder copyright owner, CONTRIBUTING references LOQ-J,
GitLab and stale commands, and third-party notices contain guessed license text
and omit major tracked dependencies.
```

## Classification

Primary taxonomy bucket: `OUTCOME_TRUTH`

Blocker level: candidate follow-up

## Architectural Hypothesis

Architectural hypothesis:

```text
This is public docs truth, not runtime behavior. Fix tracked public metadata
only. The ignored local `.github/CARRY_OVER_PROMPT.md` is not a public tracked
file in this repo state and should remain untouched.
```

Likely code/document areas:

- `NOTICE`
- `CONTRIBUTING.md`
- `THIRD-PARTY-NOTICES.md`
- user onboarding docs where stale commands/model setup are confirmed

## Goal

```text
A stranger reading the public repo sees truthful metadata and onboarding copy.
```

## Non-Goals

- No legal overclaim beyond repository-known dependency data.
- No ignored local AI prompt cleanup.
- No release/tag publication.

## Architecture Metadata

Capability:

- public repository documentation

Operation(s):

- documentation update

Owning package/class:

- repository root docs

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: not applicable
- Protected path behavior: not applicable

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: source review and doc tests where available
- Verification profile: focused docs/site tests, ticket hygiene
- Repair profile: not applicable

Outcome and trace:

- Outcome/truth warnings: avoid unsupported legal/license claims
- Trace/debug fields: none

Refactor scope:

- Allowed: public metadata docs only.
- Forbidden: runtime behavior changes.

## Acceptance Criteria

- `NOTICE` no longer contains template placeholders.
- `CONTRIBUTING.md` describes Talos/GitHub/current commands.
- `THIRD-PARTY-NOTICES.md` covers major dependencies without guessed wording.
- Ignored `.github/CARRY_OVER_PROMPT.md` remains untouched unless later proven tracked/public.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.tickets.TicketHygieneTest" --no-daemon
git diff --check
```

Observed evidence:

```text
.\gradlew.bat test --tests "dev.talos.release.PublicRepositoryMetadataTest" --no-daemon
BUILD SUCCESSFUL
```
