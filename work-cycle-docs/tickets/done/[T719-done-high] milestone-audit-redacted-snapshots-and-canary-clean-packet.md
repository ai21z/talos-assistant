# T719 - Milestone Audit Redacted Snapshots And Canary-Clean Packet

Status: done
Priority: high
Created: 2026-06-07
Completed: 2026-06-07
Branch: v0.9.0-beta-dev

## Problem

The `current-two-model-audit-20260607-204059` milestone audit produced valid
model-facing evidence, but the broad artifact scan failed because the manual
packet copied raw fixture workspaces and final workspace snapshots that contain
deliberate fake protected markers.

This is audit-owned artifact hygiene, not evidence of a Talos model/runtime
privacy leak. It still blocks treating the audit packet as release-clean.

## Evidence

- Full-root canary scan failed:
  `local/manual-testing/current-two-model-audit-20260607-204059/CANARY-FULL-ROOT.txt`
- Model-facing scan passed:
  `local/manual-testing/current-two-model-audit-20260607-204059/CANARY-MODEL-FACING.txt`
- Findings report:
  `local/manual-testing/current-two-model-audit-20260607-204059/FINDINGS.md`
- Existing synchronized approval harness already writes redacted deterministic
  workspace diffs, but the manual milestone packet copied raw fixture snapshots.

## Goal

Provide a reusable Java-backed redacted workspace snapshot path for manual and
milestone audit packets so release-clean artifact roots can include useful final
workspace evidence without raw protected/canary fixture content.

## Non-Goals

- Do not change Talos runtime protected-read behavior.
- Do not hide or delete raw local fixture workspaces; they may remain local
  evidence fixtures.
- Do not modify synchronized approval semantics.
- Do not start a versioned release-candidate loop.

## Architecture Metadata

Capability:

- Audit artifact generation / release evidence hygiene

Operation(s):

- inspect
- summarize
- artifact scan

Owning package/class:

- `dev.talos.runtime.policy`
- Gradle verification task wiring

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: release evidence / privacy-artifact hygiene
- Approval behavior: unchanged
- Protected path behavior: protected paths represented only by metadata or
  omission notes in redacted snapshots

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: deterministic unit tests and artifact canary scan
- Verification profile: runtime artifact canary scan
- Repair profile: not applicable

Outcome and trace:

- Outcome/truth warnings: unchanged
- Trace/debug fields: unchanged

Refactor scope:

- Allowed: add focused redacted snapshot utility/CLI and audit docs.
- Forbidden: broad audit harness rewrite or release-candidate version bump.

## Acceptance Criteria

- A workspace containing `notes.md`, `.env`, `protected/private-notes.md`, and
  fake canary content can be snapshotted into a redacted artifact directory with
  zero `ArtifactCanaryScanner.scanRuntimeArtifacts(...)` findings.
- Protected files are listed as omitted/protected metadata, not copied raw.
- Safe text files appear in sanitized content output.
- Binary or large files are summarized/omitted without raw bodies.
- A Gradle/JavaExec entry point can write the snapshot from workspace/output
  arguments and rejects missing or unsafe arguments.
- Milestone/full audit docs tell operators to use redacted snapshots for
  release-clean packets and to exclude or allowlist raw fixture roots.

## Tests / Evidence

Required tests:

- `dev.talos.runtime.policy.*` focused tests for redacted snapshot generation.
- CLI/task argument tests for missing arguments and workspace escape rejection.
- Artifact canary scan over generated snapshot output.

Required commands:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.*" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- Move to done only after focused tests, full `check`, `git diff --check`, and
  focused installed-product audit evidence.

## Completion Evidence

Implemented:

- Added `RedactedAuditSnapshotWriter` and `RedactedAuditSnapshotCli`.
- Added Gradle task `writeRedactedAuditSnapshot`.
- Updated milestone/full audit docs to require redacted snapshots for
  release-clean packets.
- Redacted snapshot output contains `summary.txt`, `tree.txt`, and
  `content-dump.txt`; protected/binary/large files are omitted or summarized.

Verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.policy.*" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
.\gradlew.bat installDist --no-daemon
.\gradlew.bat writeRedactedAuditSnapshot "-PauditSnapshotWorkspace=build\tmp\t719-gradle-smoke\workspace" "-PauditSnapshotOutput=build\tmp\t719-gradle-smoke\snapshot" "-PauditSnapshotLabel=t719-smoke" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build\tmp\t719-gradle-smoke\snapshot" --no-daemon
```

Focused installed-product audit:

- `local/manual-testing/t719-t720-focused-p21-audit-20260607-220219/FOCUSED-AUDIT.md`
- Combined scan passed:
  `local/manual-testing/t719-t720-focused-p21-audit-20260607-220219/CANARY-SCAN-ALL.txt`

