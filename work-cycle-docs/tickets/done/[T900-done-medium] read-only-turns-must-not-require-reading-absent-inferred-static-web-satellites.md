# [T900-done-medium] Read-only turns must not require reading inferred static-web satellites that are absent on disk

Status: done
Priority: medium

## Evidence Summary

- Source: owner manual-test session, 2026-06-28 (plan mode, model qwen3.6-35b-a3b)
- Talos version / commit: 0.10.6 / 402ad760 (branch improvement/qodana-cleanup)
- Verification status: root cause code-verified against the live transcript; fixed + focused tests green.

Observed (live, plan mode): prompt "Okay! So lets plan a full page change to make it visually better..." on a workspace containing ONLY `index.html` (CSS inlined, no JS) returned:
```
[Evidence incomplete: required workspace evidence was not gathered in this turn.]
... Required target(s): style.css, script.js, index.html.
```
The model had read index.html, but the turn was blocked for not reading `style.css`/`script.js`, which do not exist.

## Root Cause

This is the READ-evidence sibling of [T897](work-cycle-docs/tickets/open) (which is scoped to the MUTATION facet). Shared root, different obligation:

1. The conventional static-web triplet `{index.html, style.css, script.js}` is projected by [TaskContractResolver](src/main/java/dev/talos/runtime/task/TaskContractResolver.java:773) (via `inferConventionalStaticWebTargets` / `withContextualStaticWebTargets:1398`) when the user names no files.
2. Plan mode applies [CapabilityPosturePolicy.readOnly](src/main/java/dev/talos/runtime/policy/CapabilityPosturePolicy.java:31): `mutationAllowed=false` but `expectedTargets` preserved.
3. [EvidenceObligationPolicy.derive:57](src/main/java/dev/talos/runtime/policy/EvidenceObligationPolicy.java:57) then yields `READ_TARGET_REQUIRED` for the read-only contract with non-empty expectedTargets.
4. [EvidenceObligationVerifier.verifyReadTargets](src/main/java/dev/talos/runtime/policy/EvidenceObligationVerifier.java:172) requires a read of EVERY expected target. `style.css`/`script.js` were never read (cannot be: they do not exist), so the result is UNSATISFIED, and [ExecutionOutcome:284](src/main/java/dev/talos/cli/modes/ExecutionOutcome.java:284) -> [EvidenceContainmentAnswerGuard](src/main/java/dev/talos/runtime/outcome/EvidenceContainmentAnswerGuard.java:33) emits the "Evidence incomplete / Required target(s)" block.

Requiring a read of a file that does not exist is always a false block: you cannot read what is not there.

## Fix

New disk-aware, trust-preserving helper [EvidenceGate.withoutAbsentInferredStaticWebSatellites](src/main/java/dev/talos/runtime/policy/EvidenceGate.java): drops a target from the read-evidence set only when ALL of:
- its basename is a conventional inferred satellite (`style.css` or `script.js`), AND
- it does NOT exist on disk (workspace-resolved, contained), AND
- it was NOT named in the original user request (so an explicit "read style.css" still requires it).

Applied for `READ_TARGET_REQUIRED` only, in:
- [EvidenceObligationAssessment.assess](src/main/java/dev/talos/runtime/policy/EvidenceObligationAssessment.java) (the canonical verification whose `missingEvidence()` drives the containment block). If filtering leaves no targets, the obligation is satisfied (nothing readable was required).
- [ReadEvidenceHandoff.readEvidenceRecoveryForPartialTargetsIfNeeded](src/main/java/dev/talos/cli/modes/ReadEvidenceHandoff.java) (so no wasteful recovery-handoff is fired to read absent satellites).

Net: the plan-mode redesign reads index.html (the only file present) and the turn proceeds. A present-on-disk satellite, an explicitly-named satellite, a protected target, a path-existence check (`PATH_EXISTENCE_EVIDENCE_REQUIRED`, untouched), and any non-satellite target all keep their existing read requirement.

## Why this is split from T897 (and T897 stays open)

T897 is the MUTATION facet (agent-mode "redesign" being hard-blocked for not CREATING nonexistent css/js). That fix is genuinely entangled with the intended "keep creating until the triplet is done" behavior (T98/T99/T100 multifile-web-create continuation): for create-from-scratch the satellites are also absent on disk, so a naive disk-existence drop on the MUTATION obligation would regress intended create continuation. That needs the inferred-vs-named + create-vs-redesign distinction at the mutation-obligation layer and is left to T897. This ticket (T900) fixes only the READ-evidence facet, which is cleanly and safely disk-aware (you can never read a nonexistent file).

## Trust / Non-Goals

- No weakening of evidence obligations for files that exist or were named. Fail-closed behavior preserved for genuine misses (model read nothing -> still UNSATISFIED). Protected-read and path-existence paths untouched.
- Does not change `TaskContractResolver` (stays lexical, no disk) or the projected contract; the create-from-scratch triplet projection and its pinned tests are unaffected.
- No change to approval, permission, checkpoint, trace redaction, privacy, or mutation outcome-truth.

## Tests / Evidence

- [EvidenceGateTest](src/test/java/dev/talos/runtime/policy/EvidenceGateTest.java): absent inferred satellites dropped; present satellite kept; user-named-but-absent satellite kept; non-satellite target untouched.
- [EvidenceObligationAssessmentTest](src/test/java/dev/talos/runtime/policy/EvidenceObligationAssessmentTest.java): plan-mode triplet contract with only index.html present and read -> SATISFIED (the live bug); read-nothing -> still UNSATISFIED (guard).
- Green alongside: EvidenceObligationVerifierTest, EvidenceObligationPolicyTest, ReadEvidenceHandoffTest, EvidenceContainmentAnswerGuardTest, ExecutionOutcomeTest, CurrentTurnCapabilityFrameTest.

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One commit (code + tests + ticket + T897 cross-reference).
