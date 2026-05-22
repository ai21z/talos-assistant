# [T374-done-high] Architecture Boundary Zero Baseline Closeout

Status: done
Priority: high
Date: 2026-05-23
Branch: `T374`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `T373`

## Scope

This is a closeout and evaluation ticket, not an implementation burn-down.

T374 confirms that the T334-T373 architecture-boundary ratchet reached a
steady-state zero baseline, records the ownership model established by the
ratchet, and selects the next hygiene lane. It does not add a new architecture
rule, move packages, change runtime behavior, or start a T374 refactor.

## Evidence Summary

- Base branch: `origin/v0.9.0-beta-dev`.
- Current head inspected: `9d1d956491c9fca46d276e3ef2d569413ea16f0d`.
- Latest merge: PR `#38`, `T373`, source metadata moved to SPI types.
- T373 beta push CI: run `#111`, `Beta Dev CI`, completed successfully.
- Local verification:
  - `.\\gradlew.bat validateArchitectureBoundaries --no-daemon`
  - result: passed.
- Current architecture report:
  - current forbidden references: `0`
  - baselined forbidden references: `0`
  - new forbidden references: `0`
  - stale baseline entries: `0`
- Known unrelated local state:
  - untracked prompt-debug evidence directory remains present and must not be
    committed:
    `UsersariszProjectsLOQloqj-clilocalmanual-testingtrue-pty-manual-20260520-r1artifactsprompt-debug/`

## Ratchet Result

The architecture baseline is now an empty debt ledger:

```text
# Talos architecture boundary ratchet baseline.
# Format: rule|path|source-reference
# This file records existing package-direction debt only. Do not add entries
# unless a ticket explicitly accepts the new edge and explains why.
```

The scanner is now a steady-state gate. A new forbidden reference is no longer
"one more known edge"; it is a build failure unless a ticket explicitly accepts
new debt and explains why.

Milestone sequence:

| Merge | PR | Branch | Baseline count after merge |
|---|---:|---|---:|
| `6a7aa95c` | `#5` | `T334-T340` | `59` |
| `2278ba36` | `#6` | `T341` | CI hard gate added |
| `752cd998` | `#7` | `T342` | `58` |
| `dfc71b63` | `#9` | `T344` | `56` |
| `8daccacd` | direct | `T345` | `56` |
| `81056572` | `#33` | `T368` | `12` |
| `b40544b7` | `#34` | `T369` | `11` |
| `14d4c4e0` | `#35` | `T370` | `8` |
| `59fab97c` | `#36` | `T371` | `4` |
| `014b90f8` | `#37` | `T372` | `1` |
| `9d1d9564` | `#38` | `T373` | `0` |

The missing counts in the table are not hidden work; they are the middle
burn-down tickets that followed the same ratchet rule. The important closeout
fact is that the baseline reached `0` and the validator now enforces that
state.

## Ownership Model After Ratchet

The current enforced package-direction model is:

- `runtime` and `core` must not depend on `cli`.
- `core` must not depend on `runtime`.
- `tools` must not depend on `runtime`.
- `engine` must not depend on `runtime`.
- `safety` must remain neutral and must not depend on Talos application
  layers.
- `spi` must not depend on `cli`, `core`, `runtime`, or `tools`.

The implementation model established by the burn-down is:

- CLI owns terminal adapters, rendering, and composition-facing UI wiring.
- Runtime owns turn execution, approval contracts, tool-loop orchestration, and
  runtime command/workspace behavior.
- Core owns retrieval, indexing, extraction, context packing, and neutral
  local-workspace decisions.
- Tools own tool contracts and local tool implementations that do not import
  runtime policy internals.
- Safety owns pure sink-safety, protected-path tokenization, sanitization, and
  dependency-free privacy facts.
- SPI owns provider-facing and storage-facing contracts plus neutral value
  types needed by those contracts.
- Engine adapters depend on SPI and neutral lower-level services, not runtime
  policy.

## What Zero Baseline Does Not Prove

Zero baseline is not a claim that the architecture is finished.

It proves only that the current source scanner finds no references violating
the six enforced package-direction rules. It does not prove:

- class sizes are healthy;
- dependency injection is complete;
- policy logic is well-factored;
- verifier/outcome ownership is clean;
- runtime behavior is release-ready;
- live audit coverage is complete;
- broader package cycles are impossible outside the current rules.

The correct use of the zero baseline is to stop re-burning the same import
debt and move to the next evidence-backed hygiene lane.

## Next Hygiene Lane Decision

The next lane should be verification and outcome truthfulness ownership.

Reason:

- T335 identified `StaticTaskVerifier`, `ExecutionOutcome`,
  `OutcomeDominancePolicy`, and `ToolCallRepromptStage` as high-risk
  truthfulness and repair-control concentration points.
- These areas directly affect false-success prevention, verifier evidence,
  repair prompts, and final-answer honesty.
- The package boundary ratchet reduced structural import debt, but it did not
  simplify the verification and outcome pipeline.
- Starting another package-move ticket now would be counter-chasing. The
  architecture gate is already at zero.

The first packet in the next lane should be a decision/inventory ticket, not a
large refactor:

```text
Verification And Outcome Truthfulness Ownership Decision
```

It should inspect:

- `StaticTaskVerifier`
- `ExecutionOutcome`
- `OutcomeDominancePolicy`
- `RepairPolicy`
- `ToolCallRepromptStage`
- existing verifier/outcome tests and E2E false-success scenarios

It should decide which first implementation slice is smallest while still
reducing real truthfulness risk. Likely candidates are a structured verifier
context extraction, a workspace-operation verifier extraction, or replacement
of repair-context string parsing with a structured repair plan. The decision
ticket must choose from source evidence, not from line counts alone.

## Acceptance Criteria

- The closeout records the current zero-baseline evidence.
- The ownership model is explicit enough to guide future package changes.
- The next hygiene lane is selected.
- No implementation ticket is started in T374.
- No generated artifacts or prompt-debug evidence directories are committed.

## Verification

```powershell
git diff --check
.\\gradlew.bat validateArchitectureBoundaries --no-daemon
.\\gradlew.bat check --no-daemon
```

Result:

- `git diff --check`: passed.
- `validateArchitectureBoundaries`: passed with `0` current violations, `0`
  baselined violations, `0` new violations, and `0` stale baseline entries.
- `check`: passed.
