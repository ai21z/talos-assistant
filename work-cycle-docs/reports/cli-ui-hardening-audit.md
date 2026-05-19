# CLI UI Hardening Audit

Date: 2026-05-19
Branch: v0.9.0-beta-dev
Commit inspected: ec69415
Candidate version: 0.9.9

## Scope

This audit covers the latest CLI/UI changes in the working tree:

- `src/main/java/dev/talos/cli/ui/AnswerPaneRenderer.java`
- `src/main/java/dev/talos/cli/ui/ApprovalPromptRenderer.java`
- `src/main/java/dev/talos/cli/ui/ProgressLineRenderer.java`
- `src/main/java/dev/talos/cli/ui/PromptRenderer.java`
- `src/main/java/dev/talos/cli/ui/SemanticGlyphSet.java`
- `src/main/java/dev/talos/cli/repl/RenderEngine.java`
- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/runtime/CliApprovalGate.java`
- `src/main/java/dev/talos/cli/launcher/RunCmd.java`
- `src/main/java/dev/talos/cli/launcher/RootCmd.java`

The audit also checks whether this UI layer is represented in the Talos work-test/audit cycle and open-ticket backlog.

## What Is Working

- The new UI has a clear renderer layer instead of scattering terminal chrome through runtime code.
- `RenderEngine` routes final answers through `AnswerPaneRenderer`.
- Streaming natural-language output is wrapped through `RenderEngine.answerStreamSink(...)` after `ToolCallStreamFilter`, so tool-call protocol text should remain suppressed before answer-pane rendering.
- `CliApprovalGate` uses `ApprovalPromptRenderer` for approval/trust prompts.
- `RunCmd` delegates REPL prompt text to `PromptRenderer`.
- `SemanticGlyphSet` has explicit Unicode and ASCII glyph sets.
- Focused renderer tests cover answer panes, streaming rails, approval windows, progress lines, prompt stable text, and ASCII fallback safety.
- Installed redirected-CLI smoke proves the approval prompt is visible in process output, denial works, and raw canary text is not printed.
- Manual true-terminal PTY/JLine evidence now proves prompt rendering, answer pane rendering, route/progress rendering, approval trust-window rendering, denial timing, `/last trace`, and `/prompt-debug save` in a real Windows terminal session.

## Fix Completed During This Audit

The installed root command previously rejected `--help` and `-h`, and the root help description still said `Talos - Local Knowledge Engine`.

Fix:

- Added explicit `-h/--help` option to `RootCmd`.
- Updated root description to `Talos - local-first workspace operator`.
- Added `RootCmdTest` coverage for `--help`, `-h`, and stale-copy prevention.

## Verification Run

Passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.RootCmdTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.cli.ui.*" --tests "dev.talos.cli.repl.RenderEngineTest" --tests "dev.talos.runtime.CliApprovalGateTest" --tests "dev.talos.runtime.ApprovalGateTest" --tests "dev.talos.cli.launcher.RootCmdTest" --tests "dev.talos.cli.launcher.RunCmdTerminalModeTest" --tests "dev.talos.app.ui.TerminalFirstRunTest" --no-daemon
.\gradlew.bat installDist --no-daemon
.\gradlew.bat runSynchronizedApprovalCliSmoke --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/synchronized-cli-approval-smoke-20260519-184820" "-PartifactScanAllowlist=local/manual-testing/synchronized-cli-approval-smoke-20260519-184820/workspace/.env" --no-daemon
.\gradlew.bat prepareSynchronizedApprovalPtyManualAudit "-PptyManualArtifactsRoot=build/synchronized-pty-manual/artifacts" "-PptyManualWorkspace=build/synchronized-pty-manual/workspace" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/synchronized-pty-manual/artifacts,build/synchronized-pty-manual/workspace" "-PartifactScanAllowlist=build/synchronized-pty-manual/workspace/.env" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedCli*" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.SynchronizedCliPtyManualAudit*" --no-daemon
git diff --check
```

Installed CLI spot checks passed:

```powershell
.\build\install\talos\bin\talos.bat --help
.\build\install\talos\bin\talos.bat -h
.\build\install\talos\bin\talos.bat -v
@('/privacy status','/q') | .\build\install\talos\bin\talos.bat --no-logo run
```

`git diff --check` emitted CRLF warnings only.

## Evidence Artifacts

- Redirected CLI smoke summary: `local/manual-testing/synchronized-cli-approval-smoke-20260519-184820/SYNCHRONIZED-CLI-APPROVAL-SMOKE.md`
- Redirected CLI smoke transcript: `local/manual-testing/synchronized-cli-approval-smoke-20260519-184820/transcript.txt`
- PTY manual audit runbook: `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-RUNBOOK.md`
- PTY manual audit status: `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-STATUS.json`
- PTY manual audit result template: `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-RESULT-TEMPLATE.json`
- PTY manual audit transcript: `build/synchronized-pty-manual/artifacts/TRANSCRIPT.md`
- PTY manual audit result JSON: `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-RESULT.json`
- PTY manual audit validation summary: `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-VALIDATION.md`

## Follow-Up Slice

Additional automated hardening completed on 2026-05-19:

- Added a layout stress test for long unbroken approval detail text, using a Windows-style path.
- Fixed `ApprovalPromptRenderer` so long unbroken detail tokens wrap inside the trust window.
- Fixed the approval choices line so narrow trust windows wrap instead of exceeding the configured width.
- Fixed `SynchronizedCliProcessDriver` so repeated output markers must be seen again for later scripted inputs.
- Expanded `runSynchronizedApprovalCliSmoke` so redirected-process evidence now includes deterministic `/show README.md` answer-pane rendering before the protected-read approval-denial probe.
- Tightened the PTY/JLine manual packet so it now requires:
  - prompt rendering observation
  - deterministic `/show README.md` answer-pane observation
  - route/progress-line observation during the protected-read turn
  - approval trust-window observation
  - artifact scan after the manual transcript is captured
- Added a completed-evidence validator for the manual PTY/JLine packet:
  - generated packets include `PTY-MANUAL-AUDIT-RESULT-TEMPLATE.json`
  - `validateSynchronizedApprovalPtyManualAudit` fails if the completed result JSON is missing
  - the validator requires real-terminal observation flags, denial timing evidence, `/last trace`, `/prompt-debug save`, artifact-scan pass, and no raw protected fixture canary
  - the validator writes `PTY-MANUAL-AUDIT-VALIDATION.md`

Fresh redirected CLI smoke after this slice:

- Summary: `local/manual-testing/synchronized-cli-approval-smoke-20260519-190632/SYNCHRONIZED-CLI-APPROVAL-SMOKE.md`
- Transcript: `local/manual-testing/synchronized-cli-approval-smoke-20260519-190632/transcript.txt`
- Result: `PASS`
- Evidence: `answer pane observed: yes`, `approval prompt observed: yes`, `approval denial observed: yes`, `raw canary observed: no`
- Artifact canary scan: passed with fixture `.env` allowlisted.

Post-clean evidence-order correction on 2026-05-19:

- `./gradlew.bat clean check e2eTest --no-daemon` removes generated `build/` evidence such as `build/install` and `build/synchronized-pty-manual`.
- Regenerated the PTY manual packet after the clean gate:
  `./gradlew.bat prepareSynchronizedApprovalPtyManualAudit "-PptyManualArtifactsRoot=build/synchronized-pty-manual/artifacts" "-PptyManualWorkspace=build/synchronized-pty-manual/workspace" --no-daemon`
- A first parallel attempt to regenerate the PTY packet and run the installed CLI smoke at the same time failed because both tasks depend on `installDist` and can race the same `build/install` tree. Direct installed-command checks passed afterward, and the smoke passed when rerun serially.
- Fresh serial redirected CLI smoke:
  `local/manual-testing/synchronized-cli-approval-smoke-20260519-210430/SYNCHRONIZED-CLI-APPROVAL-SMOKE.md`
- Fresh serial result: `PASS`, `answer pane observed: yes`, `approval prompt observed: yes`, `approval denial observed: yes`, `raw canary observed: no`.
- `validateSynchronizedApprovalPtyManualAudit` failed closed as expected on the uncompleted manual packet because `PTY-MANUAL-AUDIT-RESULT.json` is not present yet.
- Targeted artifact canary scan passed over the regenerated PTY packet/workspace and fresh CLI smoke packet.

Manual PTY/JLine validation on 2026-05-19:

- Human-run real terminal evidence was captured from Windows Terminal / PowerShell.
- `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-VALIDATION.md` reports `Status: PASS`, `true PTY/JLine coverage: manual-validated`, and `Findings: none`.
- The manual transcript includes the Talos banner, `/show README.md` answer pane, route/progress line, approval trust window, denial entered after prompt visibility, blocked protected-read answer, `/last trace`, `/prompt-debug save`, and clean exit.
- Targeted artifact scan passed over the PTY packet/workspace with only the fixture `.env` allowlisted.
- Targeted artifact scan also passed over the prompt-debug markdown and provider-body JSON produced by the manual run:
  - `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260519-211609.md`
  - `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260519-211609.provider-body.json`

## Findings

| ID | Severity | Category | Evidence | Why it matters | Fix direction |
| --- | --- | --- | --- | --- | --- |
| CLI-UI-001 | fixed | audit-design/evidence blocker | Redirected CLI smoke still reports `terminal mode: redirected stdin/stdout process` and `true PTY/JLine coverage: no`, but the manual PTY packet now validates with `Status: PASS` in `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-VALIDATION.md`. | The new UI touches JLine-sensitive streaming, prompt redraw, and approval prompt behavior. Redirected process output alone is not enough, so the manual real-terminal packet is required evidence. | Manual PTY/JLine evidence is validated for this packet. Preserve it in the candidate evidence set; automated ConPTY remains optional future hardening. |
| CLI-UI-006 | fixed | audit-design/evidence hardening | Before this slice, the manual PTY packet had a runbook and transcript template but no validator for completed manual evidence. | A generated packet can be mistaken for evidence if no tool enforces the difference between `MANUAL_REQUIRED` and `PASS`. | Added `SynchronizedCliPtyManualAuditValidator`, result template generation, and the `validateSynchronizedApprovalPtyManualAudit` Gradle task. |
| CLI-UI-007 | fixed | audit-execution hygiene | A parallel local attempt to run `prepareSynchronizedApprovalPtyManualAudit` and `runSynchronizedApprovalCliSmoke` failed with an empty transcript before the prompt marker. Direct installed-command checks passed and the smoke passed when rerun serially. | Both tasks depend on `installDist`; running them in parallel can race the generated launcher tree and contaminate audit evidence. | Treat `installDist`-dependent audit tasks as serial steps in local evidence runs. |
| CLI-UI-002 | fixed | UX bug | `ApprovalPromptRendererTest.longUnbrokenDetailIsWrappedInsideTrustWindow` failed before the renderer patch because the approval choices line exceeded width 60 and long path-like details were not safely split. | Approval prompts are user-control surfaces. Long Windows paths are common and must not break the trust window. | Fixed in `ApprovalPromptRenderer`; focused test now passes. |
| CLI-UI-004 | P2 | UX evidence gap | Unit tests now cover long approval detail wrapping, but no automated true-terminal test covers resize behavior or streamed answer-pane redraw under JLine. | Low-to-moderate user risk: output may remain functionally correct while looking bad or wrapping awkwardly in a real terminal. | Keep T314 open for manual PTY/JLine evidence or automated ConPTY coverage. |
| CLI-UI-003 | fixed | CLI UX bug | Installed `talos --help` and `talos -h` previously failed with `Unknown option`; `RootCmd` copy said `Local Knowledge Engine`. | Root help is a first-contact UI surface. Broken help and stale identity contradict product doctrine. | Fixed in `RootCmd`; covered by `RootCmdTest`; installed help checks pass. |
| CLI-UI-005 | fixed | audit-runner bug | `SynchronizedCliProcessDriverTest.repeated_marker_must_appear_again_for_later_step` failed before the cursor patch because a second step could reuse the old prompt marker. | Repeated prompt markers are normal in REPL transcripts. Reusing an old marker can send input too early and contaminate CLI evidence. | Fixed with cursor-based marker search in `SynchronizedCliProcessDriver`; focused e2e tests pass. |

## Verdict

The new CLI UI is good enough to continue in the current implementation cycle, but it is not final release evidence.

Automated evidence proves:

- renderer unit behavior
- ASCII fallback safety
- stable prompt contract
- installed redirected CLI answer-pane plus approval-denial smoke
- manual true-terminal PTY/JLine prompt, answer pane, progress, approval-window, denial, trace, and prompt-debug evidence
- artifact canary cleanliness for the smoke packet
- artifact canary cleanliness for manual PTY packet and saved prompt-debug/provider-body files
- root help/version behavior
- fail-closed validation rules for completed manual PTY/JLine evidence

Not proven:

- automated ConPTY coverage
- resize behavior under real terminal conditions
- broader terminal matrix coverage outside the validated Windows Terminal / PowerShell run

## Decision

Do not block core runtime hardening on the UI layer. T314's manual true-terminal evidence gate is now satisfied for the current packet, but the evidence must be preserved in the candidate packet after any later clean/build/version bump.

Recommended next move:

1. Keep the new UI implementation.
2. Keep focused tests in the normal work-test cycle.
3. Preserve the validated PTY/JLine packet in release evidence.
4. Treat automated ConPTY and resize coverage as follow-up hardening, not as blockers for the already validated manual packet unless the release process requires automation.
