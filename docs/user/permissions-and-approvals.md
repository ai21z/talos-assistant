# Permissions and approvals

Talos separates tool visibility, policy, and approval.

The model does not decide what it is allowed to do. Talos first narrows the current turn to the mode, task, target paths, command profile, and privacy posture. Approval happens after that narrowing.

- Read-only inspection may run when the workspace boundary and protected-path policy allow it.
- File writes require approval by default before mutation.
- Bounded command execution requires a visible command profile and permission, with approval requested by default.
- Protected reads may require explicit approval.
- Private mode narrows how protected read results are handed to the model.

Approval is the last gate, not the only gate. Tools that are outside the current mode, task phase, or command profile should not be visible to the model for that turn.

Configured allow rules or remembered approval for supported safe writes can avoid a fresh prompt. These exceptions do not bypass target, mode or protection checks. Direct protected reads still require approval, and protected comparison operands are rejected. REPL `/verify ws:<id>` requires approval per run.

Denials are valid outcomes. A denied mutation should be reported as denied, not as completed.

## Approval outcomes

| Outcome | Meaning |
|---|---|
| Approved | Talos may perform the specific proposed action and then verify the result. |
| Denied | The denied action is not performed. Any earlier applied or uncertain effects remain reported; denial does not roll them back. |
| Not offered | The current mode, task, policy, or profile did not allow the action to reach approval. |

Do not approve a write unless the target path and proposed effect match what you asked for.
