[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$QualificationRoot,

    [Parameter(Mandatory = $true)]
    [string]$QualificationRunRoot,

    [Parameter(Mandatory = $true)]
    [string]$QualificationScenario,

    [Parameter(Mandatory = $false)]
    [string]$QualificationServerPort = ''
)

$ErrorActionPreference = 'Stop'
$RepositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$ResolvedQualificationRoot = (Resolve-Path -LiteralPath $QualificationRoot).Path
$ResolvedQualificationRunRoot = (Resolve-Path -LiteralPath $QualificationRunRoot).Path
$Gradle = Join-Path $RepositoryRoot 'gradlew.bat'
$GradleExitCode = $null

Push-Location -LiteralPath $RepositoryRoot
try {
    & $Gradle 'assertWritableModelQualificationCampaign' `
        "-PqualificationRoot=$ResolvedQualificationRoot" `
        '--no-daemon'
    if ($LASTEXITCODE -ne 0) {
        throw "Qualification campaign writable guard failed with exit code $LASTEXITCODE"
    }
    $ReconcileArguments = @(
        'reconcileModelQualificationRunPublication',
        "-PqualificationRoot=$ResolvedQualificationRoot",
        "-PqualificationRunRoot=$ResolvedQualificationRunRoot",
        "-PqualificationScenario=$QualificationScenario",
        '--no-daemon'
    )
    if (-not [string]::IsNullOrWhiteSpace($QualificationServerPort)) {
        $ReconcileArguments += "-PqualificationServerPort=$QualificationServerPort"
    }
    & $Gradle @ReconcileArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Qualification publication reconciliation failed with exit code $LASTEXITCODE"
    }
    & $Gradle 'finalizeModelQualificationResult' `
        "-PqualificationRoot=$ResolvedQualificationRoot" `
        "-PqualificationRunRoot=$ResolvedQualificationRunRoot" `
        "-PqualificationScenario=$QualificationScenario" `
        '--no-daemon'
    $GradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($GradleExitCode -ne 0) {
    throw "Java qualification result finalization failed with exit code $GradleExitCode"
}
