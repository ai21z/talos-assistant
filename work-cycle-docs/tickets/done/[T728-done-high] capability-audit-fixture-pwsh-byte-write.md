# T728 - Capability Audit Fixture PowerShell 7 Byte Write

Status: done
Priority: high
Created: 2026-06-08
Completed: 2026-06-08

## Problem

The post-T727 capability audit did not reach Talos when run under PowerShell 7. The audit fixture writer used `Set-Content -Encoding Byte`, which is accepted by Windows PowerShell but rejected by PowerShell 7 in this environment.

## Evidence

- Failed command: `pwsh .\scripts\run-capability-live-audit.ps1 -AuditId current-0.10.0-post-t727-capability-... -BetaCoreOnly -PrivateFolderBank -StopStaleServers`.
- Error: `Cannot process argument transformation on parameter 'Encoding'. 'Byte' is not a supported encoding name.`
- Source: `scripts/run-capability-live-audit.ps1` wrote `image.png` and `binary.bin` with `Set-Content -Encoding Byte`.

## Implementation

- Replaced `Set-Content -Encoding Byte` for binary fixtures with `[System.IO.File]::WriteAllBytes(...)`.
- Added self-test coverage that exercises `Write-AuditWorkspace(...)` and verifies `image.png` and `binary.bin` are created with non-empty/expected binary sizes.

## Acceptance Criteria

- The audit fixture writer is compatible with PowerShell 7 for binary file creation. Done.
- The self-test exercises `Write-AuditWorkspace(...)` enough to catch unsupported byte-write APIs before a live audit run. Done.
- `image.png` and `binary.bin` are still created as binary fixtures. Done.
- No Talos runtime behavior changes. Done; script-only audit harness change.

## Verification

Passed:

```powershell
pwsh .\scripts\run-capability-live-audit.ps1 -SelfTest
```

Then rerun the post-fix capability audit.
