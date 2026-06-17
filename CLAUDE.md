# CLAUDE.md (Talos always-loaded anchor)

This is the lean entry point loaded into context every session. It is kept short on purpose. The full doctrine is in `AGENTS.md` and the runbooks, which you read on demand.

## Authority order
1. `AGENTS.md` is the highest authority. Read it before any non-trivial Talos work. If anything here conflicts with `AGENTS.md`, `AGENTS.md` wins.
2. This file is the always-loaded summary and pointer map.
3. The per-repo auto memory (`MEMORY.md`) carries machine-local context (owner preferences, current standing) and loads automatically.

## What Talos is
Talos is a local-first Java 21 CLI workspace assistant and execution harness. Roughly "Claude Code at local level," but built around local trust, local files, explicit user control, bounded workspace tasks, safe iterative edits, and truthful evidence-backed outcomes.

Talos is NOT a chatbot, a swarm, a theatrical multi-agent system, a browser or shell automation toy, an MCP marketplace, a cloud product, or a background daemon.

The improvement target is execution-harness quality (task classification, tool-surface narrowing, permissioning, filesystem safety, approval gates, checkpoints, diffs, verification, traces, prompt-debug evidence), not model personality.

## Core doctrine (the discipline)
inspect before acting. retrieve before guessing. ask before writing. checkpoint before risky mutation. verify before claiming completion. preserve evidence after the turn. report uncertainty honestly.

A fluent final answer is not proof. Proof comes from source code, tests, tool results, approval records, command output, verifier output, local traces, prompt-debug artifacts, provider-body captures, logs, final workspace state, diffs, and generated summaries. The final answer is the least trusted artifact.

## The work-test cycle (how work is done here)
Talos work is ticket-tracked, evidence-backed, and run through the work-test cycle. Use the registered skill `talos-work-cycle` for tickets, code, audits, installed-product tests, release gates, or backlog review, unless the user says the task is outside the cycle.
- Tickets: `work-cycle-docs/tickets/` (open/ and done/, see the READMEs).
- Inner dev loop vs candidate loop: `work-cycle-docs/work-test-cycle.md` and `work-cycle-docs/work-test-cycle-step-by-step.md`.
- Audits: `work-cycle-docs/milestone-audit-workflow.md` or `work-cycle-docs/full-e2e-audit-workflow.md`.
- Living evidence wiki: `work-cycle-docs/wiki/INDEX.md` (start at `CURRENT-STATE.md`).
Inner loop means focused tests and no version bump per edit. The candidate loop runs only when a change set is ready to become versioned evidence (changelog, `scripts/bump-patch.ps1`, build, post-bump `check`, packet).

## Talos's identity and headline asset
Talos's one defensible, durable differentiator is the trust surface: it refuses to let the model claim a change it did not make (deterministic anti-overclaim), keeps approved-bytes equal to written-bytes with an auditable trace, and contains secrets and private content. This targets the named-open "tool-use hallucination" problem. The positioning is "the local assistant you can prove," not raw power.
- Trust-overclaim audit and vetted sources: `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`.
- Use the `trust-references` skill when hardening any trust-surface area.

## Hard invariants (do not violate without explicit owner say-so)
- Evidence-first and fail-closed. Never weaken approval, permission, checkpoint, trace redaction, privacy, or outcome-truth guarantees.
- Model stability: keep `qwen2.5-coder-14b` and `gpt-oss-20b` on managed llama.cpp. Engineer for stability with these models. Do not propose swapping models as the fix for instability.
- Branch naming: explanatory only, `feature/<slug>` or `improvement/<slug>`. Never `codex/` or other vague prefixes.
- `v0.9.0-beta-dev` is the main working and integration branch. Merge waves into it locally, fast-forward, never squash or rebase (packet-cited SHAs must stay reachable). No pushes to origin unless the owner says so.
- Do not overwrite `~/.talos/config.yaml` or touch `~/.talos/secrets` without explicit owner confirmation.

## Environment
- Windows 11, PowerShell primary (a Bash tool is also available for POSIX scripts).
- Gradle: run `--no-daemon` to avoid daemon lock contention on this host, for example `.\gradlew.bat check --no-daemon`. Run one gradle invocation at a time. Use `--stop` only as its own separate step.
- Qodana: Docker mode is broken on this host. Use `.\gradlew.bat qodanaNativeFreshLocal --no-daemon`.
- Version: `talosVersion` in `gradle.properties` (0.10.5 at last check). Verify `talos.bat --version` before lane work.

## Key paths
- Doctrine: `AGENTS.md`
- Work-cycle skill (registered): `.claude/skills/talos-work-cycle/SKILL.md` (canonical source of truth: `work-cycle-docs/skills/talos-work-cycle/SKILL.md`)
- Living wiki: `work-cycle-docs/wiki/INDEX.md`
- Trust audit and source list: `work-cycle-docs/research/talos-trust-overclaim-audit-and-sources-20260616.md`
- Reference material (books, comparison repos), outside the repo and not tracked: `../_reference/`
