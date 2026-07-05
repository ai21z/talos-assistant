# Live Audit Runbook

Live audit checks Talos behavior against runtime evidence. Do not accept final answers without traces, tool results, approval records, command output, verifier output, and final workspace state.

Every full audit must probe or explicitly exclude each current native tool:

- `talos.list_dir`
- `talos.read_file`
- `talos.grep`
- `talos.retrieve`
- `talos.write_file`
- `talos.edit_file`
- `talos.mkdir`
- `talos.copy_path`
- `talos.move_path`
- `talos.rename_path`
- `talos.delete_path`
- `talos.apply_workspace_batch`
- `talos.run_command`

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

