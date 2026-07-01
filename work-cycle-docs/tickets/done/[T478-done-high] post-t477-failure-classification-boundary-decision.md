# [T478-done-high] Post-T477 Failure Classification Boundary Decision

## Status

Done.

## Scope

T478 inspects the post-T477 `ToolCallExecutionStage` shape and decides whether
the next ticket should extract broad failure accounting, static-web repair
state, or a narrower failure-classification owner. This is a no-code decision
ticket.

It does not change runtime behavior, approval behavior, tool execution,
protected/private handoff, context-ledger capture, read evidence accounting,
mutation accounting, mutation evidence construction, failure classification,
repair behavior, trace wording, prompt wording, outcome wording, or final
answer rendering.

## Snapshot

Measured from fresh `origin/v0.9.0-beta-dev` at `6b1d2915`.

| Item | Measurement |
|---|---:|
| `ToolCallExecutionStage.java` | 688 lines |
| Architecture baseline | 0 |

## Source Evidence

After T477, the stage no longer owns successful read accounting or successful
mutation state accounting. The post-result failure area still includes several
different concerns:

```text
ToolError.DENIED classification
mutating denial flag
unsupported read-path collection
pre-approval path-policy block classification
expected-target scope failure decision
user approval denial flag
ToolOutcome denied/error fields
failure counters and failure-count maps
successful read-cache clearing after mutating failures
failed edit signatures
old_string-not-found classification
stale edit failure recording
static-web full rewrite recovery planning
empty edit argument failure recording
multi-failure edit_file retry suggestion
tool-result formatting after possible retry suggestion mutation
```

This is not one owner. It splits into at least four units:

| Unit | Current source | Decision |
|---|---|---|
| Pure failure classification | denied, user approval denial, pre-approval path-policy block, expected-target scope block, unsupported read path, old-string-not-found | Correct next implementation slice. |
| Generic failure state accounting | `state.failedCalls`, iteration failure count, `failureCountsByTool`, `failureCountsByPath`, read-cache clearing rules | Defer until classification is extracted. |
| Edit failure repair accounting | failed edit signatures, stale edit failures, empty edit failures, multi-failure suggestion | Defer. It changes repair inputs and user-visible retry wording. |
| Static-web full rewrite recovery | `shouldRecoverStaticWebEditFailureWithFullRewrite(...)`, repair target state, repair trace | Defer. It depends on task contracts, static-web capability, and repair context. |

## Decision

Do not extract broad failure accounting next.

The next correct implementation ticket is:

```text
[T479] Extract tool execution failure classifier
```

Target owner:

```text
dev.talos.runtime.toolcall.ToolExecutionFailureClassifier
```

Preferred responsibilities:

- classify whether a result is failed;
- classify `ToolError.DENIED`;
- classify mutating denials;
- classify user approval denials using the existing exact message prefix;
- classify pre-approval path-policy blocks using the existing exact message
  prefixes;
- classify expected-target scope blocks using the existing exact message
  prefix;
- classify unsupported read-file paths using the existing read-file alias
  behavior and normalized path output;
- classify `old_string not found` using the existing error-code and message
  checks.

`ToolCallExecutionStage` should keep:

- applying classification results to iteration flags;
- setting `state.failureDecision` for expected-target scope blocks;
- generic failure counters;
- read-cache clearing after mutating failures;
- failed edit signatures;
- stale edit failure recording;
- static-web full rewrite recovery;
- empty edit failure recording;
- multi-failure edit retry suggestion;
- `ToolOutcome` construction;
- tool-result message formatting.

## Why This Slice Is Correct

Pure classification is the safe prerequisite for any later failure accounting.
It has no state mutation, no trace side effects, and no output wording changes.
It also removes string-prefix and error-code interpretation from the stage
before a later ticket decides whether state accounting or edit-repair
accounting is coherent.

Trying to extract broad failure accounting now would couple unrelated behavior:
expected-target decisions, approval-denial flags, stale edit repair, static-web
repair recovery, cache invalidation, and retry suggestion wording.

## Rejected Immediate Work

### Extract broad failure accounting

Rejected for T479.

The current block mutates global loop state, local iteration counters, failure
decisions, repair state, and user-visible error wording. That is too much for
one safe implementation ticket.

### Extract static-web full rewrite recovery

Rejected for T479.

That owner is not pure failure classification. It depends on task contracts,
static-web file classification, repair context, trace events, and expected
targets.

### Extract edit failure repair state

Rejected for T479.

Edit failure repair state should be considered only after old-string and
path-policy classification has a dedicated owner. It includes failed call
signatures, stale edit failures, empty edit argument failures, and retry
suggestion wording.

## Required T479 Tests

Start with RED tests for `ToolExecutionFailureClassifier`:

- denied mutating result is classified as denied and mutating denied;
- approval denial is classified only when the exact existing
  `"User did not approve "` prefix is present;
- pre-approval path-policy block and expected-target scope block are classified
  using the existing exact prefixes;
- unsupported failed `read_file` result returns the normalized unsupported
  read path while a non-read tool does not;
- `old_string not found` is classified only for `INVALID_PARAMS` failures with
  the existing message text;
- `ToolCallExecutionStage` delegates failure classification and no longer owns
  `isUserApprovalDenial(...)`, `isPreApprovalPathPolicyBlock(...)`,
  `isExpectedTargetScopeBlock(...)`, or `isOldStringNotFound(...)`.

Focused checks should include:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolExecutionFailureClassifierTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.toolcall.EditFilePreApprovalGuardTest" --tests "dev.talos.runtime.toolcall.ExpectedTargetScopeRepairPlannerTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.runtime.ToolCallLoopTest.*approval*" --tests "dev.talos.runtime.ToolCallLoopTest.*oldString*" --tests "dev.talos.runtime.ToolCallLoopTest.*expectedTarget*" --no-daemon
```

Then run the normal gates:

```powershell
.\gradlew.bat validateArchitectureBoundaries --no-daemon
git diff --check
.\gradlew.bat check --no-daemon
```
