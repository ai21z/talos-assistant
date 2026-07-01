# T105 Backend-Neutral Product Surface And Embeddings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Talos product surfaces use the active engine provider instead of hard-coded Ollama assumptions, and add a non-Ollama embedding path.

**Architecture:** Add a small runtime-config resolver that centralizes active backend/model/host and embedding provider selection. CLI/status/diagnose/banner read that resolver; embeddings factory selects Ollama, compat, or disabled transports explicitly.

**Tech Stack:** Java 21, ServiceLoader `EngineRegistry`, `java.net.http.HttpClient`, JUnit 5 fake HTTP server tests.

---

### Task 1: Runtime Engine Config Resolver

**Files:**
- Create: `src/main/java/dev/talos/core/EngineRuntimeConfig.java`
- Modify: `src/main/java/dev/talos/core/llm/LlmClient.java`
- Modify: `src/main/java/dev/talos/spi/EngineRegistry.java`
- Test: `src/test/java/dev/talos/core/EngineRuntimeConfigTest.java`

- [ ] **Step 1: Write failing tests**

Cases:
- default config resolves backend `llama_cpp` and model `talos-agent`;
- legacy Ollama config still resolves `ollama/qwen2.5-coder:14b` when explicitly selected;
- `llm.model` wins over backend-specific model defaults;
- backend-neutral env aliases are represented by the resolver API while legacy Ollama aliases remain readable in Ollama code.

- [ ] **Step 2: Implement resolver and route LLM/registry through it**

Rules:
- canonical config: `llm.default_backend`, `llm.model`;
- llama.cpp fallback: `engines.llama_cpp.model`, then GGUF filename from `model_path`;
- Ollama fallback: `ollama.model`;
- display model should be backend-qualified (`backend/model`);
- host label should be backend-specific but not say Ollama unless backend is `ollama`.

### Task 2: Backend-Neutral CLI Surfaces

**Files:**
- Modify: `src/main/java/dev/talos/cli/ui/CliStatusDashboard.java`
- Modify: `src/main/java/dev/talos/cli/ui/TalosBanner.java`
- Modify: `src/main/java/dev/talos/cli/launcher/TopLevelStatusCmd.java`
- Modify: `src/main/java/dev/talos/cli/launcher/DiagnoseCmd.java`
- Modify: `src/main/java/dev/talos/cli/launcher/SetupCmd.java`
- Modify: `src/main/java/dev/talos/app/ui/TerminalFirstRun.java`
- Test: `src/test/java/dev/talos/cli/ui/CliStatusDashboardTest.java`
- Test: `src/test/java/dev/talos/app/ui/TerminalFirstRunTest.java`

- [ ] **Step 1: Write failing output tests**

Cases:
- default dashboard policy does not contain `Ollama`;
- default model label contains `llama_cpp/talos-agent`;
- legacy Ollama-selected config still reports local Ollama policy;
- first-run/setup text says local model engine or llama.cpp, not "Talos requires Ollama".

- [ ] **Step 2: Implement product-surface updates**

Rules:
- `talos status --verbose` prints active backend, model, host, health, capabilities, and embedding provider.
- `talos diagnose` prints `Engine:` with backend/model/host/health/capability summary.
- setup remains non-downloading by default and describes configured local engine setup; Ollama install/pull remains available only through explicit legacy options.

### Task 3: Compat And Disabled Embedding Providers

**Files:**
- Create: `src/main/java/dev/talos/core/embed/CompatEmbeddingsClient.java`
- Create: `src/main/java/dev/talos/core/embed/DisabledEmbeddings.java`
- Modify: `src/main/java/dev/talos/core/embed/EmbeddingsFactory.java`
- Modify: `src/main/java/dev/talos/core/embed/EmbeddingProfile.java`
- Modify: `src/main/java/dev/talos/cli/repl/slash/BenchCommand.java`
- Test: `src/test/java/dev/talos/core/embed/CompatEmbeddingsClientTest.java`
- Test: `src/test/java/dev/talos/core/embed/EmbeddingsFactoryTest.java`

- [ ] **Step 1: Write failing embedding tests**

Cases:
- `embed.provider=compat` returns a compat client and does not construct Ollama;
- compat client posts to `/v1/embeddings` with `model` and `input`;
- compat batch parsing supports OpenAI-compatible `data[].embedding`;
- `embed.provider=disabled` throws a clear disabled message on use;
- unknown providers fail with a provider-specific message that does not say only Ollama is implemented.

- [ ] **Step 2: Implement transport selection**

Rules:
- provider aliases `compat`, `openai_compat`, and `llama_cpp` use `CompatEmbeddingsClient`;
- provider `ollama` uses existing `EmbeddingsClient`;
- provider `disabled` uses `DisabledEmbeddings`;
- cache namespaces continue to include provider/model/dimensions.

### Task 4: Default Config And Closure

**Files:**
- Modify: `src/main/resources/config/default-config.yaml`
- Modify: `work-cycle-docs/tickets/open/[T105-open-high] backend-neutral-product-surface-and-embeddings.md`
- Move to: `work-cycle-docs/tickets/done/[T105-done-high] backend-neutral-product-surface-and-embeddings.md`

- [ ] **Step 1: Update defaults**

Defaults:
- `llm.default_backend: "llama_cpp"`
- `llm.model: "talos-agent"`
- `embed.provider: "compat"`
- `embed.model: "talos-embed"`
- keep legacy `ollama.*` block for explicit Ollama users.

- [ ] **Step 2: Verify**

```powershell
./gradlew.bat test --tests "dev.talos.cli.launcher.*" --tests "dev.talos.cli.ui.*" --tests "dev.talos.app.ui.*" --tests "dev.talos.core.embed.*" --tests "dev.talos.core.EngineRuntimeConfigTest" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```

- [ ] **Step 3: Commit**

```powershell
git add -f -- docs/superpowers/plans/2026-05-03-t105-backend-neutral-product-surface-and-embeddings.md
git add -- src/main/java src/test/java src/main/resources/config/default-config.yaml work-cycle-docs/tickets
git commit -m "feat: decouple product surfaces from Ollama"
```

### Self-Review

- Covered: default backend, active model resolution, status/diagnose/banner/setup wording, compat embeddings, disabled embeddings, legacy Ollama compatibility.
- Deferred: automatic llama.cpp/model download, full audit, removing Ollama provider.
