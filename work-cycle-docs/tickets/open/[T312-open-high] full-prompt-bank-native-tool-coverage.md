# [T312-open-high] Full Prompt-Bank Native Tool Coverage

Status: implemented-awaiting-evidence - native-tool prompt-bank coverage added; current stabilized head still needs full prompt-bank/candidate evidence
Severity: high
Release gate: private-document beta / full E2E release evidence
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The full E2E audit standard requires every current native Talos tool to be
probed or explicitly excluded, but the live prompt-bank coverage had drifted
from the registered native tool surface.

## Evidence From Current Code

`TalosBootstrap` registers:

- `talos.list_dir`
- `talos.read_file`
- `talos.grep`
- `talos.retrieve`
- `talos.write_file`
- `talos.edit_file`
- `talos.mkdir`
- `talos.copy_path`
- `talos.move_path`
- `talos.rename_path`
- `talos.delete_path`
- `talos.apply_workspace_batch`
- `talos.run_command`

Before this ticket's first fix, the full audit workflow and operator prompt did
not name `talos.delete_path`, even though it is registered. TalosBench also had
zero prompt-bank mentions for `talos.mkdir`, `talos.copy_path`,
`talos.move_path`, `talos.rename_path`, `talos.delete_path`,
`talos.apply_workspace_batch`, and `talos.run_command`.

## Evidence From Tests/Audits

Added `FullAuditCoverageDocumentationTest`, which fails if the full-audit docs
or TalosBench prompt bank stop naming any current native tool.

Fresh focused evidence:

- Initial run of
  `./gradlew.bat test --tests "dev.talos.audit.FullAuditCoverageDocumentationTest" --no-daemon`
  failed on the missing native-tool coverage.
- After patching docs and TalosBench cases, the same focused test passed.
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` passed and
  validated 40 TalosBench cases.
- Added synchronized harness regression coverage proving `talos.retrieve` is
  executable in the deterministic audit harness and `talos.run_command` reaches
  the command-profile rejection boundary without approval when a Gradle wrapper
  is absent.
- `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon`
  passed after the harness registry was widened.
- Installed-product focused smoke after the prompt-bank expansion:
  `pwsh .\tools\manual-eval\run-talosbench.ps1 -TalosPath .\build\install\talos\bin\talos.bat -CaseId full-audit-mkdir-tool-probe,full-audit-copy-path-tool-probe,full-audit-move-path-tool-probe,full-audit-rename-path-tool-probe,full-audit-delete-path-tool-probe,full-audit-apply-workspace-batch-tool-probe,full-audit-run-command-profile-boundary -IncludeManualRequired -WorkspaceRoot local/manual-workspaces/talosbench-native-tool-smoke-20260519-r4 -TranscriptRoot local/manual-testing/talosbench-native-tool-smoke-20260519-r4`
  passed all seven new native-tool coverage probes using `llama_cpp/gpt-oss-20b`
  and the freshly built `build\install\talos\bin\talos.bat` launcher.
  This run predates the later T313 fail-closed gate for piped approval input.
  Repeating this exploratory shape now requires the explicit
  `-AllowPipedApprovalInputs` switch and must not be described as synchronized
  approval release evidence.
- During that installed smoke, `full-audit-delete-path-tool-probe` first exposed
  a real classifier gap: `Use talos.delete_path to delete delete-me.tmp.`
  was classified as read-only because `MutationIntent` did not allow sentence
  punctuation after an explicit file-target mutation. Added focused regressions
  in `TaskContractResolverTest` and `WorkspaceOperationIntentTest`; both now
  pass.
- Comparable focused Qwen smoke:
  created an isolated Talos home under
  `local/manual-testing/talosbench-native-tool-smoke-qwen-20260519-home`,
  copied the known Qwen config into `.talos/config.yaml`, and ran the same seven
  native-tool probes with `JAVA_OPTS=-Duser.home=<isolated-home>`.
  Summary:
  `local/manual-testing/talosbench-native-tool-smoke-qwen-20260519/20260519-143649/summary.md`.
  Result: all seven probes passed with `llama_cpp/qwen2.5-coder-14b`.
  Caveat: the isolated home had no first-run sentinel, so transcripts include
  the first-run setup banner before the audited prompts.
- Synchronized scripted approval bank follow-up:
  - Added deterministic synchronized approval scenarios for `talos.mkdir`,
    `talos.copy_path`, `talos.move_path`, `talos.rename_path`,
    `talos.delete_path`, and `talos.apply_workspace_batch`.
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest.deterministic_audit_entrypoint_writes_summary_bundles_and_scan_result" --no-daemon` failed while those scenarios were absent and passed after adding them.
  - `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon` passed.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=build/synchronized-approval-audit/artifacts" "-PapprovalAuditWorkspacesRoot=build/synchronized-approval-audit/workspaces" --no-daemon` passed with 29 scripted scenarios and artifact scan PASS.
  - `./gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/synchronized-approval-audit/artifacts" --no-daemon` passed.
- Full installed-product prompt-bank evidence after the latest hardening pass:
  - GPT-OSS full prompt-bank run passed 40/40:
    `local/manual-testing/talosbench-full-gptoss-20260519-r3/20260519-162507/summary.md`.
  - Qwen full prompt-bank rerun passed 40/40:
    `local/manual-testing/talosbench-full-qwen-20260519-r2/20260519-163747/summary.md`.
  - The Qwen r1 run failed `full-audit-mkdir-tool-probe` because an invalid model tool-call payload produced no approval prompt and the redirected-stdin runner consumed the queued approval token `a` as the next user request. A focused Qwen rerun of that case passed at `local/manual-testing/talosbench-qwen-mkdir-20260519-r1/20260519-163730/summary.md`.
  - Targeted artifact scans passed over the GPT-OSS r3 and Qwen r2 full-run roots.
  - `tools/manual-eval/run-talosbench.ps1` now detects this approval-token drift explicitly in its transcript assertions, and `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest` plus `-ValidateOnly` passed after the guard was added.
  - T313 follow-up hardening now makes approval-sensitive TalosBench cases
    return `SYNC_REQUIRED` by default when `-IncludeManualRequired` is used
    without `-AllowPipedApprovalInputs`. The old full prompt-bank summaries are
    retained as historical/exploratory installed-product evidence, not as
    synchronized approval proof.
- Fresh evidence-lane rebaseline after sink hardening:
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly` passed and validated 41 cases.
  - `pwsh .\tools\manual-eval\run-talosbench.ps1 -ListCases` shows the current prompt bank includes safe redirected cases, approval-sensitive manual cases, and the `full-audit-run-command-profile-boundary` non-approval command-boundary case.
  - `./gradlew.bat runSynchronizedApprovalAudit "-PapprovalAuditArtifactsRoot=local/manual-testing/t306-t313-sync-rebaseline-20260520-221208/artifacts" "-PapprovalAuditWorkspacesRoot=local/manual-workspaces/t306-t313-sync-rebaseline-20260520-221208" --no-daemon` passed with 32 scripted scenarios and artifact scan PASS.
  - This is not a fresh two-model full prompt-bank pass. T312 remains open for lane-labeled GPT-OSS/Qwen evidence under the current fail-closed TalosBench runner.

## 2026-06-07 0.10.0 coverage reconciliation

Native-tool prompt-bank coverage is present in docs and deterministic coverage
checks, but T312 remains open until the current `0.10.0` candidate has a
lane-labeled Qwen/GPT-OSS evidence packet.

The next run must cover or explicitly exclude every current native tool named
above, plus the current beta capability families. Because beta scope includes
PDF/DOCX/XLSX text extraction, the full prompt-bank evidence must include those
formats and their private-mode/model-handoff limitations. Image/OCR and
PowerPoint remain deferred and must be explicitly excluded from beta claims.

Historical TalosBench passes and synchronized scripted runs are retained as
useful regression evidence. They do not close T312 for `0.10.0`.

## User Impact

Without this coverage guard, a future release report can claim "full native tool
audit" while silently skipping registered tools. That creates false confidence
around workspace organization, deletion, command-profile boundaries, and
approval behavior.

## Product Risk

High. The risk is not that these tools are known broken; the risk is that the
release evidence can omit them while still using full-audit language.

## Runtime Boundary Affected

- native tool surface
- mutation approval boundary
- workspace operation safety
- command profile boundary
- audit evidence truthfulness

## Non-Goals

- This ticket does not make the full two-model prompt-bank audit pass.
- This ticket does not replace the true PTY/JLine manual audit.
- This ticket does not broaden Talos into arbitrary shell execution.

## Required Behavior

- Full-audit docs must name every current native tool or explicitly exclude it.
- TalosBench must contain prompt-bank probes for every current native tool.
- Missing prompt-bank coverage must fail a deterministic test.
- Approval-sensitive prompts must remain marked as approval-sensitive.

## Proposed Implementation

First slice completed in the working tree:

- Added `FullAuditCoverageDocumentationTest`.
- Added `talos.delete_path` to the full E2E audit workflow and operator prompt.
- Added TalosBench prompt-bank probes for:
  - `talos.mkdir`
  - `talos.copy_path`
  - `talos.move_path`
  - `talos.rename_path`
  - `talos.delete_path`
  - `talos.apply_workspace_batch`
  - `talos.run_command`
- The TalosBench command probe uses the supported V1 `gradle_test` profile and
  is expected to prove bounded command-tool behavior. In a tiny fixture without
  a Gradle wrapper, a truthful runtime rejection is acceptable evidence for the
  command boundary; a full repository audit may instead run the profile.
- Widened `SynchronizedApprovalAuditRunner`'s deterministic registry to include
  `talos.retrieve` and `talos.run_command`, matching more of the production
  bootstrap surface for focused audit tests.

Remaining implementation:

- Workspace-operation prompt-bank probes now exist in the Java synchronized
  scripted audit main. The remaining question is whether to add live-model
  versions before the broader synchronized full prompt-bank run.
- Keep the full prompt-bank evidence separate from true PTY/JLine coverage and
  synchronized approval evidence. The historical full GPT-OSS/Qwen TalosBench
  runs are redirected-stdin installed-product evidence, not an interactive
  terminal audit. Future release-grade prompt-bank evidence must be lane-labeled:
  safe redirected cases, synchronized approval cases, manual true-PTY/JLine
  cases, and known-blocked/deferred cases. Approval-sensitive redirected runs now
  fail closed unless the operator explicitly opts into exploratory piped approval
  input.

## Tests

- `./gradlew.bat test --tests "dev.talos.audit.FullAuditCoverageDocumentationTest" --no-daemon`
- `./gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedApprovalAuditRunnerTest" --no-daemon`
- `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon`
- `./gradlew.bat test --tests "dev.talos.runtime.workspace.WorkspaceOperationIntentTest" --no-daemon`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly`
- `pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest`
- focused installed TalosBench native-tool smoke listed above

## Acceptance Criteria

- The deterministic coverage guard passes.
- TalosBench validation passes.
- A future full E2E audit either runs these probes or explicitly marks each
  remaining skipped tool out of scope with a reason.

## Remaining Blockers

- True PTY/JLine prompt rendering and input synchronization now has a completed
  manual packet under T306/T313:
  `local/manual-testing/true-pty-manual-20260520-r1/artifacts`.
- The full prompt-bank runner now detects approval-token drift and refuses
  approval-sensitive piped runs by default, but it still uses redirected stdin
  when `-AllowPipedApprovalInputs` is explicitly supplied. Do not call it a
  synchronized approval runner.
- 2026-05-20 strict safe-lane rerun now proves the non-approval native-tool
  command-boundary case for both GPT-OSS and Qwen with strict prompt-debug,
  session-save, transcript, and workspace-status evidence:
  - GPT-OSS safe lane: 19/19 PASS.
  - Qwen safe lane: 19/19 PASS.
  - `full-audit-run-command-profile-boundary` passed in both model lanes.
  - Fresh synchronized approval audit passed with 32 scripted scenarios,
    including workspace operation tools.
  - True PTY/JLine manual packet passed separately at
    `local/manual-testing/true-pty-manual-20260520-r1/artifacts`.

## Open Questions

- Should workspace-operation probes graduate from scripted synchronized coverage
  into the synchronized Java live-audit runner before the next installed-product
  run, or remain part of the broader full prompt-bank pass?
- Should `talos.delete_path` be included in the standard user-facing tool list,
  or remain explicitly audited but not emphasized?

## Related Files

- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/test/java/dev/talos/audit/FullAuditCoverageDocumentationTest.java`
- `tools/manual-eval/talosbench-cases.json`
- `work-cycle-docs/full-e2e-audit-workflow.md`
- `work-cycle-docs/full-e2e-audit-operator-prompt.md`

## 2026-06-07 T719/T720 focused audit note

The T719/T720 audit updated artifact-hygiene and conditional static-web
diagnostic wording only. It did not expand native-tool prompt-bank coverage and
does not close any T312 acceptance criteria. Future full prompt-bank runs should
use the new `writeRedactedAuditSnapshot` task for release-clean final workspace
evidence instead of copying raw fixture snapshots into scanned artifact roots.

## 2026-06-08 T721/T722 focused native-tool/sync evidence

Focused synchronized live evidence after T721/T722 shows that two current
prompt-bank blocker cases now reach acceptable runtime outcomes:

- Qwen `t325-python-command-boundary` used the narrowed source-derived file
  creation surface (`read_file` plus `write_file`) and created the expected
  Python files without `apply_workspace_batch` or `run_command`.
- GPT-OSS `static-web-selector-script-only-verified` recovered from a wrong
  target by blocking `script-fix.js` before approval and applying the verified
  edit to `script.js`; `scripts.js` stayed unchanged.

Evidence root:
`local/manual-testing/t721-t722-focused-postfix-20260608-080618`.

This does not close T312 because the current `0.10.0` full prompt-bank still
needs lane-labeled Qwen/GPT-OSS coverage for every native tool and beta
capability family, including PDF/DOCX/XLS/XLSX extraction.
