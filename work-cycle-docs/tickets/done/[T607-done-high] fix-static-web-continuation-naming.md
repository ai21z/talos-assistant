# [T607-done-high] Fix static-web continuation naming

## Status

Done.

## Scope

Fixed static-web verification continuation target naming so verifier- or HTML-derived exact asset names win over conventional fallback names.

This ticket is the renumbered form of the roleful intent lane's planned T583.

## Problem

After partial static-web mutation, `StaticWebContinuationPlanner` could infer the exact missing linked asset, such as `scripts.js`, and still append the conventional fallback `script.js` from the same JavaScript verification problem.

That produced wrong user-visible continuation and stop text such as:

```text
Remaining target(s): script.js
```

even when the verifier and HTML evidence pointed at:

```text
scripts.js
```

## Change

- `StaticWebContinuationPlanner` now records exact targets extracted from verifier backticks and mutated HTML links.
- Conventional fallback names are added only when the relevant verifier problem did not already name that asset family.
- If exact linked/verifier evidence names a non-conventional small web file, the matching conventional fallback is removed.
- Existing conventional behavior remains for vague verifier problems that do not identify an exact file.

## Tests

Added or updated:

- `StaticWebContinuationPlannerTest.verificationFailurePlanPreservesExactLinkedPluralScriptTarget`
- `ToolRepromptMessageOverlayTest.expectedTargetProgressMessagePreservesExactPluralScriptTarget`
- `JsonScenarioPackTest.staticVerificationContinuationPreservesScriptsJs`
- `scenarios/83-static-verification-continuation-preserves-scripts-js.json`

## Verification

RED observed before production change:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticWebContinuationPlannerTest.verificationFailurePlanPreservesExactLinkedPluralScriptTarget" --tests "dev.talos.runtime.toolcall.ToolRepromptMessageOverlayTest.expectedTargetProgressMessagePreservesExactPluralScriptTarget" --no-daemon
```

Failed because continuation missing targets were:

```text
[script.js, scripts.js]
```

GREEN after implementation:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.StaticWebContinuationPlannerTest" --tests "dev.talos.runtime.toolcall.ToolRepromptMessageOverlayTest" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest.staticVerificationContinuationPreservesScriptsJs" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --no-daemon
.\gradlew.bat e2eTest --tests "dev.talos.harness.JsonScenarioPackTest" --no-daemon
```

## Non-goals

- Did not rewrite static-web verification.
- Did not change broad task intent classification.
- Did not add an LLM intent advisor.
- Did not start live-model audit work.
