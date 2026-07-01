# ticket-aging.ps1 - read-only open-ticket queue health report (T748).
#
# Lists open tickets sorted by last-modified age with their ID, priority
# token, and first body Status: line, so stale gates surface instead of
# silently aging (T280 sat open for weeks with no signal).
#
#   pwsh scripts/ticket-aging.ps1            # full queue
#   pwsh scripts/ticket-aging.ps1 -Stale 14  # only tickets untouched >= 14 days

[CmdletBinding()]
param(
    [int]$Stale = 0,
    [string]$OpenDir = "work-cycle-docs/tickets/open"
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $OpenDir)) { throw "Open ticket directory not found: $OpenDir" }

$now = Get-Date
$rows = Get-ChildItem -LiteralPath $OpenDir -Filter *.md -File |
    Where-Object { $_.Name -ne 'README.md' } |
    ForEach-Object {
        $m = [regex]::Match($_.Name, '^\[T(\d+)-open-([a-z-]+)\]')
        $statusLine = (Select-String -LiteralPath $_.FullName -Pattern '^Status:' |
            Select-Object -First 1).Line
        [pscustomobject]@{
            Id       = if ($m.Success) { [int]$m.Groups[1].Value } else { 0 }
            Priority = if ($m.Success) { $m.Groups[2].Value } else { '?' }
            AgeDays  = [int]($now - $_.LastWriteTime).TotalDays
            Status   = if ($statusLine) { $statusLine.Trim() } else { '(no Status: line)' }
            File     = $_.Name
        }
    } |
    Where-Object { $_.AgeDays -ge $Stale } |
    Sort-Object -Property AgeDays -Descending

if (-not $rows) {
    Write-Host "No open tickets match (stale >= $Stale days)."
    exit 0
}
$rows | Format-Table -AutoSize -Wrap Id, Priority, AgeDays, Status
Write-Host "$(@($rows).Count) open ticket(s) listed (stale >= $Stale days)."
