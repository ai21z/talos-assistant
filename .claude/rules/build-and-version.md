---
paths:
  - "build.gradle.kts"
  - "settings.gradle"
  - "settings.gradle.kts"
  - "gradle.properties"
  - "gradle/**"
  - "buildSrc/**"
---

# Build and version conventions (Talos)

Full doctrine: `AGENTS.md` sections Work-Test Cycle (Versioned Candidate Loop), Candidate Packet. Environment notes: root `CLAUDE.md`.

- `talosVersion` in `gradle.properties` is the single source of truth for the version. Do NOT bump it per edit. Bumping the patch is a CANDIDATE-LOOP action only, after `## [Unreleased]` in `CHANGELOG.md` has material notes (`bump-patch.ps1` hard-fails on an empty section).
- Prefer the scripted hermetic cut: `.\scripts\cut-candidate.ps1`. It bumps, commits, builds, runs the post-bump check, and emits the candidate manifest. The recorded SHA must come from `git rev-parse`, never typed by hand.
- Coverage floors and quality gates live in `build.gradle.kts`. Lowering a floor or relaxing a gate is a deliberate, evidenced decision, not a convenience to make a build pass.
- Do not commit generated `build/`, `.qodana/`, or ignored `reports/`. They are gitignored on purpose.
- On this host run Gradle with `--no-daemon`, one invocation at a time. Use `--stop` only as its own separate step. Docker-mode Qodana is broken here: use `.\gradlew.bat qodanaNativeFreshLocal --no-daemon`.
- A behavior-changing ticket adds a one-line entry under `## [Unreleased]` when it lands. Do not create dated version entries outside candidate closeout.
