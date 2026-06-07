# T718 - Preserve Java Method Symbols With Throws Clauses

Status: done
Priority: low
Completed: 2026-06-07

## Evidence Summary

- Source: PR review comment on `src/main/java/dev/talos/core/index/SymbolExtractor.java`
- Date: 2026-06-07
- Talos version / commit: `talosVersion=0.9.9`, branch `feature/t708-project-memory-analysis`, HEAD `608dd7675226b3dfecff88d1ea0bafc8cc9d528c`
- Model/backend: not applicable; deterministic extractor follow-up
- Workspace fixture: not applicable
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: `SymbolExtractor.JAVA_METHOD` requires `{`, `;`, or end-of-line immediately after `)`, so Java declarations with `throws` are not matched.
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: focused, adjacent symbol-retrieval, and full local `check` gates passed on 2026-06-07

Expected behavior:

```text
Java methods and interface methods with `throws` clauses should be extracted as
method symbols, with the original signature preserved as line evidence.
```

Observed behavior:

```text
Declarations such as `public void load() throws IOException {` and
`void close() throws Exception;` place `throws ...` between the parameter list
and the body/semicolon delimiter, so the current regex does not match them.
```

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `MODEL_COMPETENCE`

Blocker level:

- candidate follow-up

Why this level:

```text
This is not a safety or privacy blocker, but it degrades structure-first Java
retrieval for common method declarations.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Replace the lightweight extractor with a Java parser.
```

Architectural hypothesis:

```text
The extractor intentionally uses lightweight deterministic regex scanning. The
specific gap is that the Java method scanner does not allow a bounded `throws`
clause before the method body or semicolon.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/index/SymbolExtractor.java`
- `src/test/java/dev/talos/core/index/SymbolExtractorTest.java`

Why a one-off patch is insufficient:

```text
The extractor feeds retrieval evidence, so common Java syntax gaps should become
unit-level regression tests rather than review-only notes.
```

## Goal

```text
Extract ordinary Java methods and interface methods that include `throws`
clauses without expanding the extractor into a full parser.
```

## Non-Goals

- No full Java parser or tree-sitter dependency.
- No retrieval pipeline rewrite.
- No changes to privacy, approval, checkpoint, trace, or tool policy.

## Implementation Notes

```text
Add a focused regression for class and interface methods with `throws` clauses,
then minimally extend the Java method delimiter suffix to allow a bounded throws
clause before `{`, `;`, or end-of-line.
```

## Architecture Metadata

Capability:

- Structure-first code retrieval / symbol extraction

Operation(s):

- index
- retrieve

Owning package/class:

- `dev.talos.core.index.SymbolExtractor`

New or changed tools:

- none

Risk, approval, and protected paths:

- Risk level: retrieval quality risk
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: extractor unit tests
- Verification profile: deterministic unit tests
- Repair profile: not applicable

Outcome and trace:

- Outcome/truth warnings: unchanged
- Trace/debug fields: unchanged

Refactor scope:

- Allowed: small regex/helper change in `SymbolExtractor`.
- Forbidden: broad parser dependency or unrelated symbol-index refactor.

## Acceptance Criteria

- `SymbolExtractorTest` proves Java class methods with `throws` clauses are extracted.
- `SymbolExtractorTest` proves Java interface methods with `throws` clauses are extracted.
- The original signature line remains preserved in symbol evidence.
- Existing constructor exclusion and string-literal phantom-symbol tests remain green.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `dev.talos.core.index.SymbolExtractorTest`
- Integration/executor test: not required
- JSON e2e scenario: not required
- Trace assertion: not required

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.SymbolExtractorTest" --no-daemon
git diff --check
```

## Work-Test Cycle Notes

- Use the inner dev loop.
- Do not bump version unless this becomes candidate closeout.
- Do not update `CHANGELOG.md` unless this becomes candidate closeout.

## Known Risks

- A too-loose suffix could match malformed code or control-flow-like statements.
- A too-strict suffix could still miss fully qualified or generic exception types.

## Known Follow-Ups

- If additional Java syntax gaps appear, consider a separate design ticket for parser-backed extraction.

## Completion Evidence

- Added a regression test for Java class and interface methods with `throws` clauses.
- Extended `SymbolExtractor.JAVA_METHOD` to allow an optional bounded `throws` clause before `{`, `;`, or end-of-line.
- Verified existing constructor exclusion and string-literal phantom-symbol tests still pass through `SymbolExtractorTest`.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.SymbolExtractorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --tests "dev.talos.tools.impl.RetrieveToolTest" --no-daemon
.\gradlew.bat check --no-daemon
git diff --check
```
