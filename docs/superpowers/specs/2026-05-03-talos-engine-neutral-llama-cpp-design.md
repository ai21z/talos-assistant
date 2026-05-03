# Talos Engine-Neutral llama.cpp Pivot Design

Date: 2026-05-03

Status: written for user review before implementation planning

Branch: `v0.9.0-beta-dev`

Related tickets:

- `work-cycle-docs/tickets/open/[T102-open-high] engine-neutral-provider-capability-and-request-control-spine.md`
- `work-cycle-docs/tickets/open/[T103-open-high] compat-chat-transport-for-local-model-servers.md`
- `work-cycle-docs/tickets/open/[T104-open-high] managed-llama-cpp-windows-backend.md`
- `work-cycle-docs/tickets/open/[T105-open-high] backend-neutral-product-surface-and-embeddings.md`
- `work-cycle-docs/tickets/open/[T106-open-medium] llama-cpp-focused-tool-loop-audit-and-ollama-retirement-decision.md`

## Decision

Talos should pivot away from Ollama as the default local agent engine and make
`llama.cpp` the primary Windows-first backend.

The first implementation should use managed `llama-server` plus a generic
compatibility transport, not a direct native/JNI library binding. This keeps the
Windows install story simple while giving Talos more control over process
startup, request bodies, tool-control fields, structured output, prompt debug,
and failure classification.

The internal term should be `compat chat transport` or
`chat-completions-compatible transport`. It means the local HTTP API shape used
by llama.cpp, vLLM, LocalAI, LM Studio, and similar servers. It must not imply an
OpenAI cloud dependency and should not be exposed to users as "use OpenAI".

## Why This Pivot Is Correct

The recent Qwen/GPT-OSS audit work showed that the remaining reliability
problem is not mainly bad prompt construction. Talos is correctly injecting
expected targets, exact-write frames, and repair context. The weaker boundary is
that some required actions are still expressed as prompt text while the model
chooses whether to emit native tool calls.

Ollama's native `/api/chat` API supports a `tools` list and a `format` field,
but its documented native chat shape does not expose a required tool-choice
control. Talos can contain failures with deterministic verification and
obligation gates, but the provider does not give us enough action-control
surface for a high-trust agent default.

Switching engines is still not a substitute for Talos runtime control. The
runtime must keep owning:

- current-turn task contracts;
- capability and tool-surface selection;
- mutation approval and protected reads;
- pending action obligations;
- verification;
- failure-dominant output;
- trace and prompt debug capture.

The backend should make that control easier to enforce. It should not become
the policy owner.

## Evidence

### Local Talos Architecture

Talos already has a real chat-engine SPI:

- `src/main/java/dev/talos/spi/ChatModelEngine.java`
- `src/main/java/dev/talos/spi/ModelEngine.java`
- `src/main/java/dev/talos/spi/ModelEngineProvider.java`
- `src/main/java/dev/talos/spi/EngineRegistry.java`
- `src/main/java/dev/talos/core/llm/RegistryLlmEngineResolver.java`

That means the chat backend is replaceable without rewriting the task runtime.

The coupling is outside the narrow chat interface:

- `src/main/resources/META-INF/services/dev.talos.spi.ModelEngineProvider`
  registers only `dev.talos.engine.ollama.OllamaEngineProvider`.
- `src/main/resources/config/default-config.yaml` defaults
  `llm.default_backend` to `ollama`.
- `src/main/java/dev/talos/core/llm/LlmClient.java` reads Ollama model defaults
  and `TALOS_OLLAMA_MODEL`.
- `src/main/java/dev/talos/core/embed/EmbeddingsClient.java` directly calls
  Ollama embedding endpoints.
- `src/main/java/dev/talos/core/embed/EmbeddingsFactory.java` explicitly says
  only the Ollama embedding transport is implemented.
- `src/main/java/dev/talos/app/ui/TerminalFirstRun.java`,
  `src/main/java/dev/talos/cli/launcher/SetupCmd.java`,
  `DiagnoseCmd.java`, and `TopLevelStatusCmd.java` are Ollama-specific.

So the honest assessment is: Talos has a backend foundation, but the product is
not backend-neutral yet.

### Backend Docs

llama.cpp:

- `llama-server` documents OpenAI-compatible endpoints, embeddings,
  `response_format`, JSON schema, function calling, and model provider Messages API
  compatibility:
  https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md
- llama.cpp function-calling docs document tool calling through
  `llama-server`, with important requirements around chat templates and
  `--jinja`:
  https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md
- llama.cpp releases publish Windows binaries for CPU and accelerator variants:
  https://github.com/ggml-org/llama.cpp/releases

vLLM:

- vLLM documents tool calling, named tool choice, required tool choice, and
  auto tool choice options:
  https://docs.vllm.ai/en/latest/features/tool_calling/
- vLLM installation docs state that native Windows is not supported; Windows
  use is via WSL or community-maintained forks:
  https://docs.vllm.ai/en/latest/getting_started/installation/gpu/

LocalAI:

- LocalAI describes itself as a local OpenAI-compatible API stack with multiple
  backends including llama.cpp and vLLM:
  https://localai.io/docs/overview/index.html
- LocalAI documents function/tool call extraction and setup:
  https://localai.io/features/openai-functions/

Ollama:

- Ollama `/api/chat` documents `tools` and `format`, but not a native required
  tool-choice field in the chat request:
  https://docs.ollama.com/api/chat

## Backend Choice

### Recommended: Managed llama.cpp Server

Talos should manage `llama-server` as the default local backend.

Benefits:

- good Windows fit;
- no Docker required;
- no Python server stack required;
- direct access to GGUF model files;
- supports local CPU and GPU acceleration paths;
- supports OpenAI-shaped chat APIs that other servers also implement;
- gives Talos a path to JSON schema, tool calling, embeddings, and request-body
  debug capture.

Costs:

- Talos must own model discovery, model path config, process supervision, and
  health checks;
- tool calling still needs model/template validation;
- not every GGUF model will behave well as an agent model.

### Advanced Later: vLLM

vLLM should be supported later as an advanced backend, not as the Windows-first
default.

Benefits:

- strong throughput and GPU serving;
- documented tool-choice controls;
- good fit for Linux server deployments.

Costs:

- native Windows is not supported by official docs;
- WSL/Docker/Python/CUDA stack is too heavy for the default Talos install;
- it changes the product from "easy local Windows agent" to "server ops".

### Optional Endpoint: LocalAI

LocalAI should not be the default core engine.

Benefits:

- broad OpenAI-compatible facade;
- can wrap llama.cpp and other backends;
- useful if a user already runs it.

Costs:

- adds another server layer between Talos and llama.cpp;
- often pushes users toward Docker or larger setup surface;
- reduces the direct control that motivated the pivot.

Talos can support LocalAI later through the same compat transport. It should
not be the reason we delay the llama.cpp path.

## Architecture

The architecture should split policy from transport:

```text
AssistantTurnExecutor
  -> TaskContractResolver / CurrentTurnPlan
  -> tool surface and pending obligations
  -> LlmClient
  -> EngineRegistry
  -> ModelEngineProvider
  -> compat chat transport
  -> local model server process
```

Runtime policy remains in Talos. Backend providers report capabilities and
serialize provider-specific request bodies.

## Request-Control Spine

`ChatRequest` should grow provider-neutral controls instead of adding
llama.cpp-only flags:

- `toolChoice`: `AUTO`, `NONE`, `REQUIRED`, `NAMED`
- `namedTool`: optional tool name when `toolChoice == NAMED`
- `responseFormat`: `TEXT`, `JSON_OBJECT`, `JSON_SCHEMA`
- `jsonSchema`: optional schema for structured response fallback
- `stream`: if the transport needs explicit stream control
- `debugTags`: optional turn/obligation identifiers for prompt debug

`Capabilities` should grow beyond `nativeTools`:

- supports chat;
- supports streaming;
- supports embeddings;
- supports native tool calls;
- supports required tool choice;
- supports named tool choice;
- supports JSON object output;
- supports JSON schema output;
- supports server-managed model catalog;
- supports Talos-managed process lifecycle.

This lets Talos choose enforcement strategies from facts instead of backend
names.

## Compatibility Transport

The compat transport should implement the common local chat server surface:

- `POST /v1/chat/completions`
- streamed and non-streamed responses;
- `tools`;
- `tool_choice`;
- `response_format`;
- `/v1/models` if available;
- `/v1/embeddings` when needed.

Provider differences should be explicit:

- llama.cpp may require specific server flags and chat templates for tools;
- vLLM has parser and model-specific tool-call settings;
- LocalAI may need model config for function extraction;
- not all servers support the same `response_format` schema depth.

The transport must capture the full provider-body JSON when prompt debug is
enabled. That is required for future audits because prompt construction alone
does not prove provider-control fields were sent.

## Managed llama.cpp Backend

The llama.cpp provider should be responsible for:

- resolving the configured `llama-server.exe` path;
- selecting a local GGUF model path;
- launching the server on a local port when Talos owns the process;
- detecting an already-running compatible server when configured to connect
  only;
- health checks;
- model/catalog reporting;
- context window reporting where available;
- graceful shutdown for Talos-owned processes;
- clear failure messages when the binary or model is missing.

The first implementation should avoid direct native library integration.
Starting with the server process gives us observability and an easier migration
path. A later native Talos engine can replace the process boundary after the
runtime contract is stable.

## Product Decoupling

The pivot is incomplete if chat requests work but Talos still says "install
Ollama" everywhere.

The following surfaces must become backend-neutral:

- default config;
- first-run setup;
- `setup`;
- `diagnose`;
- status output;
- env vars;
- documentation;
- embedding transport;
- prompt debug output labels;
- model switch UX.

Suggested config direction:

```yaml
llm:
  transport: "engine"
  default_backend: "llama_cpp"
  model: "local/agent.gguf"

engines:
  llama_cpp:
    mode: "managed"
    server_path: ""
    model_path: ""
    host: "http://127.0.0.1:8080"
    context: 8192
    chat_template: ""

embed:
  provider: "compat"
  model: "local/embed.gguf"
```

Legacy `ollama.*` config can remain temporarily as a compatibility path, but
new code should not add new dependencies on it.

## Future Talos-Native Engine Vision

The end state is not "Talos is a llama.cpp wrapper." The end state is:

- Talos has a native engine layer that owns local model lifecycle, request
  control, structured action contracts, diagnostics, and audit traces.
- llama.cpp is the first inference backend under that layer because it is the
  best Windows-first foundation today.
- vLLM, LocalAI, remote enterprise endpoints, and future backends can plug into
  the same capability/request-control interface.
- Runtime correctness is enforced by Talos state machines, not by prompt wording
  or provider hope.

Native Talos engine does not mean writing inference kernels now. It means Talos
owns the agent runtime contract:

- deterministic task state;
- deterministic action obligations;
- provider capability negotiation;
- controlled tool choice or schema fallback;
- model/server process management;
- unified model catalog;
- uniform prompt and provider-body debug;
- backend-neutral verification and failure rendering.

A later phase can evaluate deeper native integration:

- direct llama.cpp process control through a tighter local wrapper;
- local model download and checksum management;
- model profiles known to satisfy Talos agent requirements;
- optional native library/JNA/JNI only after the server-process path proves the
  contract.

## Migration Sequence

1. Add engine-neutral request-control and capability types.
2. Add a generic compat chat transport with body capture and tool-call parsing.
3. Add managed llama.cpp provider using the compat transport.
4. Decouple setup/status/diagnose/embeddings from Ollama.
5. Run a focused llama.cpp audit before any large T61-style audit.
6. Decide whether Ollama remains legacy optional, moves behind a compatibility
   flag, or is removed from the default distribution path.

## Testing Strategy

Deterministic tests first:

- provider capability negotiation tests;
- `ChatRequest` serialization tests for `tools`, `tool_choice`, and
  `response_format`;
- streaming parser tests for text, tool calls, and malformed chunks;
- prompt debug tests proving provider-body JSON capture;
- process manager tests using a fake server process;
- config migration tests proving no new default depends on `ollama.*`;
- setup/status/diagnose tests with fake providers.

Manual validation after deterministic tests:

- launch managed llama.cpp on Windows;
- run a simple no-tool chat probe;
- run native tool-call probes;
- run required-tool or schema fallback probes;
- run exact-file and expected-target prompt-construction probes;
- run the focused clean Talos audit against the selected llama.cpp model.

## Non-Goals

- No full T61-style audit before the focused llama.cpp backend audit.
- No direct JNI/native-library binding in the first pivot.
- No vLLM default backend in the Windows-first product path.
- No LocalAI default backend.
- No new prompt-wording campaign as the main fix.
- No removal of runtime obligation gates, verification, or failure-dominant
  output.
- No cloud-model dependency.

## Open Decisions For Implementation Planning

- Which llama.cpp Windows binary flavor should be the default recommendation:
  CPU, Vulkan, or CUDA?
- Which GGUF model becomes the first supported Talos audit model?
- Should Talos download/manage model files in V1, or only point users at a
  configured path?
- Should Ollama remain as a legacy backend for one beta cycle after llama.cpp
  becomes default?
