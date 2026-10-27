# Model profiles

Talos model profiles pin a GGUF artifact, its runtime behavior, and the evidence used to classify it. A profile being selectable does not mean it is suitable for every machine. Run `talos doctor --start` after configuration to verify the selected model and engine on your hardware.

Accepted beta stability profiles are qwen2.5-coder-14b and gpt-oss-20b. The accepted beta profiles are large local-model lanes. Qwen3-8B, Granite 4.1 3B, Qwen3 4B Instruct 2507, Granite 4.1 8B, Qwen3.5 9B, Qwen3.6-VibeForged, and DeepSeek-Coder-V2-Lite profiles are experimental selectable profiles.

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
| `qwen3-4b` | experimental selectable | native/default, non-thinking | Exact local GGUF path required by `talos setup models` |
| `granite-4.1-8b` | experimental selectable | native/default | Exact local GGUF path required by `talos setup models` |
| `qwen3.5-9b` | experimental selectable | native/default, reasoning off | Exact local GGUF path required by `talos setup models` |
| `qwen36vf-q4km` | experimental selectable | native/default | `talos setup models` |
| `qwen36vf-q6k` | experimental selectable | native/default | `talos setup models` |
| `deepseek-v2lite-q4km` | experimental selectable | text/tool-prompt | `talos setup models` |

The accepted profiles are large local-model lanes. They are not weak-PC defaults. CPU-only startup and tool turns can be slow without substantial system memory or GPU acceleration.

## Session selection and local availability

In Unreleased current source, `/models` lists selectable profiles with local availability and catalog disposition. The original configured model and session imports are also shown. `/models use <alias>` and `/set model <alias>` select an available local managed model. `/models add <alias> "<absolute GGUF path>"` registers another file for the current session without starting it. Full instructions are provided in [model setup](../getting-started/model-setup.md#select-a-model-in-the-current-session).

Listing an exact filename establishes only a local candidate, not artifact verification, readiness or qualification. Built-in aliases require the pinned artifact bytes. Custom imports remain unverified. No model is downloaded by these commands. Selection validates local launch facts before stopping the owned backend, then requires target startup and served-identity checks. Talos remains open, and connect-only servers are not restarted.

Imports and selections apply to the current session. Saved defaults, conversation history, global privacy and native-tool policy, sampling and embedding configuration are preserved. Model-specific defaults are supplied by the selected catalog profile. Session history remains restorable when a saved model is unavailable, with a warning rather than a successful model-selection claim.

## Accepted beta profiles

The example paths must be replaced with existing local paths. On Windows, the server path must name `llama-server.exe`. Without `--write`, setup only prints help. If a configuration already exists, replacement requires an explicit `--force`, and a backup is created before replacement. Talos must be restarted after a configuration write.

### qwen2.5-coder-14b

```bash
talos setup models --profile qwen2.5-coder-14b --server-path /path/to/llama-server --write
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
talos setup models --profile gpt-oss-20b --server-path /path/to/llama-server --model-path /path/to/gpt-oss-20b-mxfp4.gguf --write
talos doctor --start
```

- Repository: `ggml-org/gpt-oss-20b-GGUF`
- Artifact: `gpt-oss-20b-mxfp4.gguf`
- License: Apache-2.0
- Evidence: `legacy:two-model-large-scale-live-audit-0.10.8`
- Hardware guidance: 24 GB RAM minimum, 32 GB or more for CPU-only use.

This profile requires the exact local GGUF path. It is a large model and is not the low-resource lane.

In Unreleased current source, system instructions are combined in order into one leading system message when the artifact, engine and served identity qualify this profile. This prevents later system instructions from being omitted by its embedded Harmony template. Conversation references, workspace content and tool results retain their original roles. This delivery behavior does not establish answer correctness.

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

### qwen3-4b

Qwen3 4B Instruct 2507 is an experimental selectable profile. It is not an accepted beta profile, a default, or a weak-hardware claim. It completed more scenario definitions and trial outcomes than the other current-protocol experimental profiles, but required-tool accuracy was 47 of 54, or 87.04 percent. Accepted beta requires 90 percent.

```powershell
talos setup models --profile qwen3-4b --server-path C:/path/to/llama-server.exe --model-path D:/models/qwen3-4b-instruct-2507-Q4_K_M.gguf --write
talos doctor --start
```

- Source repository: `Qwen/Qwen3-4B-Instruct-2507`
- Source revision: `cdbee75f17c01a7cc42f958dc650907174af0554`
- Artifact: `qwen3-4b-instruct-2507-Q4_K_M.gguf`
- SHA-256: `1e5b123fcb51bcffa922c21f00e47a8ee3450c455cdacb0aafba0573827b3f73`
- Size: 2,497,279,008 bytes
- License: Apache-2.0
- Provenance: controlled local conversion from the complete official source snapshot with pinned llama.cpp b9918 conversion and quantization tools. Two independent outputs were byte-identical.
- Support level: experimental selectable
- Runtime: native/default tool mode, 8,192-token context, non-thinking through llama.cpp `--reasoning off`
- Qualified lane: pinned llama.cpp b9918, Windows x64 CUDA 13.3 reference host with 16 GiB-class VRAM
- Evidence: `local/model-qualification/qwen3-4b-full-p5-f6a2edf6-a8-b9918-cuda13-20260714`
- Result: 20 of 24 scenario definitions and 58 of 72 trials passed, with 114 of 129 requirements, 47 of 54 required-tool decisions, and zero Talos trust failures

Talos verifies the local file size and SHA-256 before writing this profile. The final GGUF is a controlled local conversion, not a published file in the source repository or a Talos release asset. It is not available as a wizard download. The packet does not establish CPU-only, Linux, WSL, Vulkan, or weak-PC support, and it does not establish compatibility with other Qwen3 4B GGUF files.

### granite-4.1-8b

Granite 4.1 8B is an experimental selectable profile. It is not an accepted beta profile or a default. It completed 19 of 24 scenario definitions. The accepted beta threshold is 20. Its context-pressure result was one of three, below the required two of three.

```powershell
talos setup models --profile granite-4.1-8b --server-path C:/path/to/llama-server.exe --model-path D:/models/granite-4.1-8b-Q4_K_M.gguf --write
talos doctor --start
```

- Repository: `ibm-granite/granite-4.1-8b-GGUF`
- Revision: `865b82c2e7970d82e3731278c88c57ae7138359c`
- Artifact: `granite-4.1-8b-Q4_K_M.gguf`
- SHA-256: `ed902ac9eb6adce5a90c6a08c8ea201b50e23fdc5976d1cd0362006afac5309e`
- Size: 5,347,914,400 bytes
- License: Apache-2.0
- Support level: experimental selectable
- Setup: Exact local GGUF path required
- Runtime: native/default tool mode, 8,192-token context, no separate thinking mode
- Qualified lane: pinned llama.cpp b9918, Windows x64 CUDA 13.3 reference host with 16 GiB-class VRAM
- Evidence: `local/model-qualification/granite-4.1-8b-full-p5-d93e263f-a1-b9918-cuda13-20260714`
- Result: 19 of 24 scenario definitions and 57 of 72 trials passed, with 110 of 129 requirements, 51 of 54 required-tool decisions, and zero Talos trust failures

Talos verifies the local file size and SHA-256 before writing this profile. It is not available as a wizard download. The evidence applies only to the exact GGUF and pinned engine lane above. It does not establish CPU-only, Linux, WSL, Vulkan, or weak-PC support, and it does not establish compatibility with other Granite 4.1 8B GGUF files.

### qwen3.5-9b

Qwen3.5 9B is an experimental selectable profile. It completed 19 of 24 scenario definitions. The accepted beta threshold is 20. Every trust gate passed in the qualification campaign.

```powershell
talos setup models --profile qwen3.5-9b --server-path C:/path/to/llama-server.exe --model-path D:/models/Qwen3.5-9B-Q4_K_M.gguf --write
talos doctor --start
```

- Source repository: `Qwen/Qwen3.5-9B`
- Source revision: `c202236235762e1c871ad0ccb60c8ee5ba337b9a`
- Artifact: `Qwen3.5-9B-Q4_K_M.gguf`
- SHA-256: `1fddc46c7ebc2180481b59c2111b83ebc0e397c183d3746a6548d07d30f63a9f`
- Size: 5,780,090,592 bytes
- License: Apache-2.0
- Provenance: controlled local text-only conversion from the complete official source snapshot with pinned llama.cpp b9918 conversion and quantization tools. Two independent BF16 and Q4_K_M outputs were byte-identical.
- Source manifest SHA-256: `aec84316f65d58e467f629b24caa2bd9f573f0c247b355a7e3b06408e2b4afac`
- Conversion environment SHA-256: `2d31c79e89a3ef3bdcd59deb1a94f489ff0edf0863471b480d25aff556720a53`
- Conversion result SHA-256: `576fc386802e77eee25fa0f4b850817cff8ff805748f4599193e339f7ff76cd2`
- Support level: experimental selectable
- Setup: Exact local GGUF path required
- Runtime: native/default tool mode, 8,192-token context, reasoning disabled through llama.cpp `--reasoning off`
- Request layout: `SINGLE_LEADING_SYSTEM`. This evidence-gated layout merges only Talos system frames before the provider request. User, workspace, assistant, and tool messages keep their roles.
- Qualified lane: pinned llama.cpp b9918, Windows x64 CUDA 13.3 reference host with 16 GiB-class VRAM and a 32 GiB system RAM campaign floor
- Evidence: `local/model-qualification/qwen3.5-9b-full-p5-aa4bb8dc-fq2-b9918-cuda13-20260715`
- Result: 19 of 24 scenario definitions and 56 of 72 trial outcomes passed, with 117 of 129 requirements, 52 of 54 required-tool decisions, 15 of 15 no-tool decisions, 135 of 135 native calls, and every trust gate passed

Talos verifies the local file size and SHA-256 before writing this profile. The final GGUF is a controlled local conversion. The source repository does not publish this GGUF, and Talos does not distribute it. It is not available as a wizard download. The evidence applies only to the exact file and pinned engine lane above. It does not establish CPU-only, Linux, WSL, Vulkan, low-VRAM, or weak-PC support. It also does not establish compatibility with other Qwen3.5 GGUF files.

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
