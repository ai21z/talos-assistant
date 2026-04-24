# 29. Talos V1 Scenario Pack

**Date:** 2026-04-24  
**Purpose:** define the curated V1 scenario pack and map it to the runtime
discipline claims Talos wants to prove.  
**Status:** first curation pass based on the existing harness and scenario set.

---

## 1. Why this document exists

Talos already has meaningful deterministic harness machinery:

- JSON-backed scenario resources under `src/e2eTest/resources/scenarios/`
- harness runners in `src/e2eTest/java/dev/talos/harness/`
- strict vs friendly measurement mode
- executor-path scenarios that drive `AssistantTurnExecutor.execute(...)`
- persistence/replay scenarios

That is enough to start making architecture claims measurable.

But the existing scenario set was assembled incrementally, mostly from concrete
runtime regressions. It is useful, but it is not yet a clearly curated V1 pack.

This document defines that pack.

---

## 2. What the V1 scenario pack is for

The V1 scenario pack should prove the core local-operator promises:

1. inspect before mutate
2. read-only requests remain read-only
3. explicit mutations remain approval-gated
4. denied mutations close truthfully
5. mutation summaries reflect real outcomes
6. grounded analysis is based on actual file evidence
7. strict measurement mode exposes raw tool/runtime weakness without removing
   user-mode cushions from the normal runtime
8. persistence and replay do not corrupt history semantics

The V1 pack is not meant to prove everything Talos can ever do.
It is meant to prove the bounded, trustworthy local-operator behavior Talos
needs for V1.

---

## 3. Current harness structure

The existing harness naturally falls into four layers:

### A. JSON scenario pack

Primary reviewer-facing scenarios. These are the clearest candidates for the
V1 pack because they are named, resource-backed, and already surfaced in the
E2E summary/reporting lane.

Current JSON scenarios:

- `01-read-only-repo-question.json`
- `02-single-safe-file-edit.json`
- `03-off-scope-mutation-warning.json`
- `04-not-found-recovery.json`
- `05-approval-denied.json`
- `06-approval-remembered.json`
- `07-replay-turn-log-fallback.json`
- `08-persistence-history-correctness.json`
- `09-read-only-workspace-no-unsolicited-mutation.json`
- `10-selector-mismatch-grounded.json`
- `11-partial-mutation-summary-truthful.json`
- `12-repeated-missing-path-stops-at-loop-cap.json`
- `13-streaming-no-tool-grounding-visible.json`

### B. Executor-path scenarios

These matter because they are the seam that actually proves
`AssistantTurnExecutor` behavior, not just `ToolCallLoop` behavior.

Primary files:

- `ExecutorScenarioTest.java`
- executor-path cases inside `JsonScenarioPackTest.java`

These scenarios prove executor-layer truth/grounding behavior that the plain
harness seam does not.

### C. Strict-mode scenarios

These are not primarily user-mode behavior checks. They are measurement checks.

Primary file:

- `StrictModeScenariosTest.java`

These scenarios prove that strict mode reveals raw model/runtime weakness
instead of silently benefiting from user-mode repair behavior.

### D. Legacy/base deterministic scenarios

Primary file:

- `Phase0ScenariosTest.java`

These are still useful as low-level deterministic coverage of harness/tool-loop
mechanics, but they are not all architecture-facing V1 reviewer scenarios.

---

## 4. Curated V1 scenario pack

### 4.1 Primary reviewer-facing JSON scenarios

These are the scenarios that should define the first V1 pack:

| Scenario | What it proves |
|---|---|
| `01-read-only-repo-question` | workspace explanation stays read-only and grounded in fixture facts |
| `02-single-safe-file-edit` | a narrow approved edit mutates only the intended file content |
| `03-off-scope-mutation-warning` | off-scope mutation risk is surfaced before approval |
| `04-not-found-recovery` | the runtime can recover from wrong-path/tool-input drift without derailing the turn |
| `05-approval-denied` | approval denial blocks the write and preserves the original file |
| `06-approval-remembered` | remembered approval works predictably within the session |
| `07-replay-turn-log-fallback` | replay restores only good turns and avoids error residue |
| `08-persistence-history-correctness` | persisted history stores stripped assistant text, not UI chrome |
| `09-read-only-workspace-no-unsolicited-mutation` | read-only workspace inspection rejects unsolicited mutation attempts |
| `10-selector-mismatch-grounded` | grounded analysis reports real selector mismatch from actual files |
| `11-partial-mutation-summary-truthful` | partial-success mutation summaries reflect real outcomes only |
| `12-repeated-missing-path-stops-at-loop-cap` | repeated failing tool turns stop at the loop cap instead of spiraling indefinitely |
| `13-streaming-no-tool-grounding-visible` | streaming no-tool fabricated evidence answers are visibly marked ungrounded |

### 4.2 Supporting executor-path scenarios

These are part of the V1 evidence story, but they are supporting scenarios
rather than the main JSON pack.

| Scenario / file | What it proves |
|---|---|
| `ExecutorScenarioTest.T5` | executor-layer false-mutation annotation/truth handling works end-to-end |
| executor-path cases in `JsonScenarioPackTest` | JSON resources can exercise `AssistantTurnExecutor`, not just the raw loop |

### 4.3 Supporting strict-mode scenarios

These are measurement scenarios, not user-mode confidence scenarios.

| Scenario / file | What it proves |
|---|---|
| strict alias rescue difference | friendly mode cushions non-canonical tool naming; strict mode does not |
| strict redundant-read difference | friendly mode suppresses redundant reads; strict mode exposes raw duplicate behavior |

### 4.4 Supporting base/mechanic scenarios

`Phase0ScenariosTest` remains valuable, but it should be treated as foundational
mechanic coverage, not the main reviewer-facing V1 pack.

It proves:

- core file-write and edit mechanics
- missing-path failures
- unknown-tool resilience
- grep/list_dir basics
- multi-tool turns

That is important, but it is a lower-level testing layer.

---

## 5. Claim-to-scenario mapping

This is the current first-pass mapping from V1 architecture claims to evidence.

| Runtime / architecture claim | Primary evidence |
|---|---|
| Read-only questions remain read-only | `01`, `09` |
| Inspect-first analysis is grounded in real files | `01`, `10` |
| Narrow file edits mutate only what was requested | `02` |
| Off-scope writes surface a warning before approval | `03` |
| Path/input recovery is possible without total derailment | `04` |
| Approval denial preserves files | `05` |
| Session approval memory behaves predictably | `06` |
| Session replay does not poison restored memory | `07` |
| Persisted memory stores conversation, not Talos UI chrome | `08` |
| Partial mutation summaries are truthful | `11` |
| Repeated failing tool turns stop at a bounded loop cap | `12` |
| Streaming no-tool evidence answers are visibly marked ungrounded | `13` |
| Executor-layer false mutation claims are caught | `ExecutorScenarioTest.T5` |
| Strict mode reveals raw alias/tool weakness | `StrictModeScenariosTest` |

---

## 6. What is still missing from the V1 pack

The first-pass curated pack is strong, but not complete.

Notable remaining gaps:

1. **Future explicit phase policy**
   - once phase policy lands, the pack will need at least one scenario that
     proves writes cannot execute during inspect/verify

2. **Future static post-apply verifier**
   - once the verifier lands, the pack will need at least one scenario that
     proves “applied” and “verified” are distinct outcomes

---

## 7. Practical guidance for ticket 1

When implementing the V1 scenario-harness ticket, do not:

- replace the current harness
- create a second scenario framework
- assume every existing scenario belongs in the reviewer-facing V1 pack

Do:

- preserve the current harness layers
- make the curated V1 pack explicit
- improve reviewer visibility of what each scenario proves
- keep strict-mode and executor-path evidence visible as supporting layers

---

## 8. Summary

Talos does not need a brand new harness.

It needs a curated, explicit V1 scenario pack built from the harness it already
has:

- JSON scenarios for reviewer-facing confidence
- executor-path scenarios for executor truth behavior
- strict-mode scenarios for raw measurement honesty
- low-level deterministic scenarios for mechanic coverage

That is the correct first step before phase policy, verifier work, or broader
runtime architecture changes.
