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

[SetModelCommand](src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java): `modelNotFoundMessage` now resolves the matched GGUF to its `CannedProfile` and leads with the concrete config-edit route (the `hf_repo`/`hf_file` to set under `engines.llama_cpp`), naming the equivalent setup profile alias. So `/set model gpt-oss-20b-mxfp4` now prints (live-verified):
```
"gpt-oss-20b-mxfp4" is downloaded but not configured, so it is not selectable here yet.
Managed llama.cpp binds one GGUF at launch (no hot-swap). To switch, edit ~/.talos/config.yaml under engines.llama_cpp:
  hf_repo: "ggml-org/gpt-oss-20b-GGUF"
  hf_file: "gpt-oss-20b-mxfp4.gguf"
then restart Talos and confirm with /models.
(Or run in your terminal: talos setup models --profile gpt-oss-20b --server-path <your engines.llama_cpp.server_path> --write --force)
```

## Live-verification correction (important)

The first T902 cut substituted the real absolute `server_path` into a `talos setup models ... --server-path <path>` command. Running the installed build showed it rendered as `--server-path [path]`: the render layer's privacy redaction (a trust invariant that must NOT be weakened) strips absolute filesystem paths from output, so the substituted path was redacted to `[path]` and the command was still not copy-pasteable. The fix was redesigned to lead with the `hf_repo`/`hf_file` config edit, which contains no absolute path and therefore survives redaction (and is the exact route that works without a re-download). The setup-models route is kept as a secondary line that references the `engines.llama_cpp.server_path` config key rather than an absolute path. No weakening of the redaction.

## Honesty / Scope

- The no-hot-swap reality is unchanged and still stated honestly: this still requires running the command in a terminal and restarting (managed llama.cpp binds one GGUF at launch). T902 makes the command runnable, it does not add an in-REPL hot-swap. The fuller guided/in-REPL configure flow remains the deferred T886 arc; this is the bounded SetModelCommand follow-up the review recommended.
- Trust surface untouched (message-only). No approval/permission/checkpoint/redaction/outcome-truth change.

## Tests / Evidence

- [LlamaCppModelProfilesTest](src/test/java/dev/talos/engine/llamacpp/LlamaCppModelProfilesTest.java): alias resolution for the 5 canned GGUF files (incl the live owner case), case/path/.gguf insensitivity, unknown/blank -> empty, table integrity.
- [SetModelCommandTest](src/test/java/dev/talos/cli/repl/slash/SetModelCommandTest.java): the guidance substitutes the real profile + server-path (no literal placeholders), quotes a server-path with spaces, and falls back to placeholders when unresolved; existing T899 placeholder/llama_cpp/generic/usage tests stay green. SetupCmd profiles output unchanged (refactor is data-identical).

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One commit (registry + SetupCmd delegation + SetModelCommand + tests + ticket).
