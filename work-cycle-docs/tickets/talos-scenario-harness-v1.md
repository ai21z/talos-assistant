# [done] Ticket: V1 Scenario Harness And Quality Lane

Date: 2026-04-24
Priority: high
Status: done
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `docs/new-architecture/talos-harness-plan.md`
- `docs/new-architecture/talos-harness-source-of-truth.md`

## Why This Ticket Exists

The architecture direction is only credible if Talos can prove its behavior
through deterministic scenarios.

The repo already has meaningful harness code:
- `src/e2eTest/java/dev/talos/harness/ScenarioRunner.java`
- JSON scenario resources under `src/e2eTest/resources/scenarios/`
- strict/friendly tool-resolution paths
- workspace fixtures and approval-policy control

So this ticket is not about inventing a harness from zero.

It is about promoting the existing scenario machinery into the primary
runtime-quality scoreboard for V1.

## Problem

Today the harness exists, but it is still closer to a useful testing mechanism
than a first-class architecture/evidence layer.

Current gaps:
- scenario coverage is still selective and incident-driven
- architecture claims do not map cleanly to a named scenario set
- strict measurement mode exists, but its use is not yet a stable quality lane
- scenario results are not yet the central evidence for runtime-discipline claims

Without this, architecture work will drift back into:
- subjective manual transcript review
- “Talos feels better now” language
- fixes landing without a stable regression contract

## Goal

Make deterministic scenario evaluation the first-class evidence lane for Talos
runtime quality.

## Desired End State

Talos should have a small, explicit scenario pack that proves the core local
operator promises:

1. inspect before mutate
2. approval denial closes truthfully
3. mutation claims match actual tool outcomes
4. read-only evidence answers do not silently fabricate
5. repeated failures stop or degrade cleanly
6. strict mode reveals raw model/runtime weakness without user-mode cushions

## Scope

### In scope

- curate a V1 scenario set tied to architecture invariants
- make scenario names/coverage understandable to reviewers
- ensure strict mode is available where it adds evaluation value
- thread scenario evidence into the existing quality/reporting story
- document which scenarios prove which runtime claims

### Out of scope

- browser automation
- shell/test-runner verification
- multi-agent evaluation
- benchmark theater or public-score chasing
- replacing unit tests

## Proposed Work

### 1. Curate a V1 scenario pack

Start with a small named set, for example:

- read-only workspace explain remains read-only
- inspect-first analysis reads evidence before answering
- explicit file fix reaches approval and mutates only after approval
- denied mutation closes truthfully with no applied-work claim
- partial mutation is summarized truthfully
- repeated failure does not spiral forever
- strict mode exposes alias rescue / malformed tool behavior

This means curate and map the existing scenario set first, not invent a second
scenario universe from scratch.

The repo already contains useful scenario assets:
- existing JSON scenarios under `src/e2eTest/resources/scenarios/`
- strict/friendly harness support in `ScenarioRunner`
- executor-path harness support that drives `AssistantTurnExecutor.execute(...)`

The job here is to:
- map current scenarios to architecture/runtime invariants
- identify the gaps
- promote the subset that becomes the reviewer-facing V1 pack
- add only the missing scenarios needed to complete that pack

### 2. Separate friendly-mode and strict-mode evidence

Friendly mode tells us whether Talos works for users.
Strict mode tells us how much hidden repair/cushioning the runtime needed.

Both are useful, but they answer different questions and should not be mixed.

### 3. Tie scenario coverage to architecture claims

Every serious runtime-discipline claim should have at least one named scenario
that proves it.

### 4. Improve reviewer visibility

Scenario results should be easier to interpret in summaries/reports than raw
JUnit or transcript output alone.

## Likely Files / Areas

- `src/e2eTest/java/dev/talos/harness/*`
- `src/e2eTest/java/dev/talos/harness/ScenarioRunner.java`
- executor-path scenario tests that drive `AssistantTurnExecutor.execute(...)`
- `src/e2eTest/resources/scenarios/*`
- `src/e2eTest/resources/fixtures/*`
- `build.gradle.kts`
- `docs/` architecture/evidence docs if needed

## Open Design Questions

1. Should strict-mode scenario execution be a separate Gradle task or remain a
   dimension inside the existing lane?
2. How many scenarios are enough for the initial V1 pack before coverage starts
   becoming noisy instead of useful?
3. Should scenario summary data be written as a first-class Talos JSON summary,
   or should the current E2E summary be enriched instead?

## Test / Verification Plan

### Required

- scenario pack runs deterministically in CI/local quality workflow
- at least one strict-mode scenario is present and documented
- named scenarios cover the current runtime-trust invariants

### Evidence / Reporting

- scenario results are visible in the existing quality evidence flow
- reviewers can tell which architecture claim each scenario proves

## Acceptance Criteria

- Talos has a documented V1 scenario pack, not just ad hoc regressions
- scenario evidence is the primary proof for runtime-discipline claims
- strict vs friendly evaluation is explicit
- scenario results are reviewable without reading raw transcripts first
