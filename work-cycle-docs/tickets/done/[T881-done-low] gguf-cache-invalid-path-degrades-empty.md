# [T881-done-low] GGUF cache invalid path degrades to empty scan

Status: done
Priority: low

## Evidence Summary

- Source: Codex static review of T877 follow-up
- Date: 2026-06-27
- Talos version / commit: 0.10.6 / 7073bbb1
- Model/backend: not applicable
- Workspace fixture: unit-test fixture
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: T877 scanner follow-up
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: regression added; focused tests and full `check` green

Expected behavior:

```text
`/models` must remain usable even when `engines.llama_cpp.hf_cache_dir` is
malformed or unparseable. A bad configured cache path should behave like an
unreadable or missing cache: the downloaded-GGUF scan contributes an empty list.
```

Observed behavior:

```text
`GgufCacheScanner.scanDownloaded(Path)` catches filesystem errors, but
`downloadedNotConfigured(EngineConfig)` calls `Path.of(configured.trim())` before
the scan. On Windows, malformed configured strings such as `bad<path` throw
`InvalidPathException`; a portable NUL-containing string does the same on Java.
`ModelsCommand` catches the exception, so the REPL does not crash, but `/models`
can report `Model catalog not reachable` instead of listing configured models
with no downloaded GGUFs.
```

## Classification

Primary taxonomy bucket:

- `OUTCOME_TRUTH`

Secondary buckets:

- `VERIFICATION`

Blocker level:

- candidate follow-up

Why this level:

```text
This is not a trust or mutation failure. It is a local UX/degradation bug in a
diagnostic listing command and an overbroad T877 "never throws" claim.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Special-case one Windows path string.
```

Architectural hypothesis:

```text
The scanner owns cache-path degradation. Both path parsing and directory walking
must be exception-safe before handing results to `/models`.
```

Likely code/document areas:

- `src/main/java/dev/talos/engine/llamacpp/GgufCacheScanner.java`
- `src/test/java/dev/talos/engine/llamacpp/GgufCacheScannerTest.java`

Why a one-off patch is insufficient:

```text
The invariant is broader than a single character: malformed, missing, unreadable,
or unwalkable cache inputs should all degrade to an empty downloaded list.
```

## Goal

```text
`GgufCacheScanner.downloadedNotConfigured` never throws because of a malformed
configured cache path and returns an empty downloaded list for that scan.
```

## Non-Goals

- No subprocess model discovery.
- No managed model hot-swap.
- No broad `/models` redesign.
- No bypassing approval, permission, checkpoint, trace, or verification.

## Implementation Notes

```text
Add a focused regression using an invalid path string, then catch path parsing in
the scanner boundary and return an empty scan result.
```

## Architecture Metadata

Capability:

- model discovery diagnostics

Operation(s):

- read/list local filesystem metadata

Owning package/class:

- `dev.talos.engine.llamacpp.GgufCacheScanner`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: low
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: unit test and focused Gradle test
- Verification profile: scanner unit test
- Repair profile: none

Outcome and trace:

- Outcome/truth warnings: no catalog error caused solely by malformed cache path
- Trace/debug fields: unchanged

Refactor scope:

- Catch path parsing at the scanner boundary.
- Do not refactor `EngineRegistry`, setup profiles, or `/models` grouping.

## Acceptance Criteria

- Malformed `hf_cache_dir` values return an empty downloaded-GGUF scan.
- Existing missing/unreadable cache behavior remains empty-list degradation.
- Existing downloaded-but-not-configured listing behavior remains unchanged.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `GgufCacheScannerTest.downloadedNotConfiguredIgnoresInvalidConfiguredCacheDir`

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.engine.llamacpp.GgufCacheScannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.docs.TicketHygieneTest" --no-daemon
```

## Work-Test Cycle Notes

- Inner dev loop only.
- No patch version bump.
- Add a one-line `CHANGELOG.md` Unreleased entry when the fix lands.

## Known Risks

- None beyond the normal local filesystem variability already handled by the scanner.

## Known Follow-Ups

- None.

## Closeout (2026-06-27)

Implemented by making `downloadedNotConfigured` resolve configured cache paths
through a scanner-local safe resolver. Invalid path syntax now returns `null`,
which reuses the existing `scanDownloaded(null)` empty-list degradation path.

Regression added:

- `GgufCacheScannerTest.downloadedNotConfiguredIgnoresInvalidConfiguredCacheDir`

TDD evidence:

- Red: `.\gradlew.bat test --tests "dev.talos.engine.llamacpp.GgufCacheScannerTest" --no-daemon`
  failed with `InvalidPathException` in the new regression.
- Green: the same scanner suite passed after the fix.
