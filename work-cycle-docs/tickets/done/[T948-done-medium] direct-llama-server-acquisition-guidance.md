# [T948-done-medium] Direct llama-server acquisition guidance

Status: done
Priority: medium

## Summary

Add clear, truthful guidance for users who need to provide their own
`llama-server` binary outside the Ubuntu/WSL setup wizard path.

The current docs repeatedly say "provide a local `llama-server`" but do not
clearly tell direct/expert users where the compatible binary comes from.

## Evidence

Current user docs require a server path:

- `docs/user/quickstart.md:26-27` says Windows or direct/expert Linux users
  must provide `llama-server.exe` or `llama-server`.
- `docs/user/quickstart.md:105-106` says to write a managed profile after
  obtaining a valid local server binary.
- `docs/user/model-setup.md:128-129` says `--server-path` must point to an
  existing local server binary.
- `docs/user/installation.md:137-141` directs users to `talos setup models`
  when they already have a compatible local `llama-server`.
- `docs/user/troubleshooting.md:73-76` gives the setup command after locating
  the binary.

Current code pins the first wizard lane:

- `src/main/java/dev/talos/cli/setup/LlamaCppEngineManifest.java:17-23`
  pins upstream tag `b9860`, asset
  `llama-b9860-bin-ubuntu-x64.tar.gz`, URL
  `https://github.com/ggml-org/llama.cpp/releases/download/b9860/llama-b9860-bin-ubuntu-x64.tar.gz`,
  and executable `llama-server`.
- `src/test/java/dev/talos/cli/setup/LlamaCppEngineManifestTest.java:19-25`
  pins the same values.

External source check:

- The official `ggml-org/llama.cpp` release page for `b9860` lists Linux Ubuntu
  x64 CPU and Windows x64 CPU release assets:
  `https://github.com/ggml-org/llama.cpp/releases/tag/b9860`.

## Implementation Direction

- In user docs, add a concise "Where to get `llama-server`" section for
  direct/expert setup.
- Prefer pointing users to the same upstream project and release family already
  pinned by the wizard, while making clear that the wizard is the recommended
  Ubuntu/WSL x64 path.
- Do not imply Talos bundles `llama-server` in the public installer.
- Do not silently recommend unpinned "latest" as verified by Talos.
- Distinguish:
  - Ubuntu/WSL x64 guided wizard path, where Talos can install the pinned CPU
    engine after confirmation;
  - direct/expert path, where the user supplies a server binary and Talos only
    validates the path/config.

## Acceptance Criteria

- Quickstart, Installation, Model Setup, or Troubleshooting gives users a
  clear source for acquiring `llama-server` before using `--server-path`.
- The guidance references the official `ggml-org/llama.cpp` release source and
  the pinned Talos wizard lane where applicable.
- Copy remains honest: no claim that public installers bundle the server, no
  claim that arbitrary latest upstream builds are Talos-verified.
- Site/docs tests guard at least one "get llama-server" reference so the
  acquisition guidance does not disappear again.

## Verification

Required focused checks:

```powershell
npm test --prefix site
npm run build --prefix site
npm run test:deploy-surface --prefix site
.\gradlew.bat test --tests "dev.talos.cli.setup.LlamaCppEngineManifestTest" --no-daemon
```

Run `git diff --check` before closeout.

Completion evidence:

- Added direct `llama-server` acquisition guidance to
  `docs/user/model-setup.md`, linking to the official `ggml-org/llama.cpp`
  `b9860` release and naming the Talos-pinned Ubuntu/WSL x64 CPU asset.
- Linked the guidance from `docs/user/quickstart.md`,
  `docs/user/installation.md`, and `docs/user/troubleshooting.md`.
- Added a site docs contract regression so the source URL, pinned asset,
  Ubuntu/Windows CPU labels, wizard path, direct setup command, and
  no-unverified-latest wording cannot disappear silently.
- Verified with `npm test --prefix site`,
  `npm run build --prefix site`,
  `npm run test:deploy-surface --prefix site`, and
  `.\gradlew.bat test --tests "dev.talos.cli.setup.LlamaCppEngineManifestTest" --no-daemon`.

## Release Gate Impact

Blocks public release artifact publication unless explicitly waived. Without
this, a direct/expert user can reach a documented setup command but not know
where to obtain the required server binary.
