# T835 Chat Transport Localhost Guard

Status: open for review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Started from: `c4f9d8f2afad399620262340ec885616f154f607`
Implementation commit: pending closeout

## Decision

T835 implements the first Wave 6 HIGH trust-surface code fix: chat model
transports now default-deny non-loopback endpoints unless the relevant backend
explicitly opts into remote chat.

This closes the T833 disclosure gap that said chat transport lacked a
localhost-only guard. The revised claim is bounded: chat endpoints are
localhost-gated by default, but explicit remote opt-in can still send full
prompts off-machine.

## Production Changes

- Added `dev.talos.core.ChatHostLocalityPolicy`:
  - accepts loopback hosts: `localhost`, `127.0.0.1`, the IPv4 loopback range,
    and IPv6 loopback;
  - rejects lookalike remote hosts such as `127.0.0.1.evil.example`;
  - throws fail-closed `SecurityException` when a remote chat host is not
    explicitly allowed.
- Updated `OllamaEngineProvider`:
  - resolves the existing Ollama chat host path;
  - enforces locality before constructing `OllamaEngine` or `OllamaCatalog`;
  - permits remote chat only with `ollama.allow_remote=true`;
  - logs a security warning when remote chat is explicitly allowed.
- Updated `LlamaCppEngineProvider` / `LlamaCppConfig`:
  - parses `engines.llama_cpp.allow_remote`;
  - enforces locality before constructing `LlamaCppEngine` or `LlamaCppCatalog`;
  - permits remote chat only with `engines.llama_cpp.allow_remote=true`;
  - logs a security warning when remote chat is explicitly allowed.

## Host Resolution Note

T835 preserves existing provider-selection and host-resolution semantics.

- Ollama already consumes `TALOS_ENGINE_HOST`, `TALOS_OLLAMA_HOST`, and
  `ollama.host`; T835 guards the final resolved Ollama chat host.
- llama.cpp currently consumes `engines.llama_cpp.host`; T835 guards that final
  configured endpoint.
- T835 does not add a new `TALOS_ENGINE_HOST` override path to llama.cpp. That
  would be a host-resolution feature change, not locality enforcement.

## Documentation Changes

README, AGENTS, and tracked user/architecture docs now state the post-T835
boundary:

> Chat model endpoints are localhost-gated by default. Non-localhost configured
> chat endpoints (`ollama.host`, `engines.llama_cpp.host`,
> `TALOS_OLLAMA_HOST`, or Ollama's `TALOS_ENGINE_HOST` override) are rejected
> unless explicit `allow_remote=true` is configured for that backend; when
> remote chat is explicitly allowed, full prompts can leave this machine.

The wording remains deliberately bounded. T835 does not claim air-gapped
operation when remote chat is explicitly allowed.

## Tests Added Or Updated

- `dev.talos.core.ChatHostLocalityPolicyTest`
- `dev.talos.engine.ollama.OllamaEngineProviderTest`
- `dev.talos.engine.llamacpp.LlamaCppEngineProviderTest`
- `dev.talos.docs.TrustClaimsHonestyTest`

## Verification

Observed TDD red runs:

- Provider default-deny tests failed before production changes.
- Docs honesty test failed after updating the expected post-T835 wording and
  before updating README/AGENTS/docs.
- `ChatHostLocalityPolicyTest` caught and fixed a lookalike-host regression:
  `127.0.0.1.evil.example` is remote, not loopback.

Focused green runs:

```powershell
.\gradlew.bat test --tests "dev.talos.engine.ollama.OllamaEngineProviderTest" --tests "dev.talos.engine.llamacpp.LlamaCppEngineProviderTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.docs.TrustClaimsHonestyTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.core.ChatHostLocalityPolicyTest" --tests "dev.talos.engine.ollama.OllamaEngineProviderTest" --tests "dev.talos.engine.llamacpp.LlamaCppEngineProviderTest" --tests "dev.talos.docs.TrustClaimsHonestyTest" --no-daemon
```

Broad gates:

```powershell
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
```

Both passed in the T835 implementation workspace before commit. T835 remains
open for review; closeout should rerun the focused/broad gates and record the
implementation commit SHA.

## Non-Goals

- No candidate cut.
- No Qodana policy change.
- No `SetupCmd.java` change.
- No `site/` edits.
- No redaction, Windows path, `run_command`, or master-key custody fixes.
