# [T715-done-low] String-Aware Symbol Comment Stripping

Status: done
Priority: low

## Evidence Summary

- Source: static code review of T710 symbol extraction and `work-cycle-docs/research/t708-t712-independent review-review.md`
- Date: 2026-06-07
- Talos version / commit: `talosVersion=0.9.9`, branch `codex/t708-project-memory-analysis`, HEAD `18b9c5b5cf5075f70850696d07438053766849ef`
- Model/backend: not applicable; deterministic extractor follow-up
- Workspace fixture: not applicable
- Raw transcript path: not applicable
- Trace path or `/last trace` summary: not applicable
- File diff summary: no runtime failure transcript; code review found regex comment stripping in `SymbolExtractor` is not string-aware
- Approval choices: not applicable
- Checkpoint id: not applicable
- Verification status: focused and full checks passed on 2026-06-07

Closeout evidence, 2026-06-07:

- Added string-literal regression coverage for `http://`, `/*`, and `//` inside JS string literals.
- Replaced `SymbolExtractor.stripComments(...)` with a small quote-aware scanner that preserves comment-like tokens inside single, double, and backtick quoted literals while still stripping real line and block comments.
- Existing comment-only symbol suppression remains covered.
- Commands passed:
  - `.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.RagServiceSymbolRetrievalTest" --tests "dev.talos.runtime.SessionMemoryTest" --tests "dev.talos.runtime.trace.PromptAuditSnapshotTest" --no-daemon`
  - `.\gradlew.bat test --tests "dev.talos.core.index.*" --tests "dev.talos.core.rag.*" --tests "dev.talos.runtime.*" --tests "dev.talos.runtime.trace.*" --tests "dev.talos.cli.prompt.*" --tests "dev.talos.cli.repl.slash.*" --no-daemon`
  - `git diff --check`
  - `.\gradlew.bat check --no-daemon`

Redacted prompt sequence:

```text
Review T710 symbol extraction correctness against code.
```

Expected behavior:

```text
The lightweight symbol extractor should ignore actual comments without treating
comment-like tokens inside string or character literals as comments.
```

Observed behavior:

```text
SymbolExtractor.extract(...) calls stripComments(...) per line. stripComments(...)
uses simple comment token scanning and block-comment state, not Java/JS/Python string
or character literal state. A line containing "http://", "/*", or "//" inside a
literal can be truncated or can enter block-comment mode incorrectly.
```

## Classification

Primary taxonomy bucket:

- `CURRENT_TURN_FRAME`

Secondary buckets:

- `MODEL_COMPETENCE`

Blocker level:

- future milestone

Why this level:

```text
This can cause false-negative or corrupted symbol evidence, but it is not a known
privacy leak or mutation safety defect. It should be fixed to improve structure-first
retrieval quality after higher-risk sidecar safety tests are in place.
```

## Architectural Hypothesis

Bad ticket framing to avoid:

```text
Replace symbol extraction with a full parser.
```

Architectural hypothesis:

```text
Talos intentionally uses a lightweight deterministic extractor. The immediate defect
is the comment-stripping state machine, not the absence of a full AST. A small
string/char-aware scanner can preserve the current simple architecture while removing
common false negatives.
```

Likely code/document areas:

- `src/main/java/dev/talos/core/index/SymbolExtractor.java`
- `src/test/java/dev/talos/core/index/SymbolExtractorTest.java`
- `work-cycle-docs/tickets/done/[T710-done-high] structure-first-code-retrieval-and-symbol-index.md`

Why a one-off patch is insufficient:

```text
The extractor feeds model-visible symbol evidence. If it misreads comment-like text
inside literals, it can silently drop useful structure evidence across Java, JS/TS,
and Python codebases.
```

## Goal

```text
Make comment stripping string/char-literal aware enough that common URL, regex, and
comment-token literals do not corrupt symbol extraction.
```

## Non-Goals

- No shell/browser unless the milestone explicitly includes it.
- No MCP or multi-agent behavior unless explicitly approved.
- No LLM classifier for safety-critical permission, privacy, mutation, or verification policy.
- No giant untyped phrase dump without an owner policy.
- No bypassing approval, permission, checkpoint, trace, or verification.
- No committing raw private transcripts.
- No full AST parser or tree-sitter dependency.
- No broad RAG rewrite.
- No language-perfect parser guarantee.

## Implementation Notes

```text
Add RED tests first. The fix should likely be a small scanner that tracks single,
double, and backtick/template quotes where relevant, escaped characters, line
comments, and block comments. Keep behavior deterministic and conservative.
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

- None expected

Risk, approval, and protected paths:

- Risk level: retrieval quality risk
- Approval behavior: unchanged
- Protected path behavior: unchanged; protected filtering must still happen before symbol visibility

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: not applicable
- Evidence obligation: extractor unit tests
- Verification profile: deterministic unit tests
- Repair profile: not applicable

Outcome and trace:

- Outcome/truth warnings: no new user-visible claims expected
- Trace/debug fields: unchanged

Refactor scope:

- Allow extracting comment scanning into a private helper/state record.
- Do not replace `SymbolExtractor` with a parser framework.

## Acceptance Criteria

- `SymbolExtractorTest` covers a Java or JS line containing `http://` inside a string literal and proves symbols on that line or subsequent lines still extract correctly.
- `SymbolExtractorTest` covers a string or character literal containing `/*` and proves block-comment state is not incorrectly entered.
- `SymbolExtractorTest` covers a string literal containing `//` and proves the line is not incorrectly truncated.
- Existing comment-only symbol suppression still works for real `//` line comments and `/* ... */` block comments.
- The implementation remains deterministic, local, and dependency-light.
- No regressions to privacy, permissions, checkpointing, trace redaction, or outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: `SymbolExtractorTest` for string-literal `http://`, `//`, and `/*` cases.
- Integration/executor test: not required.
- JSON e2e scenario: not required.
- Trace assertion: not required.

Manual/TalosBench rerun:

- Prompt family: not required.
- Workspace fixture: not required.
- Expected trace: not applicable.
- Expected outcome: improved symbol hits for code containing comment-like literals.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.core.index.SymbolExtractorTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.index.*" --no-daemon
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- Use the inner dev loop unless the ticket explicitly declares a candidate.
- Do not bump version unless this is candidate closeout.
- Do not update `CHANGELOG.md` unless this is candidate closeout.
- Keep this ticket behind T713 if prioritizing trust before retrieval quality.

## Known Risks

- Template strings and language-specific escape rules can become complex. Keep the first fix intentionally bounded and test the exact supported cases.
- Overfitting Java-only scanner behavior may leave JS/Python quirks. Document any remaining language limitations if not fixed.

## Known Follow-Ups

- If symbol extraction becomes central to code tasks, consider a later parser-backed extractor by language, but only with a clear privacy and dependency review.
