# T103 Compat Chat Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reusable local chat-completions-compatible transport that serializes Talos `ChatRequest` controls and parses text/tool-call responses.

**Architecture:** Add `dev.talos.engine.compat.CompatChatClient` as a transport helper, not a registered engine provider. It owns `/v1/chat/completions` JSON serialization, SSE parsing, provider-body prompt-debug capture, and clear malformed-response errors; T104 will wrap it in a managed llama.cpp provider.

**Tech Stack:** Java `HttpClient`, Jackson `ObjectMapper`, `com.sun.net.httpserver.HttpServer` test fixtures, JUnit 5, Gradle.

---

### Task 1: Provider Body Stage And Non-Streaming Serialization

**Files:**
- Modify: `src/main/java/dev/talos/spi/types/PromptDebugSnapshot.java`
- Create: `src/main/java/dev/talos/engine/compat/CompatChatClient.java`
- Test: `src/test/java/dev/talos/engine/compat/CompatChatClientTest.java`

- [ ] **Step 1: Write failing tests**

Create tests with a fake HTTP server that calls `CompatChatClient.chat(request)` and asserts the request path is `/v1/chat/completions`, the body includes `tools`, `tool_choice`, and `response_format`, and prompt debug captures stage `COMPAT_CHAT_HTTP_BODY`.

- [ ] **Step 2: Run red check**

```powershell
./gradlew.bat test --tests "dev.talos.engine.compat.CompatChatClientTest" --no-daemon
```

Expected: compile failure because `CompatChatClient` and the generic provider-body stage overload do not exist.

- [ ] **Step 3: Implement minimal serializer**

Add `PromptDebugSnapshot.fromProviderBody(request, stream, providerBodyJson, stage)` while preserving the existing Ollama overload.

Implement `CompatChatClient.chat` and request body building:

- preserve `system` messages as normal messages;
- use old `systemPrompt`/`userPrompt` fields only when structured messages are absent;
- map `ToolChoiceMode.REQUIRED` to `"required"`;
- map `ToolChoiceMode.NAMED` to OpenAI-style named function object;
- map `ResponseFormatMode.JSON_OBJECT` to `{"type":"json_object"}`;
- map `ResponseFormatMode.JSON_SCHEMA` to llama.cpp-compatible `{"type":"json_schema","schema":...}`;
- capture provider-body JSON under stage `COMPAT_CHAT_HTTP_BODY`.

- [ ] **Step 4: Run targeted tests**

```powershell
./gradlew.bat test --tests "dev.talos.engine.compat.CompatChatClientTest" --no-daemon
```

Expected: serialization tests pass.

### Task 2: Text And Tool-Call Parsing

**Files:**
- Modify: `src/main/java/dev/talos/engine/compat/CompatChatClient.java`
- Test: `src/test/java/dev/talos/engine/compat/CompatChatClientTest.java`

- [ ] **Step 1: Add failing parser tests**

Add tests for:

- non-streaming `choices[0].message.content`;
- streaming text SSE chunks;
- streaming tool calls in one complete delta chunk;
- malformed 200 response throws `EngineException.MalformedResponse`.

- [ ] **Step 2: Run red check**

```powershell
./gradlew.bat test --tests "dev.talos.engine.compat.CompatChatClientTest" --no-daemon
```

Expected: parser assertions fail or malformed-response subtype missing.

- [ ] **Step 3: Implement parser**

Implement:

- `parseAssistantContent`;
- SSE line parsing for `data: ...` and `data: [DONE]`;
- complete tool-call delta parsing to `TokenChunk.ofToolCalls`;
- JSON string/object argument parsing into `Map<String,Object>`;
- `EngineException.MalformedResponse`.

- [ ] **Step 4: Run targeted tests**

```powershell
./gradlew.bat test --tests "dev.talos.engine.compat.CompatChatClientTest" --tests "dev.talos.spi.EngineExceptionTest" --no-daemon
```

Expected: pass.

### Task 3: Verification And Closeout

**Files:**
- Move: `work-cycle-docs/tickets/open/[T103-open-high] compat-chat-transport-for-local-model-servers.md`
- To: `work-cycle-docs/tickets/done/[T103-done-high] compat-chat-transport-for-local-model-servers.md`

- [ ] **Step 1: Run focused verification**

```powershell
./gradlew.bat test --tests "dev.talos.engine.compat.*" --tests "dev.talos.core.llm.*PromptDebug*" --tests "dev.talos.spi.*" --no-daemon
```

Expected: pass.

- [ ] **Step 2: Run full unit tests**

```powershell
./gradlew.bat test --no-daemon
```

Expected: pass.

- [ ] **Step 3: Close ticket**

Update status to `Done`, move T103 to `done`, and commit:

```powershell
git add -f docs/superpowers/plans/2026-05-03-t103-compat-chat-transport.md
git add src/main/java/dev/talos/engine/compat src/test/java/dev/talos/engine/compat src/main/java/dev/talos/spi src/test/java/dev/talos/spi work-cycle-docs/tickets
git commit -m "feat: add compat chat transport"
```
