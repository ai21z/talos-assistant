# [T606] Add workspace target reconciliation

## Summary

T606 fixes the roleful-intent lane's singular/plural drift failure at the
workspace-aware boundary.

Before this ticket, a generic static-web request could infer conventional
targets:

```text
index.html, style.css, script.js
```

even when the current workspace already contained:

```text
styles.css, scripts.js
```

That made Talos push the model and mutation accounting toward the wrong
filenames. The pure intent resolver has no workspace evidence, so the fix is
not inside `TaskIntentResolver` or the legacy resolver. It is a separate
workspace-bound reconciliation step.

After this ticket:

- `scripts.js` replaces unmentioned conventional `script.js` when only
  `scripts.js` exists;
- `styles.css` replaces unmentioned conventional `style.css` when only
  `styles.css` exists;
- if both singular and plural variants exist, Talos does not silently guess the
  conventional singular target;
- explicit user mentions such as `script.js` or `scripts.js` preserve exact
  spelling;
- current-turn prompt frames and policy trace receive the reconciled projection.

## Source Base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = db30e051
talosVersion = 0.9.9
```

Predecessor:

```text
T605 = Fix constraint mention failure B
```

## What Changed

Changed:

```text
src/main/java/dev/talos/runtime/task/WorkspaceTargetReconciler.java
src/main/java/dev/talos/cli/modes/UnifiedAssistantMode.java
src/main/java/dev/talos/cli/modes/AssistantTurnExecutor.java
src/main/java/dev/talos/cli/prompt/PromptInspector.java
src/main/java/dev/talos/runtime/toolcall/ExpectedTargetProgressAccounting.java
src/test/java/dev/talos/runtime/task/WorkspaceTargetReconcilerTest.java
src/test/java/dev/talos/cli/modes/UnifiedAssistantModeTest.java
src/test/java/dev/talos/cli/modes/AssistantTurnExecutorTest.java
```

`WorkspaceTargetReconciler` is deliberately small and deterministic. It checks
only root-level static-web conventional pairs:

```text
script.js  <-> scripts.js
style.css  <-> styles.css
```

It rewrites only unmentioned conventional targets. It does not inspect arbitrary
workspace trees, does not touch role assignment, and does not make
`TaskIntentResolver` filesystem-aware.

## Tests Added

```text
WorkspaceTargetReconcilerTest.existingPluralScriptWinsOverUnmentionedConventionalSingular
WorkspaceTargetReconcilerTest.existingPluralStylesWinsOverUnmentionedConventionalSingular
WorkspaceTargetReconcilerTest.emptyWorkspaceKeepsConventionalStaticSiteTargets
WorkspaceTargetReconcilerTest.ambiguousSingularPluralWorkspaceDoesNotGuessConventionalAssetTargets
WorkspaceTargetReconcilerTest.explicitPluralTargetPreservesExactNameWhenSingularAlsoExists
WorkspaceTargetReconcilerTest.explicitSingularTargetPreservesExactNameWhenPluralAlsoExists
UnifiedAssistantModeTest.promptFrameUsesWorkspaceReconciledStaticWebTargets
AssistantTurnExecutorTest.policyTraceUsesWorkspaceReconciledStaticWebTargets
```

Coverage:

- fake workspace file sets for singular/plural reconciliation;
- ambiguous singular/plural conflict handling;
- exact-name preservation when the user names a file;
- current-turn prompt-frame projection;
- policy-trace projection;
- expected-target progress accounting uses the reconciled contract.

## RED/GREEN Evidence

RED observed before production code:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.WorkspaceTargetReconcilerTest" --tests "dev.talos.cli.modes.UnifiedAssistantModeTest.promptFrameUsesWorkspaceReconciledStaticWebTargets" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.policyTraceUsesWorkspaceReconciledStaticWebTargets" --no-daemon
```

Expected failure:

```text
compileTestJava FAILED
cannot find symbol: WorkspaceTargetReconciler
```

GREEN after implementation:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.WorkspaceTargetReconcilerTest" --tests "dev.talos.cli.modes.UnifiedAssistantModeTest.promptFrameUsesWorkspaceReconciledStaticWebTargets" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest.policyTraceUsesWorkspaceReconciledStaticWebTargets" --no-daemon
BUILD SUCCESSFUL
```

Neighbor suites:

```text
.\gradlew.bat test --tests "dev.talos.runtime.task.*" --tests "dev.talos.runtime.intent.*" --tests "dev.talos.runtime.toolcall.*" --no-daemon
BUILD SUCCESSFUL

.\gradlew.bat test --tests "dev.talos.cli.modes.UnifiedAssistantModeTest" --tests "dev.talos.cli.modes.AssistantTurnExecutorTest" --tests "dev.talos.cli.prompt.PromptInspectorTest" --no-daemon
BUILD SUCCESSFUL
```

## Behavior Status

Fixed in this ticket:

- Failure C root cause at the workspace-aware target projection layer;
- `scripts.js` / `styles.css` existing-file evidence now overrides unmentioned
  conventional singular defaults;
- prompt-debug render and policy trace receive reconciled expected targets;
- target progress accounting no longer compares successful plural-file mutation
  against stale singular conventional names.

Preserved:

- pure resolver behavior and compatibility APIs;
- conventional `script.js` / `style.css` defaults for empty new static-site
  workspaces;
- explicit exact filename spelling when the user names a target;
- T604 scoped-negation behavior;
- T605 verify-only constraint behavior.

Not fixed yet:

- static-web continuation naming from verifier problem payloads;
- roleful trace and prompt-debug evidence fields;
- deterministic end-to-end regression pack;
- post-lane live audit.

## Next Move

```text
[T607] Fix static-web continuation planner naming
```
