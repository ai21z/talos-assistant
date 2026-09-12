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

# BEGIN TALOS UNINSTALL PROCESS SELECTION
# Kept identical in both standalone uninstall scripts; the selector contract test
# checks parity. These helpers inspect supplied records only and never stop a process.
function ConvertTo-TalosUninstallPath([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $candidate = $Value.Replace('/', '\')
    if ($candidate -notmatch '^(?:[A-Za-z]:\\|\\\\[^\\?.][^\\]*\\[^\\]+\\)' -or
        $candidate -match '[\x00-\x1f"]') { return $null }
    try { return [IO.Path]::GetFullPath($candidate).TrimEnd('\') } catch { return $null }
}

function Split-TalosUninstallArguments([string]$CommandLine) {
    # Accept only complete, simply quoted Windows arguments. Escaped quotes,
    # concatenated quoted/unquoted pieces and malformed framing remain UNKNOWN.
    if ([string]::IsNullOrWhiteSpace($CommandLine) -or $CommandLine.Length -gt 32767 -or
        $CommandLine -match '[\x00-\x1f]' -or $CommandLine.Contains('\"')) { return }
    $arguments = [Collections.Generic.List[string]]::new()
    $offset = 0
    foreach ($match in [regex]::Matches($CommandLine, '(?:"([^"]*)"|([^"\s]+))(?: +|$)')) {
        if ($match.Index -ne $offset) { return }
        $argument = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { $match.Groups[2].Value }
        $arguments.Add($argument)
        $offset += $match.Length
    }
    if ($offset -ne $CommandLine.Length) { return }
    return $arguments.ToArray()
}

function Test-TalosUninstallOwnedRecord($Record, [string]$Root) {
    try {
        if ($null -eq $Record -or [uint32]$Record.ProcessId -eq 0 -or
            $null -eq $Record.CreationDate -or [datetime]$Record.CreationDate -eq [datetime]::MinValue) {
            return $false
        }
        $executable = ConvertTo-TalosUninstallPath ([string]$Record.ExecutablePath)
        if ($null -eq $executable) { return $false }
        # Public user-local jpackage layout, or an explicitly selected raw app-image root.
        if ($executable -ieq ($Root + '\app\Talos.exe') -or $executable -ieq ($Root + '\Talos.exe')) {
            return ([string]$Record.Name -ieq 'Talos.exe')
        }
        if ([IO.Path]::GetFileName($executable) -notin @('java.exe', 'javaw.exe') -or
            [string]$Record.Name -ine [IO.Path]::GetFileName($executable)) { return $false }
        $arguments = @(Split-TalosUninstallArguments ([string]$Record.CommandLine))
        if ($arguments.Count -lt 3 -or
            (ConvertTo-TalosUninstallPath $arguments[0]) -ine $executable) { return $false }
        $classPathBound = $false
        for ($index = 1; $index -lt $arguments.Count; $index++) {
            $argument = $arguments[$index]
            if ($argument -ceq '-jar') {
                return ($index + 1 -lt $arguments.Count -and
                    (ConvertTo-TalosUninstallPath $arguments[$index + 1]) -ieq ($Root + '\lib\talos.jar'))
            }
            if ($argument -cin @('-cp', '-classpath', '--class-path')) {
                if ($classPathBound -or ++$index -ge $arguments.Count) { return $false }
                $hasTalosJar = $false
                foreach ($entry in $arguments[$index].Split(';')) {
                    $normalized = ConvertTo-TalosUninstallPath $entry
                    if ($null -eq $normalized -or
                        -not $normalized.StartsWith($Root + '\lib\', [StringComparison]::OrdinalIgnoreCase) -or
                        $normalized.Contains('*') -or [IO.Path]::GetExtension($normalized) -ine '.jar') {
                        return $false
                    }
                    if ($normalized -ieq ($Root + '\lib\talos.jar')) { $hasTalosJar = $true }
                }
                if (-not $hasTalosJar) { return $false }
                $classPathBound = $true
                continue
            }
            # Known single-token JVM options cannot replace the selected main class.
            if ($argument -cmatch '^(?:-D\S+|-X\S+|-XX:\S+|--(?:add-opens|add-exports|enable-native-access)=\S+|-ea|-esa|-da|-dsa|-server|-client)$') {
                continue
            }
            return ($argument -ceq 'dev.talos.app.Main' -and $classPathBound)
        }
    } catch { return $false }
    return $false
}

function Select-TalosUninstallProcesses([object[]]$Snapshot, [uint32]$CurrentProcessId, [string]$InstallRoot) {
    $root = ConvertTo-TalosUninstallPath $InstallRoot
    if ($null -eq $root) { return }
    $byId = @{}
    try {
        foreach ($record in $Snapshot) {
            $recordId = [uint32]$record.ProcessId
            if ($byId.ContainsKey($recordId)) { return }
            $byId[$recordId] = $record
        }
        if (-not $byId.ContainsKey($CurrentProcessId)) { return }
        $excluded = [Collections.Generic.HashSet[uint32]]::new()
        $ancestorId = $CurrentProcessId
        while ($ancestorId -ne 0 -and $byId.ContainsKey($ancestorId)) {
            if ($excluded.Count -ge 256 -or -not $excluded.Add($ancestorId)) { return }
            $ancestorId = [uint32]$byId[$ancestorId].ParentProcessId
        }
        foreach ($record in $Snapshot) {
            if (-not $excluded.Contains([uint32]$record.ProcessId) -and
                (Test-TalosUninstallOwnedRecord $record $root)) { $record }
        }
    } catch { return }
}

function Test-TalosUninstallSameIdentity($Expected, $Observed) {
    try {
        return ($null -ne $Expected -and $null -ne $Observed -and
            [uint32]$Expected.ProcessId -eq [uint32]$Observed.ProcessId -and
            [uint32]$Expected.ParentProcessId -eq [uint32]$Observed.ParentProcessId -and
            $null -ne $Expected.CreationDate -and $null -ne $Observed.CreationDate -and
            [datetime]$Expected.CreationDate -eq [datetime]$Observed.CreationDate -and
            [string]::Equals([string]$Expected.ExecutablePath, [string]$Observed.ExecutablePath, [StringComparison]::OrdinalIgnoreCase) -and
            [string]::Equals([string]$Expected.CommandLine, [string]$Observed.CommandLine, [StringComparison]::Ordinal))
    } catch { return $false }
}
function Test-TalosUninstallLiveIdentity($Expected, [datetime]$Started, [string]$Executable) {
    try {
        if ($null -eq $Expected.CreationDate) { return $false }
        # Win32_Process timestamps expose microseconds; .NET ticks are 100 ns.
        $startedTicks = $Started.ToUniversalTime().Ticks
        $expectedTicks = ([datetime]$Expected.CreationDate).ToUniversalTime().Ticks
        return (($startedTicks - ($startedTicks % 10)) -eq ($expectedTicks - ($expectedTicks % 10)) -and
            [string]::Equals($Executable, [string]$Expected.ExecutablePath, [StringComparison]::OrdinalIgnoreCase))
    } catch { return $false }
}
# END TALOS UNINSTALL PROCESS SELECTION


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
    $snapshot = @(Get-CimInstance Win32_Process -ErrorAction Stop)
    $processes = @(Select-TalosUninstallProcesses $snapshot $PID $InstallDir)
    if ($processes.Count -gt 0) {
        foreach ($process in $processes) {
            $boundProcess = $null
            try {
                if (-not $PSCmdlet.ShouldProcess("Process $($process.ProcessId) ($($process.Name))", "Stop-Process")) {
                    continue
                }
                # Recheck PID/start/path/argv after the confirmation boundary.
                $fresh = @(Get-CimInstance Win32_Process -Filter ("ProcessId={0}" -f [uint32]$process.ProcessId) -ErrorAction Stop)
                if ($fresh.Count -ne 1 -or -not (Test-TalosUninstallSameIdentity $process $fresh[0]) -or
                    -not (Test-TalosUninstallOwnedRecord $fresh[0] (ConvertTo-TalosUninstallPath $InstallDir))) {
                    Write-Warn2 ("Skipping PID {0}: process identity changed or is unavailable." -f $process.ProcessId)
                    continue
                }
                # Holding the process handle avoids resolving a recycled PID at Stop-Process.
                $boundProcess = [Diagnostics.Process]::GetProcessById([int]$process.ProcessId)
                $null = $boundProcess.Handle
                if (-not (Test-TalosUninstallLiveIdentity $fresh[0] $boundProcess.StartTime $boundProcess.MainModule.FileName)) {
                    Write-Warn2 ("Skipping PID {0}: live process binding failed." -f $process.ProcessId)
                    continue
                }
                Write-Info ("Stopping PID {0}: {1}" -f $process.ProcessId, $process.Name)
                Stop-Process -InputObject $boundProcess -Force -ErrorAction Stop
            } catch {
                Write-Warn2 ("Could not authenticate or stop PID {0}: {1}" -f $process.ProcessId, $_.Exception.Message)
            } finally {
                if ($null -ne $boundProcess) { $boundProcess.Dispose() }
            }
        }
    } else {
        Write-Info "No authenticated install-owned processes found; close other sessions manually if files remain locked."
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
