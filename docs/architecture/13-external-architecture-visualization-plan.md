# External Architecture Visualization Plan

Branch: `feature/archunit-architecture-guards`
Status: human-run tool plan (no code changes)

## Purpose

Define exactly what to inspect visually in an external architecture tool so a
human reviewer can confirm or challenge the findings already produced by the
ArchUnit guards and the report-only discovery/cycle/spine passes
(`docs/architecture/11` and `12`). This is a checklist for a manual session, not
an implementation task and not a CI step.

This plan does not change code, does not add a build dependency, and does not
replace the in-repo ArchUnit reports. It is a cross-check.

## Tool choice

Primary: **Sonargraph Explorer** (free; reads compiled Java bytecode, gives
package dependency matrices, cycle detection, fan-in/fan-out, and complexity
lists). Acceptable alternatives if Sonargraph is unavailable:

- **IntelliJ IDEA** → *Analyze → Dependencies* / *Dependency Matrix* (DSM) and
  the diagram view (built-in, fastest to start).
- **Structure101** (commercial) - strongest for cycle/slice visualization.
- **jQAssistant + Neo4j** - query-driven, good for reproducible exports.

Whatever tool is used, point it at the **compiled production classes only**
(`build/classes/java/main`), not tests, so the picture matches the ArchUnit
`DoNotIncludeTests` scope. Build first:

```powershell
.\gradlew.bat classes --no-daemon
```

Expected baseline scale (from the discovery report, for sanity-checking the
import): 812 imported classes incl. inner, 534 distinct top-level classes,
~2658 deduped top-level `dev.talos` edges across 9 top-level packages.

## 1. Packages to inspect

| Package | Top-level classes | Why inspect |
|---------|:-----------------:|-------------|
| `dev.talos.cli.modes` | (part of cli 103) | Home of the orchestration hub `AssistantTurnExecutor`; CLI composition cycle suspect |
| `dev.talos.runtime.policy` | (part of runtime 257) | Policy ownership target; control-spine knot |
| `dev.talos.runtime.toolcall` | (part of runtime 257) | Tool-call loop stages; mutual cycles with policy/verification |
| `dev.talos.runtime.verification` | (part of runtime 257) | Verifier breadth; false-success prevention |
| `dev.talos.core.context` | (part of core 90) | Context handling; check CLI-independence |
| `dev.talos.tools` | 33 | Confirm tools do not depend upward (runtime/cli) |
| `dev.talos.spi` | 27 | Confirm the seam has zero upward edges |

Also load (context for the above, do not deep-dive): `dev.talos.safety` (expect 0
out-edges), `dev.talos.runtime` root, `dev.talos.runtime.trace`.

## 2. Classes to inspect

Use these as graph focus nodes. Expected metrics (from the spine/discovery
reports) are listed so the reviewer can confirm the tool agrees:

| Class | Package | Expected fan-out | Expected fan-in | Watch for |
|-------|---------|:---:|:---:|-----------|
| `AssistantTurnExecutor` | `cli.modes` | 63 | 5 | god-object; heavy calls into `repl.Context` |
| `ToolCallLoop` | `runtime` | 22 | 45 | central hub; balanced is OK |
| `ToolCallRepromptStage` | `runtime.toolcall` | 18 | 1 | complexity vs. contained fan-in |
| `CurrentTurnPlan` | `runtime.turn` | 9 | 18 | should stay thin aggregate |
| `TaskContractResolver` | `runtime.task` | 8 | 24 | should stay thin contract |
| `ToolSurfacePlanner` | `runtime.toolcall` | 12 | 2 | should stay single-purpose |
| `EvidenceObligationVerifier` | `runtime.policy` | 5 | 5 | contained verifier |
| `ExecutionOutcome` | `cli.modes` | 30 | 2 | "result" type doing too much |
| `ConversationManager` | `core.context` | 5 | 9 | should stay contained, CLI-free |

If the tool's numbers differ materially from these, that gap is itself a finding
(different metric definition, or the build is stale - rebuild and recheck).

## 3. Questions to answer

For each, the in-repo evidence-based expectation is noted; the visual session
should confirm or refute it.

1. **Which packages form cycles?**
   Expected top-level: only `core ↔ tools`. Expected intra-`runtime`: a large
   16-subpackage SCC. Expected intra-`cli`: `modes ↔ prompt ↔ repl`. Expected
   intra-`core`: `context↔llm`, `rerank↔retrieval`, `extract↔privacy`,
   `(root)↔security`.
2. **Which classes have highest fan-out?**
   Expected: `cli.repl.TalosBootstrap` (88), `AssistantTurnExecutor` (63),
   `runtime.TurnProcessor` (63), `core.rag.RagService` (38).
3. **Which classes have highest fan-in?**
   Expected: `runtime.task.TaskContract` (66), `tools.ToolCall` (66),
   `spi.types.ChatMessage` (60), `core.Config` (59).
4. **Is policy moving out of `AssistantTurnExecutor`?**
   Expected: not yet - fan-out 63 indicates it is still a warehouse. Look for
   policy logic that belongs in `runtime.policy`. This is the headline question.
5. **Do tools depend upward?**
   Expected: NO. `tools → runtime` and `tools → cli` must be empty (both are hard
   ArchUnit guards). `tools → core` (38) is allowed and expected.
6. **Does core remain CLI-independent?**
   Expected: YES. `core → cli` must be 0 (hard guard). Confirm visually.
7. **Are command-execution surfaces isolated?**
   Inspect `runtime.command` coupling: confirm command execution flows through
   bounded profiles and is reached via the tool-call loop, not wired directly
   into `cli`. Check `runtime.command` ↔ `runtime.trace`/`policy` edges.

## 4. Screenshots / exports to collect

Save under `local/manual-testing/<audit-id>/architecture-visuals/` (outside the
tracked tree; do not commit raw tool exports). Name files deterministically.

1. **`package-dependency-matrix.png`** - full `dev.talos.*` DSM. Confirm the
   lower-left triangle is empty for `safety`/`spi` rows.
2. **`assistantturnexecutor-class-graph.png`** - outgoing class graph for
   `AssistantTurnExecutor`, depth 1.
3. **`runtime-policy-graph.png`** - `runtime.policy` internal + external edges.
4. **`runtime-toolcall-graph.png`** - `runtime.toolcall` graph; highlight cycles
   to `policy`/`verification`.
5. **`core-context-graph.png`** - `core.context` graph; confirm no `cli` edges.
6. **`tools-graph.png`** - `dev.talos.tools` graph; confirm no upward edges.
7. **`top-complexity-list.csv`** (or `.png`) - top fan-out/fan-in/complexity
   table for cross-checking section 2/3 numbers.
8. **`cycles-list.png`** - the tool's cycle report at package + subpackage level.

## 5. How to interpret findings

Map every visual observation to one severity. Anchor to the documented layering
and the existing hard guards.

**High severity**
- Any new edge that violates a current hard guard (e.g. `core → cli`,
  `tools → cli`, `tools → runtime`, `safety → anything`, `spi → upper`,
  `runtime.policy → cli`). This means the build is broken or the export is stale -
  reconcile with ArchUnit immediately.
- New cross-layer top-level cycles beyond the known `core ↔ tools`.
- Growth of `AssistantTurnExecutor` fan-out beyond ~63, or new policy logic
  accreting there.
- Command-execution surface wired directly into `cli` (bypassing the loop).

**Medium severity**
- Confirmed intra-`runtime` SCC and the control-spine knots
  (`policy↔toolcall`, `toolcall↔verification`, `task↔verification`).
- The `cli.modes ↔ cli.prompt ↔ cli.repl` cycle.
- `ExecutionOutcome` or `StaticTaskVerifier` breadth growth.
- Two-way `runtime.trace` coupling to policy/verification (audit/redaction surface).

**Low severity**
- Localized core pairs (`context↔llm`, `rerank↔retrieval`, `extract↔privacy`).
- High fan-in on shared records/contracts.
- Cosmetic graph clutter from inner classes.

**Acceptable coupling (do not file tickets)**
- `tools → core` (38), `runtime → tools` (151), `runtime → core` (64),
  `cli → runtime/core` - all are correct downward/invocation directions.
- High fan-in on `TaskContract`, `ToolCall`, `ChatMessage`, `Config`.
- `api`/`app` reaching multiple layers (seam + composition root, unconstrained
  by design).
- `safety`/`spi` having only inbound edges.

## 6. How findings become tickets

1. **Reconcile first.** If a visual finding contradicts an ArchUnit hard guard,
   it is an evidence/staleness problem, not a new ticket - rebuild and re-export
   before believing the tool.
2. **Classify** each genuine finding by the severity rubric above.
3. **De-duplicate** against `docs/architecture/12` (top-10 refactor candidates)
   and `docs/architecture/11` (report-only findings). Most visuals should
   *confirm* existing findings, not create new ones.
4. **File only net-new or higher-confidence findings.** Each ticket records:
   target class/package, the visual evidence file, severity, why it matters, the
   suggested direction, and priority - matching the schema already used in doc 12.
5. **Promotion to a hard guard** stays governed: a boundary only becomes an
   ArchUnit guard after its edge count is driven to zero by a real refactor, and
   adding a matching `build.gradle.kts` regex entry is a separate, approval-gated
   infrastructure change (per `.github/copilot-instructions.md`).
6. **Do not let the visual session mutate code.** It is read-only evidence
   gathering; refactors go through the normal work-test cycle.

## Cross-reference

- Hard guards + report-only findings: `docs/architecture/11-architecture-guardrails.md`
- Risk evaluation + top-10 refactors + scorecard: `docs/architecture/12-current-architecture-risk-report.md`
- In-repo machine reports (regenerated by `dev.talos.architecture.*` tests):
  `build/reports/talos/architecture/{architecture-discovery,architecture-cycle,harness-spine-access}-report.md`
