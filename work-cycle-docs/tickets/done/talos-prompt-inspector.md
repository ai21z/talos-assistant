# [done] Ticket: On-Demand Prompt Inspector

Date: 2026-04-23
Branch context: ticket/talos-prompt-inspector
Status: done

## Problem

We currently infer system-prompt problems indirectly by watching model behavior.
That is slow, ambiguous, and incomplete.

Questions we cannot answer quickly today:
- what exact system prompt was assembled for this turn?
- which prompt sections were included?
- was the native or text tools preamble selected?
- how many history turns were included?
- which tools were exposed to the model?
- how large was the final assembled prompt?

Without direct prompt inspection, debugging prompt bias becomes guesswork.

## Desired Capability

Provide an on-demand way to inspect the exact prompt Talos would send or did send
for a given turn.

The tool should help answer:
- what prompt was generated?
- why was it generated?
- which sections contributed to it?

## Recommendation

Do not print the full prompt after every user turn by default.

Reasons:
- too noisy for normal CLI use
- pollutes transcripts
- makes ordinary usage unpleasant
- may expose internal scaffolding when not needed

Instead, add an explicit prompt inspector.

## Proposed UX

### CLI interactive

- `/prompt`
  - show the prompt that would be used for the next turn, based on current mode,
    config, workspace, and history state

- `/prompt last`
  - show the exact prompt used for the most recent turn, if available

- `/prompt save`
  - save the rendered prompt to a local file for review

### Non-interactive

- `talos prompt-render --mode auto --input "..." --workspace ...`

This enables deterministic inspection outside the chat loop.

## Minimum Useful Output

The inspector should include:

- selected mode
- model name
- native tool calling on/off
- workspace path
- history count included
- tools exposed
- section list included
- prompt size in chars / estimated tokens
- final assembled prompt text

## Nice-To-Have Output

- a structured header summarizing prompt inputs
- section boundaries in the rendered output
- a diff between:
  - auto vs ask vs rag vs unified
  - native tools preamble vs text fallback preamble
- save to `local/` or `build/reports/talos/prompts/`

## Implementation Approaches

### Option A: expose prompt rendering through existing builders

Use `SystemPromptBuilder` and mode-level message assembly code to render the
same prompt path the runtime uses.

Pros:
- closest to production behavior
- low conceptual duplication

Cons:
- must be careful not to create a second prompt assembly path

### Option B: capture prompts during real turns

When a turn runs, persist the exact assembled prompt and prompt metadata for
the last turn.

Pros:
- perfect fidelity for `/prompt last`

Cons:
- only helps after execution
- needs storage/lifecycle decisions

## Recommendation

Implement both in stages:

1. Stage 1:
   - on-demand renderer for "next turn"
2. Stage 2:
   - record exact prompt metadata for "last turn"

That gives immediate utility without delaying on persistence decisions.

## Scope Boundaries

Prompt inspection is a diagnosis/debugging tool.
It is not the fix for the mutation-drift bug by itself.

It will help identify:
- write-biased wording
- oversized prompts
- incorrect section inclusion
- unexpected tool exposure

But runtime safety still requires explicit guards elsewhere.

## Risks

- accidental divergence between rendered prompt and actual runtime prompt
- too much verbosity in interactive CLI
- exposing internal prompt scaffolding in normal sessions if enabled by default

## Test Plan

### Unit

- prompt renderer includes expected unified sections with no history
- prompt renderer includes conversation section when history exists
- prompt renderer reports correct native/text tool preamble choice

### CLI behavior

- `/prompt` does not execute a model turn
- `/prompt save` writes prompt artifact locally
- `prompt-render` works without entering REPL

## Acceptance Criteria

- user can inspect the exact or near-exact generated prompt on demand
- normal CLI usage remains quiet by default
- prompt metadata explains why a given prompt shape was produced
- tool selection and section selection are visible without reading source

## Completion Notes

- Added deterministic prompt rendering through `talos prompt-render`.
- Added interactive `/prompt`, `/prompt last`, and `/prompt save`.
- Captured prompt metadata before model calls in ask, rag, and unified modes.
- Verified normal usage stays quiet unless prompt inspection is explicitly requested.
- Installed Talos verification passed in `local/playground/horror-synth-site`.

## Verification

- `./gradlew.bat test --tests "dev.talos.cli.prompt.PromptInspectorTest" --tests "dev.talos.cli.repl.slash.PromptCommandTest"`
- `./gradlew.bat test --tests "dev.talos.cli.repl.TalosBootstrapTest" --tests "dev.talos.cli.repl.SlashCommandCompleterTest" --tests "dev.talos.cli.repl.slash.SimpleCommandsTest"`
- `./gradlew.bat test`
- `./gradlew.bat e2eTest`
- `./gradlew.bat check`
- Installed CLI prompt-render and REPL prompt-inspector transcript captured in `local/manual-testing/test-output`.
