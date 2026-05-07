# TalosBench Manual Runner

This folder contains the first TalosBench live prompt runner. It runs installed
Talos against controlled local fixtures and writes raw transcripts under
`local/manual-testing/talosbench/`.

The T61 pack is the T54 regression gate. It combines live prompt cases with
deterministic runner self-tests so trace parsing, approval input ordering, and
failure-truth assertions can be checked without launching Talos.

TalosBench is intentionally local-first:

- do not use real private documents as fixtures
- do not commit raw transcripts
- do not treat this runner as a replacement for deterministic unit/e2e tests
- do not hide failures; convert repeated failures into architectural tickets

For the large Qwen/GPT-OSS full E2E audit, use the tracked runbook and operator
prompt before creating the local audit directory:

- `work-cycle-docs/full-e2e-audit-workflow.md`
- `work-cycle-docs/full-e2e-audit-operator-prompt.md`

## Prerequisites

Install the current Talos build first:

```powershell
pwsh .\tools\uninstall-windows.ps1 -Quiet
./gradlew.bat clean installDist --no-daemon
pwsh .\tools\install-windows.ps1 -Force -Quiet
```

The runner looks for Talos in this order:

1. `-TalosPath`
2. `$env:TALOS_PATH`
3. `%LOCALAPPDATA%\Programs\talos\bin\talos.bat`
4. `talos` on `PATH`

## Usage

List cases:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 -ListCases
```

Validate the case file:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 -ValidateOnly
```

Run deterministic runner self-tests:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 -SelfTest
```

Run selected non-approval cases:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 `
  -CaseId capability-onboarding,privacy-no-workspace,simple-folder-listing
```

Run every non-manual case:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1
```

Create a timestamped T67 full-audit workspace with fixtures, runbook, and
question list:

```powershell
pwsh .\tools\manual-eval\new-t67-audit-workspace.ps1
```

Run approval-sensitive cases only when you intentionally want to pipe the
configured approval inputs:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 `
  -CaseId mutation-create-bmi,literal-exact-write `
  -IncludeManualRequired
```

Approval-sensitive cases are marked `MANUAL_REQUIRED` by default because CLI
approval prompts can be fragile when fully scripted. For critical candidate
evidence, prefer manual runs where a human watches the approval prompt and
records the exact choice.

Use `approvalInputsByPrompt` for multi-turn cases where only specific prompts
need scripted approval input. The runner appends repeated `/last trace` commands
after all prompts and approvals so one can be consumed by an extra approval
prompt while a later one still captures the turn trace. If a scripted approval
case does not produce a recognizable trace block, the case fails with a
diagnostic instead of silently passing.

## Multiline Literal Prompts

TalosBench drives the current REPL through line-oriented stdin. Until Talos has a
dedicated multiline prompt transport, a prompt string that contains physical
CR/LF characters can be split into separate user turns.

For literal audit fixtures that need multiline target content, write the logical
prompt as one physical line and describe line breaks explicitly:

```text
Edit README.md now using talos.write_file. The complete file must contain exactly two lines: first line T61 exact README; second line Line two; no other characters.
```

Manual audits should use the same discipline: submit one logical prompt per
Enter keypress, keep the literal line-break description on that same submitted
line, then run `/last trace` after the answer. Do not paste a raw multiline
literal payload into the current REPL for release-gate evidence.

For prompt-audit smoke runs, enable prompt diagnostics with `/debug prompt` or
the equivalent `/debug prompt on` before the audited prompt. Use `/debug prompt
off` or `/debug off` to return to quiet output.

## Output

Workspaces:

```text
local/manual-workspaces/talosbench/<case-id>/
```

Raw transcripts and run summaries:

```text
local/manual-testing/talosbench/<timestamp>/
```

The summary table includes:

```text
case id | status | category | blocker? | transcript path | notes
```

`BLOCKER` exits with code `2`. `FAIL` exits with code `1`. `PASS`,
`PASS_WITH_FOLLOWUP`, and `MANUAL_REQUIRED` do not fail the script.

## Case Schema

Starter cases live in `talosbench-cases.json`. The runner supports these fields:

- `id`
- `category`
- `workspaceFixture`
- `prompts`
- `expectedContract`
- `expectedToolsAllowed`
- `forbiddenOutputSubstrings`
- `requiredOutputSubstrings`
- `blockerConditions`
- `notes`

Additional fields used by the runner:

- `manualRequired`
- `approvalInputs`
- `approvalInputsByPrompt`
- `traceAssertions`

`approvalInputsByPrompt` must have the same number of entries as `prompts`.
Each entry is an array of approval input lines to send after that prompt.

## Trace Assertions

Cases may include a `traceAssertions` object. The runner parses the latest
`/last trace` text enough to assert runtime facts without committing raw
transcripts.

Trace parsing is section-aware:

- Trace Detail fields use `Trace Detail`, `Last Turn Trace Detail`, or
  `Current Turn Trace`.
- Prompt Audit fields use the nested `Prompt Audit` block.
- Local Trace fields use the `Local Trace` block.
- ANSI terminal escapes are stripped before parsing.

Supported fields:

- `contract`
- `mutationAllowed`
- `classificationReasonContains`
- `phaseIncludes`
- `nativeToolsContains`
- `nativeToolsExcludes`
- `blockedContains`
- `outcomeContains`
- `outcomeExcludes`
- `checkpointContains`
- `verificationContains`
- `verificationExcludes`
- `localTraceOutcomeContains`
- `localTraceOutcomeExcludes`
- `localTraceVerificationContains`
- `localTraceVerificationExcludes`
- `repairContains`
- `promptAuditTaskType`
- `promptAuditActionObligationContains`
- `promptAuditEvidenceObligationContains`
- `promptAuditActiveTaskContextContains`
- `promptAuditArtifactGoalContains`
- `promptAuditCurrentTurnFrameContains`
- `promptAuditHistoryContains`
- `promptAuditRedactionContains`
- `transcriptContains`
- `transcriptExcludes`

Example:

```json
"traceAssertions": {
  "contract": "DIRECTORY_LISTING",
  "mutationAllowed": false,
  "phaseIncludes": ["INSPECT"],
  "nativeToolsContains": ["talos.list_dir"],
  "nativeToolsExcludes": ["talos.read_file", "talos.grep", "talos.retrieve"],
  "localTraceOutcomeExcludes": ["FAILED"],
  "transcriptExcludes": ["SECRET=manual-test", "ALPHA-742"]
}
```

Trace parsing is intentionally conservative and string-based in this version.
If assertions become too complex, prefer adding a new narrowly named trace fact
over expanding global transcript matching.
