# T284 - Live Two-Model Audit Execution Results

Status: still-open - current 0.10.1 execution packet exists, but it is not yet a clean beta pass
Severity: high / release gate
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

The deterministic tests are necessary but do not replace the live two-model prompt-bank audit against Qwen and GPT-OSS.

## Evidence from current code

The runbook exists at `work-cycle-docs/reports/t267-live-two-model-audit.md`.

## Evidence from tests/audits

This pass did not run the full live prompt bank. The local model setup improved:

- Updated preflight now checks actual managed `llama.cpp` server/model files and records the need for sequential isolated configs.
- The preflight script now supports `-StopStaleServers` and `-SmokeModels`.
- GPT-OSS and Qwen GGUF files were found locally.
- 53 stale repo-owned `llama-server.exe` processes were stopped after they caused Qwen startup to fail from GPU memory exhaustion.
- Both Qwen and GPT-OSS passed a minimal model-forced Talos smoke prompt after cleanup; latest smoke evidence is `t267-live-audit-20260516-091319`, which left zero repo-owned stale server processes after cleanup.

The release gate remains open because smoke prompts are not the prompt-bank audit.

## User impact

Without live evidence, release claims remain limited to deterministic developer/text-project behavior.

## Product risk

Policy, prompt construction, model behavior, approval, and artifact capture can fail only in live trajectories.

## Runtime boundary affected

Tool-call loop, prompt-debug, provider-body capture, traces, sessions, RAG, approvals, private mode, unsupported-format final answers.

## Non-goals

- Replacing deterministic tests with live audit.

## Required behavior

- Run prompt bank against `qwen2.5-coder:14b` and `gpt-oss:20b` or explicitly approved local audit profiles.
- Capture final answers, tool calls, traces, prompt-debug artifacts, provider bodies, session/turn logs, workspace diffs, and artifact scan results.

## Proposed implementation

Execute the runbook into a fresh ignored audit directory with sequential isolated configs for Qwen and GPT-OSS.

## Tests

- Live prompt-bank audit, not JUnit.

## Acceptance criteria

- `work-cycle-docs/reports/t267-live-two-model-audit-results.md` contains pass/fail per model and hard-fail evidence.

## Remaining blockers

- Full two-model prompt-bank execution/classification remains unrun.
- Approval-sensitive prompts need synchronized human-operated capture or a purpose-built runner; naive scripted stdin can drift if the model does not request the expected approval.

## Open questions

- Which audited profiles may substitute for Qwen/GPT-OSS if one model is unavailable?

## Related files

- `work-cycle-docs/reports/t267-live-two-model-audit.md`
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md`

## 2026-05-15 final pre-beta update

The live-audit results report now records the executable preflight command and the previous BLOCKED result. No prompt-bank prompts were executed.

## 2026-05-16 update

Backend smoke is now PARTIAL rather than BLOCKED: both required local model files exist and both models answer a model-forced smoke prompt through Talos after stale `llama-server.exe` processes are stopped. The script can now perform cleanup and smoke in one command:

```powershell
./gradlew.bat installDist --no-daemon
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-t267-live-audit.ps1 -SmokeModels -StopStaleServers
```

The full prompt bank still has not run, so this ticket remains open.

## 2026-05-20 lane-labeled execution update

Current-head lane evidence now exists, but this is not yet a full release close:

- `SAFE_REDIRECTED_STDIN`
  - GPT-OSS: 19/19 PASS with `-StrictEvidence`.
  - Qwen: 19/19 PASS with `-StrictEvidence`.
  - Each case captured input script, transcript, `/last trace`, prompt-debug save command, session save command, workspace git baseline/status/diff, and lane-labeled summary.
  - Runtime artifact canary scan over the fresh safe-lane roots passed with only fixture source files allowlisted.
- `SYNC_APPROVAL`
  - `runSynchronizedApprovalAudit` passed with 32 scripted scenarios.
  - Artifact scan passed in the runner summary and in a separate `checkRuntimeArtifactCanaries` invocation.
- `TRUE_PTY_MANUAL`
  - Manual packet was prepared successfully.
  - No true terminal/JLine transcript is claimed yet.

Report: `work-cycle-docs/reports/lane-labeled-two-model-prompt-bank-audit-20260520.md`.

## 2026-05-20 true PTY/manual lane update

The true PTY/manual packet is now complete for the lane-labeled audit wave:

```text
Audit id: true-pty-manual-20260520-r1
Artifacts: local/manual-testing/true-pty-manual-20260520-r1/artifacts
Workspace: local/manual-workspaces/true-pty-manual-20260520-r1/workspace
Validator: validateSynchronizedApprovalPtyManualAudit PASS
Artifact scan: PASS
```

Evidence covers protected-read denial, private-document model-handoff denial,
private-document per-turn approval, `/last trace`, `/prompt-debug save`, and
absence of raw protected/private canaries in scanned artifacts.

Remaining blocker: rerun final clean verification before using this as
release-candidate evidence. This is still a dirty stabilization branch, not a
versioned candidate packet.

## 2026-06-07 T719/T720 focused audit note

T719/T720 added focused installed-product evidence, but it is not full live
prompt-bank completion:

- Audit root: `local/manual-testing/t719-t720-focused-p21-audit-20260607-220219`.
- GPT-OSS exercised the no-change conditional static-web review path with
  diagnostic wording, no old "Runtime static verification" wording,
  `SATISFIED_BY_INSPECTION`, and `Verification: NOT_RUN`.
- Qwen required an explicit-read variant to exercise the same no-change branch;
  the fresh no-history P21 prompt instead attempted `bmi_calculator.html` and
  was blocked before approval.
- Redacted snapshot artifacts and model-facing audit artifacts passed the
  combined canary scan recorded in `CANARY-SCAN-ALL.txt`.

Keep this ticket open for full two-model prompt-bank execution/classification
and final clean-candidate evidence.

## 2026-06-07 0.10.0 results-scope reconciliation

T284 is the results record for the T280 audit mandate, not an independent
replacement for T280. Keep this ticket open until a current-candidate
`0.10.0` two-model audit writes pass/fail results for Qwen and GPT-OSS.

The next results packet must explicitly state:

- branch, commit, and `talosVersion`;
- exact installed executable invoked;
- model/backend/profile per lane;
- which prompt-bank cases were safe redirected stdin, synchronized approval, or
  manual/PTY;
- which native tools were probed or explicitly excluded;
- PDF/DOCX/XLSX extraction outcomes and limitation wording;
- canary scan roots, including redacted final-workspace snapshots instead of
  raw protected fixture copies;
- every failure classified as runtime-owned, model-authored, backend-owned,
  audit-owned, or mixed.

Historical 2026-05 and `0.9.9` artifacts may be cited as prior evidence only.
They must not be presented as closure evidence for the `0.10.0` candidate.

## 2026-06-08 Candidate 0.10.0 Audit Evidence

Audit root:
`local/manual-testing/candidate-0.10.0-full-two-model-20260608-000026`.

Result: valid evidence, not a product pass.

- Branch/commit/version: `v0.9.0-beta-dev` /
  `ad69a8d6b0027061b04d955283fbc44d619644fc` / `0.10.0`.
- Safe redirected TalosBench lane passed non-approval cases for Qwen and
  GPT-OSS. Approval-sensitive cases were correctly marked `MANUAL_REQUIRED`.
- Added beta-scope document extraction lane with valid canonical PDF/DOCX/XLSX
  fixtures for both models. Both used `talos.read_file`,
  `DOCUMENT_EXTRACTION`, `PARSER_EXTRACTION`, and reported format limitations.
- Model-facing artifact canary scan passed for the audit root.
- Synchronized approval lanes failed before full completion:
  - Qwen failed `t325-python-command-boundary` before approval due unsupported
    `apply_workspace_batch` `write_file` operations. Tracked as T721.
  - GPT-OSS failed `static-web-selector-script-only-verified` before approval
    due placeholder `write_file(path="?")`. Tracked as T722.

Keep this ticket open. The current evidence blocks an open-beta release claim.

## 2026-06-08 T721/T722 Focused Rerun Evidence

After T721/T722 were implemented and committed at
`1abeb42c229ea9f5a0509b63eb627fd3baf28768`, the focused blockers from the
candidate audit were replayed through the synchronized live harness:

```text
Audit root: local/manual-testing/t721-t722-focused-postfix-20260608-080618
Workspace root: local/manual-workspaces/t721-t722-focused-postfix-20260608-080618
Version: talosVersion=0.10.0
Built launcher check: build/install/talos/bin/talos.bat --version reported Talos 0.10.0
Canary scan: PASS over the focused testing and workspace roots
```

Qwen `t325-python-command-boundary` result:

- Summary: `local/manual-testing/t721-t722-focused-postfix-20260608-080618/qwen-t325/SYNCHRONIZED-APPROVAL-AUDIT.md`.
- Trace status: `COMPLETE`.
- Contract: `FILE_CREATE`.
- Final workspace contains both `dijkstra.py` and `test_dijkstra.py`.
- Trace used `talos.read_file` and two `talos.write_file` calls.
- The previous `talos.apply_workspace_batch` blocker did not recur.
- Verification remained `READBACK_ONLY`, which is expected for this source-derived
  Python file creation lane; Python/pytest was not run and the final answer says
  so.

GPT-OSS `static-web-selector-script-only-verified` result:

- Summary: `local/manual-testing/t721-t722-focused-postfix-20260608-080618/gptoss-static-web/SYNCHRONIZED-APPROVAL-AUDIT.md`.
- Trace status: `PARTIAL`.
- Verification: `PASSED`.
- Runtime blocked wrong target `script-fix.js` before approval, then performed an
  approved exact `talos.edit_file` repair on `script.js`.
- Final diff changes `.missing-button` to `.cta-button` in `script.js`.
- `scripts.js` remained unchanged.

This focused rerun reduces the specific T721/T722 blockers, but it does not
close T284. The full current-candidate Qwen/GPT-OSS prompt-bank result packet is
still required.

## 2026-06-08 Post-T734 current-candidate result packet

After T730-T734 were committed, the current candidate was rebuilt and installed
from commit `87f9fc1a019abbaba546e4264d111a0bb848a55b`
(`talosVersion=0.10.0`).

Evidence roots:

- Main release packet:
  `local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958`.
- Capability/private-document packet:
  `local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability`.
- Local report:
  `work-cycle-docs/reports/current-0.10.0-release-packet-post-t734-results.md`.

Results:

- `.\gradlew.bat check --no-daemon` passed before the post-T734 install.
- `.\gradlew.bat installDist --no-daemon` passed.
- Safe redirected TalosBench lanes passed all non-approval cases for Qwen and
  GPT-OSS.
- Qwen synchronized approval lane: 25 scenarios, artifact scan PASS.
- GPT-OSS synchronized approval lane: 25 scenarios, artifact scan PASS.
- Qwen `t325-python-command-boundary` is explicitly scored
  `PASS_WITH_READBACK_ONLY_LIMITATION`; the expected files exist, Python/pytest
  was not run, and the final answer reports that command execution was
  unavailable.
- GPT-OSS has two `PASS_WITH_RUNTIME_REPAIR` cases where the trace is partial
  but final verification passed after runtime repair.
- Capability/private-mode audit passed 44 process/tool-artifact heuristic rows
  across GPT-OSS and Qwen, including PDF/DOCX/XLS/XLSX extraction and
  private-mode withholding.
- The capability CSV recorded no raw secret leaks, no raw canary leaks, no
  unsupported-overclaim flags, no unexpected private document targets, and no
  missing required prompt-debug evidence.
- Release-clean artifact scan over model-facing artifacts and redacted
  snapshots passed without an allowlist.

Keep T284 open for final release reconciliation. The evidence is valid and much
stronger than the previous failed candidate packet, but true PTY/JLine current
candidate evidence and full native-tool prompt-bank reconciliation remain
outside this packet.

## 2026-06-08 WS5 provider-body quality review

Evidence report:

```text
work-cycle-docs/reports/current-0.10.0-ws5-provider-body-quality-review.md
```

The report spot-checks the post-T734 capability/private-mode artifacts against
their prompt-debug/provider-body evidence. It confirms:

- 16/16 document handoff rows across GPT-OSS and Qwen had the expected target
  extracted/read and the expected handoff state.
- 8 public document rows recorded `SENT_TO_MODEL` and provider bodies contained
  the public extracted fixture text.
- 8 private document/retrieve rows recorded `WITHHELD_PRIVATE_MODE` and
  provider-body/prompt-debug/output artifacts did not contain configured private
  document values or protected canaries.
- Non-document privacy-sensitive rows recorded no raw secret leaks, no raw
  canary leaks, and no unsupported-overclaim flags in the summary CSV.
- The refreshed release-clean canary scan passed over the post-T734 model-facing
  packet roots.

Keep T284 open. The WS5 review strengthens the result packet, but final closure
still requires the remaining current-candidate PTY/full-native-tool release
evidence to be reconciled in one final packet.

## 2026-06-10 current 0.10.1 execution packet

Current execution packet:

- `work-cycle-docs/reports/current-0.10.1-release-packet-20260610-090049-results.md`

Authoritative lane roots:

- safe redirected GPT-OSS:
  `local/manual-testing/current-0.10.1-release-packet-20260610-090049/artifacts/gptoss/talosbench/20260610-090530`
- safe redirected Qwen:
  `local/manual-testing/current-0.10.1-release-packet-20260610-090049/artifacts/qwen/talosbench/20260610-090842`
- synchronized approval GPT-OSS:
  `local/manual-testing/current-0.10.1-release-packet-20260610-090049/artifacts/gptoss/sync-approval`
- synchronized approval Qwen failures:
  `.../artifacts/qwen/sync-approval` and `.../artifacts/qwen/sync-approval-r2`
- focused Qwen blocker reruns:
  `.../artifacts/qwen/t325-python-command-boundary` and
  `.../artifacts/qwen/workspace-batch-apply-approved`
- capability/private-mode:
  `local/manual-testing/current-0.10.1-release-packet-20260610-090049-capability`

Result:

- current `0.10.1` packet exists and is lane-labeled;
- GPT-OSS is green across the safe lane, full synchronized lane, and capability
  lane;
- Qwen is green on the safe lane, focused `t325`, focused workspace-op live
  filters, and the capability lane, but not yet on the full synchronized live
  bank;
- the PTY lane is still `MANUAL_REQUIRED`.

Keep T284 open. This is valid release evidence, but it is still an execution
packet with unresolved PTY/manual and Qwen full-lane stability gaps, not a
beta-ready close.

## 2026-06-10 evidence-repair addendum

Additional execution evidence:

- third Qwen full-bank live attempt, failed closed at
  `workspace-batch-apply-approved` after 30 completed scenarios:
  `local/manual-testing/current-0.10.1-qwen-syncbank-r3-20260610-210541/artifacts`
  (manual canary scan over the r3 root passed);
- final classification recorded in the packet report: full-bank live-model
  instability with fail-closed runtime behavior, model-owned.

The packet report and provenance corrections were committed in `953bf4eb`.

2026-06-10 21:55: the human PTY lane is complete and validated (validation
PASS, canary scan PASS, independently rerun). Keep T284 open pending only the
final owner beta decision on the model-owned Qwen full-bank instability.
