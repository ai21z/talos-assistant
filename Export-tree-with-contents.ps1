<#
  Export-tree-with-contents.ps1
  Windows PowerShell 5.x–compatible tree exporter with optional embedded file contents.

  Usage (examples):
    $noisy = @('.git','.gradle','.idea','build','tmp','reports','generated','classes','node_modules')
    .\Export-tree-with-contents.ps1 `
      -ExcludeDirs $noisy `
      -ExcludeDirPatterns '**\target\**','**\bin\**' `
      -IncludeFiles -IncludeContents `
      -OnlyExtensions '.java','.md','.yml','.yaml','.json','.kts','.gradle','.xml','.properties','.ps1','.bat','.cmd','.txt' `
      -MaxDepth 6 -MaxBytesPerFile 65536 -Unicode

#>

param(
  [string]$Root = (Get-Location).Path,
  [string]$Output = "$env:USERPROFILE\Desktop\folder-tree.txt",

  # Exclusions by exact name (case-insensitive) — directory/file "base names"
  [string[]]$ExcludeDirs = @(
    '.git','.gradle','.idea','build','out','dist','node_modules',
    'classes','generated','reports','tmp','.m2','.venv','target','bin','.svn'
  ),
  [string[]]$ExcludeFiles = @('Thumbs.db','.DS_Store'),

  # Exclusions by wildcard pattern (case-insensitive)
  # Patterns are matched against RELATIVE paths from $Root (e.g., '**\target\**', 'docs\**', '*.log')
  [string[]]$ExcludeDirPatterns = @(),
  [string[]]$ExcludeFilePatterns = @(),

  # What to show
  [switch]$IncludeFiles,            # include files in the tree
  [switch]$IncludeContents,         # embed file contents under files
  [string[]]$OnlyExtensions = @(),  # e.g. ".java",".md" (empty = all)

  # Limits
  [int]$MaxDepth = [int]::MaxValue,         # tree depth cap (root=level 0)
  [int]$MaxItemsPerDirectory = 0,           # 0 = unlimited; otherwise show first N and summarize the rest
  [int]$MaxBytesPerFile = 200000,           # per-file content cap (≈200 KB)
  [long]$MaxTotalBytes = 5000000,           # overall content cap (≈5 MB)

  # Binary handling
  [switch]$SkipBinary = $true,              # skip binaries by default
  [switch]$DumpBinaryAsHex,                 # if set, prints first MaxBytesPerFile as hex

  # Branch style
  [switch]$Unicode,                         # pretty ├── pipes; otherwise ASCII

  # Traversal
  [switch]$FollowJunctions                  # by default, skip reparse points to avoid loops
)

# --- Branch glyphs (ASCII by default) ---
$branch = '+--- '; $last = '\--- '; $pipe = '|   '; $space = '    '
if ($Unicode) {
  $branch = [string]([char]0x251C) + [char]0x2500 + [char]0x2500 + ' '
  $last   = [string]([char]0x2514) + [char]0x2500 + [char]0x2500 + ' '
  $pipe   = [string]([char]0x2502) + '   '
}

# --- Globals / helpers ---
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$sw = New-Object System.IO.StreamWriter($Output, $false, $utf8NoBom)
$Script:TotalBytesEmitted = 0
$Script:RootFull = (Resolve-Path -LiteralPath $Root).Path
$Script:RootLen = $Script:RootFull.Length

function Normalize-Ext([string]$ext) {
  if ([string]::IsNullOrWhiteSpace($ext)) { return '' }
  $e = $ext.Trim()
  if ($e.Length -gt 0 -and $e[0] -ne '.') { $e = '.' + $e }
  return $e.ToLowerInvariant()
}

# Normalize OnlyExtensions once
if ($OnlyExtensions.Count -gt 0) {
  for ($i=0; $i -lt $OnlyExtensions.Count; $i++) { $OnlyExtensions[$i] = Normalize-Ext $OnlyExtensions[$i] }
}

function To-Relative([string]$full) {
  if ($full -like "$Script:RootFull*") { return $full.Substring($Script:RootLen).TrimStart('\') }
  return $full
}

function Matches-AnyPattern([string]$relPath, [string[]]$patterns) {
  if (-not $patterns -or $patterns.Count -eq 0) { return $false }
  $p = $relPath -replace '/', '\'
  foreach ($pat in $patterns) {
    if ([string]::IsNullOrWhiteSpace($pat)) { continue }
    $w = $pat.Replace('/', '\')
    # Case-insensitive -like match
    if ($p -like $w) { return $true }
  }
  return $false
}

function Name-In([string]$name, [string[]]$list) {
  foreach ($x in $list) { if ($name.Equals($x, [System.StringComparison]::OrdinalIgnoreCase)) { return $true } }
  return $false
}

function Should-SkipDir([System.IO.DirectoryInfo]$d) {
  if (-not $d) { return $true }
  if ((-not $FollowJunctions) -and ($d.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { return $true }
  if (Name-In $d.Name $ExcludeDirs) { return $true }
  $rel = To-Relative $d.FullName
  if (Matches-AnyPattern $rel $ExcludeDirPatterns) { return $true }
  return $false
}

function Should-ShowFile([System.IO.FileInfo]$f) {
  if (-not $f) { return $false }
  if (Name-In $f.Name $ExcludeFiles) { return $false }
  $rel = To-Relative $f.FullName
  if (Matches-AnyPattern $rel $ExcludeFilePatterns) { return $false }
  if ($OnlyExtensions.Count -gt 0) {
    $ext = Normalize-Ext $f.Extension
    if ($OnlyExtensions -notcontains $ext) { return $false }
  }
  return $true
}

function Test-IsBinary([byte[]]$bytes) {
  if (-not $bytes) { return $false }
  $n = [Math]::Min(8000, $bytes.Length)
  for ($i = 0; $i -lt $n; $i++) {
    if ($bytes[$i] -eq 0) { return $true }
  }
  return $false
}

function LongPath([string]$path) {
  if ($path.StartsWith('\\?\')) { return $path }
  if ($path.StartsWith('\\')) { return '\\?\UNC\' + $path.TrimStart('\') }
  return '\\?\'+$path
}

function Get-FileBytes([string]$path, [int]$limit) {
  try {
    $lp = LongPath $path
    $fs = [System.IO.File]::Open($lp, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
  } catch {
    return $null
  }
  try {
    $len = [Math]::Min([int64]$fs.Length, [int64]$limit)
    $buf = New-Object byte[] $len
    [void]$fs.Read($buf, 0, $len)
    return ,$buf
  } catch {
    return $null
  } finally { $fs.Dispose() }
}

function Write-ContentUnder([System.IO.FileInfo]$file, [string]$contentPrefix) {
  if ($Script:TotalBytesEmitted -ge $MaxTotalBytes) {
    $sw.WriteLine($contentPrefix + '    [content skipped: MaxTotalBytes reached]')
    return
  }

  $bytes = Get-FileBytes -path $file.FullName -limit $MaxBytesPerFile
  if (-not $bytes) {
    $sw.WriteLine($contentPrefix + '    [unreadable]')
    return
  }

  $isBin = Test-IsBinary $bytes
  if ($isBin -and $SkipBinary -and -not $DumpBinaryAsHex) {
    $sw.WriteLine($contentPrefix + "    [binary skipped: $($file.Extension), $($bytes.Length) bytes]")
    return
  }

  if ($isBin -and $DumpBinaryAsHex) {
    $sw.WriteLine($contentPrefix + "    [hex dump, $($bytes.Length) bytes]")
    # 32 bytes per line -> ~95 chars incl spaces
    $line = New-Object System.Text.StringBuilder
    $count = 0
    foreach ($b in $bytes) {
      [void]$line.AppendFormat('{0:X2} ', $b)
      $count++
      if ($count -ge 32) {
        $sw.WriteLine($contentPrefix + '    ' + $line.ToString().TrimEnd())
        $line.Clear() | Out-Null
        $count = 0
      }
    }
    if ($count -gt 0) {
      $sw.WriteLine($contentPrefix + '    ' + $line.ToString().TrimEnd())
    }
    $Script:TotalBytesEmitted += $bytes.Length
    return
  }

  # Text path
  $ext = Normalize-Ext $file.Extension
  $lang = switch ($ext) {
    '.ps1' {'powershell'} '.bat' {'bat'} '.cmd' {'bat'} '.java' {'java'}
    '.kt' {'kotlin'} '.kts' {'kotlin'} '.js' {'javascript'} '.ts' {'typescript'}
    '.json' {'json'} '.yml' {'yaml'} '.yaml' {'yaml'} '.xml' {'xml'}
    '.html' {'html'} '.css' {'css'} '.md' {'markdown'} '.sql' {'sql'}
    '.py' {'python'} '.rb' {'ruby'} '.go' {'go'} '.rs' {'rust'} '.c' {'c'}
    '.h' {'c'} '.cpp' {'cpp'} '.hpp' {'cpp'}
    default {'text'}
  }

  $sw.WriteLine($contentPrefix + "    [begin $lang, $($bytes.Length) bytes]")

  # Try UTF-8 first; fall back to default code page
  $text = $null
  try { $text = [System.Text.Encoding]::UTF8.GetString($bytes) }
  catch { $text = $null }
  if ($null -eq $text) {
    try { $text = [System.Text.Encoding]::Default.GetString($bytes) }
    catch { $text = '' }
  }

  # Split lines on CRLF or LF
  $lines = $text -split "`r?`n"
  foreach ($l in $lines) { $sw.WriteLine($contentPrefix + '    ' + $l) }

  $sw.WriteLine($contentPrefix + "    [end $lang]")
  $Script:TotalBytesEmitted += $bytes.Length
}

function Write-Tree([string]$dir, [string]$prefix, [int]$depth) {
  if ($depth -gt $MaxDepth) { return }

  # Collect children (dirs, then files), all sorted by Name
  $dirs = @(Get-ChildItem -LiteralPath $dir -Directory -Force -ErrorAction SilentlyContinue |
           Where-Object { -not (Should-SkipDir $_) } | Sort-Object Name)

  $files = @()
  if ($IncludeFiles) {
    $files = @(Get-ChildItem -LiteralPath $dir -File -Force -ErrorAction SilentlyContinue |
             Where-Object { Should-ShowFile $_ } | Sort-Object Name)
  }

  $items = @()
  if ($dirs)  { $items += @($dirs) }
  if ($files) { $items += @($files) }

  $shown = $items.Count
  $moreCount = 0

  if ($MaxItemsPerDirectory -gt 0 -and $items.Count -gt $MaxItemsPerDirectory) {
    $shown = $MaxItemsPerDirectory
    $moreCount = $items.Count - $MaxItemsPerDirectory
  }

  for ($i = 0; $i -lt $shown; $i++) {
    $isLast = ($i -eq $shown - 1)
    $conn   = if ($isLast) { $last } else { $branch }
    $name   = $items[$i].Name
    $sw.WriteLine($prefix + $conn + $name)

    if ($items[$i].PSIsContainer) {
      $nextPrefix = if ($isLast) { $prefix + $space } else { $prefix + $pipe }
      Write-Tree -dir $items[$i].FullName -prefix $nextPrefix -depth ($depth + 1)
    } else {
      if ($IncludeContents) {
        $contentPrefix = if ($isLast) { $prefix + $space } else { $prefix + $pipe }
        Write-ContentUnder -file $items[$i] -contentPrefix $contentPrefix
      }
    }
  }

  if ($moreCount -gt 0) {
    # Indicate there were more items not printed
    $sw.WriteLine($prefix + $branch + "[… $moreCount more]")
  }
}

# --- header + run ---
try {
  $sw.WriteLine($Script:RootFull)
  Write-Tree -dir $Script:RootFull -prefix '' -depth 1
} finally {
  $sw.Dispose()
}
Write-Host "Wrote tree to $Output"
