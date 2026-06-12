# 0.10.5 Release Packet - Wave 4 Product Ergonomics - 2026-06-12

Packet: `current-0.10.5-release-packet-20260612-085943`
Branch: `feature/wave4-ergonomics`
Cut commit: `ed3c8ee24efcdf0b68b9e0a125c2e5eae71ec206` (scripted hermetic cut;
SHA tooling-sourced; see `build/reports/talos/candidate-manifest.json`).
Machine-checkable verdicts: sibling `-GATES.json` (schema
`talos.releaseGates.v1`).

## What this candidate contains

Wave 4 (product ergonomics and operator trust surfaces) of the top-tier
roadmap, tickets T783-T806. This packet does not reopen Wave 3 and does not
claim a fresh true-PTY cycle.

1. **T783** - README tool-table drift fixed: `talos.delete_path`,
   `/checkpoint`, and `/undo` now appear in the user-facing docs and
   `ReadmeToolTableDriftTest` pins the table against the descriptor catalog.
2. **T784** - `talos doctor` CLI preflight: config, engine/model presence,
   managed server binary/model file, server reachability, and writable
   index/home probes; `--start` opt-in runs a one-word chat and releases the
   model.
3. **T785** - first-run setup now runs the doctor probe set and reports
   truthful PASS/WARN/FAIL/SKIP lines instead of unconditional setup success.
4. **T786** - `/doctor` REPL command exposes the default doctor probe set
   inside a session, deliberately without the CLI-only `--start` behavior.
5. **T787/T797** - characterization pins established byte baselines before
   trust/profile/checkpoint and context/session changes.
6. **T788** - workspace `.talos` is now a protected CONTROL path; ordinary
   write approvals cannot mutate workspace profiles, command templates, or
   memory files.
7. **T789-T790** - workspace verification profiles: `.talos/profiles.yaml`
   declaration loading, hash-pinned trust, fail-closed validation, and trusted
   `ws:<id>` profiles routed through `talos.run_command`.
8. **T791-T792** - `/profiles`, `/verify`, and the `Verify` status row; a
   successful user-approved verification-class run after mutation upgrades
   post-apply verification from READBACK_ONLY to PASSED.
9. **T793-T796** - checkpoint read model, newest-first timeline, restore
   descriptions, approval-previewed checkpoint undo, safety checkpoint before
   undo/restore, and removal of the old ungoverned in-memory `FileUndoStack`.
10. **T798-T805** - context/session ergonomics: context meter and manual
    compaction machinery, multi-session storage/list/resume/export, `@file`
    prompt pinning, `/context`, `/compact`, and interactive-only automatic
    compaction notices.
11. **T806** - workspace template commands from protected
    `.talos/commands/*.md`, with built-ins taking precedence and templates
    expanded through the same prompt pipeline and approval policy as typed
    user input.

Ticket-file reconciliation for T783-T806 remains outside this closeout unless
the owner requests it separately.

## Gate status (see GATES.json for the authoritative ledger)

All scripted and deterministic Wave 4 lanes are green for both audited models.
`TRUE_PTY_MANUAL` is owner-deferred/waived until after all waves and is recorded
`NOT_RUN`, not PASS.

| Lane | GPT-OSS | Qwen | Verdict |
|---|---|---|---|
| `DETERMINISTIC` summaries | all summaries report 0.10.5; manifest branch/SHA match `feature/wave4-ergonomics` / `ed3c8ee24efcdf0b68b9e0a125c2e5eae71ec206`; candidate tests 5133 total, 5131 passed, 2 skipped; e2e 185/185 passed | bundle | PASS |
| `STATIC_ANALYSIS` Qodana | qodana-provenance-incomplete / SARIF-only; QDJVM 253.31821; 113 findings, 0 critical, 108 high, 5 moderate | bundle | PASS with caveat |
| `SAFE_REDIRECTED_STDIN` | 19 PASS / 22 MANUAL_REQUIRED / 0 FAIL; 19 transcript files, 0 ANSI hits | 19 PASS / 22 MANUAL_REQUIRED / 0 FAIL; 19 transcript files, 0 ANSI hits | PASS |
| `SYNC_APPROVAL` | 31 rows: 30 PASS + 1 PASS_WITH_READBACK_ONLY_LIMITATION; artifact scan PASS; 3 ADVISORY_ONLY trace rows | 31 rows: 30 PASS + 1 PASS_WITH_READBACK_ONLY_LIMITATION; artifact scan PASS; 0 ADVISORY_ONLY trace rows | PASS |
| `CAPABILITY_PRIVATE_MODE` | 24 rows | 24 rows | PASS - 48 total rows, 0 bad exits, 0 provider-missing-when-required rows, 0 raw secret/canary leaks, 0 unsupported overclaims; controlled OCR stub |
| `TRUE_PTY_MANUAL` | n/a | owner-deferred/waived until after all waves | NOT_RUN |
| Canary scans | packet artifacts root PASS | capability testing+workspace roots PASS with documented fixture allowlist | PASS |

## Self-distrust notes

- **No fresh personal PTY was run for Wave 4.** This is an explicit owner
  deferral/waiver until after all waves, not a green PTY claim. Wave 3 remains
  closed as-is; this report does not reopen it.
- **Qodana provenance is incomplete.** The current Qodana output is SARIF-only:
  `metaInformation.json` and `result-allProblems.json` are absent, so the
  report cannot claim Qodana-recorded branch/revision provenance. It can claim
  only the local summary facts: 113 total findings and 0 critical findings.
- **Capability OCR was a controlled stub.** The capability lane proves routing,
  privacy, and artifact boundaries for the OCR path, not real OCR quality or a
  product image-readiness claim.
- **Capability private-folder approval-sensitive prompts are not fully live
  synchronized evidence.** The scripted bank proves non-interactive probes; the
  generated private-folder runbook or a future synchronized approval runner is
  still needed for approval-sensitive private-folder prompts.
- **Fourteen capability rows intentionally have no prompt-debug/provider body.**
  They are no-provider/no-prompt-debug command paths (`09-pptx-summary`,
  `11-reindex`, `15-privacy-status`, `16-private-show-pdf`,
  `17-private-show-docx`, `18-private-show-xlsx`,
  `20-private-reindex-disabled`, each for both models), and are not counted as
  provider-missing-when-required.
- **GPT-OSS synchronized approval has three advisory-only trace rows.** The
  rows are recorded in the gate ledger; they are not hidden and not promoted
  into failures by the lane's current rules.
- **The canary evidence is local release evidence, not committed evidence.**
  `canary-packet-artifacts.log` and `canary-capability-roots.log` remain local
  evidence artifacts. The committed closeout is the tracked report and gate
  ledger only.
