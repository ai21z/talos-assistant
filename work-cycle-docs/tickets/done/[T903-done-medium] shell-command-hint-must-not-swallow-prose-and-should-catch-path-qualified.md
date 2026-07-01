# [T903-done-medium] ShellCommandHint must not swallow prose, and should catch path-qualified invocations

Status: done
Priority: medium

## Evidence Summary

- Source: adversarial self-review of T898-T901 (workflow wf_bec7abba, shellguard dimension: one confirmed medium, one confirmed nit)
- Talos version / commit: 0.10.6 / bb0bb351 (branch improvement/qodana-cleanup)
- Verification status: both findings confirmed by the review against the committed code; fixed + focused tests green.

## Root Cause

T898's [ShellCommandHint.detect](src/main/java/dev/talos/cli/repl/ShellCommandHint.java) classified a line as a shell invocation when `tokens[1]` was any member of `SUBCOMMANDS`, with no gate distinguishing flags/args from prose. Because `SUBCOMMANDS` contains plain English words (`run`, `status`, `setup`), a legitimate prompt addressed to the assistant that merely starts with the binary name plus such a word -- "talos run the tests please", "talos status of the repo", "talos setup my project for me" -- was matched, the hint shown, and the user's actual prompt discarded (never reached the model). Non-destructive but a real correctness/UX gap, and untested. Separately, the binary check used exact equality on `tokens[0]`, so a path-qualified invocation (`./talos`, `/usr/local/bin/talos`, `C:\...\talos.exe`) was not detected (minor false negative).

## Fix

In [ShellCommandHint.detect](src/main/java/dev/talos/cli/repl/ShellCommandHint.java):
- Tightened the subcommand branch: a subcommand second token counts as shell only when the line is a bare/short command (<= 3 tokens, covering "talos doctor" and "talos setup models") OR contains a flag token anywhere (covering "talos setup models --write --force", the live bug). Longer flagless prose ("talos run the tests please") now falls through to the model. A flag second token ("talos -v") still matches directly.
- Added basename extraction so path-qualified invocations match by the binary basename (`./talos`, `/usr/local/bin/talos --version`, `C:\Users\me\talos.exe setup`).

## Non-Goals

- No change to the trust surface (still a pure presentation nudge, no model turn spent on a match, fails open to the model on a non-match).
- The hint copy still points to /models and /set model; T902 made /set model actually actionable.

## Tests / Evidence

[ShellCommandHintTest](src/test/java/dev/talos/cli/repl/ShellCommandHintTest.java): new NotMatched cases for "talos run the tests please", "talos status of the repo", "talos setup my project for me" (reach the model); new Detected cases for "talos setup models --write --force", the 3-token "talos setup models" chain, and path-qualified "./talos setup", "/usr/local/bin/talos --version", "C:\\Users\\me\\talos.exe setup". Existing detected/non-matched cases stay green.

## Work-Test Cycle Notes

- Inner dev loop; no version bump. One commit (code + tests + ticket).
