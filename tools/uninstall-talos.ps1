<#
.SYNOPSIS
  Uninstall Talos from the current Windows user profile.

.DESCRIPTION
  Removes the public Talos user-local install:
   - Stops running Talos Java processes on a best-effort basis.
   - Removes %LOCALAPPDATA%\Programs\Talos, or a custom -InstallDir.
   - Removes the Talos bin path from the User PATH only.
   - Optionally deletes user data at "$HOME\.talos" when -Purge is used.
   - Does not remove user-owned llama.cpp installs or model files outside .talos.

.PARAMETER InstallDir
  The root installation directory. Default: "$env:LOCALAPPDATA\Programs\Talos"

.PARAMETER Purge
  Shortcut for -RemoveUserData.

.PARAMETER RemoveUserData
  Remove "$HOME\.talos" including config, indices, logs, and Talos-owned caches.

.PARAMETER Quiet
  Suppress confirmation prompt.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\uninstall-talos.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\uninstall-talos.ps1 -Purge
#>

[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'Programs\Talos'),
    [switch]$Purge,
    [Alias('RemoveData')][switch]$RemoveUserData,
    [switch]$Quiet
)

function Write-Step([string]$Message) { Write-Host ("- " + $Message) }
function Write-Info([string]$Message) { Write-Host ("  " + $Message) -ForegroundColor DarkGray }
function Write-Warn2([string]$Message) { Write-Warning $Message }

if ($Purge) { $RemoveUserData = $true }

$resolved = Resolve-Path -LiteralPath $InstallDir -ErrorAction SilentlyContinue
if ($resolved) { $InstallDir = $resolved.Path }
$BinDir = Join-Path $InstallDir 'bin'
$UserData = Join-Path $HOME '.talos'

if (-not $Quiet -and -not $WhatIfPreference) {
    $dataRemovalText = if ($RemoveUserData) { "YES" } else { "NO" }
    $message = "Uninstall Talos from:`n  Install: $InstallDir`n  Remove PATH entry: $BinDir`n  Remove user data (~\.talos): $dataRemovalText"
    $choices = New-Object Collections.ObjectModel.Collection[Management.Automation.Host.ChoiceDescription]
    $choices.Add((New-Object Management.Automation.Host.ChoiceDescription "&Yes", "Proceed"))
    $choices.Add((New-Object Management.Automation.Host.ChoiceDescription "&No", "Cancel"))
    $selected = $Host.UI.PromptForChoice("Confirm Talos uninstall", $message, $choices, 1)
    if ($selected -ne 0) {
        Write-Host "Cancelled."
        return
    }
}

if ($Quiet) {
    $ConfirmPreference = 'None'
}

Write-Step "Stopping running Talos processes if any"
try {
    $processes = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.CommandLine -and (
                    $_.CommandLine -match [regex]::Escape($InstallDir) -or
                    $_.CommandLine -match 'dev\.talos' -or
                    $_.CommandLine -match 'talos\.jar'
                )
            }
    if ($processes) {
        foreach ($process in $processes) {
            try {
                if ($PSCmdlet.ShouldProcess("Process $($process.ProcessId) ($($process.Name))", "Stop-Process")) {
                    Write-Info ("Stopping PID {0}: {1}" -f $process.ProcessId, $process.Name)
                    Stop-Process -Id $process.ProcessId -Force -ErrorAction SilentlyContinue
                }
            } catch {}
        }
    } else {
        Write-Info "No matching processes found."
    }
} catch {
    Write-Warn2 ("Process scan failed, continuing: {0}" -f $_.Exception.Message)
}

Write-Step "Removing Talos bin from User PATH"
if ($PSCmdlet.ShouldProcess($BinDir, "Remove from User PATH")) {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ([string]::IsNullOrWhiteSpace($current)) {
        Write-Info "User PATH is empty."
    } else {
        $target = $BinDir.TrimEnd('\').ToLowerInvariant()
        $parts = $current -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        $filtered = $parts | Where-Object { $_.Trim().TrimEnd('\').ToLowerInvariant() -ne $target }
        if ($filtered.Count -ne $parts.Count) {
            [Environment]::SetEnvironmentVariable('Path', ($filtered -join ';'), 'User')
            Write-Info ("Removed PATH entry: {0}" -f $BinDir)
            Write-Info "Open a new terminal to pick up PATH changes."
        } else {
            Write-Info "No PATH entry found."
        }
    }
}

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
    Write-Info "Install directory not found."
}

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
        Write-Info "User data not found."
    }
} else {
    Write-Info ("Keeping user data at: {0}" -f $UserData)
}

Write-Host "Talos uninstall complete." -ForegroundColor Green
Write-Host "Open a new terminal to pick up PATH changes." -ForegroundColor Yellow
