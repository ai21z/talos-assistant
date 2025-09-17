# LOQ-J Windows Uninstaller
# Removes LOQ-J from your system by:
# - Stopping any running LOQ-J Java processes
# - Removing LOQ-J bin directory from User PATH
# - Deleting installation directory (%LOCALAPPDATA%\Programs\loqj)
# - Optionally removing user data (~\.loqj) with -Purge flag
# - Broadcasting PATH changes to other applications

[CmdletBinding(SupportsShouldProcess=$true, ConfirmImpact='High')]
param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'Programs\loqj'),
    [switch]$Purge,
    [Alias('RemoveData')][switch]$RemoveUserData,
    [switch]$Quiet
)

function Write-Step($msg) { Write-Host "• $msg" }
function Write-Info($msg) { Write-Host "  $msg" -ForegroundColor DarkGray }
function Write-Warn2($msg){ Write-Warning $msg }

# Expand Purge shortcut
if ($Purge) { $RemoveUserData = $true }

# Normalize paths
$InstallDir = (Resolve-Path -LiteralPath $InstallDir -ErrorAction SilentlyContinue)?.Path ?? $InstallDir
$BinDir     = Join-Path $InstallDir 'bin'
$UserData   = Join-Path $HOME '.loqj'

# 0) Confirm
if (-not $Quiet) {
    $msg = "Uninstall LOQ-J from:`n  Install: $InstallDir`n  Remove PATH entry: $BinDir`n  Remove user data (~\.loqj): " + ($RemoveUserData ? "YES" : "NO")
    $title = "Confirm LOQ-J uninstall"
    $choices = New-Object Collections.ObjectModel.Collection[Management.Automation.Host.ChoiceDescription]
    $choices.Add((New-Object Management.Automation.Host.ChoiceDescription "&Yes", "Proceed"))
    $choices.Add((New-Object Management.Automation.Host.ChoiceDescription "&No", "Cancel"))
    $sel = $Host.UI.PromptForChoice($title, $msg, $choices, 1)
    if ($sel -ne 0) { Write-Host "Cancelled."; return }
}

# 1) Attempt to stop any LOQ-J-related Java processes
Write-Step "Stopping running LOQ-J processes (if any)"
try {
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.CommandLine -and (
                $_.CommandLine -match [regex]::Escape($InstallDir) -or
                        $_.CommandLine -match 'dev\.loqj' -or
                        $_.CommandLine -match 'loqj\.jar'
                )
            }
    if ($procs) {
        $procs | ForEach-Object {
            try {
                Write-Info "Stopping PID $($_.ProcessId): $($_.Name)"
                Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            } catch {}
        }
    } else {
        Write-Info "No matching processes found."
    }
} catch {
    Write-Warn2 "Process scan failed (continuing): $($_.Exception.Message)"
}

# 2) Remove LOQ-J bin from *User* PATH
function Remove-FromUserPath([string]$target) {
    $current = [Environment]::GetEnvironmentVariable('Path', 'User')
    if (-not $current) { return $false }
    $parts = $current -split ';' | Where-Object { $_ -and $_.Trim() -ne '' }
    $before = $parts.Count
    $filtered = $parts | Where-Object {
        $p = $_.Trim()
        # Case-insensitive exact match on normalized path
        -not ($p.TrimEnd('\') -ieq ($target.TrimEnd('\')))
    }
    if ($filtered.Count -ne $before) {
        $new = ($filtered -join ';')
        [Environment]::SetEnvironmentVariable('Path', $new, 'User')
        return $true
    }
    return $false
}

Write-Step "Removing LOQ-J bin from User PATH"
$removed = Remove-FromUserPath $BinDir  # Remove the Test-Path check - function handles non-existent paths fine
if ($removed) {
    Write-Info "Removed PATH entry: $BinDir"
    # Broadcast environment change to other windows (best-effort)
    try {
        Add-Type -Namespace Win32 -Name Native -MemberDefinition @"
using System;
using System.Runtime.InteropServices;
public static class Native {
  [DllImport("user32.dll", SetLastError=true, CharSet=CharSet.Auto)]
  public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam, uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
}
"@ -ErrorAction SilentlyContinue | Out-Null
        $HWND_BROADCAST = [IntPtr]0xffff
        $WM_SETTINGCHANGE = 0x001A
        $r = [UIntPtr]::Zero
        [Win32.Native]::SendMessageTimeout($HWND_BROADCAST, $WM_SETTINGCHANGE, [UIntPtr]::Zero, "Environment", 2, 5000, [ref]$r) | Out-Null
    } catch {
        Write-Info "PATH updated; open a NEW terminal to pick up changes."
    }
} else {
    Write-Info "No PATH entry found (already removed or never installed)."
}

# 3) Remove install directory
Write-Step "Removing install directory"
if (Test-Path -LiteralPath $InstallDir) {
    if ($PSCmdlet.ShouldProcess($InstallDir, "Remove-Item -Recurse -Force")) {
        try {
            Remove-Item -LiteralPath $InstallDir -Recurse -Force -ErrorAction Stop
            Write-Info "Deleted: $InstallDir"
        } catch {
            Write-Warn2 "Could not delete '$InstallDir': $($_.Exception.Message)"
        }
    }
} else {
    Write-Info "Install directory not found (already removed?)."
}

# 4) Optional: remove user data (~\.loqj)
if ($RemoveUserData) {
    Write-Step "Removing LOQ-J user data ($UserData)"
    if (Test-Path -LiteralPath $UserData) {
        if ($PSCmdlet.ShouldProcess($UserData, "Remove-Item -Recurse -Force")) {
            try {
                Remove-Item -LiteralPath $UserData -Recurse -Force -ErrorAction Stop
                Write-Info "Deleted: $UserData"
            } catch {
                Write-Warn2 "Could not delete '$UserData': $($_.Exception.Message)"
            }
        }
    } else {
        Write-Info "User data not found (already removed?)."
    }
} else {
    Write-Info "Keeping user data at: $UserData"
}

Write-Host "✔ LOQ-J uninstall complete." -ForegroundColor Green
Write-Host "   Open a NEW terminal to pick up PATH changes." -ForegroundColor Yellow
