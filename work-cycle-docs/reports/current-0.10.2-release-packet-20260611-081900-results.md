# Current 0.10.2 Release Packet Results (20260611-081900)

- Branch: `codex/wave1-stability-and-cycle`
- Commit: `157aedc840877e66bb2e9f5f054a7943de1f4439` (`Cut 0.10.2 candidate`; SHA from tooling)
- `talosVersion`: `0.10.2`
- Installed launcher: `Talos 0.10.2 - Java 21.0.9+10-LTS - Windows 11 amd64 - build 2026-06-11T06:13:16Z`
  (built AFTER the cut commit — sequencing enforced by `scripts/cut-candidate.ps1`)
- Candidate manifest: `build/reports/talos/candidate-manifest.json`
  (all four quality summaries report `0.10.2`; mandatory post-bump `check` passed)
- Machine-checkable verdicts: `current-0.10.2-release-packet-20260611-081900-GATES.json`

## Summary

This is the first release-grade packet produced under the wave-1 stack
(T739-T753) from a clean committed candidate via the scripted hermetic cut.
**Every executed lane is green for both audited models.** The Qwen full
synchronized bank — which failed closed in all three 0.10.1-era attempts —
passes from the committed candidate.

## Lane Results

| Lane | GPT-OSS | Qwen | Verdict |
|---|---|---|---|
| `SAFE_REDIRECTED_STDIN` | 19 PASS / 22 MANUAL_REQUIRED, piped approvals disallowed | 19 PASS / 22 MANUAL_REQUIRED, piped approvals disallowed | PASS |
| `SYNC_APPROVAL` (full 31-scenario live bank) | 31/31, artifact scan PASS, 0 ladder rescues | 31/31, artifact scan PASS, 5 bounded T743 rescues (honestly noted) | PASS |
| Focused `talos.retrieve` probe | n/a | PASS, `TOOL_EXECUTED talos.retrieve success=true` (seed-reproducible) | PASS |
| `CAPABILITY_PRIVATE_MODE` | 24 prompts | 24 prompts | PASS by process/tool-artifact heuristics — 48/48 runs, 0 secret/canary leaks, 0 overclaims |
| `TRUE_PTY_MANUAL` | — | carried from 0.10.1 (owner real-terminal run, validator + canary PASS, independently rerun) | PASS (carried) |
| Canary scans | packet artifacts root PASS | capability roots + workspaces PASS (documented fixture allowlist) | PASS |
| Deterministic summaries | coverage/e2e/version/qodana all `0.10.2` | (bundle) | PASS |
| Qodana | fresh native scan, 0 critical, provenance matched `b6f2641f` | (bundle) | PASS-with-note |

Evidence roots:

- Main packet: `local/manual-testing/current-0.10.2-release-packet-20260611-081900`
  (artifacts/{qwen,gptoss}/{talosbench,sync-approval}, qwen/retrieve-probe, homes/)
- Capability packet: `local/manual-testing/current-0.10.2-release-packet-20260611-081900-capability`

## Notes And Caveats

- Qwen sync bank: 5/31 scenarios were rescued by the bounded T743 ladder
  (`SATISFIED_AFTER_RETRY`). The bank is deterministic under the fixed
  harness seed (424242); first attempts under NAMED tool choice occasionally
  emit no parsed call — recorded as a focused follow-up investigation
  (grammar/template interaction), not a release blocker (fail-closed +
  bounded rescue + deterministic outcome).
- TRUE_PTY_MANUAL is carried from the 0.10.1 packet: wave-1 changed provider
  request construction and prompt frames, not terminal rendering, JLine
  input, or approval-prompt text (chrome strings unchanged; full `check`
  green). A fresh PTY run is recommended with the next UI-affecting change.
- Qodana: the fresh native scan ran on `b6f2641f`; the only commits between
  scan and cut are docs/config-only (T753 evidence + qodana.yaml baseline)
  plus the version bump — scanned source equals cut source. Docker-mode
  qodanaLocal remains broken on this host (documented Gradle-import I/O
  failure class); the native fallback is the working path.
- PTY/capability fixture allowlists and all evidence roots are git-ignored
  machine-local; this report and the GATES ledger are the committed record.

## Ticket Impact

- Close `T280` (two-model live audit before beta): release-grade two-model
  packet exists with pass/fail per model from a clean committed candidate.
- Close `T284` (results record): this report is the results record; the
  legacy AC filename (`t267-live-two-model-audit-results.md`) is superseded
  by the packet-report naming scheme, recorded at closure.
- Close `T312` (full prompt-bank native tool coverage): 13/13 native tools
  evidenced including clean focused `talos.retrieve`; deterministic coverage
  guard and lane-labeled evidence in place.
- Move wave-1 tickets `T739`-`T753` to done (each carries completion
  evidence).

## Remaining Beta-Gate Context (not this packet's blockers)

The private-document/sensitive-folder beta claim still gates on
T276/T281/T283 (redaction + private-mode UX live evidence) and T319 per the
open queue; the developer/text-workspace beta claim is supported by this
packet.
