# [T926-open-high] Installation setup wizard

Status: open
Priority: high

## Evidence Summary

- Source: WSL2 Ubuntu install-path smoke plus installation design review
- Date: 2026-07-02
- Talos version / commit: 0.10.7 / d17057b9a6c9ca39dcc50d4ded73382a5ae5dfd3
- Follow-up WSL evidence version / commit: 0.10.7 /
  2314360f2f972d482405437581160436b456c939
- Model/backend: managed `llama.cpp` intended beta path; initial WSL smoke had
  no Linux `llama-server` configured; follow-up WSL smoke configured
  `qwen2.5-coder-14b` through managed `llama.cpp`
- Workspace fixture: `/home/ai21z/talos-wsl-install-smoke`
- Verification status: milestones 1-3 implemented; engine install, model
  download, doctor execution, final docs, and full setup smoke remain open

Expected behavior:

```text
A new Ubuntu/WSL user should be able to paste one Talos install command, install
Talos itself, satisfy any Java/runtime prerequisite before the first Talos JVM
launch, configure a tested local model stack, and finish with `talos doctor
--start` evidence or a precise skipped/blocked reason. Packaged lanes that
bundle a runtime and source/developer lanes that require Java must be kept
explicitly separate.
```

Observed behavior:

```text
The current Unix installer installs Talos from an already-built distribution and
adds `~/.local/talos/bin` to a shell profile, but it does not install or guide
Java, llama.cpp, model downloads, config writing, or doctor verification. In a
fresh WSL Ubuntu home, `talos doctor` correctly failed because `server_path` was
missing; the natural turn failed honestly with BACKEND_CONNECTION_FAILED.
The current `tools/install-unix.sh` shell-profile detection is also ambiguous
for zsh users because the documented invocation runs the script under bash, so
`BASH_VERSION` is set even when the user's login shell is zsh.
```

Source anchors:

- `tools/install-unix.sh` installs to `~/.local/talos` and updates a shell
  profile, but expects `build/install/talos` to already exist.
- `tools/install-unix.sh` chooses `.zshrc` only when `$ZSH_VERSION` is set and
  `.bashrc` when `$BASH_VERSION` is set; under `bash tools/install-unix.sh`, that
  can write the Talos PATH entry to `.bashrc` for zsh users.
- `tools/install-windows.ps1` has the same install-only shape for Windows.
- `docs/public-installation.md` states that Windows packaged public install
  includes a bundled Java runtime, while Linux source/developer setup requires a
  user-provided Java 21 runtime.
- `docs/user/model-setup.md` and `docs/setup-managed-models.md` document tested
  profiles, but still require the user to provide a `llama-server` path.
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java` renders tested model
  profile config for `talos setup models`.
- `src/main/java/dev/talos/app/ui/TerminalFirstRun.java` tells users to run
  `talos setup models` when model files are missing.
- WSL smoke evidence: OpenJDK 21 had to be installed manually, direct
  `/home/ai21z/.local/talos/bin/talos --version` succeeded, `talos setup models`
  rendered, and `talos doctor` failed only on missing Linux model/server config.
- Follow-up WSL evidence: after installing a Linux `llama-server` binary and
  writing the `qwen2.5-coder-14b` config, `talos doctor --start` passed all
  eight checks and reported `Environment is ready`.
- Follow-up WSL evidence: installed Linux Talos completed a real Qwen-backed
  REPL turn (`COMPLETE / READ_ONLY_ANSWERED`), `/last trace` recorded
  `Mode: auto`, and `/prompt-debug last` captured provider-body JSON.
- Follow-up WSL evidence: `talos status --verbose` and the REPL printed Java
  native-access warnings before normal output:
  `java.lang.foreign.Linker::downcallHandle`. Dependency inspection traced the
  warning source to bundled JLine/Lucene FFM usage; running the same installed
  Linux binary with `--enable-native-access=ALL-UNNAMED` suppressed it.
- Milestone 3 WSL evidence: the Unix installer now detects Java 21 before
  Talos launch, writes PATH to an explicit/login-shell profile, verifies
  `/home/ai21z/.local/talos/bin/talos --version` directly, warns when inherited
  WSL PATH still resolves `talos` to the Windows install, and preserves
  idempotent PATH entries.

## Classification

Primary taxonomy bucket: `UNSUPPORTED_CAPABILITY`

Secondary buckets:

- `OUTCOME_TRUTH`
- `TOOL_SURFACE`
- `VERIFICATION`

Blocker level: release blocker before advertising Linux/WSL one-command setup

Why this level:

```text
Talos can be installed and run on Ubuntu/WSL once Java exists, but the product
does not yet provide a complete guided path from install to a working local
model turn. Advertising Linux/WSL installation without a setup wizard would
recreate the fresh-machine failure observed in the WSL smoke.
```

Sequencing:

```text
This ticket blocks advertising Linux/WSL one-command setup. It does not by
itself block a first Windows packaged developer-beta tag if that release stays
truthful about Linux being source/developer only and about model setup remaining
explicit.
```

## Architectural Hypothesis

Architectural hypothesis:

```text
Installation should be split into a small bootstrap installer and a Talos-owned
setup wizard. The bootstrap handles every pre-JVM prerequisite needed before the
first Talos invocation: OS/shell detection, Java/runtime detection or guidance,
Talos artifact install, PATH handling, and `talos --version` verification when
Java/runtime is available. The wizard then runs inside Talos to detect model
runtime state, ask before engine/model work, write config with backup, and
finish with `doctor --start` evidence.
```

Likely code/document areas:

- `tools/install-unix.sh`
- `tools/install-windows.ps1`
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java`
- `src/main/java/dev/talos/app/ui/TerminalFirstRun.java`
- `src/main/java/dev/talos/cli/doctor/*`
- `docs/user/installation.md`
- `docs/user/model-setup.md`
- `docs/user/troubleshooting.md`

Why a one-off patch is insufficient:

```text
Adding another doc snippet or a single `apt install` command would still leave
llama.cpp acquisition, model selection, config backup/write, WSL path ambiguity,
offline/non-interactive installs, and final doctor proof scattered across
scripts and docs. This needs one guided setup flow with explicit ownership.
```

## Goal

```text
Provide a guided install/setup wizard that takes a fresh Ubuntu/WSL developer
from installed Talos to a verified local model environment. The bootstrap must
resolve or truthfully block Java/runtime prerequisites before invoking Talos;
the wizard must require explicit consent for engine installation, model
download, config writes, and doctor verification.
```

## Non-Goals

- No public release/tag/winget cut in this ticket.
- No silent installation of system packages, services, engines, or models.
- No default use of upstream "latest" llama.cpp as the trusted path.
- No automatic trust in a randomly discovered `llama-server` binary.
- No GUI installer in this ticket.
- No deb/rpm/AppImage packaging in this ticket.
- No hidden or default elevated package-manager execution.
- No implementation of pinned llama.cpp install until a Talos-owned manifest
  names source URL, upstream tag or Talos-hosted artifact, OS/arch variant, and
  SHA-256.
- No conflating Windows packaged install with bundled runtime and Linux
  source/developer install with user-provided Java.
- No bypassing approval, permission, checkpoint, trace, or verification.
- No committing raw private install transcripts.

## Implementation Notes

```text
Recommended first version:

Milestone 1 should be a dry-run decision surface, not side effects:
`talos setup wizard --dry-run` plus deterministic preflight/decision tests.

Runtime flow:

1. Bootstrap script detects OS, distro, arch, WSL, login shell, Java runtime,
   package manager presence, existing Talos install, PATH collision risks, and
   JVM launcher flags required for clean installed-product output.
2. If Java is missing in a source/developer lane, bootstrap prints the exact
   install command or asks for explicit package-install permission before any
   Talos JVM invocation. Packaged lanes with bundled runtimes skip this paradox.
3. Bootstrap installs Talos, updates the correct shell profile, verifies the
   direct installed binary path before any inherited PATH candidate, then offers
   to launch `talos setup wizard`.
4. Wizard re-verifies runtime state and detects existing `~/.talos/config.yaml`,
   existing Linux-compatible `llama-server`, WSL path hazards, and disk/RAM
   basics.
5. Wizard asks whether to use an existing `llama-server`, install from a
   Talos-tested pinned llama.cpp manifest, or skip model setup.
6. The pinned engine manifest must name source URL, upstream tag or Talos-hosted
   artifact, OS/arch/backend variant, install path, and SHA-256 before install
   code lands.
7. Wizard presents tested model choices: `qwen2.5-coder-14b` recommended for
   coding and `gpt-oss-20b` as the alternate accepted beta profile.
8. Before any model download, wizard shows source, file, approximate download
   size, approximate disk/cache need, practical RAM guidance, CPU-only
   expectation, cache path, and support level.
9. Wizard writes config only after confirmation, backing up any existing config.
10. Wizard finishes with `talos doctor --start`; if skipped or failed, print the
    exact next command and the exact reason.
11. Non-interactive mode must require explicit flags for package-install,
    engine source, model profile, config write, and doctor behavior.
```

## Milestone 1 Implementation Evidence

Status:

```text
Implemented as a dry-run decision surface only. T926 remains open because the
interactive wizard, bootstrap Java/package-manager handling, pinned llama.cpp
manifest, model download, config write, doctor execution, installer profile
fixes, docs, and full installed-product smoke are later milestones.
```

Implemented surface:

- `talos setup wizard --dry-run`
- `talos setup wizard` without `--dry-run` fails closed with an explicit
  milestone-boundary message.
- The dry run prints:
  - detected OS/arch/WSL, distro when available, Java feature version,
    config path/existence, detected `llama-server`, usable disk, and JVM max
    memory;
  - a decision plan for Java, config, `llama-server`, accepted beta model
    profile selection, and `talos doctor --start` verification;
  - an explicit no-side-effects boundary: no package installs, no model
    downloads, no config writes, and no model starts.

Code:

- `src/main/java/dev/talos/cli/setup/SetupWizardSnapshot.java`
- `src/main/java/dev/talos/cli/setup/SetupWizardStep.java`
- `src/main/java/dev/talos/cli/setup/SetupWizardPlan.java`
- `src/main/java/dev/talos/cli/setup/SetupWizardPlanner.java`
- `src/main/java/dev/talos/cli/setup/SetupWizardRenderer.java`
- `src/main/java/dev/talos/cli/setup/SetupWizardEnvironmentProbe.java`
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java`

Pinned behavior:

- Fresh Ubuntu/WSL-like state queues manual choices without side effects.
- Existing Java 21+, config, and Linux-compatible `llama-server` are detected
  as skip/reuse-or-ask decisions.
- A Windows `.exe` `llama-server` visible from WSL is not treated as a
  compatible Linux engine binary.
- The model step lists only the accepted beta profiles
  `qwen2.5-coder-14b` and `gpt-oss-20b` from the shared
  `LlamaCppModelProfiles` registry.

Verification:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.SetupCmdTest" --tests "dev.talos.cli.setup.SetupWizardPlannerTest" --no-daemon
```

Result:

```text
BUILD SUCCESSFUL
SetupCmdTest: 15 tests, 0 failures, 0 errors
SetupWizardPlannerTest: 3 tests, 0 failures, 0 errors
```

Installed-distribution smoke:

```powershell
.\gradlew.bat clean installDist --no-daemon
.\build\install\talos\bin\talos.bat setup wizard --dry-run --config build\tmp\t926-dry-run-config.yaml
```

Result:

```text
BUILD SUCCESSFUL
Talos setup wizard dry run
No changes will be made: no package installs, no model downloads, no config writes, no model starts.
CONFIG_NOT_CREATED
```

## Milestone 2 Implementation Evidence

Status:

```text
Implemented as an interactive config-only setup wizard. T926 remains open
because bootstrap Java/package-manager handling, pinned llama.cpp manifest,
engine install, model download, doctor execution, installer profile fixes, docs,
and full installed-product smoke are later milestones.
```

Implemented surface:

- `talos setup wizard`
- `talos setup wizard --dry-run` remains side-effect-free.
- The interactive wizard:
  - prints the detected environment and a no-installs/no-downloads/no-starts
    boundary;
  - accepts an existing detected `llama-server` only after explicit `y/yes`;
  - rejects Windows `.exe` `llama-server` paths under WSL;
  - allows the user to enter a Linux-compatible `llama-server` path or skip;
  - lists only accepted beta profiles `qwen2.5-coder-14b` and `gpt-oss-20b`;
  - previews the generated config;
  - writes config only after final explicit confirmation;
  - backs up any existing config before overwrite;
  - prints `talos doctor --start` as the next command instead of running it.

Non-goals preserved:

- No package-manager execution.
- No pinned llama.cpp install.
- No model download.
- No model/server start.
- No `doctor --start` execution.

Code:

- `src/main/java/dev/talos/cli/setup/SetupWizardRunner.java`
- `src/main/java/dev/talos/cli/setup/SetupWizardEnvironmentProbe.java`
- `src/main/java/dev/talos/cli/setup/SetupWizardPlanner.java`
- `src/main/java/dev/talos/cli/setup/SetupWizardRenderer.java`
- `src/main/java/dev/talos/cli/launcher/SetupCmd.java`

Pinned behavior:

- Denying the final write leaves config absent.
- Accepting detected Linux-compatible `llama-server` plus a selected accepted
  beta profile writes the expected config.
- Existing config is backed up before overwrite.
- WSL-visible Windows `.exe` server paths are rejected and do not produce config
  when the user skips replacement.
- CLI wiring can write config through `talos setup wizard --server-path ...`
  after explicit prompt input.

Verification:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.SetupCmdTest" --tests "dev.talos.cli.setup.SetupWizardPlannerTest" --no-daemon
```

Result:

```text
BUILD SUCCESSFUL
SetupCmdTest: 15 tests, 0 failures, 0 errors
SetupWizardPlannerTest: 7 tests, 0 failures, 0 errors
```

Installed WSL smoke:

```powershell
.\gradlew.bat clean installDist --no-daemon
wsl.exe bash -lc 'cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli; bash tools/install-unix.sh --force'
```

Installed executable:

```text
/home/ai21z/.local/talos/bin/talos
Talos 0.10.7 / Linux amd64 / Java 21.0.11 / build 2026-07-02T21:51:39.787830500Z
```

Smoke probes:

```text
DENIAL_NO_CONFIG
EXE_REJECTED
WRITE_CONFIG_OK
BACKUP_OK
```

Interpretation:

```text
The installed WSL binary exercised the interactive wizard against throwaway
`/tmp/t926-wizard-smoke` paths. Final write denial left config absent; a
WSL-visible `llama-server.exe` was rejected as not Linux-compatible; accepted
`gpt-oss-20b` wrote a config; accepted `qwen2.5-coder-14b` overwrote an existing
config only after creating a backup preserving the old content. No package
install, model download, model/server start, or `doctor --start` execution was
performed.
```

## Milestone 3 Implementation Evidence

Status:

```text
Implemented as Unix bootstrap prerequisite/profile hardening only. T926 remains
open because pinned llama.cpp manifest/install, model downloads, doctor
execution, final docs, and a full fresh-machine setup smoke are later
milestones.
```

Implemented surface:

- `tools/install-unix.sh --dry-run`
- `tools/install-unix.sh --profile-file <path>`
- `tools/install-unix.sh --allow-package-install`
- Java 21+ preflight before the first Talos JVM invocation.
- Exact Ubuntu/Debian Java guidance:
  `sudo apt update && sudo apt install -y openjdk-21-jre-headless`
- Login-shell profile selection through `$SHELL` / `getent passwd`, not
  `ZSH_VERSION` / `BASH_VERSION` interpreter variables.
- Direct installed-binary verification:
  `"$INSTALL_DIR/bin/talos" --version`
- PATH-shadow warning when inherited WSL PATH resolves `talos` to another
  install.
- Handoff text to `talos setup wizard`.

Non-goals preserved:

- No hidden package-manager execution.
- No pinned llama.cpp install.
- No llama.cpp download.
- No model download.
- No model/server start.
- No `doctor --start` execution.

Code:

- `tools/install-unix.sh`
- `src/test/java/dev/talos/release/PublicInstallPackagingContractTest.java`

TDD red:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
```

Result:

```text
BUILD FAILED
8 tests completed, 3 failed
```

Focused green:

```powershell
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --tests "dev.talos.cli.launcher.SetupCmdTest" --tests "dev.talos.cli.setup.SetupWizardPlannerTest" --no-daemon
```

Result:

```text
BUILD SUCCESSFUL
```

WSL bootstrap smoke:

```powershell
.\gradlew.bat clean installDist --no-daemon
wsl.exe -e bash -lc 'cd /mnt/c/Users/arisz/Projects/LOQ/loqj-cli; bash tools/install-unix.sh --force --profile-file /tmp/talos-t926-profile'
```

Observed installer evidence:

```text
Java 21 detected.
Verifying direct installed Talos binary...
Talos 0.10.7 - Java 21.0.11+10-1-26.04.2-Ubuntu - Linux amd64
Warning: current PATH resolves talos to /mnt/c/Users/arisz/AppData/Local/Programs/talos/bin/talos
```

Additional WSL probes:

```text
SHELL=/usr/bin/zsh -> Shell profile: /home/ai21z/.zshrc
SHELL=/bin/bash -> Shell profile: /home/ai21z/.bashrc
SHELL=/usr/bin/fish -> Shell profile: /home/ai21z/.config/fish/config.fish
Rerun profile entry count: 1
source /tmp/talos-t926-profile; command -v talos -> /home/ai21z/.local/talos/bin/talos
Installed `talos setup wizard --dry-run` rendered Ubuntu 26.04 WSL, Java 21,
missing config, no detected llama-server, accepted beta profiles, and the
no-side-effects milestone boundary.
```

## Architecture Metadata

Capability:

- installed-product setup and local model runtime configuration

Operation(s):

- install files
- update PATH/profile
- inspect system prerequisites
- detect or guide Java/runtime prerequisites before first JVM launch
- optionally install pinned llama.cpp runtime from a manifest
- optionally download managed GGUF model
- write `~/.talos/config.yaml`
- run doctor verification

Owning package/class:

- Bootstrap scripts: `tools/install-unix.sh`, `tools/install-windows.ps1`
- Runtime wizard owner candidate: `src/main/java/dev/talos/cli/launcher/SetupCmd.java`
  or a dedicated `setup.wizard` package/class called by `SetupCmd`

New or changed tools:

- CLI command surface likely adds `talos setup wizard`
- Existing `talos setup models` should remain as the expert/direct path

Risk, approval, and protected paths:

- Risk level: high for onboarding/distribution; medium technical risk
- Approval behavior: every engine install, model download, and config write
  requires explicit user confirmation unless non-interactive flags provide exact
  choices. System package installation defaults to printing the command for the
  user; Talos may execute package-manager commands only behind an explicit flag
  such as `--allow-package-install`, after echoing the exact command.
- Protected path behavior: do not inspect protected workspace paths; wizard
  operates on Talos install/config/cache locations only

Checkpoint, evidence, verification, and repair:

- Checkpoint behavior: back up existing `~/.talos/config.yaml` before overwrite
- Evidence obligation: print installed paths, selected profile, config path,
  engine manifest identity/checksum, model/cache path, doctor result, and
  skipped/failed reasons
- Verification profile: `talos --version`, `talos setup models`, `talos doctor`,
  and `talos doctor --start` when model/runtime setup is complete
- Repair profile: fail closed on missing prerequisites, unsupported OS/arch, or
  checksum mismatch; print next corrective command

Outcome and trace:

- Outcome/truth warnings: never claim setup complete unless `doctor --start`
  passes or the user explicitly skipped model setup
- Trace/debug fields: not required for installer script; setup wizard should
  write enough local evidence for install smoke review

Refactor scope:

- Allowed: small setup-wizard owner extraction, deterministic detection helpers,
  installer smoke tests, and docs updates.
- Forbidden: broad CLI launcher rewrite, release packaging overhaul, hidden
  background service installation, or model-profile changes without evidence.

## Acceptance Criteria

- Fresh Ubuntu/WSL path installs Talos and verifies the Linux-installed
  `talos --version` without relying on the Windows-installed Talos in inherited
  WSL PATH.
- Fresh Ubuntu/WSL installed-product status and REPL startup do not print Java
  native-access warnings during ordinary `talos status --verbose` or
  `talos run` startup.
- In source/developer Linux lanes, missing Java runtime is detected before the
  first Talos JVM invocation and handled by printing the exact command, obtaining
  explicit package-install permission, or producing a clear block message.
- Packaged lanes with bundled runtimes are documented and tested separately from
  source/developer lanes that require user-provided Java.
- Unix installer shell-profile detection uses the user's login shell or explicit
  profile choice, not only `$ZSH_VERSION`/`$BASH_VERSION` from the script
  interpreter.
- Wizard detects or guides installation of a Linux-compatible `llama-server`;
  Windows `.exe` paths visible from WSL are not treated as valid Linux engine
  binaries.
- Default engine path uses a Talos-tested pinned llama.cpp manifest, not
  unbounded upstream latest. The manifest names source URL, upstream tag or
  Talos-hosted artifact, OS/arch/backend variant, install path, and SHA-256.
- Package-manager execution is never hidden: default behavior prints the exact
  command; execution requires an explicit flag and echoes the command first.
- Wizard offers the accepted beta profiles `qwen2.5-coder-14b` and
  `gpt-oss-20b`, with support level, source, file name, disk/cache location, and
  explicit download confirmation.
- Model selection shows approximate download size, disk/cache footprint,
  practical RAM guidance, and CPU-only expectation before download.
- Existing `~/.talos/config.yaml` is backed up before any write and never
  overwritten silently.
- Final result runs `talos doctor --start` when setup is complete; if skipped or
  failed, the wizard prints an honest partial result and exact next step.
- Non-interactive mode exists for CI/advanced installs and requires explicit
  profile/server/runtime choices; no hidden defaults that download large models.
- Wizard rerun is idempotent: it detects existing install/config/cache state,
  does not duplicate PATH entries, and does not overwrite config without backup
  and confirmation.
- Docs cover Ubuntu, WSL, Windows, offline/skip paths, uninstall, and rerun
  behavior.
- No regressions to privacy, permissions, checkpointing, trace redaction, or
  outcome truth.

## Tests / Evidence

Required deterministic regression:

- Unit test: bootstrap preflight detects Java missing/present before first JVM
  launch, WSL/non-WSL, login shell/profile target, Linux/Windows server binary
  mismatch, existing config, and disk-space warning.
- Unit/contract test: generated launcher JVM defaults include the native-access
  flag required to suppress Java FFM warnings from bundled terminal/index
  libraries on Linux.
- Unit test: wizard decision model never installs Java, llama.cpp, models, or
  writes config without confirmation or explicit non-interactive flags.
- Unit test: package-manager command execution is not selected unless an
  explicit allow flag is present and the exact command has been rendered.
- Unit test: pinned llama.cpp install cannot proceed without manifest URL/tag or
  artifact, OS/arch/backend variant, and SHA-256.
- Unit test: config write creates a backup before overwrite.
- Integration test: `talos setup wizard --dry-run` is the first milestone and
  renders the expected steps for a fresh Ubuntu/WSL environment without side
  effects.
- Installer contract test: Unix installer verifies local `~/.local/talos/bin`
  before inherited Windows PATH candidates.
- Installer contract test: zsh login-shell users get the Talos PATH entry in
  `.zshrc` or an explicitly selected profile, not silently in `.bashrc`.
- Idempotency test: rerunning the wizard does not duplicate PATH entries,
  redownload existing artifacts, or overwrite config without backup/confirmation.

Manual installed-product rerun:

- Platform: WSL2 Ubuntu
- Expected flow: install Talos, detect Java, configure pinned llama.cpp, select
  one tested profile, write config, run `doctor --start`, then run a one-turn
  REPL smoke with `/last trace` and `/prompt-debug last`.

Commands:

```powershell
.\gradlew.bat test --tests "dev.talos.cli.launcher.*Setup*Test" --no-daemon
.\gradlew.bat test --tests "dev.talos.release.PublicInstallPackagingContractTest" --no-daemon
git diff --check
```

Add broader commands if runtime code changes:

```powershell
.\gradlew.bat check --no-daemon
```

## Work-Test Cycle Notes

- This is not a candidate cut by itself.
- This blocks Linux/WSL one-command advertising, not necessarily a first Windows
  packaged developer-beta tag if Linux remains truthfully scoped as
  source/developer setup.
- Use strict TDD for setup decision logic before editing installer behavior.
- Keep the first milestone Ubuntu/WSL + Windows-aware, not full Linux packaging.
- Add a `CHANGELOG.md` `Unreleased` entry when implementation lands.

## Known Risks

- Silent dependency/model installation would violate Talos's local-trust
  posture.
- Installing system packages from inside a Talos installer without an explicit
  allow flag would be higher privilege than ordinary workspace mutation and
  off-thesis for a trust product.
- Upstream latest llama.cpp may drift under Talos and create unverifiable setup
  failures; prefer a pinned tested build.
- "Pinned tested build" is not implementable until a manifest defines the source
  URL, artifact/tag, OS/arch/backend variant, and SHA-256.
- WSL PATH inheritance can make `talos` resolve to the Windows install even
  after a Linux user-local install.
- Running the Unix installer under bash can mis-detect zsh users unless the
  script uses login-shell/profile detection rather than interpreter variables.
- Java 21+ emits native-access warnings when JLine or Lucene touches
  `java.lang.foreign` APIs unless the generated launcher includes the required
  JVM flag; warning text before normal command output makes a fresh Linux setup
  look broken even when the environment is functional.
- Large model downloads can fail on disk, bandwidth, proxy, or Hugging Face
  availability; wizard must distinguish download failure from Talos failure.
- Installing package-manager dependencies may require sudo or root; no-sudo
  users need a clear manual path.

## Known Follow-Ups

- Add Linux installer smoke to CI after the wizard has deterministic dry-run
  surfaces.
- Publish model evidence as a public matrix once release/tag evidence exists.
- Consider bundled runtime/package artifacts after the tarball/install-script
  beta path is working.
