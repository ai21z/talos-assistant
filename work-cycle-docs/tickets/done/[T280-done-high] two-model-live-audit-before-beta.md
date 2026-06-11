# T280 - Two-Model Live Audit Before Beta

Status: done - 0.10.2 release-grade two-model packet complete from a clean committed candidate (all executed lanes green for both models)
Severity: high / release gate
Release gate: yes
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-16
Owner: unassigned

## Problem

Deterministic tests are necessary but do not prove live model/tool/prompt behavior. The two-model prompt-bank audit was not run in this pass.

## Evidence from current code

No code issue by itself. This is a release-process gate.

## Evidence from tests/audits

- `work-cycle-docs/reports/t267-live-two-model-audit.md` records the runbook.
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md` records that full prompt-bank execution was not run in this pass.
- `ollama list` crashed with access violation `0xc0000005`.
- Local Talos config showed one GPT-OSS llama.cpp config. That is expected because managed `llama_cpp` currently has one active `model_path` per config; Qwen/GPT-OSS audit execution must use sequential isolated configs.
- On 2026-05-16 both Qwen and GPT-OSS GGUF files were found locally and both passed a model-forced Talos smoke prompt after stale repo-owned `llama-server.exe` processes were stopped. Latest smoke evidence: `t267-live-audit-20260516-091319`; repo-owned stale server count after the run was 0.

## User impact

Without live evidence, runtime policy and model behavior may interact in untested ways.

## Product risk

High for developer/text beta; blocker for private-document beta.

## Runtime boundary affected

Policy classification, tool visibility, approval gates, provider-body safety, final-answer truthfulness, artifacts.

## Non-goals

- Do not replace deterministic tests with live audit.
- Do not accept final answers without traces/artifacts.

## Required behavior

Run the prompt bank against `qwen2.5-coder:14b` and `gpt-oss:20b` or configured audited local profiles.

## Proposed implementation

Use the runbook in `t267-live-two-model-audit.md` and store artifacts under ignored `local/manual-testing/<audit-id>`.

## Tests

Live audit prompts and artifact canary scan.

## Acceptance criteria

- Report states pass/fail per model.
- No private-document release-ready claim if audit is not run or fails.

## Rollback / migration notes

Raw audit artifacts must not be committed.

## Open questions

- Which local profiles are considered release-audited if Qwen/GPT-OSS are unavailable?

## Related files

- `work-cycle-docs/reports/t267-live-two-model-audit.md`
- `work-cycle-docs/reports/t267-live-two-model-audit-results.md`

## 2026-05-15 final pre-beta update

Added `scripts/run-t267-live-audit.ps1` preflight. Previous preflight was BLOCKED because it expected Qwen and GPT-OSS in one config. Updated preflight checks actual model files and supports the correct sequential isolated-config strategy. Running only smoke prompts must not be counted as prompt-bank completion.

2026-05-16 follow-up: the script now supports `-StopStaleServers` and `-SmokeModels`. This makes the local backend setup reproducible, but the prompt-bank execution/classification is still open.

Follow-up ticket: T286.

## 2026-05-20 lane-labeled evidence update

The release evidence is no longer completely absent:

- Preflight PASS: `lane-bank-preflight-20260520`.
- Two-model smoke PASS: `lane-bank-smoke-models-20260520`.
- Strict `SAFE_REDIRECTED_STDIN` lane PASS for both models:
  - GPT-OSS: 19/19 PASS, summary at `local/manual-testing/lane-bank-safe-20260520/artifacts/gptoss/safe-redirected/20260520-224336/summary.md`.
  - Qwen: 19/19 PASS, summary at `local/manual-testing/lane-bank-safe-20260520/artifacts/qwen/safe-redirected/20260520-224631/summary.md`.
- Strict lane artifact scan PASS over `local/manual-testing/lane-bank-safe-20260520` and `local/manual-workspaces/lane-bank-safe-20260520`.
- `SYNC_APPROVAL` lane PASS through `runSynchronizedApprovalAudit` at `local/manual-testing/lane-bank-sync-20260520/artifacts`.
- `TRUE_PTY_MANUAL` packet prepared at `local/manual-testing/lane-bank-pty-manual-20260520/artifacts`; status remains `MANUAL_REQUIRED`.

## 2026-05-20 true PTY/manual lane update

The true terminal/JLine packet is now completed and validated:

```text
Audit id: true-pty-manual-20260520-r1
Artifacts: local/manual-testing/true-pty-manual-20260520-r1/artifacts
Workspace: local/manual-workspaces/true-pty-manual-20260520-r1/workspace
Model/backend: llama_cpp/gpt-oss-20b / llama.cpp
Validator: validateSynchronizedApprovalPtyManualAudit PASS
Artifact scan: PASS
```

This closes the missing true-terminal evidence lane for this audit wave. This
ticket remains open for final clean-candidate verification and release-level
two-model prompt-bank reconciliation, because the working tree is still dirty
and this pass is not a versioned candidate packet.

## 2026-06-07 T719/T720 focused audit note

Focused installed-product evidence exists for the T719/T720 slice:

- Audit root: `local/manual-testing/t719-t720-focused-p21-audit-20260607-220219`.
- Installed Talos reported `Talos 0.9.9`.
- Redacted audit snapshots were generated and scanned.
- Combined artifact canary scan passed:
  `local/manual-testing/t719-t720-focused-p21-audit-20260607-220219/CANARY-SCAN-ALL.txt`.
- GPT-OSS and a Qwen explicit-read path exercised the conditional no-change
  branch with `SATISFIED_BY_INSPECTION` and `Verification: NOT_RUN`.

This does not close T280. It was a focused evidence-hygiene and P21 wording
audit, not a full two-model prompt-bank or versioned release-candidate packet.

## 2026-06-07 0.10.0 release-gate reconciliation

Scope decision: the `0.10.0` beta decision must include PDF/DOCX/XLSX text
extraction claims, while image/OCR, PowerPoint, browser-render proof, Qodana
triage, public MSI/signing, and architecture-cycle cleanup remain outside the
next audit batch.

Ticket relationship: T280 is the mandate for the current-candidate two-model
live audit. T284 is the results/evidence packet for the same gate. Do not delete
or close either ticket until the current `talosVersion=0.10.0` candidate has a
release-grade pass/fail report.

Existing `current-two-model-audit-20260607-204059` evidence is useful milestone
evidence, but it ran on commit `5014076` / installed Talos `0.9.9` and is not a
release close for HEAD `afde6472` / `0.10.0`.

Next audit must:

- run Qwen and GPT-OSS against the current installed `0.10.0` candidate;
- use isolated homes/workspaces;
- capture `/last trace`, `/prompt-debug last`, `/prompt-debug save`, provider
  bodies, final file state, diffs, and approval evidence;
- use synchronized/manual approval handling for approval-sensitive prompts;
- include PDF/DOCX/XLSX beta-core extraction probes and truthfulness review;
- use redacted final-workspace snapshots for release-clean artifact scans.

## 2026-06-08 Post-T734 current-candidate evidence update

New current-candidate evidence exists at commit
`87f9fc1a019abbaba546e4264d111a0bb848a55b`, `talosVersion=0.10.0`, after
T734 fixed the source-evidence post-read continuation blocker found in the
first synchronized run.

Evidence roots:

- Main packet:
  `local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958`.
- Capability/private-document packet:
  `local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability`.
- Summary report:
  `work-cycle-docs/reports/current-0.10.0-release-packet-post-t734-results.md`.

Results:

- Installed `build/install/talos/bin/talos.bat --version` reported Talos
  `0.10.0`.
- Safe redirected TalosBench lanes passed all non-approval cases for Qwen and
  GPT-OSS; approval-sensitive and true-PTY cases remained lane-labeled
  `MANUAL_REQUIRED`.
- Synchronized approval live lanes passed 25 scenarios for Qwen and GPT-OSS,
  with artifact scan PASS in each summary.
- Capability/private-mode bank ran 44 turns across both models, including
  PDF/DOCX/XLS/XLSX beta-core extraction. The summary CSV recorded zero raw
  secret leaks, zero raw canary leaks, zero unsupported-overclaim flags, zero
  unexpected private document targets, and zero missing required prompt-debug
  evidence.
- Redacted snapshots were generated for sync and capability workspaces.
- Release-clean model-facing artifact scan passed without an allowlist over
  the two `local/manual-testing/...` packet roots.

Keep T280 open. This is strong candidate evidence, but the current packet still
does not provide current-commit true PTY/JLine evidence, and the redirected
TalosBench lane still labels approval-sensitive/native-operation cases as
manual-required rather than fully covered.

## 2026-06-08 WS5 provider-body quality review

Added a focused provider-body/prompt-debug quality review for the post-T734
capability/private-mode packet:

```text
work-cycle-docs/reports/current-0.10.0-ws5-provider-body-quality-review.md
```

Result:

- All privacy-sensitive provider-required rows had prompt-debug/provider-body
  evidence where required by the capability script.
- Private PDF/DOCX/XLSX and private retrieve-disabled rows recorded
  `WITHHELD_PRIVATE_MODE`, no unexpected private document targets, and no raw
  private/protected fixture value hits in model-facing provider-body,
  prompt-debug, or output artifacts.
- Public PDF/DOCX/XLSX rows had provider-body evidence containing the expected
  public extracted fixture text.
- `/show` private document rows were local-display-only (`provider_bodies=0`)
  and redacted the private value.
- Fresh release-clean artifact scan over the two post-T734 model-facing packet
  roots passed without an allowlist.

Keep T280 open. This removes much of the prior "heuristic only" uncertainty for
the post-T734 capability packet, but it still does not provide true PTY/JLine
coverage or a final clean beta decision packet for the current dirty branch.

## 2026-06-10 current 0.10.1 packet update

Current candidate evidence now exists for commit
`016467943b4bd0819708a070a229b18d766f182a`, `talosVersion=0.10.1`.

Evidence roots:

- main packet:
  `local/manual-testing/current-0.10.1-release-packet-20260610-090049`
- capability packet:
  `local/manual-testing/current-0.10.1-release-packet-20260610-090049-capability`
- summary report:
  `work-cycle-docs/reports/current-0.10.1-release-packet-20260610-090049-results.md`

Results:

- safe redirected TalosBench lanes passed the non-approval cases for GPT-OSS
  and Qwen with `Piped approval inputs allowed: False`; approval-sensitive cases
  remained `MANUAL_REQUIRED`;
- GPT-OSS full synchronized approval live lane passed with 31 scenarios and
  artifact scan PASS;
- Qwen full synchronized approval live lane failed closed twice on different
  scenarios (`workspace-batch-apply-approved`, then
  `t325-python-command-boundary`), while focused reruns of those scenarios
  passed individually;
- capability/private-mode bank passed 44 turns across both models;
- release-clean canary scan over the model-facing packet roots passed.

Keep T280 open. The current-candidate packet is real and strong, but the fresh
PTY/JLine human lane is still incomplete and the Qwen full synchronized lane is
not stable enough to support a beta-ready close.

## 2026-06-10 evidence-repair addendum

A third Qwen full-bank live attempt
(`local/manual-testing/current-0.10.1-qwen-syncbank-r3-20260610-210541/artifacts`)
failed closed again at `workspace-batch-apply-approved` after 30 completed
scenarios (the model issued no workspace-batch tool call; manual canary scan
over the r3 root passed). Final classification: full-bank live-model
instability with fail-closed runtime behavior — model-owned, since GPT-OSS
passes the same 31-scenario bank and the runtime failed closed correctly in
all three attempts.

2026-06-10 21:55: the human PTY lane is now complete and validated
(`pty-qwen/artifacts/PTY-MANUAL-AUDIT-VALIDATION.md` Status: PASS; canary scan
PASS; both independently rerun). T306 is closed on that evidence.

Keep T280 open. The single remaining blocker is the owner beta decision on
whether the model-owned Qwen full-bank instability blocks beta or is recorded
as a known model limitation with focused-rerun coverage and a green GPT-OSS
full bank.

## 2026-06-11 wave-1 stabilization update

The instability was re-rooted as a runtime constraint-coverage gap and fixed
(T739-T744). Both full banks are now green as stabilization evidence:
Qwen 31/31 (first complete bank, 4th attempt) at
`local/manual-testing/wave1-stabilization-qwen-20260611-005233/artifacts` and
GPT-OSS 31/31 zero-rescue at
`local/manual-testing/wave1-stabilization-gptoss-20260611-005426/artifacts`.
Bank-position hypothesis falsified by byte-identical seeded provider bodies.
Remaining for close: the release-grade two-model packet from the committed
`0.10.2` candidate (wave close).

## 2026-06-11 closure

The 0.10.2 release-grade packet (commit `157aedc8`, scripted hermetic cut)
satisfies the mandate: safe lanes 19 PASS / 22 MANUAL_REQUIRED per model with
piped approvals disallowed, full 31-scenario synchronized banks green for both
models (first release-grade Qwen full-bank pass; 5 bounded T743 rescues
honestly recorded), capability lane 48/48 with zero leaks, PTY lane carried
from the validated 0.10.1 owner run, canary scans PASS. Report:
`work-cycle-docs/reports/current-0.10.2-release-packet-20260611-081900-results.md` + machine-checkable GATES ledger. Acceptance criteria met:
pass/fail per model is stated, and no private-document release claim is made
(that claim still gates on T276/T281/T283).

Close T280.