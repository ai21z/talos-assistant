# [done] Ticket: Explicit Session Restore Policy
Date: 2026-04-26
Priority: high
Status: done
Architecture references:
- `local/tickets/new-work.md`
- `docs/new-architecture/talos-harness-plan.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`
- `local/docs/talos-source-pack-safe-local-alternative-2026-04-19.md`
- `.github/copilot-instructions.md`

## Why This Ticket Exists

Installed Talos restored prior workspace conversation state automatically and
used that stale context to answer a vague new prompt. The visible symptom was a
new `test2` session reviving an old BMI calculator thread after startup printed
`restored 9 prior exchanges`.

## Problem

Talos currently treats saved workspace session history as prompt context by
default:

- `TalosBootstrap` always wires `JsonSessionStore`.
- startup always replays saved snapshot or JSONL fallback into `SessionMemory`.
- `UnifiedAssistantMode` injects that history into every prompt.
- `session.persistence` exists in config but is not enforced by bootstrap.

This violates Session Discipline because memory helps continuity by default
even when it corrupts unrelated turns.

## Goal

Separate saved session evidence from prompt context. A saved session may exist,
but it must not enter model context unless explicit restore policy allows it.

## Scope

### In scope

- Honor `session.persistence=false`.
- Add an explicit `session.auto_load` policy, defaulting to false.
- Show a startup notice when a saved session exists but is not loaded.
- Preserve explicit `/session load` restore behavior.
- Keep JSONL crash fallback available for explicit restore.
- Add tests for no implicit restore and opt-in restore.

### Out of scope

- Named sessions.
- Long-term durable user/project memory.
- LLM-based memory relevance ranking.
- New cloud or platform memory features.

## Proposed Work

1. Add typed config access for `session.auto_load`.
2. Gate bootstrap replay on `session.persistence && session.auto_load`.
3. Keep persistence-backed append/save enabled when `session.persistence=true`.
4. Use `NoOpSessionStore` and skip restore/save hooks when `session.persistence=false`.
5. Make startup output distinguish:
   - explicitly restored session
   - saved session found but not loaded
6. Make `/session load` use the same snapshot-first, JSONL-fallback restore path
   as bootstrap so explicit restore still supports crash recovery.

## Likely Files / Areas

- `src/main/java/dev/talos/cli/repl/TalosBootstrap.java`
- `src/main/java/dev/talos/cli/repl/slash/SessionCommand.java`
- `src/main/java/dev/talos/core/ConfigView.java`
- `src/main/resources/config/default-config.yaml`
- `src/test/java/dev/talos/cli/repl/TalosBootstrapTest.java`
- `src/test/java/dev/talos/cli/repl/slash/SessionCommandTest.java`
- `src/test/java/dev/talos/core/ConfigViewTest.java`

## Test / Verification Plan

Focused:

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.TalosBootstrapTest" --tests "dev.talos.cli.repl.TalosBootstrapReconcileTest" --tests "dev.talos.cli.repl.slash.SessionCommandTest" --tests "dev.talos.core.ConfigViewTest"
```

Wider:

```powershell
./gradlew.bat check
```

Manual installed Talos:

- build and install the current distribution
- run Talos in `local/playground/horror-synth-site`
- confirm saved session presence is visible but not loaded by default
- confirm `/session load` explicitly restores when a saved session exists
- capture and review `local/manual-testing/test-output`

## Acceptance Criteria

- Restarting Talos in a workspace with saved history does not automatically put
  that history into model context by default.
- Startup tells the user when saved history exists and how to explicitly resume
  or delete it.
- `session.persistence=false` disables persistent session writes and loads.
- `session.auto_load=true` preserves opt-in automatic restore behavior.
- `/session load` restores snapshot or JSONL fallback explicitly.
