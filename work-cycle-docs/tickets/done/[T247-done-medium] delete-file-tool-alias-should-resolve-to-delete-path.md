# T247 - Delete File Tool Alias Should Resolve To Delete Path

Status: done

Closed: 2026-05-11

Severity: medium

## Problem

The live beta transcript showed the model trying `talos.delete_file`, which
failed as an unknown tool even though Talos has a first-class delete tool:
`talos.delete_path`.

This is a common, predictable alias shape. Rejecting it wastes an iteration and
makes a normal user request look less supported than it is.

## Evidence

Live transcript:

```text
> Using delete_file: synthwave_band_webpage.pdf
> error delete_file: Unknown tool: talos.delete_file
```

Code:

- `ToolAliasPolicy` maps `delete`, `remove`, and `delete_path`, but not
  `delete_file`.
- `ToolRegistry` delegates alias resolution through `ToolAliasPolicy`.

## Scope

- Accept `delete_file`, `remove_file`, and fully-qualified `talos.delete_file`
  as aliases for `talos.delete_path`.
- Preserve strict registry behavior for measurement mode.
- Keep delete approval/checkpoint behavior unchanged.

## Acceptance

- `ToolRegistry.get("talos.delete_file")` resolves to `talos.delete_path`.
- A scripted assistant turn using `talos.delete_file` deletes the requested file
  through the canonical delete tool.
- Unknown unrelated tools remain rejected.

## Resolution

- `delete_file`, `talos.delete_file`, `remove_file`, and backend namespaced
  delete-file aliases now resolve to canonical `talos.delete_path`.
- A scripted assistant turn using `talos.delete_file` now deletes through the
  registered delete-path tool instead of failing as unknown.

## Verification

- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming.naturalDeleteRequestAcceptsDeleteFileAlias' --tests 'dev.talos.tools.ToolRegistryTest.workspaceOperationAliasesResolveToCanonicalTools' --no-daemon`
- `.\gradlew test --tests 'dev.talos.cli.modes.AssistantTurnExecutorTest$NonStreaming' --tests 'dev.talos.tools.ToolRegistryTest' --no-daemon`
- `.\gradlew test --no-daemon`
- `.\gradlew build --no-daemon`
