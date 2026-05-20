# T325 - Python Command Boundary And Audit Assertions

Severity: High

Status: still-open - deterministic Python command boundary slice implemented; audit-runner expected-file assertion remains

Source: Five scenario big audit and Agent 4 static audit, 2026-05-19

## Problem

Talos is safe around Python because it cannot run arbitrary Python. It is weak around Python because it cannot verify algorithmic correctness and natural Python execution requests are not always deterministically routed to an unsupported-command outcome.

The exploratory Python case also exposed an audit-design weakness: the case passed even though the requested Python files were not actually created.

## Evidence

Local transcript:

```text
local/manual-testing/five-scenario-audit-20260519-221645/20260519-221949/five-python-algorithmic-logic.txt
```

Static audit found:

- `talos.run_command` is Gradle-profile bounded, not arbitrary shell.
- natural prompts like `run pytest` did not always become deterministic unsupported-command contracts. This is now covered for Python/pytest/.py execution prompts by deterministic classifier and outcome tests.
- Python file verification is readback-only, not syntax or semantic verification.

Fresh focused implementation evidence from 2026-05-20:

- `TaskContractResolver.looksUnsupportedPythonCommandExecutionRequest(...)` detects standalone Python/pytest/.py execution requests and routes non-mutating execution prompts to `unsupported-command-verification-request`.
- `ToolSurfacePlanner` exposes no command tool for those unsupported Python command contracts.
- `ExecutionOutcome` replaces unsupported Python execution/test success prose when no command result exists, including mixed turns where Python files were created but requested Python/pytest execution did not run.
- Focused verification passed:
  - `./gradlew.bat test --tests "dev.talos.runtime.task.TaskContractResolverTest" --no-daemon`
  - `./gradlew.bat test --tests "dev.talos.runtime.toolcall.ToolSurfacePlannerTest" --no-daemon`
  - `./gradlew.bat test --tests "dev.talos.cli.modes.ExecutionOutcomeTest" --no-daemon`

## Expected Behavior

Talos may create/edit `.py` files after approval, but must not claim:

```text
tests passed
I ran pytest
the algorithm is verified
```

unless command-profile evidence or a deterministic verifier proves it.

## Regression Tests

Add:

```text
pythonExecutionRequestsBecomeUnsupportedCommandContract - added
pythonExecutionRequestsExposeNoCommandTool - added
unsupportedPythonCommandGetsDeterministicDirectAnswer - added
createPythonAndRunTestsDoesNotClaimExecution - added
pythonReadbackOnlyDoesNotClaimAlgorithmVerified - added
talosbenchCaseFailsWhenExpectedPythonFilesAreMissing
```

## Fix Direction

1. Add unsupported natural-command detection for Python execution/test prompts.
2. Strengthen final-answer suppression for Python readback-only mutations.
3. Add audit runner assertions for expected final files where the scenario requires file creation.

## Remaining Blockers

- Add the audit/TalosBench expected-file assertion so a Python scenario cannot pass when `dijkstra.py` or `test_dijkstra.py` was not actually created.
- Run a focused mini-audit after that assertion exists. Deterministic unit coverage is stronger now, but there is not yet fresh live/audit evidence for this ticket after the 2026-05-20 command-boundary slice.
