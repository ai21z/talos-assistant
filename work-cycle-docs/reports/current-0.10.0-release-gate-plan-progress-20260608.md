# Current 0.10.0 Release-Gate Plan Progress

Date: 2026-06-08

Plan executed from:
`work-cycle-docs/research/opus-0.10.0-beta-release-implementation-plan-for-codex.md`

Current branch: `codex/t312-live-workspace-ops`

Current HEAD: `23bdbb403b52a8f4e088675f3c50c1f771b18794`

Version: `talosVersion=0.10.0`

Working-tree status after checkpoint commit: clean.

Checkpoint commit:
`23bdbb403b52a8f4e088675f3c50c1f771b18794`
(`T312 add live workspace-op release gates`).

## Summary

The plan is materially advanced but not complete. The strongest progress is
that the six native workspace-operation tools now have live-model synchronized
approval scenarios and focused two-model evidence, including a Qwen rerun after
clarifying the `apply_workspace_batch` model-facing schema. The biggest
remaining hard blocker is unchanged: current-candidate true PTY/JLine coverage
is prepared but not completed because a maintainer must run the manual packet in
a real interactive terminal and supply `PTY-MANUAL-AUDIT-RESULT.json`.

No beta-ready verdict is supported yet.

## Completed Or Advanced Workstreams

### WS1 - Live Native Workspace-Op Coverage

Implemented live synchronized approval scenarios for:

```text
talos.mkdir
talos.copy_path
talos.move_path
talos.rename_path
talos.delete_path
talos.apply_workspace_batch
```

Added a trace postcondition requiring the intended tool to appear in trace
evidence, so a scenario cannot pass by accidentally using a different tool.

Focused evidence:

```text
local/manual-testing/t312-ws1-live-workspace-ops-20260608-213656
local/manual-testing/t312-ws1-live-workspace-ops-post-schema-20260608-215206
```

Result:

- GPT-OSS passed all six live workspace-op filters.
- Qwen passed five initially, failed `workspace-batch-apply-approved` twice,
  then passed the focused post-schema rerun after the
  `operations_json` descriptor named required per-operation keys.
- Canary scans passed for both WS1 evidence roots.

T312 impact: native workspace-op live evidence is now substantially stronger,
but T312 remains open until the broader current-candidate lane-labeled packet is
rerun/reconciled and true PTY/JLine evidence is attached.

#### Current Native Tool Coverage Matrix

The current full-audit native tool surface is the 13-tool set named in
`work-cycle-docs/full-e2e-audit-workflow.md` and T312. This corrects the older
Prompt A report wording that focused on 10 tools and omitted read-only
`talos.list_dir`, `talos.grep`, and `talos.retrieve`.

| Tool | Current evidence status |
| --- | --- |
| `talos.list_dir` | Covered by safe redirected/capability lanes and named by the full audit workflow; still part of the final packet reconciliation. |
| `talos.read_file` | Covered broadly by safe redirected/capability/synchronized lanes, including document extraction and static-web evidence. |
| `talos.grep` | Covered by safe redirected/capability lanes and named by the full audit workflow; still part of the final packet reconciliation. |
| `talos.retrieve` | Covered by safe redirected/capability lanes and prior synchronized harness regression; still part of the final packet reconciliation. |
| `talos.write_file` | Covered by synchronized static-web mutation lanes, including the COD-A-001 false-success-prevention regression. |
| `talos.edit_file` | Covered by existing static-web/edit prompt-bank lanes, but final packet must still reconcile the current branch evidence. |
| `talos.run_command` | Covered by the `t325-python-command-boundary` synchronized live lane with readback-only limitation. |
| `talos.mkdir` | New synchronized live workspace-op evidence exists for both models. |
| `talos.copy_path` | New synchronized live workspace-op evidence exists for both models. |
| `talos.move_path` | New synchronized live workspace-op evidence exists for both models. |
| `talos.rename_path` | New synchronized live workspace-op evidence exists for both models. |
| `talos.delete_path` | New synchronized live workspace-op evidence exists for both models. |
| `talos.apply_workspace_batch` | New synchronized live workspace-op evidence exists for both models after the `operations_json` descriptor clarification and Qwen focused rerun. |

This matrix is evidence progress, not a closure claim. T312 remains open until
the final lane-labeled current-candidate packet includes the read-only,
mutation, command, workspace-operation, and true PTY/JLine lanes together.

### WS2 - COD-A-001 Runtime Catch Regression

Added a deterministic synchronized approval scenario for the malformed Qwen
static-web selector rewrite shape where `textContent = 'Clicked';` becomes
malformed code. The scenario asserts runtime verification failure and failure
bundle output, proving false-success prevention.

Focused evidence:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
```

Result: passed after implementation.

### WS3 - Current-Candidate PTY Packet

Prepared a manual true PTY/JLine packet:

```text
local/manual-testing/t312-ws3-pty-manual-20260608-214542/artifacts
local/manual-workspaces/t312-ws3-pty-manual-20260608-214542/workspace
```

The packet is intentionally `MANUAL_REQUIRED`. Validation failed closed because
`PTY-MANUAL-AUDIT-RESULT.json` is absent. This is the correct state until a
maintainer completes the runbook in a real interactive terminal.

### WS4 - T313 Fail-Closed Regression

Added deterministic synchronized harness coverage for the missing approval
response shape:

- scripted model requests a mutation;
- approval script supplies no approval step;
- harness raises `Unexpected approval prompt`;
- workspace remains unchanged.

Focused evidence:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
```

Result: passed.

T313 remains open because release evidence must still demonstrate
approval-sensitive cases through synchronized/manual lanes in the final packet.

### WS5 - Provider-Body / Prompt-Debug Quality Review

Added WS5 report:

```text
work-cycle-docs/reports/current-0.10.0-ws5-provider-body-quality-review.md
```

Result:

- Public PDF/DOCX/XLSX rows contain extracted public fixture text in provider
  bodies.
- Private PDF/DOCX/XLSX and private retrieve-disabled rows record
  `WITHHELD_PRIVATE_MODE` and show no configured private value in provider-body,
  prompt-debug, or output artifacts.
- `/show` rows are local-display-only and redact the configured private value.
- Release-clean canary scan passed over the post-T734 model-facing packet roots.

T280/T284/T299/T301 remain open because WS5 is an evidence increment, not the
final beta packet.

### WS7 - Release-Claim Reconciliation Slice

Reviewed the current README, public installation document, landing page, and
release reports against the post-T734/WS5 evidence. The main capability claims
are aligned: PDF/DOCX/XLS/XLSX are narrow text-extraction claims, image/OCR and
PowerPoint are frozen, private paperwork is explicitly not an approved beta
claim, and browser/static-web language does not claim render proof without a
separate browser audit.

One stale public-facing detail was found and fixed: the landing page
screen-reader description of the startup screenshot still said `TALOS v0.9.9`.
It now says `TALOS v0.10.0`, and the static site test rejects the stale
`TALOS v0.9.9` text in the hero slice.

Targeted claim tests passed after the fix:

```powershell
npm --prefix site test
.\gradlew.bat test --tests "dev.talos.docs.ReadmePrivacyCopyTest" --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
```

### WS7 - T299 Adversarial Classification And XLS Parity Slice

Added deterministic adapter coverage for corrupt PDF, DOCX, and XLS extraction,
plus XLS hidden-sheet/formula limitation parity. The new tests prove invalid
beta-scope documents are classified as `CORRUPT`, return blank extracted text,
emit `document-corrupt`, and are not allowed into model handoff as evidence.
They also prove hidden `.xls` sheets are skipped with an explicit limitation and
`.xls` formula cells report formula text plus cached values with a
non-recalculation warning. The classifier now recognizes structural PDF
corruption signals such as missing root/trailer/xref/end-of-file and `.xls`
invalid OLE2/header signatures as corrupt rather than generic extraction
failures.

Focused verification:

```powershell
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.extract.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.tools.impl.ReadFileToolTest" --no-daemon
```

Result: passed.

T299 remains open because this is a targeted generated-fixture regression, not
the larger maintained fixture corpus or final two-model live release packet.

## Verification Run In This Slice

Focused and full checks already run in this in-progress branch:

```powershell
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.workspace.*" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t312-ws1-live-workspace-ops-20260608-213656,local/manual-workspaces/t312-ws1-live-workspace-ops-20260608-213656" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/t312-ws1-live-workspace-ops-post-schema-20260608-215206,local/manual-workspaces/t312-ws1-live-workspace-ops-post-schema-20260608-215206" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958,local/manual-testing/current-0.10.0-release-packet-post-t734-20260608-191958-capability" --no-daemon
git diff --check
npm --prefix site test
.\gradlew.bat test --tests "dev.talos.docs.ReadmePrivacyCopyTest" --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionAdaptersTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.extract.*" --no-daemon
.\gradlew.bat test --tests "dev.talos.tools.impl.ReadFileToolTest" --no-daemon
```

Latest full `check` result observed in this branch before the checkpoint commit:
passed after the WS7 XLS parity slice.

Latest `git diff --check`: no whitespace errors; line-ending conversion warnings
only.

Latest targeted claim tests: passed (`site` static contract 27/27 and Gradle
README/public-install packaging claim tests).

Post-commit installed-product rebuild:

```powershell
.\gradlew.bat installDist --no-daemon
.\build\install\talos\bin\talos.bat --version
```

Result:

```text
Talos 0.10.0 - Java 21.0.9+10-LTS - Windows 11 amd64 - build 2026-06-08T20:58:33.241413100Z
```

## Remaining Work Before Any Beta-Ready Claim

1. Complete the true PTY/JLine manual packet in a real terminal and validate it.
   Without this, T306 and the PTY half of T312 cannot close.
2. Commit or otherwise stabilize the current WS1/WS2/WS3/WS4/WS5 branch, rebuild
   and reinstall from the committed SHA, then rerun/reconcile the current
   candidate packet.
3. Run the final lane-labeled two-model packet after the new workspace-op live
   scenarios are present in the installed binary.
4. Reconcile T280/T284/T299/T301/T306/T312/T313 from that final packet only.
5. Keep T299 open unless maintainers explicitly decide that larger
   maintained/adversarial fixtures are post-beta scope. Current evidence proves
   generated small-fixture beta-core routing/withholding, not real-world
   document-quality robustness.
6. Keep T301 open until README, reports, tickets, and any public capability
   matrix are reconciled against the final packet.

## Verdict

High confidence: the plan has made concrete progress on the two biggest
evidence gaps, especially live workspace-operation coverage and WS5 provider
body review.

Also high confidence: the plan is not complete and `0.10.0` remains a
candidate, not an open-beta-ready release.
