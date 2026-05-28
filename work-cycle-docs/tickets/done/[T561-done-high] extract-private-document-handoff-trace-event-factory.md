# [T561] Extract private document handoff trace event factory

## Summary

T561 extracts private-document model-handoff trace event construction from
`LocalTurnTraceCapture` into a dedicated package-local owner:
`PrivateDocumentHandoffTraceEventFactory`.

`LocalTurnTraceCapture` remains the public thread-local facade. It still exposes
the same `recordPrivateDocumentModelHandoffApprovalRequired`,
`recordPrivateDocumentModelHandoffApprovalGranted`, and
`recordPrivateDocumentModelHandoffApprovalDenied` methods, but those methods now
delegate event construction.

No private-document handoff policy, approval wording, model-context behavior,
trace lifecycle, trace persistence, prompt-debug behavior, or artifact canary
behavior changed.

## Source base

Fresh beta base:

```text
origin/v0.9.0-beta-dev = 669dab86
talosVersion = 0.9.9
```

Predecessor:

```text
T560 = Local trace evidence shape decision
```

## Scope

Moved out of `LocalTurnTraceCapture`:

- `PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_REQUIRED` event construction;
- `PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_GRANTED` event construction;
- `PRIVATE_DOCUMENT_MODEL_HANDOFF_APPROVAL_DENIED` event construction;
- private-document handoff trace payload fields:
  `scope`, `perTurn`, `rememberIgnored`, `privacyClass`, `source`,
  `rawArtifactPersistenceAllowed`, `ragIndexAllowed`, `decisionReason`, and
  metadata-derived `pathHint`.

Kept in existing owners:

- `ToolResultModelContextHandoff` still owns private-document handoff approval
  decisions, approval description/detail wording, and candidate/model result
  selection.
- `ToolContentMetadata` still owns privacy/source/persistence/RAG facts.
- `LocalTurnTraceCapture` still owns trace lifecycle, thread-local capture, and
  public facade entry points.

## Behavior preserved

The extracted factory preserves:

- exact event names;
- exact `SEND_TO_MODEL_CONTEXT` scope value;
- exact per-turn flag behavior;
- exact `rememberIgnored` payload behavior;
- exact metadata payload keys and values;
- protected path-hint redaction through `TraceRedactor.pathHint(...)`;
- raw private document text exclusion from trace artifacts.

## Tests

Added `LocalTurnTracePrivateDocumentHandoffTest`:

- verifies private-document handoff trace payload shape;
- verifies raw private document text is not serialized into the trace;
- verifies `LocalTurnTraceCapture` delegates this event family to
  `PrivateDocumentHandoffTraceEventFactory`;
- verifies the factory owns the event names and private-document metadata
  payload construction.

## RED/GREEN evidence

RED:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePrivateDocumentHandoffTest" --no-daemon
```

The ownership test failed because
`PrivateDocumentHandoffTraceEventFactory.java` did not exist and
`LocalTurnTraceCapture` still owned the event strings/payload.

GREEN:

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePrivateDocumentHandoffTest" --no-daemon
```

The test passed after adding the factory and delegating through the existing
`LocalTurnTraceCapture` facade methods.

## Focused verification

```powershell
.\gradlew.bat test --tests "dev.talos.runtime.trace.LocalTurnTracePrivateDocumentHandoffTest" --tests "dev.talos.runtime.toolcall.ProtectedReadScopeIntegrationTest" --tests "dev.talos.runtime.toolcall.ToolResultModelContextHandoffTest" --tests "dev.talos.runtime.trace.LocalTurnTraceContextLedgerTest" --no-daemon
```

Passed locally.

## Standard gate

Run before integration:

```powershell
git diff --check
.\gradlew.bat validateArchitectureBoundaries --no-daemon
.\gradlew.bat check --no-daemon
```

## Next move

After T561 lands, inspect the post-T561 local trace evidence shape before
choosing T562. Do not assume permission/checkpoint trace extraction, trace
persistence, prompt-debug lifecycle, private-document handoff policy, or canary
scanning is next without source evidence.
