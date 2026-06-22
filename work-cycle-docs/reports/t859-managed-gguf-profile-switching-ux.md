# T859 Managed GGUF Profile Switching UX

Status: implemented-awaiting-review
Date: 2026-06-22
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Implementation commit: pending

## Summary

T859 closes the product-truth gap where `/models` and `/set model` made managed
`llama.cpp` look like it could select any downloaded GGUF. Current Talos does
not scan the Hugging Face cache or arbitrary GGUF folders for switchable
profiles. The managed `llama.cpp` catalog exposes the live server's `/v1/models`
response, or one configured fallback model.

This implementation chooses the minimal beta path:

- `/models` says managed `llama.cpp` lists the configured/running model only.
- `/set model llama_cpp/<name>` failure text explains that a downloaded GGUF
  must be configured before it appears in `/models`.
- The docs describe the profile-switch workflow:
  `talos setup models --profile <name> --write --force`, then restart Talos.
- `SetupCmdTest` pins that `--force` writes a backup before replacing config.

No cache scanning, hot-swap, or multi-profile config store was added.

## Code Changes

- `src/main/java/dev/talos/cli/repl/slash/ModelsCommand.java`
  - Adds managed `llama.cpp` scope wording and the setup/restart switch command.
- `src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java`
  - Adds a managed-GGUF-specific not-found message for `llama_cpp/...` requests.
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java`
  - Adds setup-help wording that switching managed GGUF profiles rewrites config
    and requires restart.
- `docs/setup-managed-models.md`, `docs/user/model-setup.md`,
  `docs/user/commands.md`
  - Document that downloaded GGUFs are not automatically selectable and that
    `--force` writes a backup before profile replacement.

## Tests

- `src/test/java/dev/talos/cli/repl/slash/ModelsCommandTest.java`
  - Pins `/models` managed `llama.cpp` configured/running-model wording.
- `src/test/java/dev/talos/cli/repl/slash/SetModelCommandTest.java`
  - Pins `llama_cpp/...` not-found guidance and keeps non-`llama_cpp` failures
    generic.
- `src/test/java/dev/talos/cli/launcher/SetupCmdTest.java`
  - Pins `--force` backup creation before config replacement.

## Verification

Focused red test was observed before implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ModelsCommandTest" --tests "dev.talos.cli.repl.slash.SetModelCommandTest" --tests "dev.talos.cli.launcher.SetupCmdTest" --no-daemon
```

It failed because `SetModelCommand.modelNotFoundMessage(...)` did not exist.

Post-implementation focused verification passed:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.repl.slash.ModelsCommandTest" --tests "dev.talos.cli.repl.slash.SetModelCommandTest" --tests "dev.talos.cli.launcher.SetupCmdTest" --no-daemon
```

## Deferred Work

The richer design of multiple configured managed profiles remains deferred. The
current beta workflow is explicit config replacement plus restart.

T856 remains the separate managed `llama.cpp` embeddings/vector-lane ticket.
