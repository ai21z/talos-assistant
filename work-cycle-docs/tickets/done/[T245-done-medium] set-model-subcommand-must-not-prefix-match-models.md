# T245 - Set Model Subcommand Must Not Prefix-Match Models

Status: done

Closed: 2026-05-11

Severity: medium

## Problem

The live beta transcript showed `/set models ollama/qwen2.5-coder:14b`
being parsed as `/set model ...`, producing `sollama/qwen2.5-coder:14b`.

The parser accepts any argument starting with `model`, so `models` is treated
as the `model` subcommand and the extra `s` becomes part of the model name.

## Evidence

Live transcript:

```text
talos [auto] > /set models ollama/qwen2.5-coder:14b
  x [404] Model not found: sollama/qwen2.5-coder:14b
Tip: /models
```

Code:

- `src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java`
- `src/main/java/dev/talos/cli/repl/slash/SetCommand.java`

## Scope

- Parse `/set model <name>` by exact first-token match, not prefix match.
- Return usage for `/set models ...`.
- Preserve valid `/set model ollama/qwen2.5-coder:14b`.
- Keep the public `/models` command unchanged.

## Acceptance

- `/set models ollama/qwen2.5-coder:14b` returns usage and never searches for
  `sollama/qwen2.5-coder:14b`.
- Existing valid set-model tests still pass.

## Resolution

- `/set model <name>` parsing now uses an exact first-token match for `model`.
- `/set models ...` returns usage instead of prefix-matching and corrupting the
  model name.
- The legacy `SetCommand` path uses the same exact-token parsing.

## Verification

- `.\gradlew test --tests 'dev.talos.cli.repl.slash.InfraCommandsTest$SetModel.plural_models_subcommand_returns_usage_without_prefix_model_lookup' --no-daemon`
- `.\gradlew test --tests 'dev.talos.cli.repl.slash.InfraCommandsTest' --no-daemon`
- `.\gradlew test --no-daemon`
- `.\gradlew build --no-daemon`
