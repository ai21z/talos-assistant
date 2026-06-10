# T736 - PTY Manual Audit Packet Config Isolation And Transcript Proof

Status: done
Severity: high
Release gate: yes - T306/T312 PTY/manual evidence reproducibility
Branch: codex/t312-live-workspace-ops
Created/updated: 2026-06-09
Owner: unassigned

## Problem

The PTY/JLine manual packet was not self-contained. The runbook told the
operator to run `talos run` directly, which loads `~/.talos/config.yaml` by
default. That allowed ambient user rules to contaminate the gate: the `.env`
probe could hit `CONFIG_DENY` before the ordinary protected-read approval
window, while the packet still looked structurally valid.

This was a harness problem, not a runtime-policy bug. The private-document
handoff path could still be exercised, which made the packet look stronger than
it really was.

## Evidence

- `Config` loads classpath defaults first, then overlays `~/.talos/config.yaml`
  from `System.getProperty("user.home")`.
- `SynchronizedCliPtyManualAuditMain` accepted `configPath` metadata but did not
  apply it to the live `talos run` command, and when absent it implicitly used
  the operator's ambient Talos home.
- The real current-head transcript under
  `local/manual-testing/t735-pty-manual-current-head-20260609-000816` showed the
  `.env` turn taking the `CONFIG_DENY` path instead of showing the ordinary
  protected-read approval prompt.
- `SynchronizedCliPtyManualAuditValidator` previously accepted any generic
  approval/window evidence and did not require proof that the packet-local
  launch path was actually used.

## Architecture Metadata

- Capability ownership: PTY/manual audit harness only.
- Operation type: release-evidence packet preparation and post-run validation.
- Risk: contaminated PTY evidence could overstate gate coverage.
- Approval behavior: no runtime permission change; only the packet launch path
  and transcript proof requirements changed.
- Protected path behavior: unchanged runtime semantics; `.env` still requires
  the ordinary protected-read approval path under default policy.
- Checkpoint behavior: not applicable.
- Evidence obligation: the transcript must prove both packet isolation and the
  ordinary `.env` approval prompt.
- Verification profile: deterministic e2e harness tests plus a future human
  PTY rerun.

## Required Behavior

- Every PTY manual packet creates a packet-local isolated Talos home under the
  artifact root.
- If a config is provided, it is copied into the packet-local
  `isolated-home/.talos/config.yaml`.
- If no config is provided, the packet runs with classpath defaults only under
  the isolated home; it must not instruct the operator to use current user
  config.
- The packet generates `RUN-PTY-MANUAL-AUDIT.ps1`, which launches Talos with
  `-Duser.home=<isolated-home>` and restores the previous environment after
  exit.
- The transcript template and validator now require proof of packet isolation
  and the ordinary protected-read approval prompt:
  `Allow? [y=yes, a=yes for session, N=no]`.

## Tests

- `SynchronizedCliPtyManualAuditMainTest`
  - no-config packet writes launcher script, isolated home, classpath-default
    status, and no current-user-config wording
  - explicit config is copied into packet-local `config.yaml` and recorded in
    status/runbook
- `SynchronizedCliPtyManualAuditValidatorTest`
  - passing transcript includes launcher/isolated-home evidence and the ordinary
    `.env` approval prompt
  - transcript fails if private-document evidence exists but the ordinary `.env`
    approval prompt is missing
  - transcript fails if packet-isolation evidence is missing
  - paraphrase-only private-document denial still fails

## Acceptance Criteria

- Focused PTY harness tests pass.
- `./gradlew.bat check --no-daemon` passes.
- `git diff --check` has no whitespace errors.
- A contaminated transcript shaped like the current failing run is rejected by
  the validator because it lacks the ordinary `.env` approval prompt.
- T306/T312 remain open until a fresh human PTY/JLine rerun validates against
  the rebuilt installed binary using the generated launcher script.

## Completion Evidence

Implemented:

- packet-local isolated Talos home creation
- packet-local copied config support
- generated `RUN-PTY-MANUAL-AUDIT.ps1` launcher with `-Duser.home=...`
- runbook rewrite to require the launcher script instead of raw `talos run`
- status JSON fields for launcher, isolated home, effective config source, and
  copied config path
- transcript template headers for launcher script and packet isolated home
- validator proof requirements for ordinary protected-read approval prompt and
  packet isolation evidence

Automated verification passed:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedCliPtyManualAuditMainTest" --tests "dev.talos.harness.SynchronizedCliPtyManualAuditValidatorTest" --no-daemon
```

Not yet proven:

- real human PTY/JLine rerun from the generated launcher script
- completed `PTY-MANUAL-AUDIT-RESULT.json`
- fresh packet validator pass from human evidence
- release-gate closure for T306/T312
