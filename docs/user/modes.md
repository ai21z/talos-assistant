# Modes

Talos exposes four public modes:

| Mode | Purpose |
|---|---|
| `auto` | Selects the normal route for the request. |
| `ask` | Read-only answers and workspace inspection where policy allows. |
| `plan` | Read-only implementation planning. |
| `agent` | Edit-capable workspace work with approval gates. |

Legacy aliases such as `dev`, `chat`, and `unified` resolve to `agent` for compatibility. They are not advertised as separate public modes. `web` is reserved and not selectable.

Ask and Plan do not expose mutation tools. Agent can propose supported mutations, but writes still require approval.

## Which mode to use

- Use `ask` when you want an explanation, summary, or read-only workspace check.
- Use `plan` when you want a concrete implementation plan without changing files.
- Use `agent` when you want Talos to perform supported workspace work and ask before mutation.
- Use `auto` when you want Talos to choose the route for the request.

If you ask Ask or Plan to edit files, the correct behavior is a read-only refusal with a nudge to switch to Agent. That refusal is not a product failure.
