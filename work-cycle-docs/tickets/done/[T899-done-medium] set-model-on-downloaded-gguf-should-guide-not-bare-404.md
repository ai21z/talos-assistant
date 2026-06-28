# [T899-done-medium] /set model on a downloaded-but-unconfigured GGUF must give actionable switch guidance, not a bare 404

Status: done
Priority: medium

## Evidence Summary

- Source: owner manual-test session, 2026-06-28 (could not change models; confused by profiles vs models)
- Talos version / commit: 0.10.6 / 01da420e (branch improvement/qodana-cleanup)
- Verification status: root cause code-verified against the live transcript; fixed + focused tests green.

Observed (live): the owner ran `/set model Qwen3.6-14B-A3B-VibeForged-v2-Q6_K` (a GGUF shown by `/models` under "Downloaded GGUFs (not configured)") and got only:
```
x [404] Model not found: Qwen3.6-14B-A3B-VibeForged-v2-Q6_K
Tip: /models
```
He had no idea the file was on disk-but-unconfigured, nor how to actually switch. He then typed the shell command into the REPL (see T898).

## Root Cause

[SetModelCommand.execute](src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java:37) validates the requested name against the running/configured catalog only ([LlamaCppCatalog.find] -> `installed()`). A downloaded-but-unconfigured GGUF is never in that catalog, so `find` returns empty -> 404. The helpful branch in [modelNotFoundMessage](src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java:44) only fired for a `llama_cpp/`-prefixed name, so a bare GGUF filename fell through to the generic `Tip: /models`. Managed llama.cpp binds one GGUF at launch (no hot-swap), so switching genuinely requires reconfigure + restart, but the message never explained any of that.

## Fix

New overload `modelNotFoundMessage(String, List<ModelRef>)`: when the requested name matches a downloaded-but-unconfigured GGUF (case-insensitive, ignoring a `.gguf` suffix and any `backend/` prefix) it returns actionable guidance instead of the bare 404:
- states the GGUF is downloaded but not configured (so it is not selectable here yet),
- states the honest architectural limit (managed llama.cpp binds one GGUF at launch, no hot-swap; switching means reconfigure + restart),
- gives the exact `talos setup models --profile <name> --server-path <llama-server> --write --force` to run in the terminal, or the in-place `engines.llama_cpp.hf_repo / hf_file` config edit,
- ends with "restart Talos and confirm with /models".

[SetModelCommand.execute](src/main/java/dev/talos/cli/repl/slash/SetModelCommand.java) now resolves the on-disk list via `GgufCacheScanner.downloadedNotConfigured(ctx.cfg())` (the same safe, no-subprocess scan `/models` already uses) and passes it to the new overload. The original single-arg `modelNotFoundMessage` is unchanged (existing tests + the `llama_cpp/`-prefixed path preserved).

## Honesty / Scope

- No false promise of an in-REPL hot-swap. The message states the reconfigure + restart reality plainly. Trust-surface (approval/permission/checkpoint/outcome-truth) untouched; this is a message-only change.
- Out of scope (tracked under [T886](work-cycle-docs/tickets/open)): a fuller guided model-switch flow (interactive reconfigure, auto-editing config.yaml, engine restart), and per-profile name mapping in `/models`. T899 fixes the precise stumble (an unhelpful 404 on a real on-disk GGUF).

## Tests / Evidence

[SetModelCommandTest](src/test/java/dev/talos/cli/repl/slash/SetModelCommandTest.java): the live bug name returns the "downloaded but not configured" + `talos setup models` + `restart Talos` guidance and NOT the bare `Tip: /models`; the match is case- and `.gguf`-suffix-insensitive; an unknown name with a downloaded list still falls back to the generic hint; existing `llama_cpp/` and generic single-arg messages and the usage-error tests stay green.

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One commit (code + test + ticket).
