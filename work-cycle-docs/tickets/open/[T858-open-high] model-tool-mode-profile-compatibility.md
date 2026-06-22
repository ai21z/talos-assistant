# T858 Model Tool-Mode Profile Compatibility

Status: open
Priority: high
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`
Opened from: 2026-06-22 managed-model probe review

## Problem

Talos currently treats `tools.native_calling` as a global setup option, but
model compatibility is not uniform.

Evidence from the 2026-06-22 scn-06 probe:

- Qwen3.6-VibeForged Q4 and Q6 produced executable tool calls in native/default
  mode.
- DeepSeek-Coder-V2-Lite Q4 produced zero executable tool calls in
  native/default mode and Talos honestly blocked with no file changes.
- The same DeepSeek Q4 GGUF produced two executable `write_file` calls when
  configured with `tools.native_calling: false`.

Precise wording: DeepSeek is Talos-usable in text/tool-prompt mode with
`tools.native_calling: false`; native/default produced zero executable tool
calls. The chat-template/native-tool mismatch diagnosis is a strong inference,
not hard proof, because the successful `native_calling:false` provider body
still carried OpenAI-compatible `tools` and `tool_choice: "required"` while the
prompt added JSON-code-block tool instructions.

If Talos hides this behind a global flag, users discover model/tool compatibility
by failed turns instead of setup truth.

## Evidence

- Native/default DeepSeek artifact:
  `local/beta-pre-release-test-scenarios/eval-deepseek-q4/scn-06-create-empty-workspace`
  recorded `Tool calls: 0`, `BLOCKED_BY_POLICY`, and no file changes.
- DeepSeek with `tools.native_calling: false` artifact:
  `local/beta-pre-release-test-scenarios/eval-deepseek-q4-xml/scn-06-create-empty-workspace`
  recorded `Tool calls: 2`, `COMPLETED_UNVERIFIED`, and created `hello.py` and
  `README.md`.
- The successful DeepSeek provider body still contained `tools` and
  `tool_choice: "required"`, so do not describe this as pure native-tool removal.
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java` has built-in profiles
  for `qwen2.5-coder-14b` and `gpt-oss-20b`, but the profile record does not
  encode tool-mode compatibility.
- `src/main/java/dev/talos/core/Config.java` defaults
  `tools.native_calling` globally.

## Goal

Make model tool-mode compatibility explicit in setup profiles, docs, and
diagnostics so Talos does not imply every managed GGUF works with native/default
tool calling.

## Scope

- Extend managed model profile metadata to include the recommended tool mode:
  native/default or text/tool-prompt mode.
- Add or update profiles for candidate beta models only after evidence exists:
  - Qwen3.6-VibeForged Q4/Q6: native/default passes the scn-06 tool-call gate.
  - DeepSeek-Coder-V2-Lite Q4: requires `tools.native_calling: false` based on
    current evidence.
- Ensure generated config from `talos setup models` writes the correct
  `tools.native_calling` value for known profiles.
- Show the tool mode in setup help and diagnostics.
- Document that compatibility evidence is per-model and per-quant, not a family
  guarantee.

## Non-Goals

- Do not claim DeepSeek is native-tool capable.
- Do not claim the root cause is proven beyond the evidence.
- Do not change tool-call parsing semantics.
- Do not weaken native tool support for models that already work.
- Do not touch `site/`.

## Acceptance Criteria

- Known profiles can specify whether Talos should use native/default tool calls
  or text/tool-prompt mode.
- `talos setup models --profile <profile> --write` emits the expected
  `tools.native_calling` value.
- Help/docs state the tool-mode recommendation for each tested profile.
- A deterministic test covers at least:
  - native/default profile writes `tools.native_calling: true`;
  - DeepSeek-style text/tool-prompt profile writes `tools.native_calling: false`;
  - user-owned custom GGUF profiles preserve the current default unless the user
    explicitly configures otherwise.
- The docs use the bounded phrase:
  "DeepSeek is Talos-usable in text/tool-prompt mode with
  `tools.native_calling:false`; native/default produced zero executable tool
  calls."

## Suggested Tests

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.SetupCmdTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.docs.TrustClaimsHonestyTest" --no-daemon
.\gradlew.bat check --no-daemon
.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon
git diff --check -- . ':!site'
```

For live acceptance before recommending a profile, run scn-06 plus a small edit
and read-fix pass on the installed build.

## Architecture Metadata

- Capability ownership: model setup/profile metadata and tool-call compatibility
  disclosure.
- Operation type: setup/config generation and docs truth.
- Risk: high product-truth risk; medium runtime risk if generated config changes.
- Approval behavior: not applicable.
- Protected path behavior: not applicable.
- Checkpoint behavior: not applicable.
- Evidence obligation: deterministic setup tests plus live model profile probe
  before marking any profile "tested".
- Verification profile: generated YAML, help output, diagnostics, and docs.
- Allowed refactor scope: setup profile model only; no broad engine rewrite.

