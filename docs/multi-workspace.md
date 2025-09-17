# LOQ-J Multi-Workspace Guide

## What is Multi-Workspace?

LOQ-J allows you to work with multiple project directories simultaneously, keeping each project's search index and AI context completely separate. This means you can:

- Switch between different projects without mixing their data
- Ask questions specific to one project at a time  
- Maintain separate search indices for each workspace
- Keep AI conversations focused on the relevant codebase

## Installation & Setup

### Quick Install (Recommended)

**Windows PowerShell:**
```powershell
# Build the application first
.\gradlew clean installDist

# Run the installer script
pwsh tools/install-windows.ps1

# Open a NEW terminal window, then test:
loqj --version
```

**Linux/macOS:**
```bash
./gradlew clean installDist
bash tools/install-unix.sh
# Open new terminal  
loqj --version
```

After installation, `loqj` works from any directory!

### Uninstalling LOQ-J

**Windows PowerShell:**
```powershell
# Basic uninstall (keeps your workspace data)
pwsh tools/uninstall-windows.ps1

# Complete removal including all workspace data
pwsh tools/uninstall-windows.ps1 -Purge

# Silent uninstall for automation
pwsh tools/uninstall-windows.ps1 -Purge -Quiet

# Preview what would be removed without actually doing it
pwsh tools/uninstall-windows.ps1 -WhatIf
```

The uninstaller will:
- Remove LOQ-J from your system PATH
- Delete the installation directory (`%LOCALAPPDATA%\Programs\loqj`)
- Optionally remove workspace data (`~\.loqj`) when using `-Purge`
- Stop any running LOQ-J processes
- Require opening a new terminal to pick up PATH changes

**Linux/macOS:**
```bash
# Remove the symlink (if created during installation)
sudo rm /usr/local/bin/loqj

# Optionally remove workspace data
rm -rf ~/.loqj
```

### Manual Setup (Development/Testing)

If you prefer to run directly from the build directory without installing:

**Windows PowerShell:**
```powershell
# Build the application
.\gradlew clean installDist

# Navigate to the executable directory
cd build\install\loqj\bin

# Run commands using PowerShell syntax (note the .\ prefix):
.\loqj.bat --version
.\loqj.bat status --verbose
.\loqj.bat rag-index
```

**Linux/macOS:**
```bash
# Build the application
./gradlew clean installDist

# Run directly from build directory
./build/install/loqj/bin/loqj --version
```

## Basic Usage

### Check What's Currently Active
```bash
# See which workspace is active and its status
loqj status

# Get detailed information
loqj status --verbose
```

### Index Your First Workspace
```bash
# Index the current directory
loqj rag-index

# Index a specific project folder  
loqj rag-index --root "C:\path\to\your\project"
```

### Ask Questions About Your Code
```bash
# Ask about the current workspace
loqj rag-ask "What does this project do?"

# Ask about a specific workspace
loqj rag-ask --root "C:\path\to\project" "How does authentication work?"
```

### Interactive Mode with Dynamic Prompts

```bash
# Start REPL (shows banner and current mode)
loqj

# The prompt shows current mode: loqj@rag_ >
# Switch modes and watch the prompt update:
:mode ask
# Prompt becomes: loqj@ask_ >

:mode dev  
# Prompt becomes: loqj@dev_ >

# Start without banner for scripts
loqj run --no-logo
```

## Working with Multiple Projects

### Example: Managing Two Projects

Let's say you have a web app and a mobile app:

```bash
# Set up the web app workspace  
loqj rag-index --root "C:\projects\webapp"
loqj rag-ask --root "C:\projects\webapp" "What APIs are available?"

# Switch to mobile app workspace (completely separate context)
loqj rag-index --root "C:\projects\mobileapp"  
loqj rag-ask --root "C:\projects\mobileapp" "How is data stored locally?"

# Interactive mode for specific workspace
loqj run --root "C:\projects\webapp"
# Now in REPL with webapp context - all questions stay focused on webapp
```

Each workspace maintains its own:
- Search index (stored in `~/.loqj/indices/`)
- File analysis and context  
- AI conversation history

### Using Environment Variables

Set a default workspace to avoid typing `--root` every time:

**Windows PowerShell:**
```powershell
$env:LOQJ_WORKSPACE = "C:\projects\webapp"
$env:LOQJ_OLLAMA_MODEL = "qwen2.5:7b"

# Then just run:
loqj status
loqj rag-ask "What is this project about?"
loqj                    # Interactive mode for webapp
```

**Linux/macOS:**
```bash
export LOQJ_WORKSPACE=~/projects/webapp
export LOQJ_OLLAMA_MODEL=qwen2.5:7b

# Then just run:
loqj status
loqj rag-ask "What is this project about?"
loqj                    # Interactive mode for webapp
```

### How LOQ-J Chooses Your Workspace

LOQ-J picks your workspace in this order:
1. **`--root` flag** (if you specify it)
2. **`LOQJ_WORKSPACE` environment variable** (if set)  
3. **Current directory** (where you run the command)

## Advanced Features

### Version Information
```bash
# All these show the same version info:
loqj --version
loqj -v  
loqj version
```

## Troubleshooting

### Windows PowerShell Common Issues

**Problem:** `'loqj' is not recognized as the name of a cmdlet`
**Solution:** Use `.\loqj.bat` when running from the build directory, or install globally using the installer script.

**Problem:** `The process cannot access the file because it is being used by another process`
**Solution:** Close any running LOQ-J instances or terminals that might be using the application before rebuilding.

**Problem:** `'&&' is not a valid statement separator`
**Solution:** PowerShell doesn't use `&&` like bash. Use separate commands:
```powershell
# Instead of: cd path && command
cd path
command
```

**Problem:** `Unrecognized VM option 'UseTransparentHugePages'`
**Solution:** This has been fixed in the latest build. Rebuild with `.\gradlew clean installDist`

### General Issues

**Index not found:** Run `loqj rag-index` in your project directory first.

**Ollama connection failed:** Make sure Ollama is running (`ollama serve`) and the model is pulled (`ollama pull qwen2.5:7b`).

**Workspace confusion:** Use `loqj status --verbose` to see which workspace and configuration is active.

### Getting Help
```bash
# Show all available commands
loqj --help

# Get help for a specific command
loqj rag-index --help
loqj rag-ask --help
```
