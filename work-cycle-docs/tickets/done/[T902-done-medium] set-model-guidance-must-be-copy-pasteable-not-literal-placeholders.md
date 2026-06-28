# [T902-done-medium] /set model guidance must be copy-pasteable, not literal placeholders

Status: done
Priority: medium

## Evidence Summary

- Source: adversarial self-review of T898-T901 (workflow wf_bec7abba) + owner live evidence
- Talos version / commit: 0.10.6 / ba7cb800 (branch improvement/qodana-cleanup)
- Verification status: root cause confirmed by the review (modelswitch + liveblockers dimensions, both verdict=confirmed); fixed + focused tests green.

Observed (live): the owner ran `/set model backend/Qwen3.6-14B-A3B-VibeForged-v2-Q6_K` and the bare-name form. Both returned the T899 downloaded-but-unconfigured guidance with LITERAL placeholders:
```
talos setup models --profile <name> --server-path <llama-server> --write --force
```
The owner had no way to know the real profile name or server-path, tried twice, and stayed stuck on the off-doctrine 35b. T899 closed the bare-404 gap but the guidance was informational, not actionable. This was the single thing blocking the owner's whole model-switch journey.

## Root Cause

[SetModelCommand.modelNotFoundMessage(String,List)](src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java) emitted the placeholders verbatim even though both values are resolvable in-process: the typed GGUF stem maps to a canned profile (the owner's `Qwen3.6-14B-A3B-VibeForged-v2-Q6_K` -> profile `qwen36vf-q6k`), and the server-path is in the running config. The canned profile table lived ONLY in `SetupCmd.profiles()` (package `cli.launcher`), and `SetModelCommand` (package `cli.repl.slash`) could not reach it without risking a `launcher <-> repl` package cycle.

## Fix

New neutral registry [LlamaCppModelProfiles](src/main/java/dev/talos/engine/llamacpp/LlamaCppModelProfiles.java) in `engine.llamacpp` (which `SetModelCommand` already depends on via `GgufCacheScanner`, so no cycle): the canonical canned-profile table (alias -> hf_repo + hf_file) plus `profileAliasForGgufFile(String)` (case-/path-/`.gguf`-insensitive stem match). [SetupCmd.profiles()](src/main/java/dev/talos/cli/launcher/SetupCmd.java) now sources from this registry so the two never drift (single source of truth).

[SetModelCommand](src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java): a new `modelNotFoundMessage(String,List,resolvedProfile,serverPath)` overload substitutes the resolved profile alias and the configured `server_path` (read from `engines.llama_cpp.server_path` via `EngineConfig.data()`) into a copy-pasteable command, quoting the server-path when it contains spaces, and falling back to the placeholders only when a value cannot be resolved. So the owner's exact case now prints:
```
talos setup models --profile qwen36vf-q6k --server-path C:/.../llama-server.exe --write --force
```

## Honesty / Scope

- The no-hot-swap reality is unchanged and still stated honestly: this still requires running the command in a terminal and restarting (managed llama.cpp binds one GGUF at launch). T902 makes the command runnable, it does not add an in-REPL hot-swap. The fuller guided/in-REPL configure flow remains the deferred T886 arc; this is the bounded SetModelCommand follow-up the review recommended.
- Trust surface untouched (message-only). No approval/permission/checkpoint/redaction/outcome-truth change.

## Tests / Evidence

- [LlamaCppModelProfilesTest](src/test/java/dev/talos/engine/llamacpp/LlamaCppModelProfilesTest.java): alias resolution for the 5 canned GGUF files (incl the live owner case), case/path/.gguf insensitivity, unknown/blank -> empty, table integrity.
- [SetModelCommandTest](src/test/java/dev/talos/cli/repl/slash/SetModelCommandTest.java): the guidance substitutes the real profile + server-path (no literal placeholders), quotes a server-path with spaces, and falls back to placeholders when unresolved; existing T899 placeholder/llama_cpp/generic/usage tests stay green. SetupCmd profiles output unchanged (refactor is data-identical).

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One commit (registry + SetupCmd delegation + SetModelCommand + tests + ticket).
