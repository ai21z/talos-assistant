# Talos Windows Installer
# Installs Talos to your system by:
# - Copying distribution files to %LOCALAPPDATA%\Programs\talos
# - Adding Talos bin directory to User PATH
# - Broadcasting PATH changes to other applications
# - No admin privileges required (user-level installation only)

param(
    [switch]$Force,
    [switch]$Help
)

if ($Help) {
    Write-Host "Talos Windows Installer"
    Write-Host ""
    Write-Host "Usage: pwsh install-windows.ps1 [-Force]"
    Write-Host ""
    Write-Host "Options:"
    Write-Host "  -Force    Reinstall even if already installed"
    Write-Host "  -Help     Show this help message"
    exit 0
}

$ErrorActionPreference = "Stop"

# Check if Talos distribution exists
$sourceDir = Join-Path $PSScriptRoot "..\build\install\talos"
if (-not (Test-Path $sourceDir)) {
    Write-Error "Talos distribution not found at $sourceDir"
    Write-Host "Please run: ./gradlew clean installDist"
    exit 1
}

# Target installation directory
$installDir = Join-Path $env:LOCALAPPDATA "Programs\talos"
$binDir = Join-Path $installDir "bin"

# Check if already installed
if ((Test-Path $installDir) -and -not $Force) {
    Write-Host "Talos is already installed at $installDir"
    Write-Host "Use -Force to reinstall or run: talos --version"
    exit 0
}

Write-Host "Installing Talos to $installDir..."

# Remove existing installation if present
if (Test-Path $installDir) {
    Write-Host "Removing existing installation..."
    Remove-Item -Path $installDir -Recurse -Force
}

# Copy distribution
Write-Host "Copying files..."
Copy-Item -Path $sourceDir -Destination $installDir -Recurse -Force

# Check if bin directory is already in PATH
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$pathEntries = $userPath -split ';' | ForEach-Object { $_.Trim() }

if ($binDir -notin $pathEntries) {
    Write-Host "Adding $binDir to user PATH..."
    $newPath = $userPath + ';' + $binDir
    [Environment]::SetEnvironmentVariable('Path', $newPath, 'User')

    # Notify system of environment variable change
    Add-Type -TypeDefinition @'
        using System;
        using System.Runtime.InteropServices;
        public class Win32 {
            [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
            public static extern IntPtr SendMessageTimeout(
                IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam,
                uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
        }
'@

    $HWND_BROADCAST = [IntPtr]0xffff
    $WM_SETTINGCHANGE = 0x1a
    $result = [UIntPtr]::Zero
    [Win32]::SendMessageTimeout($HWND_BROADCAST, $WM_SETTINGCHANGE, [UIntPtr]::Zero, "Environment", 2, 5000, [ref]$result) | Out-Null

    Write-Host "PATH updated successfully."
} else {
    Write-Host "$binDir is already in PATH."
}

Write-Host ""
Write-Host "✅ Talos installed successfully!"
Write-Host ""
Write-Host "To verify installation:"
Write-Host "  1. Open a new PowerShell/Command Prompt window"
Write-Host "  2. Run: talos --version"
Write-Host ""
Write-Host "To start using Talos:"
Write-Host "  talos                    # Interactive mode"
Write-Host "  talos status             # Check workspace status"
Write-Host "  talos rag-index          # Index current directory"
Write-Host "  talos rag-ask \"question\" # Ask about your code"
