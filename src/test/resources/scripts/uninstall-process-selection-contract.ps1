param([Parameter(Mandatory = $true)][string]$PublicScript,
      [Parameter(Mandatory = $true)][string]$LegacyScript)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Only these pure helper definitions are loaded. Neither uninstall entry point,
# CIM inspection nor process termination is invoked by this fixture.
$helperNames = @('ConvertTo-TalosUninstallPath', 'Split-TalosUninstallArguments',
    'Test-TalosUninstallOwnedRecord', 'Select-TalosUninstallProcesses',
    'Test-TalosUninstallSameIdentity', 'Test-TalosUninstallLiveIdentity')
$previousHelpers = $null
foreach ($sourcePath in @($PublicScript, $LegacyScript)) {
    $parserTokens = $null
    $parserErrors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($sourcePath, [ref]$parserTokens, [ref]$parserErrors)
    if (@($parserErrors).Count -ne 0) { throw 'Uninstaller parser errors' }
    $helpers = @($ast.FindAll({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -in $helperNames
    }, $true))
    if ($helpers.Count -ne $helperNames.Count -or @($helpers.Name | Select-Object -Unique).Count -ne $helperNames.Count) {
        throw 'Missing or duplicate selector helper'
    }
    foreach ($helper in $helpers) {
        foreach ($command in $helper.FindAll({ param($node)
            $node -is [Management.Automation.Language.CommandAst]
        }, $true)) {
            if ($command.GetCommandName() -notin $helperNames -or
                $command.InvocationOperator -ne [Management.Automation.Language.TokenKind]::Unknown) {
                throw 'Selector contains a non-helper command or invocation operator'
            }
        }
    }
    $helperSource = ($helpers | ForEach-Object { $_.Extent.Text.Replace("`r`n", "`n") }) -join "`n"
    if ($null -ne $previousHelpers -and $previousHelpers -cne $helperSource) { throw 'Standalone selector copies drifted' }
    $previousHelpers = $helperSource
    . ([scriptblock]::Create($helperSource))

    $root = 'C:\Fixture Talos'
    $java = 'C:\Fixture JDK\bin\java.exe'
    $started = [datetime]'2026-01-01T00:00:00.123456Z'
    function New-Record([uint32]$Id, [uint32]$Parent, [string]$Executable, [string]$CommandLine) {
        [pscustomobject]@{ ProcessId = $Id; ParentProcessId = $Parent; CreationDate = $started;
            ExecutablePath = $Executable; Name = [IO.Path]::GetFileName($Executable); CommandLine = $CommandLine }
    }
    $self = New-Record 100 90 ($root + '\app\Talos.exe') 'uninstaller fixture'
    $parent = New-Record 90 80 ($root + '\Talos.exe') 'parent fixture'
    $grandparent = New-Record 80 0 ($root + '\app\Talos.exe') 'ancestor fixture'
    $ancestors = @($self, $parent, $grandparent)
    $native = New-Record 300 1 ($root + '\app\Talos.exe') '"C:\Fixture Talos\app\Talos.exe"'
    $rawImage = New-Record 310 1 ($root + '\Talos.exe') '"C:\Fixture Talos\Talos.exe"'
    $legacy = New-Record 320 1 $java ('"' + $java + '" -Xmx256m -Dfile.encoding=UTF-8 -classpath "' + $root + '\lib\talos.jar;' + $root + '\lib\dependency.jar" dev.talos.app.Main status')
    $jar = New-Record 330 1 $java ('"' + $java + '" -jar "' + $root + '\lib\talos.jar" status')
    $positive = @($native, $rawImage, $legacy, $jar)
    $ids = @(Select-TalosUninstallProcesses ($ancestors + $positive) 100 $root | ForEach-Object ProcessId)
    if (($ids -join ',') -cne '300,310,320,330') { throw ('Owned/ancestor selection mismatch: ' + ($ids -join ',')) }

    $rejected = @(
        (New-Record 400 1 'C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe' ('-File uninstall-talos.ps1 -InstallDir "' + $root + '" -Quiet talos.jar dev.talos.app.Main')),
        (New-Record 401 1 ($root + '-other\app\Talos.exe') 'Talos.exe'),
        (New-Record 402 1 'C:\Other\Talos.exe' ('--description "' + $root + '"')),
        (New-Record 403 1 $java ('"' + $java + '" -cp "' + $root + '\lib\talos.jar" unrelated.Main dev.talos.app.Main')),
        (New-Record 404 1 $java ('"' + $java + '" -cp "' + $root + '\lib\talos.jar;C:\Other\override.jar" dev.talos.app.Main')),
        (New-Record 405 1 $java ('"' + $java + '" -jar C:\Other\talos.jar "' + $root + '"')),
        (New-Record 406 1 $java ('"' + $java + '" @arguments.txt -cp "' + $root + '\lib\talos.jar" dev.talos.app.Main')),
        (New-Record 407 1 $java ('"' + $java + '" -cp "' + $root + '\lib\dependency.jar" dev.talos.app.Main')),
        (New-Record 408 1 $java ('"' + $java + '" -cp "' + $root + '\lib\talos.jar" Dev.Talos.App.Main')),
        (New-Record 409 1 $java ('"' + $java + '" -cp "' + $root + '\lib\talos.jar" --module=unrelated/app dev.talos.app.Main')),
        (New-Record 410 1 $java ('"' + $java + '" -cp "' + $root + '\lib\talos.jar dev.talos.app.Main')),
        (New-Record 411 1 '' ('talos.jar ' + $root)),
        (New-Record 412 1 $java ('"' + $java + '" -cp "' + $root + '\lib\..\..\Other\talos.jar" dev.talos.app.Main')),
        (New-Record 413 1 $java ('"' + $java + '" -cp "' + $root + '\lib\*" dev.talos.app.Main')),
        (New-Record 414 1 $java ('"' + $java + '" -cp "' + $root + '\lib\talos.jar" -cp C:\Other\talos.jar dev.talos.app.Main')),
        (New-Record 415 1 $java ('java.exe -cp "' + $root + '\lib\talos.jar" dev.talos.app.Main'))
    )
    foreach ($record in $rejected) {
        if (@(Select-TalosUninstallProcesses ($ancestors + @($record)) 100 $root).Count -ne 0) {
            throw ('Unrelated/ambiguous process selected: ' + $record.ProcessId)
        }
    }
    if (@(Select-TalosUninstallProcesses $positive 100 $root).Count -ne 0) { throw 'Missing self identity accepted' }
    if (@(Select-TalosUninstallProcesses ($ancestors + @($native, $native)) 100 $root).Count -ne 0) { throw 'Duplicate PID accepted' }
    $parent.ParentProcessId = 100
    if (@(Select-TalosUninstallProcesses ($ancestors + $positive) 100 $root).Count -ne 0) { throw 'Cyclic ancestry accepted' }
    $parent.ParentProcessId = 80
    if (@(Select-TalosUninstallProcesses ($ancestors + $positive) 100 'relative\Talos').Count -ne 0) { throw 'Unresolved root accepted' }
    $missingStart = New-Record 450 1 ($root + '\app\Talos.exe') 'Talos.exe'
    $missingStart.CreationDate = $null
    if (@(Select-TalosUninstallProcesses ($ancestors + @($missingStart)) 100 $root).Count -ne 0) { throw 'Missing creation identity accepted' }
    if (@(Select-TalosUninstallProcesses ($ancestors + @($native)) 100 $root.ToUpperInvariant()).Count -ne 1) { throw 'Windows path case rejected' }
    if (-not (Test-TalosUninstallSameIdentity $native $native)) { throw 'Same identity rejected' }
    foreach ($field in @('ProcessId', 'ParentProcessId', 'CreationDate', 'ExecutablePath', 'CommandLine')) {
        $changed = New-Record 300 1 ($root + '\app\Talos.exe') $native.CommandLine
        $changed.$field = switch ($field) {
            'ProcessId' { 999 }
            'ParentProcessId' { 999 }
            'CreationDate' { $started.AddSeconds(1) }
            'ExecutablePath' { 'C:\Other\Talos.exe' }
            'CommandLine' { 'changed arguments' }
        }
        if (Test-TalosUninstallSameIdentity $native $changed) { throw ('Changed identity accepted: ' + $field) }
    }
    if (-not (Test-TalosUninstallLiveIdentity $native $started.AddTicks(9) $native.ExecutablePath)) { throw 'CIM precision binding rejected' }
    if (Test-TalosUninstallLiveIdentity $native $started.AddTicks(10) $native.ExecutablePath) { throw 'Changed live start accepted' }
    if (Test-TalosUninstallLiveIdentity $native $started 'C:\Other\Talos.exe') { throw 'Changed live path accepted' }
}
[Console]::Out.WriteLine('TALOS_UNINSTALL_SELECTOR_CONTRACT_PASS')
