# Model profiles

Accepted beta stability profiles are qwen2.5-coder-14b and gpt-oss-20b.

Qwen3.6-VibeForged and DeepSeek-Coder-V2-Lite profiles are experimental selectable profiles.

## qwen2.5-coder-14b

```bash
talos setup models --profile qwen2.5-coder-14b
talos doctor --start
```

Use this profile when you want the strongest currently tested coding lane.

## gpt-oss-20b

```bash
talos setup models --profile gpt-oss-20b
talos doctor --start
```

gpt-oss-20b requires a concrete local GGUF model path.

## Experimental Profiles

DeepSeek-Coder-V2-Lite uses text/tool-prompt mode. Do not describe it as a structured tool-calling profile unless new runtime evidence proves that.

Save the talos doctor --start output as evidence before calling a profile verified on a new machine.

## Evidence rule

A selectable profile is not automatically a release-supported profile. Before using a model in release evidence, record the setup profile, actual model identity, backend, `doctor --start` result, and the manual audit lanes that were run.
