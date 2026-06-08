# Current 0.10.0 Release Packet After T734

Date: 2026-06-08
Branch: `v0.9.0-beta-dev`
Commit: `87f9fc1a019abbaba546e4264d111a0bb848a55b`
Version: `talosVersion=0.10.0`
Installed launcher: `build/install/talos/bin/talos.bat`

## Summary

This packet is valid release-gate evidence for the current committed candidate
after T730-T734. It is not a blanket open-beta release pass because several
full-audit and architecture gates remain open, especially true PTY/JLine
coverage and full native-tool prompt-bank reconciliation.

## Build And Install Evidence

- `.\gradlew.bat check --no-daemon` passed after T734.
- `.\gradlew.bat installDist --no-daemon` passed after T734.
- `.\build\install\talos\bin\talos.bat --version` reported Talos `0.10.0`.

## TalosBench Safe Redirected Lanes

Audit root:
`local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958`.

- Qwen summary:
  `artifacts/qwen/talosbench/20260608-192016/summary.md`.
- GPT-OSS summary:
  `artifacts/gptoss/talosbench/20260608-192245/summary.md`.

Result:

- All `SAFE_REDIRECTED_STDIN` cases passed for both models.
- Approval-sensitive and true-PTY cases were correctly marked
  `MANUAL_REQUIRED`, not silently treated as release-grade redirected-stdin
  approval evidence.

## Synchronized Approval Lanes

Artifact roots:

- Qwen:
  `local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958/artifacts/qwen/sync-approval`.
- GPT-OSS:
  `local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958/artifacts/gptoss/sync-approval`.

Result:

- Qwen: 25 scenarios, artifact scan PASS.
- GPT-OSS: 25 scenarios, artifact scan PASS.
- Both summaries record scenario-level scoring.
- Qwen `t325-python-command-boundary` is `PARTIAL` with
  `PASS_WITH_READBACK_ONLY_LIMITATION`: expected files exist, Python/pytest was
  not run, and the final answer says the command was unavailable under the
  bounded command profile.
- GPT-OSS `mutation-append-line-verified` and
  `static-web-selector-script-only-verified` are
  `PASS_WITH_RUNTIME_REPAIR`: runtime repair occurred, and final verification
  passed.

## Capability And Private-Mode Document Lanes

Audit root:
`local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability`.

Result:

- 44 prompt turns across GPT-OSS and Qwen passed by the script's
  process/tool-artifact heuristics.
- PDF, DOCX, XLSX, and XLS/text comparison prompts targeted the expected
  documents and used the expected handoff state.
- Private-mode PDF/DOCX/XLSX prompts extracted the named target locally and
  withheld model handoff by default.
- `LIVE-CAPABILITY-AUDIT-SUMMARY.csv` recorded:
  - nonzero exits: 0;
  - raw secret leaks: 0;
  - raw canary leaks: 0;
  - unsupported overclaim flags: 0;
  - unexpected private document targets: 0;
  - required prompt-debug evidence missing: 0.

The capability script still labels this as a process/tool-artifact heuristic
pass. It does not replace maintainer prompt-debug/provider-body quality review
or approval-sensitive manual private-folder probes.

## Canary And Redacted Snapshot Evidence

Raw capability fixture scan:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries `
  "-PartifactScanRoots=local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability,local/manual-workspaces/current-0.10.0-release-packet-post-t734-20260608-191958-capability" `
  "-PartifactScanAllowlist=<script-generated fixture allowlist>" `
  --no-daemon
```

Result: PASS.

Redacted snapshots generated:

- `artifacts/qwen/redacted-sync-workspace`
- `artifacts/gptoss/redacted-sync-workspace`
- `artifacts-qwen/redacted-capability-workspace`
- `artifacts-gptoss/redacted-capability-workspace`

Release-clean artifact scan:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries `
  "-PartifactScanRoots=local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958,local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability" `
  --no-daemon
```

Result: PASS.

## Remaining Limits

- This packet does not prove true PTY/JLine rendering for the current commit.
- TalosBench still marks many approval-sensitive and native workspace-operation
  cases as `MANUAL_REQUIRED` in the redirected lane; the synchronized live bank
  covers a strong approval slice but not every native-tool prompt-bank case.
- Larger maintained/adversarial document fixtures remain open beyond the
  generated small beta-core fixture evidence.
- Image/OCR and PowerPoint remain outside beta scope.
- Architecture-cycle cleanup, Qodana triage, browser/render proof, MSI/signing,
  and public packaging gates remain outside this packet.

## Ticket Impact

- Update T280/T284 with this valid current-candidate evidence, but keep open
  until the maintainers decide the remaining true-PTY/full prompt-bank coverage
  satisfies the beta gate.
- Update T306 with the synchronized 25-scenario two-model pass, but keep open
  for broader prompt-bank/PTY integration.
- Update T312 with the lane-labeled evidence, but keep open because redirected
  TalosBench still marks native workspace-operation cases as manual-required.
- Update T299/T301 with PDF/DOCX/XLS/XLSX evidence, but keep open for larger
  fixture corpus and release-claim drift prevention.
