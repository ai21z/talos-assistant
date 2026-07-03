# [T929-done-high] Release QA gate before artifacts

Status: done
Priority: high

## Evidence Summary

- Source: release-readiness review, repo CI/work-test-cycle inspection, owner
  release policy decision
- Date: 2026-07-03
- Talos version / commit: 0.10.7 /
  cfd60a76eee85371e9435f14896b78213871cb74
- Branch: `v0.9.0-beta-dev`
- Evidence anchors:
  - `work-cycle-docs/work-test-cycle.md` defines the inner loop and versioned
    candidate loop.
  - `work-cycle-docs/full-e2e-audit-workflow.md` defines the fresh-workspace
    live-audit evidence model.
  - `.github/workflows/beta-dev-ci.yml` runs Windows checks and Linux command
    portability, but it does not prove manual PTY/live-model behavior.
  - `scripts/cut-candidate.ps1` cuts a candidate and runs automated gates, but
    it does not currently require a manual PTY or large-scale live audit packet
    before release artifact creation/publication.

## Classification

Primary taxonomy bucket: `VERIFICATION`

Secondary buckets:

- `OUTCOME_TRUTH`
- `AUDITABILITY`
- `RELEASE_HYGIENE`

Blocker level: release blocker

Why this level:

```text
The owner's release rule is explicit: before any public release artifact,
Talos must pass all existing automated tests plus manual PTY and large-scale
installed-product QA. Without a tracked gate, release artifacts can be built
from a tree that is green in CI but unproven in the real REPL, approval bank,
model surface, setup wizard, and installed-product lanes.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Talos needs a named release QA packet that sits between candidate readiness and
artifact publication. The packet must bind automated gates, manual PTY evidence,
large-scale two-model live audit evidence, installed-product smoke, and artifact
eligibility to one commit SHA and version.
```

Likely code/document areas:

- `scripts/cut-candidate.ps1`
- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/full-e2e-audit-workflow.md`
- `work-cycle-docs/work-test-cycle-step-by-step.md`
- `work-cycle-docs/tickets/open/`
- `docs/public-installation.md`

Why a one-off patch is insufficient:

```text
Running `check` once does not prove release readiness for Talos. The product
claim is installed local trust, so release QA must include actual PTY behavior,
approval prompts, traces, prompt-debug artifacts, model/tool behavior, and fresh
installed-product setup. The invariant must be documented and enforced as a
repeatable release gate, not remembered ad hoc.
```

## Goal

```text
Define and enforce a release QA gate: no public, signed, tagged, GitHub
Release-hosted, winget-linked, or release-named artifact may be produced until
the candidate SHA has full automated verification plus manual PTY and
large-scale installed-product live audit evidence.
```

## Important Boundary

```text
"No release artifact before QA" means no public artifact, signed artifact,
GitHub Release asset, draft GitHub Release asset, tag-bound asset,
winget-linked asset, or release-named distribution may be created before the QA
gate. Local or CI staging builds for QA are allowed only after automated gates
pass and must be clearly marked non-release/staging until manual QA completes.
```

Literal prohibition of all local artifacts before QA would make installer QA
impossible, because the installer path itself must be tested against a built
distribution. The enforceable boundary is publication/release identity, not
ephemeral local staging.

Artifact taxonomy:

- Local staging artifact: a build output under `build/` or another explicit
  local scratch path. Allowed after automated gates, never called a release.
- CI staging artifact: an Actions artifact attached to a workflow run for QA
  download only. Allowed after automated gates if named `staging` or
  `qa-staging`, never attached to a GitHub Release, tag, or winget manifest.
- Public release artifact: any asset attached to a GitHub Release, including a
  draft or prerelease; any signed artifact; any tag-bound artifact; any
  winget-linked artifact; any artifact whose filename or docs call it the
  release. Blocked until this QA gate passes.

## Non-Goals

- No public release/tag in this ticket.
- No version bump in this ticket.
- No relaxing the candidate loop.
- No replacing deterministic tests with manual QA.
- No counting redirected stdin as synchronized approval evidence.
- No treating a single model as full beta evidence unless explicitly scoped.
- No committing raw private transcripts or provider bodies.

## Required QA Packet

The packet must name:

- branch, commit SHA, version, build timestamp, and executable path tested;
- whether the working tree was clean before each release-relevant lane;
- automated commands run and results;
- installed product path and installer/source used;
- OS/platform for each installed smoke;
- backend, engine profile, model profile, and model artifact identity;
- manual PTY transcript path;
- prompt-debug artifact path;
- `/last trace` evidence path;
- provider-body path when model/tool-call behavior is being judged;
- approval prompt/denial/acceptance evidence;
- final workspace status/diff for mutation tests;
- artifact canary-scan result for manual testing roots;
- explicit skipped coverage with reason.

## Minimum Automated Gate

Before local or CI staging artifacts:

```powershell
git status --short
git diff --check
.\gradlew.bat clean check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
.\gradlew.bat talosQualitySummaries --no-daemon
```

The executor must inventory current Gradle and CI tasks before finalizing the
gate. If additional verification tasks exist at that time, include them rather
than relying on this static list.

The inventory must explicitly check for:

- release-relevant Gradle tasks beyond `check`;
- deterministic e2e/scenario/capability tasks not already reached by `check`;
- installer/package contract tests;
- live-audit runner scripts that are intended to produce release evidence;
- runtime artifact canary scan tasks;
- CI jobs that do not map to local Gradle tasks.

Skipped tasks require a named reason in the QA packet. "Not run" without a
reason is a failed release gate.

## Minimum Manual PTY Gate

Run against a clean installed product in a real terminal/PTY, not redirected
stdin:

- `talos --version`
- `talos status --verbose`
- `talos doctor --start`
- `talos`
- `/debug prompt on`
- `/status --verbose`
- `/mode`
- `/prompt`
- `/last trace` after every natural-language prompt
- `/prompt-debug last` and `/prompt-debug save` when prompt/tool claims matter
- approval denial, approval once, approval allow-in-session, and command
  approval paths
- `/session clear`, `/session list`, and any known session target fix if T927
  is still open

## Minimum Large-Scale Live Audit Gate

Run fresh audit roots for both standard models unless explicitly scoped:

- Qwen profile: `qwen2.5-coder-14b`
- GPT-OSS profile: `gpt-oss-20b`
- backend: managed `llama.cpp`
- modes: auto, ask, plan, agent, plus hidden legacy aliases where relevant
- coverage:
  - no-workspace chat/privacy
  - workspace read/explain
  - protected read denial and approved handling
  - proposal-only vs apply
  - edit approval denied/approved/allow-in-session
  - checkpoint and changed-files summary
  - command profile approved/rejected
  - static-web repair
  - similar target trap (`script.js` vs `scripts.js`)
  - setup wizard and doctor
  - trace/prompt-debug/provider-body capture

## Architecture Metadata

Capability:

- release QA and installed-product audit

Operation(s):

- verify
- run installed CLI
- capture traces/prompts/provider bodies
- run bounded commands through Talos approval paths

Owning package/class:

- docs/process first; possible later owner under release scripts

New or changed tools:

- none in this ticket unless enforcement script is added during implementation

Risk, approval, and protected paths:

- Risk level: high
- Approval behavior: approval-sensitive lanes require synchronized/manual PTY
  evidence
- Protected path behavior: protected-read lanes must use synthetic canaries and
  artifact canary scans

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: mutation lanes must prove checkpoint behavior where
  policy requires it
- Evidence obligation: QA packet must include exact evidence paths
- Verification profile: full automated gates plus manual PTY and live model
  gates
- Repair profile: any release-blocking failure creates or amends a ticket and
  restarts the relevant candidate evidence

Outcome and trace:

- Outcome/truth warnings: do not claim release readiness from final prose
- Trace/debug fields: `/last trace`, prompt-debug, and provider-body evidence
  required where applicable

Refactor scope:

- Allowed: release-process docs, QA packet schema, small validation script
- Forbidden: release artifact publication, tag creation, broad release workflow
  implementation

## Acceptance Criteria

- The work-test/release docs define the QA packet and artifact boundary.
- The candidate/release checklist requires all existing automated gates,
  manual PTY evidence, and large-scale live audit evidence before public
  artifact creation/publication.
- The gate distinguishes local staging artifacts from public release artifacts.
- The gate distinguishes CI staging artifacts from GitHub Release/draft-release
  assets.
- The gate makes redirected-stdin approval evidence non-release-grade.
- The gate requires both standard models unless the release scope explicitly
  says otherwise.
- The gate requires artifact canary scanning for manual audit roots.
- The gate requires `wikiEvidenceCloseGate --rerun-tasks` and
  `talosQualitySummaries` unless the current candidate runbook has replaced
  them with a documented stronger gate.
- The gate requires a current task/workflow inventory and named exclusions for
  anything skipped.
- No release/tag/winget/GitHub asset can be called ready without the packet.

## Tests / Evidence

Required deterministic regression:

- Unit test: process/docs lint or release-contract test pins the QA packet
  requirement if a suitable existing test owner exists.
- Integration/executor test: not required unless a script is added.
- JSON e2e scenario: not required.
- Trace assertion: manual gate requires trace artifacts.

Manual/TalosBench rerun:

- Prompt family: full release live-audit bank.
- Workspace fixture: fresh standard fixture per model.
- Expected trace: every natural-language turn has `/last trace`.
- Expected outcome: no untracked release blocker findings.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.docs.TicketHygieneTest" --tests "dev.talos.wiki.WikiLintStructuralTest" --no-daemon
git diff --check
```

## Known Risks

- The gate can become theater if it only records checkboxes and not evidence
  paths.
- Manual PTY work is slow; release cadence must account for it.
- Big live audits can be contaminated by stale model servers, stale Talos homes,
  old binaries, or reused fixtures.

## Known Follow-Ups

- Add a machine-checkable QA manifest once the first 0.10.8 packet shape is
  proven manually.

## Resolution

Implemented as a documented release QA gate in:

- `work-cycle-docs/work-test-cycle.md`
- `work-cycle-docs/work-test-cycle-step-by-step.md`

The gate now defines:

- local staging artifacts;
- CI staging artifacts;
- public release artifacts, including draft GitHub Release assets;
- the automated gate before staging artifacts;
- manual PTY evidence;
- two-model large-scale live-audit evidence;
- runtime artifact canary scanning for manual roots;
- named exclusions for skipped tasks, tools, models, platforms, or lanes.

Regression coverage:

- `ReleaseQaGateContractTest` pins the artifact taxonomy and the
  QA-before-publication boundary in the work-test runbooks.

Verification:

```text
.\gradlew.bat test --tests "dev.talos.docs.ReleaseQaGateContractTest" --no-daemon
```
