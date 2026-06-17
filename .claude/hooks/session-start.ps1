# Talos SessionStart hook.
# Prints a short, LIVE repo-state reminder that complements the static CLAUDE.md.
# stdout is added to the session context. Always exits 0 (never blocks a session).
$ErrorActionPreference = 'SilentlyContinue'
try {
  $root = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR } else { (Get-Location).Path }

  $ver = 'unknown'
  $gp = Join-Path $root 'gradle.properties'
  if (Test-Path -LiteralPath $gp) {
    foreach ($line in Get-Content -LiteralPath $gp) {
      if ($line -match '^\s*talosVersion\s*=\s*(.+?)\s*$') { $ver = $Matches[1]; break }
    }
  }

  Push-Location -LiteralPath $root
  $branch = (& git rev-parse --abbrev-ref HEAD 2>$null)
  $head   = (& git rev-parse --short HEAD 2>$null)
  $dirty  = ((& git status --porcelain 2>$null | Measure-Object -Line).Lines)
  Pop-Location

  Write-Output "Talos work-cycle active. AGENTS.md is the authority; use the talos-work-cycle skill unless told the task is outside the cycle. branch=$branch head=$head talosVersion=$ver uncommitted=$dirty. Inner loop = focused tests, no version bump per edit; candidate loop only when cutting versioned evidence. The final answer is the least-trusted artifact: judge from code, tests, traces, approvals, and diffs."
} catch { }
exit 0
