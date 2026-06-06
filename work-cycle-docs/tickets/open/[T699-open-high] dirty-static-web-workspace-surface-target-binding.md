# T699 - Dirty Static-Web Workspace-Surface Target Binding

Status: open
Severity: high

## Problem

The T698 synchronized dirty audit proves that a new Talos process can recognize a static-web prompt enough to select `STATIC_WEB`, but still lose exact targets and requirements. With no exact target binding, the apply tool surface falls back to broad workspace mutation tools, and GPT-OSS wrote `README.md` during a Retrocats website polishing/repair prompt.

This is not hidden session pollution. The dirty process printed that a saved session was found but not loaded, and prompt-debug showed `history=0` and `activeTaskContext: NONE_OR_NOT_DERIVED`.

## Evidence

- Audit root:
  `local/TalosTestOUTPUT/test02-11-post-t697-t698-sync-audit-20260606-131440/`
- Qwen dirty:
  - `artifacts/qwen/SESSION-DIRTY-OUTPUT.txt`
  - prompt-debug: expected targets `(none)`, broad mutation tools, `activeTaskContext: NONE_OR_NOT_DERIVED`.
- GPT-OSS dirty:
  - `artifacts/gptoss/SESSION-DIRTY-OUTPUT.txt`
  - `talos.write_file -> README.md [ok]`
  - `Verification: READBACK_ONLY - Target/readback checks passed for 1 mutated target(s); no task-specific static verifier was applicable.`
  - `Outcome: COMPLETE (COMPLETED_UNVERIFIED)`
- Final file:
  `local/TalosTestOUTPUT/test02-11-post-t697-t698-sync-audit-20260606-131440/workspaces/gptoss/README.md`
- Source:
  - `ToolSurfacePlanner.staticWebFullFileApplyTargets(...)` requires exact static-web expected targets before selecting the safe `write_file`-only surface.
  - `ToolSurfacePlanner` broad fallback exposes workspace operations when no expected target predicate matches.
  - `TargetScopeStaticVerifier` returns immediately when both expected and forbidden targets are empty.

## Architecture Metadata

- Capability ownership: static-web target binding / task contract resolution / tool-surface policy.
- Operation type: mutation-capable static-web follow-up in an existing workspace.
- Risk: high. Missing targets can permit unrelated writes and skip task-specific static verification.
- Approval behavior: approval must remain required for writes, but wrong target writes should be blocked before approval when the workspace surface implies canonical static-web targets.
- Protected path behavior: unchanged.
- Checkpoint behavior: unchanged; if a valid expected static-web target write proceeds, existing checkpoint rules apply.
- Evidence obligation: prompt-debug must show reconstructed canonical targets or explicitly state why no static-web target binding was possible.
- Verification profile: `STATIC_WEB`.
- Repair profile: static-web repair/full-file replacement should use canonical web targets.
- Outcome/trace changes: trace should show expected targets and target roles for dirty workspace-surface continuations.
- Allowed refactor scope: `TaskContractResolver`, `StaticWebCapabilityProfile`, `WorkspaceTargetReconciler`, `ToolSurfacePlanner` tests, and static-web target-policy helpers only.

## Acceptance

- In a new process with no loaded session, if the workspace contains a small static-web surface such as `index.html`, linked `style.css`, and linked `script.js`, prompts like:
  - `Make this Retrocats website even more polished and complete.`
  - `Use Tailwind correctly, preserve facts, and repair anything unverified.`
  - `Make this website better.`
  become mutation-capable static-web contracts with expected targets bound to canonical web files.
- Reconstructed targets prefer:
  1. exact file list in the current user prompt,
  2. `index.html` linked local CSS/JS,
  3. existing canonical small web files.
- The prompt does not silently inherit hidden prior-session facts when the session is not loaded.
- If facts are needed, they come from the current user prompt or current workspace reads, not hidden session state.
- The apply tool surface for broad static-web polish/repair is narrowed to read/list/grep/retrieve/write_file for canonical web targets.
- A model attempt to write `README.md` under this static-web prompt is rejected before approval.
- Status/explanation prompts remain read-only.

## Tests

- `TaskContractResolverTest`: dirty static-web polish prompt over an existing `index.html` + linked `style.css` + `script.js` workspace reconstructs expected targets.
- `ToolSurfacePlannerTest`: reconstructed static-web target contract uses the narrow write-file static-web surface, not broad workspace operations.
- `ApprovalGatedToolTest` or tool-call execution test: `write_file(README.md)` is blocked before approval when the current contract has reconstructed static-web expected targets.
- `StaticTaskVerifierTest`: dirty continuation writing only `README.md` cannot produce `READBACK_ONLY` completion for a static-web polish prompt.

## Non-Goals

- Do not load prior sessions implicitly.
- Do not infer detailed Retrocats requirements from hidden history.
- Do not add visual/render verification.
