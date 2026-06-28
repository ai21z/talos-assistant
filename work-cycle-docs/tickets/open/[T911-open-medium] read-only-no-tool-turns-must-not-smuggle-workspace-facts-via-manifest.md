# [T911-open-medium] Read-only no-tool turns must not smuggle workspace facts via manifest

Status: open
Priority: medium

## Evidence Summary

- Source: installed-product Ask-mode and Plan-mode manual audits plus prompt-debug artifacts
- Date: 2026-06-28
- Talos version / commit: 0.10.6 / 873b9ed2
- Installed build: 2026-06-28T20:44:48.560965600Z
- Model/backend: llama_cpp / qwen2.5-coder-14b
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\talos-ask-mode-deep-20260628-2315\ask-workspace`
- Prompt-debug artifact: `C:\Users\arisz\.talos\prompt-debug\prompt-debug-20260628-235001.md`
- Trace path or `/last trace` summary: `trc-49824b02-c2da-4b09-880e-6d06b444dd63`
- File diff summary: none
- Approval choices: none
- Checkpoint id: n/a
- Verification status: live installed audit reproduced; deterministic regression not yet added

Additional installed-product corroboration:

- Source: installed-product Plan-mode manual audit
- Date: 2026-06-28 / prompt-debug saved as `20260629-000800` local time
- Repo HEAD at audit: `1b79cb11`
- Installed build: `2026-06-28T20:44:48.560965600Z`
- Workspace fixture: `C:\Users\arisz\Projects\LOQ\loqj-cli\local\manual-workspaces\plan-mode-deep-20260628-235632\plan-workspace`
- Prompt-debug artifact copy: `local/manual-testing/plan-mode-deep-20260628-235632/artifacts/prompt-debug/prompt-debug-20260629-000800.md`
- Provider body artifact copy: `local/manual-testing/plan-mode-deep-20260628-235632/artifacts/prompt-debug/prompt-debug-20260629-000800.provider-body.json`
- Trace id: `trc-02edf5b5-512f-41db-9f3b-217726b29a2d`
- File diff summary: none

Redacted prompt sequence:

```text
/mode ask
Without reading or listing any files, tell me the workspace codename. If you cannot know it from current verified evidence, say exactly: I cannot verify the codename without inspecting files.
/last trace
```

Expected behavior:

```text
For a no-tool Ask/Plan read-only turn whose current-turn frame says no workspace
tools are visible and to answer only from Talos product identity/capability,
Talos should not answer workspace file facts unless those facts are explicitly
represented as current-turn evidence.
```

Observed behavior:

```text
The turn was classified as `SMALL_TALK`, with no native/prompt tools and
`DIRECT_ANSWER_ONLY`. `/last trace` showed:

- Tools: none
- nativeTools: none
- promptTools: none
- evidenceObligation: NONE
- frame preview: "No workspace tools are visible. Do not call tools. Answer
  directly from Talos product identity/capability only."

The assistant still answered:

  The workspace codename is ORBIT-ASK-17.

The saved prompt-debug artifact shows why: the Ask system prompt included the
workspace file structure and a `README (excerpt)` containing
`Visible project codename: ORBIT-ASK-17.`

The same class reproduced in Plan. The prompt:

  Without reading or listing files, tell me the workspace codename...

was classified as `SMALL_TALK`, with no native tools, no prompt tools, no
retrieval, and `DIRECT_ANSWER_ONLY`. The assistant still answered
`PLAN-ARC-42`. The saved Plan prompt-debug artifact shows the system prompt
included `File structure:` and a `README (excerpt):` containing the codename.
```

Code evidence:

- `AskMode.handle(...)` builds the Ask system prompt with
  `.withWorkspace(workspace)` regardless of whether the effective tool surface
  for the current turn is empty:
  `src/main/java/dev/talos/cli/modes/AskMode.java`.
- `PlanMode.handle(...)` uses the same workspace-aware prompt builder path for
  Plan turns, so the same hidden manifest channel exists in read-only Plan:
  `src/main/java/dev/talos/cli/modes/PlanMode.java`.
- `SystemPromptBuilder.buildComposed(...)` injects `WorkspaceManifest.build(...)`
  for any non-directory-listing workspace prompt:
  `src/main/java/dev/talos/core/llm/SystemPromptBuilder.java`.
- `WorkspaceManifest` is intentionally tested to include file structure and
  README excerpts when `withWorkspace(...)` is used:
  `src/test/java/dev/talos/core/llm/SystemPromptBuilderWorkspaceManifestTest.java`.
- `UnifiedAssistantModeTest` has expectations that some prompt modes avoid the
  manifest in certain render paths, but the installed Ask path still included
  the manifest for the no-tool turn:
  `src/test/java/dev/talos/cli/modes/UnifiedAssistantModeTest.java`.

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `OUTCOME_TRUTH`
- `TOOL_SURFACE`

Blocker level:

- candidate follow-up

Why this level:

```text
The answer happened to be true and did not expose protected content. The problem
is evidence semantics: a no-tool/direct-answer frame says workspace facts are
not in scope, while the system prompt still supplies README-derived workspace
facts that the model can use.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Tell the model not to use README excerpts.
```

Architectural hypothesis:

```text
Workspace manifest injection must be aligned with the current-turn capability
frame. If the runtime preloads file facts, those facts are evidence and must be
represented in prompt-debug/trace semantics; otherwise no-tool Ask turns should
not receive file-content excerpts.
```

Likely code/document areas:

- `AskMode`
- `SystemPromptBuilder`
- `WorkspaceManifest`
- `PromptInspector`
- `CurrentTurnCapabilityFrame`

Why a one-off patch is insufficient:

```text
The mismatch affects any no-tool/direct-answer turn in a workspace with a README
excerpt. Fixing one prompt phrase would leave the hidden evidence channel.
```

## Goal

```text
Read-only no-tool turns must not answer workspace file facts from hidden
manifest content unless the current-turn evidence model and trace make that
manifest explicit as available evidence.
```

## Non-Goals

- No removal of workspace manifest from all modes without design review.
- No weakening of protected-path redaction.
- No web or command capability changes.
- No model-only policy for evidence scope.

## Implementation Notes

```text
Two viable directions: suppress README/file-structure manifest injection when
the effective Ask tool surface is empty and the task is SMALL_TALK/direct-answer,
or promote manifest snippets into an explicit evidence source in prompt-debug
and trace, with current-turn instructions allowing their use. The former is
safer for data minimization; the latter preserves instant-awareness UX but must
be honest.
```

## Architecture Metadata

Capability:

- read-only Ask/Plan evidence framing

Operation(s):

- prompt construction and read-only answer

Owning package/class:

- `AskMode`, `PlanMode`, `SystemPromptBuilder`, `WorkspaceManifest`, `PromptInspector`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: medium
- Approval behavior: unchanged
- Protected path behavior: protected files remain excluded/redacted by manifest policy

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: n/a
- Evidence obligation: current-turn frame and prompt-debug must agree
- Verification profile: prompt-render tests and installed Ask-mode smoke
- Repair profile: n/a

Outcome and trace:

- Outcome/truth warnings: no workspace facts from unmodeled evidence
- Trace/debug fields: manifest evidence either absent or explicit

Refactor scope:

- `<allowed: Ask prompt manifest gating, prompt-debug evidence labeling, focused tests>`
- `<forbidden: broad prompt rewrite, protected-content manifest expansion, mode-router changes>`

## Acceptance Criteria

- In Ask and Plan no-tool/direct-answer turns, the prompt does not include
  README excerpt workspace facts unless trace/prompt-debug explicitly labels
  them as available evidence.
- A prompt that says "without reading or listing files" does not answer a
  workspace file fact solely from a hidden README manifest.
- Ask/Plan workspace-inspection turns that expose read tools still work and can
  read requested files.
- Protected content remains excluded from manifest and prompt-debug.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: Ask/Plan prompt-render or `PromptInspector` test for SMALL_TALK/no-tool read-only turns
- Integration/executor test: Ask/Plan no-tool workspace-fact prompt does not answer from README manifest
- JSON e2e scenario: n/a
- Trace assertion: no-tool frame and manifest evidence are consistent

Manual/TalosBench rerun:

- Prompt family: Ask and Plan mode "without reading/listing files, tell me codename"
- Workspace fixture: README contains codename
- Expected trace: no tools; no hidden manifest fact use, or explicit manifest evidence
- Expected outcome: honest cannot-verify answer if no manifest evidence is exposed

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.modes.AskModeTest" --tests "dev.talos.cli.prompt.PromptInspectorTest" --no-daemon
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- No candidate bump.
- Add a `CHANGELOG.md` `Unreleased` line when complete.

## Known Risks

- Removing manifest evidence too broadly could make Ask feel less workspace
  aware. Keep the change scoped to no-tool/direct-answer turns or make manifest
  evidence explicit and auditable.

## Known Follow-Ups

- Decide whether README manifest snippets are product evidence or only
  orientation hints. The current behavior treats them as both.
