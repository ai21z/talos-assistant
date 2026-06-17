# Talos PreToolUse guard for Bash / PowerShell commands.
# Reads the hook JSON from stdin and enforces three hard invariants:
#   1. No 'git push' unless the owner consciously opts in via TALOS_ALLOW_PUSH=1.
#   2. No shell access to ~/.talos/secrets.
#   3. No write/delete to ~/.talos/config.yaml without explicit owner action.
# Blocking is exit code 2 (stderr is shown to Claude and the tool call is stopped).
# It FAILS OPEN (exit 0) on any parse/error path so a bug can never brick the CLI.
$ErrorActionPreference = 'SilentlyContinue'
try {
  $raw = [Console]::In.ReadToEnd()
  if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }

  $obj = $raw | ConvertFrom-Json
  $cmd = ''
  if ($obj.tool_input -and $obj.tool_input.command) { $cmd = [string]$obj.tool_input.command }
  if ([string]::IsNullOrWhiteSpace($cmd)) { exit 0 }

  $norm = ($cmd.ToLower()) -replace '\\', '/'

  # 1) git push gate. Match 'git push' only as an actual command (start of line or
  # after a shell separator), NOT the words appearing inside a quoted string or commit
  # message, which was a real false-positive (it blocked a 'git commit' whose message
  # mentioned git push).
  if ($norm -match '(^\s*|[;&|(\n]\s*)git\s+push') {
    if ($env:TALOS_ALLOW_PUSH -ne '1') {
      [Console]::Error.WriteLine("Talos guard: 'git push' is blocked by project policy (no pushes to origin unless the owner says so). If the owner authorized this push, set TALOS_ALLOW_PUSH=1 in the environment and retry.")
      exit 2
    }
  }

  # 2) secret store is off-limits to shell commands
  if ($norm -match '\.talos/secrets') {
    [Console]::Error.WriteLine("Talos guard: shell access to ~/.talos/secrets is blocked by project policy. Do not read or modify the secret store through commands.")
    exit 2
  }

  # 3) writes/deletes to ~/.talos/config.yaml
  if ($norm -match '\.talos/config\.yaml') {
    $writeish = @('>', 'set-content', 'out-file', 'add-content', 'clear-content', 'remove-item', 'new-item', 'move-item', 'copy-item', 'del ', 'rm ', 'mv ', 'cp ', 'tee')
    foreach ($w in $writeish) {
      if ($norm.Contains($w)) {
        [Console]::Error.WriteLine("Talos guard: modifying ~/.talos/config.yaml is blocked without explicit owner confirmation of the intended contents. Make the change manually outside Talos if the owner confirmed it.")
        exit 2
      }
    }
  }

  exit 0
} catch {
  exit 0
}
