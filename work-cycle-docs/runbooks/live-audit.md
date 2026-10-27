# Live Audit Runbook

Live audit checks Talos behavior against runtime evidence. Do not accept final answers without traces, tool results, approval records, command output, verifier output, and final workspace state.

Every full audit must probe each current model-exposed native tool:

- `talos.list_dir`
- `talos.read_file`
- `talos.compare_documents`
- `talos.grep`
- `talos.retrieve`
- `talos.write_file`
- `talos.edit_file`
- `talos.mkdir`
- `talos.copy_path`
- `talos.move_path`
- `talos.rename_path`
- `talos.delete_path`
- `talos.run_command`

`talos.apply_workspace_batch` is an explicitly excluded registered
compatibility surface. The current assistant planner cannot expose it because
workspace-operation authority is typed for exactly one operation. Run the
deterministic scripted `workspace-batch-no-typed-authority` scenario to prove a
fabricated batch proposal is blocked before approval and mutation. Do not use a
live provider success scenario for this compatibility surface.

Standard setup:

```text
/session clear
/debug prompt on
```

After each natural-language prompt:

```text
/last trace
```

Use separate fresh workspaces per model. Save transcripts, prompt-debug artifacts, provider bodies when relevant, server logs when relevant, approval evidence, final diffs, and final file states.

## Document comparison acceptance

The following lanes are required on both release profiles, GPT-OSS 20B MXFP4
and Qwen2.5 Coder 14B Q4_K_M. The installed candidate, exact model identity,
configuration and source fixtures are recorded for each lane. The bounded
[comparison contract](../../docs/user/commands.md#document-comparison) applies.

- Positive ordered comparison of distinct UTF-8 text or code files, and of PDF
  and DOCX extracted text. Operand order, source identities, delivered changes
  and completion status are checked against the captured inputs.
- A private document comparison with model-context opt-in and artifact storage
  denied. The approved model handoff and local answer are checked separately
  from retained artifacts, history and saved sessions. Known fixture markers
  must be absent from every storage surface for which permission was denied.
  Evidence retention must itself respect those permissions.
- A denied operand and an unsupported comparison or requested explanation.
  Missing evidence and unsupported requirements must remain incomplete. Neither
  may be presented as an equal-files result or a fully supported answer.

Actual provider observations and candidate captures are required for these
lanes. Scripted provider fixtures and saved-response replays are recorded as
deterministic coverage, separately from live acceptance. A passing subset is
not evidence that either profile or the candidate has passed the full audit.

## Model qualification finalization

Finalize provider evidence through the tracked owner. A deterministic runtime
answer may start the managed server only to verify identity. Server process
existence does not prove that a provider prompt was sent.

```powershell
.\gradlew.bat finalizeModelQualificationProviderEvidence `
  -PqualificationRoot=<campaign-root> `
  -PqualificationScenario=<scenario-id> `
  -PqualificationRunRoot=<run-root> `
  -PqualificationServerPort=<port> `
  --no-daemon
```

The command retains the exact paired `NOT_APPLICABLE` evidence written from a
verified prompt-debug response. For a provider turn, it requires one saved
prompt render and the campaign llama.cpp log. Mixed, partial, missing, or
pre-existing noncanonical evidence fails closed.

Publish the exact Talos session trace through Java from its packet-contained,
no-follow-validated source. PowerShell must not copy or write `run/trace.json`.

```powershell
.\gradlew.bat finalizeModelQualificationTraceEvidence `
  -PqualificationRoot=<campaign-root> `
  -PqualificationScenario=<scenario-id> `
  -PqualificationRunRoot=<run-root> `
  --no-daemon
```

After `answer.txt`, the committed `trace.json`, `before-files/`, and `final-files/` are fixed,
generate requirement results through the tracked canonical evaluator:

```powershell
.\gradlew.bat finalizeModelQualificationRequirements `
  -PqualificationRoot=<campaign-root> `
  -PqualificationScenario=<scenario-id> `
  -PqualificationRunRoot=<run-root> `
  --no-daemon
```

The command writes `mechanical-requirements.json` once and refuses overwrite.
Campaign finalizers consume its `requirements` array. They must not reimplement
answer, file, or trace requirement semantics in PowerShell or another script.
The packet validator recomputes the same results through the same Java owner.
