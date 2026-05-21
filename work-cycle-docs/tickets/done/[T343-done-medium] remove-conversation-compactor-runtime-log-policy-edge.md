# [T343-done-medium] Remove Conversation Compactor Runtime Log Policy Edge

Status: done
Priority: medium
Date: 2026-05-21
Branch: `T343`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T342-done-medium] remove-score-threshold-reranker-runtime-log-policy-edge`

## Evidence Summary

- Source: post-T342 architecture burn-down request after PR #7 merged into
  `v0.9.0-beta-dev`.
- Date: 2026-05-21.
- Talos version / commit: `0.9.9` / local working tree on `T343`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary: removed one `core-no-runtime` baseline edge by replacing a
  conversation compaction exception-path log's runtime policy formatter
  dependency with a content-free exception-class diagnostic.
- Verification status: RED/GREEN ownership test, focused conversation
  compaction tests, architecture scanner, diff hygiene, and full `check` passed.

## Problem

`ConversationCompactor` lives in `dev.talos.core.context`, but its failure-path
warning imported `dev.talos.runtime.policy.SafeLogFormatter` only to render an
LLM compaction exception message.

That created a core-to-runtime dependency for a fallback diagnostic. The
compactor's behavior is simple: if summarization fails, keep the existing sketch
unchanged. It does not need runtime protected-content policy ownership for that
behavior.

## Goal

Remove the `ConversationCompactor -> SafeLogFormatter` boundary edge without
changing conversation compaction behavior or moving runtime policy classes.

## Non-Goals

- No `SafeLogFormatter` package move.
- No `ProtectedContentPolicy` package move.
- No conversation compaction prompt, truncation, fallback, or sketch behavior
  change.
- No `ConversationManager` behavior change.
- No baseline growth.
- No broad logging-policy redesign.

## Implementation Summary

- Added an ownership regression test proving `ConversationCompactor` does not
  reference `dev.talos.runtime.policy.SafeLogFormatter` in source or in the
  architecture baseline.
- Removed the `SafeLogFormatter` import from `ConversationCompactor`.
- Replaced the compaction failure warning with a content-free diagnostic that
  reports only the exception class name.
- Removed the matching baseline entry from
  `config/architecture-boundary-baseline.txt`.

## Architecture Metadata

Capability:

- Conversation history compaction and sketch preservation.

Operation(s):

- Static ownership boundary cleanup.

Owning package/class:

- `dev.talos.core.context.ConversationCompactor`.

New or changed tools:

- None.

Risk, approval, and protected paths:

- Risk level: low. The only runtime behavior changed is one warning emitted
  when the compaction LLM call fails.
- Approval behavior: not changed.
- Protected path behavior: not changed.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not changed.
- Evidence obligation: focused source ownership test plus the real repository
  architecture scanner.
- Verification profile: focused conversation compaction tests, architecture
  validation, diff checks, and full Gradle `check`.
- Repair profile: not changed.

Outcome and trace:

- Outcome/truth warnings: not changed.
- Trace/debug fields: not changed.

Refactor scope:

- Allowed: remove one core-context runtime-policy logging edge.
- Forbidden: move runtime policy classes or change compaction semantics.

## TDD Evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.ConversationCompactionTest*conversationCompactorDoesNotDependOnRuntimeLogPolicy" --no-daemon
```

Result: failed because `ConversationCompactor` and the architecture baseline
still referenced `dev.talos.runtime.policy.SafeLogFormatter`.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.ConversationCompactionTest*conversationCompactorDoesNotDependOnRuntimeLogPolicy" --no-daemon
```

Result: passed after removing the runtime-policy formatter import, replacing
the compaction failure warning with an exception-class diagnostic, and removing
the baseline entry.

Focused behavior coverage:

```powershell
.\gradlew.bat test --tests "dev.talos.core.context.ConversationCompactionTest" --no-daemon
```

Result: passed.

Architecture scanner:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
```

Result: passed with `57` current and baselined forbidden references, `0` new
violations, and `0` stale entries.

Full check:

```powershell
.\gradlew.bat check --no-daemon
```

Result: passed.

## Acceptance Criteria

- `ConversationCompactor` no longer references
  `dev.talos.runtime.policy.SafeLogFormatter`.
- The matching baseline entry is removed.
- `validateArchitectureBoundaries` passes with no new or stale violations.
- Focused conversation compaction behavior tests pass.
- Full `check` passes.
- No generated audit artifacts are committed.

## Result

Acceptance criteria satisfied.

## Work-Test Cycle Notes

Inner dev loop. No version bump. No candidate packet. No live audit.

## Known Risks

- The affected warning no longer includes the original exception message. This
  is intentional because the compactor fallback only needs to report that
  compaction failed and preserved the existing sketch.
- Other `SafeLogFormatter` baseline edges remain. They should continue to be
  evaluated one at a time.

## Known Follow-Ups

- Continue burn-down against the remaining baseline using one-edge tickets.
- Reconsider `SafeLogFormatter` ownership only after deciding where
  `ProtectedContentPolicy` belongs.
