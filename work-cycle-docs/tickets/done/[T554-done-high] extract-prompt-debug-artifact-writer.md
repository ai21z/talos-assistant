# [T554] Extract prompt-debug artifact writer

## Summary

T554 extracts prompt-debug artifact file naming and file writes from
`PromptDebugCommand` into `dev.talos.cli.prompt.PromptDebugArtifactWriter`.

The scope is intentionally narrow. It does not change prompt-debug capture
lifecycle, snapshot factories, trace persistence, destination precedence,
missing-capture UX, final command wording, redaction strings, or provider-body
formatting.

## What changed

- Added `PromptDebugArtifactWriter` as a narrowly scoped public CLI prompt
  artifact writer.
- Moved timestamped prompt-debug filenames, `Files.createDirectories(...)`,
  markdown writes, redacted provider-body JSON writes, and save-all index writes
  behind that writer.
- Kept `PromptDebugCommand` responsible for:
  - slash-command parsing;
  - latest/history capture selection;
  - destination precedence;
  - missing-capture messages;
  - `Result` construction and user-facing output wording.
- Added an ownership regression proving `PromptDebugCommand` delegates save
  artifact writes and no longer imports direct write/timestamp machinery.

## Preserved behavior

- `/prompt-debug last` output is unchanged.
- `/prompt-debug save [directory]` still writes:
  - `prompt-debug-<timestamp>.md`;
  - `prompt-debug-<timestamp>.provider-body.json` when provider JSON exists.
- `/prompt-debug save-all [directory]` still writes:
  - `prompt-debug-<timestamp>-<NN>.md`;
  - `prompt-debug-<timestamp>-<NN>.provider-body.json` when provider JSON exists;
  - `prompt-debug-<timestamp>-index.md`.
- Save command result lines still use:
  - `Saved prompt debug render to: ...`;
  - `Saved provider body JSON to: ...`;
  - `Saved prompt debug history index to: ...`.
- Redaction is still delegated through `PromptDebugInspector` and
  `PromptDebugRedactor`.

## TDD evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest.saveDelegatesArtifactWritingToPromptDebugArtifactWriter" --no-daemon
```

The test failed before implementation because
`PromptDebugArtifactWriter.java` did not exist and `PromptDebugCommand` still
owned direct artifact writes.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest.saveDelegatesArtifactWritingToPromptDebugArtifactWriter" --no-daemon
```

The ownership test passed after extraction.

Focused prompt-debug/canary suite:

```powershell
.\gradlew.bat test `
  --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" `
  --tests "dev.talos.runtime.policy.ArtifactCanaryScanTest" `
  --no-daemon
```

This passed and covers prompt-debug save behavior, prompt-debug redaction
ownership, and generated artifact canary safety.

## Final local gate

Final gate for the committed diff:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

All three passed locally.

## Out of scope

- Moving `PromptDebugCapture`.
- Moving `PromptDebugSnapshot`.
- Moving trace persistence.
- Normalizing provider request recording.
- Changing prompt-debug destination precedence.
- Changing prompt-debug redaction wording or artifact formatting.

## Next move

After T554 is integrated, inspect the post-extraction prompt-debug artifact
shape before selecting T555. Do not assume capture lifecycle, trace
persistence, provider-body normalization, or artifact canary ownership is next
without current source inspection.
