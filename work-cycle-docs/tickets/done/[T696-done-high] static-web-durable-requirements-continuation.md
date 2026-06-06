# T696 - Static-Web Durable Requirements Continuation

Status: done
Severity: high

## Problem

The current static-web creation path can extract and render exact targets,
required visible facts, and forbidden local artifacts, but dirty continuation
can still re-enter with a thinner active static-web context.

In the `test02-10-post-t693-live-audit-20260605-105937` Qwen dirty
continuation, the prompt was:

```text
Make this Retrocats website even more polished and complete. Use Tailwind correctly, preserve the required band facts, and repair anything unverified.
```

The saved turn trace classified it as `FILE_CREATE` with `STATIC_WEB`, but the
contract carried only:

```text
expectedTargets=["index.html","style.css"]
forbiddenTargets=[]
rolefulTargets=index.html/style.css only
```

The same audit's first prompt-debug frame had already shown the fuller contract:

```text
Expected targets: index.html, style.css, script.js
requiredVisibleFacts: Retrocats, Costanza, Merri, ... Life span, ...
forbiddenArtifacts: tailwind.css, tailwind.min.css
```

The final site still omitted the required visible fact `Life span`. Fresh
verification had caught that missing fact, but the dirty continuation trace did
not carry durable requirements strongly enough to make the same preservation
obligation visible in that turn.

## Evidence

- Audit root:
  `local/TalosTestOUTPUT/test02-10-post-t693-live-audit-20260605-105937/`
- Dirty continuation trace:
  `homes/qwen/.talos/sessions/traces/ac2188b79f2affebb0709b3785e3b8912af7b966/000006-trc-dc4835a9-2c2c-45ef-b302-56fe4a8907c4.json`
- Dirty turns log:
  `homes/qwen/.talos/sessions/ac2188b79f2affebb0709b3785e3b8912af7b966.turns.jsonl`
- Prompt-debug creation frame:
  `artifacts/qwen/prompt-debug/prompt-debug-20260606-063348.md`
- Final files:
  `artifacts/qwen/dirty-final/index.html`,
  `artifacts/qwen/dirty-final/style.css`,
  `artifacts/qwen/dirty-final/script.js`
- Code already has the needed carrier surfaces:
  `src/main/java/dev/talos/runtime/task/StaticWebRequirements.java`,
  `src/main/java/dev/talos/runtime/context/ActiveTaskContext.java`,
  `src/main/java/dev/talos/runtime/context/ActiveTaskContextPolicy.java`,
  `src/main/java/dev/talos/runtime/verification/StaticWebContentPreservationVerifier.java`,
  `src/main/java/dev/talos/runtime/JsonSessionStore.java`.

## Architecture Metadata

- Capability ownership: `runtime.task`, `runtime.context`,
  `runtime.verification`, and CLI session persistence.
- Operation type: static-web creation, rewrite, repair, and dirty continuation.
- Risk: high; losing durable requirements can turn a verified factual website
  task into a merely structural web rewrite.
- Approval behavior: unchanged; mutation still requires the existing approval
  gate.
- Protected path behavior: unchanged; requirements must come from explicit user
  text or approved/read evidence, not hidden protected content.
- Checkpoint behavior: unchanged.
- Evidence obligation: prompt-debug and trace must show expected targets,
  forbidden artifacts, and required visible facts when active context is used.
- Verification profile: `STATIC_WEB`.
- Repair profile: static-web repair must preserve requirements and target set.
- Outcome/trace changes: trace should expose restored requirements and forbidden
  artifacts on dirty continuation turns.
- Allowed refactor scope: targeted changes to context persistence, context
  policy, task contract reconstruction, and static-web verifier inputs only.

## Acceptance

- Dirty continuation after an exact static-web creation retains
  `index.html`, `style.css`, and `script.js` when those were the explicit user
  targets.
- Dirty continuation retains explicit required visible facts, including
  `Life span`, and forbidden artifacts such as `tailwind.css` and
  `tailwind.min.css`.
- Static-web content preservation verification reads the retained requirements
  on continuation/repair turns and fails if facts are dropped.
- Status-only or explanation-only prompts remain read-only and do not mutate.
- If a user explicitly replaces the target set or requirements, the new explicit
  contract can supersede the old one and the trace must show why.

## Implementation Evidence

- `ActiveTaskContextUpdater` now preserves a richer active static-web target
  set when a later continuation/failed turn reports only a subset and the user
  has not explicitly replaced the target set.
- Existing `StaticWebRequirements`, `ActiveTaskContext`,
  `JsonSessionStore`, `ActiveTaskContextPolicy`, current-turn frame rendering,
  and static-web content preservation carriers remain in use.
- Focused tests passed:
  `ActiveTaskContextUpdaterTest`,
  `ActiveTaskContextPolicyTest`,
  `JsonSessionStoreTest`, and
  `CurrentTurnCapabilityFrameTest`.

## Regression Tests

- `ActiveTaskContextPolicyTest`: dirty continuation with a stored Retrocats
  context restores all exact targets, required facts, and forbidden artifacts.
- `JsonSessionStoreTest`: stored static-web requirements survive save/load and
  are applied to a later process.
- `StaticWebContentPreservationVerifierTest` or `StaticTaskVerifierTest`:
  dirty continuation rewrite that drops `Life span` fails verification.
- Prompt-audit/trace test: restored requirements render in the current-turn
  frame and trace.

## Non-Goals

- No visual/render proof in this ticket.
- No automatic rollback.
- No broad inference of facts from arbitrary chat history; use explicit
  required-fact spans and safe read evidence only.
