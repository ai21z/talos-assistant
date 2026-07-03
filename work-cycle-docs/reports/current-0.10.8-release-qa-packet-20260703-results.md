# Talos 0.10.8 Release QA Packet - 2026-07-03

## Scope

- Branch: `v0.9.0-beta-dev`
- Candidate version: `0.10.8`
- Local product commit under QA: `5ba4836cb58242f65100b374f0688f6db1705473`
- Product install path: `C:\Users\arisz\AppData\Local\Programs\talos\bin\talos.bat`
- Installed identity:
  `Talos 0.10.8 - Java 21.0.9+10-LTS - Windows 11 amd64 - build 2026-07-03T10:52:32.463528400Z`
- QA root:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238`
- Workspace root:
  `local/manual-workspaces/t936-0.10.8-release-qa-20260703-1238`
- Artifact status: local QA evidence only. No GitHub Release asset, tag,
  winget-linked artifact, or public release artifact was created.

## Automated Candidate Evidence

The original 0.10.8 candidate automated gate passed at
`f291e902c28d1c84bbc27756f3b8822569eef0c1`:

- `git diff --check`
- `.\gradlew.bat installDist --no-daemon`
- installed launcher identity check reporting `Talos 0.10.8`
- `.\gradlew.bat check --no-daemon`
- `.\gradlew.bat wikiEvidenceCloseGate --rerun-tasks --no-daemon`
- `.\gradlew.bat talosQualitySummaries --no-daemon`
- `build/reports/talos/candidate-manifest.json`

During manual QA, T938 fixed an e2e harness false-negative in the synchronized
approval runner. The product runtime code was unchanged by T938; the installed
product was rebuilt and reinstalled from `5ba4836cb58242f65100b374f0688f6db1705473`
before the final PTY evidence.

## Installed Doctor Evidence

Qwen profile:

- Config/home:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/config.yaml`
- Evidence:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/qwen/doctor-start.txt`
- Result: `8 passed, 0 warning(s), 0 failed, 0 skipped`

GPT-OSS profile:

- Config/home:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-gptoss/.talos/config.yaml`
- Evidence:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/gptoss/doctor-start.txt`
- Result: `8 passed, 0 warning(s), 0 failed, 0 skipped`

## Synchronized Approval Banks

Qwen:

- Model/profile: `llama_cpp/qwen2.5-coder-14b`
- Summary:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/qwen/synchronized-approval/SYNCHRONIZED-APPROVAL-AUDIT.md`
- Scenarios: 33
- Artifact scan: PASS
- Review note: `protected-read-denied` is scored `FAIL_REVIEW_REQUIRED`
  because the scenario expected an approval prompt, but runtime deterministically
  denied `.env` with `CONFIG_DENY`. Manual review confirmed no protected content
  leak and an honest denial final answer.

GPT-OSS:

- Model/profile: `llama_cpp/gpt-oss-20b`
- Initial summary:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/gptoss/synchronized-approval/SYNCHRONIZED-APPROVAL-AUDIT-FAILED.md`
- Initial result: failed at
  `mutation-forbidden-sibling-target-blocked-before-approval`.
- Root cause: T938 found a QA-harness false-negative. GPT-OSS updated
  `script.js` without a terminal newline while keeping forbidden sibling
  `scripts.js` unchanged; the product safety invariant held.
- Corrected rerun summary:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/artifacts/gptoss/synchronized-approval-rerun-t938/SYNCHRONIZED-APPROVAL-AUDIT.md`
- Corrected rerun scenarios: 33
- Corrected rerun artifact scan: PASS
- Review note: `protected-read-denied` has the same reviewed
  `CONFIG_DENY` shape as Qwen.

## Manual PTY Evidence

Manual PTY lane 1, installed Qwen product:

- Workspace:
  `local/manual-workspaces/t936-0.10.8-release-qa-20260703-1238/qwen-pty`
- Talos home:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen`
- Session ledger:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/sessions/0912e1016a3cf6b37b5310fdc589e48a86fcdd1c-20260703105311.turns.jsonl`
- Trace directory:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/sessions/traces/0912e1016a3cf6b37b5310fdc589e48a86fcdd1c-20260703105311`
- Prompt-debug save:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/prompt-debug/prompt-debug-20260703-125431.md`
- Provider body:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/prompt-debug/prompt-debug-20260703-125431.provider-body.json`
- Covered:
  `talos`, `/debug prompt on`, `/status --verbose`, `/mode`, `/prompt`,
  read-only tool use, `/last trace`, `/prompt-debug last`,
  `/prompt-debug save`, approval denial, one-time approval, checkpoint creation,
  and static edit verification.
- Result:
  read-only answer grounded in `README.md`; denied edit changed no file;
  approved edit changed `README.md` and verified.
- Limitation:
  attempts to exercise allow-in-session in this first lane were denied because
  Qwen proposed bad diffs, and one later multiline terminal injection was
  operator-contaminated. Those turns are not counted as product failures.

Manual PTY lane 2, installed Qwen product, session approval:

- Workspace:
  `local/manual-workspaces/t936-0.10.8-release-qa-20260703-1238/qwen-pty-session-approval`
- Session ledger:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/sessions/b20020a7a5da2a1056592caee5f8cac0f84a43f2-20260703110039.turns.jsonl`
- Trace directory:
  `local/manual-testing/t936-0.10.8-release-qa-20260703-1238/home-qwen/.talos/sessions/traces/b20020a7a5da2a1056592caee5f8cac0f84a43f2-20260703110039`
- Covered:
  approval allow-in-session in a true PTY/JLine REPL.
- Evidence:
  turn 1 changed `README.md` heading to `# Session Approval A` with
  `approvalsRequired=1`, `approvalsGranted=1`, `approvalsDenied=0`.
  Turn 2 changed the heading to `# Session Approval B` with
  `approvalsRequired=0`, `approvalsGranted=1`, `approvalsDenied=0`.
- Final workspace state:
  `README.md` contains `# Session Approval B`.
- `/last trace` for turn 2 reported `COMPLETE`, `COMPLETED_VERIFIED`,
  `Approvals: required=0 granted=1 denied=0`, checkpoint CREATED, and
  verification PASSED.

## Artifact Canary Scan

Manual evidence root scan:

```powershell
.\gradlew.bat checkRuntimeArtifactCanaries -PartifactScanRoots="local/manual-testing/t936-0.10.8-release-qa-20260703-1238" --no-daemon
```

Result: PASS.

The raw workspace root was intentionally not scanned as an artifact root because
the fixture workspaces contain synthetic protected markers by design.

## Skipped Or Scoped Coverage

- No GitHub Release asset, draft release asset, signed artifact, tag-bound
  artifact, or winget-linked artifact was created.
- Linux/WSL installer smoke was already performed for the setup wizard work, but
  this packet's live model QA was Windows installed-product evidence.
- Manual PTY was run on Qwen. GPT-OSS manual behavior was covered by the
  synchronized approval bank, not a separate interactive PTY lane.
- The first PTY transcript captured by PowerShell was incomplete under JLine;
  durable Talos session ledgers, traces, prompt-debug files, provider body, and
  final workspace state are the authoritative PTY evidence.

## Current Verdict

Local T929 QA evidence is acceptable for the 0.10.8 candidate after T938:

- installed product identity is correct;
- doctor passes for both standard model profiles;
- synchronized approval banks pass for both standard models after the T938
  harness correction;
- protected-read denial review confirms deterministic deny with no leak;
- manual PTY covers installed REPL identity, mode/status/prompt surfaces,
  prompt-debug, `/last trace`, denial, one-time approval, session approval,
  checkpointing, and verification;
- artifact canary scan passes for manual evidence roots.

Remote branch push and GitHub Actions verification were still pending when this
packet was written.
