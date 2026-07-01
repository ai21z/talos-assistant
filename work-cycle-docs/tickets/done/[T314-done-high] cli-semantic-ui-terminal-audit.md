# T314 - CLI Semantic UI Terminal Audit

Status: done - CLI semantic UI terminal evidence validated; candidate packet preservation remains release-process work
Severity: high
Release gate: yes for finalizing the new CLI UI layer
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-19
Owner: unassigned

## Problem

The new semantic CLI UI layer is covered by unit tests, redirected-process smoke tests, and a validated manual true-terminal PTY/JLine evidence packet.

This matters because the UI changes touch prompt rendering, streamed answer panes, approval windows, progress lines, terminal glyph fallback, and JLine-safe streaming output. Redirected stdin/stdout is not the same as a real Windows terminal.

## Evidence from current code

- `AnswerPaneRenderer` renders block and streamed answer panes.
- `ApprovalPromptRenderer` renders approval/trust windows.
- `ProgressLineRenderer` renders route, tool, and turn progress lines.
- `PromptRenderer` centralizes prompt rendering.
- `SemanticGlyphSet` owns Unicode and ASCII glyphs.
- `RenderEngine.answerStreamSink(...)` wraps natural-language stream chunks in answer-pane chrome.
- `TalosBootstrap` routes the LLM stream through `ToolCallStreamFilter(renderRef.answerStreamSink(...))`.
- `RunCmd` chooses JLine for real interactive terminals and a scripted input path for redirected stdin/stdout.
- `CliApprovalGate` uses the semantic approval prompt renderer.

## Evidence from tests/audits

Passed on 2026-05-19:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.ui.*" --tests "dev.talos.cli.repl.RenderEngineTest" --tests "dev.talos.runtime.CliApprovalGateTest" --tests "dev.talos.runtime.ApprovalGateTest" --tests "dev.talos.cli.launcher.RootCmdTest" --tests "dev.talos.cli.launcher.RunCmdTerminalModeTest" --tests "dev.talos.app.ui.TerminalFirstRunTest" --no-daemon
.\gradlew.bat installDist --no-daemon
.\gradlew.bat runSynchronizedApprovalCliSmoke --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=local/manual-testing/synchronized-cli-approval-smoke-20260519-184820" "-PartifactScanAllowlist=local/manual-testing/synchronized-cli-approval-smoke-20260519-184820/workspace/.env" --no-daemon
.\gradlew.bat prepareSynchronizedApprovalPtyManualAudit "-PptyManualArtifactsRoot=build/synchronized-pty-manual/artifacts" "-PptyManualWorkspace=build/synchronized-pty-manual/workspace" --no-daemon
.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=build/synchronized-pty-manual/artifacts,build/synchronized-pty-manual/workspace" "-PartifactScanAllowlist=build/synchronized-pty-manual/workspace/.env" --no-daemon
```

Generated evidence:

- `local/manual-testing/synchronized-cli-approval-smoke-20260519-184820/SYNCHRONIZED-CLI-APPROVAL-SMOKE.md`
- `local/manual-testing/synchronized-cli-approval-smoke-20260519-184820/transcript.txt`
- `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-RUNBOOK.md`

The redirected CLI smoke explicitly reports:

```text
terminal mode: redirected stdin/stdout process
true PTY/JLine coverage: no
```

The PTY packet explicitly reports:

```text
Status: MANUAL_REQUIRED
```

## User impact

Users may see a polished redirected transcript while real interactive terminal behavior still has redraw, cursor, prompt, or wrapping defects. That would damage trust because approval prompts and protected-content warnings are user-control surfaces, not cosmetic decoration.

## Product risk

High for beta polish and trust UX. This is not currently evidence of protected-content leakage or unapproved mutation, but it is a release-evidence gap for the new CLI UI layer.

## Runtime boundary affected

User-facing approval boundary, REPL prompt boundary, streaming output boundary, and terminal transcript/audit boundary.

## Non-goals

- Do not block runtime privacy or mutation-safety fixes on visual polish.
- Do not claim redirected stdin/stdout is true PTY/JLine evidence.
- Do not weaken approval prompts to make testing easier.
- Do not add broad terminal dependencies unless they improve deterministic evidence.

## Required behavior

- Root `talos --help` and `talos -h` must work.
- Root help must use current Talos product identity, not `Local Knowledge Engine`.
- Answer panes must render safely in streamed and non-streamed output.
- Approval prompts must be visible before the user response is read.
- ASCII fallback must not emit Unicode replacement or question-mark glyph degradation.
- Redirected CLI smoke must remain green.
- True PTY/JLine manual or automated evidence must exist before finalizing the UI layer for beta.

## Proposed implementation

Completed first slice:

- Added `RootCmdTest`.
- Fixed root help flags and stale root description.
- Ran focused renderer/unit checks.
- Ran installed redirected CLI smoke.
- Prepared the manual PTY/JLine audit packet.
- Added CLI UI coverage to `AGENTS.md`.
- Added approval trust-window layout stress coverage for long Windows-style path details.
- Fixed approval prompt wrapping for long unbroken detail tokens and narrow-width choices.
- Tightened the PTY/JLine manual packet to require prompt, deterministic answer-pane, route/progress-line, and approval trust-window observations.
- Fixed `SynchronizedCliProcessDriver` marker synchronization so repeated prompt markers must appear again before later inputs are sent.
- Expanded `runSynchronizedApprovalCliSmoke` to execute `/show README.md` and require `answer pane observed: yes` before the protected-read denial probe.
- Added `validateSynchronizedApprovalPtyManualAudit`, a fail-closed validator for completed manual PTY/JLine evidence. It requires `PTY-MANUAL-AUDIT-RESULT.json`, a completed transcript, real-terminal observation flags, denial timing evidence, `/last trace`, `/prompt-debug save`, artifact-scan pass, and no raw protected fixture canary.
- Added `PTY-MANUAL-AUDIT-RESULT-TEMPLATE.json` to the prepared packet so maintainers update structured evidence instead of treating the generated runbook/status files as completed coverage.

Completed manual PTY/JLine evidence:

- Manual transcript: `build/synchronized-pty-manual/artifacts/TRANSCRIPT.md`
- Manual result JSON: `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-RESULT.json`
- Validation summary: `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-VALIDATION.md`
- Validation status: `PASS`
- Artifact scan passed over the PTY packet/workspace with the fixture `.env` allowlisted.
- Artifact scan also passed over the prompt-debug markdown and provider-body JSON saved by the manual run:
  - `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260519-211609.md`
  - `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260519-211609.provider-body.json`

Remaining implementation:

- Add a Windows ConPTY-backed automated harness only if automated true-terminal evidence becomes a release requirement.
- Add any remaining renderer stress tests that real-terminal evidence exposes.
- Add UI smoke to the normal milestone evidence checklist.

## Tests

Current:

- `dev.talos.cli.ui.*`
- `dev.talos.cli.repl.RenderEngineTest`
- `dev.talos.runtime.CliApprovalGateTest`
- `dev.talos.cli.launcher.RootCmdTest`
- `dev.talos.cli.launcher.RunCmdTerminalModeTest`
- `dev.talos.app.ui.TerminalFirstRunTest`
- `dev.talos.harness.SynchronizedCliPtyManualAuditMainTest`
- `dev.talos.harness.SynchronizedCliPtyManualAuditValidatorTest`
- `dev.talos.harness.SynchronizedCliProcessDriverTest`
- `dev.talos.harness.SynchronizedCliApprovalSmokeMainTest`
- `runSynchronizedApprovalCliSmoke`
- `validateSynchronizedApprovalPtyManualAudit`

Fresh evidence after the answer-pane smoke expansion:

- `local/manual-testing/synchronized-cli-approval-smoke-20260519-190632/SYNCHRONIZED-CLI-APPROVAL-SMOKE.md`
- `local/manual-testing/synchronized-cli-approval-smoke-20260519-190632/transcript.txt`
- Summary reports `Status: PASS`, `answer pane observed: yes`, `approval prompt observed: yes`, `approval denial observed: yes`, `raw canary observed: no`.
- Targeted artifact canary scan passed over that smoke packet with the fixture `.env` allowlisted.

Fresh post-clean serial evidence:

- `./gradlew.bat clean check e2eTest --no-daemon` passed before regenerating the manual packet.
- The generated `build/` PTY packet is not durable across `clean`; it must be regenerated after a clean gate when it is part of the current evidence packet.
- Regenerated PTY manual packet:
  `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-RUNBOOK.md`
- Fresh serial redirected CLI smoke:
  `local/manual-testing/synchronized-cli-approval-smoke-20260519-210430/SYNCHRONIZED-CLI-APPROVAL-SMOKE.md`
- Summary reports `Status: PASS`, `answer pane observed: yes`, `approval prompt observed: yes`, `approval denial observed: yes`, `raw canary observed: no`.
- `validateSynchronizedApprovalPtyManualAudit` failed closed as expected on the uncompleted manual packet because `PTY-MANUAL-AUDIT-RESULT.json` is absent.
- Artifact canary scan passed over the regenerated PTY packet/workspace and fresh CLI smoke packet with only fixture `.env` files allowlisted.
- Operational audit rule: do not run `installDist`-dependent local audit tasks in parallel against the same workspace. A parallel attempt can race `build/install` and contaminate smoke evidence.

Manual PTY/JLine validation evidence:

- `build/synchronized-pty-manual/artifacts/TRANSCRIPT.md` records the real terminal run.
- `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-RESULT.json` records the observed pass flags.
- `build/synchronized-pty-manual/artifacts/PTY-MANUAL-AUDIT-VALIDATION.md` reports `Status: PASS`, `true PTY/JLine coverage: manual-validated`, and `Findings: none`.
- `.\gradlew.bat validateSynchronizedApprovalPtyManualAudit "-PptyManualArtifactsRoot=C:\Users\arisz\Projects\LOQ\loqj-cli\build\synchronized-pty-manual\artifacts" "-PptyManualWorkspace=C:\Users\arisz\Projects\LOQ\loqj-cli\build\synchronized-pty-manual\workspace" --no-daemon` passed.
- `.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=C:\Users\arisz\Projects\LOQ\loqj-cli\build\synchronized-pty-manual\artifacts,C:\Users\arisz\Projects\LOQ\loqj-cli\build\synchronized-pty-manual\workspace" "-PartifactScanAllowlist=C:\Users\arisz\Projects\LOQ\loqj-cli\build\synchronized-pty-manual\workspace\.env" --no-daemon` passed.
- `.\gradlew.bat checkRuntimeArtifactCanaries "-PartifactScanRoots=C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260519-211609.md,C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260519-211609.provider-body.json" --no-daemon` passed.

Needed:

- Preserve this evidence in the candidate packet after any later clean/build/version bump.
- Automated ConPTY test remains optional unless manual evidence is deemed insufficient for the final beta process.
- Resize behavior under real terminal conditions remains a lower-priority visual evidence gap.

## Acceptance criteria

- Manual true PTY/JLine audit transcript is captured and scanned, or equivalent automated PTY/ConPTY coverage passes.
- Manual transcript/result packet passes `validateSynchronizedApprovalPtyManualAudit` before any release report claims true PTY/JLine evidence.
- Approval prompt is visibly rendered before denial/approval input is sent.
- Protected `.env` canary does not appear in final answer, transcript, prompt-debug, trace, provider body, session artifacts, or reports outside allowlisted source fixture.
- Root help/version installed commands pass.
- Redirected CLI smoke still passes.
- Redirected CLI smoke reports `answer pane observed: yes`.
- Artifact canary scan passes over generated UI audit artifacts.
- UI ticket can be closed only with evidence paths recorded.

## Remaining blockers

- No automated Windows ConPTY harness exists.
- Resize/real-terminal streaming layout is still not automatically proven.

## Open questions

- Is manual PTY/JLine evidence sufficient for beta, or should Talos invest in an automated ConPTY harness before beta?
- Should the synchronized CLI smoke eventually include streamed model answer-pane evidence, not only deterministic `/show` answer-pane evidence?
- Should the renderer expose width from terminal capabilities instead of fixed widths in `RenderEngine` and `CliApprovalGate`?

## Related files

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
- `src/test/java/dev/talos/cli/ui/*`
- `src/test/java/dev/talos/cli/repl/RenderEngineTest.java`
- `src/test/java/dev/talos/runtime/CliApprovalGateTest.java`
- `src/test/java/dev/talos/cli/launcher/RootCmdTest.java`
- `work-cycle-docs/reports/cli-ui-hardening-audit.md`
- `work-cycle-docs/tickets/open/[T306-open-high] synchronized-approval-live-audit-runner.md`
- `work-cycle-docs/tickets/open/[T313-open-high] talosbench-piped-approval-drift-on-missing-approval.md`
