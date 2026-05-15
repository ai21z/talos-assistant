# T282 - Config Default Fallback Privacy Parity

Status: open
Severity: high
Release gate: yes for sensitive/private-document beta
Branch: v0.9.0-beta-dev
Created/updated: 2026-05-15
Owner: unassigned

## Problem

Runtime fallback defaults must not diverge from `default-config.yaml` for protected paths, unsupported formats, or private-mode defaults.

## Evidence from current code

This pass updates `Config.ensureDefaults` to include additional protected/tooling excludes that were present in `default-config.yaml`, including `.vscode`, `.external assistant`, `.gradle`, `.mvn`, `node_modules`, `dist`, `prompts`, and `META-INF`.

## Evidence from tests/audits

`ConfigPrivacyDefaultsTest` checks env/secrets/protected excludes, unsupported format excludes, resource-default privacy parity, safe missing-config defaults, and private-mode defaults.

## User impact

A user with no config file should still get safe RAG excludes.

## Product risk

Fallback drift can silently re-enable indexing of protected or unsupported files.

## Runtime boundary affected

Config loading, RAG indexing, private-mode defaults.

## Non-goals

- Replacing YAML config with generated schema.

## Required behavior

- Keep fallback config and resource default config aligned for privacy-sensitive defaults.
- Add tests whenever new protected or unsupported formats are added.

## Proposed implementation

Keep `ConfigPrivacyDefaultsTest` as the regression guard; consider deriving fallback excludes from a single source later.

## Tests

- `ConfigPrivacyDefaultsTest`

## Acceptance criteria

- Missing user config still excludes env, secrets, protected, unsupported binary/document/image/archive formats.
- Private-mode defaults exist when config is absent.

## Remaining blockers

- No single-source generation yet.

## Open questions

- Should default-config parity become a structured config schema test rather than string/list comparisons?

## Related files

- `src/main/java/dev/talos/core/Config.java`
- `src/main/resources/config/default-config.yaml`
- `src/test/java/dev/talos/core/ConfigPrivacyDefaultsTest.java`

