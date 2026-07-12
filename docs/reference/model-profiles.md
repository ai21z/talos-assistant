# Model profiles

Talos model profiles pin a GGUF artifact, its runtime behavior, and the evidence used to classify it. A profile being selectable does not mean it is suitable for every machine. Run `talos doctor --start` after configuration to verify the selected model and engine on your hardware.

Accepted beta stability profiles are qwen2.5-coder-14b and gpt-oss-20b. The accepted beta profiles are large local-model lanes. Qwen3.6-VibeForged and DeepSeek-Coder-V2-Lite profiles are experimental selectable profiles.

## Support levels

- **accepted beta stability** means the profile has the existing beta evidence needed for the public beta lane.
- **experimental selectable** means Talos can configure the profile, but its evidence is narrower. It is not a beta default.
- Custom GGUF files remain unverified unless they are added to the catalog through the qualification process.

## Available profiles

| Profile | Support level | Tool mode | Setup |
| --- | --- | --- | --- |
| `qwen2.5-coder-14b` | accepted beta stability | native/default | Wizard download or `talos setup models` |
| `gpt-oss-20b` | accepted beta stability | native/default | Exact local GGUF path required by `talos setup models` |
| `qwen36vf-q4km` | experimental selectable | native/default | `talos setup models` |
| `qwen36vf-q6k` | experimental selectable | native/default | `talos setup models` |
| `deepseek-v2lite-q4km` | experimental selectable | text/tool-prompt | `talos setup models` |

The accepted profiles are large local-model lanes. They are not weak-PC defaults. CPU-only startup and tool turns can be slow without substantial system memory or GPU acceleration.

## Accepted beta profiles

### qwen2.5-coder-14b

```bash
talos setup models --profile qwen2.5-coder-14b
talos doctor --start
```

- Repository: `Qwen/Qwen2.5-Coder-14B-Instruct-GGUF`
- Artifact: `qwen2.5-coder-14b-instruct-q4_k_m.gguf`
- License: Apache-2.0
- Evidence: `legacy:two-model-large-scale-live-audit-0.10.8`
- Hardware guidance: 16 GB RAM minimum, 24 GB or more for CPU-only use.

Use this profile for the established coding lane. It is not a low-resource profile.

### gpt-oss-20b

```bash
talos setup models --profile gpt-oss-20b --model-path /path/to/gpt-oss-20b-mxfp4.gguf
talos doctor --start
```

- Repository: `ggml-org/gpt-oss-20b-GGUF`
- Artifact: `gpt-oss-20b-mxfp4.gguf`
- License: Apache-2.0
- Evidence: `legacy:two-model-large-scale-live-audit-0.10.8`
- Hardware guidance: 24 GB RAM minimum, 32 GB or more for CPU-only use.

This profile requires the exact local GGUF path. It is a large model and is not the low-resource lane.

gpt-oss-20b requires a concrete local GGUF model path.

## Experimental profiles

### qwen36vf-q4km

- Repository: `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF`
- Artifact: `Qwen3.6-14B-A3B-VibeForged-v2-Q4_K_M.gguf`
- Support level: experimental selectable
- Tool mode: native/default
- Evidence: `legacy:managed-profile-evidence-0.10.7`
- Limit: third-party derivative screened on Windows lanes. It is not accepted beta evidence.

### qwen36vf-q6k

- Repository: `tvall43/Qwen3.6-14B-A3B-VibeForged-v2-GGUF`
- Artifact: `Qwen3.6-14B-A3B-VibeForged-v2-Q6_K.gguf`
- Support level: experimental selectable
- Tool mode: native/default
- Evidence: `legacy:managed-profile-evidence-0.10.7`
- Limit: third-party derivative screened on Windows lanes. It is not accepted beta evidence.

### deepseek-v2lite-q4km

DeepSeek-Coder-V2-Lite uses text/tool-prompt mode.

- Repository: `bartowski/DeepSeek-Coder-V2-Lite-Instruct-GGUF`
- Artifact: `DeepSeek-Coder-V2-Lite-Instruct-Q4_K_M.gguf`
- Support level: experimental selectable
- Tool mode: text/tool-prompt
- Evidence: `legacy:managed-profile-evidence-0.10.7`
- Limit: native/default produced no executable tool calls in the recorded evidence. Do not describe this as a native tool-calling profile.

## Evidence and custom models

The catalog pins repository revisions, artifact hashes and sizes, licenses, runtime defaults, compatible engine lanes, and qualification evidence. `talos setup models`, the setup wizard, `/models`, and status output read from that catalog.

A custom GGUF remains custom and unverified. Its successful `doctor --start` result proves only that the model starts and answers the smoke prompt on that machine. It does not promote the model or prove edit and approval behavior.

Save the talos doctor --start output as evidence before calling a profile verified on a new machine.
