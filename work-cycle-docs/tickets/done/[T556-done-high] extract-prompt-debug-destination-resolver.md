# [T556] Extract prompt-debug destination resolver

## Summary

T556 extracts prompt-debug artifact destination resolution out of
`PromptDebugCommand` into `dev.talos.cli.prompt.PromptDebugDestinationResolver`.

The command still owns slash-command UX:

- parsing `last`, `save`, `save-all`, and `saveall`;
- latest/history capture selection;
- missing-capture messages;
- final `Result` wording;
- help text.

The new resolver owns only destination mechanics:

- explicit save directory;
- `talos.promptDebugDir`;
- `TALOS_PROMPT_DEBUG_DIR`;
- default `~/.talos/prompt-debug`;
- optional single/double quote stripping;
- absolute path normalization.

`PromptDebugArtifactWriter` remains the artifact filename/write owner.
`PromptDebugInspector` and `PromptDebugRedactor` were not changed.

## Behavior preserved

Destination precedence remains:

1. explicit directory;
2. `talos.promptDebugDir`;
3. `TALOS_PROMPT_DEBUG_DIR`;
4. `~/.talos/prompt-debug`.

Quoted explicit destinations still unwrap before path normalization. Command
output wording, saved filenames, artifact formatting, redaction behavior, and
missing-capture wording are unchanged.

## Tests

T556 added direct resolver behavior coverage and an ownership regression proving
`PromptDebugCommand` delegates destination resolution instead of owning system
property, environment variable, or quote-stripping mechanics.

Verification run during the ticket:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest.saveDelegatesDestinationResolutionToPromptDebugDestinationResolver" --no-daemon
```

This failed before implementation because `PromptDebugDestinationResolver` did
not exist.

After implementation:

```powershell
.\gradlew.bat test `
  --tests "dev.talos.cli.prompt.PromptDebugDestinationResolverTest" `
  --tests "dev.talos.cli.repl.slash.PromptDebugCommandTest" `
  --tests "dev.talos.cli.prompt.PromptDebugInspectorRedactionOwnershipTest" `
  --tests "dev.talos.runtime.policy.ArtifactCanaryScanTest" `
  --no-daemon
```

## Out of scope

T556 does not move:

- prompt-debug capture lifecycle;
- prompt-debug snapshot factories;
- provider-body recording or normalization;
- prompt-debug artifact writing;
- prompt-debug redaction;
- artifact canary ownership;
- trace persistence.

## Next move

Inspect the post-T556 prompt-debug artifact/command shape before selecting
T557. Do not assume capture lifecycle, provider-body normalization, trace
persistence, or artifact canary ownership is the next coherent implementation
unit.
