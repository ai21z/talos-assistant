# Model profiles

Talos model profiles pin a GGUF artifact, its runtime behavior, and the evidence used to classify it. A profile being selectable does not mean it is suitable for every machine. Run `talos doctor --start` after configuration to verify the selected model and engine on your hardware.

Accepted beta stability profiles are qwen2.5-coder-14b and gpt-oss-20b. The accepted beta profiles are large local-model lanes. Qwen3-8B, Granite 4.1 3B, Qwen3.6-VibeForged, and DeepSeek-Coder-V2-Lite profiles are experimental selectable profiles.

## Support levels

- **accepted beta stability** means the profile has the existing beta evidence needed for the public beta lane.
- **experimental selectable** means Talos can configure the profile, but its evidence is narrower. It is not a beta default.
- Custom GGUF files remain unverified unless they are added to the catalog through the qualification process.

Passing admission is not a support claim. Admission only shows that the exact model and runtime completed the smaller compatibility gate.

Model quality and hardware support are separate decisions. A selectable profile can have strong model evidence and still lack a claim for CPU-only use, Linux, Vulkan, or a smaller GPU. Hardware guidance is limited to measured lanes.

Legacy evidence remains legacy until a current protocol packet replaces it. Existing legacy profiles keep their explicit evidence label rather than inheriting a newer model's qualification result.

See [Model profile lifecycle](../architecture/model-profile-lifecycle.md) for how evidence becomes a catalog disposition. Maintainers evaluating a candidate use [Onboard new models](../development/onboard-new-models.md).

## Available profiles

| Profile | Support level | Tool mode | Setup |
| --- | --- | --- | --- |
| `qwen2.5-coder-14b` | accepted beta stability | native/default | Wizard download or `talos setup models` |
| `gpt-oss-20b` | accepted beta stability | native/default | Exact local GGUF path required by `talos setup models` |
| `qwen3-8b` | experimental selectable | native/default, non-thinking | Exact local GGUF path required by `talos setup models` |
| `granite-4.1-3b` | experimental selectable | native/default | Exact local GGUF path required by `talos setup models` |
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

### qwen3-8b

Qwen3-8B is an experimental selectable profile. It is not an accepted beta profile or a weak-hardware claim.

```powershell
talos setup models --profile qwen3-8b --server-path C:/path/to/llama-server.exe --model-path D:/models/Qwen3-8B-Q4_K_M.gguf --write
talos doctor --start
```

- Repository: `Qwen/Qwen3-8B-GGUF`
- Revision: `7c41481f57cb95916b40956ab2f0b139b296d974`
- Artifact: `Qwen3-8B-Q4_K_M.gguf`
- SHA-256: `d98cdcbd03e17ce47681435b5150e34c1417f50b5c0019dd560e4882c5745785`
- License: Apache-2.0
- Support level: experimental selectable
- Runtime: native/default tool mode, 8,192-token context, non-thinking through llama.cpp `--reasoning off`
- Qualified lane: pinned llama.cpp b9918, Windows x64 CUDA 13.3, 16 GiB-class VRAM and 32 GiB system RAM
- Evidence: `local/model-qualification/qwen3-8b-full-p5a15-3271f5f0-b9918-cuda13-20260712`
- Result: 18 of 24 scenario definitions and 57 of 72 trials passed, with zero Talos trust failures

Talos verifies the local file size and SHA-256 before writing this profile. The packet does not establish CPU-only, 8 GiB VRAM, Linux, or weak-PC support.

The evidence value is an immutable local packet identifier. Raw qualification packets are retained as local review evidence and are not bundled in Talos release artifacts.

### granite-4.1-3b

Granite 4.1 3B is an experimental selectable profile. It is not an accepted beta profile or a weak-hardware claim. It is fast and memory-efficient on the tested reference lane, but it completed fewer complex and multi-file tasks than the larger current-protocol candidates.

```powershell
talos setup models --profile granite-4.1-3b --server-path C:/path/to/llama-server.exe --model-path D:/models/granite-4.1-3b-Q4_K_M.gguf --write
talos doctor --start
```

- Repository: `ibm-granite/granite-4.1-3b-GGUF`
- Revision: `ab4701481089b58a082ef63cc1cee738887293ff`
- Artifact: `granite-4.1-3b-Q4_K_M.gguf`
- SHA-256: `662b0626cd58f443baea23559b469df6576a81d349649c59413b36a9fb32eb29`
- License: Apache-2.0
- Support level: experimental selectable
- Runtime: native/default tool mode, 8,192-token context, no separate thinking mode
- Qualified lane: pinned llama.cpp b9918, Windows x64 CUDA 13.3, 16 GiB-class VRAM and 32 GiB system RAM
- Evidence: `local/model-qualification/granite-4.1-3b-full-p5-3b691d80-a10-b9918-cuda13-20260713`
- Result: 16 of 24 scenario definitions and 47 of 72 trials passed, with zero Talos trust failures

Talos verifies the local file size and SHA-256 before writing this profile. The packet does not establish CPU-only, Linux, WSL, Vulkan, or weak-PC support. Granite is not offered as a wizard download.

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
