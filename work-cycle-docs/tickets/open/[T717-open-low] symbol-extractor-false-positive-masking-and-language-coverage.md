# T717 - Symbol Extractor False Positive Masking And Language Coverage

Status: open
Priority: low
Created: 2026-06-07

## Evidence Summary

- Source: `work-cycle-docs/research/t708-t715-independent review-review.md`
- Branch: `codex/t708-project-memory-analysis`
- HEAD at creation: `18b9c5b5cf5075f70850696d07438053766849ef`
- Talos version: `0.9.9`

Expected behavior:

```text
The lightweight symbol extractor should avoid obvious phantom symbols from
code-like string literals and have direct tests for every language family it
claims to scan.
```

Observed behavior:

```text
T715 made comment stripping quote-aware enough to avoid dropping symbols after
http://, //, or /* inside same-line string literals. The scanner still preserves
string interiors before regex extraction, so code-like strings can produce
phantom symbols. Template literal quote state is line-oriented, and direct tests
currently cover Java, JavaScript, and Python, but not every in-scope format.
```

## Goal

```text
Improve symbol-extractor quality by masking string interiors before regex
matching and adding direct coverage for the remaining supported language
families.
```

## Non-Goals

- Deferred beyond the current T716 batch.
- No parser/tree-sitter dependency unless a later design ticket justifies it.
- No retrieval pipeline rewrite.
- No vector work.

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

- Risk level: low retrieval-quality risk
- Approval behavior: unchanged
- Protected path behavior: unchanged

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: none
- Evidence obligation: extractor unit tests
- Verification profile: none
- Repair profile: none

Outcome and trace:

- No expected trace shape change.

Refactor scope:

- Allowed: small scanner helper changes in `SymbolExtractor`.
- Forbidden: broad parser dependency without a new design review.

## Acceptance Criteria

- Code-like string content such as `"export function fake() {}"` does not create a phantom symbol hit.
- Existing same-line string/comment-token fixes from T715 remain green.
- Direct tests cover at least TypeScript plus one JVM-adjacent format currently routed through Java-like extraction.
- Any remaining multiline template-literal limitation is documented in code or ticket notes.

## Known Risks

- Over-masking strings could hide legitimate same-line declarations following a string literal if implemented incorrectly.
- Language-perfect extraction is out of scope for this lightweight scanner.
