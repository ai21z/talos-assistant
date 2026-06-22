# T858 Model Tool-Mode Profile Compatibility Report

Status: implemented-awaiting-review
Branch: `v0.9.0-beta-dev`
Talos version: `0.10.5`

## Summary

T858 implements per-profile tool-mode metadata for managed `llama.cpp` setup.
The immediate driver is the 2026-06-22 model probe evidence:

- Qwen3.6-VibeForged Q4/Q6 produced executable tool calls in native/default
  mode on the scn-06 create-two-files probe.
- DeepSeek-Coder-V2-Lite Q4 produced zero executable tool calls in
  native/default mode.
- DeepSeek-Coder-V2-Lite Q4 produced executable tool calls when configured with
  `tools.native_calling:false`.

The implementation keeps the wording bounded. DeepSeek is described as
Talos-usable in text/tool-prompt mode with `tools.native_calling:false`;
native/default produced zero executable tool calls. The native-template cause
remains a strong inference, not a proven root cause.

## Code Changes

- `src/main/java/dev/talos/cli/launcher/SetupCmd.java`
  - Extends the managed model profile record with `nativeCalling`.
  - Emits `tools.native_calling: true` for native/default profiles.
  - Emits `tools.native_calling: false` for the DeepSeek-Coder-V2-Lite Q4
    profile.
  - Adds tested profile entries:
    - `qwen36vf-q4km`
    - `qwen36vf-q6k`
    - `deepseek-v2lite-q4km`
  - Keeps user-owned custom GGUF profiles on the existing default:
    `tools.native_calling: true`.

## Documentation Changes

- `docs/user/model-setup.md`
- `docs/setup-managed-models.md`

Both docs now list the tested profile sources, files, and tool modes. They also
pin the bounded DeepSeek wording and avoid calling DeepSeek native/default
compatible.

## Test Changes

- `src/test/java/dev/talos/cli/launcher/SetupCmdTest.java`
  - Pins new profiles in setup help.
  - Pins native/default YAML for Qwen profiles.
  - Pins text/tool-prompt YAML for DeepSeek.
  - Pins user-owned custom GGUF default behavior.
- `src/test/java/dev/talos/docs/TrustClaimsHonestyTest.java`
  - Requires bounded DeepSeek tool-mode wording in tracked model setup docs.
  - Guards against broad claims that DeepSeek is native/default compatible.

## Verification

Focused tests:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.SetupCmdTest" --no-daemon
.\gradlew.bat test --tests "dev.talos.docs.TrustClaimsHonestyTest" --no-daemon
```

Both passed after the red tests were observed failing.

## Review Notes

T858 remains open for external review. It should not be closed until the
reviewer accepts:

- the profile names;
- the DeepSeek bounded wording;
- the generated YAML shape;
- whether Qwen3.6-VibeForged Q4/Q6 and DeepSeek-Coder-V2-Lite Q4 should be
  treated as beta profile entries or provisional testing profiles.

