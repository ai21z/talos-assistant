# Tool-Calling Protocol Migration: XML Retirement Review

**Branch:** `v0.9.0-beta-dev`  
**Date:** 2026-04-13  
**Reviewer:** Architecture review session  
**Scope:** Tool-calling format layer — current state, burden, feasibility, target, plan

---

## 1. Current-State Verification

All claims below are verified against the actual code in `v0.9.0-beta-dev`.

### 1.1 Where XML Is Still Active

| Location | File | What it does |
|----------|------|-------------|
| **System prompt instruction** | `tools-preamble.txt` (49 lines) | Lines 4–6, 42: "You MUST use `<tool_call>` and `</tool_call>` tags. Do not use \`\`\`json blocks or bare JSON." |
| **Inline fallback prompt** | `SystemPromptBuilder.java` lines 251–285 (`DEFAULT_TOOLS_PREAMBLE`) | Same XML instructions, used when resource files are absent |
| **Native→XML bridge** | `OllamaEngine.java` lines 290–336 (`convertNativeToolCallsToXml`) | Converts Ollama's structured `tool_calls` JSON back into `<tool_call>\n{JSON}\n</tool_call>` text |
| **Streaming bridge** | `OllamaEngine.java` lines 448–464 (`chatStreamViaMessages` lambda) | Detects `"tool_calls"` in stream chunk, calls `convertNativeToolCallsToXml()`, emits as text `TokenChunk` |
| **Non-streaming bridge** | `OllamaEngine.java` lines 247–269 (`extractChatContentOrToolCalls`) | Same conversion for non-streaming `/api/chat` response |
| **Parser pass 1 (priority)** | `ToolCallParser.java` lines 33–36 (`VARIANT_TAG_PATTERN`) | `<(tool_call\|function_call\|tool\|function)>…</\1>` — first extraction pass |
| **Parser strip** | `ToolCallParser.java` lines 51–54 (`STRIP_PATTERN`) | Removes XML-tagged blocks for final prose |
| **Stream filter** | `ToolCallStreamFilter.java` (185 lines, entire file) | Suppresses `<tool_call>`, `<function_call>`, `<tool>`, `<function>` tags from terminal display |
| **Sanitize workaround** | `Sanitize.java` lines 24–26 (`TOOL_CALL_BLOCK` pattern) | Protects `<tool_call>` blocks from SUS_HTML stripping |
| **Sanitize workaround** | `Sanitize.java` lines 84–88 (`sanitizeForOutputPreservingToolCalls`) | Applies SUS_HTML only outside tool_call blocks |
| **Sanitize workaround** | `Sanitize.java` lines 136–158 (`stripSuspiciousHtmlOutsideToolCalls`) | Walk-and-protect algorithm for interleaved prose+blocks |
| **Belt-and-suspenders** | `ToolCallLoop.java` lines 250–251 | `Sanitize.stripSuspiciousHtml(ToolCallParser.stripToolCalls(currentAnswer))` |
| **Tool-call detection** | `AssistantTurnExecutor.java` line 43 | `ToolCallParser.containsToolCalls(answer)` — XML pattern check |
| **Tool-call detection** | `ToolCallLoop.java` line 135, 156 | `ToolCallParser.containsToolCalls(initialAnswer)` / `ToolCallParser.containsToolCalls(currentAnswer)` |
| **Test fixtures** | `OllamaToolCallBridgeTest.java` (382 lines) | 10 tests for `convertNativeToolCallsToXml`, all assert `<tool_call>` in output |

### 1.2 Where JSON Is Already Accepted

| Location | File | What it does |
|----------|------|-------------|
| **Parser pass 2** | `ToolCallParser.java` lines 39–42 (`CODE_FENCE_PATTERN`) | Accepts ` ```json\n{…"name"…}\n``` ` code-fenced blocks |
| **Parser pass 3** | `ToolCallParser.java` lines 45–48 (`BARE_JSON_PATTERN`) | Accepts bare `{"name":"talos.…"}` at line boundaries (only if no XML/fenced found) |
| **Parser internals** | `ToolCallParser.java` lines 137–193 (`parseJson`, `unwrapIfNeeded`, `extractName`, `extractParams`) | Accepts key aliases: `name`/`function`/`tool_name`/`tool`, `parameters`/`arguments`/`args`/`params` |
| **Ollama native → JSON** | `OllamaEngine.java` lines 484–513 (`convertToolSpecs`) | Sends `ToolSpec` as native JSON tool definitions to Ollama |
| **Tool call JSON inside XML** | The JSON payload *inside* `<tool_call>…</tool_call>` is already JSON | The XML tags are just wrappers; the actual data format has always been JSON |

### 1.3 Where Native Tool Calling Is Already Active

| Location | File | What it does |
|----------|------|-------------|
| **Config default** | `default-config.yaml` line 110 | `tools.native_calling: true` |
| **Config read** | `OllamaEngineProvider.java` line 40–43 | `nativeToolCallingFrom(cfg)` reads `tools.native_calling`, defaults `true` |
| **Engine construction** | `OllamaEngineProvider.java` line 49–50 | `new OllamaEngine(host, model, nativeTools)` |
| **Request building** | `OllamaEngine.java` lines 211–216, 420–425 | When `nativeToolCalling=true`, sends `"tools"` field in `/api/chat` request body |
| **Response parsing** | `OllamaEngine.java` lines 253–258 | Detects `tool_calls` array in non-streaming response |
| **Stream parsing** | `OllamaEngine.java` lines 450–464 | Detects `"tool_calls"` in streaming chunk |
| **Message serialization** | `OllamaEngine.java` lines 527–551 (`serializeChatMessage`) | Serializes `ChatMessage.NativeToolCall` as Ollama-format `tool_calls` array |
| **SPI types** | `ChatMessage.java` lines 18–72 | `NativeToolCall` record, `assistantWithToolCalls()`, `toolResult()`, `hasNativeToolCalls()` |
| **SPI request** | `ChatRequest.java` line 27 | `List<ToolSpec> tools` field |
| **SPI type** | `ToolSpec.java` (23 lines) | `name`, `description`, `parametersSchemaJson` |
| **LlmClient wiring** | `LlmClient.java` lines 41, 126–128 | `toolSpecs` field, `setToolSpecs()` populates it |
| **LlmClient request** | `LlmClient.java` line 302, 368 | Passes `toolSpecs` to `ChatRequest` constructor |

### 1.4 Current Real Data Flow (verified end-to-end)

```
[1] SystemPromptBuilder.build()
    │  loads tools-preamble.txt → instructs XML <tool_call> format
    │  appends tool descriptors from ToolRegistry
    │  CONFLICT: also generates ToolSpec list for native API

[2] LlmClient.engineAssembledWithMessages()
    │  sanitizes messages via Sanitize.sanitizeMessageContent() [ctrl-chars only]
    │  creates ChatRequest with messages + toolSpecs

[3] OllamaEngine.chatStreamViaMessages()
    │  separates system prompt from conversation turns
    │  serializes messages via serializeChatMessage()
    │    → handles NativeToolCall in assistant messages
    │    → DOES NOT serialize toolCallId for role="tool" (code missing, only comment)
    │  IF nativeToolCalling=true: converts ToolSpec→Ollama format, adds "tools" to body
    │  SENDS to Ollama: {model, system[XML instructions!], messages, stream:true, tools[native]}
    │  CONFLICT: model receives native "tools" field AND XML instructions in system prompt

[4] Ollama model generates response
    │  Modern models (Gemma4, Llama3.x, Qwen2.5): prefer native tool_calls JSON
    │  Older/smaller models: may follow system prompt and emit XML text

[5] OllamaEngine stream handler (lines 448-470)
    │  IF chunk contains "tool_calls": 
    │    → convertNativeToolCallsToXml(textContent, toolCallsNode)
    │    → emits as text TokenChunk containing "<tool_call>\n{JSON}\n</tool_call>"
    │    CRITICAL: native structured data is DESTROYED here, converted to text
    │  ELSE: normal text token extraction

[6] LlmClient.assembleFromStream() (lines 396-423)
    │  accumulates TokenChunks into StringBuilder
    │  applies Sanitize.stripThinkTags()
    │  applies Sanitize.sanitizeForOutputPreservingToolCalls()
    │    → SUS_HTML applied only outside <tool_call> blocks
    │    → this workaround EXISTS because tool calls are text, not structured
    │  applies Sanitize.hardTruncate()
    │  emits delta to onChunk (→ ToolCallStreamFilter)

[7] ToolCallStreamFilter.accept() (called via onChunk)
    │  XML state machine: scans for <tool_call>, <function_call>, <tool>, <function>
    │  suppresses tool-call blocks from terminal display
    │  passes prose to display delegate
    │  EXISTS purely because tool calls travel as text mixed with prose

[8] AssistantTurnExecutor.execute() (lines 85-173)
    │  after stream completes, checks hasAnyToolCalls(answer):
    │    → ToolCallParser.containsToolCalls() [XML/JSON text matching]
    │    → CodeBlockToolExtractor.containsExtractableBlocks() [disabled but still checked]
    │  IF tool calls found: enters ToolCallLoop.run()

[9] ToolCallLoop.run() (lines 130-256)
    │  WHILE answer contains tool calls:
    │    ToolCallParser.parse(currentAnswer)
    │      → Pass 1: VARIANT_TAG_PATTERN (XML tags) → extract JSON payload
    │      → Pass 2: CODE_FENCE_PATTERN (```json blocks)
    │      → Pass 3: BARE_JSON_PATTERN (bare JSON with talos. prefix)
    │      → All paths → parseJson() → ToolCall(name, Map<String,String> params)
    │    messages.add(ChatMessage.assistant(currentAnswer))
    │      → CRITICAL: appends raw text (with XML tags) as assistant message
    │      → does NOT use ChatMessage.assistantWithToolCalls()
    │    FOR each ToolCall:
    │      repairMissingPath(call)  [no inference, just validation]
    │      TurnProcessor.executeTool(session, call, ctx)  [sandbox + approval]
    │      messages.add(ChatMessage.user(resultText))
    │        → CRITICAL: sends result as role="user", not role="tool"
    │        → does NOT use ChatMessage.toolResult()
    │    re-prompt: ctx.llm().chat(messages)
    │      → messages contain XML-polluted assistant + user-role results
    │  
    │  final: ToolCallParser.stripToolCalls() + Sanitize.stripSuspiciousHtml()

[10] ToolCall record (final internal representation)
     │  record ToolCall(String toolName, Map<String,String> parameters)
     │  FORMAT-AGNOSTIC. All tool execution operates on this.
     │  TurnProcessor, ToolRegistry, TalosTool, Sandbox, ApprovalGate: all ToolCall-based.
```

### 1.5 True Canonical Internal Representation

**`ToolCall`** (`dev.talos.tools.ToolCall`): `record ToolCall(String toolName, Map<String, String> parameters)`

This is genuinely format-agnostic. Every tool implementation, the approval gate, the sandbox, and the progress sink work exclusively with `ToolCall`. The format layer (XML/JSON/native) only affects how `ToolCall` is *constructed*, not how it's *consumed*.

### 1.6 Message Types / Bridge Layers That Exist But Are Partially Unused

| Type / Method | Status | What's missing |
|---------------|--------|---------------|
| `ChatMessage.NativeToolCall(id, name, arguments)` | **DEFINED, TESTED, UNUSED IN LOOP** | `ToolCallLoop` never creates these; uses `ChatMessage.assistant(rawText)` instead |
| `ChatMessage.assistantWithToolCalls(content, toolCalls)` | **DEFINED, TESTED, UNUSED IN LOOP** | `ToolCallLoop` line 169: `messages.add(ChatMessage.assistant(currentAnswer))` — raw XML text |
| `ChatMessage.toolResult(toolCallId, resultContent)` | **DEFINED, TESTED, UNUSED IN LOOP** | `ToolCallLoop` line 191: `messages.add(ChatMessage.user(resultText))` — role="user" not role="tool" |
| `ChatMessage.toolCallId()` field | **DEFINED, TESTED, NOT SERIALIZED** | `OllamaEngine.serializeChatMessage()` line 547-548: comment says "Include tool_call_id" but **no code follows** |
| `OllamaEngine.serializeChatMessage()` tool_calls support | **IMPLEMENTED, BUT NEVER TRIGGERED** | Because `ToolCallLoop` never creates `assistantWithToolCalls` messages |
| `Capabilities.nativeTools` field | **DOES NOT EXIST** | `Capabilities` only has `chat`, `stream`, `embed`, `contextWindow`. No way to query if engine supports native tools at the SPI level. |

---

## 2. Challenge the Assumptions

### Statement 1: "Talos currently has native-capable transport in OllamaEngine"

**CONFIRMED — but with important nuance.**

`OllamaEngine` sends native `tools` field and detects native `tool_calls` in responses. However, it immediately destroys the structured data by converting to XML text via `convertNativeToolCallsToXml()`. The transport is native-capable at the wire level but not at the pipeline level. The native data never reaches `ToolCallLoop` in structured form.

**Evidence:** `OllamaEngine.java` line 457: `String xmlToolCalls = convertNativeToolCallsToXml(textContent, toolCallsNode);` followed by `return TokenChunk.of(xmlToolCalls);` — the structured `JsonNode toolCallsNode` is discarded.

### Statement 2: "XML-centered prompting and orchestration"

**CONFIRMED.**

`tools-preamble.txt` line 42: `"You MUST use <tool_call> and </tool_call> tags."` This is sent as the system prompt even when `nativeToolCalling=true`, creating a contradiction. Additionally, `SystemPromptBuilder.DEFAULT_TOOLS_PREAMBLE` (line 279): same instruction.

The orchestration (detection, parsing, stripping, filtering) is all XML-first. `ToolCallParser` checks XML tags in Pass 1 before JSON.

### Statement 3: "JSON-capable parsing in ToolCallParser"

**CONFIRMED.**

`ToolCallParser` handles code-fenced JSON (Pass 2, `CODE_FENCE_PATTERN`) and bare JSON with `talos.` prefix (Pass 3, `BARE_JSON_PATTERN`). However, bare JSON is only checked if no XML/fenced blocks were found (`if (calls.isEmpty())` at line 78). So JSON is a fallback, not an equal path.

### Statement 4: "Partially wired native message replay via ChatMessage.NativeToolCall"

**CONFIRMED — more partial than implied.**

The types exist and are tested (`OllamaEngineNativeToolsTest`). `serializeChatMessage()` handles `hasNativeToolCalls()`. But:
- `ToolCallLoop` never creates `assistantWithToolCalls` messages (line 169: uses raw text)
- `ToolCallLoop` never creates `toolResult` messages (line 191: uses `ChatMessage.user()`)
- `serializeChatMessage()` does NOT serialize `toolCallId` despite commenting it should (line 547-549: comment, no code)
- The native replay path is effectively dead code in production

### Statement 5: "No structured streamed tool-call primitive yet (TokenChunk only carries text/done)"

**CONFIRMED.**

`TokenChunk.java` (8 lines): `record TokenChunk(String text, Boolean done)`. No field for tool calls, no variant type, no metadata. This forces `OllamaEngine` to serialize native tool calls into text at the stream level.

`ModelEngine.chatStream()` returns `Stream<TokenChunk>` — the SPI contract has no mechanism to return structured tool calls from the stream.

### Statement 6: "XML-specific stream filtering and XML-aware sanitization"

**CONFIRMED.**

- `ToolCallStreamFilter` (185 lines): entirely XML-tag-based. `OPEN_TAG` pattern: `<(tool_call|function_call|tool|function)>`. `CLOSE_TAG` pattern: `</(tool_call|function_call|tool|function)>`. `couldBeOpenTagPrefix()` checks partial matches at chunk boundaries.
- `Sanitize.sanitizeForOutputPreservingToolCalls()`: exists solely because XML tool-call blocks contain JSON with HTML values that SUS_HTML would corrupt. The `TOOL_CALL_BLOCK` pattern and `stripSuspiciousHtmlOutsideToolCalls()` algorithm are XML-awareness code.

### Statement 7: "Prompt still teaches XML <tool_call> blocks"

**CONFIRMED.** See 1.1 above.

### Statement 8: "Ollama native tool_calls are converted back to XML text"

**CONFIRMED.** `convertNativeToolCallsToXml()` at lines 290-336. Called from both streaming (line 457) and non-streaming (line 257) paths.

### Statement 9: "Parser still prioritizes XML"

**CONFIRMED.** `ToolCallParser.parse()` line 71: Pass 1 is `VARIANT_TAG_PATTERN` (XML). Pass 2 is `CODE_FENCE_PATTERN`. Pass 3 is `BARE_JSON_PATTERN` (only if `calls.isEmpty()`).

### Statement 10: "Stream filtering only understands XML-like tags"

**CONFIRMED.** `ToolCallStreamFilter` has no JSON detection. If a model emitted tool calls as bare JSON (no XML wrapper), the filter would display them to the terminal.

### Statement 11: "Sanitization had to become tool-call-aware"

**CONFIRMED.** Direct consequence of the SUS_HTML bug. `sanitizeForOutputPreservingToolCalls()` and `stripSuspiciousHtmlOutsideToolCalls()` were added to fix the 6-iteration corruption loop where `<script>` inside JSON tool params was stripped.

### Statement 12: "Native message replay is incomplete because tool_call_id serialization may be missing"

**CONFIRMED — and worse than "may be missing".**

`OllamaEngine.serializeChatMessage()` lines 547-549:
```java
// Include tool_call_id for tool-result messages
// (Ollama doesn't actually require this yet, but it's correct protocol)
```
**No code follows.** The `toolCallId` is never added to the serialized message map. This is dead code by omission — the comment promises functionality that doesn't exist.

Additionally, `ToolCallLoop` never creates `toolResult()` messages (uses `ChatMessage.user()` instead), so even if serialization worked, it would never be triggered.

### Statement 13: "A full XML retirement likely requires a small SPI/streaming change"

**CONFIRMED.**

`TokenChunk` must be extended or wrapped. Currently `record TokenChunk(String text, Boolean done)` with no mechanism to carry structured tool calls. `ModelEngine.chatStream()` returns `Stream<TokenChunk>`. Either:
- `TokenChunk` gains a `List<ChatMessage.NativeToolCall> toolCalls()` field, or
- A new envelope type is introduced, or  
- The streaming assembly method gets a side-channel

Without this, `OllamaEngine` has no way to pass structured tool calls through the stream pipeline without serializing to text.

### Additional Finding: Missing but Important

**MISSING IMPORTANT DETAIL: `Capabilities` has no `nativeTools` flag.**

`Capabilities.java`: `record Capabilities(boolean chat, boolean stream, boolean embed, int contextWindow)`. There is no way for `SystemPromptBuilder` or `ToolCallLoop` to ask "does the current engine support native tools?" at runtime. The `nativeToolCalling` boolean lives only inside `OllamaEngine`. This means:
- `SystemPromptBuilder` cannot conditionally omit XML instructions
- `ToolCallLoop` cannot conditionally prefer a native path
- Adding a new engine that doesn't support native tools would silently break

This is a missing SPI signal that the migration plan must address.

---

### 2.1 Retirement Metric and Observation Plan

`CCR-012.2` must be gated by explicit evidence that the XML compatibility path
is no longer needed.

Primary metric:

- `xml_parser_fallback_activations`: count of `ToolCallParser.parse(...)`
  invocations where the deprecated XML path produced one or more executable
  `ToolCall` objects after JSON formats were checked first.

Supporting metrics:

- `xml_parser_fallback_calls`: total number of tool calls produced by those XML
  fallback activations.
- `xml_stream_suppressed_blocks`: count of complete XML tool-call blocks
  suppressed by `ToolCallStreamFilter` from user-visible stream output.

Interpretation:

- Non-zero `xml_parser_fallback_activations` means XML is still a live
  executable compatibility path and must not be removed.
- Non-zero `xml_stream_suppressed_blocks` with zero parser activations is
  weaker evidence. It means XML-looking text reached the stream filter, but it
  does not by itself prove that XML fallback still executes tools.

Collection mechanism:

- Surface these counters in `/status --verbose` as process-local runtime
  telemetry, along with last-seen timestamps and the last XML-derived tool
  names.
- Keep the instrumentation lightweight and local; this is a manual review gate
  for beta/playground work, not analytics infrastructure.

Observation window required before `CCR-012.2`:

1. At least 14 consecutive calendar days of routine manual usage and targeted
   playground validation on active flows, including `playground/horror-synth-site`.
2. At least one full beta-cycle branch where `/status --verbose` is checked
   after representative tool-calling sessions and the XML parser metric remains
   zero.

Retirement threshold:

- `xml_parser_fallback_activations == 0` for the full observation window.
- No targeted playground validation session requires XML fallback to complete
  tool work.
- Any non-zero stream-filter XML observations are investigated and shown not to
  correspond to executable XML fallback behavior.

If the parser activation metric is non-zero even once during the observation
window, the XML compatibility path remains in place until a subsequent window
returns to zero.

---

## 3. XML Retirement Feasibility Analysis

### Can XML be fully retired from Talos?

**Yes, but not by simple deletion.** It requires completing the native pipeline first. XML currently provides: (1) a prompt instruction contract, (2) a text serialization format, (3) a display-suppression mechanism, (4) the only working detection/parsing path for the tool loop. Removing XML without replacing these functions would break tool calling entirely.

### Is that feasible now?

**Yes.** The native infrastructure is 70–80% built:
- `ChatMessage.NativeToolCall` exists
- `OllamaEngine` already sends/receives native
- `ToolCall` is already format-agnostic
- Tool execution is already format-agnostic
- The gap is in the middle: stream transport, loop handling, message replay, prompt instructions

### What exactly prevents a simple XML → JSON replacement?

Five concrete blockers:

**Blocker 1: `TokenChunk` cannot carry structured tool calls.**  
`Stream<TokenChunk>` is the SPI contract. Without an extension, there is no way to pass native tool calls from the engine through the stream without text serialization.

**Blocker 2: `ToolCallLoop` is text-only.**  
It receives a `String initialAnswer`, checks for tool calls via regex, and re-prompts with `ChatMessage.assistant(rawText)` / `ChatMessage.user(result)`. It has no path for receiving `List<NativeToolCall>` from the stream assembly.

**Blocker 3: `OllamaEngine.serializeChatMessage()` does not serialize `toolCallId`.**  
If we switch to `ChatMessage.toolResult()` for re-prompting, the correlation ID won't actually be sent to Ollama. (Ollama doesn't require it today, but it's wrong to introduce a native path that's knowingly incomplete.)

**Blocker 4: `SystemPromptBuilder` has no signal to switch prompt strategy.**  
It always emits XML instructions. It needs to know whether the engine supports native tools to conditionally omit them.

**Blocker 5: `ToolCallStreamFilter` would need updating for JSON fallback.**  
If XML is retired from prompts, models using text fallback would emit JSON blocks (code-fenced or bare), not XML. The stream filter doesn't handle those.

### Is native-first + JSON fallback the correct target?

**Yes, with a qualification.** Native-first is correct for all Ollama models that support the `tools` field (which is most models released after mid-2024). JSON text fallback is correct for:
- Models served via `/api/generate` (legacy single-turn path)
- Models that ignore the native `tools` field
- Future non-Ollama backends that don't support native tool calling

**The qualification:** XML should not be aggressively deleted from the parser. `ToolCallParser` should keep its XML recognition as a read-only fallback — it's 20 lines of regex and costs nothing at runtime. What should be retired is: XML in *prompts*, XML as the *bridge format*, XML-specific *stream filtering*, and XML-aware *sanitization workarounds*.

### Is JSON text fallback enough, or must the native streaming/message path be completed first?

**The native streaming/message path must be completed first.**

JSON text fallback handles the degenerate case (model doesn't support native). But the *primary* path — which is 90%+ of real usage with modern Ollama models — currently goes through the native→XML→parse roundtrip. If we only add JSON fallback without completing the native path, we've added a new format without removing the old burden. The payoff comes from the native path being first-class.

### Risk Assessment

| Risk | Rating | Detail |
|------|--------|--------|
| **Implementation risk** | **LOW-MEDIUM** | Infrastructure is mostly built. Main work is wiring, not invention. The SPI change (`TokenChunk` extension) is the only tricky part. |
| **Regression risk** | **MEDIUM** | Tool calling is the most safety-critical path. Every change must be tested against: correct parsing, approval gate, sandbox, no-op rejection, progress UX, compaction. |
| **Test burden** | **MEDIUM** | `OllamaToolCallBridgeTest` (382 lines) tests the XML bridge — many tests need updating or replacement. `SanitizeToolCallPreservationTest` tests the workaround — some tests become simpler, some become unnecessary. New tests needed for native path in `ToolCallLoop`. |
| **Model-behavior risk** | **LOW** | Modern Ollama models already prefer native tool calling. Removing XML prompt instructions may actually *improve* reliability by eliminating conflicting instructions. |
| **UX risk** | **LOW** | No user-visible change except potentially better tool-calling reliability. Stream filtering change is invisible. |
| **Maintenance payoff** | **HIGH** | Eliminates: ~50 lines of sanitize workaround, 185-line stream filter simplification, ~60 lines of XML bridge in OllamaEngine, ~300 tokens/turn in prompt, the entire SUS_HTML bug category. |

---

## 4. Target Architecture

### 4.1 Primary Path: Native Tool Calling

```
[1] SystemPromptBuilder.build()
    │  IF engine.caps().nativeTools():
    │    → short preamble: "You have tools. Use them proactively. Results will follow."
    │    → tool DESCRIPTIONS only (name, what it does)
    │    → NO format instructions (native API handles format)
    │  ELSE:
    │    → JSON fallback preamble (see 4.2)

[2] OllamaEngine.chatStreamViaMessages()
    │  sends native "tools" field (unchanged)
    │  receives streaming response
    │  IF chunk has "tool_calls":
    │    → parse into List<NativeToolCall>
    │    → emit TokenChunk.ofToolCalls(nativeToolCalls)  ← NEW
    │    → DO NOT convert to XML text
    │  ELSE:
    │    → emit TokenChunk.of(text) as before

[3] LlmClient.assembleFromStream()
    │  collects text TokenChunks → StringBuilder (sanitize as before)
    │  collects tool-call TokenChunks → List<NativeToolCall>
    │  returns StreamResult { String text, List<NativeToolCall> toolCalls }  ← NEW
    │  (text sanitization: sanitizeForOutput, NOT sanitizeForOutputPreservingToolCalls)
    │  (tool calls are structured, not in text — no workaround needed)

[4] ToolCallLoop.run() — NEW signature
    │  receives StreamResult (or both text + toolCalls)
    │  IF toolCalls non-empty:
    │    → convert NativeToolCall → ToolCall (trivial map)
    │    → messages.add(ChatMessage.assistantWithToolCalls(prose, nativeToolCalls))
    │    → execute each ToolCall via TurnProcessor (UNCHANGED)
    │    → messages.add(ChatMessage.toolResult(callId, resultContent))
    │    → re-prompt: ctx.llm().chat(messages)
    │  ELSE IF text contains tool-call patterns:
    │    → ToolCallParser.parse(text) (JSON fallback, XML legacy)
    │    → same execution path, but messages.add(ChatMessage.assistant(text))
    │    → messages.add(ChatMessage.user(resultText))  [legacy format]

[5] OllamaEngine.serializeChatMessage() — FIXED
    │  assistant with tool_calls → includes "tool_calls" array (already works)
    │  tool result → role="tool", content=result, tool_call_id=id  ← FIX the missing code
```

### 4.2 Fallback Path: JSON Text

For models that don't support native tool calling:

```
[1] SystemPromptBuilder (non-native branch)
    │  JSON format preamble:
    │    "To use a tool, emit a JSON block:
    │     ```json
    │     {"name": "talos.tool_name", "parameters": {"key": "value"}}
    │     ```
    │     You may emit multiple blocks."
    │  NO XML instructions

[2] ToolCallParser.parse()
    │  Pass 1: code-fenced JSON (promoted from current pass 2)
    │  Pass 2: bare JSON with talos. prefix (promoted from current pass 3)
    │  Pass 3: XML tags (DEMOTED to legacy, kept for read-only compat)

[3] ToolCallStreamFilter
    │  Mode-aware: 
    │    native mode → mostly no-op (tool calls are structured, not in text)
    │    fallback mode → scan for ```json blocks to suppress

[4] Sanitization
    │  sanitizeForOutput() is sufficient
    │  sanitizeForOutputPreservingToolCalls() → deprecated, then removed
    │  (JSON tool calls in code fences don't contain raw HTML — they use
    │   escaped strings, and the fence itself isn't matched by SUS_HTML)
```

### 4.3 Internal Canonical Representation

**`ToolCall` remains the canonical execution abstraction.** Confirmed — no change needed.

`record ToolCall(String toolName, Map<String, String> parameters)` is consumed by:
- `TurnProcessor.executeTool()` → sandbox + approval gate
- `ToolRegistry.execute()` → tool dispatch
- All `TalosTool` implementations
- `ToolCallLoop.repairMissingPath()`
- `ToolCallLoop.resolvePathHint()`
- `TurnProcessor.buildApprovalDetail()`

None of these care about the source format. The migration only affects how `ToolCall` is *constructed* (from `NativeToolCall` vs from `ToolCallParser`).

**One consideration:** `ToolCall.parameters` is `Map<String, String>` (values are always String). `NativeToolCall.arguments` is `Map<String, Object>` (values can be any JSON type). The converter must `String.valueOf()` / `.toString()` non-string values. Currently `OllamaEngine.convertNativeToolCallsToXml` does this via `entry.getValue().asText("")` (line 317), which flattens arrays/objects to empty string. The direct converter should match this behavior for parity, but with a warning log for non-string values.

### 4.4 Re-prompt Path

**Current (broken):**
```java
messages.add(ChatMessage.assistant(currentAnswer));  // raw text with XML
// ...
messages.add(ChatMessage.user(resultText));  // role=user, not tool
```

**Target (native path):**
```java
messages.add(ChatMessage.assistantWithToolCalls(prose, nativeToolCalls));
// ...
messages.add(ChatMessage.toolResult(call.id(), resultContent));
```

**Target (fallback path):**
```java
messages.add(ChatMessage.assistant(currentAnswer));  // text with JSON blocks
// ...
messages.add(ChatMessage.user(resultText));  // role=user (legacy compat)
```

The native path uses proper protocol roles. The fallback path keeps the current behavior (safe, model understands `user` role results).

**Missing piece:** `serializeChatMessage()` must actually serialize `toolCallId`:
```java
if ("tool".equals(m.role()) && m.toolCallId() != null) {
    msg.put("tool_call_id", m.toolCallId());
}
```

**Correlation:** `NativeToolCall.id` may be null from some Ollama models (the `id` field is optional in Ollama's response). The converter should generate a synthetic ID if none is provided: `"call_" + index`.

### 4.5 Streaming Primitive

**Recommendation: Extend `TokenChunk` with an optional `toolCalls` field.**

This is preferred over a new wrapper type because:
- `ModelEngine.chatStream()` returns `Stream<TokenChunk>` — changing the SPI return type is a breaking change
- Adding a field to a record is backward-compatible (existing constructors still work)
- The semantics are clear: a chunk is either text or tool calls (never both in practice)

```java
public record TokenChunk(
    String text, 
    Boolean done, 
    List<ChatMessage.NativeToolCall> toolCalls  // NEW, nullable
) {
    // Backward-compat constructors (existing code compiles unchanged)
    public TokenChunk(String text) { this(text, null, null); }
    public TokenChunk(String text, Boolean done) { this(text, done, null); }
    
    public static TokenChunk of(String text) { return new TokenChunk(text, null, null); }
    public static TokenChunk eos() { return new TokenChunk("", true, null); }
    
    // NEW
    public static TokenChunk ofToolCalls(List<ChatMessage.NativeToolCall> calls) {
        return new TokenChunk("", null, calls);
    }
    
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
```

**Why not a separate response envelope?**  
`assembleFromStream()` already collects the stream into a `String`. Adding a `StreamResult` record there is also valid. But the `TokenChunk` extension is strictly better because it allows the *caller* (not just `LlmClient`) to detect tool calls during streaming — useful for future event-driven architectures.

**Why not a completely separate method?**  
`ModelEngine` is the SPI. Adding a new method (`chatStreamNative()`) forces all engine implementations to implement it. The `TokenChunk` extension is additive — engines that don't support native tools simply never emit tool-call chunks.

### 4.6 Sanitization and Stream Filtering After XML Retirement

**Sanitization simplification:**

| Method | Current role | After migration |
|--------|-------------|-----------------|
| `sanitizeForOutput()` | Full sanitization (ctrl + think + SUS_HTML) | **Primary.** Used for all text. Unchanged. |
| `sanitizeForOutputPreservingToolCalls()` | SUS_HTML workaround for XML tool blocks | **Deprecated → removed.** Not needed when tool calls are structured. |
| `sanitizeMessageContent()` | Ctrl-chars only for messages to model | **Kept.** Still needed for message content. |
| `stripSuspiciousHtmlOutsideToolCalls()` | Walk-and-protect algorithm | **Removed.** Dead code once tool calls are not in text. |
| `TOOL_CALL_BLOCK` pattern | Identifies tool_call XML blocks | **Removed.** Not needed. |

**Stream filter simplification:**

The native path needs no stream filtering — tool calls are structured, never in the text stream. The JSON fallback path needs code-fence filtering (simpler than XML tag matching).

Two options:
1. **Simplify `ToolCallStreamFilter`** to handle both XML (legacy) and code-fenced JSON, with a no-op fast path when no patterns are present.
2. **Replace with a simpler approach**: on the native path, tool calls are never emitted as text chunks, so the filter becomes a pass-through. On the fallback path, `ToolCallParser.stripToolCalls()` already handles post-hoc removal — the stream filter could be simplified to a thin wrapper.

Recommend option 1 — keep the filter but add a fast path. Don't delete it entirely until XML is fully retired from the parser.

---

## 5. Implementation Plan

### HIGH Priority (Mandatory First Steps)

#### H1: Extend `TokenChunk` with optional `toolCalls`

**Goal:** Give the SPI streaming contract a way to carry structured tool calls.

**Files:**
- `TokenChunk.java` — add `List<NativeToolCall> toolCalls` field, backward-compat constructors, `ofToolCalls()`, `hasToolCalls()`

**Why HIGH:** This is the foundational SPI change that unblocks everything else. Without it, no other step can pass native tool calls through the stream.

**Risks:**
- Record field addition changes the canonical constructor. Any code calling `new TokenChunk(text, done)` still compiles (the 2-arg constructor is kept).
- Test grep for `TokenChunk` usages to verify no breakage.

**Tests:**
- Unit tests for new constructors, `ofToolCalls()`, `hasToolCalls()`, backward compat.

**Must NOT mix into this PR:** Any changes to `OllamaEngine`, `LlmClient`, or `ToolCallLoop`. This is a pure SPI type change.

---

#### H2: `OllamaEngine` returns native tool calls as structured `TokenChunk`

**Goal:** Stop converting native tool calls to XML. Emit `TokenChunk.ofToolCalls()` instead.

**Files:**
- `OllamaEngine.java` — change `chatStreamViaMessages()` lambda (lines 448-464) to emit `TokenChunk.ofToolCalls(...)` instead of `convertNativeToolCallsToXml()`. Change `extractChatContentOrToolCalls()` (lines 247-269) for non-streaming path.
- `OllamaEngine.java` — fix `serializeChatMessage()` to actually serialize `toolCallId` (lines 547-549)

**Why HIGH:** This is the bridge elimination. Native tool calls stop being destroyed.

**Risks:**
- `LlmClient.assembleFromStream()` doesn't expect tool-call chunks yet. Must handle gracefully (skip text append, collect tool calls separately).
- `OllamaToolCallBridgeTest` — many tests assert XML output. Must be rewritten.

**Tests:**
- Updated `OllamaToolCallBridgeTest`: assert `TokenChunk.hasToolCalls()` instead of XML strings.
- Verify non-streaming path returns tool calls via new mechanism.
- Verify `serializeChatMessage()` now includes `tool_call_id`.

**Must NOT mix into this PR:** Changes to `ToolCallLoop` or `SystemPromptBuilder`. This PR changes the engine layer only.

---

#### H3: `LlmClient.assembleFromStream()` collects native tool calls

**Goal:** The stream assembly method handles both text chunks and tool-call chunks, returning both to callers.

**Files:**
- `LlmClient.java` — `assembleFromStream()` gains a `List<NativeToolCall>` side-collection. Returns a `StreamResult` record or exposes via a callback.
- `LlmClient.java` — new `chatStream()` overload that returns `StreamResult` (or: new method `chatStreamStructured()`)
- Alternative: pass tool calls via a mutable holder/callback rather than changing return type.

**Why HIGH:** Without this, `ToolCallLoop` can't receive native tool calls from `LlmClient`.

**Risks:**
- `LlmClient` has many `chatStream()` overloads. Adding return type change touches the public API.
- **Pragmatic approach:** Rather than changing all return types, add a package-private field or ThreadLocal that `ToolCallLoop` reads. This avoids a large API change.
- **Better approach:** New `ChatStreamResult` record returned by a new `chatStreamFull()` method. Existing `chatStream()` methods continue returning `String` for backward compat.

**Tests:**
- Unit test: stream with tool-call chunk → `StreamResult` contains tool calls.
- Unit test: stream without tool-call chunks → `StreamResult` has empty tool calls.
- Backward compat: existing `chatStream()` methods still return `String`.

**Must NOT mix into this PR:** Changes to `ToolCallLoop` or `SystemPromptBuilder`.

---

#### H4: `ToolCallLoop` native tool-call path

**Goal:** When native tool calls are present, use them directly (no regex parsing). Use proper message types for re-prompting.

**Files:**
- `ToolCallLoop.java` — `run()` signature change: accept `List<NativeToolCall>` alongside `String initialAnswer`. If native calls present, convert to `ToolCall` directly. Use `ChatMessage.assistantWithToolCalls()` and `ChatMessage.toolResult()`.
- New: `NativeToolCallConverter.java` (or inline in `ToolCallLoop`) — `NativeToolCall → ToolCall` mapping.

**Why HIGH:** This completes the native pipeline end-to-end.

**Risks:**
- `ToolCallLoop.run()` is called from `AssistantTurnExecutor`. Its signature change must be coordinated.
- Fallback path must still work: when no native calls present but text contains tool patterns, use `ToolCallParser` as before.
- Approval gate, sandbox, progress UX must all still fire correctly.

**Tests:**
- New: `ToolCallLoopNativeTest` — native tool calls are executed correctly.
- New: test that native path uses `assistantWithToolCalls` and `toolResult` message types.
- Existing: all current `ToolCallLoop` tests must still pass (fallback path).
- Integration: approval gate fires for native path mutations.

**Must NOT mix into this PR:** Prompt changes or stream filter changes.

---

#### H5: `SystemPromptBuilder` conditional prompt

**Goal:** When native tools are enabled, omit XML format instructions. Keep tool descriptions.

**Files:**
- `SystemPromptBuilder.java` — accept a boolean `nativeToolsEnabled` flag. When true, use a short preamble instead of `tools-preamble.txt`.
- New: `prompts/sections/tools-preamble-native.txt` — short native preamble.
- `Capabilities.java` — add `boolean nativeTools` field.
- `OllamaEngine.java` — return `nativeToolCalling` in `caps()`.

**Why HIGH:** Eliminates ~300 wasted tokens per turn and the contradictory dual instruction.

**Risks:**
- Must not break models that DON'T support native tools. The `Capabilities.nativeTools` signal must be correct.
- Must not break tests that expect specific system prompt content.

**Tests:**
- Unit test: `SystemPromptBuilder` with `nativeTools=true` does NOT contain `<tool_call>`.
- Unit test: `SystemPromptBuilder` with `nativeTools=false` still contains format instructions (JSON fallback, not XML).
- Verify: prompt token estimate decreases.

**Must NOT mix into this PR:** Tool loop changes or engine changes.

---

### MEDIUM Priority (Next Wave)

#### M1: Update `ToolCallStreamFilter` for native + JSON fallback

**Goal:** Native path is no-op (tool calls aren't in text). Fallback path suppresses code-fenced JSON blocks.

**Files:**
- `ToolCallStreamFilter.java` — add code-fence detection alongside XML detection. Add a fast-path skip when in native mode.

**Why MEDIUM:** Not blocking — the native path doesn't need filtering (tool calls are structured). But the fallback path currently would display JSON blocks to the user.

**Risks:** Low. The filter is isolated.

**Tests:**
- Native mode: all text passes through unmodified.
- Fallback mode: code-fenced JSON blocks are suppressed.
- Legacy: XML blocks still suppressed (backward compat).

---

#### M2: Simplify `Sanitize.java` — deprecate tool-call awareness

**Goal:** Remove `sanitizeForOutputPreservingToolCalls()`, `TOOL_CALL_BLOCK`, `stripSuspiciousHtmlOutsideToolCalls()`.

**Files:**
- `Sanitize.java` — deprecate methods (keep for one release cycle).
- `LlmClient.java` — switch to `sanitizeForOutput()`.
- `ToolCallLoop.java` — verify `stripSuspiciousHtml()` on final prose is still correct (it is — tool calls are already stripped at that point).

**Why MEDIUM:** The workaround is no longer needed once native tool calls bypass text sanitization. But rushing this before the native path is stable risks regression.

**Risks:** Must verify that the fallback path (JSON in text) doesn't trigger SUS_HTML. Code-fenced JSON contains escaped content, not raw HTML tags — this should be safe, but must be tested.

**Tests:**
- Regression: `SanitizeToolCallPreservationTest.RegressionBug` — verify the original bug scenario still works.
- New: JSON fallback with HTML content in tool params → not corrupted.

---

#### M3: Add correlation ID tracking

**Goal:** `NativeToolCall.id` (or synthetic) flows through the pipeline. `ChatMessage.toolResult()` carries the correct ID. `serializeChatMessage()` sends it.

**Files:**
- `ToolCallLoop.java` — capture `NativeToolCall.id` when converting to `ToolCall`, pass to `toolResult()`.
- `OllamaEngine.java` — verify serialization (already fixed in H2).
- `ToolCall.java` — consider adding optional `callId` field (or keep as side data).

**Why MEDIUM:** Ollama doesn't require `tool_call_id` today, but the model provider and OpenAI protocols do. Future-proofing for multi-backend SPI.

**Risks:** Minimal. Additive change.

---

#### M4: Update `tools-preamble.txt` for JSON fallback

**Goal:** Replace XML instructions with JSON instructions in the text fallback preamble.

**Files:**
- `tools-preamble.txt` — rewrite: code-fenced JSON format, no XML references.
- `SystemPromptBuilder.DEFAULT_TOOLS_PREAMBLE` — update inline fallback to match.

**Why MEDIUM:** After H5 (conditional prompt), this file is only used for non-native engines. It should instruct JSON, not XML.

**Risks:** Model behavior may change. Must test with a model that uses the fallback path.

---

### LOW Priority (Later Cleanup)

#### L1: Remove `convertNativeToolCallsToXml()` method

**Goal:** Delete the dead bridge method and its tests.

**Files:**
- `OllamaEngine.java` — delete `convertNativeToolCallsToXml()`.
- `OllamaToolCallBridgeTest.java` — delete `ConvertNativeToolCallsToXml` nested class (or whole file if no other tests remain).

**Why LOW:** After H2, this method is never called. Safe to delete after one release cycle.

---

#### L2: Remove sanitize workaround methods

**Goal:** Delete deprecated `sanitizeForOutputPreservingToolCalls()` and related private methods.

**Files:**
- `Sanitize.java` — remove deprecated methods.
- `SanitizeToolCallPreservationTest.java` — simplify or remove `PreservingToolCalls` tests.

**Why LOW:** After M2 deprecation cycle.

---

#### L3: Demote XML in `ToolCallParser`

**Goal:** Change pass order: JSON first, XML last. XML becomes the lowest-priority fallback.

**Files:**
- `ToolCallParser.java` — reorder: Pass 1 = code-fenced JSON, Pass 2 = bare JSON, Pass 3 = XML tags.

**Why LOW:** Cosmetic. The parser already handles all formats. Reordering reflects the new priority but doesn't change functionality.

---

#### L4: Simplify `ToolCallStreamFilter` (post-XML)

**Goal:** After XML retirement is complete, simplify the filter to only handle code-fenced JSON (or remove it entirely if native-only).

**Files:**
- `ToolCallStreamFilter.java` — remove XML-specific patterns, simplify state machine.

**Why LOW:** The filter works fine as-is. Simplification is maintenance quality-of-life.

---

#### L5: Remove `CodeBlockToolExtractor` detection from `AssistantTurnExecutor`

**Goal:** `CodeBlockToolExtractor.containsExtractableBlocks()` is disabled but still called in `hasAnyToolCalls()`. Remove the dead check.

**Files:**
- `AssistantTurnExecutor.java` line 44 — remove `CodeBlockToolExtractor` reference.

**Why LOW:** The extractor is already disabled in `ToolCallLoop.run()`. The check in `hasAnyToolCalls()` is harmless but misleading.

---

## 6. Concrete PR Sequence

### PR-1: `feat(spi): extend TokenChunk with optional toolCalls`

**Purpose:** Foundation SPI type change enabling structured tool-call streaming.

**Files:** `TokenChunk.java`, new unit test `TokenChunkTest.java`

**Why bounded:** Pure type change. No behavior change. All existing code compiles unchanged.

**Major risk:** None. Additive record field with backward-compat constructors.

**Must not regress:** All existing `TokenChunk.of()` and `TokenChunk.eos()` callers still work.

**Tests:** `TokenChunkTest`: constructors, `ofToolCalls`, `hasToolCalls`, backward compat.

---

### PR-2: `feat(spi): add nativeTools to Capabilities`

**Purpose:** SPI signal for native tool support. Enables conditional behavior upstream.

**Files:** `Capabilities.java` (add field), `OllamaEngine.java` (return in `caps()`), test updates.

**Why bounded:** Small SPI type change. Additive.

**Major risk:** Callers that destructure `Capabilities` may need updating.

**Must not regress:** Existing `Capabilities.of(chat, stream, embed, ctx)` callers. Add overload.

**Tests:** `Capabilities` factory methods, `OllamaEngine.caps()` returns `nativeTools=true`.

---

### PR-3: `feat(engine): OllamaEngine returns native tool calls via TokenChunk`

**Purpose:** Stop converting native tool calls to XML. Fix `toolCallId` serialization.

**Files:** `OllamaEngine.java` (stream + non-stream paths, `serializeChatMessage`), updated `OllamaToolCallBridgeTest`

**Why bounded:** Engine-layer only. `LlmClient` will receive tool-call chunks but currently ignores unknown fields — no breakage.

**Major risk:** `LlmClient.assembleFromStream()` receives `TokenChunk` with `toolCalls` set. Currently it accesses `.text()` which returns `""` for tool-call chunks. Tool calls would be silently lost until PR-4. **Mitigation:** This PR must be followed immediately by PR-4, OR `LlmClient` should be updated in the same PR to at minimum not lose tool calls.

**Actually — better to merge PR-3 and PR-4 as one PR to avoid a broken intermediate state.** See revised PR-3+4 below.

**Must not regress:** Non-streaming `chat()` path, streaming text-only responses.

**Tests:** Updated bridge tests asserting `TokenChunk.hasToolCalls()`. `serializeChatMessage` test for `tool_call_id`.

---

### PR-3+4 (merged): `feat: native tool-call pipeline (engine → client → loop)`

**Purpose:** Complete the native tool-call pipeline from `OllamaEngine` through `LlmClient` to `ToolCallLoop`. This is the core migration PR.

**Files:**
- `OllamaEngine.java` — emit `TokenChunk.ofToolCalls()` instead of XML, fix `toolCallId` serialization
- `LlmClient.java` — `assembleFromStream()` collects tool-call chunks; new internal `StreamResult` or side-channel; new method to expose structured result
- `ToolCallLoop.java` — accept native tool calls, convert to `ToolCall`, use `assistantWithToolCalls()` + `toolResult()` for re-prompt, keep fallback path
- `AssistantTurnExecutor.java` — pass native tool calls from `LlmClient` to `ToolCallLoop`

**Why bounded:** The boundary is clear: engine→client→loop. Tool execution (`TurnProcessor`, tools, sandbox, approval) is untouched. Prompt generation is untouched. Stream filter is untouched (tool calls are no longer in the text stream on the native path, so the filter simply doesn't trigger).

**Major risk:** This is the largest PR. Must be carefully tested. The fallback path (text-based tool calls) must continue working for models that don't use native tools.

**Must not regress:**
- Approval gate fires for write/edit (tested via `TurnProcessor`)
- No-op edit rejection (tested via `EditFileTool`)
- Sandbox enforcement (tested via `Sandbox` tests)
- Tool progress UX (tested via `ToolProgressSink`)
- Verification status (tested via `ToolResult.verification()`)
- Compaction behavior (tested via compaction tests)

**Tests:**
- New: `ToolCallLoopNativeTest` — end-to-end with `NativeToolCall` input
- New: native path uses correct `ChatMessage` types
- Updated: `OllamaToolCallBridgeTest` for new behavior
- Existing: all `ToolCallLoop` tests pass (fallback path)
- Existing: all `ToolCallParser` tests pass (unchanged)

---

### PR-5: `feat(prompt): conditional system prompt for native tool engines`

**Purpose:** Eliminate XML format instructions when native tools are available. Save ~300 tokens/turn.

**Files:**
- `SystemPromptBuilder.java` — accept `nativeTools` flag, conditional preamble
- New: `tools-preamble-native.txt` — short native preamble
- `Capabilities.java` — already updated in PR-2

**Why bounded:** Prompt-only change. No pipeline changes.

**Major risk:** Model behavior change. Must live-test.

**Must not regress:** Tool descriptions still present. File creation/modification rules still present.

**Tests:** Unit tests for prompt content with native=true/false. Token estimate comparison.

---

### PR-6: `feat(prompt): JSON fallback preamble (replaces XML instructions)`

**Purpose:** For non-native engines, instruct JSON format instead of XML.

**Files:**
- `tools-preamble.txt` — rewrite with JSON examples
- `SystemPromptBuilder.DEFAULT_TOOLS_PREAMBLE` — update inline fallback

**Why bounded:** Text-only change to prompt resources.

**Major risk:** Model behavior with JSON instructions vs XML. Must test with a fallback model.

**Must not regress:** Tool calling works for non-native models.

**Tests:** Live test with a model using text fallback.

---

### PR-7: `chore: update ToolCallStreamFilter for JSON fallback`

**Purpose:** Add code-fence detection to the stream filter. Native path fast-pass.

**Files:** `ToolCallStreamFilter.java`

**Why bounded:** Isolated display-layer change.

**Major risk:** Low. Filter is a display concern only — doesn't affect tool execution.

**Must not regress:** XML blocks still suppressed (legacy compat). Normal text passes through.

**Tests:** Native mode pass-through. JSON fence suppression. XML suppression (legacy).

---

### PR-8: `chore: deprecate sanitize tool-call-awareness`

**Purpose:** Mark `sanitizeForOutputPreservingToolCalls()` and related methods as `@Deprecated`.

**Files:** `Sanitize.java`, `LlmClient.java` (switch to `sanitizeForOutput()`).

**Why bounded:** Simple method swap + deprecation annotation.

**Major risk:** Must verify JSON fallback doesn't trigger SUS_HTML on tool params.

**Must not regress:** `SanitizeToolCallPreservationTest.RegressionBug` tests.

**Tests:** New: JSON-in-text with HTML params → not corrupted by `sanitizeForOutput()`.

---

### PR-9: `chore: cleanup — remove deprecated XML bridge + sanitize workaround`

**Purpose:** Delete `convertNativeToolCallsToXml()`, deprecated sanitize methods, update tests.

**Files:** `OllamaEngine.java`, `Sanitize.java`, `OllamaToolCallBridgeTest.java`, `SanitizeToolCallPreservationTest.java`

**Why bounded:** Pure deletion of dead code.

**Major risk:** None if previous PRs are stable.

**Must not regress:** Full test suite passes.

**Tests:** Remove tests for deleted methods. Verify no callers remain via compilation.

---

## A. Final Judgment

### Is XML now technical debt?

**Yes.** XML is actively harmful, not merely wasteful. It:
- Caused a critical production bug (SUS_HTML corrupting `<script>` in tool params)
- Wastes ~300 tokens/turn on contradictory format instructions
- Forces a serialize→regex-parse roundtrip that destroys structured data
- Required 50+ lines of sanitization workaround
- Maintains a 185-line stream filter that's unnecessary on the native path

### Is `v0.9.0-beta-dev` a good enough base for the migration?

**Yes.** The branch has:
- Clean tool execution pipeline (ToolCall → TurnProcessor → sandbox → approval → tool)
- Native SPI types already defined (`NativeToolCall`, `toolResult()`, `assistantWithToolCalls()`)
- Native engine transport already working (`OllamaEngine` sends/receives native)
- Comprehensive test suite (2016 tests passing)
- Recent hardening of the safety/trust layer that must not regress

### Should the next branch be the XML retirement / native+JSON refactor branch?

**Yes.** Create `feature/native-tool-pipeline` from `v0.9.0-beta-dev`. The migration is bounded, the infrastructure exists, and the longer it's deferred, the more code accumulates that depends on the XML path.

### What is the single biggest technical blocker to doing it cleanly?

**`TokenChunk` having no mechanism to carry structured tool calls.**

This is a one-line-ish change to a record, but it's the SPI boundary that gates everything. Once `TokenChunk` can carry `List<NativeToolCall>`, the entire pipeline can be rewired incrementally. Without it, `OllamaEngine` is forced to serialize native data to text, and the entire XML burden follows.

---

## B. Non-Regression Checklist

The following properties must be preserved across the entire migration:

| Property | Where tested | Why it could regress |
|----------|-------------|---------------------|
| **No guessed mutation targets** | `ToolCallLoop.repairMissingPath()`, `PathInferenceTest` | `NativeToolCall → ToolCall` converter must not add path inference |
| **No code-block fallback writes** | `ToolCallLoop.run()` line 141-144 (warning only) | Must not re-enable during refactor |
| **Approval previews** | `TurnProcessor.executeTool()`, `ApprovalGateTest` | Approval gate operates on `ToolCall` (format-agnostic) — should be safe |
| **Structured verification status** | `ToolResult.verification()`, write/edit tool tests | Tool execution is unchanged — should be safe |
| **Tool progress UX** | `ToolCallLoop.emitProgress()`, `ToolProgressSink` | Progress operates on `ToolCall.toolName()` — should be safe |
| **Compaction improvements** | Compaction tests | Compaction operates on `ChatMessage.content()` — must verify `assistantWithToolCalls()` messages compact correctly |
| **Payload-safe sanitization** | `SanitizeToolCallPreservationTest` | Until M2, the workaround stays. After M2, verify JSON fallback is safe |
| **`ToolCall` execution semantics** | All tool tests, `TurnProcessorTest` | `ToolCall` record is unchanged — zero risk |
| **No-op edit rejection** | `EditFileToolTest` | Operates on `ToolCall.parameters()` — format-agnostic |
| **Stream display doesn't show protocol** | `ToolCallStreamFilter` tests | Native: tool calls never in text. Fallback: filter updated in PR-7 |
| **Tool result formatting** | `ToolCallLoop.formatToolResult()` | Unchanged — formats `ToolCall` + `ToolResult`, not format-specific |
| **Multi-turn context integrity** | Chat/session tests | `ChatMessage` types are additive. Backward-compat constructor preserved |
| **Config flag respected** | `OllamaEngineProviderTest`, config tests | `nativeToolCalling` boolean gates behavior — must remain functional |
| **Error handling in tool loop** | `ToolCallLoop` error paths | Must verify native path error handling matches fallback path |

---

## C. Comparison With Reference Repos

### Disclaimer

I do not have direct access to browse `chauncygu/collection-external-coding-assistant-source-code` or `ultraworkers/claw-code` at runtime. The comparison below is based on publicly documented architecture patterns of local coding assistant and similar agent frameworks.

### local coding assistant Architecture Patterns

**What they do well:**
1. **Structured tool protocol throughout.** Tool calls are JSON objects with `type: "tool_use"`, tool results are `type: "tool_result"` with `tool_use_id` correlation. No text-based format at any layer.
2. **Correlation IDs are mandatory.** Every tool call has an `id`, every result references it. This enables parallel tool execution and unambiguous result matching.
3. **No format instructions in system prompt.** The API handles tool format — the prompt only describes tool *semantics* (when to use, what each tool does).
4. **Streaming events, not text chunks.** Tool use events are distinct from text content events in the stream.

**What Talos should borrow:**
- Correlation ID discipline (PR M3)
- Removing format instructions from prompt when native tools available (PR H5)
- Distinct streaming events for tool calls (PR H1: `TokenChunk.ofToolCalls()`)

**What Talos should NOT copy:**
- local coding assistant assumes a cloud API with guaranteed native tool support. Talos must handle local models that may not support native tools → fallback path is essential.
- local coding assistant's streaming event model is deeply integrated with the model provider API. Talos's SPI must remain backend-neutral.

### Claw-Code Architecture Patterns

**What they do well:**
1. **Agent loop with explicit state transitions.** Tool calls, results, and re-prompts are state machine transitions, not text parsing heuristics.
2. **Result serialization is type-aware.** Tool results carry structured metadata (success/failure, output type, size) rather than being flattened to text.
3. **Parallel tool execution.** When multiple tool calls are returned, they can execute concurrently.

**What Talos should borrow:**
- Structured tool result metadata (Talos already has `ToolResult.verification()` — this is partially there)
- The concept of native tool calls as a first-class pipeline stage rather than a text parsing artifact

**What Talos should NOT copy:**
- Parallel tool execution — in a local-first CLI with user approval gates, parallel execution would create confusing UX (multiple approval prompts simultaneously).
- Heavy state machine abstraction — Talos's `ToolCallLoop` is deliberately simple (while loop with max iterations). Over-engineering the state machine would add complexity without corresponding benefit for a single-user CLI.

### What Is Incompatible With Talos's Local-First CLI Constraints

1. **Assuming reliable native tool support.** Both reference repos assume a cloud API that always supports native tools. Talos must handle local models with varying capabilities. The fallback path is non-negotiable.
2. **Assuming fast, reliable responses.** Cloud APIs have SLAs. Local Ollama may be slow, may crash, may OOM. Talos's retry/timeout/error-handling in `ToolCallLoop` and `LlmClient` is more robust than what cloud-oriented agents need, and must not be simplified away.
3. **Assuming trust in model output.** Cloud-hosted models are API-controlled. Local models may produce unexpected formats, hallucinated tool names, or malformed JSON. Talos's defensive parsing (`ToolCallParser` with multiple fallback patterns) and validation (`repairMissingPath`, no-op edit rejection) are essential safety nets that the reference repos don't need.

---

*Review complete. All claims verified against code in `v0.9.0-beta-dev`.*  
*Findings that could not be verified from code alone: None — all analysis is code-grounded.*

