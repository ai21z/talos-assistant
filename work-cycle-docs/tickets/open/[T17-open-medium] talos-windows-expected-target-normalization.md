# [open] Ticket: Windows-Aware Expected Target Normalization
Date: 2026-04-27
Priority: medium
Status: open
Architecture references:
- `work-cycle-docs/tickets/new-work.md`
- `work-cycle-docs/tickets/done/talos-static-task-verifier.md`
- `work-cycle-docs/tickets/done/talos-minimal-task-contract.md`
- `local/manual-testing/test-output.txt`

## Why This Ticket Exists

Manual testing showed static verification treating `Index.html` as different
from the successfully mutated `index.html`:

```text
Index.html: expected target was not successfully mutated.
```

On Windows, that is misleading because the filesystem is normally
case-insensitive.

## Problem

Expected target matching normalizes slashes but not platform case semantics.
This creates false static-verification failures when the user capitalizes a path
differently from the actual file.

## Goal

Normalize expected target matching according to platform path semantics.

## Scope

### In scope

- Normalize path separators consistently.
- On Windows, compare expected and mutated targets case-insensitively.
- Preserve case-sensitive behavior on platforms where that is the safer
  default.
- Add tests that do not depend on the developer machine being Windows where
  possible.

### Out of scope

- Broad filesystem abstraction rewrite.
- Changing actual file path casing on disk.
- Index path normalization changes outside the verifier.

## Proposed Work

1. Add a small path matching helper for static verifier target comparisons.
2. Make platform behavior explicit and testable.
3. Update expected-target verification to use that helper.
4. Add regression coverage for `Index.html` vs `index.html`.

## Likely Files / Areas

- `src/main/java/dev/talos/runtime/verification/StaticTaskVerifier.java`
- `src/main/java/dev/talos/runtime/task/TaskContractResolver.java`
- `src/test/java/dev/talos/runtime/verification/StaticTaskVerifierTest.java`
- `src/test/java/dev/talos/runtime/task/TaskContractResolverTest.java`

## Test / Verification Plan

- Unit test path normalization helper.
- Unit test expected target verification with mismatched casing.
- Run focused static verifier tests.

## Acceptance Criteria

- On Windows semantics, `Index.html` matches mutated `index.html`.
- Slash normalization still works.
- The verifier no longer reports false missing-target failures for simple case
  differences on Windows.
