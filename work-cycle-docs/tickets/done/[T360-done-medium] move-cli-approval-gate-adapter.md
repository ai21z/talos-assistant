# [T360-done-medium] Move CLI Approval Gate Adapter Out Of Runtime

Status: done
Priority: medium
Date: 2026-05-22
Branch: `T360`
Candidate version: `talosVersion=0.9.9`
Parent baseline: `config/architecture-boundary-baseline.txt`
Predecessor: `[T359-done-medium] extract-private-document-indexing-policy`

## Evidence Summary

- Source: post-T359 implementation after PR #24 merged into
  `v0.9.0-beta-dev`.
- Base branch: `origin/v0.9.0-beta-dev` at
  `109d6a90cf6ed6d9fda050e5381e0a1d932b4465`.
- Beta push CI: run `#68`, `Beta Dev CI`, push event for `109d6a90`,
  completed successfully.
- Talos version / commit: `0.9.9` / local working tree on `T360`.
- Model/backend: none; no live model was run.
- Workspace fixture: repository checkout.
- File diff summary:
  - moved CLI terminal approval adapter from runtime ownership to
    `dev.talos.cli.approval`;
  - kept `ApprovalGate`, `ApprovalResponse`, and `NoOpApprovalGate` in
    runtime;
  - moved `CliApprovalGateTest` with the adapter;
  - moved CLI-specific protected-read rendering coverage out of
    `ApprovalGateTest`;
  - removed runtime Javadocs that directly named the CLI adapter;
  - architecture baseline reduced by two stale entries.
- Verification status: passed.

## Problem

`ApprovalGate` is a runtime contract. It belongs in runtime because runtime
tool execution asks for approval through that interface.

`CliApprovalGate` was different. It was a concrete terminal adapter that:

- printed CLI approval UI;
- depended on `ApprovalPromptRenderer`;
- depended on `CliTheme`;
- read user input through scanner or JLine line-reader integration.

Keeping that adapter in `dev.talos.runtime` forced runtime to import CLI UI:

```text
runtime-core-no-cli|src/main/java/dev/talos/runtime/CliApprovalGate.java|dev.talos.cli.ui.ApprovalPromptRenderer
runtime-core-no-cli|src/main/java/dev/talos/runtime/CliApprovalGate.java|dev.talos.cli.ui.CliTheme
```

That was an ownership error, not a runtime behavior requirement. Production
already constructs the adapter from the CLI composition root,
`TalosBootstrap`.

## Change

T360 moves the concrete adapter to:

```text
dev.talos.cli.approval.CliApprovalGate
```

Runtime keeps:

```text
dev.talos.runtime.ApprovalGate
dev.talos.runtime.ApprovalResponse
dev.talos.runtime.NoOpApprovalGate
```

`TalosBootstrap` now imports the CLI-owned adapter and wires it exactly where
it already did before. The approval prompt implementation, risk inference,
JLine/scanner behavior, session-remember response handling, and one-turn-only
approval behavior are unchanged.

The runtime contract Javadocs now describe a terminal approval adapter without
naming the CLI implementation class. That avoids reintroducing a source-level
runtime-to-CLI reference through documentation.

## Baseline Result

Architecture baseline moved:

```text
38 -> 36
```

Removed entries:

```text
runtime-core-no-cli|src/main/java/dev/talos/runtime/CliApprovalGate.java|dev.talos.cli.ui.ApprovalPromptRenderer
runtime-core-no-cli|src/main/java/dev/talos/runtime/CliApprovalGate.java|dev.talos.cli.ui.CliTheme
```

This is one ownership fix even though it removes two baseline rows: both rows
belonged to the same misplaced CLI adapter.

## Tests Updated

- `CliApprovalGateTest` moved to `dev.talos.cli.approval`.
- `ApprovalGateTest` now covers only runtime contract/default-gate behavior.
- Protected-read prompt risk labeling moved into `CliApprovalGateTest`, because
  that assertion verifies CLI adapter rendering behavior rather than the
  runtime approval interface.

## Verification

- RED architecture ratchet:
  `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  failed as expected with the two removed `CliApprovalGate` baseline rows.
- Focused GREEN test run:
  `.\gradlew.bat test --tests "dev.talos.cli.approval.CliApprovalGateTest" --tests "dev.talos.runtime.ApprovalGateTest" --tests "dev.talos.cli.ui.ApprovalPromptRendererTest" --tests "dev.talos.cli.repl.TalosBootstrapWiringTest" --no-daemon`:
  passed.
- `.\gradlew.bat validateArchitectureBoundaries --no-daemon`:
  passed.
- `git diff --check`: passed, line-ending warnings only.
- `.\gradlew.bat check --no-daemon`: passed.

## Next Correct Ticket

Do not jump directly to `DocumentExtractionService -> PrivateDocumentPolicy`
yet. That edge is still model-handoff policy and needs explicit ownership
design.

After T360, inspect the remaining `36` baseline entries. The next correct
ticket should target either another self-contained adapter ownership error or
pause for a design ticket if the remaining edges are all mixed runtime/tool,
RAG context, SPI, or private-document handoff boundaries.

Confidence: high.
