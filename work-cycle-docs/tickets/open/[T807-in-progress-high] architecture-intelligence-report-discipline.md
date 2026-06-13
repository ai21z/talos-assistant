# T807 - Architecture Intelligence Report Discipline

Status: in-progress - hardening implemented, awaiting owner review
Severity: high
Release gate: no - pre-Wave-5 architecture evidence tooling
Branch: feature/wave4-ergonomics
Created/updated: 2026-06-13
Owner: unassigned

## Problem

Wave 5 should not be a cosmetic class-moving exercise. Before changing
architecture boundaries, Talos needs deterministic evidence for package cycles,
manual Java wiring, object lifecycle ownership, method-call hotspots,
static/global state, approval/tool execution paths, trace/privacy paths, quality
overlays, toolchain readiness, and a recommended Wave 5 sequence.

Without lifecycle ownership evidence, moving classes can create stale state,
privacy leaks, trace bleed, broken approval memory, bad test isolation, or
accidental shared mutable state.

## Required Behavior

1. Add a report-only Gradle task:
   `.\gradlew.bat architectureIntelligenceReport --no-daemon`.
2. Generate deterministic Markdown and JSON under
   `build/reports/talos/architecture-intelligence/current/`.
3. Produce one readable developer narrative plus topic reports for:
   package boundaries/cycles, manual DI/composition, lifecycle ownership,
   method-call hotspots, static/global/thread-local state, approval/tool
   execution ownership, trace/privacy ownership, coverage/Qodana overlays, Wave
   5 sequencing, jdeps cross-check, and toolchain/Qodana readiness.
4. Use ArchUnit as the primary fact engine for package/class dependencies,
   method calls, constructor calls, field/static access, and incoming/outgoing
   access counts.
5. Use source scanning only for supplemental hints that ArchUnit cannot expose
   directly.
6. Read Qodana summary state only; do not run or modify Qodana.
7. Report missing, stale, or incomplete optional evidence honestly.

## Non-Goals

- No Wave 5 refactor.
- No Talos runtime, CLI, product API, or release-gate behavior changes.
- No Qodana task, config, version, mode, or evidence mutation.
- No CodeQL, JFR custom event, Error Prone, NullAway, or JSpecify integration.
- No committed generated `build/` reports unless separately requested.

## Architecture Metadata

- Capability: architecture intelligence reporting for Wave 5 planning.
- Operation(s): static report generation from bytecode, source files, local
  summary JSON, and local tool availability checks.
- Owning package/class: `src/test/java/dev/talos/architecture/intelligence`.
- New or changed tools: new Gradle reporting task
  `architectureIntelligenceReport`.
- Risk, approval, and protected paths: no runtime workspace mutation; reads only
  repository source and generated local build summaries.
- Checkpoint, evidence, verification, and repair: deterministic Markdown/JSON
  reports under ignored `build/reports/talos/architecture-intelligence/current/`.
- Outcome and trace: no product runtime outcome or trace behavior changes.
- Refactor scope: test/reporting code plus Gradle task and this ticket only.

## Acceptance Criteria

- `.\gradlew.bat architectureIntelligenceReport --no-daemon` passes.
- All required Markdown reports exist under
  `build/reports/talos/architecture-intelligence/current/`.
- All required JSON data files exist under
  `build/reports/talos/architecture-intelligence/current/data/`.
- Generated payloads contain no wall-clock timestamp.
- Optional Qodana, jdeps, Docker, CodeQL, and JFR/JMC readiness is reported as
  available, unavailable, stale, incomplete, or follow-on without mutating those
  tools.
- The main report tells a coherent Wave 5 story with explicit uncertainty where
  ownership is inferred.
- Report-value contracts reject noisy or misleading output:
  - manual DI/composition rows are deduplicated by top-level class and label
    outbound construction sites honestly;
  - method hotspot rows are deduplicated by top-level class;
  - lifecycle ownership classifies the known Wave 5 control objects into
    reviewed scopes and still labels unresolved ownership honestly;
  - static/global state output filters compiler synthetic enum/switch fields,
    harmless enum constants, and non-state static declarations;
  - coverage/Qodana evidence validates the referenced raw artifacts and reports
    missing or stale inputs directly;
  - jdeps output is classified as complete, partial-classpath, failed, or
    unavailable rather than treating `not found` as clean evidence;
  - Wave 5 sequence scores are explained as an unnormalized priority index with
    machine-readable component breakdowns;
  - approval/tool and trace/privacy thematic rows include bounded source
    evidence hits instead of keyword-only ownership claims.

## Verification

```powershell
.\gradlew.bat architectureIntelligenceReport --no-daemon
.\gradlew.bat test --tests "dev.talos.architecture.*" --no-daemon
git diff --check
git status --short
```

## Current Evidence

- Red contract check: `.\gradlew.bat test --tests "dev.talos.architecture.intelligence.ArchitectureIntelligenceReportContractTest" --no-daemon`
  failed before implementation because `ArchitectureIntelligenceReporter` did not exist.
- Green focused check: `.\gradlew.bat test --tests "dev.talos.architecture.intelligence.ArchitectureIntelligenceReportContractTest" --no-daemon` passed.
- Report command: `.\gradlew.bat architectureIntelligenceReport --no-daemon` passed and generated the full Markdown/JSON suite.
- Determinism check: forced `architectureIntelligenceReport --rerun-tasks --no-daemon` produced byte-stable report files.
- Architecture suite: `.\gradlew.bat test --tests "dev.talos.architecture.*" --no-daemon` passed.
- Qodana was not run or modified; only existing `build/reports/talos/qodana-summary.json` was consumed as read-only input.
- Hardening review found the first implementation is not complete enough to
  close:
  - lifecycle rows were `50/50` `UNKNOWN`;
  - manual DI and hotspot reports duplicated top-level classes;
  - static/global output contained compiler synthetic fields such as `$VALUES`
    and `$SwitchMap`;
  - coverage/Qodana report was summary-only instead of an overlay;
  - Qodana summary referenced raw SARIF that was absent locally;
  - jdeps emitted `not found`, requiring partial-classpath classification.

## Hardening Evidence

- Red value-contract checks failed against the first implementation for:
  duplicate manual DI/hotspot rows, all-unknown lifecycle output, static-state
  source noise, summary-only Qodana handling, and missing machine-readable jdeps
  output.
- Green focused hardening check:
  `.\gradlew.bat test --tests "dev.talos.architecture.intelligence.ArchitectureIntelligenceReportContractTest" --no-daemon`.
- Green public report command:
  `.\gradlew.bat architectureIntelligenceReport --no-daemon`.
- Green architecture suite:
  `.\gradlew.bat test --tests "dev.talos.architecture.*" --no-daemon`.
- Forced rerun determinism check:
  `.\gradlew.bat architectureIntelligenceReport --rerun-tasks --no-daemon`
  followed by SHA-256 comparison of all files under
  `build/reports/talos/architecture-intelligence/current/` produced
  `HASH_STABLE`.
- Current report-value spot checks:
  - lifecycle rows now include `APPLICATION`, `WORKSPACE`, `SESSION`, `TURN`,
    `TOOL_LOOP`, `TOOL_CALL`, and `TRACE`;
  - manual DI and hotspot JSON reports have zero duplicate top-level class rows;
  - static/global/thread-local report has zero compiler-synthetic rows and
    retains ThreadLocal ownership evidence;
  - Qodana overlay reports `RAW_ARTIFACT_MISSING` for the currently absent raw
    SARIF instead of trusting stale summary provenance;
  - jdeps writes `data/jdeps-cross-check.json` and currently classifies the
    advisory result as `PARTIAL_CLASSPATH`.
- Score explainability hardening:
  - red contract checks failed against the previous score UX because the Wave 5
    sequence JSON had only an ambiguous `score` field and no formula or
    component breakdown;
  - green focused contract check now requires `scoreModel`, pinned score-model
    constants, `priorityIndex`, per-row `scoreBreakdown`, and sum invariants
    without freezing today's `AssistantTurnExecutor` snapshot score;
  - `11-wave5-ticket-sequence.md` now shows `Hotspot`, `Lifecycle`,
    `Approval`, `Trace/privacy`, and `Priority index` columns and explicitly
    labels the value an unnormalized priority index;
  - approval/tool and trace/privacy maps now include capped source evidence
    hits with file, line, signal, and snippet, preferring behavioral source
    lines over imports.

## Owner Review State

This ticket intentionally remains in `open/` as `in-progress` until the owner
reviews the hardened report suite. Do not move it to `done/` or commit it
without owner approval.
