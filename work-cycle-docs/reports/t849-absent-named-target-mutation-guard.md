# T849 Absent Named Target Mutation Guard

Status: implemented-awaiting-review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Created: 2026-06-21

## Summary

T849 adds a narrow pre-execution guard for high-confidence named-function
mutation requests. If the user asks to modify `foo()` in `helper.py`, Talos now
requires complete same-turn read evidence before approval and blocks the
mutation when that evidence proves `foo()` is absent.

The ticket remains open. The deterministic guard and focused tests are in
place, but the T842 scn-14 live rerun on the audited models is still required
before closeout.

Post-implementation review found that the first implementation covered the
simplified prompt `Modify foo() in helper.py...` but missed the real scn-14
shape `Modify the existing function foo() in helper.py...`. Pass 2 keeps the
same mutation-verb gate and adds bounded descriptor words between the verb and
`foo()`. It also pins that `Add a function foo() to helper.py...` remains
allowed so the guard does not over-block legitimate creation requests.

## Scope

Production source changes are limited to:

- `src/main/java/dev/talos/runtime/toolcall/NamedTargetExistenceGuard.java`
- `src/main/java/dev/talos/runtime/toolcall/ToolCallPreExecutionGuardChain.java`

Test changes:

- `src/test/java/dev/talos/runtime/toolcall/NamedTargetExistenceGuardTest.java`

Work-cycle/documentation changes:

- `CHANGELOG.md`
- `work-cycle-docs/tickets/open/[T849-open-high] absent-named-target-mutation-guard.md`
- this report

No `site/` files are in scope.

## Implemented Behavior

The guard applies only when all of these are true:

- the original user request has a high-confidence `name()` in `file` mutation
  shape, for example `Modify foo() in helper.py` or
  `Modify the existing function foo() in helper.py`;
- the tool call is `write_file` or `edit_file`;
- the mutation path matches the named file.

For matching requests:

- missing complete same-turn read evidence blocks the mutation before approval;
- a complete readback that contains the named target allows the mutation to
  proceed to the existing approval/execution path;
- a complete readback that proves the named target is absent blocks before
  execution with `INVALID_PARAMS`, failed pre-execution mutation accounting, a
  model-visible tool-result error, and a `NAMED_TARGET_EXISTENCE` trace action
  obligation.

The Python target check is definition-oriented:

```text
def foo(
async def foo(
```

For non-Python paths the guard uses a conservative call-shaped existence check
for the named target. The current T842 finding is Python-specific.

## Non-Claims

- T849 does not add broad semantic code understanding.
- T849 does not replace symbol indexing or retrieval.
- T849 does not validate every possible natural-language target form.
- T849 does not close T850/T851/T852.
- T849 does not replace old-string-not-found protection, exact-write
  verification, approval, or anti-overclaim checks.

## Red-First Evidence

The first focused run was red before production changes:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.NamedTargetExistenceGuardTest" --no-daemon
```

Result: failed because
`writeFileIsBlockedWhenRequestedFunctionTargetIsAbsentFromSameTurnReadback`
expected `result.blocked() == true`, while the existing guard chain allowed
the `write_file` call.

## Green Evidence

After implementation, the focused T849 guard tests passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.NamedTargetExistenceGuardTest" --no-daemon
```

Result: passed.

Post-review pass 2 added red coverage for the exact real scn-14 prompt:

```text
Modify the existing function foo() in helper.py so it returns 99. Do not add new functions.
```

The focused test failed before the regex expansion because the guard did not
match descriptor words between `Modify` and `foo()`. After the bounded
descriptor expansion, the focused test passed and the add/create non-overblock
probe stayed green:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.NamedTargetExistenceGuardTest" --no-daemon
```

Result: passed.

The broader runtime/tool-call focused suite also passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.*" --tests "dev.talos.runtime.ToolCallLoopTest" --no-daemon
```

Result: passed.

The ticket's focused runtime/tool-call filter passed:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.*" --tests "dev.talos.runtime.toolcall.*" --no-daemon
```

Result: passed.

The full Java gate passed:

```powershell
.\gradlew.bat check --no-daemon
```

Result: passed after correcting the open ticket body to keep `Status: open`
for ticket-hygiene compatibility while recording implementation state
separately.

The wiki close gate passed:

```powershell
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

Result: passed.

## Review Requirements Before Closeout

- Re-run T842 scn-14 on `qwen2.5-coder:14b` and `gpt-oss:20b` through the
  accepted managed `llama.cpp` audit path.
- Confirm absent `foo()` in `helper.py` is reported and no other function is
  mutated.
- Confirm valid named-target edits still pass through the normal approval path.
- Confirm no regression to T848 natural beta prompts.
