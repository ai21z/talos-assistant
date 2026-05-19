# T324 - Source-To-Code Target Extraction

Severity: High

Status: Open

Source: Five scenario big audit, 2026-05-19

## Problem

For code-generation prompts that cite a source file, Talos can confuse the source evidence file with the expected output target.

The Python live audit asked Talos to create implementation/test files according to a problem statement. Talos blocked valid output paths because the expected target set contained the source file.

## Evidence

Local transcript:

```text
local/manual-testing/five-scenario-audit-20260519-221645/20260519-221949/five-python-algorithmic-logic.txt
```

Observed behavior:

- source file: `problem.md`
- requested output files: Python implementation and test files
- runtime blocked the output write as outside the expected target set

## Expected Behavior

For:

```text
Create dijkstra.py and test_dijkstra.py according to problem.md.
```

Task contract should distinguish:

- source evidence target: `problem.md`
- expected mutation targets: `dijkstra.py`, `test_dijkstra.py`

The source file should be read before writing, but it should not become the only allowed mutation target.

## Regression Tests

Add:

```text
sourceBackedCodeGenerationSeparatesSourceAndOutputTargets
problemMdDoesNotBecomeOnlyExpectedMutationTarget
multiOutputCodeGenerationAllowsImplementationAndTestFiles
```

## Fix Direction

Review `MutationIntent` and `TaskContractResolver` source-to-target parsing. Extend it to represent source evidence and output target sets separately wherever possible.

