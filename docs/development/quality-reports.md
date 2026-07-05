# Quality reports

Talos can write local evidence summaries for reviewers. Generated report outputs stay local. They are not committed, and they are not published on the website.

The reports are useful for review, but they are not a substitute for tests, installed-product smoke, manual PTY evidence, or release staging.

## Local report outputs

| Output | Purpose |
|---|---|
| `build/reports/talos/version-summary.json` | Candidate identity and build facts. |
| `build/reports/talos/coverage-summary.json` | JaCoCo and test summary facts. |
| `build/reports/talos/e2e-summary.json` | Deterministic E2E summary facts. |
| `build/reports/talos/qodana-summary.json` | Qodana artifact and provenance summary. |
| `build/reports/talos/architecture-boundaries.json` | Architecture boundary validation facts. |
| `reports/*.md` | Reviewer-friendly Markdown snapshots generated from the JSON summaries. |

`build/`, `.qodana/`, `reports/`, and `local/` are local outputs. Do not commit them.

## Commands

Write the machine-readable summaries:

```powershell
.\gradlew.bat talosQualitySummaries --no-daemon
```

Write Markdown snapshots under `reports/`:

```powershell
.\gradlew.bat writeQualityMarkdownReports --no-daemon
```

Run the local Qodana Community lane and then write summaries:

```powershell
.\gradlew.bat qodanaNativeFreshLocal --no-daemon
.\gradlew.bat talosQualitySummaries --no-daemon
```

Run the combined optional local quality lane:

```powershell
.\gradlew.bat talosQualityLocal --no-daemon
```

## Qodana scope

The checked-in `qodana.yaml` uses Qodana Community. The Gradle Qodana tasks also pin the community linter. No private token is required for the normal build and test loop.

The website uses its own npm tests and build checks. It is not part of the Qodana JVM scan.

## Reading the evidence

Use generated reports as pointers to primary evidence. If a report says a lane passed, confirm the matching test output, workflow run, staged artifact, installed binary, or manual audit packet before making a release decision.

If generated evidence was produced from a dirty tree, wrong branch, wrong executable, or stale artifact directory, discard it and rerun from the intended state.
