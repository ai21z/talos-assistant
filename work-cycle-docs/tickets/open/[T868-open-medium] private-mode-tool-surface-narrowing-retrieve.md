# [T868-open-medium] Private-Mode Tool-Surface Narrowing Should Remove Retrieval Tools, Not Only Disable Function

Status: open
Priority: medium

## Evidence Summary

- Source: manual prompt (T842 Part A semi-automated bank + Part B interactive PTY)
- Date: 2026-06-24
- Talos version / commit: `0.10.5` / `a366091d`
- Branch: `v0.9.0-beta-dev`
- Model/backend: managed llama.cpp; `qwen2.5-coder-14b` and `gpt-oss-20b`
- Workspace fixture: `local/manual-workspaces/capability-live-audit-20260624-173843/{qwen,gptoss}`
- Raw transcript path: `local/manual-testing/capability-live-audit-20260624-173843/` (per-model prompt-debug homes, 52 captures each)
- Trace path or `/last trace` summary: per-turn `/session` audit was overwritten by the runbook's clear-before-each-turn pattern (evidence caveat); durable evidence = provider bodies + workspace git state + canary scan
- File diff summary: no false/unapproved mutation landed; trust surface held (see Verification status)
- Approval choices: destructive qwen README full-rewrite-for-an-append surfaced by approval and DENIED; `.env` reads failed CLOSED (`CONFIG_DENY`)
- Checkpoint id: not applicable to this finding
- Verification status: reviewed by independent review + cross-verified by owner against disk. NO secret/canary/PII leak (canary scan passed, `.ssh/id_rsa` content `dummy` absent from every capture). NO false/unapproved mutation landed (qwen README unchanged, gptoss README carries only the correct append, the stale-edit `x` is nowhere). NO hard-fail gate fired (no protected leak, no unapproved mutation, no approved-without-checkpoint, no landed false-success).

Redacted prompt sequence:

```text
Private mode enabled for both model workspaces. Retrieval-style and
file-grounded prompts were issued from the T842 capability bank (find/locate
content, summarize across files, answer-from-workspace turns) under the
runbook's clear-before-each-turn pattern.
```

Expected behavior:

```text
In private mode, RAG/retrieve is disabled. The tool surface offered to the
model for these turns should also exclude retrieval tools (talos.retrieve and
any RAG-dependent tool), consistent with the disabled function. The model
should never be offered a tool that can only no-op, so it cannot waste a call
on it and there is one fewer surface to defend.
```

Observed behavior:

```text
In private mode the talos.retrieve tool was still EXPOSED in the model's tool
surface. qwen actually CALLED retrieve. gpt-oss-20b had retrieve exposed but
used grep/read/list instead.

Retrieval was functionally disabled, so NO private content leaked (verified:
canary scan clean, no private corpus content in any capture). The defect is a
tool-surface-narrowing inconsistency, not a leak: a disabled capability is
still advertised, which wastes a model call and leaves a no-op tool in the
offered surface as an unnecessary defense-in-depth gap.
```

Source evidence:

- `src/main/java/dev/talos/runtime/toolcall/ToolSurfacePlanner.java`: selects the offered surface from the registry by operation predicate. `talos.retrieve` rides into the surface through `isReadOnlyOperation(...)` (`return select(registry, ToolSurfacePlanner::isReadOnlyOperation, "read-only metadata surface")`). The planner has no private-mode input and no retrieval-exclusion seam, so a privacy fact cannot narrow the surface here today.
- `src/main/java/dev/talos/core/rag/RagService.java`: holds the private-mode gate (`PrivacyConfigFacts.privateMode(cfg)` -> `PRIVATE_MODE_REINDEX_DISABLED`). This disables the *function* but does not influence which tools are *offered*. The two layers disagree.
- `src/main/java/dev/talos/runtime/toolcall/PromptToolDescriptors.java`: renders the selected surface (including the disabled retrieve descriptor) into the prompt the model sees.
- `src/main/java/dev/talos/core/privacy/PrivacyConfigFacts.java`: canonical deterministic source of the `privateMode(cfg)` fact that the planner should consult.

## Classification

Primary taxonomy bucket:

- `TOOL_SURFACE`

Secondary buckets:

- none

Blocker level:

- candidate follow-up

Why this level:

```text
This is not a release blocker because the trust invariant held: retrieval is
functionally disabled in private mode and NO private content leaked (verified
against disk and canary scan). It is a tool-surface-narrowing inconsistency and
a defense-in-depth/wasted-call gap, not a privacy break. It should be closed
before strong public claims about clean private-mode tool surfaces, but it does
not block the candidate cut.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Hide talos.retrieve when private mode is on by string-matching the tool name in
the planner.
```

Architectural hypothesis:

```text
The tool-surface selection layer (ToolSurfacePlanner) decides the offered
surface from operation predicates alone and has no view of the privacy facts
that RagService already enforces at the function layer. The correct boundary is
that the offered surface is the intersection of operationally-relevant tools and
currently-enabled capabilities: a tool that is gated off for the active config
(retrieval under private mode, and any RAG-dependent tool) must be excluded from
the offered surface, not merely no-op at call time. Ownership stays
deterministic: the planner consults PrivacyConfigFacts (or an explicit
capability-availability input derived from it) to drop retrieval tools, mirroring
the existing 'RAG/retrieve disabled in private mode' rule. The functional disable
in RagService is NOT weakened or replaced; it remains the fail-closed backstop.
```

Likely code/document areas:

- `src/main/java/dev/talos/runtime/toolcall/ToolSurfacePlanner.java` (surface selection; add a retrieval/RAG-availability exclusion seam keyed off the privacy fact)
- `src/main/java/dev/talos/runtime/policy/CurrentTurnCapabilityFrame.java` (current-turn frame copy that mentions retrieve must stay consistent with the narrowed surface)
- `src/main/java/dev/talos/runtime/toolcall/PromptToolDescriptors.java` (must not render a retrieval descriptor once excluded)
- `src/main/java/dev/talos/core/rag/RagService.java` and `src/main/java/dev/talos/core/privacy/PrivacyConfigFacts.java` (canonical private-mode fact and the existing functional disable that stays the backstop)

Why a one-off patch is insufficient:

```text
This is one instance of a recurring invariant: the offered tool surface must
equal the set of currently-usable capabilities. A name-based hide for
talos.retrieve would not generalize to other RAG-dependent or
capability-gated tools, would drift as tools are renamed or added, and would
leave the same disagreement between the function layer and the surface layer for
the next gated capability. The fix needs a capability-availability seam in the
planner, not a special case.
```

## Goal

```text
In private mode the offered tool surface excludes retrieval tools (talos.retrieve
and any RAG-dependent tool), matching the disabled function. The model is never
offered retrieve in private mode, so it cannot call a no-op tool, and the
surface advertised to the model equals the set of currently-usable capabilities.
The functional disable in RagService remains intact and fail-closed.
```

## Non-Goals

- No shell/browser unless the milestone explicitly includes it.
- No MCP or multi-agent behavior unless explicitly approved.
- No LLM classifier for safety-critical permission, privacy, mutation, or
  verification policy. The private-mode availability decision stays a
  deterministic config fact (`PrivacyConfigFacts`).
- No giant untyped phrase dump without an owner policy.
- No bypassing approval, permission, checkpoint, trace, or verification.
- No committing raw private transcripts.
- Do NOT weaken or remove the functional retrieval disable in `RagService`; the
  surface narrowing is additive defense-in-depth on top of it.
- No broad rewrite of `ToolSurfacePlanner` selection logic beyond adding the
  capability-availability seam.
- Do not change non-private-mode behavior: retrieve stays offered when RAG is
  enabled.

## Implementation Notes

```text
Thread a deterministic capability-availability input into ToolSurfacePlanner so
selection can drop retrieval/RAG-dependent tools when PrivacyConfigFacts reports
private mode (and RAG is not explicitly re-enabled for private mode). Keep the
predicate-based selection model; add an exclusion step that removes retrieval
tools from any selected surface rather than special-casing each surface kind.
Identify retrieval tools by a typed capability/operation marker on the tool
descriptor, not by hard-coded tool-name strings, so the exclusion generalizes to
all RAG-dependent tools. Ensure PromptToolDescriptors and CurrentTurnCapabilityFrame
copy stay consistent with the narrowed surface (no advertised retrieve, no
frame text implying retrieve is available). Leave RagService's private-mode
refusal exactly as-is as the fail-closed backstop.
```

## Architecture Metadata

Capability:

- Tool-surface narrowing for private mode (retrieval/RAG-dependent tool availability)

Operation(s):

- read-only / retrieve (surface selection only; no new mutation operation)

Owning package/class:

- `dev.talos.runtime.toolcall.ToolSurfacePlanner` (offered-surface owner), consulting `dev.talos.core.privacy.PrivacyConfigFacts`

New or changed tools:

- none (no new tool; `talos.retrieve` is excluded from the offered surface in private mode)

Risk, approval, and protected paths:

- Risk level: low (removes a no-op tool from an advertised surface; functional disable already present)
- Approval behavior: unchanged
- Protected path behavior: unchanged; private-mode protected/unsupported-file exclusion stays owned by the privacy and indexing policies

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: unchanged
- Evidence obligation: deterministic regression proving retrieve is absent from the offered surface in private mode and present when RAG is enabled
- Verification profile: unchanged
- Repair profile: unchanged

Outcome and trace:

- Outcome/truth warnings: none added; this reduces wasted retrieve calls in private mode
- Trace/debug fields: the offered tool surface recorded for a turn should no longer list retrieve under private mode

Refactor scope:

- Allowed: add a capability-availability seam/parameter to `ToolSurfacePlanner` and a typed retrieval-tool marker
- Forbidden: broad rewrite of selection predicates; any change that weakens or relocates the `RagService` private-mode functional disable

## Acceptance Criteria

- In private mode (default config), the surface returned by `ToolSurfacePlanner` for retrieval-eligible and read-only turns does NOT contain `talos.retrieve` or any RAG-dependent tool.
- With RAG enabled (non-private, or private-mode RAG explicitly enabled), retrieve is still offered, proving the change is scoped to the disabled state.
- The exclusion keys off the deterministic `PrivacyConfigFacts.privateMode(cfg)` fact, not an LLM decision and not hard-coded tool-name strings.
- `PromptToolDescriptors` does not render a retrieve descriptor in private mode; `CurrentTurnCapabilityFrame` copy does not imply retrieve is available in private mode.
- The functional retrieval/reindex disable in `RagService` is unchanged and still fail-closed (regression proving private-mode retrieval still no-ops even if a tool were somehow invoked).
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `ToolSurfacePlannerTest` (extend) - private-mode config excludes retrieve from each selected surface; RAG-enabled config includes it.
- Integration/executor test: surface handed to the model in private mode contains no retrieval tool (assert against `PromptToolDescriptors` output / the rendered surface).
- JSON e2e scenario: private-mode scripted retrieval-style turn (extend `PrivateModeScriptedE2eTest`) asserts retrieve is not offered and no private content surfaces.
- Trace assertion: recorded offered-tool surface for a private-mode turn omits retrieve.

Manual/TalosBench rerun:

- Prompt family: T842 retrieval/file-grounded bank under private mode (both `qwen2.5-coder-14b` and `gpt-oss-20b`)
- Workspace fixture: a private-mode capability fixture mirroring `local/manual-workspaces/capability-live-audit-*`
- Expected trace: offered surface lists grep/read/list (and applicable read-only tools) but not retrieve
- Expected outcome: model uses grep/read/list; no wasted retrieve call; canary scan clean

Commands:

```powershell
./gradlew.bat test --no-daemon
```

Add broader commands if runtime code changes:

```powershell
./gradlew.bat e2eTest --no-daemon
./gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop; this is not a candidate closeout.
- Do not bump version.
- This is a behavior-changing ticket (the offered tool surface changes in private mode): add a one-line entry under `## [Unreleased]` in `CHANGELOG.md` when it lands.
- Convert the T842 live finding (retrieve offered + qwen calling it in private mode) into the deterministic `ToolSurfacePlanner` regression before closeout.

## Known Risks

- A retrieval-tool marker that is too broad could drop legitimately-available read-only tools; scope the exclusion to retrieval/RAG-dependent tools and pin both the excluded and retained sets in tests.
- Frame/descriptor copy that still mentions retrieve after the surface is narrowed would re-introduce the inconsistency at the prompt layer; assert the rendered surface and frame copy together.
- Future private-mode-RAG-enabled path must keep offering retrieve; guard against an over-eager unconditional exclusion.

## Known Follow-Ups

- Generalize the capability-availability seam to any other capability-gated tool so the offered surface always equals usable capabilities (audit for further function-disabled-but-still-offered tools).
- The T842 evidence caveat (per-turn `/session` audit overwritten by the runbook clear-before-each-turn pattern) is a separate runbook-evidence-durability follow-up, not part of this ticket.
