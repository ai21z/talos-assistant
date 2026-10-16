# Model setup

Talos works best with a local `llama.cpp` server path and an explicit GGUF model path. The setup wizard handles the first Ubuntu/WSL x64 CPU lane.

There are two setup levels:

- Guided Ubuntu/WSL setup, where `talos setup wizard` can install the pinned CPU engine lane and help configure an accepted model.
- Direct setup, where you provide the `llama-server` path and GGUF model path yourself.

```bash
talos setup wizard
talos doctor --start
```

On Windows, existing server and GGUF paths must be substituted in this configuration command:

```powershell
talos setup models --profile qwen2.5-coder-14b --server-path C:/path/to/llama-server.exe --model-path D:/models/qwen2.5-coder-14b-instruct-q4_k_m.gguf --write
talos doctor --start
```

`talos setup models` without `--write` prints help only. Existing configuration replacement requires an explicit `--force`, with a backup created first. Talos must be restarted after a configuration write. Download guidance is provided in [Windows setup](windows-setup.md).

## Select a model in the current session

In Unreleased current source, an already configured managed llama.cpp session can be switched to another local model without closing Talos:

```text
/models
/models --verbose
/models use <alias>
/set model <alias>
```

The two selection commands are equivalent for managed aliases. `/models` lists selectable catalog profiles, the original configured model and aliases registered in the current session. A missing file is listed as unavailable locally. Exact catalog filenames are sought only in configured and standard Hugging Face cache locations and the wizard download layout, `~/.talos/models/gguf/<alias>/<artifactFile>`. Other local files can be registered explicitly:

```text
/models add my-agent "D:/models/agent.gguf"
/models use my-agent
```

On Linux, an absolute path such as `/home/user/models/agent.gguf` is required instead. Registration checks the local GGUF header and does not start the model. Aliases are case-insensitive and contain at most 54 ASCII letters, digits, dots, underscores or hyphens, beginning with a letter or digit. Consecutive dots are rejected. An alias cannot be rebound to another file. Built-in aliases remain bound to their exact pinned artifact, even when a different file has the expected name.

Selection checks the local model, server binary and launch settings before the current owned backend is stopped. The owned backend is restarted for the target, while Talos remains open. A switch is reported as successful only after target startup and served-identity checks succeed. `/models` and `/set model` do not download models. Connect-only servers are not restarted, and local GGUF registration requires managed mode. Other configured backends retain `/set model <backend/model>`.

`/models use <alias>` is the primary managed selection command. `/set model <alias>` is a compatibility form of the same operation, not a different recovery method. `/model` remains an alias for `/models`.

An occupied managed port is rejected before a replacement server is launched. An unrelated server is never adopted or stopped by selection. Its owning session must be closed, or a different managed port configured. After the cause is resolved, `/models use <alias>` may be used again. Ordinary managed startup requires an inspectable process and a matching listener on `http://127.0.0.1`, with `localhost` normalized to that address. Startup and request endpoints must use the same port. Connect-only endpoint handling is unchanged.

The launched process identity and listener ownership are sampled around readiness and served-model inspection. A cached profile does not authorize a dead or replaced owned process. These checks do not claim immunity from a hostile operating system or a change racing after the final observation. If a switch has already stopped the previous owned backend, a later failure leaves requests blocked until another explicit successful selection is made. No automatic rollback or fallback model is started.

Imports and selections apply to this session. Saved defaults and conversation history are preserved, as are global privacy policy, `tools.native_calling`, sampling and embedding configuration. A profile supplies its model-specific context, template and launch arguments. Safe shared launch settings are retained, while conflicting source, alias, endpoint or unknown overrides prevent selection. The original configured model keeps its own model-specific settings when selected again.

A local candidate is not proof of readiness. Catalog disposition, artifact verification, current served identity and qualification remain separate facts. Registering a custom GGUF does not qualify it or establish native-tool support.

The compact list separates the current selection, local candidates and missing files. Filenames and per-entry details are shown by `/models --verbose`. `SELECTED` denotes configuration, while `ACTIVE` requires a matched served identity and an available chat backend. Neither label alone establishes qualification. Tab completion is available for local aliases after `/models use ` and `/set model `.

If validation fails before shutdown, the previous selection is preserved. If shutdown or target startup fails, no successful selection is reported and another explicit successful selection is required before model requests can resume. Saved session history is restored even when its saved model cannot be selected, and a warning identifies that failure. Imported aliases must be registered again in a later Talos run before they can be selected. Persistent default changes still use `talos setup models --write` outside the REPL.

## Where to get `llama-server`

The current pinned Ubuntu/WSL x64 CPU lane uses the llama.cpp `b9860` release, and the Windows lanes use `b9918`:

- Ubuntu release: https://github.com/ggml-org/llama.cpp/releases/tag/b9860
- Ubuntu x64 (CPU): `llama-b9860-bin-ubuntu-x64.tar.gz`
- Windows release: https://github.com/ggml-org/llama.cpp/releases/tag/b9918
- Windows x64 (CPU): `llama-b9918-bin-win-cpu-x64.zip`
- Windows x64 (CUDA 12.4, NVIDIA driver 551.61+): `llama-b9918-bin-win-cuda-12.4-x64.zip` plus `cudart-llama-bin-win-cuda-12.4-x64.zip`
- Windows x64 (CUDA 13.3, NVIDIA driver 580+): `llama-b9918-bin-win-cuda-13.3-x64.zip` plus `cudart-llama-bin-win-cuda-13.3-x64.zip`

Automatic Windows engine installation and `talos tune` are Unreleased current-source capabilities, not capabilities of the published 0.10.8 Windows artifact. In current source, the Windows lane is selected by the setup wizard from detected NVIDIA driver evidence, with CPU selected when no compatible driver is detected. GPU offload is verified by `talos tune` from the server log before a GPU-lane configuration is kept, not assumed from the binary name.

`talos doctor --start` checks managed server startup and an end-to-end smoke reply. Current-source rate reporting is conditional on measurable timing evidence, not guaranteed for every server response.

The first accepted model to try is Qwen. It is a large CPU model, not a low-resource default:

- Model page: https://huggingface.co/Qwen/Qwen2.5-Coder-14B-Instruct-GGUF
- GGUF file: `qwen2.5-coder-14b-instruct-q4_k_m.gguf`

Talos does not claim arbitrary latest upstream builds are verified. Use the pinned lane when you need reproducible setup evidence.

## Profiles

Accepted beta stability profiles are qwen2.5-coder-14b and gpt-oss-20b. Both are large local-model lanes. See [Model Profiles](../reference/model-profiles.md) for exact guidance.

After any setup change, run:

```bash
talos doctor --start
```

Do not treat a profile as ready until doctor completes the end-to-end model smoke. If doctor reports a slow smoke warning, the setup is functional but may be too slow for normal editing work on that machine.
