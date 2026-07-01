# T104 Managed llama.cpp Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a discoverable `llama_cpp` model-engine provider that can connect to an existing llama.cpp-compatible server or launch a configured local `llama-server` process.

**Architecture:** Keep Talos policy above the engine SPI. The new provider owns config parsing, process lifecycle, health/catalog probing, and delegates chat serialization/parsing to the T103 compat transport.

**Tech Stack:** Java 21, `java.net.http.HttpClient`, `ProcessBuilder`, ServiceLoader, JUnit 5 fake HTTP server/process seams.

---

### Task 1: Config And Process Seams

**Files:**
- Create: `src/main/java/dev/talos/engine/llamacpp/LlamaCppConfig.java`
- Create: `src/main/java/dev/talos/engine/llamacpp/LlamaCppProcessLauncher.java`
- Create: `src/main/java/dev/talos/engine/llamacpp/ProcessBuilderLlamaCppProcessLauncher.java`
- Test: `src/test/java/dev/talos/engine/llamacpp/LlamaCppServerManagerTest.java`

- [ ] **Step 1: Write failing tests for managed/connect-only config behavior**

Test cases:
- managed mode command includes executable, `-m`, `-c`, `--host`, `--port`, `--alias`, `--jinja`, and configured extra flags;
- connect-only mode does not launch a process.

Run:

```powershell
./gradlew.bat test --tests "dev.talos.engine.llamacpp.LlamaCppServerManagerTest" --no-daemon
```

Expected before implementation: compile fails because the llama.cpp package/classes do not exist.

- [ ] **Step 2: Implement minimal config and launcher seams**

Implementation requirements:
- `LlamaCppConfig.from(Config)` reads `engines.llama_cpp`.
- Supported keys: `mode`, `server_path`, `model_path`, `model`, `host`, `port`, `context`, `jinja`, `chat_template`, `chat_template_file`, `server_args`.
- `mode` supports `managed`, `connect_only`, and `connect-only`.
- `baseUrl()` returns `http://host:port` unless `host` is already an HTTP URL.
- `listenHost()` strips `http://` / `https://` and any port for the server command.
- `ProcessBuilderLlamaCppProcessLauncher` starts the command without shell string concatenation.

### Task 2: Server Manager And Health

**Files:**
- Create: `src/main/java/dev/talos/engine/llamacpp/LlamaCppServerManager.java`
- Test: `src/test/java/dev/talos/engine/llamacpp/LlamaCppServerManagerTest.java`

- [ ] **Step 1: Write failing health tests**

Test cases:
- missing binary returns down health naming `server_path`;
- missing model returns down health naming `model_path`;
- failed launch is recorded and visible in health;
- failed HTTP health is distinct from missing config.

- [ ] **Step 2: Implement manager**

Implementation requirements:
- `ensureStarted()` is a no-op in connect-only mode.
- managed mode validates binary and model before launch.
- managed mode launches once and reuses an alive process.
- command uses llama.cpp documented flags: `-m`, `-c`, `--host`, `--port`, optional `--jinja`, `--chat-template`, `--chat-template-file`, `--alias`, and extra `server_args`.
- `close()` destroys only Talos-owned processes.
- `health()` performs config validation and `GET /health`; failed status/connection is reported as down.

### Task 3: Engine, Catalog, Provider, Service Registration

**Files:**
- Create: `src/main/java/dev/talos/engine/llamacpp/LlamaCppEngine.java`
- Create: `src/main/java/dev/talos/engine/llamacpp/LlamaCppCatalog.java`
- Create: `src/main/java/dev/talos/engine/llamacpp/LlamaCppEngineProvider.java`
- Modify: `src/main/resources/META-INF/services/dev.talos.spi.ModelEngineProvider`
- Test: `src/test/java/dev/talos/engine/llamacpp/LlamaCppEngineProviderTest.java`

- [ ] **Step 1: Write failing provider tests**

Test cases:
- provider id is `llama_cpp`;
- provider caps report chat/stream/native tools/JSON formats/server catalog and managed-process state;
- provider is discoverable through `EngineRegistry`;
- connect-only chat routes through the compat transport using a fake `/v1/chat/completions` server;
- catalog reads `/v1/models` when available and falls back to the configured model alias/path.

- [ ] **Step 2: Implement engine/provider/catalog**

Implementation requirements:
- `LlamaCppEngine.chat/chatStream` call `serverManager.ensureStarted()` before delegating to `CompatChatClient`.
- `LlamaCppEngine.health` delegates to the manager.
- `LlamaCppEngine.caps` uses config context and conservative capability flags.
- `LlamaCppEngine.embed` throws a clear unsupported exception until T105.
- `LlamaCppCatalog.installed` parses `{"data":[{"id":"..."}]}` from `/v1/models`; fallback is the configured model alias or GGUF filename.
- Register provider in ServiceLoader after the Ollama provider.

### Task 4: Verification And Ticket Closure

**Files:**
- Modify: `work-cycle-docs/tickets/open/[T104-open-high] managed-llama-cpp-windows-backend.md`
- Move to: `work-cycle-docs/tickets/done/[T104-done-high] managed-llama-cpp-windows-backend.md`

- [ ] **Step 1: Run targeted tests**

```powershell
./gradlew.bat test --tests "dev.talos.engine.llamacpp.*" --tests "dev.talos.engine.compat.*" --tests "dev.talos.spi.*" --no-daemon
```

- [ ] **Step 2: Run full tests**

```powershell
./gradlew.bat test --no-daemon
```

- [ ] **Step 3: Commit**

```powershell
git add -f -- docs/superpowers/plans/2026-05-03-t104-managed-llama-cpp-backend.md
git add -- src/main/java/dev/talos/engine/llamacpp src/test/java/dev/talos/engine/llamacpp src/main/resources/META-INF/services/dev.talos.spi.ModelEngineProvider
git commit -m "feat: add managed llama.cpp backend"
```

### Self-Review

- Spec coverage: process lifecycle, connect-only mode, health, catalog, provider discovery, compat transport routing, and graceful shutdown are covered.
- Out of scope: setup/status UX, default backend migration, embeddings, model download, and real llama.cpp audit remain T105/T106.
- Type consistency: all new runtime classes live in `dev.talos.engine.llamacpp`; tests share the package for package-private seams.
