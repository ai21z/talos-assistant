<#
.SYNOPSIS
Installs the Talos public Windows app-image release for the current user.

.DESCRIPTION
This is the public bootstrap fallback for signed GitHub Release artifacts. It
installs Talos only. Local model configuration remains a separate
`talos setup models` step after installation.
#>
[CmdletBinding()]
param(
    [string]$Repository = "ai21z/talos-assistant",
    [string]$Version = "latest",
    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA "Programs\Talos"),
    [switch]$AllowUnsigned,
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ($env:OS -ne "Windows_NT") {
    throw "Talos public beta installer supports Windows x64 only."
}

if (-not [Environment]::Is64BitOperatingSystem) {
    throw "Talos public beta installer supports Windows x64 only."
}

if ($PSCommandPath -and -not $AllowUnsigned) {
    $signature = Get-AuthenticodeSignature -FilePath $PSCommandPath
    if ($signature.Status -ne "Valid") {
        throw "Installer signature is $($signature.Status). Download the signed release script or rerun with -AllowUnsigned for local development/manual QA only."
    }
}

function Get-GitHubRelease {
    param(
        [Parameter(Mandatory = $true)][string]$Repo,
        [Parameter(Mandatory = $true)][string]$ReleaseVersion
    )

    $headers = @{ "User-Agent" = "talos-installer" }
    if ($ReleaseVersion -eq "latest") {
        return Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases/latest"
    }

    $tag = $ReleaseVersion
    if (-not $tag.StartsWith("v", [System.StringComparison]::OrdinalIgnoreCase)) {
        $tag = "v$tag"
    }
    return Invoke-RestMethod -Headers $headers -Uri "https://api.github.com/repos/$Repo/releases/tags/$tag"
}

function Find-ReleaseAsset {
    param(
        [Parameter(Mandatory = $true)]$Release,
        [Parameter(Mandatory = $true)][string]$AssetName
    )

    $asset = $Release.assets | Where-Object { $_.name -eq $AssetName } | Select-Object -First 1
    if (-not $asset) {
        throw "Release asset not found: $AssetName"
    }
    return $asset
}

function Read-ExpectedSha256 {
    param(
        [Parameter(Mandatory = $true)][string]$ChecksumFile,
        [Parameter(Mandatory = $true)][string]$FileName
    )

    $escaped = [Regex]::Escape($FileName)
    foreach ($line in Get-Content -LiteralPath $ChecksumFile) {
        if ($line -match "^([A-Fa-f0-9]{64})\s+\*?$escaped$") {
            return $matches[1].ToLowerInvariant()
        }
    }
    throw "No SHA256 entry for $FileName in checksums.txt"
}

function Assert-Sha256 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Expected
    )

    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "Checksum mismatch for $Path. Expected $Expected, got $actual."
    }
}

function Add-UserPathEntry {
    param([Parameter(Mandatory = $true)][string]$PathEntry)

    $current = [Environment]::GetEnvironmentVariable("Path", "User")
    $parts = @()
    if (-not [string]::IsNullOrWhiteSpace($current)) {
        $parts = $current -split ";" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    }

    $alreadyPresent = $false
    foreach ($part in $parts) {
        if ([string]::Equals($part.TrimEnd([char]'\'), $PathEntry.TrimEnd([char]'\'), [System.StringComparison]::OrdinalIgnoreCase)) {
            $alreadyPresent = $true
            break
        }
    }

    if (-not $alreadyPresent) {
        $updated = @($parts + $PathEntry) -join ";"
        [Environment]::SetEnvironmentVariable("Path", $updated, "User")
        $env:Path = "$env:Path;$PathEntry"
        Publish-EnvironmentChange
    }
}

function Publish-EnvironmentChange {
    Add-Type -TypeDefinition @'
        using System;
        using System.Runtime.InteropServices;
        public class TalosInstallerWin32 {
            [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
            public static extern IntPtr SendMessageTimeout(
                IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
                uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
        }
'@

    $HWND_BROADCAST = [IntPtr]0xffff
    $WM_SETTINGCHANGE = 0x1a
    $result = [UIntPtr]::Zero
    [TalosInstallerWin32]::SendMessageTimeout($HWND_BROADCAST, $WM_SETTINGCHANGE, [UIntPtr]::Zero, "Environment", 2, 5000, [ref]$result) | Out-Null
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("talos-install-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null

try {
    $release = Get-GitHubRelease -Repo $Repository -ReleaseVersion $Version
    $releaseVersion = [string]$release.tag_name
    if ($releaseVersion.StartsWith("v", [System.StringComparison]::OrdinalIgnoreCase)) {
        $releaseVersion = $releaseVersion.Substring(1)
    }

    $zipName = "talos-$releaseVersion-windows-x64-app.zip"
    $checksumName = "checksums.txt"
    $zipAsset = Find-ReleaseAsset -Release $release -AssetName $zipName
    $checksumAsset = Find-ReleaseAsset -Release $release -AssetName $checksumName

    $zipPath = Join-Path $tempRoot $zipName
    $checksumPath = Join-Path $tempRoot $checksumName

    Invoke-WebRequest -Uri $zipAsset.browser_download_url -OutFile $zipPath
    Invoke-WebRequest -Uri $checksumAsset.browser_download_url -OutFile $checksumPath

    $expectedZipHash = Read-ExpectedSha256 -ChecksumFile $checksumPath -FileName $zipName
    Assert-Sha256 -Path $zipPath -Expected $expectedZipHash

    $extractRoot = Join-Path $tempRoot "extract"
    Expand-Archive -LiteralPath $zipPath -DestinationPath $extractRoot

    $launcher = Get-ChildItem -Path $extractRoot -Filter "Talos.exe" -Recurse | Select-Object -First 1
    if (-not $launcher) {
        throw "Talos.exe was not found in $zipName"
    }

    $appSource = $launcher.Directory.FullName
    $appTarget = Join-Path $InstallRoot "app"
    $binTarget = Join-Path $InstallRoot "bin"

    if (Test-Path -LiteralPath $InstallRoot) {
        if (-not $Force) {
            throw "Install target already exists: $InstallRoot. Rerun with -Force to replace it."
        }
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force
    }

    New-Item -ItemType Directory -Path $appTarget, $binTarget | Out-Null
    Copy-Item -Path (Join-Path $appSource "*") -Destination $appTarget -Recurse -Force

    $shim = Join-Path $binTarget "talos.cmd"
    $shimLines = @(
        "@echo off",
        'setlocal',
        'set "TALOS_EXE=%~dp0..\app\Talos.exe"',
        '"%TALOS_EXE%" %*'
    )
    Set-Content -LiteralPath $shim -Value $shimLines -Encoding ASCII

    Add-UserPathEntry -PathEntry $binTarget

    Write-Host "Installed Talos $releaseVersion to $InstallRoot"
    Write-Host "Open a new PowerShell window, then run:"
    Write-Host "  talos --version"
    Write-Host "  talos setup models"
    Write-Host "  talos status --verbose"
    Write-Host "  talos"
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force
    }
}
