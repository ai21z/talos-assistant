# TalosBench Manual Runner

This folder contains the first TalosBench live prompt runner. It runs installed
Talos against controlled local fixtures and writes raw transcripts under
`local/manual-testing/talosbench/`.

TalosBench is intentionally local-first:

- do not use real private documents as fixtures
- do not commit raw transcripts
- do not treat this runner as a replacement for deterministic unit/e2e tests
- do not hide failures; convert repeated failures into architectural tickets

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

Run selected non-approval cases:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1 `
  -CaseId capability-onboarding,privacy-no-workspace,simple-folder-listing
```

Run every non-manual case:

```powershell
pwsh .\tools\manual-eval\run-talosbench.ps1
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

Starter cases live in `talosbench-cases.json`. T50 supports these fields:

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

T51 should add structured `/last trace` parsing. T50 only performs transcript
substring checks.
