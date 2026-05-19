# T325 - Python Command Boundary And Audit Assertions

Severity: High

Status: Open

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
- natural prompts like `run pytest` may not always become deterministic unsupported-command contracts.
- Python file verification is readback-only, not syntax or semantic verification.

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
pythonExecutionRequestsBecomeUnsupportedCommandContract
createPythonAndRunTestsDoesNotClaimExecution
pythonReadbackOnlyDoesNotClaimAlgorithmVerified
talosbenchCaseFailsWhenExpectedPythonFilesAreMissing
```

## Fix Direction

1. Add unsupported natural-command detection for Python execution/test prompts.
2. Strengthen final-answer suppression for Python readback-only mutations.
3. Add audit runner assertions for expected final files where the scenario requires file creation.

