# Work-Test Cycle Setup

This document explains how to set up the local Talos work-test cycle.

The rule is simple:

- hard gate: unit tests and deterministic E2E tests must pass
- coverage: local regression guard
- Qodana: optional, highly recommended local static analysis
- security scans: optional, recommended local checks
- paid cloud services: not required

## 1. Install Java 21+

Talos builds with Java 21.

What to install:

- Any JDK 21+ distribution.
- Keep `JAVA_HOME` pointed at that JDK if you have multiple Java versions.

Check:

```powershell
java -version
```

Expected:

- Java reports version 21 or newer.

## 2. Check Gradle Wrapper

The repo uses the checked-in Gradle wrapper. Do not install Gradle globally just
for this project.

Check:

```powershell
./gradlew.bat --version
```

Expected:

- Gradle starts.
- No wrapper download or lock-file error remains.

If Gradle cannot access the wrapper cache, close other Gradle/Java processes and
retry.

## 3. Install Docker Or Podman For Optional Qodana

Qodana is optional but highly recommended.

Default Qodana container mode requires Docker or Podman running locally. You do
not need to start a Qodana container yourself. The task starts a temporary
container and removes it when done.

Official Docker Desktop links:

- Docker Desktop overview: https://docs.docker.com/desktop/
- Docker Desktop for Windows install: https://docs.docker.com/desktop/setup/install/windows-install/
- Docker Desktop download guide: https://docs.docker.com/get-started/introduction/get-docker-desktop/
- Podman Desktop alternative: https://podman-desktop.io/

Important Docker Desktop licensing note:

- Docker Desktop is free for personal use, education, non-commercial open source, and small businesses under Docker's stated limits.
- Larger commercial use may require a paid Docker subscription.
- If that matters for your environment, use Podman instead of Docker Desktop.

Check:

```powershell
docker version
```

Expected:

- The command can talk to the container engine.
- If Docker Desktop is installed but not running, start Docker Desktop first.
- Give Docker at least 4 GB memory for Qodana; JetBrains documents this as a practical requirement for the Gradle Qodana path.

## 4. Pull The Qodana Community Image

We use the free Community JVM linter.

Image:

```text
jetbrains/qodana-jvm-community:2026.1
```

Official links:

- Qodana deployment options: https://www.jetbrains.com/help/qodana/deploy-qodana.html
- Qodana quick start: https://www.jetbrains.com/help/qodana/quick-start.html
- Qodana JVM docs: https://www.jetbrains.com/help/qodana/jvm.html
- Qodana YAML configuration: https://www.jetbrains.com/help/qodana/qodana-yaml.html
- Docker Hub image: https://hub.docker.com/r/jetbrains/qodana-jvm-community

Pull:

```powershell
docker pull jetbrains/qodana-jvm-community:2026.1
```

Expected:

- Docker downloads the Qodana Community JVM image.
- The image is around 1 GB, so the first pull can take time.

Do not use the paid image for the local-free path:

```text
jetbrains/qodana-jvm
```

That image belongs to the paid Qodana JVM line and normally expects token-based
setup.

Optional Qodana CLI install:

```powershell
winget install -e --id JetBrains.QodanaCLI
```

The repo does not require Qodana CLI because `./gradlew.bat qodanaLocal` calls
Docker directly. Install the CLI only if you prefer `qodana scan` workflows.

## 5. Run The Hard Local Gate

This is the gate that must pass before a candidate is trusted.

Command:

```powershell
./gradlew.bat check
```

What it runs:

- unit tests
- deterministic E2E tests
- JaCoCo coverage verification

Expected:

- Unit tests pass.
- E2E tests pass.
- Coverage stays above the configured baseline.

If this fails, fix the code. Do not hide the failure with Qodana or summary
generation.

## 6. Run Optional Qodana Locally

Qodana is highly recommended, but it is not the hard gate.

Preferred repo task:

```powershell
./gradlew.bat qodanaLocal
```

Equivalent raw Docker command:

```powershell
docker run --rm -v "${PWD}:/data/project" -v "${PWD}\.qodana:/data/results" -v talos-qodana-cache:/data/cache -v talos-qodana-gradle-cache:/root/.gradle jetbrains/qodana-jvm-community:2026.1
```

Expected:

- Qodana writes local results to `.qodana/`.
- Qodana and Gradle caches are stored in Docker volumes named `talos-qodana-cache` and `talos-qodana-gradle-cache`.
- No `QODANA_TOKEN` is needed.
- No Qodana Cloud upload is needed.

Current project rule:

- Critical Qodana findings fail the Qodana run.
- Existing high/moderate findings should be reviewed and reduced, but they are not yet a hard block because the current baseline is noisy.

If Docker mode fails on Windows with a Gradle `Input/output error`, use one of
these fallbacks:

```powershell
winget install -e --id JetBrains.QodanaCLI
./gradlew.bat qodanaNativeLocal
```

Or run Qodana locally from IntelliJ IDEA's Qodana/Problems tool window.

## 7. Run Optional Security Scans

Qodana Community is not a full security stack. Use focused local tools for
security.

### Secret scan with Gitleaks

Official link:

- Gitleaks GitHub: https://github.com/gitleaks/gitleaks

Repo task:

```powershell
./gradlew.bat gitleaksLocal
```

Equivalent raw Docker command:

```powershell
docker run --rm -v "${PWD}:/repo" ghcr.io/gitleaks/gitleaks:latest git -v /repo
```

Expected:

- The scan fails if likely secrets are found.

### Dependency scan with OSV-Scanner

Official links:

- OSV-Scanner install: https://google.github.io/osv-scanner/installation/
- OSV-Scanner usage: https://google.github.io/osv-scanner/usage/

Install on Windows:

```powershell
winget install Google.OSVScanner
```

Run:

```powershell
./gradlew.bat osvScannerLocal
```

Equivalent raw command:

```powershell
osv-scanner scan -r .
```

Expected:

- The scanner checks dependency manifests and reports known vulnerabilities.
- This may need network access to query vulnerability data.

## 8. Run The Candidate Evidence Packet

Use this after the hard gate passes.

Recommended sequence:

```powershell
./scripts/bump-patch.ps1
./gradlew.bat check
./gradlew.bat qodanaLocal
./gradlew.bat talosQualitySummaries
```

If you intentionally skip Qodana:

```powershell
./scripts/bump-patch.ps1
./gradlew.bat check
./gradlew.bat talosQualitySummaries
```

Expected summary files:

- `build/reports/talos/version-summary.json`
- `build/reports/talos/coverage-summary.json`
- `build/reports/talos/e2e-summary.json`
- `build/reports/talos/qodana-summary.json`

Important:

- If Qodana was skipped, `qodana-summary.json` should say results are missing or stale.
- That is acceptable only if the reviewer explicitly accepts the skipped optional scan.
- Unit and E2E failures are not acceptable for a normal candidate.

## 9. What To Commit

Commit:

- source changes
- test changes
- docs changes
- `gradle.properties` version bump
- `CHANGELOG.md` update

Do not commit:

- `build/`
- `.qodana/`
- local scanner output
- personal files under `local/`

## 10. Quick Command Reference

Hard gate:

```powershell
./gradlew.bat check
```

Optional Qodana:

```powershell
./gradlew.bat qodanaLocal
./gradlew.bat qodanaNativeLocal
```

Optional security:

```powershell
./gradlew.bat gitleaksLocal
./gradlew.bat osvScannerLocal
```

Candidate summaries:

```powershell
./gradlew.bat talosQualitySummaries
```

Full recommended local candidate cycle:

```powershell
./scripts/bump-patch.ps1
./gradlew.bat check
./gradlew.bat qodanaLocal
./gradlew.bat gitleaksLocal
./gradlew.bat osvScannerLocal
./gradlew.bat talosQualitySummaries
```
