# T109 - Provider Tool Choice From Action Obligations

Status: Done
Priority: High
Branch: v0.9.0-beta-dev
Source: T106 focused managed llama.cpp audit

## Evidence Summary

T106 proved that llama.cpp provider-body JSON included tools but no provider
tool-choice control:

- Exact write turn: tools present, `tool_choice=null`.
- Static web/BMI create turn: tools present, `tool_choice=null`.
- Inspection/evidence turn: read-only tools present, `tool_choice=null`.

Prompt debug displayed `Tool choice: AUTO` even when the runtime action
obligation was `MUTATING_TOOL_REQUIRED` or `INSPECT_REQUIRED`.

## Goal

Map Talos action/evidence obligations to provider-neutral request controls so
capable backends receive required tool choice on turns where a tool call is a
runtime obligation.

## Scope

- Set `ChatRequestControls.toolChoice=REQUIRED` for mutating-tool-required
  turns when backend capabilities support required tool choice.
- Set required tool choice for workspace-inspection/evidence-required turns when
  read-only tools are visible and provider capabilities support it.
- Keep small-talk/direct-answer turns at AUTO/NONE with no tools.
- Preserve Ollama compatibility by not sending unsupported provider fields.
- Keep deterministic failure gates; required tool choice is an enforcement aid,
  not the only control.

## Acceptance Criteria

- Tests assert compat/llama.cpp provider body includes `tool_choice:"required"`
  for mutating obligation turns.
- Tests assert read-only evidence-required turns include required tool choice
  when tools are visible.
- Tests assert direct-answer turns do not force tools.
- Existing failure-dominant behavior remains intact when the model still returns
  no valid tool call.
- Prompt debug clearly records the selected tool choice.

## Suggested Verification

```powershell
./gradlew.bat test --tests "*ToolCall*" --tests "*PromptDebug*" --tests "*Compat*" --no-daemon
./gradlew.bat test e2eTest --no-daemon
```
