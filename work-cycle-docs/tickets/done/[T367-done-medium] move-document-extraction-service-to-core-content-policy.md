# [T367-done-medium] Move Document Extraction Service To Core Content Policy

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T367`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T366-done-medium] extract-private-document-content-policy`

## Evidence Summary

- Source: post-T366 implementation after PR #31 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `4c5719b6137d49d518bf075564a5d01b4b1f2184`.
- Beta push CI: run `#89`, `Beta Dev CI`, push event for `4c5719b6`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T367`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- Verification status: passed locally before commit.

## Problem

After T366, `DocumentExtractionService` still had one core-to-runtime policy
edge:

```text
core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy
```

The remaining call was only `modelHandoffAllowed(...)`. T366 already moved
that pure content handoff decision into
`dev.talos.core.privacy.PrivateDocumentContentPolicy`, so keeping the runtime
facade import in core extraction was stale ownership debt.

## Change

T367 changes `DocumentExtractionService` to use:

```text
dev.talos.core.privacy.PrivateDocumentContentPolicy
```

for model handoff decisions.

No extraction behavior changed. `PrivateDocumentPolicy` remains available as a
runtime facade for runtime and CLI callers that still need runtime-owned
privacy notes or compatibility.

## Baseline Result

Architecture baseline moved:

```text
18 -> 17
```

Removed entry:

```text
core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy
```

## Verification

- RED architecture ratchet:
  `.\\gradlew.bat validateArchitectureBoundaries --no-daemon` failed as
  expected with the single removed `DocumentExtractionService ->
  PrivateDocumentPolicy` baseline row.
- RED ownership test:
  `.\\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest.service_uses_neutral_sanitizer_and_core_private_document_content_policy" --no-daemon`
  failed before implementation because the service still imported runtime
  policy.
- Focused GREEN test run:
  `.\\gradlew.bat test --tests "dev.talos.core.extract.DocumentExtractionServiceTest" --tests "dev.talos.core.privacy.PrivateDocumentContentPolicyTest" --tests "dev.talos.runtime.policy.PrivateDocumentPolicyTest" --tests "dev.talos.tools.impl.ReadFileToolTest" --no-daemon`
  passed.
- `.\\gradlew.bat validateArchitectureBoundaries --no-daemon`: passed.
- Final verification before commit:
  `git diff --check` and `.\\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

After T367, inspect the remaining `17` baseline entries before choosing T368.
The private-document policy track no longer has cheap pure-content call sites.
Likely next tracks are RAG context-ledger ownership, runtime-to-CLI session
context ownership, or SPI purity, each requiring source inspection before code.

Confidence: high.
