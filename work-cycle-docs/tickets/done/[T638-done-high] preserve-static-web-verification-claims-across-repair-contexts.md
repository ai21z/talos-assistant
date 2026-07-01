# [T638-done-high] Preserve Static-Web Verification Claims Across Repair Contexts

Status: done
Priority: high
Completed: 2026-06-01

## Evidence Summary

- Source: focused manual live audit, exploratory repair-context probe
- Date: 2026-06-01
- Talos version / commit: `talosVersion=0.9.9`, `fa6b6e15`
- Branch: `v0.9.0-beta-dev`
- Model/backend: `qwen2.5-coder:14b` via managed `llama.cpp`
- Workspace fixture: `local/manual-workspaces/t637-synthwave-formal-live-audit-20260601/qwen/`
- Raw transcript path: `local/manual-testing/t637-synthwave-formal-live-audit-20260601/artifacts-qwen/talos-repair-output.txt`
- Prompt-debug path: `local/manual-testing/t637-synthwave-formal-live-audit-20260601/artifacts-qwen/repair-prompt-debug/prompt-debug-20260601-200359.md`
- File state evidence:
  - `local/manual-testing/t637-synthwave-formal-live-audit-20260601/artifacts-qwen/repair-final-index.html`
  - `local/manual-testing/t637-synthwave-formal-live-audit-20260601/artifacts-qwen/repair-final-scripts.js`
- Approval choices: redirected stdin granted write approvals; useful audit evidence, not synchronized release-grade approval evidence
- Checkpoint id: `chk-72956572-9b59-4fd6-a680-d42f5b64d67f`
- Verification status: repair turn reported `PASSED` and final outcome `COMPLETED_VERIFIED`

Redacted prompt sequence:

```text
Initial task:
Create a polished three-file static website for a synthwave band named Neon Voltage.
Write exactly index.html, styles.css, and scripts.js. The page should look
synthwave/retro, include band name, tour dates, a newsletter email field, and a
button with id teaser-button that updates visible text in #teaser-status when
clicked. Keep CSS in styles.css and JavaScript in scripts.js. Do not create any
other files.

Exploratory separate-process repair prompt:
Fix the remaining static verification problems and make the existing Neon
Voltage site verified. Keep exactly index.html, styles.css, and scripts.js; do
not create any other files.
```

Expected behavior:

```text
When a repair request refers to previous static verification problems, Talos
must preserve the previous required verification claim if it is available. For
the Neon Voltage task, the required claim is:

  #teaser-button click -> visible text update in #teaser-status

Static web coherence alone must not satisfy that claim. If the previous claim
context is unavailable and the current repair prompt does not explicitly state
an interaction claim, Talos must not produce COMPLETED_VERIFIED merely from
generic static coherence.
```

Observed behavior:

```text
The initial turn failed correctly with requiredClaims=1 and unsatisfied=1 after
detecting a JavaScript syntax error in scripts.js. The workspace still contained
#teaser-button and #teaser-status.

The exploratory repair run was launched as a separate process with no loaded
conversation history. The prompt audit showed evidenceObligation=NONE,
activeTaskContext=NONE_OR_NOT_DERIVED, and history=SUPPRESSED messages=0.

The model rewrote the workspace into a minimal coherent HTML/CSS/JS site:

  index.html: title, h1, stylesheet link, scripts.js link
  scripts.js: console.log('Neon Voltage site is verified!');

The repaired files no longer contained #teaser-button, #teaser-status,
addEventListener, textContent, or innerText. Talos still reported:

  [Static verification: passed - Static web coherence checks passed for 3 mutated target(s).]
  Outcome: COMPLETED_VERIFIED
```

Important scope qualification:

```text
This is not evidence that the primary T637 threaded static-web path is broken.
The threaded audit passed for both standard models with:

  Claims: required=1 unsatisfied=0
  Authoritative proof: STATIC_INTERACTION_GUARD, BROWSER_BEHAVIOR

The finding is narrower: repair/no-history or context-suppressed turns can lose
the original claim obligation and then award verified completion from weaker
static coherence.
```

## Classification

Primary taxonomy bucket:

- `VERIFICATION`

Secondary buckets:

- `REPAIR_CONTROL`
- `CURRENT_TURN_FRAME`
- `OUTCOME_TRUTH`

Blocker level:

- candidate follow-up

Why this level:

```text
The observed run was an exploratory separate-process repair probe with no loaded
history, so it is not a release blocker against the already-passing primary
threaded T637 path. It is still high priority because the failure mode is a
false verified completion whenever a repair context loses a required claim and
falls back to generic static coherence.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Teach the prompt to remember the original synthwave requirements.
```

Architectural hypothesis:

```text
Claim-scoped verification currently protects only claims present in the current
TaskContract/VerificationReport. Static repair context preserves file targets
and static failure text, but not a first-class required VerificationClaim such
as #teaser-button -> #teaser-status. When history is suppressed, unavailable, or
compacted, the repair turn can become a generic STATIC_WEB task with
evidenceObligation=NONE. VerificationOutcomeGate then has no required claim to
enforce and generic static coherence can project to PASSED.
```

Likely code/document areas:

- `dev.talos.runtime.repair.RepairPolicy`
- `dev.talos.runtime.verification.StaticVerificationRepairContext`
- `dev.talos.runtime.verification.VerificationReport`
- `dev.talos.runtime.verification.VerificationClaim`
- `dev.talos.runtime.verification.VerificationOutcomeGate`
- `dev.talos.runtime.turn.CurrentTurnPlan`
- `dev.talos.cli.repl.ActiveTaskContextUpdater`
- `dev.talos.cli.modes.ExecutionOutcome`
- prompt-debug and local trace fields that expose repair claim carry-forward

Why a one-off patch is insufficient:

```text
The invariant is not specific to Neon Voltage. Any verifier with required
claims can be weakened if a repair or continuation turn carries only target
files and generic verifier profile, not the original required obligations.
Fixing one prompt phrase would leave the same hole for other static-web
interactions and future document/source-derived claim lanes.
```

## Goal

```text
Talos must preserve required verification claims across repair contexts when
the user asks to fix previous verification problems. A repair turn must not
downgrade an earlier required STATIC_INTERACTION_GUARD or BROWSER_BEHAVIOR
obligation into generic STATIC_COHERENCE. If the required claim cannot be
recovered or re-derived, Talos must not report COMPLETED_VERIFIED from generic
coherence alone.
```

## Non-Goals

- No external browser or Playwright lane.
- No LLM verifier authority.
- No broad session-memory rewrite.
- No attempt to make a separate no-history process know arbitrary missing
  history by model inference.
- No committing raw private transcripts.
- No change to the primary successful T637 static-web behavior path unless
  required by the carry-forward invariant.

## Implementation Notes

```text
Prefer a runtime-owned claim carry-forward path:

1. After a failed or unavailable claim-scoped verification, persist a compact
   repair-safe summary of required claims:
   - claim id/description
   - TargetBinding trigger selector, output selector, event type
   - required proof kinds
   - authoritative/supplemental/advisory authority
   - unsatisfied/failure reason and affected files

2. Render that compact claim summary into static verification repair context
   and mutation retry context when the user asks to fix previous verification
   problems.

3. Let the planner/verifier treat carried claims as required obligations for
   the repair turn, even when the current natural-language prompt is vague.

4. If no previous claim context is available, but the current prompt is repairy
   ("remaining static verification problems", "make existing site verified")
   and no explicit binding can be derived from the prompt or current workspace,
   do not let generic static coherence produce COMPLETED_VERIFIED. Prefer
   COMPLETED_UNVERIFIED with an explanation, or a read/inspect/repair path that
   re-derives the claim from current workspace evidence where possible.

5. Keep the gate deterministic. LLM-authored repair prose cannot add or satisfy
   required verification claims.
```

Potential re-derivation path:

```text
The failed workspace in the audit still contained #teaser-button and
#teaser-status before the repair rewrite. Talos may be able to re-derive a
candidate interaction claim from current HTML/JS evidence in a repair turn, but
that must be a deterministic verifier/planner rule, not an LLM judgment.
```

## Architecture Metadata

Capability:

- Static-web verification repair
- Claim-scoped verification

Operation(s):

- write/edit/verify

Owning package/class:

- `dev.talos.runtime.repair`
- `dev.talos.runtime.verification`
- `dev.talos.runtime.turn`
- `dev.talos.cli.repl`

New or changed tools:

- None expected.

Risk, approval, and protected paths:

- Risk level: high outcome-truth risk.
- Approval behavior: unchanged.
- Protected path behavior: unchanged.

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged.
- Evidence obligation: previous required verification claims must remain
  required during repair when applicable.
- Verification profile: `STATIC_WEB`; no new browser lane.
- Repair profile: static verification repair must carry claim obligations, not
  only file targets and prose problem text.

Outcome and trace:

- Outcome/truth warnings: generic static coherence must not be rendered as
  verified completion when a carried required claim is unsatisfied or missing.
- Trace/debug fields: expose carried required claim count, target binding, and
  whether claim context was recovered, re-derived, or unavailable.

Refactor scope:

- Allowed: small value type or field additions for compact claim carry-forward.
- Allowed: targeted extraction in repair/context planner if needed.
- Forbidden: broad rewrite of session memory, repair policy, or verifier
  registry.

## Acceptance Criteria

- A repair turn after a failed `#teaser-button -> #teaser-status` verification
  still has a required interaction claim.
- Generic static web coherence cannot satisfy a carried required interaction
  claim.
- A repair output that removes `#teaser-button` and `#teaser-status` cannot
  become `COMPLETED_VERIFIED` for the original interaction task.
- A valid repair that fixes JavaScript syntax while preserving the click/update
  behavior can become `COMPLETED_VERIFIED`.
- If no previous claim context is available and the current repair prompt is too
  vague to derive a claim, Talos must not report `COMPLETED_VERIFIED` from
  static coherence alone.
- Prompt-debug or trace evidence shows whether the claim obligation was carried,
  re-derived, or unavailable.
- Existing T637 passing threaded synthwave path still reports
  `STATIC_INTERACTION_GUARD, BROWSER_BEHAVIOR` with `required=1 unsatisfied=0`.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test:
  - `ActiveTaskContextUpdater` or equivalent session-memory test proving failed
    claim-scoped verification stores a compact repair-safe required claim.
  - `RepairPolicy` or repair-context test proving a "fix remaining static
    verification problems" prompt receives the carried binding
    `#teaser-button -> #teaser-status`.
- Integration/executor test:
  - Seed a prior failed `VerificationReport` for a static-web interaction.
  - Simulate a repair turn where the model writes a minimal coherent site with
    no `#teaser-button`, no `#teaser-status`, and only `console.log(...)`.
  - Expected: compatibility status is not `PASSED`; final task status is not
    `COMPLETED_VERIFIED`.
  - Simulate a repair turn that fixes the syntax error and preserves the
    interaction.
  - Expected: required claim satisfied and final status may be
    `COMPLETED_VERIFIED`.
- JSON e2e scenario:
  - Add a deterministic static-web repair continuation scenario if the harness
    can seed previous verification context.
- Trace assertion:
  - Verify trace/prompt-debug includes carried required claim count and binding,
    or an explicit "claim context unavailable" limitation.

Manual/TalosBench rerun:

- Prompt family: T637 synthwave static-web creation followed by repair prompt.
- Workspace fixture:
  - Use fresh `local/manual-workspaces/<audit-id>/qwen/` and
    `local/manual-workspaces/<audit-id>/gptoss/`.
- Expected trace:
  - First failed turn: required claim present.
  - Repair turn: same required claim present or explicitly unavailable; static
    coherence alone does not pass the carried claim.
- Expected outcome:
  - Bad minimal repair: not `COMPLETED_VERIFIED`.
  - Correct syntax-fix repair: `COMPLETED_VERIFIED` only with claim satisfaction.

Commands:

```powershell
./gradlew.bat test --tests "dev.talos.cli.repl.ActiveTaskContextUpdaterTest" --tests "dev.talos.runtime.repair.*" --tests "dev.talos.runtime.verification.*" --no-daemon
./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
./gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop unless this becomes candidate closeout.
- Do not bump version unless this is candidate closeout.
- Do not update `CHANGELOG.md` unless this is candidate closeout.
- Convert the live failure evidence into deterministic regression before
  closeout.

## Implementation Summary

- Added a typed `ActiveTaskContext.RequiredVerificationClaim` carrier for compact
  repair-safe required verification claims.
- Persisted required verification claims in session JSON.
- Derived static-web interaction repair claims from failed claim-scoped
  verification turns when the original request contains a deterministic
  trigger/output binding.
- Made verifier-finding active contexts consumable by explicit repair
  continuations such as "fix remaining static verification problems."
- Kept status questions such as "is it verified now?" from consuming verifier
  context as a repair mutation.
- Added a static-web verifier gate for high-risk vague no-history repair prompts
  such as "make the existing site verified" so generic static coherence cannot
  become `PASSED` when required claim context is unavailable.
- Preserved structural static-web repair behavior: generic "fix remaining static
  verification problems" repairs for HTML/CSS/JS structure can still pass by the
  structural static-web oracle when no interaction claim is present.
- Isolated deterministic unit/E2E tests from the local live-audit
  `~/.talos/config.yaml`, which had added fake OCR and protected-read deny rules
  that contaminated default-policy assertions.

## Acceptance Evidence

- RED observed before implementation:
  `./gradlew.bat test --tests "dev.talos.cli.repl.ActiveTaskContextUpdaterTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.vagueStaticVerificationRepairWithoutClaimContextDoesNotPassStaticCoherenceOnly" --no-daemon`
  failed at compile because `ActiveTaskContext` had no required-claim carrier.
- Focused context/static-verifier test pass:
  `./gradlew.bat test --tests "dev.talos.cli.repl.ActiveTaskContextUpdaterTest" --tests "dev.talos.runtime.context.ActiveTaskContextPolicyTest" --tests "dev.talos.runtime.JsonSessionStoreTest" --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.vagueStaticVerificationRepairWithoutClaimContextDoesNotPassStaticCoherenceOnly" --no-daemon`
- Broader affected surface pass:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.*" --tests "dev.talos.runtime.context.*" --tests "dev.talos.cli.repl.ActiveTaskContext*" --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --tests "dev.talos.cli.modes.OutcomeDominancePolicyTest" --no-daemon`
- Additional RED/GREEN observed during verification:
  `./gradlew.bat test --tests "dev.talos.runtime.context.ActiveTaskContextPolicyTest.completionQuestionDoesNotConsumeVerifierContextAsRepairMutation" --no-daemon`
  failed before tightening repair-continuation status-question detection, then
  passed with the positive repair-consumption test.
- Additional RED/GREEN observed during verification:
  `./gradlew.bat test --tests "dev.talos.runtime.verification.StaticTaskVerifierTest.structuralStaticVerificationRepairWithoutInteractionClaimCanPassStaticCoherence" --no-daemon`
  failed before narrowing the no-claim fallback, then passed with
  `vagueStaticVerificationRepairWithoutClaimContextDoesNotPassStaticCoherenceOnly`.
- Previously failing E2E scenario subset pass:
  `./gradlew.bat e2eTest --tests "*repairAfterStaticVerificationFailureUsesVerifierContext*" --tests "*structuralWebRepairRedirectsEditFileToWriteFile*" --tests "*structuralWebRepairContinuesUntilPlannedWriteTargets*" --tests "*protectedReadRequiresApproval*" --tests "*deniedProtectedReadProducesBlockedOutcome*" --no-daemon`
- Final whitespace and full verification pass:
  `git diff --check`
  `./gradlew.bat check --no-daemon`

## Known Risks

- Over-carrying stale claims could recreate the stale repair context class
  fixed in earlier tickets. Carry-forward must be target-bound and superseded by
  later successful verification for the same targets.
- Under-carrying claims leaves the false-verified repair path open.
- Re-deriving claims from current workspace evidence can be useful, but it must
  be deterministic and conservative to avoid hallucinated obligations.

## Known Follow-Ups

- A named reproducible live-audit harness for the synthwave static-web probe.
- Release-grade synchronized approval evidence for static-web repair audits.
