# Quality reports

Talos can write local evidence summaries for reviewers. Generated report outputs stay local. They are not committed, and they are not published on the website.

The reports are useful for review, but they are not a substitute for tests, installed-product smoke, manual PTY evidence, or release staging. Treat the Markdown files as reviewer snapshots. Treat the JSON summaries and gates as the machine-readable release evidence.

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

Write Markdown snapshots under `reports/`. This is diagnostic and may write reports that describe failed evidence:

```powershell
.\gradlew.bat writeQualityMarkdownReports --no-daemon
```

Validate the generated summaries as release-quality evidence:

```powershell
.\gradlew.bat qualityReportGate --no-daemon
```

Run the normal `check` and also write Markdown snapshots for local review:

```powershell
.\gradlew.bat check -PwithQualityReports=true --no-daemon
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

Run the maintainer release-quality packet before release staging or publication decisions:

```powershell
.\gradlew.bat releaseQualityPacket --no-daemon
```

`releaseQualityPacket` runs the full automated check, the fresh local quality lane, Markdown report generation, and the summary gate. It is intentionally heavier than normal development.

## Qodana scope

The checked-in `qodana.yaml` uses Qodana Community. The Gradle Qodana tasks also pin the community linter. No private token is required for the normal build and test loop.

The website uses its own npm tests and build checks. It is not part of the Qodana JVM scan.

The summary gate accepts only `qodana-results-match-current-candidate`, together with the exact current-source snapshot, authenticated run/artifact binding, consistent result schemas, required observed source roots, the configured analysis engine, and zero critical issues. Missing, incomplete, stale, malformed, conflicting, or critical evidence is rejected; `qodana-provenance-incomplete` is not an accepted status.

Native Qodana branch/revision fields may be absent on the local Community path. Their absence is informational only when the exact source snapshot and the other required bindings establish the accepted status. Explicit native branch/revision conflicts still reject the evidence even if the wrapper's source snapshot and artifact hashes match. The Markdown reviewer signal follows the accepted summary status; generating a report is not itself a passing quality gate.

## Reading the evidence

Use generated reports as pointers to primary evidence. If a report says a lane passed, confirm the matching test output, workflow run, staged artifact, installed binary, or manual audit packet before making a release decision.

Retain source-bound dirty-tree results as local diagnostic evidence, not as qualification of a clean, exact release candidate. Rerun the required release gates from the intended committed state. Results from a wrong branch, wrong executable, or stale artifact directory do not qualify that candidate either; preserve their provenance rather than deleting historical evidence.
