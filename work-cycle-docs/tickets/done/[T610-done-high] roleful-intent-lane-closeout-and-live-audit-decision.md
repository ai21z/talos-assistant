# [T610-done-high] Roleful intent lane closeout and live audit decision

## Status

Done.

## Scope

No runtime code changed.

This ticket closes the roleful intent fix lane that was opened in T600. It is the renumbered form of the roleful intent lane's planned T586.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = a97171b9
```

Predecessor:

```text
T609 = deterministic roleful intent e2e regression pack
```

## What this lane fixed

The lane addressed the highest-risk live-audit defect: Talos was using lexical intent plus flat target sets, so it could confuse scoped constraints, verification mentions, and conventional filenames with required mutation targets.

Fixed and guarded:

| Failure | Fixed by | Guarded by |
| --- | --- | --- |
| Scoped output constraint such as `Do not create extra files` cancels or distorts a mutation request. | T604, T609 | `TaskIntentResolverTest`, `TaskContractResolverTest`, `ToolSurfacePlannerTest`, `StaticWebCapabilityProfileTest`, scenario 84 |
| Constraint mention such as `so index.html still works` becomes a mutation obligation. | T605, T609 | `TaskIntentResolverTest`, `ExpectedTargetProgressAccountingTest`, scenario 85 |
| Existing plural static-web targets are replaced by conventional singular `script.js` / `style.css`. | T606, T607, T609 | `WorkspaceTargetReconcilerTest`, `StaticWebContinuationPlannerTest`, `ToolRepromptMessageOverlayTest`, scenarios 83 and 86 |
| Roleful intent evidence is absent from traces and prompt-debug output. | T608, T609 | `LocalTurnTracePolicyTraceTest`, `PromptDebugInspectorTargetRolesTest`, `JsonSessionStoreTurnsTest`, scenarios 84-86 |

## Integrated ticket sequence

| Ticket | Result |
| --- | --- |
| T600 | Documented the roleful intent lane, acceptance matrix, and renumbered plan. |
| T601 | Added inert roleful intent value types. |
| T602 | Added `TaskIntent` and `TaskContractCompiler`. |
| T603 | Wired roleful intent behind `TaskContractResolver` in parity mode. |
| T604 | Fixed scoped negation failure A. |
| T605 | Fixed constraint mention failure B. |
| T606 | Added workspace target reconciliation. |
| T607 | Fixed static-web continuation exact target naming. |
| T608 | Added roleful trace and prompt-debug evidence. |
| T609 | Added deterministic e2e regression coverage and closed integration holes. |

## Current architecture shape

Roleful intent is now an internal deterministic layer:

```text
dev.talos.runtime.intent
```

The existing compatibility surface remains intact:

- `TaskContractResolver.fromUserRequest(...)`
- `TaskContractResolver.fromMessages(...)`
- `TaskContract.expectedTargets`
- `TaskContract.sourceEvidenceTargets`
- `TaskContract.forbiddenTargets`

The compatibility projection is now backed by roleful target semantics:

- `MUST_MUTATE` and `OUTPUT_DESTINATION` project to expected mutation targets.
- `FORBIDDEN` projects to forbidden targets.
- `SOURCE_EVIDENCE` and source-bound `MUST_READ` project to source evidence.
- `VERIFY_ONLY` remains evidence/verification intent, not mutation progress.
- `MENTIONED_ONLY` remains trace/debug context only.

Workspace-specific reconciliation stays outside the pure intent resolver and is applied where workspace evidence exists.

## Remaining defects and limits

This lane did not make Talos a semantic intent-understanding system. The implementation is still deterministic and lexical, by design for this lane.

Remaining risks:

- Broad natural-language target semantics are still limited to known patterns and tests.
- Ambiguous user wording still needs conservative behavior or follow-up rather than guessing.
- Static-web capability profiling still contains conventional filename heuristics; they are now bounded by workspace reconciliation and regression tests, not removed.
- Live model behavior has not yet been re-audited after the deterministic fixes.
- Phase 5 LLM intent advisory remains intentionally out of scope.

## Decision

The roleful intent lane is complete enough to stop implementation and run a focused live audit.

Do not resume broad architecture or `AssistantTurnExecutor` refactoring before checking the live behavior against the same failure shapes that motivated the lane.

Next move:

```text
Run a focused live audit against qwen2.5-coder:14b and gpt-oss:20b for the roleful intent failure shapes.
```

The audit should use fresh workspaces and capture:

- `/debug prompt on`
- `/last trace` after each natural-language turn
- `/prompt-debug save` or documented fallback after each natural-language turn
- provider-body evidence when available
- final file state
- trace roleful target entries
- prompt-debug roleful target entries

The audit should directly probe:

1. `Improve only styles.css. Do not create extra files. Do not modify index.html or scripts.js.`
2. `Rewrite styles.css so index.html still works.`
3. Existing `scripts.js` / `styles.css` with no singular files.
4. Existing both `script.js` and `scripts.js`, where Talos must not silently guess.
5. True read-only prompts such as `Review index.html. Do not change anything.`
6. True advisory prompts such as `What would you change in styles.css? Do not edit files.`

## Verification

Required local gates for this no-code closeout:

```powershell
git diff --cached --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Non-goals

- Did not change runtime behavior.
- Did not add more intent roles.
- Did not introduce an LLM intent advisor.
- Did not run a live model audit in this ticket.
- Did not resume broad architecture cleanup.
