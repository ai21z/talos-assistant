# Developer setup

This page is for contributors building Talos from source. The normal loop is local and free. No private token is required for the normal build and test loop.

## Required tools

| Tool | Purpose |
|---|---|
| Java 21 | Compiles and runs the Gradle project. |
| Git | Clones the repository and records changes. |
| Gradle wrapper | Runs the checked-in build without a separate Gradle install. |
| PowerShell or Bash | Runs platform-specific scripts and examples. |

Windows:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat installDist
.\build\install\talos\bin\talos.bat --version
```

Linux:

```bash
./gradlew test --no-daemon
./gradlew check --no-daemon
./gradlew installDist
./build/install/talos/bin/talos --version
```

## Optional local tools

These tools are useful for deeper local checks. They are not required for a normal contributor build.

| Tool | Used by | Notes |
|---|---|---|
| Node.js and npm | Website checks | Needed only when editing `site/` or website docs rendering. |
| Docker | `qodanaLocal`, `gitleaksLocal` | Used for containerized optional scans. |
| Qodana CLI | `qodanaNativeFreshLocal` | Uses Qodana Community. No private token is required. |
| Gitleaks | `gitleaksLocal` through Docker | Scans for obvious committed secret patterns. |
| OSV-Scanner | `osvScannerLocal` | Checks dependency advisories when installed locally. |
| WiX Toolset | Release staging on Windows | Needed for MSI staging, not normal development. |
| GitHub CLI | Release and staging operations | Needed only for repository owner release work. |

Website checks:

```bash
cd site
npm ci
npm test
npm run build
```

Optional quality checks:

```powershell
.\gradlew.bat qodanaNativeFreshLocal --no-daemon
.\gradlew.bat talosQualitySummaries --no-daemon
.\gradlew.bat writeQualityMarkdownReports --no-daemon
```

## What is not required

Normal source development does not require cloud credentials, GitHub release secrets, code-signing credentials, WiX, Docker, or local model downloads.

Model downloads and llama.cpp setup are needed only when you want to run installed-product or live model checks. Start with [model setup](../getting-started/model-setup.md) for that path.
