# Quality Reports

Generated quality reports are written to the repository-root `reports/` folder.
That folder is intentionally ignored by Git because reports are local run artifacts.

## How To Generate Reports

Run:

```powershell
./gradlew.bat writeQualityMarkdownReports
```

For a full fresh local quality run, including native Qodana first, run:

```powershell
./gradlew.bat talosQualityLocal
```

The generator reads the machine-readable summaries from `build/reports/talos/`
and writes four Markdown snapshots:

```text
reports/
|-- coverage-DDMMYYYY-version.md
|-- e2e-DDMMYYYY-version.md
|-- qodana-DDMMYYYY-version.md
`-- version-DDMMYYYY-version.md
```

Example:

```text
coverage-23042026-090.md
```

## Enabling The Reports Folder

This `reports-disabled/` folder is tracked documentation only. It keeps the
instructions visible without committing generated report output.

To use local reports, either:

- create a repository-root `reports/` folder yourself, or
- rename/copy `reports-disabled/` to `reports/`.

Gradle will also create `reports/` automatically when you run
`writeQualityMarkdownReports` or `talosQualityLocal`.

## Cleanup Behavior

Before writing new reports, the generator deletes previous generated report
snapshots matching:

```text
coverage|e2e|qodana|version-DDMMYYYY-version.md
```

Manual files with other names are preserved.
