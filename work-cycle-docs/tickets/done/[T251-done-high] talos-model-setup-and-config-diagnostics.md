# Talos Model Setup And Config Diagnostics

Status: done
Severity: high
Area: setup / managed llama.cpp / config loading

## Problem

Fresh Talos installs can start with `llama_cpp/talos-agent` even when no
`llama.cpp` server or model source is configured. A malformed
`~/.talos/config.yaml` is silently ignored, so the user sees a model-not-running
failure instead of the real config problem.

The concrete local failure was a Windows path written inside double-quoted YAML:

```yaml
server_path: "C:\Users\arisz\Projects\LOQ\loqj-cli\local\engines\llama-cpp\b9010-vulkan-x64\llama-server.exe"
```

That config was not loaded; `talos status --verbose` reported classpath defaults
and an empty `llama_cpp.server_path`.

## Scope

- Report malformed user config in `Config.Report` and verbose status output.
- Add a model setup path for managed llama.cpp profiles that have been audited:
  `qwen2.5-coder-14b` and `gpt-oss-20b`.
- Generate YAML-safe Windows paths.
- Support Talos-owned Hugging Face cache storage under `~/.talos/models`.
- Document both setup choices: Talos-managed HF model source or user-owned GGUF
  path.
- Keep Ollama available as a legacy backend.

## Acceptance

- [x] `talos status --verbose` names a malformed user config instead of hiding it.
- [x] `talos setup models` shows tested profiles and setup commands.
- [x] `talos setup models --profile <profile> --server-path <llama-server.exe> --write`
  writes a valid managed llama.cpp config.
- [x] Generated config uses forward-slash paths or otherwise YAML-safe strings.
- [x] Managed llama.cpp launches with `HF_HOME` when `hf_cache_dir` is configured.
- [x] README and help mention the model setup path.
- [x] Tests cover config parse diagnostics, generated config shape, and `HF_HOME`
  launch environment.

## Verification

- `.\gradlew.bat test --tests dev.talos.core.ConfigUserConfigTest --tests dev.talos.cli.launcher.SetupCmdTest --tests dev.talos.cli.launcher.DiagnoseCmdTest --tests dev.talos.engine.llamacpp.LlamaCppServerManagerTest --tests dev.talos.cli.repl.slash.SimpleCommandsTest --tests dev.talos.app.ui.TerminalFirstRunTest --tests dev.talos.cli.repl.TalosBootstrapTest`
- `.\gradlew.bat check installDist`
- Installed with `tools/install-windows.ps1 -Force`.
- `talos status --verbose` reports `User config: loaded` after setup.
- `talos rag-ask --root . "Say exactly: talos-model-ok"` returned `talos-model-ok`.
