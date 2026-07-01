# T832 In-turn compaction evidence and conditional gist

## Provenance

- Branch: `v0.9.0-beta-dev`
- Base commit: `4edb69cfcc7474f409b786f3d40ee4ddc8e965f2`
- Talos version: `0.10.5`
- Ticket: `work-cycle-docs/tickets/open/[T832-open-high] in-turn-compaction-evidence-and-conditional-gist.md`
- Confidence: `INFERRED_REVIEW` for artifact interpretation, `DETERMINISTIC_STATIC` for source anchors, `OBSERVED_RUNTIME` for characterization tests after they pass.

## Source Anchors

- In-turn compaction helper: `src/main/java/dev/talos/runtime/toolcall/ToolCallSupport.java`
- Reprompt call site: `src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java`
- Per-turn retained read bodies: `src/main/java/dev/talos/runtime/toolcall/LoopState.java`
- Session-level context compaction, separate system: `src/main/java/dev/talos/core/context/ConversationManager.java`
- Token budget default: `src/main/java/dev/talos/core/context/TokenBudget.java`
- Runtime config defaults: `src/main/resources/config/default-config.yaml`

## Current Mechanism

`ToolCallRepromptStage` calls `ToolCallSupport.compactOlderToolResultsInPlace(...)`
only when `state.iterations >= 3`. The helper is therefore not a first-iteration
or second-iteration behavior.

The helper scans role `tool` messages, keeps the last two tool results verbatim,
and replaces older nonblank results with `ChatMessage.toolResult(...)` carrying
a stub from `summarizeToolResult(...)`.

The current stub includes:

- tool name parsed from `[tool_result: ...]`,
- success versus error based on `[error]`,
- original character count,
- a fixed elision phrase.

The current stub does not include a gist of the result content.

Already compacted messages beginning `[compacted:]` are skipped. Synthetic user
messages beginning `[tool_result:`, `[tool_result]`, or `[compacted:]` are
ignored by `latestUserRequestIn(...)`.

`LoopState.readFileBodiesThisTurn` retains read-file bodies for later
verification and protected-read answer guards. That retained state is not
prompt rehydration. Once a tool-result prompt message is compacted, the elided
text is not automatically restored into a later model prompt.

## Session Compaction Boundary

This report is about in-turn tool-loop compaction only. It does not characterize
or change session-level context handling in `core.context.ConversationManager`,
`ContextPacker`, or `TokenBudget`.

## Local Artifact Measurement

Phase 1 scanned existing local provider-body and prompt-debug artifacts under
`local/`.

Measurement summary:

| Metric | Value |
| --- | ---: |
| Provider-body JSON files scanned | 12,571 |
| Original Phase 1 files containing `[compacted:]` | 738 |
| Original Phase 1 parseable compacted provider bodies | 700 |
| Current hygiene rescan files containing `[compacted:]` | 740 |
| Current hygiene rescan parseable compacted provider bodies | 702 |

The file count uses the literal glob `local/**/*provider-body*.json`. The
`local/` artifact corpus is live. Audit and test runs may add provider-body
files, so the `[compacted:]` count can drift slightly between scans. The
original Phase 1 scan found 738 and 700. The current hygiene rescan found 740
and 702.

Reproduction script:

```powershell
@'
import json
from pathlib import Path

files = list(Path("local").rglob("*provider-body*.json"))
raw_compacted = 0
parseable = 0
parse_failures = 0
with_messages = 0
parseable_compacted = 0

for path in files:
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        continue

    if "[compacted:" in text:
        raw_compacted += 1

    try:
        data = json.loads(text)
        parseable += 1
    except Exception:
        parse_failures += 1
        continue

    messages = data.get("messages") if isinstance(data, dict) else None
    if not isinstance(messages, list):
        continue
    with_messages += 1

    if any(
        isinstance(message, dict)
        and isinstance(message.get("content"), str)
        and "[compacted:" in message.get("content")
        for message in messages
    ):
        parseable_compacted += 1

print(
    f"files={len(files)} rawCompacted={raw_compacted} "
    f"parseable={parseable} parseFailures={parse_failures} "
    f"withMessages={with_messages} "
    f"parseableCompacted={parseable_compacted}"
)
'@ | python -
```

Current hygiene rescan output:

```text
files=12571 rawCompacted=740 parseable=10957 parseFailures=1529 withMessages=10769 parseableCompacted=702
```

Representative artifact paths included:

- `local/TalosTestOUTPUT/gpt-oss-20b-stress-audit-20260530-2253/prompt-debug/prompt-debug-20260530-231555.provider-body.json`
- `local/TalosTestOUTPUT/gpt-oss-20b-stress-audit-20260530-2253/prompt-debug/prompt-debug-20260530-231013.provider-body.json`
- `local/TalosTestOUTPUT/gpt-oss-20b-stress-audit-20260530-2253/prompt-debug/prompt-debug-20260530-230142.provider-body.json`

The artifact scan groups model names by the value present in the artifact. Some
older artifacts use folder-style `gpt-oss-20b`, while others use runtime-style
`gpt-oss:20b`.

| Artifact model label | Parseable compacted provider bodies | Compacted stubs | Same-turn re-read proxy count | Max prompt chars | Max approx prompt tokens |
| --- | ---: | ---: | ---: | ---: | ---: |
| `gpt-oss-20b` | 666 | 2,251 | 548 | 28,675 | 7,169 |
| `gpt-oss:20b` | 29 | 135 | 2 | 17,178 | 4,295 |
| `qwen2.5-coder-14b` | 5 | 14 | 2 | 21,677 | 5,420 |

The same-turn re-read proxy means a parsed provider body contained both a
compacted `talos.read_file` result and multiple same-path `talos.read_file`
calls in the same message history. It is evidence that re-read patterns coexist
with compaction in real artifacts. It is not proof that compaction caused the
re-read or harmed the final answer.

The approximate token count uses the same rough character-based interpretation
used elsewhere in this codebase for budget reasoning. It is not exact tokenizer
output.

Config evidence shows the default managed context and fallback LLM context
budget are both `8192`. The largest parsed compacted artifact in this sample is
below that budget by the rough estimate, but close enough that compaction can be
material in long tool turns.

## Answer Quality Finding

Phase 1 answer: no, the available evidence does not prove a measurable answer
quality regression from current in-turn compaction.

The evidence does prove:

- compaction appears in real local audit artifacts,
- compaction can coexist with same-turn re-read proxy signals,
- the current stub is information-poor because it carries no gist,
- retained `LoopState.readFileBodiesThisTurn` protects verification state but
  does not recover elided content into model prompts.

No inspected artifact supplied a deterministic expected-versus-final-workspace
comparison proving an inconsistent edit caused by compaction. That is the
boundary of this Phase 1 conclusion.

## Recommendation

Close T832 Phase 1 after review. Do not treat current compaction as a release
blocker based on this evidence.

A later Phase 2 is reasonable if the owner accepts the tradeoff. The smallest
candidate is a deterministic gist-in-stub improvement that derives its gist only
from already-sanitized prompt message content. That would improve continuity
without touching raw protected file bodies.

Do not move to token-pressure-triggered compaction or prompt rehydration without
new evidence from current multi-iteration traces.

## Deferred

T832 Phase 1 does not authorize production extraction or behavior changes.

Deferred items:

- gist-in-stub production change,
- token-pressure triggering,
- prompt rehydration,
- session-level `core.context` compaction changes,
- any protected-content policy change.
