# T754 - Bare Tool-JSON Pattern Backtracking Hardening

Status: done - completed in wave 2; see completion evidence section
Severity: medium
Release gate: no (latent DoS hardening; behavior-preserving)
Branch: feature/wave2-trust-surface
Created/updated: 2026-06-11
Owner: external assistant

## Problem

The bare tool-call JSON detection regex contained the nested-quantifier
alternation `(?:[^{}]*|\{[^{}]*\})*`, a catastrophic-backtracking shape: a
long candidate without a closing brace forces the engine to explore an
exponential number of partitions of the non-brace run before failing. The
pattern existed in TWO byte-identical copies and runs on every model
response:

- `src/main/java/dev/talos/runtime/ToolCallParser.java` (text-fallback
  parse pass 2 + `containsToolCalls` + `stripToolCalls`)
- `src/main/java/dev/talos/tools/ToolProtocolText.java:30-31`
  (`stripToolCalls` runs on every displayed answer; `ToolCallStreamFilter`
  buffers up to 2 MB through these paths)

Source: 2026-06-10 top-tier evaluation, roadmap item W2.9
(`work-cycle-docs/research/talos-top-tier-evaluation-and-roadmap-20260610.md`).

## Architectural Hypothesis

The duplication, not just the quantifier shape, is the structural problem:
two detection surfaces (runtime parser, protocol stripper) can drift apart.
Fix the backtracking once and give the pattern a single owner.

## Architecture Metadata

Capability: tool-call text-fallback parsing / protocol text cleanup
Operation(s): n/a (regex hardening, no tool behavior change)
Owning package/class: `dev.talos.tools.ToolProtocolText` (single owner);
`dev.talos.runtime.ToolCallParser` references it
New or changed tools: none
Risk, approval, and protected paths: n/a
Checkpoint, evidence, verification, and repair: n/a
Outcome and trace: n/a
Refactor scope: the two named files plus their tests; no parser-pass
restructuring (the Jackson pass-2b alternative stays a future option)

## Required Behavior

1. Convert the alternation to possessive quantifiers so failure is linear,
   while accepting exactly the same language (existing bare-JSON tests pin
   the positive cases, including one-level nested argument objects).
2. Make `ToolProtocolText` the single owner of the pattern; the runtime
   parser references it via an accessor.
3. Adversarial regression tests with preemptive timeouts proving
   parse/containsToolCalls/stripToolCalls fail fast on unclosed candidates.

## Non-Goals

- No replacement of pass 2 with a Jackson-only scanner (more invasive;
  pass 2b already covers brace-in-string payloads for raw-JSON responses).
- No change to the recognized name-key alias set or the talos. prefix gate.

## Tests

- `ToolCallParserTest.adversarialUnclosedBareJsonFailsFast` — 200k-char
  unclosed candidate under `assertTimeoutPreemptively(2s)`.
- `ToolCallParserTest.adversarialRepeatedOpenBraceFragmentsFailFast` —
  50k repeated `{"a":` fragments under the same timeout.
- `ToolProtocolTextTest.stripToolCallsSurvivesAdversarialUnclosedBareJson`
  and `...RepeatedOpenBraces` — display-path stripper survives, prose kept.
- `ToolProtocolTextTest.stripToolCallsStillRemovesBareJsonWithOneLevelNestedBraces`
  — equivalence pin for the possessive conversion.
- Existing bare-JSON tests (`parseBareJson`,
  `codeFencedJsonSuppressesBareJsonFallback`, `containsToolCallsDetectsBareJson`,
  lenient-JSON suite) unchanged and green.

## Acceptance Criteria

- Both pattern copies replaced by one possessive-quantifier owner.
- Adversarial inputs fail in linear time (timeout tests green).
- All existing parser/protocol tests pass unchanged.
- CHANGELOG `## [Unreleased]` gains a T754 entry.

## 2026-06-11 completion evidence

- Pattern now lives only in `ToolProtocolText.BARE_JSON_PATTERN`
  (exposed via `bareToolJsonPattern()`); `ToolCallParser` references it.
- Final form: `(?:[^{}]++|\{[^{}]*+\})*+`. Implementation note discovered
  during the work and documented in the javadoc: the first branch must be
  `++`, not `*+` — a zero-length first-branch iteration terminates the
  loop, and under a possessive outer loop there is no backtrack left to
  retry that iteration with the brace branch, which silently breaks
  one-level nested argument objects (caught by `parseBareJson` and the new
  nested-brace equivalence pin before commit).
- `gradlew test --tests ToolCallParserTest --tests ToolProtocolTextTest
  --tests ToolCallParserLenientJsonTest` green (77 tests).
