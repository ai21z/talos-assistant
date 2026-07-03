# [T936-done-high] Cut 0.10.8 candidate with QA packet

Status: done
Priority: high

## Evidence Summary

- Source: current branch state and release planning
- Date: 2026-07-03
- Talos version / commit: 0.10.8 candidate product /
  f291e902c28d1c84bbc27756f3b8822569eef0c1
- Branch: `v0.9.0-beta-dev`
- Evidence:
  - `CHANGELOG.md` promoted the post-0.10.7 entries into the `0.10.8`
    candidate section.
  - `gradle.properties` reports `talosVersion=0.10.8`.
  - Automated candidate gates passed after the wiki identity repair at
    `f291e902c28d1c84bbc27756f3b8822569eef0c1`.
  - T929 defines the QA packet required before public release artifacts.

## Classification

Primary taxonomy bucket: `VERIFICATION`

Secondary buckets:

- `RELEASE_HYGIENE`
- `OUTCOME_TRUTH`

Blocker level: release blocker before public beta artifact

Why this level:

```text
The branch contains material post-0.10.7 work. Reusing the 0.10.7 identity for
new artifacts would be false provenance. The next candidate should be cut as
0.10.8 and evaluated as its own evidence packet.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
0.10.8 should be a candidate cut, not an immediate public release. Its purpose
is to bind the current beta-dev work to a clean version/changelog identity and
then run the full automated plus manual QA gates before any public artifact.
```

Likely code/document areas:

- `gradle.properties`
- `CHANGELOG.md`
- `scripts/cut-candidate.ps1`
- `work-cycle-docs/wiki/CURRENT-STATE.md`
- QA packet location under `local/manual-testing/` or `work-cycle-docs/reports/`

## Goal

```text
Cut a clean 0.10.8 candidate from `v0.9.0-beta-dev`, run the full automated
candidate gates, then run the T929 manual PTY and large-scale installed-product
QA packet before any GitHub Release asset, draft release asset, signed asset,
tag-bound asset, winget-linked asset, or release-named artifact is created.
```

## Non-Goals

- No GitHub Release publication in this ticket.
- No draft GitHub Release asset in this ticket.
- No signed/release-named artifact in this ticket.
- No tag unless the release workflow policy explicitly says the candidate is
  ready for tagging after QA.
- No winget submission.
- No history rewrite.
- No skipping T929 because `check` passed.

## Architecture Metadata

Capability:

- candidate versioning and release evidence

Operation(s):

- bump version
- promote changelog
- build candidate
- run automated gates
- run installed-product QA
- record evidence

Owning package/class:

- candidate scripts and work-test-cycle docs

New or changed tools:

- none unless cut script needs QA-packet awareness

Risk, approval, and protected paths:

- Risk level: high
- Approval behavior: candidate cut requires owner release decision
- Protected path behavior: live audit must use synthetic protected fixtures

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: mutation live-audit lanes must prove checkpoint behavior
- Evidence obligation: candidate manifest plus T929 QA packet
- Verification profile: all automated gates, then manual PTY and two-model
  large-scale QA
- Repair profile: if QA fails, create/amend tickets, fix forward, and recut or
  rerun candidate evidence as appropriate

Outcome and trace:

- Outcome/truth warnings: do not call 0.10.8 release-ready until QA packet is
  complete and reviewed
- Trace/debug fields: manual QA requires `/last trace`, prompt-debug, and
  provider-body evidence where applicable

Refactor scope:

- Allowed: candidate docs/script guard improvements
- Forbidden: runtime feature work in the candidate cut commit

## Acceptance Criteria

- `0.10.8` candidate version is created only after T929 is amended/implemented
  and current open release blockers are either done or explicitly scoped out.
- `[Unreleased]` changelog entries are promoted to `0.10.8`.
- Candidate commit is clean and identifies branch/SHA/version.
- Automated gates pass from the candidate commit.
- Manual PTY gate passes against a clean installed product.
- Large-scale live QA runs for Qwen and GPT-OSS unless the release scope
  explicitly narrows model coverage.
- QA packet records all evidence paths and skipped coverage.
- Public release artifacts, draft GitHub Release assets, signed assets,
  tag-bound assets, winget-linked assets, and release-named artifacts remain
  blocked until T929 acceptance criteria are satisfied.

## Progress

2026-07-03 candidate-cut start:

- `scripts/cut-candidate.ps1 -SelfTest` passed.
- `scripts/cut-candidate.ps1 -DryRun` confirmed a clean
  `v0.9.0-beta-dev` tree at `0.10.7` and planned a `0.10.8` patch bump.
- `scripts/cut-candidate.ps1` created cut commit
  `420e6c92b1a5837caa1a1b4f2d79cacac6d9a165` with
  `talosVersion=0.10.8` and a promoted `CHANGELOG.md` `0.10.8` section.
- The scripted post-bump `installDist` step passed.
- The scripted post-bump `check` step failed as designed because
  `WikiLintStructuralTest` caught `CURRENT-STATE.md` still reporting
  `0.10.7`. This was an evidence-state failure, not a runtime/product failure.
- `CURRENT-STATE.md` was repaired forward to the `0.10.8` cut identity.

2026-07-03 automated candidate evidence from repaired tree:

- `git diff --check` passed.
- `.\gradlew.bat installDist --no-daemon` passed.
- Installed launcher identity check reported `Talos 0.10.8`.
- `.\gradlew.bat check --no-daemon` passed.
- `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon` passed.
- `.\gradlew.bat talosQualitySummaries --no-daemon` passed.
- Candidate manifest:
  `build/reports/talos/candidate-manifest.json`
  recorded version `0.10.8`, SHA
  `f291e902c28d1c84bbc27756f3b8822569eef0c1`, and release status
  `candidate-automated-evidence-only-not-release-ready`.

2026-07-03 T929 installed-product QA start:

- Clean global install refreshed to:
  `C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat`
- Installed identity:
  `Talos 0.10.8 - Java 21.0.9+10-LTS - Windows 11 amd64`
- Fresh QA packet root:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238`
- Fresh workspace root:
  `local/manual-workspaces/t936-0.10.8-release-qa-20260703-1238`
- Qwen installed smoke and `talos doctor --start` passed against isolated
  home/config:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/qwen/doctor-start.txt`
- GPT-OSS installed smoke and `talos doctor --start` passed against isolated
  home/config:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/gptoss/doctor-start.txt`
- Qwen synchronized approval live bank passed with 33 scenarios and artifact
  scan PASS:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/qwen/synchronized-approval/SYNCHRONIZED-APPROVAL-AUDIT.md`
- Initial GPT-OSS synchronized approval live bank failed at
  `mutation-forbidden-sibling-target-blocked-before-approval`.
  Inspection proved the product safety invariant held: `script.js` changed,
  `scripts.js` stayed unchanged, approval was requested/granted for
  `script.js`, and verification passed. Root cause was a QA-harness
  false-negative on a single missing terminal LF in `script.js`, tracked and
  fixed as T938.
- Corrected GPT-OSS synchronized approval live bank rerun passed with 33
  scenarios and artifact scan PASS:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/gptoss/synchronized-approval-rerun-t938/SYNCHRONIZED-APPROVAL-AUDIT.md`
- Tracked QA packet summary written:
  `work-cycle-docs/reports/current-0.10.8-release-qa-packet-20260703-results.md`
- Clean global install rebuilt after T938 and refreshed to:
  `C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat`
- Installed identity after T938 rebuild:
  `Talos 0.10.8 - Java 21.0.9+10-LTS - Windows 11 amd64 - build 2026-07-03T10:52:32.463528400Z`
- Manual PTY lane 1 passed the installed-product basics on Qwen:
  `talos`, `/debug prompt on`, `/status --verbose`, `/mode`, `/prompt`,
  read-only answer, `/last trace`, `/prompt-debug last`,
  `/prompt-debug save`, approval denial, one-time approval, checkpoint creation,
  and static edit verification. Durable evidence lives under:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/sessions/0912e1016a3cf6b37b5310fdc589e48a86fcdd1c-20260703105311.turns.jsonl`
- Manual PTY lane 2 confirmed allow-in-session behavior on Qwen. Turn 1
  required one approval and changed `README.md` to `# Session Approval A`;
  turn 2 changed it to `# Session Approval B` with `approvalsRequired=0`,
  `approvalsGranted=1`, `approvalsDenied=0`. Durable evidence:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/sessions/b20020a7a5da2a1056592caee5f8cac0f84a43f2-20260703110039.turns.jsonl`
- Protected-read-denied rows in both synchronized approval banks were manually
  reviewed: runtime denied `.env` with `CONFIG_DENY`, no protected content
  leaked, and the final answer reported the denial. This is a reviewed harness
  expectation mismatch, not a release-blocking product failure.
- Manual audit artifact canary scan passed for the manual evidence root:
  `.\gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/t936-0.10.8-release-qa-20260703-1238" --no-daemon`
- Post-QA local release gates passed at
  `7dcb969e11e78a10da0b13234cf7e854fb931eba`:
  `git diff --check`,
  `.\gradlew.bat clean check --no-daemon`,
  `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon`, and
  `.\gradlew.bat talosQualitySummaries --no-daemon`.
- `v0.9.0-beta-dev` was pushed to
  `7dcb969e11e78a10da0b13234cf7e854fb931eba`.
- GitHub Actions run `28657059098` passed for
  `7dcb969e11e78a10da0b13234cf7e854fb931eba`:
  `https://github.com/ai21z/talos-assistant/actions/runs/28657059098`

Remaining:

- None for the 0.10.8 candidate QA packet. This ticket does not publish a
  release artifact, create a tag, or make a release decision.

## Tests / Evidence

Required deterministic regression:

- Unit test: not required unless cut script changes.
- Integration/executor test: candidate script run.
- JSON e2e scenario: full manual QA references existing scenario banks where
  practical.
- Trace assertion: every natural-language live turn has `/last trace` evidence.

Commands:

```powershell
.\scripts\cut-candidate.ps1
git diff --check
```

After candidate cut:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat talosQualitySummaries --no-daemon
```

Manual QA must follow T929.

## Known Risks

- Running the candidate script before release blockers are done can create a
  noisy 0.10.8 packet that must be repaired forward.
- Manual QA after candidate cut can expose runtime bugs; those must create
  tickets and may require a new candidate packet.

## Known Follow-Ups

- T930 release workflow after 0.10.8 candidate QA is clean.
