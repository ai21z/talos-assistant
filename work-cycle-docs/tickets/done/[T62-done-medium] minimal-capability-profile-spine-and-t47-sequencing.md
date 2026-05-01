# [T62-done-medium] Minimal Capability Profile Spine And T47 Sequencing

Status: done
Priority: medium
Closed: 2026-05-02

## Evidence Summary

- Source: T54 prompt audit re-evaluation and architecture audit 07
- Date: 2026-04-30
- Existing related ticket:
  `work-cycle-docs/tickets/open/[T47-open-medium] improve-cross-file-web-repair-coherence-after-full-write.md`
- Design spec: `docs/superpowers/specs/2026-04-30-t54-control-plane-roadmap-design.md`

Observed problem:

- Static web verification and repair are useful, but web-specific concepts are
  spread through generic task, verifier, repair, outcome, and prompt code.
- T47 is valid but should not be the immediate next step before T55 through T61.
- Installed Talos 0.9.8 smoke run on 2026-04-30 showed natural BMI web app
  creation writing only `index.html`, then failing static verification because
  the workspace did not expose a small HTML/CSS/JS surface. That is a useful
  verifier result, but the static web profile should own the target-shape
  expectation instead of generic turn-control code.

T67 audit update, 2026-05-01:

- Summary:
  `local/manual-testing/t67-audit-20260501-143927/summary.md`
- Natural BMI creation now sometimes writes all three expected files
  (`index.html`, `styles.css`, `scripts.js`) but the verifier can still report
  that the workspace does not expose a small HTML/CSS/JS surface.
- The generated file set was also cross-file incoherent: JavaScript referenced
  IDs that HTML did not define.
- This strengthens the profile-boundary need: Static Web should own artifact
  target shape, selected verifier profile, and post-write surface recognition
  instead of relying on generic turn-control code.

## Classification

Primary taxonomy bucket: `REPAIR_CONTROL`

Secondary buckets:

- `VERIFICATION`
- `CURRENT_TURN_FRAME`
- `MODEL_COMPETENCE`

Blocker level: future milestone after release-blocker control-plane work

Why this level:

Capability ownership matters for long-term generality, but T54 showed more
urgent turn-state, boundary, evidence, and outcome blockers.

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Add more BMI/web repair prompt text in generic repair code.
```

Architectural hypothesis:

```text
Talos needs a minimal static capability profile spine so Static Web owns its
artifact targets, verifier selection, repair guidance, and TalosBench cases.
T47 should proceed as a Static Web profile refinement after this ownership
boundary exists or is at least sketched.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/verification/WebDiagnosticIntent.java`
- `src/main/java/dev/talos/runtime/repair/RepairPolicy.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/main/java/dev/talos/cli/modes/ExecutionOutcome.java`
- `src/e2eTest/resources/scenarios/`
- `tools/manual-eval/talosbench-cases.json`

## Goal

Introduce a minimal static capability/profile boundary so web-specific verifier
and repair behavior no longer lives as generic turn-control logic.

The profile boundary should also clarify natural web creation expectations:
whether a task is allowed to produce one self-contained HTML file, whether it
must produce an HTML/CSS/JS surface, and how the verifier reports incomplete
surface shape without owning the final outcome status.

## Non-Goals

- No dynamic plugin loader.
- No marketplace.
- No MCP-first architecture.
- No browser execution.
- No shell/test-runner expansion.
- No broad artifact taxonomy beyond what current code needs.

## Implementation Notes

- Sketch or implement a static Java capability registry.
- Define minimal concepts: artifact kind, artifact operation, target set,
  verifier profile, and repair profile.
- Move Static Web verifier and repair applicability behind profile-owned
  predicates.
- Keep generic outcome dominance generic; profile verifiers can supply summaries
  but should not own final truth precedence.
- Revisit T47 after this boundary exists.

## Acceptance Criteria

- Static web verifier applicability is profile-owned or clearly isolated.
- Static web repair guidance is profile-owned or clearly isolated.
- Natural web app creation selects the Static Web profile and records the
  expected surface shape before verification.
- A one-file web creation can pass only when it is explicitly self-contained or
  allowed by the selected profile; otherwise the verifier reports an incomplete
  surface and T58 owns the final failed/not-verified status.
- Generic task classification does not own detailed BMI/web repair coherence.
- T47 has a clear implementation owner and no longer requires generic repair
  prompt expansion.
- Existing static web tests continue to pass.

## Tests / Evidence

Required deterministic regression:

- Unit test: Static Web profile selected for HTML/CSS/JS web tasks.
- Unit test: Static Web profile selected for natural BMI/web app creation from
  an empty workspace.
- Unit test: non-web README/config/code tasks do not select Static Web repair.
- Static verifier test: one-file BMI creation is accepted only when
  self-contained/profile-allowed, otherwise reports incomplete web surface.
- Static verifier tests remain passing.
- T47 e2e scenarios can be implemented after this ticket or as part of it if
  the scope remains small.

Commands:

```powershell
./gradlew.bat test --no-daemon
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

## Known Risks

- A capability spine can become a plugin system too early. Keep it static and
  compile-time.
- Moving verifier/repair ownership can create churn. Prefer adapters first if
  extraction is risky.

## Known Follow-Ups

- Continue or reframe T47 as a Static Web repair-profile ticket.
- Future document, config, code, and data capabilities can use the same spine
  after the static profile pattern proves useful.

## Closure Notes

- Added a minimal static capability spine under `dev.talos.runtime.capability`.
- Added the `static-web` profile with artifact kind, operation, target surface,
  verifier profile, and repair profile.
- Routed Static Web verifier applicability and separate HTML/CSS/JS target-shape
  expectations through the profile registry.
- Moved structural web repair helpers behind `StaticWebCapabilityProfile`.
- Allowed explicitly self-contained HTML web creation to verify as a
  profile-owned single-file surface.
- Updated T47 so its next implementation owner is the Static Web profile plus
  verifier/repair adapters, not generic turn-control prompt expansion.

Verification:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.capability.CapabilityProfileRegistryTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.selfContainedHtmlWebCreationPassesWhenStaticWebProfileAllowsSingleFile" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.capability.CapabilityProfileRegistryTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest" --tests "dev.talos.runtime.repair.RepairPolicyTest" --no-daemon
```
