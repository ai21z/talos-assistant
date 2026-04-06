<#
.SYNOPSIS
  Uninstall Talos from a Windows user profile.

.DESCRIPTION
  Reverses tools/install-windows.ps1:
   - Stops running Talos Java processes (best-effort).
   - Removes %LOCALAPPDATA%\Programs\talos (or custom -InstallDir).
   - Removes the Talos bin path from the User PATH only.
   - Optionally deletes user data at "$HOME\.talos" (indices, caches, config).
   - Idempotent; safe to run multiple times.

.PARAMETER InstallDir
  The root installation directory. Default: "$env:LOCALAPPDATA\Programs\talos"

.PARAMETER Purge
  Shortcut for -RemoveUserData.

.PARAMETER RemoveUserData
  Remove "$HOME\.talos" (indices, caches, config). Does not touch Ollama models.

.PARAMETER Quiet
  Suppress confirmation prompt.

.EXAMPLE
  pwsh tools/uninstall-windows.ps1

.EXAMPLE
  pwsh tools/uninstall-windows.ps1 -WhatIf

.EXAMPLE
  pwsh tools/uninstall-windows.ps1 -Quiet

.EXAMPLE
  pwsh tools/uninstall-windows.ps1 -Quiet -Purge
#>

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'Programs\talos'),
    [switch]$Purge,
    [Alias('RemoveData')][switch]$RemoveUserData,
    [switch]$Quiet
)

function Write-Step([string]$msg) { Write-Host ("- " + $msg) }
function Write-Info([string]$msg) { Write-Host ("  " + $msg) -ForegroundColor DarkGray }
function Write-Warn2([string]$msg) { Write-Warning $msg }

# Expand Purge -> RemoveUserData
if ($Purge) { $RemoveUserData = $true }

# Normalize paths
$resolved = Resolve-Path -LiteralPath $InstallDir -ErrorAction SilentlyContinue
if ($resolved) { $InstallDir = $resolved.Path }
$BinDir   = Join-Path $InstallDir 'bin'
$UserData = Join-Path $HOME '.talos'

# 0) Confirm (unless -Quiet or -WhatIf or -Confirm:$false)
if (-not $Quiet -and -not $WhatIfPreference) {
    $dataRemovalText = if ($RemoveUserData) { "YES" } else { "NO" }
    $msg = "Uninstall Talos from:`n  Install: $InstallDir`n  Remove PATH entry: $BinDir`n  Remove user data (~\.talos): $dataRemovalText"
    $title = "Confirm Talos uninstall"
    $choices = New-Object Collections.ObjectModel.Collection[Management.Automation.Host.ChoiceDescription]
    $choices.Add((New-Object Management.Automation.Host.ChoiceDescription "&Yes", "Proceed"))
    $choices.Add((New-Object Management.Automation.Host.ChoiceDescription "&No", "Cancel"))
    $sel = $Host.UI.PromptForChoice($title, $msg, $choices, 1)
    if ($sel -ne 0) { Write-Host "Cancelled."; return }
}

# Set ConfirmPreference if -Quiet is specified (suppresses all confirmation prompts)
if ($Quiet) {
    $ConfirmPreference = 'None'
}

# 1) Stop any Talos Java processes (best-effort)
Write-Step "Stopping running Talos processes (if any)"
try {
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.CommandLine -and (
                $_.CommandLine -match [regex]::Escape($InstallDir) -or
                        $_.CommandLine -match 'dev\.talos' -or
                        $_.CommandLine -match 'talos\.jar'
                )
            }
    if ($procs) {
        foreach ($p in $procs) {
            try {
                if ($PSCmdlet.ShouldProcess("Process $($p.ProcessId) ($($p.Name))", "Stop-Process")) {
                    Write-Info ("Stopping PID {0}: {1}" -f $p.ProcessId, $p.Name)
                    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
                }
            } catch {}
        }
    } else {
        Write-Info "No matching processes found."
    }
} catch {
    Write-Warn2 ("Process scan failed (continuing): {0}" -f $_.Exception.Message)
}

# 2) Remove Talos bin from User PATH
Write-Step "Removing Talos bin from User PATH"

if ($PSCmdlet.ShouldProcess($BinDir, "Remove from User PATH")) {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')

    if (-not $current) {
        Write-Info "User PATH is empty (nothing to remove)."
    } else {
        $parts = $current -split ';' | Where-Object { $_ -and $_.Trim() -ne '' }
        $before = $parts.Count

        # Normalize target path for comparison
        $targetNormalized = $BinDir.TrimEnd('\').ToLower()

        # Filter out entries that match the target path
        $filtered = $parts | Where-Object {
            $entryNormalized = $_.Trim().TrimEnd('\').ToLower()
            $entryNormalized -ne $targetNormalized
        }

        if ($filtered.Count -ne $before) {
            $newPath = ($filtered -join ';')
            [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')
            Write-Info ("Removed PATH entry: {0}" -f $BinDir)
            Write-Info "PATH updated in the User profile. Open a NEW terminal to pick up changes."
        } else {
            Write-Info "No PATH entry found (already removed or never installed)."
        }
    }
}

# 3) Remove install directory
Write-Step "Removing install directory"
if (Test-Path -LiteralPath $InstallDir) {
    if ($PSCmdlet.ShouldProcess($InstallDir, "Remove-Item -Recurse -Force")) {
        try {
            Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction Stop
            Write-Info ("Deleted: {0}" -f $InstallDir)
        } catch {
            Write-Warn2 ("Could not delete '{0}': {1}" -f $InstallDir, $_.Exception.Message)
        }
    }
} else {
    Write-Info "Install directory not found (already removed?)."
}

# 4) Optional: remove user data (~\.talos)
if ($RemoveUserData) {
    Write-Step ("Removing Talos user data ({0})" -f $UserData)
    if (Test-Path -LiteralPath $UserData) {
        if ($PSCmdlet.ShouldProcess($UserData, "Remove-Item -Recurse -Force")) {
            try {
                Remove-Item -LiteralPath $UserData -Recurse -Force -ErrorAction Stop
                Write-Info ("Deleted: {0}" -f $UserData)
            } catch {
                Write-Warn2 ("Could not delete '{0}': {1}" -f $UserData, $_.Exception.Message)
            }
        }
    } else {
        Write-Info "User data not found (already removed?)."
    }
} else {
    Write-Info ("Keeping user data at: {0}" -f $UserData)
}

Write-Host "Talos uninstall complete." -ForegroundColor Green
Write-Host "Open a NEW terminal to pick up PATH changes." -ForegroundColor Yellow
