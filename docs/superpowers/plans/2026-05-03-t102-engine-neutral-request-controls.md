# T102 Engine-Neutral Request Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add provider-neutral request-control and capability metadata so Talos runtime can reason about tool-choice and structured-output support without naming Ollama.

**Architecture:** Add small SPI value types under `dev.talos.spi.types`, thread them through `ChatRequest`, `Capabilities`, and `PromptDebugSnapshot`, and keep all existing constructors/factories backward compatible. This ticket does not serialize provider-specific HTTP fields; T103 owns that.

**Tech Stack:** Java records/enums, JUnit 5, Gradle.

---

### Task 1: Add Request-Control Value Types

**Files:**
- Create: `src/main/java/dev/talos/spi/types/ToolChoiceMode.java`
- Create: `src/main/java/dev/talos/spi/types/ResponseFormatMode.java`
- Create: `src/main/java/dev/talos/spi/types/ChatRequestControls.java`
- Test: `src/test/java/dev/talos/spi/types/ChatRequestControlsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.talos.spi.types;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatRequestControlsTest {
    @Test
    void defaultsAreAutoTextWithNoSchemaOrTags() {
        ChatRequestControls controls = ChatRequestControls.defaults();

        assertEquals(ToolChoiceMode.AUTO, controls.toolChoice());
        assertEquals("", controls.namedTool());
        assertEquals(ResponseFormatMode.TEXT, controls.responseFormat());
        assertEquals("", controls.jsonSchema());
        assertTrue(controls.debugTags().isEmpty());
    }

    @Test
    void namedToolChoiceRequiresToolName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ChatRequestControls(
                        ToolChoiceMode.NAMED,
                        " ",
                        ResponseFormatMode.TEXT,
                        "",
                        List.of()));

        assertTrue(error.getMessage().contains("namedTool"));
    }

    @Test
    void debugTagsAreTrimmedAndBlankTagsAreDropped() {
        ChatRequestControls controls = new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.JSON_SCHEMA,
                "{\"type\":\"object\"}",
                List.of(" obligation ", "", " turn-7 "));

        assertEquals(List.of("obligation", "turn-7"), controls.debugTags());
        assertEquals("{\"type\":\"object\"}", controls.jsonSchema());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
./gradlew.bat test --tests "dev.talos.spi.types.ChatRequestControlsTest" --no-daemon
```

Expected: fails because `ChatRequestControls`, `ToolChoiceMode`, and `ResponseFormatMode` do not exist.

- [ ] **Step 3: Implement the value types**

Create enums with values:

```java
public enum ToolChoiceMode {
    AUTO,
    NONE,
    REQUIRED,
    NAMED
}
```

```java
public enum ResponseFormatMode {
    TEXT,
    JSON_OBJECT,
    JSON_SCHEMA
}
```

Create `ChatRequestControls` as an immutable record that normalizes nulls,
trims debug tags, and rejects `NAMED` without a tool name.

- [ ] **Step 4: Run the test to verify it passes**

Run:

```powershell
./gradlew.bat test --tests "dev.talos.spi.types.ChatRequestControlsTest" --no-daemon
```

Expected: pass.

### Task 2: Thread Controls Through ChatRequest And Prompt Debug

**Files:**
- Modify: `src/main/java/dev/talos/spi/types/ChatRequest.java`
- Modify: `src/main/java/dev/talos/spi/types/PromptDebugSnapshot.java`
- Test: `src/test/java/dev/talos/spi/types/ChatRequestControlsTest.java`
- Test: `src/test/java/dev/talos/core/llm/LlmClientPromptDebugCaptureTest.java`

- [ ] **Step 1: Extend the failing test**

Add assertions proving:

```java
ChatRequest request = new ChatRequest(
        "llama_cpp", "model.gguf", "", "", List.of(), null,
        List.of(ChatMessage.user("hi")),
        List.of(),
        new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "",
                ResponseFormatMode.JSON_OBJECT,
                "",
                List.of("repair")));

assertEquals(ToolChoiceMode.REQUIRED, request.controls.toolChoice());
assertEquals(ResponseFormatMode.JSON_OBJECT, request.controls.responseFormat());
assertEquals(List.of("repair"), request.controls.debugTags());
```

In `LlmClientPromptDebugCaptureTest`, add a direct `PromptDebugSnapshot`
assertion that `fromChatRequest` preserves controls from a request.

- [ ] **Step 2: Run tests to verify failure**

Run:

```powershell
./gradlew.bat test --tests "dev.talos.spi.types.ChatRequestControlsTest" --tests "dev.talos.core.llm.LlmClientPromptDebugCaptureTest" --no-daemon
```

Expected: fails because `ChatRequest` and `PromptDebugSnapshot` do not expose controls.

- [ ] **Step 3: Implement minimal threading**

Add `public final ChatRequestControls controls` to `ChatRequest`.
Keep all existing constructors delegating to `ChatRequestControls.defaults()`.
Add one full constructor accepting controls.

Add `ChatRequestControls controls` to `PromptDebugSnapshot` and populate it in
`fromChatRequest` and `fromProviderBody`.

- [ ] **Step 4: Run tests to verify pass**

Run:

```powershell
./gradlew.bat test --tests "dev.talos.spi.types.ChatRequestControlsTest" --tests "dev.talos.core.llm.LlmClientPromptDebugCaptureTest" --no-daemon
```

Expected: pass.

### Task 3: Extend Capability Reporting

**Files:**
- Modify: `src/main/java/dev/talos/spi/types/Capabilities.java`
- Test: `src/test/java/dev/talos/spi/ModelEngineCompositionTest.java`

- [ ] **Step 1: Write failing assertions**

Add a test proving `Capabilities.of(...)` keeps existing native-tool behavior
while defaulting new provider-control flags to false, and add a test proving a
full capability value can express required tool choice and JSON schema support.

- [ ] **Step 2: Run targeted tests**

Run:

```powershell
./gradlew.bat test --tests "dev.talos.spi.ModelEngineCompositionTest" --no-daemon
```

Expected: fails because the new accessors do not exist.

- [ ] **Step 3: Implement capability fields and factories**

Extend `Capabilities` with:

- `requiredToolChoice`
- `namedToolChoice`
- `jsonObjectResponse`
- `jsonSchemaResponse`
- `serverModelCatalog`
- `managedProcess`

Keep the existing `of` factory methods and add a new full factory.

- [ ] **Step 4: Run targeted tests**

Run:

```powershell
./gradlew.bat test --tests "dev.talos.spi.ModelEngineCompositionTest" --no-daemon
```

Expected: pass.

### Task 4: Integration Verification And Ticket Closeout

**Files:**
- Modify ticket status only after tests pass:
  `work-cycle-docs/tickets/open/[T102-open-high] engine-neutral-provider-capability-and-request-control-spine.md`

- [ ] **Step 1: Run focused test set**

```powershell
./gradlew.bat test --tests "dev.talos.spi.*" --tests "dev.talos.core.llm.*PromptDebug*" --tests "dev.talos.engine.ollama.*PromptDebug*" --no-daemon
```

Expected: pass.

- [ ] **Step 2: Run full unit tests**

```powershell
./gradlew.bat test --no-daemon
```

Expected: pass.

- [ ] **Step 3: Move T102 to done**

Move the ticket to:

```text
work-cycle-docs/tickets/done/[T102-done-high] engine-neutral-provider-capability-and-request-control-spine.md
```

Update status in the ticket body to `Done`.

- [ ] **Step 4: Commit**

```powershell
git add -f docs/superpowers/plans/2026-05-03-t102-engine-neutral-request-controls.md
git add src/main/java/dev/talos/spi/types src/test/java/dev/talos/spi src/test/java/dev/talos/core/llm work-cycle-docs/tickets
git commit -m "feat: add engine-neutral request controls"
```
