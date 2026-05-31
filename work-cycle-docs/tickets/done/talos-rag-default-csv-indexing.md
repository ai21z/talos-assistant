# [done] Ticket: Include CSV In Default RAG Indexing
Date: 2026-04-26
Priority: medium
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/architecture/talos-harness-source-of-truth.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`

## Why This Ticket Exists

Manual installed-Talos QA found a mismatch between Talos's supported source
format model and the default RAG indexing configuration.

Workspace contents:

```text
README.md
config.json
metrics.csv
```

After `/reindex`, Talos reported:

```text
Reindex complete: Scanned: 2, Skipped: 0, Embedded: 2, Chunks: 2
Indexed files (2):

  config.json
  README.md
```

`metrics.csv` was not indexed, even though the assistant could later discover
it through direct tools.

## Problem

CSV is recognized by the ingestion model:

```text
src/main/java/dev/talos/core/ingest/SourceFormat.java
```

but the default RAG config does not include it:

```text
src/main/resources/config/default-config.yaml
```

The fallback defaults in `Config.ensureDefaults()` are even narrower and also
omit CSV.

This creates inconsistent behavior:

- `talos.list_dir` / `talos.read_file` can inspect CSV files.
- `SourceFormat` says CSV is a supported textual source format.
- `/reindex` and `/files` omit CSV by default.
- Retrieval may miss small local data files that users reasonably expect Talos
  to understand.

## Goal

Make default indexing behavior match Talos's declared lightweight text/data
format support for CSV.

## Scope

### In scope

- Add CSV to default include globs.
- Update both classpath config and Java fallback defaults.
- Add tests proving default config indexes CSV.
- Verify `/reindex` and `/files` include CSV in a small workspace.

### Out of scope

- Spreadsheet extraction.
- Binary Excel support.
- General table reasoning improvements.
- Broad config migration.

## Proposed Work

1. Add to `default-config.yaml`:

   ```yaml
   - "**/*.csv"
   - "**/*.tsv"
   ```

   TSV should be considered at the same time because it is the same lightweight
   text-table class and is already referenced in CLI grep/file patterns.

2. Update `Config.ensureDefaults()` fallback include list with the same globs.

3. Add a regression test for default includes:

   - create a temporary workspace with `README.md`, `config.json`,
     `metrics.csv`
   - run the indexer with default config
   - assert `metrics.csv` is indexed/listed

4. Run installed Talos against the mixed-docs QA workspace:

   ```text
   /reindex
   /files
   ```

   Expected: `metrics.csv` appears.

## Likely Files / Areas

- `src/main/resources/config/default-config.yaml`
- `src/main/java/dev/talos/core/Config.java`
- `src/test/java/dev/talos/core/index/`
- `src/test/java/dev/talos/core/ConfigTest.java` if present

## Test / Verification Plan

Focused tests:

```powershell
./gradlew.bat test --tests "*Config*"
./gradlew.bat test --tests "*Indexer*"
```

Then widen:

```powershell
./gradlew.bat test
./gradlew.bat e2eTest
```

Manual installed verification:

- Install current dist.
- Run `/reindex` and `/files` in a disposable workspace containing CSV.
- Confirm CSV is included without custom config.

## Acceptance Criteria

- CSV files are indexed by default.
- Java fallback defaults match packaged config defaults.
- Existing excludes remain unchanged.
- Binary spreadsheet support remains explicitly out of scope.

## Completion Notes

Implemented on branch `ticket/talos-rag-default-csv-indexing`.

- Added CSV and TSV include globs to packaged and fallback defaults.
- Added TSV to the lightweight structured-source model so default config,
  format detection, media typing, and source classification stay aligned.
- Added unit coverage for default include globs, indexer filtering, source
  format detection, media typing, and source classification.
- Installed Talos and verified `/reindex --full` plus `/files` in
  `local/manual-testing/qa-workspaces/mixed-docs`.

Installed verification transcript showed:

```text
Reindex complete: Scanned: 4, Skipped: 0, Embedded: 4, Chunks: 4
Indexed files (4):
  config.json
  metrics.csv
  metrics.tsv
  README.md
```

Verification:

```powershell
./gradlew.bat test --tests "dev.talos.core.ConfigDefaultIncludesTest" --tests "dev.talos.core.index.IndexerCaseTest" --tests "dev.talos.core.ingest.SourceFormatTest" --tests "dev.talos.core.ingest.MediaTypeTest" --tests "dev.talos.core.ingest.SourceClassifierTest"
./gradlew.bat test
./gradlew.bat e2eTest
./gradlew.bat check
pwsh tools/uninstall-windows.ps1 -Quiet
./gradlew.bat --no-daemon installDist
pwsh tools/install-windows.ps1 -Force -Quiet
```
